package dev.rubec.otoscope.stream.ne3

import java.util.Base64

/**
 * Pre-baked JPEG header the NE3 camera family expects clients to prepend to
 * every received scan.
 *
 * The camera streams headerless MJPEG: each frame is only entropy-coded scan
 * data ending with an `FF D9` EOI marker. The vendor SDK (`libbl_vii_jni.so`,
 * Bouffalo Lab BL602 VII) ships six pre-baked headers — one per quality
 * setting (Q5 / Q10 / Q25 / Q50 / Q75 / Q100), all sized for the fixed
 * 640×360 stream — and prepends the one matching the currently-selected Q
 * before handing the JPEG up to Java.
 *
 * We ship a single Q75 header (605 bytes verbatim from the vendor SDK at
 * `.rodata` offset 0x69e6, symbol `jpeg_header_640x360_Q75`) because that is
 * the default upper Q the SDK asks the encoder to use and the most likely Q
 * a fresh session will produce. If a future firmware ships frames encoded at
 * a different Q, the decoded image will show quantisation artefacts and we
 * would need to ship the other five headers and pick per frame.
 */
internal object Ne3JpegHeader {

    /** Fixed resolution for this hardware family — the vendor SDK doesn't
     *  expose a resolution setter, and the Q75 header below bakes in these
     *  dimensions in its SOF0 segment. */
    const val FRAME_WIDTH = 640
    const val FRAME_HEIGHT = 360

    // Base64-encoded header, split across lines only so this source file
    // stays narrow. Decodes to the exact 605-byte header the vendor .so
    // emits for Q75 — a full JPEG prelude (SOI + DQT×2 + SOF0 640×360 4:2:0
    // + DHT×4 + SOS), so appending the scan bytes yields a complete file.
    private const val BASE64_Q75: String =
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

    /** Header prepended to every scan. Ends in an SOS marker so the scan
     *  bytes concatenate directly after it. Decoded once at class load. */
    val Q75: ByteArray = Base64.getDecoder().decode(BASE64_Q75)
}
