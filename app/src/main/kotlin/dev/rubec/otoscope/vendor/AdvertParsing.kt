package dev.rubec.otoscope.vendor

/**
 * Walk the BLE scan record's LTV (length/type/value) structure looking for a
 * manufacturer-specific data record (AD type `0xFF`) whose payload starts with
 * [prefix]. Returns the payload bytes AFTER the prefix, or null if no such record
 * is present.
 *
 * Both Wudaopu (`0x66 0x99`) and JEGOAT (`0x0F 0x27` = company ID 0x270F LE) use
 * the same envelope; they only differ in the prefix and the bytes after it.
 */
internal fun findManufacturerData(record: ByteArray, prefix: ByteArray): ByteArray? {
    var i = 0
    while (i < record.size) {
        val len = record[i].toInt() and 0xFF
        if (len == 0) return null
        if (i + len >= record.size) return null
        val type = record[i + 1].toInt() and 0xFF
        if (type == 0xFF && len >= 1 + prefix.size) {
            val payloadStart = i + 2
            val payloadEnd = i + 1 + len
            if (prefix.indices.all { record[payloadStart + it] == prefix[it] }) {
                return record.copyOfRange(payloadStart + prefix.size, payloadEnd)
            }
        }
        i += len + 1
    }
    return null
}
