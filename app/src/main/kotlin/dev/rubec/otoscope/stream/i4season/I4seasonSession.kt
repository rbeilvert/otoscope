package dev.rubec.otoscope.stream.i4season

import android.graphics.Bitmap
import android.net.Network
import android.os.SystemClock
import dev.rubec.otoscope.debug.FileLog as Log
import dev.rubec.otoscope.stream.BatteryStatus
import dev.rubec.otoscope.stream.CameraSession
import dev.rubec.otoscope.stream.JpegDecoder
import dev.rubec.otoscope.stream.LedControl
import dev.rubec.otoscope.stream.SessionStats
import dev.rubec.otoscope.stream.bindOrTerminal
import dev.rubec.otoscope.stream.toHex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * i4season (Soulear) camera session. Two UDP sockets bound to the camera network:
 *
 *  - **Control**: request/response datagrams (see [I4seasonProtocol]). We read
 *    device info (model, firmware, battery), then send "open video" with the
 *    video socket's local port. The camera has no separate heartbeat: like the
 *    vendor app, we re-send "open video" whenever no video packet arrived for
 *    [VIDEO_STALL_MS], and poll device info every [DEVINFO_INTERVAL_MS] for
 *    the battery level.
 *  - **Video**: JPEG chunks pushed by the camera to the port we announced.
 *    Reassembly is in [FrameAssembler]; each frame carries an accelerometer
 *    sample that drives [rotation].
 *
 * The ring light is switched and dimmed over the control channel (see [led]).
 */
