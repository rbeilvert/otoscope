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
 * waiting — the vendor SDK does the same.
 *
 * Frame assembly: [Ne3FrameAssembler] parses the chunked wire format and
 * extracts the per-frame Q level, width and height; [Ne3JpegHeader.build]
 * assembles the SOI+DQT+SOF0+DHT+SOS prelude the camera strips before
 * transmission, patching SOF0 to the live resolution each frame.
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

        // Writes the first [RAW_PAYLOAD_DUMP_COUNT] UDP datagrams to a single
        // binary file next to the debug log so we can replay the stream
        // through [Ne3FrameAssembler] in a local JVM test and iterate on the
        // decoder without needing a camera on the bench. Declared outside the
        // try so the finally can close it even if the loop throws.
        val rawDump = RawPayloadDump.openNextTo(Log.path(), RAW_PAYLOAD_DUMP_COUNT)

        try {
            val assembler = Ne3FrameAssembler()
            val recvBuf = ByteArray(2048)
            val packet = DatagramPacket(recvBuf, recvBuf.size)
            var firstPacketLogged = false
            var firstFrameLogged = false
            var firstCtlMsgLogged = false
            val loggedOtherTypes = mutableSetOf<Int>()
            val counters = VideoCounters()
            // Keeps rolling track of the camera's advertised Q so a transition
            // (which recomputes the DQT tables used for decode, visibly
            // changing brightness/colour) prints exactly one log line each
            // time it happens. A stable camera should log once, at the first
            // frame, and never again.
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

                rawDump?.record(recvBuf, len)

                if (!firstPacketLogged) {
                    firstPacketLogged = true
                    Log.i(TAG, "first packet $len bytes: ${recvBuf.toHex(0, minOf(len, 48))}")
                }

                when (val outcome = assembler.feed(recvBuf, len)) {
                    is Ne3FrameAssembler.Outcome.Frame -> {
                        val jpeg = Ne3JpegHeader.build(
                            outcome.qualityLevel, outcome.width, outcome.height,
                        ) + outcome.scan
                        if (!firstFrameLogged) {
                            firstFrameLogged = true
                            lastReportedQ = outcome.qualityLevel
                            Log.i(
                                TAG,
                                "first complete frame: cameraQ=${outcome.qualityLevel} " +
                                    "${outcome.width}x${outcome.height} scan=${outcome.scan.size}B",
                            )
                        } else if (outcome.qualityLevel != lastReportedQ) {
                            Log.i(TAG, "cameraQ transition: $lastReportedQ -> ${outcome.qualityLevel} (${outcome.width}x${outcome.height})")
                            lastReportedQ = outcome.qualityLevel
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
                    }
                    Ne3FrameAssembler.Outcome.Dropped -> {
                        counters.drops++
                        _stats.update { it.copy(framesDropped = it.framesDropped + 1) }
                    }
                    is Ne3FrameAssembler.Outcome.CtlMsg -> {
                        counters.other++
                        if (!firstCtlMsgLogged) {
                            firstCtlMsgLogged = true
                            // The ctlmsg channel is the UDP path for sensor
                            // frames (rotation / shutter). We don't know yet
                            // what triggers the camera to start sending them;
                            // logging the first lets a reporter confirm the
                            // wire format on new firmware revisions.
                            Log.i(TAG, "first ctlmsg seq=${outcome.seq} payload[${outcome.payload.size}B]=${outcome.payload.toHex(0, minOf(outcome.payload.size, 48))}")
                        }
                        parser.feed(outcome.payload, outcome.payload.size)
                    }
                    is Ne3FrameAssembler.Outcome.Other -> {
                        counters.other++
                        // msg_type 2 = ack, 8 = queryinfo-resp. mcu-ctl has
                        // its own CtlMsg branch above. Log the first of each
                        // remaining type so a reporter's log surfaces payloads
                        // we haven't decoded yet, without flooding on a chatty
                        // ACK stream.
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
            rawDump?.close()
        }
    }

    /** Rolling tally of a 5-second window of the receive loop. Collapses
     *  into one summary log line via [reportIfDue] when the window is up
     *  and resets itself in the same step, so the caller never has to
     *  hand-reset the fields. The log gives the reporter enough of a
     *  heartbeat to tell "camera silent" from "camera streaming but frames
     *  failing to decode" from "everything's fine, ignore me". */
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

        /** How many raw UDP datagrams to dump at session start for offline
         *  decoder debugging. 15 covers two or three frames (with a few
         *  chunks each) at a few KB total — enough to replay a short burst
         *  through [Ne3FrameAssembler] in a JVM test, small enough to not
         *  bloat the reporter's debug directory. */
        private const val RAW_PAYLOAD_DUMP_COUNT: Int = 15
    }

    /** Writes a bounded number of UDP datagrams to one length-prefixed
     *  binary file so a reporter's capture can be fed back into
     *  [Ne3FrameAssembler] in a JVM test. File format: for each packet,
     *  a 2-byte little-endian length followed by that many payload bytes.
     *  Reading side (Python / Kotlin):
     *
     *  ```python
     *  with open('ne3-raw-packets.bin', 'rb') as f:
     *      while (hdr := f.read(2)):
     *          n = int.from_bytes(hdr, 'little')
     *          packet = f.read(n)
     *          # feed packet to the assembler
     *  ```
     *
     *  Stops writing after [budget] packets. All I/O failures are swallowed
     *  with a single log line so the stream loop never crashes on a dead
     *  disk. */
    private class RawPayloadDump private constructor(
        private val stream: java.io.OutputStream,
        private val path: String,
        private var budget: Int,
    ) {

        fun record(buf: ByteArray, len: Int) {
            if (budget <= 0) return
            runCatching {
                stream.write(len and 0xff)
                stream.write((len ushr 8) and 0xff)
                stream.write(buf, 0, len)
                stream.flush()
            }.onFailure {
                Log.w(TAG, "raw-payload dump write failed: ${it.message}")
                budget = 0
            }
            budget--
            if (budget == 0) {
                Log.i(TAG, "raw-payload dump complete: $path")
                runCatching { stream.close() }
            }
        }

        fun close() {
            runCatching { stream.close() }
        }

        companion object {
            /** Open a dump next to the given debug-log path, named
             *  `ne3-raw-packets.bin` and overwriting any previous one. Returns
             *  null when the log directory can't be resolved (test runs,
             *  disabled FileLog) — the caller treats null as "diagnostic
             *  disabled" and skips recording. */
            fun openNextTo(logPath: String?, budget: Int): RawPayloadDump? {
                if (logPath == null || budget <= 0) return null
                val dir = java.io.File(logPath).parentFile ?: return null
                val target = java.io.File(dir, "ne3-raw-packets.bin")
                return runCatching {
                    RawPayloadDump(target.outputStream().buffered(), target.absolutePath, budget)
                }.getOrElse {
                    Log.w(TAG, "raw-payload dump open failed: ${it.message}")
                    null
                }
            }
        }
    }
}
