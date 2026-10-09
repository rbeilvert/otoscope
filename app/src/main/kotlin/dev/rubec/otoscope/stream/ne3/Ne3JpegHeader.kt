package dev.rubec.otoscope.stream.ne3

import java.util.Base64

/**
 * Pre-baked JPEG headers the NE3 camera family expects clients to prepend to
 * every received scan.
 *
 * The camera streams headerless MJPEG: each frame is only entropy-coded scan
 * data ending with an `FF D9` EOI marker. The vendor SDK (`libbl_vii_jni.so`,
 * Bouffalo Lab BL602 VII) ships **six** pre-baked headers — one per Q level
 * (5 / 10 / 25 / 50 / 75 / 100) — and prepends the one matching the active
 * encoder Q before handing the JPEG up to Java. All six blobs declare the
 * same SOF0 skeleton and share DHT + SOS; they differ only in a 128-byte
 * stretch covering the two DQT segments.
 *
 * Resolution is per-frame, not fixed by the headers: the vendor native lib
 * patches the SOF0 width/height from `packet[44..47]` into the chosen
 * header each frame (see `.text+0x5b8c..0x5c34`), and [build] mirrors that.
 * Skipping the patch tears the image into horizontal strips aligned to the
 * stale header's row stride.
 *
 * The camera volunteers its active Q as byte 48 of every frame-fragment
 * packet (see [Ne3FrameAssembler] for the wire layout and vendor
 * cross-references). [Ne3VideoClient] passes that byte plus the per-frame
 * width/height through [build] to produce the final header.
 */
internal object Ne3JpegHeader {

    /** Fallback dimensions used when a frame reports garbage (zero or absurd)
     *  width/height. The six vendor headers were baked at this resolution,
     *  so leaving the SOF0 untouched gives a decodable (if wrongly sized)
     *  image rather than a decoder crash. */
    const val DEFAULT_WIDTH = 640
    const val DEFAULT_HEIGHT = 360

    /** Q level used when the camera reports a value outside the vendor's
     *  six-element enum. Q=75 is the vendor app's own factory default. */
    const val DEFAULT_Q = 75

    /** Full set, keyed by the vendor's Q level. Values are the exact bytes
     *  the vendor .so ships at `.rodata` offsets 0x6072..0x6c43 — base64 only
     *  so this source file stays narrow. Decoded once at class load. */
    val ALL: Map<Int, ByteArray> by lazy {
        mapOf(
            5 to decode(BASE64_Q5),
            10 to decode(BASE64_Q10),
            25 to decode(BASE64_Q25),
            50 to decode(BASE64_Q50),
            75 to decode(BASE64_Q75),
            100 to decode(BASE64_Q100),
        )
    }

    /** Build a JPEG header for a frame at [q] / [width] × [height]. Returns
     *  a fresh 605-byte array with the chosen DQT tables and an SOF0 patched
     *  to the given dimensions — safe for the caller to concatenate directly
     *  with the scan payload. Falls back to [DEFAULT_Q] / [DEFAULT_WIDTH] /
     *  [DEFAULT_HEIGHT] when the inputs are out of the vendor's valid range. */
    fun build(q: Int?, width: Int, height: Int): ByteArray {
        val base = ALL[q] ?: ALL.getValue(DEFAULT_Q)
        val w = if (width in 1..MAX_SANE_DIMENSION) width else DEFAULT_WIDTH
        val h = if (height in 1..MAX_SANE_DIMENSION) height else DEFAULT_HEIGHT
        val out = base.copyOf()
        // SOF0 layout at [SOF0_OFFSET..]: FF C0 00 11 08 HH HH WW WW …
        // Height is BE u16 at +5, width is BE u16 at +7.
        out[SOF0_OFFSET + 5] = ((h ushr 8) and 0xff).toByte()
        out[SOF0_OFFSET + 6] = (h and 0xff).toByte()
        out[SOF0_OFFSET + 7] = ((w ushr 8) and 0xff).toByte()
        out[SOF0_OFFSET + 8] = (w and 0xff).toByte()
        return out
    }

    /** Byte offset of the `FF C0` SOF0 marker in every vendor header. Pinned
     *  because every blob shares the same prelude layout up to this point;
     *  pre-computing avoids a byte scan on every frame. */
    private const val SOF0_OFFSET = 140

