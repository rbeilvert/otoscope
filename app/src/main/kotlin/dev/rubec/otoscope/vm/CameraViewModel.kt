package dev.rubec.otoscope.vm

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.wifi.WifiManager
import dev.rubec.otoscope.debug.FileLog as Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.rubec.otoscope.ble.CameraAdvert
import dev.rubec.otoscope.ble.CameraBleScanner
import dev.rubec.otoscope.stream.BatteryStatus
import dev.rubec.otoscope.stream.CameraSession
import dev.rubec.otoscope.stream.TerminalErrors
import dev.rubec.otoscope.wifi.CameraWifiConnector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    /** Camera dropped mid-session (powered off, out of range). Shown briefly
     *  then auto-transitions back to [Idle] — no user action required. */
    data class Disconnected(val reason: String) : CameraState
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
    private var stallWatchdogJob: Job? = null

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

        // React to the camera's WiFi AP disappearing (typical when the user
        // powers the camera off). Fires once — no double-teardown risk.
        wifi.onNetworkLost = {
            viewModelScope.launch {
                handleUnexpectedDisconnect(reason = "Camera disconnected")
            }
        }

        // Watch the session for terminal errors; conditions we know can't
        // recover on their own (e.g. the OS refusing to bind our sockets to the
        // camera's Wi-Fi because another app is holding a VPN with lockdown).
        // Route these to a specific error message so the user sees what to do.
        viewModelScope.launch {
            session.terminalError.collect { code ->
                if (code == null || _state.value !is CameraState.Streaming) return@collect
                Log.w(TAG, "terminal error from session: $code")
                surfaceTerminalError(code)
            }
        }

        // Two-mode watchdog:
        //  - FIRST-PACKET timeout. If we never receive a single video packet
        //    within FIRST_PACKET_TIMEOUT_MS after start, the camera can't reach
        //    us. Usually a VPN routing packets away from the camera Wi-Fi even
        //    though our bind succeeded (kernel accepts the source-IP bind but
        //    Android's egress rules still drop the traffic). Surface a specific
        //    terminal error so the user sees actionable copy instead of a stuck
        //    "Waiting for frames".
        //  - STEADY-STATE stall. Packets stopped after some had arrived. That's
        //    a mid-session disconnect (camera powered off, out of range, ...).
        stallWatchdogJob?.cancel()
        stallWatchdogJob = viewModelScope.launch {
            val startedAt = elapsedNow()
            var lastPackets = 0L
            var stalledSince: Long? = null
            while (isActive && _state.value is CameraState.Streaming) {
                delay(STALL_CHECK_INTERVAL_MS)
                val packets = session.stats.value.packetsReceived
                val now = elapsedNow()

                if (packets == 0L) {
                    // Still haven't heard from the camera.
                    if (now - startedAt >= FIRST_PACKET_TIMEOUT_MS) {
                        Log.w(TAG, "no first packet after ${(now - startedAt) / 1000}s")
                        surfaceTerminalError(TerminalErrors.CAMERA_UNREACHABLE)
                        return@launch
                    }
                    continue
                }

                if (packets != lastPackets) {
                    lastPackets = packets
                    stalledSince = null
                    continue
                }
                // Had packets, then they stopped.
                if (stalledSince == null) {
                    stalledSince = now
                } else if (now - stalledSince >= STALL_TIMEOUT_MS) {
                    Log.w(TAG, "stream stalled: ${(now - stalledSince) / 1000}s without packets")
                    handleUnexpectedDisconnect(reason = "Camera stopped responding")
                    return@launch
                }
            }
        }
    }

    /** Map a machine-readable [TerminalErrors] code to a user-facing message,
     *  tear the session down, and land in [CameraState.Error] so the message
     *  stays visible until the user acknowledges it. */
    private fun surfaceTerminalError(code: String) {
        val streaming = _state.value as? CameraState.Streaming ?: return
        stallWatchdogJob?.cancel()
        stallWatchdogJob = null
        runCatching { streaming.session.close() }
        runCatching { wifi.disconnect() }
        val message = when (code) {
            TerminalErrors.NETWORK_BIND_FORBIDDEN,
            TerminalErrors.CAMERA_UNREACHABLE ->
                "Couldn't reach the camera over Wi-Fi. " +
                    "If you have an active VPN, disable it and try connecting again."
            else -> "Camera session failed ($code)"
        }
        _state.value = CameraState.Error(message)
    }

    /** Consolidate cleanup for both the network-loss callback and the stall
     *  watchdog. Idempotent — safe to call from either signal path even if the
     *  other has already fired. */
    private suspend fun handleUnexpectedDisconnect(reason: String) {
        val streaming = _state.value as? CameraState.Streaming ?: return
        Log.i(TAG, "unexpected disconnect: $reason")
        stallWatchdogJob?.cancel()
        stallWatchdogJob = null

        // Session close involves socket teardown that can throw when the
        // underlying network is already gone — swallow, we're bailing anyway.
        withContext(NonCancellable) {
            runCatching { streaming.session.close() }
            runCatching { wifi.disconnect() }
        }

        // Drop the previous scan result — the camera that just went dark is
        // still in there, and its now-stale BSSID would show as a reconnectable
        // entry until the user starts a fresh scan.
        _adverts.value = emptyList()

        _state.value = CameraState.Disconnected(reason)
        delay(DISCONNECT_NOTICE_MS)
        // Only clear the notice if the user hasn't already moved on (e.g.
        // tapped a scan control) during the notice window.
        if (_state.value is CameraState.Disconnected) {
            _state.value = CameraState.Idle
        }
    }

    /** Monotonic clock — [System.currentTimeMillis] is affected by NTP jumps. */
    private fun elapsedNow(): Long = android.os.SystemClock.elapsedRealtime()

    fun setFlipEnabled(enabled: Boolean) {
        flipEnabled.value = enabled
    }

    fun disconnect() {
        stallWatchdogJob?.cancel()
        stallWatchdogJob = null
        runCatching { (state.value as? CameraState.Streaming)?.session?.close() }
        runCatching { wifi.disconnect() }
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
        private const val STALL_CHECK_INTERVAL_MS = 1_000L
        // Ample headroom over the video keepalive/packet cadence — real streams
        // send tens of packets per second, so 5 s of silence is unambiguous.
        private const val STALL_TIMEOUT_MS = 5_000L
        // Grace window from session start to the first video packet. Wudaopu's
        // start-cmd burst + camera boot handshake normally lands the first frame
        // within ~1 s; 15 s is well past that but may be shorter than a user's
        // patience with a stuck "Waiting for frames" screen.
        private const val FIRST_PACKET_TIMEOUT_MS = 10_000L
        private const val DISCONNECT_NOTICE_MS = 3_000L
    }
}
