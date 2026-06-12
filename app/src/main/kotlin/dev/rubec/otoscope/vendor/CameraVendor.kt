package dev.rubec.otoscope.vendor

import android.content.Context
import android.net.Network
import dev.rubec.otoscope.ble.CameraAdvert
import dev.rubec.otoscope.stream.CameraSession

/**
 * One physical product family that this app knows how to talk to.
 *
 * Each vendor owns:
 *  - **BLE advert parsing** — the magic bytes that identify the vendor and how
 *    to pull SSID / BSSID / (optionally) WPA2 passphrase out of the manufacturer
 *    data.
 *  - **Pre-WiFi handshake** — some vendors need a BLE GATT exchange to make the
 *    camera enable its WiFi AP. Default is a no-op.
 *  - **WiFi defaults** — the camera's IP when DHCP doesn't tell us.
 *  - **Session creation** — a [CameraSession] that speaks the vendor's specific
 *    streaming and control protocols.
 *
 * Adding a new vendor:
 *  1. Implement this interface as a singleton `object`.
 *  2. Add it to [CameraVendors.all].
 *  3. Provide a matching [CameraSession] implementation.
 */
interface CameraVendor {
    /** Human-readable name, used in logs and diagnostic strings. */
    val displayName: String

    /** Fallback for the camera's IP when [android.net.LinkProperties.routes]
     *  yields no gateway. Almost always overridden by the DHCP-assigned gateway. */
    val defaultCameraIp: String

    /** Try to parse a BLE scan into a [CameraAdvert]. Returns null when the
     *  vendor's signature isn't present — frees the registry to try the next one.
     *
     *  [bleAddress] is the device's BLE MAC (from `ScanResult.device.address`) —
     *  required by vendors that need a GATT round-trip in [preWifiHandshake]. */
    fun parseAdvert(
        bleAddress: String,
        deviceName: String?,
        scanRecord: ByteArray?,
        rssi: Int,
    ): CameraAdvert?

    /** Hook called between BLE discovery and WiFi join. Override when the
     *  camera needs a BLE-GATT poke before its AP comes up (e.g. JEGOAT reads
     *  service `0x9900` / char `0x0099`). Default no-op covers vendors whose
     *  WiFi AP is always on. */
    suspend fun preWifiHandshake(context: Context, advert: CameraAdvert) {
        // no-op
    }

    /** Create a freshly-bound (but not yet [CameraSession.start]ed) session. */
    fun createSession(network: Network?, cameraIp: String): CameraSession
}

/** Central registry of all supported vendors. */
object CameraVendors {
    val all: List<CameraVendor> = listOf(WudaopuVendor, JegoatVendor)

    /** Ask each registered vendor in order whether it recognizes this advert. */
    fun parseAdvert(
        bleAddress: String,
        deviceName: String?,
        scanRecord: ByteArray?,
        rssi: Int,
    ): CameraAdvert? {
        for (vendor in all) {
            vendor.parseAdvert(bleAddress, deviceName, scanRecord, rssi)?.let { return it }
        }
        return null
    }
}
