package dev.rubec.otoscope.vm

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.wifi.WifiManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.rubec.otoscope.ble.CameraAdvert
import dev.rubec.otoscope.ble.CameraBleScanner
import dev.rubec.otoscope.stream.OtoscopeCameraClient
import dev.rubec.otoscope.stream.OtoscopeControlClient
import dev.rubec.otoscope.wifi.CameraWifiConnector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface CameraState {
    data object Idle : CameraState
    data object BluetoothOff : CameraState
    data object WifiOff : CameraState
    data object Scanning : CameraState
    data class Found(val advert: CameraAdvert) : CameraState
    data class Connecting(val advert: CameraAdvert) : CameraState
    data class Streaming(
        val advert: CameraAdvert,
        val client: OtoscopeCameraClient,
        val frame: StateFlow<Bitmap?>,
        val rotation: StateFlow<Float>,
        val model: StateFlow<String?>,
        val battery: StateFlow<OtoscopeControlClient.Battery?>,
        val flipEnabled: StateFlow<Boolean>,
    ) : CameraState
    data class Error(val message: String) : CameraState
}

class CameraViewModel(app: Application) : AndroidViewModel(app) {

    private val scanner = CameraBleScanner(app)
    private val wifi = CameraWifiConnector(app)
    private val wifiManager = app.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val _state = MutableStateFlow<CameraState>(CameraState.Idle)
    val state: StateFlow<CameraState> = _state.asStateFlow()

    private val _adverts = MutableStateFlow<List<CameraAdvert>>(emptyList())
    val adverts: StateFlow<List<CameraAdvert>> = _adverts.asStateFlow()

    private var scanJob: Job? = null
    private var sessionJobs: MutableList<Job> = mutableListOf()

    /** Horizontal-mirror state for the active stream. Owned here so the UI
     *  reads it read-only and routes toggles back through [setFlipEnabled]. */
    private val flipEnabled = MutableStateFlow(true)

    fun startScan() {
        if (!scanner.isBluetoothEnabled) {
            _state.value = CameraState.BluetoothOff
            return
        }
        if (!wifiManager.isWifiEnabled) {
            _state.value = CameraState.WifiOff
            return
        }
        scanJob?.cancel()
        _adverts.value = emptyList()
        _state.value = CameraState.Scanning
        scanJob = viewModelScope.launch {
            try {
                scanner.scan().collect { advert ->
                    _adverts.update { current ->
                        if (current.any { it.bssid == advert.bssid }) current
                        else current + advert
                    }
                    if (_state.value is CameraState.Scanning) {
                        _state.value = CameraState.Found(advert)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = CameraState.Error(e.message ?: "Scan failed")
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        if (_state.value is CameraState.Scanning || _state.value is CameraState.Found) {
            _state.value = CameraState.Idle
        }
    }

    fun connect(advert: CameraAdvert) {
        stopScan()
        _state.value = CameraState.Connecting(advert)
        viewModelScope.launch {
            runCatching { wifi.connect(advert) }
                .onSuccess { startStreaming(advert) }
                .onFailure {
                    _state.value = CameraState.Error(it.message ?: "WiFi connect failed")
                }
        }
    }

    private fun startStreaming(advert: CameraAdvert) {
        val cameraIp = wifi.gatewayIp ?: "192.168.0.10"
        Log.i(TAG, "starting otoscope client on $cameraIp (local=${wifi.localIp})")

        val camera = OtoscopeCameraClient(cameraIp = cameraIp, network = wifi.currentNetwork)
        val control = OtoscopeControlClient(cameraIp = cameraIp, network = wifi.currentNetwork)

        val frameState = camera.frames
            .stateIn(viewModelScope, SharingStarted.Eagerly, initialValue = null as Bitmap?)
        val modelState = MutableStateFlow<String?>(null)
        val batteryState = MutableStateFlow<OtoscopeControlClient.Battery?>(null)
        flipEnabled.value = true

        camera.start()

        // Identify the camera once, then poll the battery on a slow cadence.
        sessionJobs += viewModelScope.launch {
            // The board-info command sometimes loses to the streaming start cmd
            // burst — retry a few times until the camera answers.
            var attempts = 0
            while (isActive && modelState.value == null && attempts < 6) {
                control.getBoardInfo()?.let { modelState.value = it.model }
                attempts += 1
                if (modelState.value == null) delay(1000)
            }
        }
        sessionJobs += viewModelScope.launch {
            while (isActive) {
                control.getBattery()?.let { batteryState.value = it }
                delay(2000)
            }
        }

        _state.value = CameraState.Streaming(
            advert = advert,
            client = camera,
            frame = frameState,
            rotation = camera.rotation,
            model = modelState.asStateFlow(),
            battery = batteryState.asStateFlow(),
            flipEnabled = flipEnabled.asStateFlow(),
        )
    }

    fun setFlipEnabled(enabled: Boolean) {
        flipEnabled.value = enabled
    }

    fun disconnect() {
        sessionJobs.forEach { it.cancel() }
        sessionJobs.clear()
        (state.value as? CameraState.Streaming)?.client?.close()
        wifi.disconnect()
        _state.value = CameraState.Idle
    }

    override fun onCleared() {
        disconnect()
        super.onCleared()
    }

    companion object {
        private const val TAG = "CameraViewModel"
    }
}
