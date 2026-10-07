package dev.rubec.otoscope.ble

import dev.rubec.otoscope.vendor.CameraVendor

/**
 * A camera discovered during a scan. The vendor-specific advert format (e.g.
 * Xylla's `0x66 0x99` magic vs JEGOAT's `0x270F` company ID) is hidden behind
 * [vendor], which the rest of the app consults when it needs to dispatch on
 * hardware. The scan path that produced the advert is implicit in
 * [CameraVendor.discoveryMode].
 */
data class CameraAdvert(
    val vendor: CameraVendor,
    val ssid: String,
    val bssid: String,
    /** Non-null = WPA2-secured AP, with this exact passphrase (vendor-derived).
     *  Null = the AP is open. */
    val wpa2Passphrase: String?,
    /** BLE MAC of the advertising device. Some vendors need a GATT exchange on
     *  this address before the camera enables its WiFi AP.
     *  See [CameraVendor.preWifiHandshake]. */
    val bleAddress: String,
    val rssi: Int,
)
