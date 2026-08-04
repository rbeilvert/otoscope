package dev.rubec.otoscope.stream.earfairy

import java.io.ByteArrayOutputStream

/**
 * Reassembles JPEG frames from an MJPEG-over-RTP stream (payload type 26,
 * RFC 2435). The wire fragments carry only the raw JPEG scan data plus enough
 * metadata (type / Q / dimensions / optional quantization tables) to
 * synthesise a full JPEG stream on the receiver side — this class does that
 * synthesis.
 *
 * Usage:
 * ```
 * val d = RtpJpegDepacketizer()
 * for (rtpPayload in stream) {
 *     val jpeg = d.feed(rtpPayload, markerBitSet = ...)
 *     if (jpeg != null) decodeAndDisplay(jpeg)
 * }
 * ```
 *
 * Frames with missing fragments are dropped (a hole in a JPEG is visually
 * worse than a skipped frame).
 */
internal class RtpJpegDepacketizer {

    private val scanData = ByteArrayOutputStream()
    private var expectedOffset = 0
    private var quantTables: ByteArray? = null
    private var type: Int = 0
    private var quality: Int = 0
    private var width: Int = 0
    private var height: Int = 0
    private var dri: Int = 0
    private var frameStartSeen = false

    /**
     * Feed one RTP payload (the bytes AFTER the RTP header). Returns a
     * complete JPEG (SOI…EOI) when [markerBitSet] indicates the last
     * fragment of a frame; null while still assembling or when the frame
     * is being discarded because a fragment was missed.
     */
    fun feed(payload: ByteArray, markerBitSet: Boolean): ByteArray? {
        if (payload.size < JPEG_MAIN_HEADER_SIZE) return null

        // ---- Main JPEG header (RFC 2435 §3.1) ----
        //   0        Type-specific
        //   1..3     Fragment Offset (24-bit big-endian)
        //   4        Type (0..63 or 64..127 with restart intervals)
        //   5        Q  (quality factor, 1..99 for default tables, 128..255 for
        //              inline quant tables)
        //   6        Width  / 8
        //   7        Height / 8
        val fragmentOffset =
            ((payload[1].toInt() and 0xff) shl 16) or
                ((payload[2].toInt() and 0xff) shl 8) or
                (payload[3].toInt() and 0xff)
        val fragType = payload[4].toInt() and 0xff
        val fragQ = payload[5].toInt() and 0xff
        val fragW = (payload[6].toInt() and 0xff) * 8
        val fragH = (payload[7].toInt() and 0xff) * 8
        var cursor = JPEG_MAIN_HEADER_SIZE

        // ---- Restart Marker header (RFC 2435 §3.1.3), types 64..127 ----
        var fragDri = 0
        if (fragType in RESTART_TYPE_MIN..RESTART_TYPE_MAX) {
            if (payload.size < cursor + RESTART_HEADER_SIZE) return null
            fragDri = ((payload[cursor].toInt() and 0xff) shl 8) or
                (payload[cursor + 1].toInt() and 0xff)
            cursor += RESTART_HEADER_SIZE
        }

        // ---- Quantization Table header (RFC 2435 §3.1.8), Q >= 128, first fragment ----
        var fragTables: ByteArray? = null
        if (fragQ >= 128 && fragmentOffset == 0) {
            if (payload.size < cursor + QT_HEADER_SIZE) return null
            val qtLen = ((payload[cursor + 2].toInt() and 0xff) shl 8) or
                (payload[cursor + 3].toInt() and 0xff)
            cursor += QT_HEADER_SIZE
            if (qtLen > 0) {
                if (payload.size < cursor + qtLen) return null
                fragTables = payload.copyOfRange(cursor, cursor + qtLen)
                cursor += qtLen
            }
        }

        val scanBytes = payload.copyOfRange(cursor, payload.size)

        // ---- Frame assembly ----
        if (fragmentOffset == 0) {
            // Start of a new frame. Reset any partial state.
            reset()
            type = fragType and 0x3f // strip the restart-marker high bit
            quality = fragQ
            width = fragW
            height = fragH
            dri = fragDri
            quantTables = fragTables ?: defaultQuantTables(quality)
            frameStartSeen = true
        } else if (!frameStartSeen || fragmentOffset != expectedOffset) {
            // We missed the first fragment or a middle one; drop the whole
            // frame. Waiting for the next Offset==0 restarts assembly cleanly.
            drop()
            return null
        }

        scanData.write(scanBytes)
        expectedOffset = fragmentOffset + scanBytes.size

        if (!markerBitSet) return null

        // Last fragment of the frame — build the full JPEG file.
        val jpeg = ByteArrayOutputStream(scanData.size() + 1024).apply {
            writeJpegHeader(
                type = type,
                width = width,
                height = height,
                quantTables = quantTables ?: return@apply,
                dri = dri,
            )
            write(scanData.toByteArray())
            // EOI. RFC 2435 says the encoder MAY omit it; append unconditionally
            // so decoders that don't tolerate a missing EOI still work.
            write(0xFF); write(0xD9)
        }
        reset()
        return jpeg.toByteArray()
    }

