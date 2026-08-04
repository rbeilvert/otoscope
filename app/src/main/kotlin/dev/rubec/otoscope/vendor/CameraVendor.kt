package dev.rubec.otoscope.vendor

import android.content.Context
import android.net.Network
import dev.rubec.otoscope.ble.CameraAdvert
import dev.rubec.otoscope.stream.CameraSession

/**
 * One physical product family that this app knows how to talk to.
 *
 * Each vendor owns:
 *  - **Discovery mode**: how the app finds the camera (BLE advert scan or
 *    Wi-Fi SSID scan).
 *  - **Advert parsing**: turns a scan hit into a [CameraAdvert] the ViewModel
 *    can act on.
 *  - **Optional pre-Wi-Fi handshake**: some cameras need a BLE GATT read to
 *    wake their AP.
 *  - **Session creation**: a [CameraSession] speaking the vendor's protocol.
 *
 * Adding a new vendor:
 *  1. Implement this interface as a singleton `object`.
 *  2. Add it to [CameraVendors.all].
 *  3. Provide a matching [CameraSession] implementation.
 */
interface CameraVendor {
    /** Human-readable name, used in the model picker, logs, and diagnostics. */
    val displayName: String

    /** How the user's device finds this vendor's cameras. Governs which scan
     *  path the ViewModel runs when the user picks this family on the home
     *  screen. */
    val discoveryMode: DiscoveryMode

    /** Fallback for the camera's IP when [android.net.LinkProperties.routes]
     *  yields no gateway. Almost always overridden by the DHCP-assigned gateway. */
    val defaultCameraIp: String

    /** Try to parse a BLE scan into a [CameraAdvert]. Returns null when the
     *  vendor's signature isn't present. Only called for [DiscoveryMode.BLE]
     *  vendors.
     *
     *  [bleAddress] is the device's BLE MAC (from `ScanResult.device.address`);
     *  required by vendors that need a GATT round-trip in [preWifiHandshake]. */
    fun parseAdvert(
        bleAddress: String,
        deviceName: String?,
        scanRecord: ByteArray?,
        rssi: Int,
    ): CameraAdvert? = null

    /** Try to parse a Wi-Fi scan hit into a [CameraAdvert]. Returns null when
     *  the SSID doesn't match this vendor's pattern. Only called for
     *  [DiscoveryMode.WIFI_SCAN] vendors. */
    fun parseSsid(ssid: String, bssid: String, rssi: Int): CameraAdvert? = null

    /** Hook called between BLE discovery and Wi-Fi join. Override when the
     *  camera needs a BLE-GATT poke before its AP comes up (e.g. JEGOAT reads
     *  service `0x9900` / char `0x0099`). Default no-op covers vendors whose
     *  Wi-Fi AP is always on. */
    suspend fun preWifiHandshake(context: Context, advert: CameraAdvert) {
        // no-op
    }

    /** Create a freshly-bound (but not yet [CameraSession.start]ed) session.
     *  [context] gives vendors that use Android platform APIs (e.g. Media3's
     *  ExoPlayer) somewhere to root. Application-scoped is fine. */
    fun createSession(context: Context, network: Network?, cameraIp: String): CameraSession
}

/** Where the app looks to discover a vendor's cameras. */
enum class DiscoveryMode {
    /** BLE scan; the advert carries the Wi-Fi SSID/BSSID for auto-join. */
    BLE,

    /** Wi-Fi scan; the app filters visible SSIDs by prefix and lets the user
     *  connect to a specific one. */
    WIFI_SCAN,
}

/** Central registry of all supported vendors. */
object CameraVendors {
    // iTiMO is checked before Xylla because both share the `0x66 0x99` BLE
    // manufacturer magic; the SSID-name check on iTiMO would lose otherwise.
    val all: List<CameraVendor> = listOf(ItimoVendor, XyllaVendor, JegoatVendor, EarFairyVendor)

    val bleVendors: List<CameraVendor> = all.filter { it.discoveryMode == DiscoveryMode.BLE }
    val wifiScanVendors: List<CameraVendor> = all.filter { it.discoveryMode == DiscoveryMode.WIFI_SCAN }

    /** Ask each registered BLE vendor in order whether it recognizes this advert. */
    fun parseAdvert(
        bleAddress: String,
        deviceName: String?,
        scanRecord: ByteArray?,
        rssi: Int,
    ): CameraAdvert? {
        for (vendor in bleVendors) {
            vendor.parseAdvert(bleAddress, deviceName, scanRecord, rssi)?.let { return it }
        }
        return null
    }

    /** Ask each Wi-Fi-scan vendor whether it recognizes this SSID. */
    fun parseSsid(ssid: String, bssid: String, rssi: Int): CameraAdvert? {
        for (vendor in wifiScanVendors) {
            vendor.parseSsid(ssid, bssid, rssi)?.let { return it }
        }
        return null
    }
}
