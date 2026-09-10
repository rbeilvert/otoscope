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
 *     40     u32  reserved
 *     44     u16  width  (LE) — true frame width; varies per firmware (416,
 *                 640, …), NOT fixed, so the SOF0 field of the pre-baked
 *                 [Ne3JpegHeader] must be patched to match before decode.
 *     46     u16  height (LE) — same deal; the vendor native lib byte-swaps
 *                 these two into the output header's SOF0 at
 *                 `.text+0x5b8c..0x5c34`.
 *     48     u8   quality level — one of {5, 10, 25, 50, 75, 100}; selects
 *                 which pre-baked [Ne3JpegHeader] to prepend. The vendor
 *                 native lib reads the same byte from this exact offset
 *                 (`handle_mcu_msg_frag` at `.text+0x44dc`), copies it
 *                 into the per-frame ring-slot context, and feeds it to
 *                 the Q selector at `.text+0x59f0`.
 *     49..55 reserved (per-vendor bookkeeping)
 *     56..   raw JPEG scan bytes (headerless — [Ne3JpegHeader] is prepended
 *           by the caller when a frame is complete).
 * ```
 *
 * **Ring buffer, not single slot.** The camera happily has several frames
 * in flight at once — a late chunk from frame N can land after chunks of
 * frame N+1 have already started, and if we only kept one slot (as an
 * earlier revision did) chunks of frame N+1 would be lost whenever a
 * stale frame N chunk flushed the state. The vendor native lib keeps
 * [RING_SIZE] slots keyed by `(frame_counter - 1) & 3`
 * (`handle_mcu_msg_frag` at `.text+0x458c..0x45bc`); we mirror that so
 * every in-flight frame gets its own chunk map. On completion we also
 * verify the scan ends with an `FF D9` EOI, because the single most
 * visible failure mode of a mis-assembled frame is a scan that decodes
 * past its intended end into whatever bytes the camera (or an earlier
 * frame) left in the buffer.
 *
 * Chunks may arrive out of order within a frame (UDP), so payloads are
 * buffered by [chunk_seq] and only concatenated once every slot in
 * `[0..expected)` is present. A frame missing any slot is reported as
 * [Outcome.Dropped] when a newer frame evicts its slot.
 */
internal class Ne3FrameAssembler {

    sealed interface Outcome {
        /** A complete headerless scan, ready for [Ne3JpegHeader] prepend.
         *  [qualityLevel] is the Q byte lifted from the first fragment of
         *  this frame (packet offset 48) — one of {5, 10, 25, 50, 75, 100}
         *  on a well-formed stream, or null if the camera reported a value
         *  outside that set, in which case the caller should fall back to
         *  [Ne3JpegHeader.DEFAULT_Q].
         *
         *  [width] / [height] are the true per-frame dimensions lifted from
         *  packet offsets 44 and 46. Both are > 0 on a well-formed stream;
         *  if either is 0 or absurdly large the caller should fall back to
         *  the vendor's [Ne3JpegHeader.DEFAULT_WIDTH] / [DEFAULT_HEIGHT]. */
        data class Frame(
            val scan: ByteArray,
            val qualityLevel: Int?,
            val width: Int,
            val height: Int,
        ) : Outcome
        /** Chunk recorded, frame not yet complete. */
        data object Building : Outcome
        /** A frame in flight was evicted from its ring slot (a newer frame
         *  started reusing the slot before this one completed) or completed
         *  without a trailing `FF D9` EOI (likely stitched / truncated). */
        data object Dropped : Outcome
        /** An mcu-ctl message (msg_type=4) — carries [Ne3SensorParser]
         *  frames (rotation, shutter). [seq] is the camera's monotonic
         *  message counter; [payload] is bytes after the 12-byte prelude,
         *  ready to feed into the parser. Split out from [Other] because
         *  it has its own well-defined wire layout and a parser attached
         *  to it, unlike the still-undecoded ack / queryinfo types. */
        data class CtlMsg(val seq: Long, val payload: ByteArray) : Outcome
        /** A known-but-non-video message type (ack / queryinfo-resp);
         *  the caller can log its payload as diagnostic hex. */
        data class Other(val msgType: Int, val payload: ByteArray) : Outcome
        /** Packet too short, wrong magic, or otherwise unparseable. */
        data object Invalid : Outcome
    }

    /** One in-flight frame's state. Separate from the assembler itself so
     *  the ring can hold several at once without their chunk maps or metadata
     *  bleeding into each other. */
    private class Slot {
        var frameId: Long = NO_FRAME
        var expected: Int = 0
        val chunks = java.util.TreeMap<Int, ByteArray>()
        var quality: Int? = null
        var width: Int = 0
        var height: Int = 0

        fun reset() {
            frameId = NO_FRAME
            expected = 0
            chunks.clear()
            quality = null
            width = 0
            height = 0
        }
    }

    private val slots: Array<Slot> = Array(RING_SIZE) { Slot() }

    fun feed(packet: ByteArray, len: Int): Outcome {
        // Common 8-byte prelude is required before we can look at the type.
        if (len < COMMON_HEADER_SIZE) return Outcome.Invalid
        if (packet[0] != PACKET_MAGIC) return Outcome.Invalid
        val msgType = packet[1].toInt() and 0xff

        // mcu-ctl (msg_type=4): decoded wire layout, carries sensor frames.
        // The vendor's handle_mcu_msg_ctlmsg hands Java a payload starting
        // at raw offset 12 (past magic, msg_type, u16 total-length, u32 seq,
        // u16 payload-length, 2 reserved); mirror that split so the parser
        // gets exactly the bytes the vendor's W0/b.f sees.
        if (msgType == MSG_TYPE_MCU_CTL) {
            if (len < CTLMSG_HEADER_SIZE) return Outcome.Invalid
            val seq = readU32LE(packet, 4).toLong() and 0xffffffffL
            return Outcome.CtlMsg(seq, packet.copyOfRange(CTLMSG_HEADER_SIZE, len))
        }

        // Other non-fragment types (ack / queryinfo-resp) have no decoded
        // wire layout yet — hand the payload back so the caller can log it
        // as diagnostic hex until a future revision decodes them.
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

        // Route to the ring slot the camera expects for this frame. Vendor's
        // slot formula at .text+0x458c: `((frame_counter - 1) & 3)`. We mask
        // to the ring size so the arithmetic stays safe when the counter
        // starts at 0 or wraps.
        val slot = slots[((frameId - 1) and (RING_SIZE - 1).toLong()).toInt() and (RING_SIZE - 1)]

        // Slot busy with a DIFFERENT frame → that one lost its race, evict.
        // "The slot was waiting for frame A chunks but frame B arrived at
        // the same slot" means A will never complete; its remaining chunks
        // would land on top of B. Clear so B gets a clean build.
        var evicted = false
        if (slot.frameId != frameId) {
            evicted = slot.chunks.isNotEmpty()
            slot.reset()
            slot.frameId = frameId
            slot.expected = chunkTotal
        }

        // Lift the Q byte + resolution off the first chunk only; later
        // chunks of the same frame carry the same values. Clamp the Q to
        // the vendor's allowed set — anything outside it signals a corrupt
        // or shifted header and falls back to the default downstream.
        if (chunkSeq == 0) {
            val q = packet[48].toInt() and 0xff
            slot.quality = if (q in VENDOR_Q_LEVELS) q else null
            slot.width = readU16LE(packet, 44)
            slot.height = readU16LE(packet, 46)
        }

        // Reject chunks whose seq is outside the first-chunk-declared range.
        // Prevents a stray `chunkSeq=7` arriving with `chunkTotal=8` (corrupt
        // bit flip, or genuinely a different frame that aliased into this
        // slot) from filling what was a missing slot in a smaller frame and
        // tricking the completion check.
        if (chunkSeq >= slot.expected) return Outcome.Invalid

        slot.chunks[chunkSeq] = packet.copyOfRange(FRAG_HEADER_SIZE, len)

        // Strict completion: not just `size == expected` but every seq in
        // [0, expected) must be present. TreeMap's firstKey/lastKey gives
        // us the range cheaply.
        if (slot.chunks.size < slot.expected ||
            slot.chunks.firstKey() != 0 ||
            slot.chunks.lastKey() != slot.expected - 1
        ) {
            return if (evicted) Outcome.Dropped else Outcome.Building
        }

        // All chunks present — stitch the scan in sequence order.
        val total = slot.chunks.values.sumOf { it.size }
        val scan = ByteArray(total)
        var offset = 0
        for (part in slot.chunks.values) {
            System.arraycopy(part, 0, scan, offset, part.size)
            offset += part.size
        }

        // Integrity check: a well-formed JPEG scan from the camera ends
        // with the `FF D9` EOI marker. If it doesn't, something stitched
        // or truncated the scan — drop rather than feed a mis-assembled
        // frame to the decoder.
        if (!endsWithEoi(scan)) {
            slot.reset()
            return Outcome.Dropped
        }

        val frame = Outcome.Frame(scan, slot.quality, slot.width, slot.height)
        slot.reset()
        return frame
    }

    private fun endsWithEoi(scan: ByteArray): Boolean =
        scan.size >= 2 &&
            scan[scan.size - 2] == EOI_HI &&
            scan[scan.size - 1] == EOI_LO

    private fun readU16LE(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or
            ((b[off + 1].toInt() and 0xff) shl 8)

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

        /** Bytes preceding the sensor-frame payload in an mcu-ctl datagram
         *  (magic, msg_type, u16 total-length, u32 seq, u16 payload-length,
         *  2 reserved). The vendor native lib hands Java the bytes at this
         *  offset; we do the same. */
        const val CTLMSG_HEADER_SIZE: Int = 12

        /** Bytes preceding the scan payload in a fragment datagram. */
        const val FRAG_HEADER_SIZE: Int = 56

        /** Ring buffer depth, matching the vendor's `& 3` slot formula.
         *  Must be a power of two for the bitmask arithmetic. */
        const val RING_SIZE: Int = 4

        /** Sentinel for "slot empty". Camera frame counters start at 1 and
         *  go up; -1 can't collide with a real one. */
        private const val NO_FRAME: Long = -1L

        /** Sanity ceiling. A single JPEG frame at ~40 KB with a ~1 KB
         *  per-datagram UDP payload would fit in ~40 chunks; 256 is well
         *  above what real hardware produces and prevents runaway
         *  allocations from a corrupt chunk_total field. */
        private const val MAX_CHUNKS_PER_FRAME: Int = 256

        /** Vendor's allowed Q enum, mirroring the six pre-baked headers in
         *  `libbl_vii_jni.so`. Values outside this set are rejected rather
         *  than guessed — a shifted/corrupt packet should fall back to
         *  the default header, not silently load a wrong matrix. */
        private val VENDOR_Q_LEVELS: Set<Int> = setOf(5, 10, 25, 50, 75, 100)

        private const val EOI_HI: Byte = 0xFF.toByte()
        private const val EOI_LO: Byte = 0xD9.toByte()
    }
}
