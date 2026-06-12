package dev.rubec.otoscope.vendor

import android.content.Context
import android.net.Network
import dev.rubec.otoscope.ble.BleKnock
import dev.rubec.otoscope.ble.CameraAdvert
import dev.rubec.otoscope.stream.CameraSession
import dev.rubec.otoscope.stream.jegoat.JegoatSession
import java.util.UUID

/**
 * Cameras advertising as `softish-XXXXXX`.
 *
 * BLE advert: manufacturer-specific data (type 0xFF) with company ID `0x270F`
 * (= 9999 decimal, little-endian on the wire), then 12 payload bytes — a
 * 6-byte build timestamp (skipped here for forward compatibility) and the
 * 6-byte WiFi BSSID. The AP is WPA2-secured; the passphrase is the BSSID
 * printed as 12 lowercase-hex characters with no separators.
 *
 * The camera doesn't enable its WiFi AP until a client performs a BLE GATT
 * read on [KNOCK_SERVICE] / [KNOCK_CHARACTERISTIC]. The returned value is
 * irrelevant — the read itself is the trigger. Without it the SSID never
 * appears in WiFi scans.
 */
object JegoatVendor : CameraVendor {
    override val displayName = "JEGOAT"
    override val defaultCameraIp = "192.168.1.1"

    private val COMPANY_ID = byteArrayOf(0x0F, 0x27)
    private val KNOCK_SERVICE: UUID = UUID.fromString("00009900-0000-1000-8000-00805f9b34fb")
    private val KNOCK_CHARACTERISTIC: UUID = UUID.fromString("00000099-0000-1000-8000-00805f9b34fb")

    override fun parseAdvert(
        bleAddress: String,
        deviceName: String?,
        scanRecord: ByteArray?,
        rssi: Int,
    ): CameraAdvert? {
        if (deviceName.isNullOrBlank() || scanRecord == null) return null
        val payload = findManufacturerData(scanRecord, COMPANY_ID) ?: return null
        if (payload.size < 12) return null
        val bssidBytes = payload.copyOfRange(6, 12)
        val bssid = bssidBytes.joinToString(":") { "%02X".format(it) }
        val passphrase = bssidBytes.joinToString("") { "%02x".format(it) }
        return CameraAdvert(
            vendor = this,
            ssid = deviceName,
            bssid = bssid,
            wpa2Passphrase = passphrase,
            bleAddress = bleAddress,
            rssi = rssi,
        )
    }

    override suspend fun preWifiHandshake(context: Context, advert: CameraAdvert) {
        BleKnock.readCharacteristic(
            context = context,
            deviceAddress = advert.bleAddress,
            serviceUuid = KNOCK_SERVICE,
            characteristicUuid = KNOCK_CHARACTERISTIC,
        )
        // Return value intentionally discarded — only the read side-effect matters.
    }

    override fun createSession(network: Network?, cameraIp: String): CameraSession =
        JegoatSession(cameraIp = cameraIp, network = network)
}