    private fun reset() {
        scanData.reset()
        expectedOffset = 0
        frameStartSeen = false
        quantTables = null
        dri = 0
    }

    private fun drop() {
        // Same state cleanup as reset(), separate name so intent reads clearly.
        reset()
    }

    companion object {
        private const val JPEG_MAIN_HEADER_SIZE = 8
        private const val RESTART_HEADER_SIZE = 4
        private const val QT_HEADER_SIZE = 4
        private const val RESTART_TYPE_MIN = 64
        private const val RESTART_TYPE_MAX = 127
    }
}

// -- JPEG header synthesis (RFC 2435 Appendix B) -------------------------------
//
// The sender ships the raw scan data and enough metadata (type/Q/dims/tables)
// for the receiver to reconstruct a syntactically valid JPEG file. That
// reconstruction is what these helpers do — hard-coded standard Huffman tables
// (Appendix K.3 of the JPEG spec) plus a computed set of quantization tables
// derived from the quality factor when Q < 128.

private fun ByteArrayOutputStream.writeJpegHeader(
    type: Int,
    width: Int,
    height: Int,
    quantTables: ByteArray,
    dri: Int,
) {
    // SOI
    write(0xFF); write(0xD8)

    // DQT — one Define-Quantization-Table segment per 64-byte table in the buffer.
    // RFC 2435 packs them back-to-back; the first is luma (Tq=0), the second is
    // chroma (Tq=1). We ship whatever the sender sent (or the standard defaults).
    val tables = quantTables.size / 64
    for (i in 0 until tables) {
        write(0xFF); write(0xDB)              // DQT marker
        write(0x00); write(0x43)              // segment length = 67 (2 length + 1 precision/table-id + 64 values)
        write(i)                              // 8-bit precision + table id
        write(quantTables, i * 64, 64)
    }

    // DRI — Define Restart Interval, only if the sender used a restart type.
    if (dri > 0) {
        write(0xFF); write(0xDD)
        write(0x00); write(0x04)
        write((dri shr 8) and 0xff)
        write(dri and 0xff)
    }

    // SOF0 — baseline DCT, 3 components (YCbCr).
    //   marker + length(17) + precision(8) + height(2) + width(2) + Nf(3=YCbCr)
    //   then 3 × { component id, sampling, quant table selector }.
    write(0xFF); write(0xC0)
    write(0x00); write(0x11)                  // length = 17
    write(0x08)                                // 8-bit precision
    write((height shr 8) and 0xff); write(height and 0xff)
    write((width shr 8) and 0xff);  write(width and 0xff)
    write(0x03)                                // Nf = 3 components (Y, Cb, Cr)
    // Y: id=1, sampling per type, quant table 0.
    write(0x01)
    // Type 0 = 4:2:2 (2h1v), Type 1 = 4:2:0 (2h2v). Higher bits carried extra
    // semantics we don't use for otoscope streams.
    write(if (type and 0x3f == 0) 0x21 else 0x22)
    write(0x00)
    // Cb: id=2, sampling 1h1v, quant table 1.
    write(0x02); write(0x11); write(0x01)
    // Cr: id=3, sampling 1h1v, quant table 1.
    write(0x03); write(0x11); write(0x01)

    // DHT — Huffman tables. Four of them (luma-DC, luma-AC, chroma-DC, chroma-AC),
    // hard-coded from RFC 2435 Appendix B / JPEG spec Annex K.3. Cheaper to ship
    // the raw bytes than to reconstruct them each time.
    write(HUFFMAN_TABLES)

    // SOS — start of scan, 3 components.
    write(0xFF); write(0xDA)
    write(0x00); write(0x0C)                  // length = 12
    write(0x03)                                // Ns = 3
    write(0x01); write(0x00)                   // Y  → DC=0, AC=0
    write(0x02); write(0x11)                   // Cb → DC=1, AC=1
    write(0x03); write(0x11)                   // Cr → DC=1, AC=1
    write(0x00); write(0x3F); write(0x00)      // Ss=0, Se=63, Ah/Al=0
}

