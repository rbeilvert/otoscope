package dev.rubec.otoscope.stream.earfairy

import android.graphics.Bitmap
import android.net.Network
import dev.rubec.otoscope.debug.FileLog as Log
import dev.rubec.otoscope.stream.JpegDecoder
import dev.rubec.otoscope.stream.SessionStats
import dev.rubec.otoscope.stream.TerminalErrors
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.SocketFactory
import kotlin.coroutines.coroutineContext

/**
 * Video-stream client for EarFairy cameras.
 *
 * Speaks RTSP over TCP for the handshake (OPTIONS / DESCRIBE / SETUP / PLAY)
 * and receives MJPEG-over-RTP (payload type 26, RFC 2435) on a dedicated UDP
 * socket. The camera advertises PT 26 in its SDP and rejects TCP-interleaved
 * SETUP with 461, so UDP is the only working transport. Media3's built-in
 * RTSP source doesn't implement PT 26 either — hence the hand-rolled
 * depacketizer in [RtpJpegDepacketizer].
 *
 * Lifecycle: [start] → collect [frames] → [close]. Not restartable.
 */
internal class EarFairyVideoClient(
    private val cameraIp: String,
    private val network: Network?,
    private val port: Int = 7070,
    private val path: String = "/webcam",
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var rtspSocket: Socket? = null
    private var udpRtp: DatagramSocket? = null
    private var udpRtcp: DatagramSocket? = null
    private var runJob: Job? = null

    private val _frames = MutableSharedFlow<Bitmap>(replay = 0, extraBufferCapacity = 2)
    val frames: SharedFlow<Bitmap> = _frames.asSharedFlow()

    private val _stats = MutableStateFlow(SessionStats())
    val stats: StateFlow<SessionStats> = _stats.asStateFlow()

    private val _terminalError = MutableStateFlow<String?>(null)
    val terminalError: StateFlow<String?> = _terminalError.asStateFlow()

    fun start() {
        if (runJob != null) return
        runJob = scope.launch { runSafe() }
    }

    fun close() {
        // Close the sockets first — the read loop throws and exits. Cancelling
        // the job after is a no-op most of the time.
        runCatching { udpRtp?.close() }
        runCatching { udpRtcp?.close() }
        runCatching { rtspSocket?.close() }
        udpRtp = null; udpRtcp = null; rtspSocket = null
        runJob?.cancel(); runJob = null
        scope.cancel()
    }

    /** Outer loop: run one RTSP session, and if it dies transiently, reopen
     *  it after a short pause. Wi-Fi hiccups on the camera's soft-AP are
     *  routine (packet loss, brief association drops); a single RTP-side
     *  read failure shouldn't kill the whole session, so we reconnect
     *  quickly instead of surfacing a fatal error.
     *
     *  Retry policy: a session is "stable" once it has run for
     *  [SESSION_STABLE_MS], which resets the failure counter. If we fail
     *  [MAX_CONSECUTIVE_FAILURES] times in a row without ever stabilising —
     *  camera powered off, wrong AP, VPN, whatever — we surface
     *  `CAMERA_UNREACHABLE` and give up. */
    private suspend fun runSafe() {
        var consecutiveFailures = 0
        while (coroutineContext.isActive) {
            val startedAt = SystemClock.elapsedRealtime()
            try {
                runOnce()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "RTSP session ended: ${e.javaClass.simpleName}: ${e.message}")
                _stats.update { it.copy(lastError = "${e.javaClass.simpleName}: ${e.message}") }
            }

            val ranMs = SystemClock.elapsedRealtime() - startedAt
            if (ranMs >= SESSION_STABLE_MS) {
                consecutiveFailures = 0
            } else {
                consecutiveFailures += 1
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    Log.e(TAG, "giving up after $consecutiveFailures consecutive failed sessions")
                    _terminalError.value = TerminalErrors.CAMERA_UNREACHABLE
                    return
                }
            }
            if (!coroutineContext.isActive) return
            Log.i(TAG, "restarting RTSP session in ${RESTART_DELAY_MS}ms (attempt ${consecutiveFailures + 1})")
            delay(RESTART_DELAY_MS)
        }
    }

    /** One end-to-end RTSP session: open sockets, handshake, drain RTP until
     *  the socket dies or we're cancelled, tear down. Owns all sockets it
     *  opens and always closes them on exit — including the `rtspSocket`/
     *  `udpRtp`/`udpRtcp` fields exposed to [close], which are cleared here
     *  so a subsequent restart starts from a clean slate. */
    private fun runOnce() {
        val baseUrl = "rtsp://$cameraIp:$port$path"

        val udp = openUdpPair() ?: throw RuntimeException("failed to bind an even/odd UDP port pair")
        udpRtp = udp.rtp
        udpRtcp = udp.rtcp

        val sock = openRtspSocket()
        rtspSocket = sock

        try {
            val rtsp = RtspClient(sock, baseUrl)
            rtsp.handshake(udp.transportHeader)
            Log.i(TAG, "RTSP handshake ok via UDP (client_port=${udp.rtpPort}-${udp.rtcpPort})")

            val depacket = RtpJpegDepacketizer()
            try {
                readRtp(udp.rtp, depacket)
            } finally {
                runCatching { rtsp.teardown() }
            }
        } finally {
            runCatching { udp.rtp.close() }
            runCatching { udp.rtcp.close() }
            runCatching { sock.close() }
            if (udpRtp === udp.rtp) udpRtp = null
            if (udpRtcp === udp.rtcp) udpRtcp = null
            if (rtspSocket === sock) rtspSocket = null
        }
    }

    private fun openRtspSocket(): Socket {
        val sf: SocketFactory = network?.socketFactory ?: SocketFactory.getDefault()
        val sock = sf.createSocket()
        try {
            sock.connect(InetSocketAddress(cameraIp, port), CONNECT_TIMEOUT_MS)
        } catch (e: Exception) {
            runCatching { sock.close() }
            throw e
        }
        sock.tcpNoDelay = true
        return sock
    }

    /** RTP is conventionally an even port, RTCP the next odd port. Some RTSP
     *  servers enforce that; we honour it by retrying an ephemeral bind until
     *  the OS hands us an even port, then claiming its `+1` sibling. */
    private fun openUdpPair(): UdpTransport? {
        for (attempt in 0 until UDP_PAIR_ATTEMPTS) {
            val rtp = DatagramSocket()
            val rtpPort = rtp.localPort
            if (rtpPort % 2 != 0) {
                rtp.close()
                continue
            }
            val rtcp = runCatching { DatagramSocket(rtpPort + 1) }.getOrNull()
            if (rtcp == null) {
                rtp.close()
                continue
            }
            runCatching { network?.bindSocket(rtp) }
            runCatching { network?.bindSocket(rtcp) }
            rtp.soTimeout = UDP_READ_TIMEOUT_MS
            return UdpTransport(
                rtp = rtp,
                rtcp = rtcp,
                rtpPort = rtpPort,
                rtcpPort = rtpPort + 1,
                transportHeader = "RTP/AVP;unicast;client_port=$rtpPort-${rtpPort + 1}",
            )
        }
        Log.w(TAG, "failed to bind an even/odd UDP port pair")
        return null
    }

    /** Read RTP directly off the UDP socket — each datagram is one RTP
     *  packet, no framing needed. Loops until [close] shuts the socket. */
    private fun readRtp(rtp: DatagramSocket, depacket: RtpJpegDepacketizer) {
        val buf = ByteArray(2048)
        val packet = DatagramPacket(buf, buf.size)
        while (!rtp.isClosed) {
            packet.length = buf.size
            try {
                rtp.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                if (rtp.isClosed) return
                Log.w(TAG, "RTP UDP recv: ${e.message}")
                return
            }
            _stats.update {
                it.copy(
                    packetsReceived = it.packetsReceived + 1,
                    bytesReceived = it.bytesReceived + packet.length,
                )
            }
            handleRtp(buf, packet.length, depacket)
        }
    }

    private fun handleRtp(rtp: ByteArray, len: Int, depacket: RtpJpegDepacketizer) {
        if (len < RTP_MIN_HEADER_SIZE) return
        // RTP header (RFC 3550 §5.1):
        //   byte0: V(2) P(1) X(1) CC(4)
        //   byte1: M(1) PT(7)
        //   byte2-3: sequence
        //   byte4-7: timestamp
        //   byte8-11: SSRC
        //   +4·CC CSRCs
        //   + extension header if X
        val b0 = rtp[0].toInt() and 0xff
        val b1 = rtp[1].toInt() and 0xff
        val cc = b0 and 0x0f
        val hasExtension = (b0 and 0x10) != 0
        val marker = (b1 and 0x80) != 0
        val payloadType = b1 and 0x7f

        if (payloadType != PAYLOAD_TYPE_JPEG) return

        var payloadStart = RTP_MIN_HEADER_SIZE + 4 * cc
        if (hasExtension) {
            if (len < payloadStart + 4) return
            val extLen32 = ((rtp[payloadStart + 2].toInt() and 0xff) shl 8) or
                (rtp[payloadStart + 3].toInt() and 0xff)
            payloadStart += 4 + 4 * extLen32
        }
        if (payloadStart > len) return

        val payload = rtp.copyOfRange(payloadStart, len)
        val jpeg = depacket.feed(payload, marker) ?: return
        val bmp = JpegDecoder.decode(jpeg)
        if (bmp != null) {
            _frames.tryEmit(bmp)
            _stats.update { it.copy(framesReceived = it.framesReceived + 1) }
        } else {
            _stats.update { it.copy(framesDropped = it.framesDropped + 1) }
        }
    }

    /** RTP + RTCP DatagramSockets bound to the camera network, plus the
     *  Transport header the RTSP SETUP request will announce. */
    private data class UdpTransport(
        val rtp: DatagramSocket,
        val rtcp: DatagramSocket,
        val rtpPort: Int,
        val rtcpPort: Int,
        val transportHeader: String,
    )

    companion object {
        private const val TAG = "EarFairyVideo"
        private const val CONNECT_TIMEOUT_MS = 3_000

        private const val RTP_MIN_HEADER_SIZE = 12
        private const val PAYLOAD_TYPE_JPEG = 26

        private const val UDP_PAIR_ATTEMPTS = 8
        private const val UDP_READ_TIMEOUT_MS = 1_000

        /** 500 ms pause between a failed session and the reconnect attempt.
         *  The camera-side RTSP server needs a beat to finish tearing down
         *  its old session state; reconnecting instantly can trip a
         *  "session already exists" error on the next SETUP. */
        private const val RESTART_DELAY_MS = 500L
        /** Any session that ran at least this long is considered "stable"
         *  and resets the consecutive-failure counter — a transient hiccup
         *  during otherwise-healthy playback shouldn't count as fatal. */
        private const val SESSION_STABLE_MS = 3_000L
        /** After this many back-to-back failed sessions with no stable run
         *  in between, we bail and let the caller show the "camera
         *  unreachable" state. */
        private const val MAX_CONSECUTIVE_FAILURES = 5
    }
}
