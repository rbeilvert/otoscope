package dev.rubec.otoscope.stream.wudaopu

import android.graphics.Bitmap
import android.net.Network
import dev.rubec.otoscope.stream.BatteryStatus
import dev.rubec.otoscope.stream.CameraSession
import dev.rubec.otoscope.stream.Diagnostics
import dev.rubec.otoscope.stream.SessionStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * [CameraSession] backed by the Wudaopu video stream client (UDP/8032) and the
 * Wudaopu settings/status control client (UDP/50000). Composes the two into a
 * single object the ViewModel can use without knowing the vendor.
 */
class WudaopuSession(
    cameraIp: String,
    network: Network?,
) : CameraSession {

    private val camera = WudaopuCameraClient(cameraIp = cameraIp, network = network)
    private val control = WudaopuControlClient(cameraIp = cameraIp, network = network)

    override val frames: SharedFlow<Bitmap> get() = camera.frames
    override val rotation: StateFlow<Float> get() = camera.rotation
    override val stats: StateFlow<SessionStats> get() = camera.stats

    private val _battery = MutableStateFlow<BatteryStatus?>(null)
    override val battery: StateFlow<BatteryStatus?> = _battery.asStateFlow()

    private val _model = MutableStateFlow<String?>(null)
    override val model: StateFlow<String?> = _model.asStateFlow()

    private val _diagnostics = MutableStateFlow<Map<String, String>>(emptyMap())
    override val diagnostics: StateFlow<Map<String, String>> = _diagnostics.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pollJobs = mutableListOf<Job>()

    override fun start() {
        camera.start()

        // Identify probes (board info + firmware version) on their own coroutine.
        // Bounded retries — some firmwares never reply to one or the other, and we
        // don't want to thrash the control channel forever. The control client's
        // internal mutex briefly serialises against battery polling, so a stalled
        // probe will delay one battery sample but not the steady cadence.
        pollJobs += scope.launch {
            delay(STARTUP_SETTLE_MS)
            var attempts = 0
            while (isActive &&
                attempts < MAX_IDENTIFY_ATTEMPTS &&
                (_model.value == null || _diagnostics.value["Firmware"] == null)
            ) {
                if (_model.value == null) {
                    control.getBoardInfo()?.let { info ->
                        _model.value = info.model
                        mergeDiagnostics(
                            // `model` is shown in the top status row, `ssid_head`
                            // is just the SSID prefix the user already sees.
                            Diagnostics.fromJson(info.raw, exclude = setOf("model", "ssid_head")),
                        )
                    }
                }
                if (_diagnostics.value["Firmware"] == null) {
                    control.getRemoteVersion()?.let { version ->
                        mergeDiagnostics(mapOf("Firmware" to "v$version"))
                    }
                }
                attempts += 1
                delay(IDENTIFY_RETRY_MS)
            }
        }

        // Battery on a clean fixed cadence; doubles as the control-channel keepalive.
        pollJobs += scope.launch {
            while (isActive) {
                control.getBattery()?.let { _battery.value = it }
                delay(BATTERY_POLL_INTERVAL_MS)
            }
        }
    }

    private fun mergeDiagnostics(extra: Map<String, String>) {
        if (extra.isEmpty()) return
        _diagnostics.value = (_diagnostics.value + extra).toMap()
    }

    override fun close() {
        pollJobs.forEach { it.cancel() }
        pollJobs.clear()
        camera.close()
        scope.cancel()
    }

    companion object {
        private const val STARTUP_SETTLE_MS = 300L
        private const val BATTERY_POLL_INTERVAL_MS = 5_000L
        private const val IDENTIFY_RETRY_MS = 3_000L
        private const val MAX_IDENTIFY_ATTEMPTS = 10
    }
}
