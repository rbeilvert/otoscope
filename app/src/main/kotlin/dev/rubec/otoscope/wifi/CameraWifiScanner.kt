package dev.rubec.otoscope.wifi

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import dev.rubec.otoscope.ble.CameraAdvert
import dev.rubec.otoscope.debug.FileLog as Log
import dev.rubec.otoscope.vendor.CameraVendors
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Wi-Fi-scan-based camera discovery for vendors whose companion apps have no
 * BLE. Wraps [WifiManager.startScan] + the `SCAN_RESULTS_AVAILABLE_ACTION`
 * broadcast, filters visible SSIDs through the registered Wi-Fi-scan vendors
 * (see [CameraVendors.parseSsid]), and emits a [CameraAdvert] per matching AP.
 *
 * `startScan()` is rate-limited by Android (~4 scans / 2 minutes on API 28+);
 * the OS falls back to a cached result set when the throttle kicks in, which
 * is fine here because we only need one hit to move on.
 *
 * This class emits verbose diagnostic logs. When a scan seems to "silently
 * fail", the log file has enough to distinguish permission denial, empty
 * results, throttling, and prefix mismatches.
 */
class CameraWifiScanner(context: Context) {
    private val appContext = context.applicationContext
    private val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val cm = appContext.getSystemService(ConnectivityManager::class.java)

    val isWifiEnabled: Boolean get() = wifi.isWifiEnabled

