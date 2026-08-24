package dev.rubec.otoscope.stream.earfairy

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for the MJPEG-over-RTP depacketiser. We synthesise the
 * exact byte layout described in RFC 2435 §3.1 and verify the depacketiser
 * assembles a well-formed JPEG file — the same structural checks any JPEG
 * decoder will do on real frames, so drift in either the JPEG-header
 * synthesis or the fragment bookkeeping shows up here.
 */
class RtpJpegDepacketizerTest {

    @Test fun `single-fragment frame produces a JPEG with SOI, tables, and EOI`() {
        val scan = ByteArray(128) { 0x42 }
        val payload = jpegHeader(offset = 0, type = 0, quality = 50, width8 = 8, height8 = 8) + scan
        val jpeg = RtpJpegDepacketizer().feed(payload, markerBitSet = true)
        val out = assertNotNull(jpeg)

        // JPEG structural landmarks.
        assertTrue(out.size > scan.size, "output must include synthesised header + scan")
        assertContentEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte()), out.copyOfRange(0, 2))
        assertContentEquals(
            byteArrayOf(0xFF.toByte(), 0xD9.toByte()),
            out.copyOfRange(out.size - 2, out.size),
        )
        assertMarkerPresent(out, 0xDB) // DQT (quantisation)
        assertMarkerPresent(out, 0xC0) // SOF0 (baseline)
        assertMarkerPresent(out, 0xC4) // DHT (Huffman)
        assertMarkerPresent(out, 0xDA) // SOS (start of scan)

        // Scan bytes are shipped verbatim between SOS and EOI. Search for the
        // known fill pattern.
        assertTrue(
            containsSlice(out, ByteArray(32) { 0x42 }),
            "scan bytes should appear inside the assembled JPEG",
        )
    }

    @Test fun `multi-fragment frame assembles when offsets are contiguous`() {
        val d = RtpJpegDepacketizer()
        val scan1 = ByteArray(64) { 0x11 }
        val scan2 = ByteArray(96) { 0x22 }
        val scan3 = ByteArray(48) { 0x33 }

        val f1 = jpegHeader(offset = 0, type = 0, quality = 50, width8 = 8, height8 = 8) + scan1
        // Subsequent fragments carry only the 8-byte main header (no QT
        // header — Q<128 uses default tables, and only the first fragment
        // ever carries an inline table anyway).
        val f2 = jpegHeader(offset = scan1.size, type = 0, quality = 50, width8 = 8, height8 = 8) + scan2
        val f3 = jpegHeader(offset = scan1.size + scan2.size, type = 0, quality = 50, width8 = 8, height8 = 8) + scan3

        assertNull(d.feed(f1, markerBitSet = false))
        assertNull(d.feed(f2, markerBitSet = false))
        val jpeg = assertNotNull(d.feed(f3, markerBitSet = true))
        assertTrue(
            containsSlice(jpeg, scan1) && containsSlice(jpeg, scan2) && containsSlice(jpeg, scan3),
            "all three scan payloads must survive reassembly",
        )
    }

    @Test fun `missing fragment discards the whole frame`() {
        val d = RtpJpegDepacketizer()
        val scan1 = ByteArray(64) { 1 }
        val scan3 = ByteArray(48) { 3 }
        val f1 = jpegHeader(offset = 0, type = 0, quality = 50, width8 = 8, height8 = 8) + scan1
        // Skip fragment 2 entirely. Fragment 3's offset won't match
        // expectedOffset, so the depacketiser must drop the whole frame.
        val f3 = jpegHeader(offset = scan1.size + 96, type = 0, quality = 50, width8 = 8, height8 = 8) + scan3
        d.feed(f1, markerBitSet = false)
        val result = d.feed(f3, markerBitSet = true)
        assertNull(result, "dropped frame must not emit anything")
    }

    @Test fun `payload shorter than the 8-byte main header returns null`() {
        val short = ByteArray(4)
        assertNull(RtpJpegDepacketizer().feed(short, markerBitSet = true))
    }

    @Test fun `Q above 128 with fragment offset 0 accepts an inline quant table`() {
        val d = RtpJpegDepacketizer()
        // Two distinct 64-byte tables so we can find each one in the output
        // independently — the depacketiser splits them across two DQT
        // segments (marker + table id + 64 bytes each) so they never appear
        // as a single 128-byte contiguous slice in the assembled JPEG.
        val lumaTable = ByteArray(64) { (0x20 + it).toByte() }
        val chromaTable = ByteArray(64) { (0x80 + it).toByte() }
        val scan = ByteArray(64) { 0x77 }

        val header = ByteArray(8 + 4)
        // Main header — offset=0, type=0, Q=200 (inline tables).
        writeMainHeader(header, offset = 0, type = 0, quality = 200, width8 = 8, height8 = 8)
        // QT header (4 bytes): MBZ, precision (0), length (u16 BE) = 128.
        header[8] = 0
        header[9] = 0
        header[10] = 0
        header[11] = 128.toByte()

        val payload = header + lumaTable + chromaTable + scan
        val jpeg = assertNotNull(d.feed(payload, markerBitSet = true))

        // Each 64-byte table appears verbatim inside its own DQT segment.
        assertTrue(
            containsSlice(jpeg, lumaTable),
            "inline luma quant table must be embedded verbatim",
        )
        assertTrue(
            containsSlice(jpeg, chromaTable),
            "inline chroma quant table must be embedded verbatim",
        )
    }

    // ---- helpers -----------------------------------------------------------

    private fun jpegHeader(
        offset: Int,
        type: Int,
        quality: Int,
        width8: Int,
        height8: Int,
    ): ByteArray {
        val h = ByteArray(8)
        writeMainHeader(h, offset, type, quality, width8, height8)
        return h
    }

    private fun writeMainHeader(
        out: ByteArray,
        offset: Int,
        type: Int,
        quality: Int,
        width8: Int,
        height8: Int,
    ) {
        out[0] = 0                                          // Type-specific
        out[1] = ((offset ushr 16) and 0xff).toByte()       // fragment offset (BE)
        out[2] = ((offset ushr 8) and 0xff).toByte()
        out[3] = (offset and 0xff).toByte()
        out[4] = (type and 0xff).toByte()
        out[5] = (quality and 0xff).toByte()
        out[6] = (width8 and 0xff).toByte()
        out[7] = (height8 and 0xff).toByte()
    }

    private fun assertMarkerPresent(jpeg: ByteArray, marker: Int) {
        val ff = 0xFF.toByte()
        val m = marker.toByte()
        for (i in 0 until jpeg.size - 1) {
            if (jpeg[i] == ff && jpeg[i + 1] == m) return
        }
        throw AssertionError("JPEG marker FF ${"%02X".format(marker)} not found")
    }

    private fun containsSlice(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }
}