// Default quantization tables per RFC 2435 §4.1 / Appendix A. Two 8×8 tables
// (luma, chroma) scaled by the sender-provided quality factor Q ∈ [1, 99].
private fun defaultQuantTables(quality: Int): ByteArray {
    // Clamp Q to the valid range that MakeTables in RFC 2435 expects.
    val q = when {
        quality < 1 -> 1
        quality > 99 -> 99
        else -> quality
    }
    // RFC 2435's factor formula: for Q < 50 use 5000/Q, else 200 - 2*Q.
    val factor = if (q < 50) 5000 / q else 200 - q * 2
    val out = ByteArray(128)
    for (i in 0 until 64) out[i] = scale(LUMA_QT[i], factor)
    for (i in 0 until 64) out[64 + i] = scale(CHROMA_QT[i], factor)
    return out
}

private fun scale(value: Int, factor: Int): Byte {
    // Same rounding as RFC 2435 MakeTables.
    val v = (value * factor + 50) / 100
    return when {
        v < 1 -> 1
        v > 255 -> 255.toByte()
        else -> v.toByte()
    }
}

/** Standard luma quantization table (JPEG Annex K.1), used with RFC 2435's Q scaler. */
private val LUMA_QT = intArrayOf(
    16, 11, 10, 16, 24, 40, 51, 61,
    12, 12, 14, 19, 26, 58, 60, 55,
    14, 13, 16, 24, 40, 57, 69, 56,
    14, 17, 22, 29, 51, 87, 80, 62,
    18, 22, 37, 56, 68, 109, 103, 77,
    24, 35, 55, 64, 81, 104, 113, 92,
    49, 64, 78, 87, 103, 121, 120, 101,
    72, 92, 95, 98, 112, 100, 103, 99,
)

/** Standard chroma quantization table (JPEG Annex K.2). */
private val CHROMA_QT = intArrayOf(
    17, 18, 24, 47, 99, 99, 99, 99,
    18, 21, 26, 66, 99, 99, 99, 99,
    24, 26, 56, 99, 99, 99, 99, 99,
    47, 66, 99, 99, 99, 99, 99, 99,
    99, 99, 99, 99, 99, 99, 99, 99,
    99, 99, 99, 99, 99, 99, 99, 99,
    99, 99, 99, 99, 99, 99, 99, 99,
    99, 99, 99, 99, 99, 99, 99, 99,
)

/** Concatenated DHT segments for the four standard JPEG Huffman tables
 *  (DC luma, DC chroma, AC luma, AC chroma) — verbatim from RFC 2435
 *  Appendix B / JPEG spec Annex K.3. Written as one blob because these
 *  never change: the sender assumes them, so we must emit them literally. */
