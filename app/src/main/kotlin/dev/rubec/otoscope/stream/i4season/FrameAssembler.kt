package dev.rubec.otoscope.stream.i4season

/**
 * Assembles i4season video frames from UDP chunks.
 *
 * Per-packet wire format (type 1; type 6 adds 12 bytes of 6-axis data before
 * the payload, which we skip):
 *
 *   offset  size  field
 *   ------  ----  -----
 *      0     u8   packet type: 1 = 16-byte header, 6 = 28-byte header
 *      1     u8   packet sequence, +1 per datagram across frames
 *      2     u8   frame id
 *      3     u8   last-chunk flag
 *      4     u8   number of chunks in the frame
 *      5     u8   flags; bit0 = accelerometer sample present
 *      6-9   u32  packed accelerometer sample, see [I4seasonProtocol.rollDegrees]
 *     10-11  u8×2 last two bytes of the camera MAC
 *     12-13  i16  width
 *     14-15  i16  height
 *     16..   …    JPEG chunk; the last chunk is zero-padded after FFD9
 *
 * There is no per-chunk index, only the global sequence number. Like the vendor
 * library, we drop a frame when the sequence has a gap or when the number of
 * received chunks doesn't match the count on the last chunk.
 */
internal class FrameAssembler {

    sealed interface Outcome {
        /** A complete JPEG frame. [gsensor] is the raw packed accelerometer
         *  sample, or null when the camera didn't include one. */
        class Frame(val data: ByteArray, val gsensor: Int?, val width: Int, val height: Int) : Outcome
        /** Chunk recorded, frame not yet complete. */
        data object Building : Outcome
        /** A frame was dropped (sequence gap, missing chunks, not a JPEG). */
        data object Dropped : Outcome
        /** Not a video packet we understand. */
        data object Invalid : Outcome
    }

    private var frameId = -1
    private var lastSeq = -1
    private var broken = false
    private val chunks = ArrayList<ByteArray>()

    fun feed(packet: ByteArray, len: Int): Outcome {
        val headerSize = when (packet.getOrNull(0)?.toInt()) {
            1 -> HEADER_V1
            6 -> HEADER_V6
            else -> return Outcome.Invalid
        }
        if (len < headerSize) return Outcome.Invalid

        val seq = packet[1].toInt() and 0xff
        val id = packet[2].toInt() and 0xff
        val last = packet[3].toInt() != 0
        val count = packet[4].toInt() and 0xff
        val flags = packet[5].toInt() and 0xff

        var outcome: Outcome = Outcome.Building
        if (id != frameId) {
            // A frame still in flight never saw its last chunk.
            if (chunks.isNotEmpty()) outcome = Outcome.Dropped
            frameId = id
            chunks.clear()
            broken = false
        } else if (seq != (lastSeq + 1) and 0xff) {
            broken = true
        }
        lastSeq = seq
        chunks.add(packet.copyOfRange(headerSize, len))
        if (!last) return outcome

        val complete = !broken && chunks.size == count
        val data = if (complete) joinChunks() else null
        frameId = -1
        chunks.clear()
        if (data == null || data.size < 2 || data[0] != 0xFF.toByte() || data[1] != 0xD8.toByte()) {
            return Outcome.Dropped
        }
        return Outcome.Frame(
            data = trimAfterEoi(data),
            gsensor = if (flags and 1 != 0) readIntLe(packet, 6) else null,
            width = readShortLe(packet, 12),
            height = readShortLe(packet, 14),
        )
    }

    private fun joinChunks(): ByteArray {
        val out = ByteArray(chunks.sumOf { it.size })
        var pos = 0
        for (c in chunks) {
            c.copyInto(out, pos)
            pos += c.size
        }
        return out
    }

    companion object {
        const val HEADER_V1 = 16
        const val HEADER_V6 = 28

        /** Cut the zero padding the firmware appends after the JPEG's EOI marker. */
        fun trimAfterEoi(data: ByteArray): ByteArray {
            for (i in data.size - 2 downTo 2) {
                if (data[i] == 0xFF.toByte() && data[i + 1] == 0xD9.toByte()) {
                    return if (i + 2 == data.size) data else data.copyOf(i + 2)
                }
            }
            return data
        }

        private fun readIntLe(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xff) or
                ((b[off + 1].toInt() and 0xff) shl 8) or
                ((b[off + 2].toInt() and 0xff) shl 16) or
                ((b[off + 3].toInt() and 0xff) shl 24)

        private fun readShortLe(b: ByteArray, off: Int): Int =
            ((b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8)).toShort().toInt()
    }
}
