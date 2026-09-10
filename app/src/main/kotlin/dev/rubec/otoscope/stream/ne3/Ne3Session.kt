package dev.rubec.otoscope.stream.ne3

import android.graphics.Bitmap
import android.net.Network
import dev.rubec.otoscope.debug.FileLog as Log
import dev.rubec.otoscope.stream.BatteryStatus
import dev.rubec.otoscope.stream.CameraSession
import dev.rubec.otoscope.stream.SessionStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * NE3 family session (Bouffalo Lab BL602-based otoscopes shipped under the
 * "HND" companion app, `com.xiaozhen.beauty.hnd`).
 *
 * Two channels, both direct to the camera at `192.168.169.1`:
 *  - **Video** via [Ne3VideoClient] on UDP/8800: fragmented headerless
 *    MJPEG, reassembled by [Ne3FrameAssembler] and completed with the
 *    vendor Q75 JPEG prelude from [Ne3JpegHeader].
 *  - **Sensor + button** via [Ne3ControlClient] on TCP/2271: 38-byte
 *    packets carrying accelerometer readings (X/Y/Z as int16 BE) and, on
 *    variants with a physical shutter button, discrete press events.
 *
 * Battery, LED, and per-frame MCU control messages are exposed via the
 * UDP video channel's non-fragment message types (2 / 4 / 8) — those
 * payloads are surfaced up by [Ne3VideoClient] as debug logs and stay
 * unparsed for now until we have real captures to decode against.
 */
class Ne3Session(
    cameraIp: String,
    network: Network?,
) : CameraSession {

    private val video = Ne3VideoClient(cameraIp = cameraIp, network = network)
    private val control = Ne3ControlClient(cameraIp = cameraIp, network = network)

    override val frames: SharedFlow<Bitmap> get() = video.frames
    override val stats: StateFlow<SessionStats> get() = video.stats
    override val terminalError: StateFlow<String?> get() = video.terminalError
    override val rotation: StateFlow<Float> get() = control.rotation

    private val _battery = MutableStateFlow<BatteryStatus?>(null)
    override val battery: StateFlow<BatteryStatus?> = _battery.asStateFlow()

    private val _model = MutableStateFlow<String?>("NE3")
    override val model: StateFlow<String?> = _model.asStateFlow()

    /** Populated from the control client's device-type once the camera has
     *  identified itself, so the debug overlay shows which hardware variant
     *  is on the other end without needing the reporter to grep the log. */
    private val _diagnostics = MutableStateFlow<Map<String, String>>(emptyMap())
    override val diagnostics: StateFlow<Map<String, String>> = _diagnostics.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun start() {
        Log.i(TAG, "starting")
        video.start()
        control.start()
        scope.launch {
            control.deviceType.collect { dt ->
                if (dt != null) _diagnostics.value = _diagnostics.value + ("Sensor devType" to dt.toString())
            }
        }
    }

    override fun close() {
        video.close()
        control.close()
        scope.cancel()
    }

    companion object {
        private const val TAG = "Ne3Session"
    }
}
