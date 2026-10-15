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
 * The camera stays silent until it receives the 4-byte hello `EF 00 04 00`,
 * then streams fragmented JPEG frames back on the same source port. The
 * hello is re-sent at ~10 Hz as a keepalive. After each completed frame we
 * send an ACK + REQ-next pair over the same socket; without it the camera
 * stops advancing img_number and the stream freezes.
 *
 * [Ne3FrameAssembler] parses the chunked wire format and extracts the Q
 * level, resolution and inline accelerometer reading per frame.
 * [Ne3JpegHeader.build] produces the SOI+DQT+SOF0+DHT+SOS prelude the
 * camera strips before transmission, patched to the live resolution; the
 * caller appends an `FF D9` EOI (the firmware doesn't emit one) and feeds
 * the result to [JpegDecoder].
 */
internal class Ne3VideoClient(
    private val cameraIp: String,
    private val network: Network?,
    private val parser: Ne3SensorParser,
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
        // Comfortably larger than any single camera datagram (~1.1 KB);
        // undersizing silently truncates JPEG chunks.
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
            var firstFrameLogged = false
            var firstCtlMsgLogged = false
            val loggedOtherTypes = mutableSetOf<Int>()
            val counters = VideoCounters()
            // Logs one line per Q change: a stable camera prints once.
            var lastReportedQ: Int? = null

            while (scope.isActive) {
                packet.length = recvBuf.size
                try {
                    sock.receive(packet)
                } catch (_: SocketTimeoutException) {
                    counters.reportIfDue()
                    continue
                } catch (e: Exception) {
                    _stats.update { it.copy(lastError = e.message) }
                    Log.w(TAG, "recv: ${e.message}")
                    continue
                }

                val len = packet.length
                counters.packets++
                _stats.update {
                    it.copy(
                        packetsReceived = it.packetsReceived + 1,
                        bytesReceived = it.bytesReceived + len,
                    )
                }

                if (!firstPacketLogged) {
                    firstPacketLogged = true
                    Log.i(TAG, "first packet $len bytes: ${recvBuf.toHex(0, minOf(len, 48))}")
                }

                when (val outcome = assembler.feed(recvBuf, len)) {
                    is Ne3FrameAssembler.Outcome.Frame -> {
                        val jpeg = Ne3JpegHeader.build(
                            outcome.qualityLevel, outcome.width, outcome.height,
                        ) + outcome.scan + EOI_MARKER
                        if (!firstFrameLogged) {
                            firstFrameLogged = true
                            lastReportedQ = outcome.qualityLevel
                            Log.i(
                                TAG,
                                "first complete frame: cameraQ=${outcome.qualityLevel} " +
                                    "${outcome.width}x${outcome.height} scan=${outcome.scan.size}B " +
                                    "img#${outcome.imgNumber} accel=(${outcome.accelX},${outcome.accelY})",
                            )
                        } else if (outcome.qualityLevel != lastReportedQ) {
                            Log.i(TAG, "cameraQ transition: $lastReportedQ -> ${outcome.qualityLevel} (${outcome.width}x${outcome.height})")
                            lastReportedQ = outcome.qualityLevel
                        }
                        val x = outcome.accelX
                        val y = outcome.accelY
                        if (x != null && y != null) {
                            parser.setRotationFromInlineAccel(x, y)
                        }
                        val bmp = JpegDecoder.decode(jpeg)
                        if (bmp != null) {
                            counters.frames++
                            _frames.tryEmit(bmp)
                            _stats.update { it.copy(framesReceived = it.framesReceived + 1) }
                        } else {
                            counters.drops++
                            _stats.update { it.copy(
                                framesDropped = it.framesDropped + 1,
                                lastError = "decode failed (scan=${outcome.scan.size}B)",
                            ) }
                            if (counters.drops <= FAILED_DECODE_HEX_LIMIT) {
                                Log.w(TAG, "decode failed on scan[${outcome.scan.size}B], head=${outcome.scan.toHex(0, minOf(outcome.scan.size, 24))}")
                            }
                        }
                        sendAckAndReq(sock, videoAddr, outcome.imgNumber)
                    }
                    Ne3FrameAssembler.Outcome.Dropped -> {
                        counters.drops++
                        _stats.update { it.copy(framesDropped = it.framesDropped + 1) }
                    }
                    is Ne3FrameAssembler.Outcome.CtlMsg -> {
                        counters.other++
                        if (!firstCtlMsgLogged) {
                            firstCtlMsgLogged = true
                            // Camera firmware we've tested doesn't volunteer
                            // ctlmsg packets; the first-arrival hex dump is
                            // kept so a future firmware revision that does
                            // can be decoded from captured logs.
                            Log.i(TAG, "first ctlmsg seq=${outcome.seq} payload[${outcome.payload.size}B]=${outcome.payload.toHex(0, minOf(outcome.payload.size, 48))}")
                        }
                    }
                    is Ne3FrameAssembler.Outcome.Other -> {
                        counters.other++
                        // msg_type 2 = ack, 8 = queryinfo-resp. Log once per
                        // type so unknown payloads surface for later decoding
                        // without a chatty ACK stream flooding the log.
                        if (loggedOtherTypes.add(outcome.msgType)) {
                            Log.i(TAG, "msg_type=${outcome.msgType} payload[${outcome.payload.size}B]=${outcome.payload.toHex(0, minOf(outcome.payload.size, 32))}")
                        }
                    }
                    Ne3FrameAssembler.Outcome.Invalid -> counters.invalid++
                    Ne3FrameAssembler.Outcome.Building -> Unit
                }

                counters.reportIfDue()
            }
        } finally {
            hello.cancel()
        }
    }

    /** Builds and sends the ACK + REQ-next-frame control packet. Wire format:
     *
     *  ```
     *  outer:   [ EF 02 ][ u16 LE total_len ][ payload ]
     *  payload: 02 02 00 01
     *           [ u64 LE msg_count ]
     *           00 00 00 00 00 00 00 00
     *           0a 4b 14 2d 00 00 00 00
     *           [ msg_count × message blocks ]
     *           00 00 00 00 00 00 00 00
     *
     *  ack msg: [ u64 LE img_number ] 01 00 00 00 14 00 00 00 ff ff ff ff
     *  req msg: [ u64 LE img_number ] 03 00 00 00 10 00 00 00
     *  ``` */
    private fun sendAckAndReq(sock: DatagramSocket, dest: InetSocketAddress, imgNumber: Long) {
        val ack = ByteArray(20).apply {
            writeU64LE(this, 0, imgNumber)
            System.arraycopy(ACK_MSG_TAIL, 0, this, 8, ACK_MSG_TAIL.size)
        }
        val req = ByteArray(16).apply {
            writeU64LE(this, 0, imgNumber + 1)
            System.arraycopy(REQ_MSG_TAIL, 0, this, 8, REQ_MSG_TAIL.size)
        }
        val payload = ByteArray(4 + 8 + 8 + 8 + ack.size + req.size + 8).also { p ->
            System.arraycopy(PAYLOAD_PREFIX, 0, p, 0, PAYLOAD_PREFIX.size)
            writeU64LE(p, 4, 2L)  // msg_count: ACK + REQ
            // Bytes 12..19 stay zero (padding), 20..27 is the vendor filler.
            System.arraycopy(PAYLOAD_MIDDLE, 0, p, 20, PAYLOAD_MIDDLE.size)
            var at = 28
            System.arraycopy(ack, 0, p, at, ack.size); at += ack.size
            System.arraycopy(req, 0, p, at, req.size); at += req.size
            // Trailing 8 zero bytes stay zero from allocation.
        }
        val total = 4 + payload.size
        val dg = ByteArray(total).also { d ->
            d[0] = 0xEF.toByte()
            d[1] = 0x02
            d[2] = (total and 0xff).toByte()
            d[3] = ((total ushr 8) and 0xff).toByte()
            System.arraycopy(payload, 0, d, 4, payload.size)
        }
        runCatching {
            sock.send(DatagramPacket(dg, dg.size, dest))
        }.onFailure { Log.w(TAG, "ack/req send failed: ${it.message}") }
    }

    private fun writeU64LE(b: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) b[off + i] = ((v ushr (i * 8)) and 0xff).toByte()
    }

    /** Rolling tally of one [REPORT_INTERVAL_MS] window. [reportIfDue]
     *  flushes a summary log line and resets the fields in one step. */
    private class VideoCounters {
        var packets = 0L
        var frames = 0L
        var drops = 0L
        var invalid = 0L
        var other = 0L
        private var windowStartMs = System.currentTimeMillis()

        fun reportIfDue() {
            val now = System.currentTimeMillis()
            val elapsed = now - windowStartMs
            if (elapsed < REPORT_INTERVAL_MS) return
            Log.i(
                TAG,
                "video: ${packets}pkt (%.1f pkt/s), ${frames}frame (%.1f fps), drops=$drops, invalid=$invalid, other=$other".format(
                    packets * 1000.0 / elapsed, frames * 1000.0 / elapsed,
                ),
            )
            packets = 0; frames = 0; drops = 0; invalid = 0; other = 0
            windowStartMs = now
        }
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

        /** Hello packet the camera waits for before streaming. */
        private val HELLO: ByteArray = byteArrayOf(0xEF.toByte(), 0x00, 0x04, 0x00)

        /** Hello / keepalive cadence. The camera drops back to idle after
         *  roughly 2 s of silence from the client. */
        private const val HELLO_INTERVAL_MS: Long = 100L

        /** Summary-log cadence for [VideoCounters]. */
        private const val REPORT_INTERVAL_MS: Long = 5_000L

        /** Cap on per-session "failed decode" hex dumps. */
        private const val FAILED_DECODE_HEX_LIMIT: Long = 3L

        /** JPEG end-of-image marker. The camera doesn't emit one; appending
         *  it ourselves lets the decoder finalise cleanly. */
        private val EOI_MARKER: ByteArray = byteArrayOf(0xFF.toByte(), 0xD9.toByte())

        /** ACK/REQ wire-format constants; byte layout documented on
         *  [sendAckAndReq]. */
        private val PAYLOAD_PREFIX: ByteArray = byteArrayOf(0x02, 0x02, 0x00, 0x01)
        private val PAYLOAD_MIDDLE: ByteArray = byteArrayOf(0x0A, 0x4B, 0x14, 0x2D, 0x00, 0x00, 0x00, 0x00)
        private val ACK_MSG_TAIL: ByteArray = byteArrayOf(
            0x01, 0x00, 0x00, 0x00, 0x14, 0x00, 0x00, 0x00,
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
        )
        private val REQ_MSG_TAIL: ByteArray = byteArrayOf(
            0x03, 0x00, 0x00, 0x00, 0x10, 0x00, 0x00, 0x00,
        )
    }
}
