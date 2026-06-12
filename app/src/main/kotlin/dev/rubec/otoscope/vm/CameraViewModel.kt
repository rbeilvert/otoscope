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
import dev.rubec.otoscope.stream.BatteryStatus
import dev.rubec.otoscope.stream.CameraSession
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
import kotlinx.coroutines.launch

sealed interface CameraState {
    data object Idle : CameraState
    data object BluetoothOff : CameraState
    data object WifiOff : CameraState
    data object Scanning : CameraState
    data class Found(val advert: CameraAdvert) : CameraState
    data class Connecting(
        val advert: CameraAdvert,
        val attempt: Int = 1,
        val totalAttempts: Int = 1,
    ) : CameraState
    data class Streaming(
        val advert: CameraAdvert,
        val session: CameraSession,
        val frame: StateFlow<Bitmap?>,
        val rotation: StateFlow<Float>,
        val model: StateFlow<String?>,
        val battery: StateFlow<BatteryStatus?>,
        val diagnostics: StateFlow<Map<String, String>>,
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
        _state.value = CameraState.Connecting(advert, attempt = 1, totalAttempts = CONNECT_ATTEMPTS)
        viewModelScope.launch {
            // Pairing is occasionally flaky on the first try — the BLE knock can
            // return before the camera's AP is fully advertised, or the
            // WifiNetworkSpecifier request can time out on a slow boot. Retry
            // with a short backoff before surfacing an error.
            var lastError: String? = null
            repeat(CONNECT_ATTEMPTS) { attempt ->
                _state.value = CameraState.Connecting(
                    advert = advert,
                    attempt = attempt + 1,
                    totalAttempts = CONNECT_ATTEMPTS,
                )
                if (attempt > 0) delay(RETRY_BACKOFF_MS)
                try {
                    Log.i(TAG, "connect attempt ${attempt + 1}/${CONNECT_ATTEMPTS} for ${advert.vendor.displayName}")
                    // Some vendors (JEGOAT) need a BLE GATT read to make the
                    // camera bring its AP up. No-op for vendors with always-on
                    // APs.
                    advert.vendor.preWifiHandshake(getApplication(), advert)
                    // Brief wait between the knock and the WiFi connect — the
                    // camera takes a moment to start broadcasting after being
                    // woken up.
                    delay(POST_KNOCK_DELAY_MS)
                    wifi.connect(advert)
                    startStreaming(advert)
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "attempt ${attempt + 1} failed: ${e.message}")
                    lastError = e.message
                    // Clean up any partial state from this attempt before retrying.
                    wifi.disconnect()
                }
            }
            _state.value = CameraState.Error(lastError ?: "Connect failed")
        }
    }

    private fun startStreaming(advert: CameraAdvert) {
        val cameraIp = wifi.gatewayIp ?: advert.vendor.defaultCameraIp
        Log.i(TAG, "starting ${advert.vendor.displayName} session on $cameraIp (local=${wifi.localIp})")

        val session = advert.vendor.createSession(network = wifi.currentNetwork, cameraIp = cameraIp)
        flipEnabled.value = true
        session.start()

        val frameState = session.frames
            .stateIn(viewModelScope, SharingStarted.Eagerly, initialValue = null as Bitmap?)

        _state.value = CameraState.Streaming(
            advert = advert,
            session = session,
            frame = frameState,
            rotation = session.rotation,
            model = session.model,
            battery = session.battery,
            diagnostics = session.diagnostics,
            flipEnabled = flipEnabled.asStateFlow(),
        )
    }

    fun setFlipEnabled(enabled: Boolean) {
        flipEnabled.value = enabled
    }

    fun disconnect() {
        (state.value as? CameraState.Streaming)?.session?.close()
        wifi.disconnect()
        _state.value = CameraState.Idle
    }

    override fun onCleared() {
        disconnect()
        super.onCleared()
    }

    companion object {
        private const val TAG = "CameraViewModel"
        private const val CONNECT_ATTEMPTS = 3
        private const val RETRY_BACKOFF_MS = 1_500L
        private const val POST_KNOCK_DELAY_MS = 500L
    }
}
