package dev.rubec.otoscope.ble

/**
 * A camera discovered over BLE. The camera hosts an open WiFi AP whose SSID matches
 * the BLE device name (e.g. "Enjoy-3B3D90", "JesHome-XXXX") and whose BSSID is
 * embedded in the BLE manufacturer data.
 */
data class CameraAdvert(
    val ssid: String,
    val bssid: String,
    val rssi: Int
) {
    companion object {
        /** Magic vendor bytes that the Wudaopu otoscope ships at the start of its manufacturer data. */
        private val MAGIC = byteArrayOf(0x66, 0x99.toByte())

        /**
         * Parse a BLE scan record. Walks the LTV (length/type/value) AD structures
         * looking for a manufacturer-specific data record (type 0xFF) whose payload
         * starts with [MAGIC] and is followed by a 6-byte BSSID.
         *
         * The vendor wires the "vendor ID" bytes as 0x66, 0x99 — which the original
         * decompiled Java compares against the hex literal "6699" rather than the
         * canonical little-endian 0x9966. We match the raw bytes either way.
         */
        fun parse(deviceName: String?, scanRecord: ByteArray?, rssi: Int): CameraAdvert? {
            if (deviceName.isNullOrBlank() || scanRecord == null) return null
            val payload = findManufacturerData(scanRecord) ?: return null
            if (payload.size < MAGIC.size + 6) return null
            if (payload[0] != MAGIC[0] || payload[1] != MAGIC[1]) return null
            val bssid = (2 until 8).joinToString(":") { "%02X".format(payload[it]) }
            return CameraAdvert(ssid = deviceName, bssid = bssid, rssi = rssi)
        }

        private fun findManufacturerData(record: ByteArray): ByteArray? {
            var i = 0
            while (i < record.size) {
                val len = record[i].toInt() and 0xFF
                if (len == 0) return null
                if (i + len >= record.size) return null
                val type = record[i + 1].toInt() and 0xFF
                if (type == 0xFF && len >= 3) {
                    return record.copyOfRange(i + 2, i + 1 + len)
                }
                i += len + 1
            }
            return null
        }
    }
}
