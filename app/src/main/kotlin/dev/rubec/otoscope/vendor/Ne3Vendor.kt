package dev.rubec.otoscope.vendor

import android.content.Context
import android.net.Network
import dev.rubec.otoscope.ble.CameraAdvert
import dev.rubec.otoscope.stream.CameraSession
import dev.rubec.otoscope.stream.ne3.Ne3Session

/**
 * NE3 otoscope family, sold under the "HND" companion app
 * (`com.xiaozhen.beauty.hnd`). Bouffalo Lab BL602-based hardware; the
 * vendor app is a thin JNI shim over Bouffalo Lab's "VII" (Video-over-IP)
 * SDK. We reimplement the wire protocol directly — the video is
 * fragmented headerless MJPEG on UDP/8800 that we assemble in
 * [dev.rubec.otoscope.stream.ne3.Ne3FrameAssembler] and complete with the
 * pre-baked JPEG header lifted from the vendor SDK
 * ([dev.rubec.otoscope.stream.ne3.Ne3JpegHeader]).
 *
 * Wire summary:
 *  - WPA2 Wi-Fi AP, camera at `192.168.169.1`. The passphrase
 *    `"1234567890"` is baked into the vendor app; there is no per-device
 *    variation and no in-app WPA setup — the vendor's own app just opens
 *    the system Wi-Fi picker and hopes the user types the right thing.
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

    /** WPA2 passphrase baked into the vendor companion app's Wi-Fi
     *  setup path (`a1/b.java:89`). Camera family ships with this exact
     *  string; a variant with a different passphrase would need its own
     *  vendor or a runtime prompt. */
    private const val WPA2_PASSPHRASE = "1234567890"

    override fun parseSsid(ssid: String, bssid: String, rssi: Int): CameraAdvert? {
        if (!ssid.startsWith(SSID_PREFIX, ignoreCase = true)) return null
        return CameraAdvert(
            vendor = this,
            ssid = ssid,
            bssid = bssid,
            wpa2Passphrase = WPA2_PASSPHRASE,
            // No BLE for this vendor.
            bleAddress = "",
            rssi = rssi,
        )
    }

    override fun createSession(context: Context, network: Network?, cameraIp: String): CameraSession =
        Ne3Session(cameraIp = cameraIp, network = network)
}