class I4seasonSession(
    private val cameraIp: String,
    private val network: Network?,
) : CameraSession {

    private val _frames = MutableSharedFlow<Bitmap>(replay = 0, extraBufferCapacity = 2)
    override val frames: SharedFlow<Bitmap> = _frames.asSharedFlow()

    private val _rotation = MutableStateFlow(0f)
    override val rotation: StateFlow<Float> = _rotation.asStateFlow()

    private val _battery = MutableStateFlow<BatteryStatus?>(null)
    override val battery: StateFlow<BatteryStatus?> = _battery.asStateFlow()

    private val _model = MutableStateFlow<String?>(null)
    override val model: StateFlow<String?> = _model.asStateFlow()

    private val _stats = MutableStateFlow(SessionStats())
    override val stats: StateFlow<SessionStats> = _stats.asStateFlow()

    private val _diagnostics = MutableStateFlow<Map<String, String>>(emptyMap())
    override val diagnostics: StateFlow<Map<String, String>> = _diagnostics.asStateFlow()

    private val _terminalError = MutableStateFlow<String?>(null)
    override val terminalError: StateFlow<String?> = _terminalError.asStateFlow()

    // Like the vendor app, we switch the light on at connect time.
    private val _ledEnabled = MutableStateFlow(true)

    /** Latest requested light state. A StateFlow so quick toggling collapses
     *  into one command for the final state. */
    private val ledRequest = MutableStateFlow<Boolean?>(null)

    override val led: LedControl = object : LedControl {
        override val enabled: StateFlow<Boolean> get() = _ledEnabled
        override fun setEnabled(on: Boolean) {
            _ledEnabled.value = on
            ledRequest.value = on
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Serialises control requests: the control loop and LED commands share
     *  one socket and one sequence counter. */
    private val requestLock = Mutex()
    private var videoSocket: DatagramSocket? = null
    private var controlSocket: DatagramSocket? = null
    private var cameraAddr: InetAddress? = null

    /** Identifies this client to the camera, which uses it to report
     *  "in use by another phone". The vendor library takes `time(NULL)` once. */
    private val clientId = (System.currentTimeMillis() / 1000).toInt()
    private var seq = 0

    @Volatile private var lastVideoPacketAt = 0L
    @Volatile private var mountOffset = 0f
    private var resolution: String? = null

    override fun start() {
        if (videoSocket != null) return
        cameraAddr = try {
            InetAddress.getByName(cameraIp)
        } catch (e: Exception) {
            Log.e(TAG, "resolving $cameraIp failed", e)
            _stats.update { it.copy(lastError = "address: ${e.message}") }
            return
        }

        val video = DatagramSocket()
        videoSocket = video
        bindOrTerminal(video, network, TAG)?.let {
            _terminalError.value = it
            return
        }
        video.soTimeout = 1000
        video.receiveBufferSize = 4 * 1024 * 1024

        val control = DatagramSocket()
        controlSocket = control
        bindOrTerminal(control, network, TAG)?.let {
            _terminalError.value = it
            return
        }
        control.soTimeout = REPLY_TIMEOUT_MS

        Log.i(TAG, "start: cameraIp=$cameraIp videoPort=${video.localPort} network=${network != null}")
        scope.launch {
            try {
                runVideo(video)
            } catch (e: Exception) {
                Log.e(TAG, "video loop crashed", e)
                _stats.update { it.copy(lastError = "${e.javaClass.simpleName}: ${e.message}") }
            }
        }
        scope.launch {
            try {
                runControl(control, video.localPort)
            } catch (e: Exception) {
                Log.e(TAG, "control loop crashed", e)
            }
        }
    }

    private suspend fun runControl(sock: DatagramSocket, picPort: Int) {
        queryDevInfo(sock)
        // The vendor app always reads the licence before opening video. The
        // camera doesn't seem to care, but mirroring the sequence is cheap.
        request(sock, I4seasonProtocol.CMD_LICGET, port = I4seasonProtocol.CMD_PORT, attempts = 3)

        request(sock, I4seasonProtocol.CMD_LED, I4seasonProtocol.ledReadPayload(), I4seasonProtocol.CMD_PORT)
            ?.let { I4seasonProtocol.parseLed(it.payload) }
            ?.let { Log.i(TAG, "led at connect: $it") }
        led.setEnabled(true)
        scope.launch {
            ledRequest.filterNotNull().collect { wanted -> sendLed(sock, wanted) }
        }

        val openVideo = I4seasonProtocol.openVideoPayload(picPort, clientId)
        var lastOpen = 0L
        var lastDevInfo = SystemClock.elapsedRealtime()
        while (scope.isActive) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastVideoPacketAt > VIDEO_STALL_MS && now - lastOpen > VIDEO_STALL_MS) {
                val reply = request(sock, I4seasonProtocol.CMD_OPEN_VIDEO, openVideo, I4seasonProtocol.VIDEO_CMD_PORT)
                Log.i(TAG, "open video -> ${reply?.let { "status=${it.status}" } ?: "no reply"}")
                lastOpen = now
            }
            if (now - lastDevInfo >= DEVINFO_INTERVAL_MS) {
                queryDevInfo(sock)
                lastDevInfo = now
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun sendLed(sock: DatagramSocket, on: Boolean) {
        val payload = I4seasonProtocol.ledWritePayload(on)
        val reply = request(sock, I4seasonProtocol.CMD_LED, payload, I4seasonProtocol.CMD_PORT)
        val state = reply?.let { I4seasonProtocol.parseLed(it.payload) }
        Log.i(TAG, "led set on=$on -> ${reply?.let { "status=${it.status} state=$state" } ?: "no reply"}")
    }

    private suspend fun queryDevInfo(sock: DatagramSocket) {
        val reply = request(sock, I4seasonProtocol.CMD_DEVINFO, port = I4seasonProtocol.CMD_PORT) ?: return
        val info = I4seasonProtocol.parseDevInfo(reply.payload) ?: return
        if (_model.value == null) Log.i(TAG, "devinfo: $info")
        mountOffset = I4seasonProtocol.mountOffsetDegrees(info.product)
        _model.value = info.product.ifEmpty { info.ssid }
        // The status word has no charging bit we could confirm yet.
        _battery.value = BatteryStatus(
            percent = info.batteryPercent,
            charging = false,
            full = info.batteryPercent >= 100,
        )
        publishDiagnostics(info)
    }

    private fun publishDiagnostics(info: I4seasonProtocol.DevInfo? = null) {
        _diagnostics.update { old ->
            val next = LinkedHashMap(old)
            if (info != null) {
                next["Vendor"] = info.vendor
                next["Firmware"] = info.firmware
            }
            resolution?.let { next["Resolution"] = it }
            next
        }
    }

    /** Send one command and wait for the reply with the matching sequence
     *  number. Blocks the calling IO thread while holding [requestLock]. */
    private suspend fun request(
        sock: DatagramSocket,
        cmd: Int,
        payload: ByteArray = ByteArray(0),
        port: Int,
        attempts: Int = REQUEST_ATTEMPTS,
    ): I4seasonProtocol.Reply? = requestLock.withLock {
        requestLocked(sock, cmd, payload, port, attempts)
    }

    private fun requestLocked(
        sock: DatagramSocket,
        cmd: Int,
        payload: ByteArray,
        port: Int,
        attempts: Int,
    ): I4seasonProtocol.Reply? {
        val addr = cameraAddr ?: return null
        val mySeq = seq
        seq = (seq + 1) and 0xffff
        val out = I4seasonProtocol.request(mySeq, cmd, payload)
        val sendPacket = DatagramPacket(out, out.size, addr, port)
        val buf = ByteArray(4096)
        val recvPacket = DatagramPacket(buf, buf.size)
        repeat(attempts) {
            try {
                sock.send(sendPacket)
                while (true) {
                    recvPacket.length = buf.size
                    sock.receive(recvPacket)
                    val reply = I4seasonProtocol.parseReply(buf, recvPacket.length) ?: continue
                    // Late replies to earlier retries carry an older seq; skip them.
                    if (reply.seq == mySeq && reply.cmd == cmd) return reply
                }
            } catch (_: SocketTimeoutException) {
                // Retry.
            } catch (e: Exception) {
                if (sock.isClosed) return null
                Log.w(TAG, "cmd 0x${cmd.toString(16)}: ${e.message}")
            }
        }
        return null
    }

    private fun runVideo(sock: DatagramSocket) {
        val assembler = FrameAssembler()
        val buf = ByteArray(8192)
        val packet = DatagramPacket(buf, buf.size)
        var firstPacketLogged = false

        while (scope.isActive && !sock.isClosed) {
            packet.length = buf.size
            try {
                sock.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                if (sock.isClosed) return
                _stats.update { it.copy(lastError = e.message) }
                Log.w(TAG, "video recv: ${e.message}")
                continue
            }
            val len = packet.length
            lastVideoPacketAt = SystemClock.elapsedRealtime()
            _stats.update {
                it.copy(packetsReceived = it.packetsReceived + 1, bytesReceived = it.bytesReceived + len)
            }
            if (!firstPacketLogged) {
                firstPacketLogged = true
                Log.i(TAG, "first packet $len bytes: ${buf.toHex(0, minOf(len, 32))}")
            }
            when (val outcome = assembler.feed(buf, len)) {
                is FrameAssembler.Outcome.Frame -> emitFrame(outcome)
                FrameAssembler.Outcome.Dropped ->
                    _stats.update { it.copy(framesDropped = it.framesDropped + 1) }
                FrameAssembler.Outcome.Building,
                FrameAssembler.Outcome.Invalid -> Unit
            }
        }
    }

    private fun emitFrame(frame: FrameAssembler.Outcome.Frame) {
        val bmp = JpegDecoder.decode(frame.data)
        if (bmp == null) {
            _stats.update {
                it.copy(framesDropped = it.framesDropped + 1, lastError = "JPEG decode failed (${frame.data.size}B)")
            }
            return
        }
        frame.gsensor?.let { g ->
            // Keep the previous angle when roll is undefined (probe vertical).
            I4seasonProtocol.rollDegrees(g)?.let { _rotation.value = (it + mountOffset) % 360f }
        }
        if (resolution == null) {
            resolution = "${frame.width}×${frame.height}"
            publishDiagnostics()
        }
        _frames.tryEmit(bmp)
        _stats.update { it.copy(framesReceived = it.framesReceived + 1) }
    }

    override fun close() {
        scope.cancel()
        runCatching { videoSocket?.close() }; videoSocket = null
        runCatching { controlSocket?.close() }; controlSocket = null
    }

    companion object {
        private const val TAG = "I4seasonSession"
        private const val REPLY_TIMEOUT_MS = 100
        private const val REQUEST_ATTEMPTS = 10
        private const val POLL_INTERVAL_MS = 250L
        /** The vendor app re-opens video after one silent second. */
        private const val VIDEO_STALL_MS = 1_000L
        private const val DEVINFO_INTERVAL_MS = 10_000L
    }
}
