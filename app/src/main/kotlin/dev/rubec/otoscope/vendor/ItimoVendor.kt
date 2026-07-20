package dev.rubec.otoscope.vendor

import android.net.Network
import dev.rubec.otoscope.ble.CameraAdvert
import dev.rubec.otoscope.stream.CameraSession
import dev.rubec.otoscope.stream.wudaopu.WudaopuSession

/**
 * Cameras advertising as `iTiMO-XXXXXX` or `jetion_XXXX`.
 *
 * Sold under the "Smart Visual Ear Cleaner" brand, paired with the "iTiMO"
 * companion app (`com.molink.john.itimo`). The wire protocol is the same
 * as Wudaopu — same BLE-advert envelope (`0x66 0x99` magic + 6-byte BSSID),
 * same control channel on UDP/50000, same 24-byte frame header on the video
 * channel, but the video/preview UDP port is `8031` instead of `8032`.
 * We reuse [WudaopuSession] with the port override.
 *
 * The reference app scans Wi-Fi results for the `jetion_` prefix
 * (`com.wifiview.config.WifiFunction`) alongside the newer `iTiMO-`
 * branding, so both spellings identify the same hardware family; we
 * accept either as long as the BLE manufacturer-data envelope matches.
 *
 * Registered before [WudaopuVendor] so the SSID-name match wins on adverts
 * whose manufacturer data would otherwise be claimed by Wudaopu's magic check.
 */
object ItimoVendor : CameraVendor {
    override val displayName = "iTiMO"
    // Hard-coded in the reference iTiMO app (`com.wifiview.nativelibs.CmdSocket`
    // and `com.wifiview.config.WifiFunction`). Some devices don't run DHCP and
    // rely on the phone assigning itself a static `192.168.10.X`; when that
    // happens Android's `LinkProperties.routes` come back empty and this
    // fallback is the only way we know where to send.
    override val defaultCameraIp = "192.168.10.123"

    private const val VIDEO_PORT = 8031
    private val NAME_PREFIXES = listOf("iTiMO-", "jetion_")
    private val MAGIC = byteArrayOf(0x66, 0x99.toByte())

    override fun parseAdvert(
        bleAddress: String,
        deviceName: String?,
        scanRecord: ByteArray?,
        rssi: Int,
    ): CameraAdvert? {
        if (deviceName.isNullOrBlank()) return null
        if (NAME_PREFIXES.none { deviceName.startsWith(it, ignoreCase = true) }) return null
        if (scanRecord == null) return null
        val payload = findManufacturerData(scanRecord, MAGIC) ?: return null
        if (payload.size < 6) return null
        val bssid = (0 until 6).joinToString(":") { "%02X".format(payload[it]) }
        return CameraAdvert(
            vendor = this,
            ssid = deviceName,
            bssid = bssid,
            wpa2Passphrase = null,
            bleAddress = bleAddress,
            rssi = rssi,
        )
    }

    override fun createSession(network: Network?, cameraIp: String): CameraSession =
        WudaopuSession(cameraIp = cameraIp, network = network, videoPort = VIDEO_PORT)
}
