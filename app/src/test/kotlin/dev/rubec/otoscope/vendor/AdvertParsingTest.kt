package dev.rubec.otoscope.vendor

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

/**
 * [findManufacturerData] walks the BLE scan-record LTV tree. Every BLE
 * vendor we support relies on it, so a regression here breaks all of them.
 */
class AdvertParsingTest {

    @Test fun `finds a manufacturer-specific record with the exact prefix`() {
        // Layout: L=05 T=0xFF <66 99 AA BB CC DD EE FF>
        val record = byteArrayOf(
            0x09, 0xFF.toByte(),                             // len, type = mfr data
            0x66, 0x99.toByte(),                             // prefix
            0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(),     // payload
            0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte(),
        )
        val out = findManufacturerData(record, byteArrayOf(0x66, 0x99.toByte()))
        assertContentEquals(
            byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte()),
            out,
        )
    }

    @Test fun `skips unrelated LTV records before the target`() {
        val record = byteArrayOf(
            0x02, 0x01, 0x06,                                // flags AD, ignored
            0x03, 0x03, 0x0A, 0x18,                          // 16-bit service UUIDs, ignored
            0x08, 0xFF.toByte(),                             // len=8, type=0xFF
            0x0F, 0x27,                                       // company ID (JEGOAT)
            0x01, 0x02, 0x03, 0x04, 0x05,
        )
        val out = findManufacturerData(record, byteArrayOf(0x0F, 0x27))
        assertContentEquals(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05), out)
    }

    @Test fun `returns null when the prefix does not match any record`() {
        val record = byteArrayOf(
            0x05, 0xFF.toByte(),
            0x99.toByte(), 0x66, 0xAA.toByte(), 0xBB.toByte(),
        )
        assertNull(findManufacturerData(record, byteArrayOf(0x66, 0x99.toByte())))
    }

    @Test fun `returns null on a zero-length LTV terminator`() {
        val record = byteArrayOf(0x00)
        assertNull(findManufacturerData(record, byteArrayOf(0x66, 0x99.toByte())))
    }

    @Test fun `returns null on a truncated record`() {
        // len=10 but there are only 4 bytes after the length byte.
        val record = byteArrayOf(0x0A, 0xFF.toByte(), 0x66, 0x99.toByte(), 0xAA.toByte())
        assertNull(findManufacturerData(record, byteArrayOf(0x66, 0x99.toByte())))
    }
}
