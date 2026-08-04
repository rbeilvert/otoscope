package dev.rubec.otoscope.vendor

import android.content.Context
import android.net.Network
import dev.rubec.otoscope.ble.CameraAdvert
import dev.rubec.otoscope.stream.CameraSession
import dev.rubec.otoscope.stream.earfairy.EarFairySession

/**
 * EarFairy otoscope family (e.g. EarFairy Y-201), paired on Android with the
 * "Cooleer" companion app (`com.cooingdv.cooleer`).
 *
 * The companion app has no BLE component at all — users are expected to join
 * the camera's open Wi-Fi from Android settings, then the app streams video
 * over standard RTSP. We match the same shape via an in-app Wi-Fi scan
 * filtered by the SSID prefix `Cooleer_`.
 *
 * Wire summary:
 *  - Open Wi-Fi AP, camera at `192.168.1.1`.
 *  - Video: `rtsp://192.168.1.1:7070/webcam` — MJPEG-over-RTP (payload type
 *    26, RFC 2435) delivered on a UDP unicast pair set up via a plain RTSP
 *    handshake. Decoded by our own [EarFairySession] because Media3's RTSP
 *    source doesn't support that payload type.
 *  - Control + telemetry: UDP/7099. Heartbeat is 2 bytes `[0x01, 0x01]` at
 *    ~1 Hz to keep the camera pushing rotation + battery frames; LED
 *    on/off command is 2 bytes. Frame format is in
 *    [dev.rubec.otoscope.stream.earfairy.EarFairyControlClient].
 */
object EarFairyVendor : CameraVendor {
    override val displayName = "EarFairy"
    override val discoveryMode = DiscoveryMode.WIFI_SCAN
    override val defaultCameraIp = "192.168.1.1"

    /** SSID prefix matches the companion app's own name (Cooleer), not the
     *  device brand (EarFairy). The firmware bakes it in either way. */
    private const val SSID_PREFIX = "Cooleer_"

    override fun parseSsid(ssid: String, bssid: String, rssi: Int): CameraAdvert? {
        if (!ssid.startsWith(SSID_PREFIX, ignoreCase = true)) return null
        return CameraAdvert(
            vendor = this,
            ssid = ssid,
            bssid = bssid,
            wpa2Passphrase = null,
            // No BLE address for this vendor. Kept blank rather than nullable
            // to avoid loosening the shared advert data class for one case;
            // preWifiHandshake is a no-op anyway.
            bleAddress = "",
            rssi = rssi,
        )
    }

    override fun createSession(context: Context, network: Network?, cameraIp: String): CameraSession =
        EarFairySession(cameraIp = cameraIp, network = network)
}