    /** Ceiling for a believable camera dimension. The hardware tops out
     *  well under 2 K; anything above this is corrupt packet data and
     *  should fall back to the baked-in default rather than crash a decoder
     *  with a wild stride. */
    private const val MAX_SANE_DIMENSION = 4096

    private fun decode(b64: String): ByteArray = Base64.getDecoder().decode(b64)

    // The 6 blobs are each 605 bytes. Split across lines only so this source
    // file stays narrow. Everything past the first ~128 bytes is identical
    // across all six (SOF0 640×360 4:2:0, DHT×4, SOS); only the DQT luma+chroma
    // tables up front differ.

    private const val BASE64_Q5 =
        "/9j/2wBDAKBueIx4ZKCMgoy0qqC+8P//8Nzc8P//////////////////////////////////////" +
        "////////////////////2wBDAaq0tPDS8P//////////////////////////////////////////" +
        "////////////////////////////////////wAARCAFoAoADAREAAhEBAxEB/8QAHwAAAQUBAQEB" +
        "AQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1Fh" +
        "ByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZ" +
        "WmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXG" +
        "x8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAEC" +
        "AwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHB" +
        "CSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0" +
        "dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX" +
        "2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwA="

    private const val BASE64_Q10 =
        "/9j/2wBDAFA3PEY8MlBGQUZaVVBfeMiCeG5uePWvuZHI////////////////////////////////" +
        "////////////////////2wBDAVVaWnhpeOuCguv/////////////////////////////////////" +
        "////////////////////////////////////wAARCAFoAoADAREAAhEBAxEB/8QAHwAAAQUBAQEB" +
        "AQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1Fh" +
        "ByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZ" +
        "WmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXG" +
        "x8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAEC" +
        "AwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHB" +
        "CSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0" +
        "dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX" +
        "2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwA="

    private const val BASE64_Q25 =
        "/9j/2wBDACAWGBwYFCAcGhwkIiAmMFA0MCwsMGJGSjpQdGZ6eHJmcG6AkLicgIiuim5woNqirr7E" +
        "ztDOfJri8uDI8LjKzsb/2wBDASIkJDAqMF40NF7GhHCExsbGxsbGxsbGxsbGxsbGxsbGxsbGxsbG" +
        "xsbGxsbGxsbGxsbGxsbGxsbGxsbGxsbGxsb/wAARCAFoAoADAREAAhEBAxEB/8QAHwAAAQUBAQEB" +
        "AQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1Fh" +
        "ByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZ" +
        "WmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXG" +
        "x8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAEC" +
        "AwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHB" +
        "CSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0" +
        "dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX" +
        "2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwA="

    private const val BASE64_Q50 =
        "/9j/2wBDABALDA4MChAODQ4SERATGCgaGBYWGDEjJR0oOjM9PDkzODdASFxOQERXRTc4UG1RV19i" +
        "Z2hnPk1xeXBkeFxlZ2P/2wBDARESEhgVGC8aGi9jQjhCY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2Nj" +
        "Y2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2P/wAARCAFoAoADAREAAhEBAxEB/8QAHwAAAQUBAQEB" +
        "AQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1Fh" +
        "ByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZ" +
        "WmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXG" +
        "x8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAEC" +
        "AwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHB" +
        "CSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0" +
        "dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX" +
        "2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwA="

    private const val BASE64_Q75 =
        "/9j/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAx" +
        "NDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIy" +
        "MjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAFoAoADAREAAhEBAxEB/8QAHwAAAQUBAQEB" +
        "AQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1Fh" +
        "ByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZ" +
        "WmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXG" +
        "x8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAEC" +
        "AwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHB" +
        "CSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0" +
        "dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX" +
        "2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwA="

    private const val BASE64_Q100 =
        "/9j/2wBDAAEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEB" +
        "AQEBAQEBAQEBAQEBAQH/2wBDAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEB" +
        "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQH/wAARCAFoAoADAREAAhEBAxEB/8QAHwAAAQUBAQEB" +
        "AQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1Fh" +
        "ByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZ" +
        "WmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXG" +
        "x8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAEC" +
        "AwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHB" +
        "CSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0" +
        "dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX" +
        "2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwA="
}
