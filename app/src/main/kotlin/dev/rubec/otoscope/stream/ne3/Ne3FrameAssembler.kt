package dev.rubec.otoscope.stream.ne3

/**
 * Reassembles the NE3 camera's chunked video frames from UDP/8800 datagrams.
 *
 * Every datagram carries a common 8-byte prelude followed by a message-type-
 * specific body:
 *
 * ```
 *   offset  size  field
 *   ------  ----  -----
 *      0     u8   magic 0x93 (drop otherwise)
 *      1     u8   msg_type (1 = fragment, others ignored here)
 *      2     u16  packet length (LE, includes this header)
 *      4     u32  reserved / unknown
 * ```
 *
 * For msg_type = 1 (fragment) the body carries the piece of a JPEG scan:
 *
 * ```
 *      8     u64  frame counter (LE, monotonic per frame)
 *     16     u64  reserved
 *     24     u64  reserved
 *     32     u32  chunk_seq   (LE, 0-based per frame)
 *     36     u32  chunk_total (LE, number of chunks in this frame)
 *     40     u16  reserved (offsets 40..55 are per-vendor bookkeeping)
 *     56..   raw JPEG scan bytes (headerless — [Ne3JpegHeader] is prepended
 *           by the caller when a frame is complete).
 * ```
 *
 * The vendor SDK tracks four in-flight frames in a ring keyed by
 * `frame_counter & 3`; we only keep the *current* frame — if a datagram
 * from a different frame arrives we drop the previous one and start over.
 * This is enough for a live viewer: reordering across frames only happens
 * when packets are ~40 ms late, which is longer than a viewer will care to
 * wait anyway.
 *
 * Chunks may arrive out of order within a frame (UDP), so payloads are
 * buffered by [chunk_seq] and only concatenated once every slot is filled.
 * A frame missing any chunk is reported as [Outcome.Dropped].
 */
internal class Ne3FrameAssembler {

    sealed interface Outcome {
        /** A complete headerless scan, ready for [Ne3JpegHeader] prepend. */
        data class Frame(val scan: ByteArray) : Outcome
        /** Chunk recorded, frame not yet complete. */
        data object Building : Outcome
        /** A frame in flight was dropped (missing chunks or a new frame
         *  arrived before this one completed). */
        data object Dropped : Outcome
        /** A known-but-non-video message type (ack / mcu-ctl / queryinfo);
         *  the caller can log its payload for future decoding. */
        data class Other(val msgType: Int, val payload: ByteArray) : Outcome
        /** Packet too short, wrong magic, or otherwise unparseable. */
        data object Invalid : Outcome
    }

    private var currentFrame: Long = -1L
    private var expectedChunks: Int = 0
    private val chunks = java.util.TreeMap<Int, ByteArray>()

    fun feed(packet: ByteArray, len: Int): Outcome {
        // Common 8-byte prelude is required before we can look at the type.
        if (len < COMMON_HEADER_SIZE) return Outcome.Invalid
        if (packet[0] != PACKET_MAGIC) return Outcome.Invalid
        val msgType = packet[1].toInt() and 0xff

        // Non-fragment types (ack / mcu-ctl / queryinfo-response) carry no
        // JPEG data — hand the payload back so the caller can log it while
        // we iterate on their formats.
        if (msgType != MSG_TYPE_FRAG) {
            return Outcome.Other(msgType, packet.copyOfRange(COMMON_HEADER_SIZE, len))
        }

        // Fragment payload — full 56-byte header required.
        if (len < FRAG_HEADER_SIZE) return Outcome.Invalid
        val frameId = readU64LE(packet, 8)
        val chunkSeq = readU32LE(packet, 32)
        val chunkTotal = readU32LE(packet, 36)

        if (chunkTotal <= 0 || chunkTotal > MAX_CHUNKS_PER_FRAME) return Outcome.Invalid
        if (chunkSeq < 0 || chunkSeq >= chunkTotal) return Outcome.Invalid

        val payloadLen = len - FRAG_HEADER_SIZE
        if (payloadLen <= 0) return Outcome.Invalid

        // A datagram from a different frame implicitly drops the previous
        // one. This is intentional: chasing late chunks across frames adds
        // latency and buys us nothing on a live viewer.
        var droppedPrevious = false
        if (frameId != currentFrame) {
            droppedPrevious = chunks.isNotEmpty()
            currentFrame = frameId
            expectedChunks = chunkTotal
            chunks.clear()
        }

        chunks[chunkSeq] = packet.copyOfRange(FRAG_HEADER_SIZE, len)

        if (chunks.size < expectedChunks) {
            return if (droppedPrevious) Outcome.Dropped else Outcome.Building
        }

        // All slots filled — stitch the scan in sequence order and reset.
        val total = chunks.values.sumOf { it.size }
        val scan = ByteArray(total)
        var offset = 0
        for (part in chunks.values) {
            System.arraycopy(part, 0, scan, offset, part.size)
            offset += part.size
        }
        chunks.clear()
        currentFrame = -1L
        expectedChunks = 0
        return Outcome.Frame(scan)
    }

    private fun readU32LE(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or
            ((b[off + 1].toInt() and 0xff) shl 8) or
            ((b[off + 2].toInt() and 0xff) shl 16) or
            ((b[off + 3].toInt() and 0xff) shl 24)

    private fun readU64LE(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = v or ((b[off + i].toLong() and 0xff) shl (i * 8))
        return v
    }

    companion object {
        /** All datagrams the NE3 sends start with this byte. */
        const val PACKET_MAGIC: Byte = 0x93.toByte()

        /** msg_type values the vendor SDK dispatches on. Only [MSG_TYPE_FRAG]
         *  carries a JPEG fragment; the rest are logged as diagnostic hex
         *  until we decode their payloads. */
        const val MSG_TYPE_FRAG: Int = 0x01
        const val MSG_TYPE_ACK: Int = 0x02
        const val MSG_TYPE_MCU_CTL: Int = 0x04
        const val MSG_TYPE_QUERY_INFO_RESP: Int = 0x08

        /** Bytes shared by every message type: magic, msg_type, length,
         *  and one reserved u32. */
        const val COMMON_HEADER_SIZE: Int = 8

        /** Bytes preceding the scan payload in a fragment datagram. */
        const val FRAG_HEADER_SIZE: Int = 56

        /** Sanity ceiling. A single JPEG frame at ~40 KB with a ~1 KB
         *  per-datagram UDP payload would fit in ~40 chunks; 256 is well
         *  above what real hardware produces and prevents runaway
         *  allocations from a corrupt chunk_total field. */
        private const val MAX_CHUNKS_PER_FRAME: Int = 256
    }
}
