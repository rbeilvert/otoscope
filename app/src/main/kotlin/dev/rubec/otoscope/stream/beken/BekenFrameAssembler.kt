package dev.rubec.otoscope.stream.beken

/**
 * Reassembles the HND NE3's video frames from UDP datagrams.
 *
 * Every datagram is a 4-byte header followed by a slice of one complete JPEG
 * (the first chunk starts with `FF D8`):
 *
 * ```
 *   0  u8  frame id, +1 per frame, wraps at 256
 *   1  u8  1 on the last chunk of the frame, else 0
 *   2  u8  chunk number, from 1; on the last chunk it equals the chunk count
 *   3  u8  4 in every capture so far
 *   4..    JPEG bytes
 * ```
 *
 * The camera re-sends some chunks, so chunks are kept by number, and a repeat
 * of a frame we just finished is ignored instead of starting a phantom frame.
 */
internal class BekenFrameAssembler {

    sealed interface Outcome {
        /** A complete JPEG, ready for [dev.rubec.otoscope.stream.JpegDecoder]. */
        class Frame(val jpeg: ByteArray, val frameId: Int) : Outcome

        /** An unfinished frame was replaced by a newer one. */
        data object Dropped : Outcome

        /** Chunk stored, frame not complete yet, or not a video packet. */
        data object Building : Outcome
    }

    private var frameId = -1
    private var doneId = -1
    private var total = 0
    private val chunks = HashMap<Int, ByteArray>()

    fun feed(packet: ByteArray, length: Int): Outcome {
        if (length <= HEADER_SIZE) return Outcome.Building
        val id = packet[0].toInt() and 0xff
        val last = packet[1].toInt() and 0xff
        val number = packet[2].toInt() and 0xff
        if (number == 0) return Outcome.Building
        if (id == doneId) return Outcome.Building

        var dropped = false
        if (id != frameId) {
            dropped = chunks.isNotEmpty()
            frameId = id
            total = 0
            chunks.clear()
        }
        chunks[number] = packet.copyOfRange(HEADER_SIZE, length)
        if (last == 1) total = number

        val complete = total > 0 && chunks.size >= total && (1..total).all { it in chunks }
        if (!complete) return if (dropped) Outcome.Dropped else Outcome.Building

        val size = (1..total).sumOf { chunks.getValue(it).size }
        val jpeg = ByteArray(size)
        var at = 0
        for (i in 1..total) {
            val part = chunks.getValue(i)
            System.arraycopy(part, 0, jpeg, at, part.size)
            at += part.size
        }
        doneId = id
        frameId = -1
        chunks.clear()
        val isJpeg = jpeg.size >= 2 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()
        return if (isJpeg) Outcome.Frame(jpeg, id) else Outcome.Dropped
    }

    companion object {
        const val HEADER_SIZE = 4
    }
}
