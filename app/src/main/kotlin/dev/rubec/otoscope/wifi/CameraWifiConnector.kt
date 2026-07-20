package dev.rubec.otoscope.wifi

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiNetworkSuggestion
import dev.rubec.otoscope.debug.FileLog as Log
import dev.rubec.otoscope.ble.CameraAdvert
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.Inet4Address
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Joins the camera's WiFi access point. Uses [WifiNetworkSpecifier] (API 29+)
 * so the camera network exists only for the lifetime of the process binding
 * and the user's saved WiFi config isn't touched.
 *
 * A [WifiNetworkSuggestion] is filed in parallel because some Android skins
 * (MIUI, OneUI) need it to show the inline connect notification cleanly
 * instead of bouncing the user out to Settings.
 */
class CameraWifiConnector(context: Context) {

    private val appContext = context.applicationContext
    private val cm = appContext.getSystemService(ConnectivityManager::class.java)
        ?: error("ConnectivityManager unavailable")
    private val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var current: ConnectivityManager.NetworkCallback? = null
    private var lastSuggestions: List<WifiNetworkSuggestion> = emptyList()

    /** Fires when the bound camera network drops after a successful connect —
     *  e.g. the user powered off the camera. Cleared automatically on
     *  [disconnect]. Assigned by the ViewModel so it can react without polling. */
    var onNetworkLost: (() -> Unit)? = null

    /** The bound camera network — non-null while connected. Hand its SocketFactory
     *  to any networking library so its sockets route through the camera AP
     *  instead of whatever Android picks as the default. */
    var currentNetwork: Network? = null
        private set

    /** The phone's IPv4 address on the camera network. */
    var localIp: String? = null
        private set

    /** Best guess at the camera's IPv4 address. Defaults to the network gateway,
     *  which on these cheap APs is the camera itself. */
    var gatewayIp: String? = null
        private set

    @SuppressLint("MissingPermission")
    suspend fun connect(advert: CameraAdvert): Network = suspendCancellableCoroutine { cont ->
        // 1. File a suggestion. This lets some launchers pop an inline notification
        //    "App wants to connect to Enjoy-XXXX" rather than opening Settings.
        runCatching {
            val suggestion = WifiNetworkSuggestion.Builder()
                .setSsid(advert.ssid)
                .setIsAppInteractionRequired(true)
                .build()
            wifi.removeNetworkSuggestions(lastSuggestions)
            lastSuggestions = listOf(suggestion)
            wifi.addNetworkSuggestions(lastSuggestions)
        }.onFailure { Log.w(TAG, "Suggestion failed: ${it.message}") }

        // 2. File the specifier request. This is what actually establishes the
        //    bound network we'll send UDP through. Wudaopu APs are open; JEGOAT
        //    is WPA2 with a per-camera passphrase carried in the BLE advert.
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(advert.ssid)
            .setBssid(MacAddress.fromString(advert.bssid))
            .apply { advert.wpa2Passphrase?.let { setWpa2Passphrase(it) } }
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifier)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                cm.bindProcessToNetwork(network)
                currentNetwork = network
                captureDiagnostics(network)
                Log.i(TAG, "joined ${advert.ssid}: local=$localIp gateway=$gatewayIp")
                if (cont.isActive) cont.resume(network)
            }

            override fun onUnavailable() {
                if (cont.isActive) cont.resumeWithException(
                    IllegalStateException("Could not join WiFi ${advert.ssid}")
                )
            }

            override fun onLost(network: Network) {
                Log.w(TAG, "Lost WiFi ${advert.ssid}")
                // Only trip the listener if we had already reported success. The
                // suspendCancellableCoroutine hasn't resumed yet if we lose the
                // network before onAvailable fires, and the connect() caller
                // handles that path via onUnavailable / cancellation.
                if (!cont.isActive && currentNetwork == network) {
                    onNetworkLost?.invoke()
                }
            }
        }

        current = callback
        cm.requestNetwork(request, callback)
        cont.invokeOnCancellation { disconnect() }
    }

    fun disconnect() {
        // Clear the loss listener FIRST so a race where the network drops during
        // our own teardown doesn't fire an unexpected-disconnect notification.
        onNetworkLost = null
        current?.let {
            runCatching { cm.unregisterNetworkCallback(it) }
            current = null
        }
        if (lastSuggestions.isNotEmpty()) {
            runCatching { wifi.removeNetworkSuggestions(lastSuggestions) }
            lastSuggestions = emptyList()
        }
        runCatching { cm.bindProcessToNetwork(null) }
        currentNetwork = null
        localIp = null
        gatewayIp = null
    }

    private fun captureDiagnostics(network: Network) {
        val props = cm.getLinkProperties(network) ?: return
        localIp = props.linkAddresses
            .map { it.address }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isAnyLocalAddress }
            ?.hostAddress
        gatewayIp = props.routes
            .firstNotNullOfOrNull { route ->
                route.gateway
                    ?.takeIf { it is Inet4Address && !it.isAnyLocalAddress }
                    ?.hostAddress
            }
    }

    companion object {
        private const val TAG = "WifiConnector"
    }
}
