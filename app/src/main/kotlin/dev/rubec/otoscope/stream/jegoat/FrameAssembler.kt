package dev.rubec.otoscope.stream.jegoat

/**
 * Assembles JEGOAT video frames from UDP chunks.
 *
 * Per-packet wire format:
 *
 *   offset  size  field
 *   ------  ----  -----
 *      0     u8   frame id (rolls every frame)
 *      1     u8   chunk_seq within frame, 0 == first chunk
 *      2     u8   chunks_total for this frame
 *      3-6   f32  rotation angle in degrees, little-endian
 *      7-23  …    unused (16 bytes of padding)
 *     24..   …    raw JPEG payload, no length field
 *
 * Reassembly: collect chunks keyed by `chunk_seq` (UDP may reorder), emit the
 * frame when we have all [chunks_total] chunks. Frames with missing chunks are
 * dropped (decoding partial JPEGs produced visible glitches).
 */
internal class FrameAssembler {

    sealed interface Outcome {
        /** A complete JPEG frame, ready to decode. */
        data class Frame(val data: ByteArray, val angle: Float) : Outcome
        /** Chunk recorded, frame not yet complete. */
        data object Building : Outcome
        /** A frame in flight was dropped (missing chunks or discontinuity). */
        data object Dropped : Outcome
        /** Packet too short to parse a header. */
        data object Invalid : Outcome
    }

    private var currentFrameId: Int = -1
    private var expectedChunks: Int = 0
    private val chunks = java.util.TreeMap<Int, ByteArray>()
    private var pendingAngle: Float = 0f

    fun feed(packet: ByteArray, len: Int): Outcome {
        if (len < HEADER_SIZE) return Outcome.Invalid

        val frameId = packet[0].toInt() and 0xff
        val chunkSeq = packet[1].toInt() and 0xff
        val chunksTotal = packet[2].toInt() and 0xff
        val angle = Float.fromBits(
            (packet[3].toInt() and 0xff) or
                ((packet[4].toInt() and 0xff) shl 8) or
                ((packet[5].toInt() and 0xff) shl 16) or
                ((packet[6].toInt() and 0xff) shl 24)
        )
        val payload = packet.copyOfRange(HEADER_SIZE, len)

        if (chunkSeq == 0) {
            // New frame starts here — flush whatever's in flight first.
            val flushed = flushFrame()
            currentFrameId = frameId
            expectedChunks = chunksTotal
            pendingAngle = angle
            chunks.clear()
            chunks[0] = payload
            // `flushed` is the *previous* frame; tryComplete() below covers the case
            // where the new frame is itself complete in one chunk (small JPEG).
            return flushed ?: tryComplete()
        }

        if (frameId != currentFrameId || currentFrameId < 0) {
            // Mid-frame chunk for a frame we never saw the start of; drop it
            // and reset, otherwise we'd splice random JPEG fragments together.
            val flushed = flushFrame()
            currentFrameId = -1
            chunks.clear()
            return flushed ?: Outcome.Dropped
        }

        chunks[chunkSeq] = payload
        return tryComplete()
    }

    /** If we now have every chunk for the current frame, emit it. */
    private fun tryComplete(): Outcome {
        if (expectedChunks <= 0 || chunks.size < expectedChunks) {
            return Outcome.Building
        }
        return flushFrame() ?: Outcome.Building
    }

    /** Concatenate buffered chunks in sequence order and emit. Returns null if
     *  nothing is buffered, the frame is missing chunks, or the result is
     *  smaller than [MIN_FRAME_BYTES] (stragglers from a stalled frame). */
    private fun flushFrame(): Outcome.Frame? {
        if (chunks.isEmpty()) return null

        val complete = expectedChunks > 0 && chunks.size == expectedChunks
        // Snapshot before clearing, so the caller doesn't see internal mutation.
        val angle = pendingAngle
        chunks.let { c ->
            if (!complete) {
                c.clear()
                currentFrameId = -1
                return null
            }
        }

        val totalPayload = chunks.values.sumOf { it.size }
        val buf = ByteArray(totalPayload + 2) // reserve room for synthetic EOI if needed
        var offset = 0
        for (chunk in chunks.values) {
            System.arraycopy(chunk, 0, buf, offset, chunk.size)
            offset += chunk.size
        }
        // The last chunk should already end with the JPEG EOI marker; append
        // one if firmware happens to omit it — costs nothing on a complete
        // frame and avoids decoder errors on the occasional malformed tail.
        val needsEoi = offset < 2 ||
            buf[offset - 2] != 0xFF.toByte() ||
            buf[offset - 1] != 0xD9.toByte()
        if (needsEoi) {
            buf[offset] = 0xFF.toByte()
            buf[offset + 1] = 0xD9.toByte()
            offset += 2
        }

        chunks.clear()
        currentFrameId = -1

        return if (offset >= MIN_FRAME_BYTES) {
            Outcome.Frame(buf.copyOfRange(0, offset), angle)
        } else null
    }

    companion object {
        const val HEADER_SIZE = 24
        // Skip stragglers from stalled frames — anything this small is noise.
        private const val MIN_FRAME_BYTES = 1000
    }
}
