package dev.rubec.otoscope.stream.ne3

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integrity checks on the pre-baked JPEG header. It's lifted verbatim from
 * the vendor SDK, so a byte-level regression (a base64 typo, a byte reorder)
 * would break every NE3 frame on device but slip through a plain-build run.
 * These assertions catch that class of drift at test time.
 */
class Ne3JpegHeaderTest {

    @Test fun `header decodes to the expected 605-byte blob`() {
        // 605 bytes is what the vendor SDK ships at .rodata offset 0x69e6.
        // Sizes further imply the header is complete (SOI + DQT + SOF0 + DHT
        // + SOS with no scan). If this is ever off, the concatenated
        // JPEG is malformed and no decoder will open it.
        assertEquals(605, Ne3JpegHeader.Q75.size)
    }

    @Test fun `header starts with SOI marker`() {
        assertEquals(0xFF.toByte(), Ne3JpegHeader.Q75[0])
        assertEquals(0xD8.toByte(), Ne3JpegHeader.Q75[1])
    }

    @Test fun `header ends with a Start-Of-Scan marker so the scan appends cleanly`() {
        // The last 14 bytes are the SOS segment (`FF DA 00 0C` + a 12-byte
        // body). Appending the entropy-coded scan directly after this makes
        // a syntactically valid MJPEG frame.
        val end = Ne3JpegHeader.Q75.copyOfRange(Ne3JpegHeader.Q75.size - 14, Ne3JpegHeader.Q75.size)
        assertEquals(0xFF.toByte(), end[0])
        assertEquals(0xDA.toByte(), end[1])
        assertEquals(0x00.toByte(), end[2])
        assertEquals(0x0C.toByte(), end[3]) // segment length = 12
    }

    @Test fun `header contains a baseline DCT SOF0 marker with the expected dimensions`() {
        // The SOF0 segment is `FF C0 00 11 08 <H_hi H_lo W_hi W_lo> 03 ...`.
        // We don't hard-code its offset — walk the marker stream to find it,
        // then read the height/width fields. That way a future header change
        // that reorders segments still passes as long as SOF0 is well-formed.
        // SOF0 layout after the FF C0 marker: 2 bytes segment length,
        // 1 byte precision, then 2 bytes height (BE), 2 bytes width (BE).
        val sof0 = findMarker(Ne3JpegHeader.Q75, 0xC0)
            ?: throw AssertionError("no SOF0 marker in header")
        val height = ((Ne3JpegHeader.Q75[sof0 + 3].toInt() and 0xff) shl 8) or
            (Ne3JpegHeader.Q75[sof0 + 4].toInt() and 0xff)
        val width = ((Ne3JpegHeader.Q75[sof0 + 5].toInt() and 0xff) shl 8) or
            (Ne3JpegHeader.Q75[sof0 + 6].toInt() and 0xff)
        assertEquals(Ne3JpegHeader.FRAME_HEIGHT, height)
        assertEquals(Ne3JpegHeader.FRAME_WIDTH, width)
    }

    @Test fun `header contains the required Huffman tables`() {
        // 4 DHT segments (luma-DC, luma-AC, chroma-DC, chroma-AC) — every
        // JPEG decoder needs all four to expand the scan.
        val dhts = countMarkers(Ne3JpegHeader.Q75, 0xC4)
        assertTrue(dhts == 4, "expected 4 DHT segments, found $dhts")
    }

    // ---- helpers -----------------------------------------------------------

    /** Return the index of the byte AFTER `FF nn`, or null if not found. */
    private fun findMarker(bytes: ByteArray, marker: Int): Int? {
        val m = marker.toByte()
        val ff = 0xFF.toByte()
        for (i in 0 until bytes.size - 1) {
            if (bytes[i] == ff && bytes[i + 1] == m) return i + 2
        }
        return null
    }

    private fun countMarkers(bytes: ByteArray, marker: Int): Int {
        val m = marker.toByte()
        val ff = 0xFF.toByte()
        var count = 0
        for (i in 0 until bytes.size - 1) {
            if (bytes[i] == ff && bytes[i + 1] == m) count++
        }
        return count
    }
}
