package dev.rubec.otoscope.stream.earfairy

import android.graphics.Bitmap
import android.net.Network
import dev.rubec.otoscope.debug.FileLog as Log
import dev.rubec.otoscope.stream.BatteryStatus
import dev.rubec.otoscope.stream.CameraSession
import dev.rubec.otoscope.stream.LedControl
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
 * EarFairy family session.
 *
 * Two channels, both direct to the camera at `192.168.1.1`:
 *  - **Video** via [EarFairyVideoClient]: standard RTSP handshake on TCP/7070
 *    with MJPEG-over-RTP (payload type 26, RFC 2435) delivered on a dedicated
 *    UDP socket. Frames are decoded to Bitmaps and emitted through [frames],
 *    matching the other vendors.
 *  - **Control + telemetry** via [EarFairyControlClient] on UDP/7099. The
 *    camera pushes rotation + battery frames while we ping it once per
 *    second; the client relays those and — via [led] — sends the LED
 *    on/off command. Full wire format is documented on the control client.
 */
class EarFairySession(
    cameraIp: String,
    network: Network?,
) : CameraSession {

    private val video = EarFairyVideoClient(cameraIp = cameraIp, network = network)
    private val control = EarFairyControlClient(cameraIp = cameraIp, network = network)

    override val frames: SharedFlow<Bitmap> get() = video.frames
    override val stats: StateFlow<SessionStats> get() = video.stats
    override val rotation: StateFlow<Float> get() = control.rotation
    override val battery: StateFlow<BatteryStatus?> get() = control.battery

    private val _model = MutableStateFlow<String?>("EarFairy")
    override val model: StateFlow<String?> = _model.asStateFlow()

    private val _diagnostics = MutableStateFlow<Map<String, String>>(emptyMap())
    override val diagnostics: StateFlow<Map<String, String>> = _diagnostics.asStateFlow()

    // Coalesced terminal error from both channels — whichever fails first wins.
    private val _terminalError = MutableStateFlow<String?>(null)
    override val terminalError: StateFlow<String?> = _terminalError.asStateFlow()

    override val led: LedControl = object : LedControl {
        override val enabled: StateFlow<Boolean> get() = control.ledEnabled
        override fun setEnabled(on: Boolean) = control.setLed(on)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun start() {
        Log.i(TAG, "starting")
        video.start()
        control.start()
        scope.launch {
            video.terminalError.collect { code -> code?.let { _terminalError.value = it } }
        }
        scope.launch {
            control.terminalError.collect { code -> code?.let { _terminalError.value = it } }
        }
    }

    override fun close() {
        video.close()
        control.close()
        scope.cancel()
    }

    companion object {
        private const val TAG = "EarFairySession"
    }
}
