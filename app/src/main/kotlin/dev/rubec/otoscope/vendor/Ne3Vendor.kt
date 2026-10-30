package dev.rubec.otoscope.vendor

import android.content.Context
import android.net.Network
import dev.rubec.otoscope.ble.CameraAdvert
import dev.rubec.otoscope.stream.CameraSession
import dev.rubec.otoscope.stream.beken.BekenSession
import dev.rubec.otoscope.stream.ne3.Ne3Session

/**
 * NE3 otoscope family, sold under the "HND" companion app
 * (`com.xiaozhen.beauty.hnd`). Two hardware generations share the `HNDEC_`
 * SSID and the app, and are told apart by the camera's IP (see [createSession]):
 *
 *  - **Bouffalo BL602** at `192.168.169.1` (this file's wire summary below,
 *    [Ne3Session]).
 *  - **Beken BK7231U** at `192.168.1.1`, a different protocol on UDP 46526 /
 *    44506 / 52219, see [BekenSession] and `docs/beken-protocol.md`.
 *
 * Bouffalo Lab BL602-based hardware; the
 * vendor app is a thin JNI shim over Bouffalo Lab's "VII" (Video-over-IP)
 * SDK. We reimplement the wire protocol directly — the video is
 * fragmented headerless MJPEG on UDP/8800 that we assemble in
 * [dev.rubec.otoscope.stream.ne3.Ne3FrameAssembler] and complete with the
 * pre-baked JPEG header lifted from the vendor SDK
 * ([dev.rubec.otoscope.stream.ne3.Ne3JpegHeader]).
 *
 * Wire summary:
 *  - Open Wi-Fi AP, camera at `192.168.169.1`. The vendor HND app's one
 *    hard-coded passphrase `"1234567890"` lives in a BLE-discovery helper
 *    that's never reached by the NE3 path — the HND app for NE3 just opens
 *    the system Wi-Fi picker and lets the user tap through. Field logs
 *    (issue #30) confirm the AP responds to an open-SSID
 *    [android.net.wifi.WifiNetworkSpecifier] with no passphrase declared;
 *    declaring WPA2 made Android filter the SSID out of its specifier scan
 *    and surface "No devices found" instead.
 *  - Video: UDP/8800. Hello packet `EF 00 04 00` @ ~10 Hz starts the
 *    stream; fragmented JPEG datagrams flow back.
 *  - Control + telemetry: not yet implemented (accelerometer on TCP/2271,
 *    MCU messages on the same UDP video channel; formats undecoded).
 */
object Ne3Vendor : CameraVendor {
    override val displayName = "NE3"
    override val discoveryMode = DiscoveryMode.WIFI_SCAN
    override val defaultCameraIp = "192.168.169.1"

    /** SSID prefix. The user-reported example was `HNDEC_55-XXXXXX`; the
     *  `_55-` chunk is likely a model / batch code, so we match on the
     *  broader `HNDEC_` root and let sibling SKUs come along for free. */
    private const val SSID_PREFIX = "HNDEC_"

    override fun parseSsid(ssid: String, bssid: String, rssi: Int): CameraAdvert? {
        if (!ssid.startsWith(SSID_PREFIX, ignoreCase = true)) return null
        return CameraAdvert(
            vendor = this,
            ssid = ssid,
            bssid = bssid,
            wpa2Passphrase = null,
            // No BLE for this vendor.
            bleAddress = "",
            rssi = rssi,
        )
    }

    /** Only the Bouffalo camera lives at [defaultCameraIp]; the Beken camera's
     *  DHCP gateway is `192.168.1.1`. UDP/8800 is closed on the Beken camera
     *  (it answers ICMP port-unreachable), so guessing wrong never streams. */
    override fun createSession(context: Context, network: Network?, cameraIp: String): CameraSession =
        if (isBouffalo(cameraIp)) Ne3Session(cameraIp = cameraIp, network = network)
        else BekenSession(cameraIp = cameraIp, network = network)

    internal fun isBouffalo(cameraIp: String): Boolean = cameraIp.startsWith(BOUFFALO_SUBNET)

    private const val BOUFFALO_SUBNET = "192.168.169."
}
