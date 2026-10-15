package dev.rubec.otoscope.stream.ne3

/**
 * Reassembles the NE3 camera's chunked video frames from UDP/8800 datagrams.
 * Wire format from haxko e.V.'s reference implementation
 * (https://github.com/haxko/NE3-Scope, `ne3.py`).
 *
 * Every datagram carries a 56-byte header followed by chunk bytes:
 *
 * ```
 *   offset  size  field
 *   ------  ----  -----
 *      0     u8   magic 0x93
 *      1     u8   msg_type (1 = fragment, 2 = ack, 4 = mcu-ctl)
 *      2     u16  packet length (LE, includes this header)
 *      4     u32  reserved
 *      8     u64  img_number        (monotonic frame counter)
 *     16     u64  img_number2       (same as img_number, duplicated)
 *     24     u64  reserved
 *     32     u32  packet_number     (0-based chunk index within frame)
 *     36     u32  packet_count      (total chunks for this frame)
 *     40     u32  img_size          (total scan bytes for this frame)
 *     44     u16  img_width
 *     46     u16  img_height
 *     48     u8   img_quality       (one of 5/10/25/50/75/100)
 *     49..55 reserved
 *     56..1079    scan data, capped at [CHUNK_PAYLOAD_SIZE] bytes per chunk
 *                 (last chunk may be shorter)
 *     1080..1090  chunk 0 only: 11-byte ASCII accelerometer suffix
 *                 `"+XXXX+-YYYY"` (two signed 5-char X/Y readings)
 * ```
 *
 * Frame identity is **img_number**, interpreted as a monotonic counter:
 *  - `>` current ⇒ new frame; discard the in-flight one
 *  - `==` current ⇒ continuation of the current frame
 *  - `<` current ⇒ stale retransmit, drop
 *
 * The camera stops advancing img_number unless [Ne3VideoClient] sends ACK
 * + REQ-next control messages after every completed frame.
 */
internal class Ne3FrameAssembler {

    sealed interface Outcome {
        /** A complete headerless scan. The caller prepends [Ne3JpegHeader]
         *  and appends `FF D9` EOI before decode.
         *
         *  [qualityLevel] is `null` when the camera reports a Q outside the
         *  vendor's six-element enum; the caller falls back to
         *  [Ne3JpegHeader.DEFAULT_Q].
         *
         *  [width] / [height] vary per firmware (416×416 and 640×360 both
         *  seen on the wire); [Ne3JpegHeader.build] patches SOF0 to match.
         *
         *  [imgNumber] drives the ACK/REQ cycle in [Ne3VideoClient]. */
        data class Frame(
            val scan: ByteArray,
            val qualityLevel: Int?,
            val width: Int,
            val height: Int,
            val imgNumber: Long,
            val accelX: Int?,
            val accelY: Int?,
        ) : Outcome
        /** Chunk recorded, frame not yet complete. */
        data object Building : Outcome
        /** The in-flight frame was discarded when a higher [imgNumber] arrived. */
        data object Dropped : Outcome
        /** An mcu-ctl message (msg_type=4). Exposed so a caller can log the
         *  payload; rotation on this hardware is delivered inline via
         *  [Frame.accelX] / [Frame.accelY] instead. */
        data class CtlMsg(val seq: Long, val payload: ByteArray) : Outcome
        /** A known-but-non-video message type (ack / queryinfo-resp). */
        data class Other(val msgType: Int, val payload: ByteArray) : Outcome
        /** Packet too short, wrong magic, or otherwise unparseable. */
        data object Invalid : Outcome
    }

    /** Per-frame buffer. Only one is held at a time because img_number is
     *  monotonic — any arriving chunk with a higher value replaces it. */
    private class CurrentFrame(
        val imgNumber: Long,
        val expected: Int,
        val quality: Int?,
        val width: Int,
        val height: Int,
    ) {
        val chunks = java.util.TreeMap<Int, ByteArray>()
        var accelX: Int? = null
        var accelY: Int? = null
    }

    private var current: CurrentFrame? = null

