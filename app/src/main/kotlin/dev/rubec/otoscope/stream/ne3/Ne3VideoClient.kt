package dev.rubec.otoscope.stream.ne3

import android.graphics.Bitmap
import android.net.Network
import dev.rubec.otoscope.debug.FileLog as Log
import dev.rubec.otoscope.stream.JpegDecoder
import dev.rubec.otoscope.stream.SessionStats
import dev.rubec.otoscope.stream.bindOrTerminal
import dev.rubec.otoscope.stream.toHex
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
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * NE3 video-stream client (Bouffalo Lab BL602 VII protocol on UDP/8800).
 *
 * The camera is silent until it receives a 4-byte hello packet
 * `EF 00 04 00`; once it does, it starts pushing fragmented JPEG frames
 * back on the same source port. We keep re-sending the hello at ~10 Hz for
 * the life of the session so a transient packet drop can't leave the camera
 * waiting — the vendor SDK does the same, and switches to a heavier
 * per-frame ACK stream once frames start flowing. We ignore the ACK stream
 * for now: on hardware we've tested the camera keeps sending frames as
 * long as it hears from us, and the ACK carries per-frame quality
 * negotiation we don't yet drive.
 *
 * Frame assembly: [Ne3FrameAssembler] handles the chunked wire format;
 * [Ne3JpegHeader] prepends the SOI+DQT+SOF0+DHT+SOS prelude the camera
 * strips before transmission.
 */