    @SuppressLint("MissingPermission")
    fun scan(): Flow<CameraAdvert> = callbackFlow {
        // De-dupe so the caller sees each camera once even though Android
        // fires SCAN_RESULTS_AVAILABLE_ACTION multiple times per scan pass.
        val seenBssids = HashSet<String>()
        val loggedNonMatches = HashSet<String>()
        var scanIndex = 0

        // Surface exactly why a scan might return nothing before we even try.
        // Order matches Android's own gating: Wi-Fi radio → runtime perm.
        Log.i(TAG, "scan(): api=${Build.VERSION.SDK_INT} wifiEnabled=${wifi.isWifiEnabled}")
        for (perm in requiredPermissions()) {
            val granted = ContextCompat.checkSelfPermission(appContext, perm) == PackageManager.PERMISSION_GRANTED
            Log.i(TAG, "scan(): $perm granted=$granted")
        }

        val emit = emit@{
            scanIndex += 1
            val results = runCatching { wifi.scanResults }.getOrElse {
                // On API 33+ this throws SecurityException if we're missing
                // NEARBY_WIFI_DEVICES; on older releases the analogue is
                // ACCESS_FINE_LOCATION. Either way, the message tells us why.
                Log.w(TAG, "scan#$scanIndex: scanResults failed: ${it.javaClass.simpleName}: ${it.message}")
                return@emit
            }
            Log.i(TAG, "scan#$scanIndex: ${results.size} results")
            for (result in results) {
                val ssid = result.ssidText().orEmpty()
                if (ssid.isBlank()) continue
                val bssid = result.BSSID ?: continue
                val advert = CameraVendors.parseSsid(ssid, bssid, result.level)
                if (advert == null) {
                    // Log each non-matching SSID once per scan session so users
                    // can spot a typo / case-mismatch in the vendor's prefix
                    // (e.g. Cooleer_ vs cooleer_) without drowning the log.
                    if (loggedNonMatches.add(bssid)) {
                        Log.d(TAG, "  seen (no match): ssid=$ssid bssid=$bssid rssi=${result.level}")
                    }
                    continue
                }
                if (seenBssids.add(advert.bssid)) {
                    Log.i(TAG, "  MATCH ${advert.vendor.displayName}: ssid=$ssid bssid=${advert.bssid} rssi=${result.level}")
                    trySend(advert)
                }
            }
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) return
                // `EXTRA_RESULTS_UPDATED` tells us if this broadcast reflects
                // a fresh scan (true) or a cached-result nudge (false). Useful
                // when the OS silently throttles startScan(): the receiver
                // still fires with updated=false.
                val updated = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                Log.d(TAG, "SCAN_RESULTS_AVAILABLE (updated=$updated)")
                emit()
            }
        }
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(receiver, filter)
        }

        val emitCurrent: () -> Unit = {
            emitCurrentConnection { advert ->
                if (seenBssids.add(advert.bssid)) {
                    Log.i(TAG, "  MATCH (current connection) ${advert.vendor.displayName}: ssid=${advert.ssid} bssid=${advert.bssid}")
                    trySend(advert)
                }
            }
        }

        // Second receiver, for the case where the user goes to Android's
        // Wi-Fi settings, joins the camera network manually, and returns to
        // the app. NETWORK_STATE_CHANGED_ACTION fires on connect/disconnect;
        // we re-poll the current connection each time.
        val netStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != WifiManager.NETWORK_STATE_CHANGED_ACTION) return
                Log.d(TAG, "NETWORK_STATE_CHANGED: re-checking current connection")
                emitCurrent()
            }
        }
        val netStateFilter = IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(netStateReceiver, netStateFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(netStateReceiver, netStateFilter)
        }

        // First surface anything the OS already knows about the current
        // connection. Some devices refuse to populate `scanResults` for our
        // app (OEMs gating on Location Services even with NEARBY_WIFI_DEVICES
        // + neverForLocation), so if the user has manually joined the camera
        // Wi-Fi from Android settings, this bypass is our reliable way of
        // seeing them. Uses TransportInfo → WifiInfo, no scan needed.
        emitCurrent()

        // Emit anything already cached; kick off a fresh scan to refresh it.
        emit()
        val started = runCatching { wifi.startScan() }
        if (started.isFailure) {
            // `startScan()` throws SecurityException on missing permission and
            // returns false on scan-throttle. Both matter to the diagnosis.
            Log.w(TAG, "startScan: ${started.exceptionOrNull()?.message}")
        } else {
            Log.i(TAG, "startScan: kicked=${started.getOrDefault(false)}")
        }

        awaitClose {
            runCatching { appContext.unregisterReceiver(receiver) }
            runCatching { appContext.unregisterReceiver(netStateReceiver) }
        }
    }

    /** Inspect the phone's Wi-Fi connections and pass through any that a
     *  vendor claims. Runs even when [WifiManager.getScanResults] is silently
     *  empty (OEM Location Services gate, throttling, …), because it reads
     *  the connection state — not a scan.
     *
     *  The camera AP has no internet, so Android often doesn't promote it to
     *  the default `activeNetwork`. We enumerate every network the OS knows
     *  and pick the Wi-Fi one(s) explicitly. */
    private fun emitCurrentConnection(onMatch: (CameraAdvert) -> Unit) {
        val infos = currentWifiInfos()
        if (infos.isEmpty()) {
            Log.d(TAG, "currentConnection: no Wi-Fi networks visible to app")
            return
        }
        for (info in infos) {
            val ssid = info.ssidText().orEmpty()
            val bssid = info.bssid
            Log.i(TAG, "currentConnection: ssid=$ssid bssid=$bssid rssi=${info.rssi}")
            // Log `<unknown ssid>` explicitly. This is what Android returns
            // when the app lacks the permission the platform expects for
            // reading the SSID of the currently-connected network. On API 31+
            // NEARBY_WIFI_DEVICES alone doesn't cover this path; you may need
            // ACCESS_FINE_LOCATION.
            if (ssid.isBlank() || ssid == "<unknown ssid>") {
                Log.w(TAG, "  SSID hidden by OS (missing permission?); skipping")
                continue
            }
            if (bssid == null) continue
            val advert = CameraVendors.parseSsid(ssid, bssid, info.rssi) ?: continue
            onMatch(advert)
        }
    }

    @Suppress("DEPRECATION")
    private fun currentWifiInfos(): List<WifiInfo> {
        val cm = cm ?: return emptyList()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            // API 29-30: WifiManager keeps a single "current connection" via
            // the deprecated `connectionInfo`. Same shape, one entry max.
            return listOfNotNull(wifi.connectionInfo)
        }

        // API 31+: transportInfo on each Wi-Fi-transport Network. Iterate all
        // networks — the camera AP without internet won't show up as
        // `activeNetwork`, so `activeNetwork` alone would miss it.
        val out = mutableListOf<WifiInfo>()
        for (net in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(net) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            val info = caps.transportInfo as? WifiInfo ?: continue
            out += info
        }
        Log.d(TAG, "currentWifiInfos: found ${out.size} Wi-Fi network(s) on device")
        return out
    }

    @Suppress("DEPRECATION")
    private fun WifiInfo.ssidText(): String? {
        // `WifiInfo.getWifiSsid()` (API 33+) is @SystemApi and not available
        // to us; `getSSID()` stays functional across all supported APIs and
        // wraps the SSID in double quotes we strip here.
        return ssid?.trim('"')
    }

    /** `ScanResult.getSSID()` was deprecated in API 33 in favour of
     *  `getWifiSsid()`. Bridge both without leaking the API-level branch. */
    @Suppress("DEPRECATION")
    private fun android.net.wifi.ScanResult.ssidText(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wifiSsid?.toString()?.trim('"')
        } else {
            SSID
        }

    /** Permissions Android requires to actually populate `scanResults`.
     *  Logged at scan start so a silently-empty result set is diagnosable
     *  from the debug log alone. */
    private fun requiredPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    companion object {
        private const val TAG = "WifiScan"
    }
}
