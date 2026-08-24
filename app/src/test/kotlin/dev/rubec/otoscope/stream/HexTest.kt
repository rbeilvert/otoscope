package dev.rubec.otoscope.stream

import kotlin.test.Test
import kotlin.test.assertEquals

/** Trivial but load-bearing: the "first packet" debug lines rely on this
 *  format staying stable. */
class HexTest {

    @Test fun `renders bytes as space-separated lowercase pairs`() {
        val bytes = byteArrayOf(0x66, 0x99.toByte(), 0x01, 0x00, 0xFF.toByte())
        assertEquals("66 99 01 00 ff", bytes.toHex(0, bytes.size))
    }

    @Test fun `respects offset and length`() {
        val bytes = byteArrayOf(0x00, 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0x00)
        assertEquals("aa bb cc", bytes.toHex(1, 3))
    }

    @Test fun `zero-length slice yields an empty string`() {
        assertEquals("", byteArrayOf(1, 2, 3).toHex(1, 0))
    }
}