internal class Ne3VideoClient(
    private val cameraIp: String,
    private val network: Network?,
    private val videoPort: Int = 8800,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: DatagramSocket? = null
    private var runJob: Job? = null

    private val _frames = MutableSharedFlow<Bitmap>(replay = 0, extraBufferCapacity = 2)
    val frames: SharedFlow<Bitmap> = _frames.asSharedFlow()

    private val _stats = MutableStateFlow(SessionStats())
    val stats: StateFlow<SessionStats> = _stats.asStateFlow()

    private val _terminalError = MutableStateFlow<String?>(null)
    val terminalError: StateFlow<String?> = _terminalError.asStateFlow()

    fun start() {
        if (runJob != null) return
        runJob = scope.launch {
            try {
                runStream()
            } catch (e: Exception) {
                Log.e(TAG, "stream loop crashed", e)
                _stats.update { it.copy(lastError = "${e.javaClass.simpleName}: ${e.message}") }
            }
        }
    }

    private suspend fun runStream() {
        val sock = DatagramSocket().also { socket = it }
        bindOrTerminal(sock, network, TAG)?.let {
            _terminalError.value = it
            return
        }
        sock.soTimeout = 1000
        // Room for the largest datagram we've seen (~1.1 KB) with generous
        // margin. Undersizing here silently truncates JPEG chunks.
        sock.receiveBufferSize = 4 * 1024 * 1024

        val cameraAddr = InetAddress.getByName(cameraIp)
        val videoAddr = InetSocketAddress(cameraAddr, videoPort)
        Log.i(
            TAG,
            "runStream: cameraIp=$cameraIp videoPort=$videoPort " +
                "network=${network != null} localPort=${sock.localPort}",
        )

        val hello = scope.launch {
            val helloPacket = DatagramPacket(HELLO, HELLO.size, videoAddr)
            while (isActive) {
                runCatching { sock.send(helloPacket) }
                    .onFailure { Log.w(TAG, "hello send failed: ${it.message}") }
                delay(HELLO_INTERVAL_MS)
            }
        }

        try {
            val assembler = Ne3FrameAssembler()
            val recvBuf = ByteArray(2048)
            val packet = DatagramPacket(recvBuf, recvBuf.size)
            var firstPacketLogged = false
            val loggedOtherTypes = mutableSetOf<Int>()

            // Rolling counters for the 5-second summary log. Give the
            // reporter enough of a heartbeat to tell "camera silent" from
            // "camera streaming but frames failing to decode" from
            // "everything's fine, ignore me".
            var pktSinceReport = 0L
            var framesSinceReport = 0L
            var dropsSinceReport = 0L
            var invalidSinceReport = 0L
            var otherSinceReport = 0L
            var startMs = System.currentTimeMillis()

            while (scope.isActive) {
                packet.length = recvBuf.size
                try {
                    sock.receive(packet)
                } catch (_: SocketTimeoutException) {
                    reportIfDue(startMs, pktSinceReport, framesSinceReport, dropsSinceReport, invalidSinceReport, otherSinceReport)
                        ?.let { newStart ->
                            startMs = newStart
                            pktSinceReport = 0; framesSinceReport = 0
                            dropsSinceReport = 0; invalidSinceReport = 0; otherSinceReport = 0
                        }
                    continue
                } catch (e: Exception) {
                    _stats.update { it.copy(lastError = e.message) }
                    Log.w(TAG, "recv: ${e.message}")
                    continue
                }

                val len = packet.length
                pktSinceReport++
                _stats.update {
                    it.copy(
                        packetsReceived = it.packetsReceived + 1,
                        bytesReceived = it.bytesReceived + len,
                    )
                }

                if (!firstPacketLogged) {
                    firstPacketLogged = true
                    Log.i(TAG, "first packet $len bytes: ${recvBuf.toHex(0, minOf(len, 32))}")
                }

                when (val outcome = assembler.feed(recvBuf, len)) {
                    is Ne3FrameAssembler.Outcome.Frame -> {
                        val jpeg = Ne3JpegHeader.Q75 + outcome.scan
                        val bmp = JpegDecoder.decode(jpeg)
                        if (bmp != null) {
                            framesSinceReport++
                            _frames.tryEmit(bmp)
                            _stats.update { it.copy(framesReceived = it.framesReceived + 1) }
                        } else {
                            dropsSinceReport++
                            _stats.update { it.copy(
                                framesDropped = it.framesDropped + 1,
                                lastError = "decode failed (scan=${outcome.scan.size}B)",
                            ) }
                            // Decode failures are the biggest single hint
                            // that the wrong Q header is prepended, so log
                            // the head of a failing scan so we can inspect it.
                            if (dropsSinceReport <= FAILED_DECODE_HEX_LIMIT) {
                                Log.w(TAG, "decode failed on scan[${outcome.scan.size}B], head=${outcome.scan.toHex(0, minOf(outcome.scan.size, 24))}")
                            }
                        }
                    }
                    Ne3FrameAssembler.Outcome.Dropped -> {
                        dropsSinceReport++
                        _stats.update { it.copy(framesDropped = it.framesDropped + 1) }
                    }
                    is Ne3FrameAssembler.Outcome.Other -> {
                        otherSinceReport++
                        // Log unfamiliar-but-known message types once per
                        // type so the reporter's log shows the payloads we
                        // haven't decoded yet (msg_type 2 = ack, 4 = mcu-ctl,
                        // 8 = queryinfo). Rate-limit so a chatty ACK loop
                        // doesn't flood the file.
                        if (loggedOtherTypes.add(outcome.msgType)) {
                            Log.i(TAG, "msg_type=${outcome.msgType} payload[${outcome.payload.size}B]=${outcome.payload.toHex(0, minOf(outcome.payload.size, 32))}")
                        }
                    }
                    Ne3FrameAssembler.Outcome.Invalid -> invalidSinceReport++
                    Ne3FrameAssembler.Outcome.Building -> Unit
                }

                reportIfDue(startMs, pktSinceReport, framesSinceReport, dropsSinceReport, invalidSinceReport, otherSinceReport)
                    ?.let { newStart ->
                        startMs = newStart
                        pktSinceReport = 0; framesSinceReport = 0
                        dropsSinceReport = 0; invalidSinceReport = 0; otherSinceReport = 0
                    }
            }
        } finally {
            hello.cancel()
        }
    }

    /** Print a summary line once [REPORT_INTERVAL_MS] have passed since
     *  [startMs]. Returns the new startMs when a report fired, null
     *  otherwise, so the caller can reset its counters in step. */
    private fun reportIfDue(
        startMs: Long,
        pkts: Long, frames: Long, drops: Long, invalid: Long, other: Long,
    ): Long? {
        val now = System.currentTimeMillis()
        val elapsed = now - startMs
        if (elapsed < REPORT_INTERVAL_MS) return null
        val pktHz = pkts * 1000.0 / elapsed
        val fps = frames * 1000.0 / elapsed
        Log.i(
            TAG,
            "video: ${pkts}pkt (%.1f pkt/s), ${frames}frame (%.1f fps), drops=$drops, invalid=$invalid, other=$other".format(pktHz, fps),
        )
        return now
    }

    fun close() {
        runJob?.cancel()
        runJob = null
        runCatching { socket?.close() }
        socket = null
        scope.cancel()
    }

    companion object {
        private const val TAG = "Ne3VideoClient"

        /** Hello / start-streaming packet the camera waits for. Same 4 bytes
         *  the vendor SDK spams at boot until the camera replies. */
        private val HELLO: ByteArray = byteArrayOf(0xEF.toByte(), 0x00, 0x04, 0x00)

        /** ~10 Hz — matches the vendor SDK's boot-loop cadence. Doubles as
         *  a keepalive; if the camera goes ~2 s without hearing from us it
         *  drops back into an idle "waiting for client" state. */
        private const val HELLO_INTERVAL_MS: Long = 100L

        /** Once every 5 s we flush a video-channel summary — enough to
         *  make a debug log show whether the stream is healthy without
         *  drowning the file. */
        private const val REPORT_INTERVAL_MS: Long = 5_000L

        /** Cap the "here's the head of a failing scan" log spam to the
         *  first N failures per session. One or two head dumps are all we
         *  need to diagnose a wrong-header issue. */
        private const val FAILED_DECODE_HEX_LIMIT: Long = 3L
    }
}
