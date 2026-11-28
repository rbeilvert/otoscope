package dev.rubec.otoscope.stream.ne3

import android.graphics.Bitmap
import android.net.Network
import dev.rubec.otoscope.debug.FileLog as Log
import dev.rubec.otoscope.stream.BatteryStatus
import dev.rubec.otoscope.stream.CameraSession
import dev.rubec.otoscope.stream.SessionStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * NE3 family session (Bouffalo Lab BL602-based otoscopes shipped under the
 * "HND" companion app, `com.xiaozhen.beauty.hnd`). Camera lives at
 * `192.168.169.1`.
 *
 * [Ne3VideoClient] handles the UDP/8800 video stream and the inline
 * accelerometer suffix embedded in each frame; [Ne3SensorParser] holds the
 * resulting rotation state.
 *
 * This hardware family exposes no battery readout, so [battery] stays null.
 */
class Ne3Session(
    cameraIp: String,
    network: Network?,
) : CameraSession {

    private val parser = Ne3SensorParser()
    private val video = Ne3VideoClient(cameraIp = cameraIp, network = network, parser = parser)

    override val frames: SharedFlow<Bitmap> get() = video.frames
    override val stats: StateFlow<SessionStats> get() = video.stats
    override val terminalError: StateFlow<String?> get() = video.terminalError
    override val rotation: StateFlow<Float> get() = parser.rotation

    private val _battery = MutableStateFlow<BatteryStatus?>(null)
    override val battery: StateFlow<BatteryStatus?> = _battery.asStateFlow()

    private val _model = MutableStateFlow<String?>("NE3")
    override val model: StateFlow<String?> = _model.asStateFlow()

    private val _diagnostics = MutableStateFlow<Map<String, String>>(emptyMap())
    override val diagnostics: StateFlow<Map<String, String>> = _diagnostics.asStateFlow()

    override fun start() {
        Log.i(TAG, "starting")
        video.start()
    }

    override fun close() {
        video.close()
    }

    companion object {
        private const val TAG = "Ne3Session"
    }
}
