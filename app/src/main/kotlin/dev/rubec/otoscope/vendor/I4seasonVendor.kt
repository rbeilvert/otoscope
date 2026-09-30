package dev.rubec.otoscope.vendor

import android.content.Context
import android.net.Network
import dev.rubec.otoscope.ble.CameraAdvert
import dev.rubec.otoscope.stream.CameraSession
import dev.rubec.otoscope.stream.i4season.I4seasonSession

/**
 * i4season otoscope family (e.g. Hopefox Find T), paired on Android with the
 * "Soulear" companion app (`com.i4season.bkCamera_soulear`).
 *
 * Like EarFairy there is no BLE component: the camera runs an open Wi-Fi AP
 * named `Soulear-XXXXX`, so we discover it with a Wi-Fi scan on that prefix.
 *
 * Wire summary (details in [dev.rubec.otoscope.stream.i4season.I4seasonProtocol]):
 *  - Open Wi-Fi AP, camera at `192.168.1.1`.
 *  - Control: request/response UDP datagrams with a `0xFFEEFFEE` magic,
 *    UDP/10005 for device info and UDP/10006 for "open video".
 *  - Video: JPEG chunks over UDP to the local port announced in "open video",
 *    each with a 16-byte header carrying a packed 3-axis accelerometer sample.
 */
object I4seasonVendor : CameraVendor {
    override val displayName = "Soulear"
    override val discoveryMode = DiscoveryMode.WIFI_SCAN
    override val defaultCameraIp = "192.168.1.1"

    /** SSID prefix matches the companion app's name, not the device brand
     *  (Hopefox, …). The firmware bakes it in. */
    private const val SSID_PREFIX = "Soulear-"

    override fun parseSsid(ssid: String, bssid: String, rssi: Int): CameraAdvert? {
        if (!ssid.startsWith(SSID_PREFIX, ignoreCase = true)) return null
        return CameraAdvert(
            vendor = this,
            ssid = ssid,
            bssid = bssid,
            wpa2Passphrase = null,
            // No BLE address for this vendor, same as EarFairy.
            bleAddress = "",
            rssi = rssi,
        )
    }

    override fun createSession(context: Context, network: Network?, cameraIp: String): CameraSession =
        I4seasonSession(cameraIp = cameraIp, network = network)
}
