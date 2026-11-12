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
import kotlinx.coroutines.channels.Channel
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
import java.util.concurrent.atomic.AtomicLong

/**
 * NE3 video-stream client (Bouffalo Lab BL602 VII protocol on UDP/8800).
 *
 * Session lifecycle, closely mirroring the ne3-scope reference `client.py`:
 *  - Open the UDP socket, bind to the camera's network.
 *  - Send one null packet to `camera:1234` and one HELLO (`EF 00 04 00`) to
 *    `camera:8800`. The null packet seems to prime the camera-side NAT /
 *    listener; the HELLO tells the camera "client is here, start streaming".
 *  - Receive packets with a short (25 ms) timeout.
 *    * On fragment: feed [Ne3FrameAssembler]. On a complete frame, send
 *      ACK(img) + REQ(img+1) right away, then hand the scan to the decoder
 *      coroutine. The camera sends nothing new until that REQ arrives, so the
 *      receive loop never waits on a decode.
 *    * On a chunk of the frame we just completed (the camera hasn't seen our
 *      ACK yet): re-send the ACK + REQ, at most once per 25 ms.
 *    * On a chunk of a still-incomplete frame: send an empty `send_msgs([])`
 *      at most every 25 ms to nudge the camera to keep transmitting.
 *  - On recv timeout: either reconnect (silent ≥ 0.5 s → re-HELLO + reset
 *    assembler), re-send ACK+REQ if we're stuck on the last completed frame,
 *    or nudge otherwise. A camera that stays silent is torn down by the
 *    ViewModel's stall watchdog.
 *  - Send a null packet every 5 s regardless (keepalive).
 *
 * The decoder coroutine prepends the SOI+DQT+SOF0+DHT+SOS prelude the camera
 * strips before transmission ([Ne3JpegHeader.build]), appends `FF D9` EOI (the
 * firmware doesn't emit one) and feeds the result to [JpegDecoder]. Its queue
 * is conflated: if decoding falls behind, only the newest frame is kept.
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

    /** Completed frames waiting to be decoded; newest wins. */
    private val decodeQueue = Channel<Ne3FrameAssembler.Outcome.Frame>(Channel.CONFLATED)
    private val counters = VideoCounters()

    fun start() {
        if (runJob != null) return
        scope.launch(Dispatchers.Default) { runDecoder() }
        runJob = scope.launch {
            try {
                runStream()
            } catch (e: Exception) {
                Log.e(TAG, "stream loop crashed", e)
                _stats.update { it.copy(lastError = "${e.javaClass.simpleName}: ${e.message}") }
            } finally {
                decodeQueue.close()
            }
        }
    }

    private suspend fun runDecoder() {
        var failures = 0L
        for (frame in decodeQueue) {
            val jpeg = Ne3JpegHeader.build(frame.qualityLevel, frame.width, frame.height) +
                frame.scan + EOI_MARKER
            val bmp = JpegDecoder.decode(jpeg)
            if (bmp != null) {
                val x = frame.accelX
                val y = frame.accelY
                if (x != null && y != null) {
                    parser.setRotationFromInlineAccel(x, y)
                }
                counters.decoded.incrementAndGet()
                _frames.tryEmit(bmp)
                _stats.update { it.copy(framesReceived = it.framesReceived + 1) }
            } else {
                counters.decodeFailed.incrementAndGet()
                _stats.update { it.copy(
                    framesDropped = it.framesDropped + 1,
                    lastError = "decode failed (scan=${frame.scan.size}B)",
                ) }
                if (++failures <= FAILED_DECODE_HEX_LIMIT) {
                    Log.w(TAG, "decode failed on scan[${frame.scan.size}B], head=${frame.scan.toHex(0, minOf(frame.scan.size, 24))}")
                }
            }
        }
    }

    private fun runStream() {
        val sock = DatagramSocket().also { socket = it }
        bindOrTerminal(sock, network, TAG)?.let {
            _terminalError.value = it
            return
        }
        sock.soTimeout = SOCKET_TIMEOUT_MS
        // Comfortably larger than any single camera datagram (~1.1 KB);
        // undersizing silently truncates JPEG chunks.
        sock.receiveBufferSize = 4 * 1024 * 1024

        val cameraAddr = InetAddress.getByName(cameraIp)
        val videoAddr = InetSocketAddress(cameraAddr, videoPort)
        val nullAddr = InetSocketAddress(cameraAddr, NULL_PKT_PORT)
        Log.i(
            TAG,
            "runStream: cameraIp=$cameraIp videoPort=$videoPort " +
                "network=${network != null} localPort=${sock.localPort}",
        )

        sendNullPkt(sock, nullAddr)
        sendHello(sock, videoAddr)

        val assembler = Ne3FrameAssembler()
        val recvBuf = ByteArray(2048)
        val packet = DatagramPacket(recvBuf, recvBuf.size)
        var firstPacketLogged = false
        var firstFrameLogged = false
        var firstCtlMsgLogged = false
        val loggedOtherTypes = mutableSetOf<Int>()
        // Logs one line per Q change: a stable camera prints once.
        var lastReportedQ: Int? = null

        var lastRecvMs = System.currentTimeMillis()
        var lastReqMoreMs = 0L
        var lastAckMs = 0L
        var lastNullMs = System.currentTimeMillis()

        fun nudgeIfDue(now: Long) {
            if (now - lastReqMoreMs > REQ_MORE_INTERVAL_MS) {
                sendMsgs(sock, videoAddr, emptyList())
                lastReqMoreMs = now
            }
        }

        while (scope.isActive) {
            packet.length = recvBuf.size
            try {
                sock.receive(packet)
            } catch (_: SocketTimeoutException) {
                val now = System.currentTimeMillis()
                when {
                    now - lastRecvMs > RECONNECT_SILENCE_MS -> {
                        Log.i(TAG, "silent for ${now - lastRecvMs}ms, re-sending hello")
                        sendHello(sock, videoAddr)
                        assembler.reset()
                    }
                    // Stuck on the last completed frame: our REQ may be lost.
                    !assembler.inFlight && assembler.lastCompleted >= 0 -> {
                        sendAckAndReq(sock, videoAddr, assembler.lastCompleted)
                        lastAckMs = now
                    }
                    else -> {
                        sendMsgs(sock, videoAddr, emptyList())
                    }
                }
                counters.reportIfDue()
                continue
            } catch (e: Exception) {
                _stats.update { it.copy(lastError = e.message) }
                Log.w(TAG, "recv: ${e.message}")
                continue
            }

            val len = packet.length
            val now = System.currentTimeMillis()
            lastRecvMs = now
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
                    // ACK first: the camera idles until it gets the REQ.
                    sendAckAndReq(sock, videoAddr, outcome.imgNumber)
                    lastAckMs = now
                    counters.frames++
                    decodeQueue.trySend(outcome)
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
                }
                is Ne3FrameAssembler.Outcome.Duplicate -> {
                    counters.duplicates++
                    if (now - lastAckMs > REQ_MORE_INTERVAL_MS) {
                        sendAckAndReq(sock, videoAddr, outcome.imgNumber)
                        lastAckMs = now
                    }
                }
                Ne3FrameAssembler.Outcome.Building -> nudgeIfDue(now)
                Ne3FrameAssembler.Outcome.Dropped -> {
                    counters.drops++
                    _stats.update { it.copy(framesDropped = it.framesDropped + 1) }
                    nudgeIfDue(now)
                }
                is Ne3FrameAssembler.Outcome.CtlMsg -> {
                    counters.other++
                    if (!firstCtlMsgLogged) {
                        firstCtlMsgLogged = true
                        // Camera firmware we've tested doesn't volunteer
                        // ctlmsg packets; the first-arrival hex dump is kept
                        // so a future firmware revision that does can be
                        // decoded from captured logs.
                        Log.i(TAG, "first ctlmsg seq=${outcome.seq} payload[${outcome.payload.size}B]=${outcome.payload.toHex(0, minOf(outcome.payload.size, 48))}")
                    }
                }
                is Ne3FrameAssembler.Outcome.Other -> {
                    counters.other++
                    // msg_type 2 = ack, 8 = queryinfo-resp. Log once per type
                    // so unknown payloads surface for later decoding without
                    // a chatty ACK stream flooding the log.
                    if (loggedOtherTypes.add(outcome.msgType)) {
                        Log.i(TAG, "msg_type=${outcome.msgType} payload[${outcome.payload.size}B]=${outcome.payload.toHex(0, minOf(outcome.payload.size, 32))}")
                    }
                }
                Ne3FrameAssembler.Outcome.Invalid -> counters.invalid++
            }

            if (now - lastNullMs > NULL_PKT_INTERVAL_MS) {
                sendNullPkt(sock, nullAddr)
                lastNullMs = now
            }

            counters.reportIfDue()
        }
    }

    /** Builds and sends a control packet carrying zero or more messages.
     *  Wire format:
     *
     *  ```
     *  outer:   [ EF 02 ][ u16 LE total_len ][ payload ]
     *  payload: 02 02 00 01
     *           [ u64 LE msg_count ]
     *           00 00 00 00 00 00 00 00
     *           0a 4b 14 2d 00 00 00 00
     *           [ msg_count × message blocks ]
     *           00 00 00 00 00 00 00 00
     *  ```
     *
     *  See [ackMsg] / [reqMsg] for the per-message formats. An empty [msgs]
     *  list sends a "please keep transmitting" nudge the camera needs
     *  periodically while a frame is in flight. */
    private fun sendMsgs(sock: DatagramSocket, dest: InetSocketAddress, msgs: List<ByteArray>) {
        val msgsBytes = msgs.sumOf { it.size }
        val payload = ByteArray(4 + 8 + 8 + 8 + msgsBytes + 8).also { p ->
            System.arraycopy(PAYLOAD_PREFIX, 0, p, 0, PAYLOAD_PREFIX.size)
            writeU64LE(p, 4, msgs.size.toLong())
            // Bytes 12..19 stay zero (padding), 20..27 is the vendor filler.
            System.arraycopy(PAYLOAD_MIDDLE, 0, p, 20, PAYLOAD_MIDDLE.size)
            var at = 28
            for (m in msgs) { System.arraycopy(m, 0, p, at, m.size); at += m.size }
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
        }.onFailure { Log.w(TAG, "send_msgs(${msgs.size}) failed: ${it.message}") }
    }

    private fun sendAckAndReq(sock: DatagramSocket, dest: InetSocketAddress, imgNumber: Long) {
        sendMsgs(sock, dest, listOf(ackMsg(imgNumber), reqMsg(imgNumber + 1)))
    }

    private fun sendHello(sock: DatagramSocket, dest: InetSocketAddress) {
        runCatching {
            sock.send(DatagramPacket(HELLO, HELLO.size, dest))
        }.onFailure { Log.w(TAG, "hello send failed: ${it.message}") }
    }

    /** Fire-and-forget zero-length UDP packet to [dest]. The reference
     *  client sends one at startup and every 5 s; purpose is unclear but
     *  consistently preserved across cameras we've tested against. */
    private fun sendNullPkt(sock: DatagramSocket, dest: InetSocketAddress) {
        runCatching {
            sock.send(DatagramPacket(ByteArray(0), 0, dest))
        }.onFailure { Log.w(TAG, "null-pkt send failed: ${it.message}") }
    }

    /** Per-message ACK: `[ u64 LE img_number ] 01 00 00 00 14 00 00 00 ff ff ff ff` */
    private fun ackMsg(imgNumber: Long): ByteArray = ByteArray(20).apply {
        writeU64LE(this, 0, imgNumber)
        System.arraycopy(ACK_MSG_TAIL, 0, this, 8, ACK_MSG_TAIL.size)
    }

    /** Per-message REQ: `[ u64 LE img_number ] 03 00 00 00 10 00 00 00` */
    private fun reqMsg(imgNumber: Long): ByteArray = ByteArray(16).apply {
        writeU64LE(this, 0, imgNumber)
        System.arraycopy(REQ_MSG_TAIL, 0, this, 8, REQ_MSG_TAIL.size)
    }

    private fun writeU64LE(b: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) b[off + i] = ((v ushr (i * 8)) and 0xff).toByte()
    }

    /** Rolling tally of one [REPORT_INTERVAL_MS] window. [reportIfDue]
     *  flushes a summary log line and resets the fields in one step. The
     *  plain fields belong to the receive loop; the atomics are bumped by the
     *  decoder coroutine. `frames` counts assembled frames, `decoded` the
     *  ones that reached the screen. */
    private class VideoCounters {
        var packets = 0L
        var frames = 0L
        var drops = 0L
        var duplicates = 0L
        var invalid = 0L
        var other = 0L
        val decoded = AtomicLong()
        val decodeFailed = AtomicLong()
        private var windowStartMs = System.currentTimeMillis()

        fun reportIfDue() {
            val now = System.currentTimeMillis()
            val elapsed = now - windowStartMs
            if (elapsed < REPORT_INTERVAL_MS) return
            val shown = decoded.getAndSet(0)
            Log.i(
                TAG,
                ("video: ${packets}pkt (%.1f pkt/s), ${frames}frame (%.1f fps), ${shown}decoded (%.1f fps), " +
                    "decodeFailed=${decodeFailed.getAndSet(0)}, drops=$drops, dup=$duplicates, " +
                    "invalid=$invalid, other=$other").format(
                    packets * 1000.0 / elapsed, frames * 1000.0 / elapsed, shown * 1000.0 / elapsed,
                ),
            )
            packets = 0; frames = 0; drops = 0; duplicates = 0; invalid = 0; other = 0
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

        /** Secondary port the null keepalive packet is sent to. */
        private const val NULL_PKT_PORT: Int = 1234

        /** Short recv timeout — the main loop uses the timeout path to drive
         *  retransmits and keepalives. */
        private const val SOCKET_TIMEOUT_MS: Int = 25

        /** Minimum interval between in-progress "please keep sending" nudges. */
        private const val REQ_MORE_INTERVAL_MS: Long = 25

        /** Null keepalive-packet cadence. */
        private const val NULL_PKT_INTERVAL_MS: Long = 5_000

        /** Silence that triggers a HELLO re-send + assembler reset. */
        private const val RECONNECT_SILENCE_MS: Long = 500

        /** Summary-log cadence for [VideoCounters]. */
        private const val REPORT_INTERVAL_MS: Long = 5_000L

        /** Cap on per-session "failed decode" hex dumps. */
        private const val FAILED_DECODE_HEX_LIMIT: Long = 3L

        /** JPEG end-of-image marker. The camera doesn't emit one; appending
         *  it ourselves lets the decoder finalise cleanly. */
        private val EOI_MARKER: ByteArray = byteArrayOf(0xFF.toByte(), 0xD9.toByte())

        /** Fixed prefix of every `sendMsgs` payload. */
        private val PAYLOAD_PREFIX: ByteArray = byteArrayOf(0x02, 0x02, 0x00, 0x01)

        /** Fixed middle filler of every `sendMsgs` payload (purpose unknown;
         *  matches the vendor and reference clients byte-for-byte). */
        private val PAYLOAD_MIDDLE: ByteArray = byteArrayOf(0x0A, 0x4B, 0x14, 0x2D, 0x00, 0x00, 0x00, 0x00)

        /** Trailer bytes of an ACK message, following the u64 LE img_number. */
        private val ACK_MSG_TAIL: ByteArray = byteArrayOf(
            0x01, 0x00, 0x00, 0x00, 0x14, 0x00, 0x00, 0x00,
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
        )

        /** Trailer bytes of a REQ message, following the u64 LE img_number. */
        private val REQ_MSG_TAIL: ByteArray = byteArrayOf(
            0x03, 0x00, 0x00, 0x00, 0x10, 0x00, 0x00, 0x00,
        )
    }
}
