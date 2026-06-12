package dev.rubec.otoscope.stream.jegoat

import android.graphics.Bitmap
import android.net.Network
import android.util.Log
import dev.rubec.otoscope.stream.BatteryStatus
import dev.rubec.otoscope.stream.CameraSession
import dev.rubec.otoscope.stream.JpegDecoder
import dev.rubec.otoscope.stream.SessionStats
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
import org.json.JSONException
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * JEGOAT camera session. Two UDP sockets bound to the camera network:
 *
 *  - **Video** on port 61501: send `0x20 0x01` to start, receive 24-byte-header
 *    JPEG chunks back on the same source port. Reassembly is in [FrameAssembler].
 *    Send `0x20 0x02` to stop on disconnect.
 *  - **Control** on port 61500: send `0x10 0x01` every ~5 s, camera replies
 *    with a payload containing a JSON telemetry object (battery, firmware, …).
 */
class JegoatSession(
    private val cameraIp: String,
    private val network: Network?,
    private val videoPort: Int = 61501,
    private val controlPort: Int = 61500,
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var videoJob: Job? = null
    private var controlJob: Job? = null
    private var videoSocket: DatagramSocket? = null
    private var controlSocket: DatagramSocket? = null

    // Resolved synchronously in start() — InetAddress.getByName on a numeric IP
    // doesn't do DNS, just parsing. Keeps close()'s stop-packet branch race-free
    // with the video coroutine, and the control coroutine no longer needs to
    // busy-wait for the address to be assigned.
    private var cameraAddr: InetAddress? = null

    override fun start() {
        if (videoJob != null) return
        cameraAddr = try {
            InetAddress.getByName(cameraIp)
        } catch (e: Exception) {
            Log.e(TAG, "resolving $cameraIp failed", e)
            _stats.update { it.copy(lastError = "address: ${e.message}") }
            return
        }
        videoJob = scope.launch {
            try {
                runVideo()
            } catch (e: Exception) {
                Log.e(TAG, "video loop crashed", e)
                _stats.update { it.copy(lastError = "${e.javaClass.simpleName}: ${e.message}") }
            }
        }
        controlJob = scope.launch {
            try {
                runControl()
            } catch (e: Exception) {
                Log.e(TAG, "control loop crashed", e)
            }
        }
    }

    private suspend fun runVideo() {
        val addr = cameraAddr ?: return
        val sock = DatagramSocket().also { s ->
            network?.bindSocket(s)
            s.soTimeout = 1000
            s.receiveBufferSize = 4 * 1024 * 1024
        }
        videoSocket = sock

        // Initial start cmd burst. The camera replies to whichever source port
        // the burst came from, so this socket has to send AND receive.
        val startPacket = DatagramPacket(VIDEO_START, VIDEO_START.size, addr, videoPort)
        repeat(10) {
            runCatching { sock.send(startPacket) }
                .onFailure { Log.w(TAG, "video start send failed: ${it.message}") }
        }

        // Keepalive — re-send start cmd periodically so the camera doesn't quietly
        // stop streaming if it loses track of our session (matches what Wudaopu
        // needs, costs ~2 bytes/second and is harmless on cameras that don't).
        val keepalive = scope.launch {
            while (isActive) {
                delay(VIDEO_KEEPALIVE_MS)
                runCatching { sock.send(startPacket) }
                    .onFailure { Log.w(TAG, "video keepalive failed: ${it.message}") }
            }
        }

        try {
            val assembler = FrameAssembler()
            val recvBuf = ByteArray(8192)
            val packet = DatagramPacket(recvBuf, recvBuf.size)
            var firstPacketLogged = false

            while (scope.isActive) {
                packet.length = recvBuf.size
                try {
                    sock.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (e: Exception) {
                    _stats.update { it.copy(lastError = e.message) }
                    Log.w(TAG, "video recv: ${e.message}")
                    continue
                }
                val len = packet.length
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
                    is FrameAssembler.Outcome.Frame -> emitFrame(outcome)
                    FrameAssembler.Outcome.Dropped ->
                        _stats.update { it.copy(framesDropped = it.framesDropped + 1) }
                    FrameAssembler.Outcome.Building,
                    FrameAssembler.Outcome.Invalid -> Unit
                }
            }
        } finally {
            keepalive.cancel()
        }
    }

    private fun emitFrame(frame: FrameAssembler.Outcome.Frame) {
        val bmp = JpegDecoder.decode(frame.data)
        if (bmp != null) {
            _rotation.value = frame.angle
            _frames.tryEmit(bmp)
            _stats.update { it.copy(framesReceived = it.framesReceived + 1) }
        } else {
            _stats.update {
                it.copy(
                    framesDropped = it.framesDropped + 1,
                    lastError = "JPEG decode failed (${frame.data.size}B)",
                )
            }
        }
    }

    private suspend fun runControl() {
        val addr = cameraAddr ?: return
        val sock = DatagramSocket().also { s ->
            network?.bindSocket(s)
            s.soTimeout = 1500
        }
        controlSocket = sock

        val pollPacket = DatagramPacket(CTRL_HEARTBEAT, CTRL_HEARTBEAT.size, addr, controlPort)
        val recvBuf = ByteArray(4096)
        val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
        var jsonLogged = false

        while (scope.isActive) {
            try {
                sock.send(pollPacket)
                sock.receive(recvPacket)
                if (!jsonLogged) {
                    jsonLogged = true
                    val raw = String(recvBuf, 0, recvPacket.length, Charsets.UTF_8)
                    Log.i(TAG, "telemetry: ${raw.replace('\n', ' ')}")
                }
                parseTelemetry(recvBuf, recvPacket.length)
            } catch (_: SocketTimeoutException) {
                // Camera silent this round — try again next interval.
            } catch (e: Exception) {
                Log.w(TAG, "control: ${e.message}")
            }
            delay(5_000)
        }
    }

    /** The camera prefixes the JSON with a few bytes (probably an echo of the
     *  cmd) before the actual payload. Skip to the first `{`. */
    private fun parseTelemetry(buf: ByteArray, len: Int) {
        val text = String(buf, 0, len, Charsets.UTF_8)
        val start = text.indexOf('{')
        if (start < 0) return
        val obj = try {
            JSONObject(text.substring(start))
        } catch (e: JSONException) {
            Log.w(TAG, "telemetry JSON parse: ${e.message}")
            return
        }

        // Battery — the camera publishes percent and a separate charging flag.
        val pct = obj.optInt("battery_percentage", -1).takeIf { it in 0..100 }
        if (pct != null) {
            val charging = obj.optBoolean("battery_charging", false)
            // No dedicated "full" indicator in the telemetry; infer it.
            val full = pct >= 100 && !charging
            _battery.value = BatteryStatus(percent = pct, charging = charging, full = full)
        }

        // Model = SoC name + firmware version, formatted for human display.
        val chip = obj.optString("device_chip").takeIf { it.isNotEmpty() }
        val firmware = obj.optInt("device_version", -1).takeIf { it >= 0 }
        if (chip != null) {
            _model.value = if (firmware != null) "$chip v$firmware" else chip
        }

        // Debug-overlay diagnostics — vendor-specific extras the always-on UI
        // doesn't need but that help when something's off.
        val width = obj.optInt("width", -1).takeIf { it > 0 }
        val height = obj.optInt("height", -1).takeIf { it > 0 }
        val fps = obj.optInt("fps", -1).takeIf { it > 0 }
        val rssi = obj.optInt("debug_rssi", Int.MIN_VALUE).takeIf { it > -200 }
        val next = linkedMapOf<String, String>()
        if (firmware != null) next["Firmware"] = "v$firmware"
        if (width != null && height != null) {
            next["Resolution"] = if (fps != null) "${width}×${height} @ ${fps}fps" else "${width}×${height}"
        }
        if (rssi != null) next["Wi-Fi RSSI"] = "$rssi dBm"
        _diagnostics.value = next
    }

    override fun close() {
        // Best-effort stop streaming before we tear the sockets down. cameraAddr
        // was resolved synchronously in start() so it's set whenever videoSocket is.
        val sock = videoSocket
        val addr = cameraAddr
        if (sock != null && addr != null) {
            runCatching {
                val stop = DatagramPacket(VIDEO_STOP, VIDEO_STOP.size, addr, videoPort)
                repeat(5) { sock.send(stop) }
            }
        }
        videoJob?.cancel(); videoJob = null
        controlJob?.cancel(); controlJob = null
        runCatching { videoSocket?.close() }; videoSocket = null
        runCatching { controlSocket?.close() }; controlSocket = null
        scope.cancel()
    }

    companion object {
        private const val TAG = "JegoatSession"
        private const val VIDEO_KEEPALIVE_MS = 1_000L
        private val VIDEO_START = byteArrayOf(0x20, 0x01)
        private val VIDEO_STOP = byteArrayOf(0x20, 0x02)
        private val CTRL_HEARTBEAT = byteArrayOf(0x10, 0x01)
    }
}