    fun feed(packet: ByteArray, len: Int): Outcome {
        if (len < COMMON_HEADER_SIZE) return Outcome.Invalid
        if (packet[0] != PACKET_MAGIC) return Outcome.Invalid
        val msgType = packet[1].toInt() and 0xff

        if (msgType == MSG_TYPE_MCU_CTL) {
            if (len < CTLMSG_HEADER_SIZE) return Outcome.Invalid
            val seq = readU32LE(packet, 4).toLong() and 0xffffffffL
            return Outcome.CtlMsg(seq, packet.copyOfRange(CTLMSG_HEADER_SIZE, len))
        }

        if (msgType != MSG_TYPE_FRAG) {
            return Outcome.Other(msgType, packet.copyOfRange(COMMON_HEADER_SIZE, len))
        }

        if (len < FRAG_HEADER_SIZE) return Outcome.Invalid
        val imgNumber = readU64LE(packet, 8)
        val packetNumber = readU32LE(packet, 32)
        val packetCount = readU32LE(packet, 36)

        if (packetCount <= 0 || packetCount > MAX_CHUNKS_PER_FRAME) return Outcome.Invalid
        if (packetNumber < 0 || packetNumber >= packetCount) return Outcome.Invalid

        val payloadLen = len - FRAG_HEADER_SIZE
        if (payloadLen <= 0) return Outcome.Invalid

        var droppedPrevious = false
        var frame = current

        when {
            frame == null -> {
                frame = startFrame(packet, imgNumber, packetCount)
                current = frame
            }
            imgNumber > frame.imgNumber -> {
                droppedPrevious = frame.chunks.isNotEmpty()
                frame = startFrame(packet, imgNumber, packetCount)
                current = frame
            }
            imgNumber < frame.imgNumber -> return Outcome.Invalid
            // imgNumber == frame.imgNumber: continuation of the current frame.
        }

        // Only the first CHUNK_PAYLOAD_SIZE bytes of each chunk are scan
        // data; chunk 0's trailing 11 bytes are the ASCII accel suffix,
        // never scan.
        val scanLen = minOf(payloadLen, CHUNK_PAYLOAD_SIZE)
        frame.chunks[packetNumber] =
            packet.copyOfRange(FRAG_HEADER_SIZE, FRAG_HEADER_SIZE + scanLen)

        if (packetNumber == 0 && payloadLen >= CHUNK_PAYLOAD_SIZE + ROTATION_SUFFIX_SIZE) {
            parseRotation(packet, FRAG_HEADER_SIZE + CHUNK_PAYLOAD_SIZE, frame)
        }

        if (frame.chunks.size < frame.expected ||
            frame.chunks.firstKey() != 0 ||
            frame.chunks.lastKey() != frame.expected - 1
        ) {
            return if (droppedPrevious) Outcome.Dropped else Outcome.Building
        }

        val total = frame.chunks.values.sumOf { it.size }
        val scan = ByteArray(total)
        var offset = 0
        for (part in frame.chunks.values) {
            System.arraycopy(part, 0, scan, offset, part.size)
            offset += part.size
        }

        val out = Outcome.Frame(
            scan = scan,
            qualityLevel = frame.quality,
            width = frame.width,
            height = frame.height,
            imgNumber = frame.imgNumber,
            accelX = frame.accelX,
            accelY = frame.accelY,
        )
        current = null
        return out
    }

    private fun startFrame(packet: ByteArray, imgNumber: Long, packetCount: Int): CurrentFrame {
        val q = packet[48].toInt() and 0xff
        return CurrentFrame(
            imgNumber = imgNumber,
            expected = packetCount,
            quality = if (q in VENDOR_Q_LEVELS) q else null,
            width = readU16LE(packet, 44),
            height = readU16LE(packet, 46),
        )
    }

    /** Parses the 11-byte ASCII accel suffix `"<±XXXX>·<±YYYY>"` on chunk 0
     *  (middle char is a separator, dropped). Leaves accel fields null when
     *  the bytes don't parse — some firmwares may not embed this suffix. */
    private fun parseRotation(packet: ByteArray, offset: Int, frame: CurrentFrame) {
        if (offset + ROTATION_SUFFIX_SIZE > packet.size) return
        try {
            val raw = String(packet, offset, ROTATION_SUFFIX_SIZE, Charsets.US_ASCII)
            frame.accelX = raw.substring(0, 5).trim().toInt()
            frame.accelY = raw.substring(6, 11).trim().toInt()
        } catch (_: Exception) {
            // Non-ASCII or malformed suffix: leave accel null.
        }
    }

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

        const val MSG_TYPE_FRAG: Int = 0x01
        const val MSG_TYPE_ACK: Int = 0x02
        const val MSG_TYPE_MCU_CTL: Int = 0x04
        const val MSG_TYPE_QUERY_INFO_RESP: Int = 0x08

        /** Bytes shared by every message type: magic, msg_type, length, and
         *  one reserved u32. */
        const val COMMON_HEADER_SIZE: Int = 8

        /** Bytes preceding the sensor-frame payload in an mcu-ctl datagram
         *  (magic, msg_type, u16 total-length, u32 seq, u16 payload-length,
         *  2 reserved). */
        const val CTLMSG_HEADER_SIZE: Int = 12

        /** Bytes preceding the scan payload in a fragment datagram. */
        const val FRAG_HEADER_SIZE: Int = 56

        /** Scan bytes per chunk. Bytes past this on chunk 0 are the ASCII
         *  accel suffix; the last chunk is typically shorter. */
        const val CHUNK_PAYLOAD_SIZE: Int = 1024

        /** ASCII rotation suffix size on chunk 0 (`"+XXXX+-YYYY"`). */
        const val ROTATION_SUFFIX_SIZE: Int = 11

        /** Sanity ceiling on chunk count; stops a corrupt packet_count
         *  field from allocating a huge TreeMap. */
        private const val MAX_CHUNKS_PER_FRAME: Int = 256

        /** Vendor's six Q levels; anything else from the wire gets clamped
         *  to null and falls back to [Ne3JpegHeader.DEFAULT_Q]. */
        private val VENDOR_Q_LEVELS: Set<Int> = setOf(5, 10, 25, 50, 75, 100)
    }
}
