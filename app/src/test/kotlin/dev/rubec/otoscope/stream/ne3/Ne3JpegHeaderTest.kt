package dev.rubec.otoscope.stream.ne3

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integrity checks on the pre-baked JPEG headers. Each blob is lifted
 * verbatim from the vendor SDK, so a byte-level regression (a base64 typo,
 * a byte reorder, a missing Q entry) would break every NE3 frame on device
 * but slip through a plain-build run. These assertions catch that class of
 * drift at test time.
 */
class Ne3JpegHeaderTest {

    @Test fun `all six Q headers are present and 605 bytes each`() {
        // 605 bytes is what the vendor SDK ships at `.rodata` offsets
        // 0x6072..0x6c43. The six headers correspond to the quality levels
        // the vendor's encoder picks between.
        assertEquals(setOf(5, 10, 25, 50, 75, 100), Ne3JpegHeader.ALL.keys)
        for ((q, bytes) in Ne3JpegHeader.ALL) {
            assertEquals(605, bytes.size, "Q$q header should be 605 bytes")
        }
    }

    @Test fun `every header starts with SOI and ends with Start-Of-Scan`() {
        for ((q, bytes) in Ne3JpegHeader.ALL) {
            assertEquals(0xFF.toByte(), bytes[0], "Q$q: SOI byte 0")
            assertEquals(0xD8.toByte(), bytes[1], "Q$q: SOI byte 1")
            // The last 14 bytes are the SOS segment (`FF DA 00 0C` + a
            // 12-byte body). Appending the entropy-coded scan directly
            // after this makes a syntactically valid MJPEG frame.
            val end = bytes.copyOfRange(bytes.size - 14, bytes.size)
            assertEquals(0xFF.toByte(), end[0], "Q$q: SOS marker byte 0")
            assertEquals(0xDA.toByte(), end[1], "Q$q: SOS marker byte 1")
            assertEquals(0x00.toByte(), end[2], "Q$q: SOS length hi")
            assertEquals(0x0C.toByte(), end[3], "Q$q: SOS length lo")
        }
    }

    @Test fun `every baked header starts at the vendor's 640x360 defaults`() {
        // The SOF0 dimensions are a template that build() patches per frame;
        // what matters here is that the raw blobs all land on the same
        // starting values, so a per-frame patch overwrites the same bytes in
        // every variant. Any Q drifting to a different template dimension
        // would mean build() leaves stale bytes in the other half.
        for ((q, bytes) in Ne3JpegHeader.ALL) {
            val sof0 = findMarker(bytes, 0xC0)
                ?: throw AssertionError("Q$q: no SOF0 marker")
            val height = ((bytes[sof0 + 3].toInt() and 0xff) shl 8) or
                (bytes[sof0 + 4].toInt() and 0xff)
            val width = ((bytes[sof0 + 5].toInt() and 0xff) shl 8) or
                (bytes[sof0 + 6].toInt() and 0xff)
            assertEquals(Ne3JpegHeader.DEFAULT_HEIGHT, height, "Q$q: height")
            assertEquals(Ne3JpegHeader.DEFAULT_WIDTH, width, "Q$q: width")
        }
    }

    @Test fun `every header contains the required 4 Huffman tables`() {
        for ((q, bytes) in Ne3JpegHeader.ALL) {
            val dhts = countMarkers(bytes, 0xC4)
            assertTrue(dhts == 4, "Q$q: expected 4 DHT segments, found $dhts")
        }
    }

    @Test fun `headers differ only in the DQT segments`() {
        // The six blobs share SOF0, DHT and SOS — only the first ~128 bytes
        // (two 64-byte quantisation tables packaged into two DQT segments)
        // vary. If a refactor accidentally mutated anything past the DQTs,
        // every Q would decode to garbage; this test catches that.
        val reference = Ne3JpegHeader.ALL.getValue(75)
        val sof0At = findMarker(reference, 0xC0) ?: throw AssertionError("no SOF0 in Q75")
        // The common tail starts at the SOF0 marker (two bytes earlier
        // than sof0, since findMarker returns the byte after FF C0).
        val commonStart = sof0At - 2
        for ((q, bytes) in Ne3JpegHeader.ALL) {
            if (q == 75) continue
            for (i in commonStart until reference.size) {
                assertEquals(
                    reference[i], bytes[i],
                    "Q$q differs from Q75 at offset $i (past the DQT block, expected identical)",
                )
            }
        }
    }

    @Test fun `DEFAULT_Q is one of the shipped headers`() {
        assertTrue(Ne3JpegHeader.DEFAULT_Q in Ne3JpegHeader.ALL)
    }

    @Test fun `build patches SOF0 to the requested resolution`() {
        val out = Ne3JpegHeader.build(q = 25, width = 416, height = 416)
        val sof0 = findMarker(out, 0xC0) ?: throw AssertionError("no SOF0")
        val h = ((out[sof0 + 3].toInt() and 0xff) shl 8) or (out[sof0 + 4].toInt() and 0xff)
        val w = ((out[sof0 + 5].toInt() and 0xff) shl 8) or (out[sof0 + 6].toInt() and 0xff)
        assertEquals(416, h)
        assertEquals(416, w)
    }

    @Test fun `build picks the Q-matching DQT tables`() {
        val baseQ25 = Ne3JpegHeader.ALL.getValue(25)
        val out = Ne3JpegHeader.build(q = 25, width = 640, height = 360)
        // Everything before SOF0 (the two DQT blocks) must come from Q=25
        // verbatim — the DQT tables are what makes a header "a Q25 header".
        val sof0 = findMarker(out, 0xC0) ?: throw AssertionError("no SOF0")
        val commonStart = sof0 - 2
        for (i in 0 until commonStart) {
            assertEquals(baseQ25[i], out[i], "DQT byte $i must come from Q25")
        }
    }

    @Test fun `build with unknown Q falls back to Q75 DQT tables`() {
        val baseQ75 = Ne3JpegHeader.ALL.getValue(75)
        val out = Ne3JpegHeader.build(q = null, width = 640, height = 360)
        val sof0 = findMarker(out, 0xC0) ?: throw AssertionError("no SOF0")
        val commonStart = sof0 - 2
        for (i in 0 until commonStart) {
            assertEquals(baseQ75[i], out[i], "DQT byte $i must come from Q75 fallback")
        }
    }

    @Test fun `build with absurd width or height falls back to vendor defaults`() {
        val out = Ne3JpegHeader.build(q = 75, width = 0, height = 999_999)
        val sof0 = findMarker(out, 0xC0) ?: throw AssertionError("no SOF0")
        val h = ((out[sof0 + 3].toInt() and 0xff) shl 8) or (out[sof0 + 4].toInt() and 0xff)
        val w = ((out[sof0 + 5].toInt() and 0xff) shl 8) or (out[sof0 + 6].toInt() and 0xff)
        assertEquals(Ne3JpegHeader.DEFAULT_HEIGHT, h)
        assertEquals(Ne3JpegHeader.DEFAULT_WIDTH, w)
    }

    @Test fun `build returns a fresh array that doesn't mutate the baked blob`() {
        val before = Ne3JpegHeader.ALL.getValue(75).copyOf()
        Ne3JpegHeader.build(q = 75, width = 416, height = 416)
        val after = Ne3JpegHeader.ALL.getValue(75)
        for (i in before.indices) {
            assertEquals(before[i], after[i], "baked blob mutated at offset $i")
        }
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
