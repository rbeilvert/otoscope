package dev.rubec.otoscope.stream.beken

import android.graphics.Bitmap
import android.net.Network
import android.os.SystemClock
import dev.rubec.otoscope.debug.FileLog as Log
import dev.rubec.otoscope.stream.BatteryStatus
import dev.rubec.otoscope.stream.CameraSession
import dev.rubec.otoscope.stream.Diagnostics
import dev.rubec.otoscope.stream.JpegDecoder
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.SocketTimeoutException
import kotlin.math.abs

/**
 * Session for the HND NE3 built on a Beken BK7231U (see [BekenProtocol]).
 *
 * Three UDP sockets, all bound to the camera network:
 *
 *  - **Info**: asks for the device-info JSON on port 46526 (model, firmware).
 *  - **Video**: bound to 44506. JPEG chunks come back from the camera's 44506
 *    and are reassembled in [BekenFrameAssembler].
 *  - **Sensor**: bound to 52219. Accelerometer packets drive [rotation].
 *
 * There is no heartbeat. Like the vendor app we send the start commands again
 * when a stream stalls ([STALL_MS]). The camera has no light control and no
 * known battery readout, so [led] stays null and [battery] stays null.
 */
class BekenSession(
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var videoSocket: DatagramSocket? = null
    private var sensorSocket: DatagramSocket? = null
    private var infoSocket: DatagramSocket? = null

    @Volatile private var lastVideoAt = 0L
    @Volatile private var lastSensorAt = 0L

    override fun start() {
        if (videoSocket != null) return
        val camera = try {
            InetAddress.getByName(cameraIp)
        } catch (e: Exception) {
            Log.e(TAG, "resolving $cameraIp failed", e)
            _stats.update { it.copy(lastError = "address: ${e.message}") }
            return
        }

        val video = boundSocket(BekenProtocol.VIDEO_PORT) ?: return
        val sensor = boundSocket(BekenProtocol.SENSOR_PORT) ?: return
        val info = DatagramSocket()
        bindOrTerminal(info, network, TAG)?.let {
            _terminalError.value = it
            return
        }
        videoSocket = video
        sensorSocket = sensor
        infoSocket = info
        Log.i(TAG, "start: camera=$cameraIp network=${network != null}")

        scope.launch { runControl(camera, info, video, sensor) }
        scope.launch { runVideo(video) }
        scope.launch { runSensor(sensor) }
    }

    /** A UDP socket on a fixed local port, bound to the camera network first
     *  (`Network.bindSocket` needs the socket unbound). Null on failure, with
     *  [terminalError] or [SessionStats.lastError] set. */
    private fun boundSocket(port: Int): DatagramSocket? {
        val socket = DatagramSocket(null as SocketAddress?)
        socket.reuseAddress = true
        socket.receiveBufferSize = 4 * 1024 * 1024
        bindOrTerminal(socket, network, TAG)?.let {
            _terminalError.value = it
            socket.close()
            return null
        }
        return try {
            socket.bind(InetSocketAddress(port))
            socket.soTimeout = RECEIVE_TIMEOUT_MS
            socket
        } catch (e: Exception) {
            Log.e(TAG, "bind $port failed", e)
            _stats.update { it.copy(lastError = "bind $port: ${e.message}") }
            socket.close()
            null
        }
    }

    private suspend fun runControl(
        camera: InetAddress,
        info: DatagramSocket,
        video: DatagramSocket,
        sensor: DatagramSocket,
    ) {
        try {
            // Right after joining the camera's Wi-Fi the route may not be up
            // yet: keep asking until the camera answers.
            while (scope.isActive && !queryInfo(camera, info)) delay(RETRY_MS)
            var lastInfo = SystemClock.elapsedRealtime()
            var lastVideoStart = 0L
            var lastSensorStart = 0L
            while (scope.isActive) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastVideoAt > STALL_MS && now - lastVideoStart > STALL_MS) {
                    send(video, camera, BekenProtocol.VIDEO_PORT, BekenProtocol.CMD_VIDEO_START)
                    Log.i(TAG, "video start sent")
                    lastVideoStart = now
                }
                if (now - lastSensorAt > STALL_MS && now - lastSensorStart > STALL_MS) {
                    send(sensor, camera, BekenProtocol.SENSOR_PORT, BekenProtocol.CMD_SENSOR_START)
                    lastSensorStart = now
                }
                if (now - lastInfo >= INFO_REFRESH_MS) {
                    queryInfo(camera, info)
                    lastInfo = now
                }
                delay(POLL_MS)
            }
        } catch (e: Exception) {
            if (scope.isActive) Log.e(TAG, "control loop crashed", e)
        }
    }

    private fun send(socket: DatagramSocket, camera: InetAddress, port: Int, data: ByteArray) {
        runCatching { socket.send(DatagramPacket(data, data.size, camera, port)) }
            .onFailure { if (scope.isActive) Log.w(TAG, "send to $port failed: ${it.message}") }
    }

    private fun queryInfo(camera: InetAddress, socket: DatagramSocket): Boolean {
        socket.soTimeout = INFO_TIMEOUT_MS
        send(socket, camera, BekenProtocol.INFO_PORT, BekenProtocol.CMD_INFO)
        val buf = ByteArray(2048)
        val packet = DatagramPacket(buf, buf.size)
        try {
            socket.receive(packet)
        } catch (_: SocketTimeoutException) {
            return false
        } catch (_: Exception) {
            return false
        }
        val json = BekenProtocol.parseInfo(buf, packet.length) ?: return false
        if (_model.value == null) Log.i(TAG, "device info: $json")
        _model.value = json.optString("brand").takeIf { it.isNotEmpty() }
            ?: json.optString("model").takeIf { it.isNotEmpty() }
        _diagnostics.value = Diagnostics.fromJson(json, exclude = DIAGNOSTICS_EXCLUDE)
        return true
    }

    private fun runVideo(socket: DatagramSocket) {
        val assembler = BekenFrameAssembler()
        val buf = ByteArray(2048)
        val packet = DatagramPacket(buf, buf.size)
        var first = true
        while (scope.isActive) {
            packet.length = buf.size
            try {
                socket.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                if (!scope.isActive) return
                _stats.update { it.copy(lastError = e.message) }
                continue
            }
            val len = packet.length
            lastVideoAt = SystemClock.elapsedRealtime()
            _stats.update {
                it.copy(packetsReceived = it.packetsReceived + 1, bytesReceived = it.bytesReceived + len)
            }
            if (first) {
                first = false
                Log.i(TAG, "first video packet $len bytes: ${buf.toHex(0, minOf(len, 16))}")
            }
            when (val outcome = assembler.feed(buf, len)) {
                is BekenFrameAssembler.Outcome.Frame -> {
                    val bitmap = JpegDecoder.decode(outcome.jpeg)
                    if (bitmap != null) {
                        _frames.tryEmit(bitmap)
                        _stats.update { it.copy(framesReceived = it.framesReceived + 1) }
                    } else {
                        _stats.update {
                            it.copy(framesDropped = it.framesDropped + 1, lastError = "decode failed")
                        }
                    }
                }
                BekenFrameAssembler.Outcome.Dropped ->
                    _stats.update { it.copy(framesDropped = it.framesDropped + 1) }
                BekenFrameAssembler.Outcome.Building -> Unit
            }
        }
    }

    private fun runSensor(socket: DatagramSocket) {
        val buf = ByteArray(256)
        val packet = DatagramPacket(buf, buf.size)
        while (scope.isActive) {
            packet.length = buf.size
            try {
                socket.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: Exception) {
                if (!scope.isActive) return
                continue
            }
            val (_, y, z) = BekenProtocol.parseSensor(buf, packet.length) ?: continue
            val angle = BekenProtocol.parseAngle(buf, packet.length) ?: continue
            lastSensorAt = SystemClock.elapsedRealtime()
            // Lens straight up or down: the roll angle is noise, keep the last one.
            if (abs(y) > BekenProtocol.FLAT_THRESHOLD || abs(z) > BekenProtocol.FLAT_THRESHOLD) {
                _rotation.value = BekenProtocol.rotationDegrees(angle)
            }
        }
    }

    override fun close() {
        val video = videoSocket
        val camera = runCatching { InetAddress.getByName(cameraIp) }.getOrNull()
        if (video != null && camera != null) {
            // Best effort: the camera serves one client at a time.
            runCatching {
                video.send(DatagramPacket(
                    BekenProtocol.CMD_VIDEO_STOP, BekenProtocol.CMD_VIDEO_STOP.size,
                    camera, BekenProtocol.VIDEO_PORT,
                ))
            }
        }
        scope.cancel()
        for (s in listOf(videoSocket, sensorSocket, infoSocket)) runCatching { s?.close() }
        videoSocket = null
        sensorSocket = null
        infoSocket = null
    }

    companion object {
        private const val TAG = "BekenSession"
        private const val RECEIVE_TIMEOUT_MS = 1000
        private const val INFO_TIMEOUT_MS = 500
        private const val RETRY_MS = 500L
        private const val POLL_MS = 250L
        private const val STALL_MS = 1000L
        private const val INFO_REFRESH_MS = 30_000L

        /** Static identifiers that only clutter the overlay. */
        private val DIAGNOSTICS_EXCLUDE = setOf("uuid", "macid", "factory", "calibrated", "wifi_mode")
    }
}
