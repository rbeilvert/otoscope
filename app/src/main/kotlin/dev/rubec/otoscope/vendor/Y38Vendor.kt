package dev.rubec.otoscope.vendor

import android.content.Context
import android.net.Network
import dev.rubec.otoscope.ble.CameraAdvert
import dev.rubec.otoscope.stream.CameraSession
import dev.rubec.otoscope.stream.xylla.XyllaSession

/**
 * Y38 otoscope family: a Wi-Fi-only sibling of [XyllaVendor] that broadcasts
 * `AIR-ES-XXXXXX` and never emits a BLE advert, so BLE discovery can't reach
 * it. The AP is open and always on.
 *
 * The wire protocol is the same `0x66 0x99` UDP/8032 MJPEG shape — i.e. the
 * [XyllaSession] implementation is reused as-is. Only the discovery path and
 * the user-visible branding differ. Keeping Y38 a distinct vendor rather than
 * a second mode on `XyllaVendor` means the home-screen Wi-Fi card shows "Y38",
 * not "Xylla", for a device the user bought under the Y38 name.
 */
object Y38Vendor : CameraVendor {
    override val displayName = "Y38"
    override val discoveryMode = DiscoveryMode.WIFI_SCAN
    override val defaultCameraIp = "192.168.0.10"

    /** Full-match SSID shape, not a bare prefix: a false positive is more
     *  than cosmetic since a listed SSID can be joined — or adopted if
     *  already connected — binding all of the app's traffic to it. The two
     *  firmware spellings observed in the wild are `AIR-ES-4d3418` and
     *  `AIR-ES6a78c8`; the dash placement varies, so we accept either and
     *  require at least a 3-char hex-like tag after. */
    private val SSID_PATTERN = Regex("""(?i)AIR-ES[-_]?[0-9a-z]{3,}""")

    override fun parseSsid(ssid: String, bssid: String, rssi: Int): CameraAdvert? {
        if (!SSID_PATTERN.matches(ssid)) return null
        return CameraAdvert(
            vendor = this,
            ssid = ssid,
            bssid = bssid,
            wpa2Passphrase = null,
            // No BLE for this vendor, same as the other Wi-Fi-scan families.
            bleAddress = "",
            rssi = rssi,
        )
    }

    override fun createSession(context: Context, network: Network?, cameraIp: String): CameraSession =
        XyllaSession(cameraIp = cameraIp, network = network)
}
