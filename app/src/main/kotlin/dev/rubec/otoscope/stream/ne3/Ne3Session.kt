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
 *    MJPEG, reassembled by [Ne3FrameAssembler] with the Q level and
 *    resolution read per frame off the wire and the vendor JPEG prelude
 *    built on-the-fly by [Ne3JpegHeader].
 *  - **Sensor + button** via two possible transports feeding one shared
 *    [Ne3SensorParser]: [Ne3ControlClient] on TCP/2271 (optional, present
 *    on some HND firmwares) and [Ne3VideoClient]'s ctlmsg branch on
 *    UDP/8800 msg_type=4 (newer firmwares, same port as video). Either
 *    path drives the same rotation + shutter state so UI code never cares
 *    which one the firmware uses.
 *
 * Battery is NOT exposed: the HND vendor app ships no battery widget and
 * no code path in its native lib or smali reads a battery value from any
 * channel. [battery] stays null for this family.
 */
class Ne3Session(
    cameraIp: String,
    network: Network?,
) : CameraSession {

    private val parser = Ne3SensorParser()
    private val video = Ne3VideoClient(cameraIp = cameraIp, network = network, parser = parser)
    private val control = Ne3ControlClient(cameraIp = cameraIp, network = network, parser = parser)

    override val frames: SharedFlow<Bitmap> get() = video.frames
    override val stats: StateFlow<SessionStats> get() = video.stats
    override val terminalError: StateFlow<String?> get() = video.terminalError
    override val rotation: StateFlow<Float> get() = parser.rotation

    private val _battery = MutableStateFlow<BatteryStatus?>(null)
    override val battery: StateFlow<BatteryStatus?> = _battery.asStateFlow()

    private val _model = MutableStateFlow<String?>("NE3")
    override val model: StateFlow<String?> = _model.asStateFlow()

    /** Populated from the parser's device-type once the camera has
     *  identified itself (whichever channel delivered the first frame), so
     *  the debug overlay shows which hardware variant is on the other end
     *  without needing the reporter to grep the log. */
    private val _diagnostics = MutableStateFlow<Map<String, String>>(emptyMap())
    override val diagnostics: StateFlow<Map<String, String>> = _diagnostics.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun start() {
        Log.i(TAG, "starting")
        video.start()
        control.start()
        scope.launch {
            parser.deviceType.collect { dt ->
                if (dt != null) _diagnostics.value = _diagnostics.value + ("Sensor devType" to dt.toString())
            }
        }
        scope.launch {
            parser.shutter.collect { code -> Log.i(TAG, "shutter code=0x%02x".format(code)) }
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
