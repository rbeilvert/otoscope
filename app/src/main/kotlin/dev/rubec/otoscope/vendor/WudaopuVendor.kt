package dev.rubec.otoscope.vendor

import android.net.Network
import dev.rubec.otoscope.ble.CameraAdvert
import dev.rubec.otoscope.stream.CameraSession
import dev.rubec.otoscope.stream.wudaopu.WudaopuSession

/**
 * Cameras advertising as `Enjoy-XXXXXX`, `JesHome-XXXX`, and similar.
 *
 * BLE advert: manufacturer-specific data (type 0xFF) starting with the magic
 * bytes `0x66 0x99` (not a Bluetooth-SIG company ID — used in place of one)
 * followed by a 6-byte WiFi BSSID. SSID is the BLE device name. The AP is
 * open (no passphrase) and always advertised; no BLE GATT handshake required.
 */
object WudaopuVendor : CameraVendor {
    override val displayName = "Wudaopu"
    override val defaultCameraIp = "192.168.0.10"

    private val MANUFACTURER_DATA_PREFIX = byteArrayOf(0x66, 0x99.toByte())

    override fun parseAdvert(
        bleAddress: String,
        deviceName: String?,
        scanRecord: ByteArray?,
        rssi: Int,
    ): CameraAdvert? {
        if (deviceName.isNullOrBlank() || scanRecord == null) return null
        val payload = findManufacturerData(scanRecord, MANUFACTURER_DATA_PREFIX) ?: return null
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
        WudaopuSession(cameraIp = cameraIp, network = network)
}
