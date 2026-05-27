package dev.rubec.otoscope.stream

/**
 * Assembles the chunked video frames the otoscope camera sends over UDP.
 *
 * Each UDP datagram carries a 24-byte header followed by `chunk_len` bytes of
 * payload. The full wire format (reversed from `libmlcamera-2.5.so`):
 *
 *   offset  size  field
 *   ------  ----  -----
 *      0     u8   magic 0x66
 *      1     u8   type (0x01 or 0x03 — treated as opaque)
 *      2     u8   format (1 = MJPEG, 4 = YUYV)
 *      3     u8   flag (unknown, ignored)
 *      4-7   u32  total frame size (little-endian)
 *      8-9   u16  width
 *     10-11  u16  height
 *     12-13  u16  chunk_seq (0-based per frame)
 *     14-15  u16  chunk_len (bytes of payload in this datagram)
 *     16-19  u32  timestamp/id
 *     20-23  u32  reserved
 *     24..   ..   payload
 */
class FrameAssembler {

    data class Frame(
        val data: ByteArray,
        val format: Int,
        val width: Int,
        val height: Int,
        /** Packed accelerometer reading from the camera at offset 16 of the header.
         *  Three 10-bit axes (X high, Z low). Decoded by [RotationFilter]. */
        val accelerometer: Int,
    )

    enum class FeedResult { Frame, Building, Dropped, Invalid }
    data class Outcome(val result: FeedResult, val frame: Frame? = null)

    private var buffer: ByteArray? = null
    private var format = 0
    private var width = 0
    private var height = 0
    private var expectedSize = 0
    private var accumulatedSize = 0
    private var lastChunkSeq = -1
    private var accelerometer = 0

    fun reset() {
        buffer = null
        accumulatedSize = 0
        lastChunkSeq = -1
        expectedSize = 0
    }

    fun feed(packet: ByteArray, len: Int): Outcome {
        if (len < HEADER_SIZE) return Outcome(FeedResult.Invalid)
        if (packet[0] != MAGIC) return Outcome(FeedResult.Invalid)

        val totalSize = readU32LE(packet, 4)
        val chunkSeq = readU16LE(packet, 12)
        val chunkLen = readU16LE(packet, 14)

        if (chunkLen <= 0 || chunkLen + HEADER_SIZE > len) return Outcome(FeedResult.Invalid)
        if (totalSize <= 0 || totalSize > MAX_FRAME_SIZE) return Outcome(FeedResult.Invalid)

        // New frame begins when chunk_seq resets, OR when the total size differs,
        // OR we haven't started yet.
        val isNewFrame = buffer == null ||
            totalSize != expectedSize ||
            chunkSeq < lastChunkSeq ||
            chunkSeq == 0

        if (isNewFrame) {
            buffer = ByteArray(totalSize)
            format = packet[2].toInt() and 0xff
            width = readU16LE(packet, 8)
            height = readU16LE(packet, 10)
            expectedSize = totalSize
            accumulatedSize = 0
            lastChunkSeq = -1
        }

        if (chunkSeq != lastChunkSeq + 1) {
            // Out-of-order or duplicate — drop the in-flight frame.
            reset()
            return Outcome(FeedResult.Dropped)
        }

        if (accumulatedSize + chunkLen > expectedSize) {
            reset()
            return Outcome(FeedResult.Dropped)
        }

        System.arraycopy(packet, HEADER_SIZE, buffer!!, accumulatedSize, chunkLen)
        accumulatedSize += chunkLen
        lastChunkSeq = chunkSeq
        // The accelerometer reading travels in every chunk's header; remember the
        // most recent one — that's what the original native lib stores at frame end.
        accelerometer = readU32LE(packet, 16)

        return if (accumulatedSize == expectedSize) {
            val frame = Frame(buffer!!, format, width, height, accelerometer)
            buffer = null
            accumulatedSize = 0
            lastChunkSeq = -1
            Outcome(FeedResult.Frame, frame)
        } else {
            Outcome(FeedResult.Building)
        }
    }

    private fun readU16LE(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8)

    private fun readU32LE(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or
            ((b[off + 1].toInt() and 0xff) shl 8) or
            ((b[off + 2].toInt() and 0xff) shl 16) or
            ((b[off + 3].toInt() and 0xff) shl 24)

    companion object {
        const val HEADER_SIZE = 24
        const val MAGIC: Byte = 0x66
        // Camera spec maxes around 1080p MJPEG; 4 MB is plenty and bounds bad reads.
        private const val MAX_FRAME_SIZE = 4 * 1024 * 1024

        const val FORMAT_MJPEG = 1
        const val FORMAT_YUYV = 4
    }
}
