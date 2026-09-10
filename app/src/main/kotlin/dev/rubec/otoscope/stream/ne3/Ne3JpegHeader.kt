package dev.rubec.otoscope.stream.ne3

import java.util.Base64

/**
 * Pre-baked JPEG headers the NE3 camera family expects clients to prepend
 * to every received scan. The camera streams headerless entropy-coded MJPEG;
 * the caller appends `FF D9` EOI after the scan before decode.
 *
 * The vendor SDK ships six headers, one per Q level (5 / 10 / 25 / 50 / 75
 * / 100). They share SOF0 skeleton, DHT and SOS; only the two DQT segments
 * at the front differ. [build] picks the Q-matching base and patches SOF0
 * width/height to the per-frame values the camera sends; the dimensions
 * vary per firmware (416×416 and 640×360 both observed).
 */
internal object Ne3JpegHeader {

    /** Fallback dimensions used when a frame reports garbage width/height.
     *  Matches the vendor headers' baked-in SOF0 so the output decodes
     *  (even if wrongly sized) rather than crashing. */
    const val DEFAULT_WIDTH = 640
    const val DEFAULT_HEIGHT = 360

    /** Q level used when the camera reports a value outside the vendor's
     *  six-element enum. */
    const val DEFAULT_Q = 75

    /** Full set, keyed by Q level. Values are the exact bytes the vendor
     *  .so ships; base64 only to keep the source file narrow. */
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

    /** Returns a fresh 605-byte header with the Q-matching DQT tables and
     *  SOF0 patched to [width] × [height]. Falls back to [DEFAULT_Q] /
     *  [DEFAULT_WIDTH] / [DEFAULT_HEIGHT] on out-of-range inputs. */
    fun build(q: Int?, width: Int, height: Int): ByteArray {
        val base = ALL[q] ?: ALL.getValue(DEFAULT_Q)
        val w = if (width in 1..MAX_SANE_DIMENSION) width else DEFAULT_WIDTH
        val h = if (height in 1..MAX_SANE_DIMENSION) height else DEFAULT_HEIGHT
        val out = base.copyOf()
        // SOF0 at [SOF0_OFFSET..]: FF C0 00 11 08 HH HH WW WW …
        out[SOF0_OFFSET + 5] = ((h ushr 8) and 0xff).toByte()
        out[SOF0_OFFSET + 6] = (h and 0xff).toByte()
        out[SOF0_OFFSET + 7] = ((w ushr 8) and 0xff).toByte()
        out[SOF0_OFFSET + 8] = (w and 0xff).toByte()
        return out
    }

    /** Byte offset of the `FF C0` SOF0 marker in every vendor header. */
    private const val SOF0_OFFSET = 140

    /** Ceiling for a believable camera dimension — anything larger is
     *  corrupt packet data and gets clamped to the baked-in default. */
    private const val MAX_SANE_DIMENSION = 4096

    private fun decode(b64: String): ByteArray = Base64.getDecoder().decode(b64)

    // The 6 blobs are each 605 bytes, base64-split across lines only to keep
    // this file narrow. All bytes past the DQT segments are identical
    // across the six (shared SOF0 skeleton, DHT×4, SOS).

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