private val HUFFMAN_TABLES: ByteArray = byteArrayOf(
    // DC luma table (Tc/Th = 0x00): length 31 = 2 (length) + 1 (Tc/Th) + 16 (BITS) + 12 (VALUES).
    0xFF.toByte(), 0xC4.toByte(), 0x00, 0x1F, 0x00,
    // BITS[1..16] — count of Huffman codes of each length. Sum = 12 = |VALUES|.
    0x00, 0x01, 0x05, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    // VALUES — the 12 symbols.
    0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B,
    // AC luma table (0x10): length 181
    0xFF.toByte(), 0xC4.toByte(), 0x00, 0xB5.toByte(), 0x10,
    0x00, 0x02, 0x01, 0x03, 0x03, 0x02, 0x04, 0x03, 0x05, 0x05, 0x04, 0x04, 0x00, 0x00, 0x01, 0x7D,
    0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07,
    0x22, 0x71, 0x14, 0x32, 0x81.toByte(), 0x91.toByte(), 0xA1.toByte(), 0x08, 0x23, 0x42, 0xB1.toByte(),
    0xC1.toByte(), 0x15, 0x52, 0xD1.toByte(), 0xF0.toByte(), 0x24, 0x33, 0x62, 0x72, 0x82.toByte(),
    0x09, 0x0A, 0x16, 0x17, 0x18, 0x19, 0x1A, 0x25, 0x26, 0x27, 0x28, 0x29, 0x2A, 0x34, 0x35, 0x36,
    0x37, 0x38, 0x39, 0x3A, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4A, 0x53, 0x54, 0x55, 0x56,
    0x57, 0x58, 0x59, 0x5A, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6A, 0x73, 0x74, 0x75, 0x76,
    0x77, 0x78, 0x79, 0x7A, 0x83.toByte(), 0x84.toByte(), 0x85.toByte(), 0x86.toByte(), 0x87.toByte(),
    0x88.toByte(), 0x89.toByte(), 0x8A.toByte(), 0x92.toByte(), 0x93.toByte(), 0x94.toByte(),
    0x95.toByte(), 0x96.toByte(), 0x97.toByte(), 0x98.toByte(), 0x99.toByte(), 0x9A.toByte(),
    0xA2.toByte(), 0xA3.toByte(), 0xA4.toByte(), 0xA5.toByte(), 0xA6.toByte(), 0xA7.toByte(),
    0xA8.toByte(), 0xA9.toByte(), 0xAA.toByte(), 0xB2.toByte(), 0xB3.toByte(), 0xB4.toByte(),
    0xB5.toByte(), 0xB6.toByte(), 0xB7.toByte(), 0xB8.toByte(), 0xB9.toByte(), 0xBA.toByte(),
    0xC2.toByte(), 0xC3.toByte(), 0xC4.toByte(), 0xC5.toByte(), 0xC6.toByte(), 0xC7.toByte(),
    0xC8.toByte(), 0xC9.toByte(), 0xCA.toByte(), 0xD2.toByte(), 0xD3.toByte(), 0xD4.toByte(),
    0xD5.toByte(), 0xD6.toByte(), 0xD7.toByte(), 0xD8.toByte(), 0xD9.toByte(), 0xDA.toByte(),
    0xE1.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xE4.toByte(), 0xE5.toByte(), 0xE6.toByte(),
    0xE7.toByte(), 0xE8.toByte(), 0xE9.toByte(), 0xEA.toByte(), 0xF1.toByte(), 0xF2.toByte(),
    0xF3.toByte(), 0xF4.toByte(), 0xF5.toByte(), 0xF6.toByte(), 0xF7.toByte(), 0xF8.toByte(),
    0xF9.toByte(), 0xFA.toByte(),
    // DC chroma table (Tc/Th = 0x01): same shape as DC luma, different BITS/VALUES.
    0xFF.toByte(), 0xC4.toByte(), 0x00, 0x1F, 0x01,
    0x00, 0x03, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B,
    // AC chroma table (0x11): length 181
    0xFF.toByte(), 0xC4.toByte(), 0x00, 0xB5.toByte(), 0x11,
    0x00, 0x02, 0x01, 0x02, 0x04, 0x04, 0x03, 0x04, 0x07, 0x05, 0x04, 0x04, 0x00, 0x01, 0x02, 0x77,
    0x00, 0x01, 0x02, 0x03, 0x11, 0x04, 0x05, 0x21, 0x31, 0x06, 0x12, 0x41, 0x51, 0x07, 0x61, 0x71,
    0x13, 0x22, 0x32, 0x81.toByte(), 0x08, 0x14, 0x42, 0x91.toByte(), 0xA1.toByte(), 0xB1.toByte(),
    0xC1.toByte(), 0x09, 0x23, 0x33, 0x52, 0xF0.toByte(), 0x15, 0x62, 0x72, 0xD1.toByte(), 0x0A,
    0x16, 0x24, 0x34, 0xE1.toByte(), 0x25, 0xF1.toByte(), 0x17, 0x18, 0x19, 0x1A, 0x26, 0x27, 0x28,
    0x29, 0x2A, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3A, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4A,
    0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5A, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6A,
    0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7A, 0x82.toByte(), 0x83.toByte(), 0x84.toByte(),
    0x85.toByte(), 0x86.toByte(), 0x87.toByte(), 0x88.toByte(), 0x89.toByte(), 0x8A.toByte(),
    0x92.toByte(), 0x93.toByte(), 0x94.toByte(), 0x95.toByte(), 0x96.toByte(), 0x97.toByte(),
    0x98.toByte(), 0x99.toByte(), 0x9A.toByte(), 0xA2.toByte(), 0xA3.toByte(), 0xA4.toByte(),
    0xA5.toByte(), 0xA6.toByte(), 0xA7.toByte(), 0xA8.toByte(), 0xA9.toByte(), 0xAA.toByte(),
    0xB2.toByte(), 0xB3.toByte(), 0xB4.toByte(), 0xB5.toByte(), 0xB6.toByte(), 0xB7.toByte(),
    0xB8.toByte(), 0xB9.toByte(), 0xBA.toByte(), 0xC2.toByte(), 0xC3.toByte(), 0xC4.toByte(),
    0xC5.toByte(), 0xC6.toByte(), 0xC7.toByte(), 0xC8.toByte(), 0xC9.toByte(), 0xCA.toByte(),
    0xD2.toByte(), 0xD3.toByte(), 0xD4.toByte(), 0xD5.toByte(), 0xD6.toByte(), 0xD7.toByte(),
    0xD8.toByte(), 0xD9.toByte(), 0xDA.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xE4.toByte(),
    0xE5.toByte(), 0xE6.toByte(), 0xE7.toByte(), 0xE8.toByte(), 0xE9.toByte(), 0xEA.toByte(),
    0xF2.toByte(), 0xF3.toByte(), 0xF4.toByte(), 0xF5.toByte(), 0xF6.toByte(), 0xF7.toByte(),
    0xF8.toByte(), 0xF9.toByte(), 0xFA.toByte(),
)
