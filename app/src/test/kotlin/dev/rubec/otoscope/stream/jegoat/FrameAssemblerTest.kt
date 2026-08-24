package dev.rubec.otoscope.stream.jegoat

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Regression tests for JEGOAT's [FrameAssembler]. JEGOAT's UDP framing differs
 * from Xylla's: a 24-byte header carrying frame-id, chunk_seq, chunks_total,
 * and a little-endian float32 angle. Missing / stalled frames are dropped
 * rather than decoded partially.
 */
class FrameAssemblerTest {

    // A payload big enough to survive MIN_FRAME_BYTES (1000). Ends with EOI so
    // the assembler shouldn't need to append one.
    private val bigPayload1: ByteArray = ByteArray(1200) { 0xAA.toByte() }
        .also { it[it.size - 2] = 0xFF.toByte(); it[it.size - 1] = 0xD9.toByte() }

    @Test fun `single-chunk frame emits with angle preserved`() {
        val angle = 42.5f
        val packet = packet(frameId = 1, chunkSeq = 0, chunksTotal = 1, angle = angle, payload = bigPayload1)
        val out = FrameAssembler().feed(packet, packet.size)
        val frame = assertIs<FrameAssembler.Outcome.Frame>(out)
        assertEquals(angle, frame.angle)
        // Payload passes through verbatim (already ends with FFD9, so no
        // synthetic EOI is appended).
        assertContentEquals(bigPayload1, frame.data)
    }

    @Test fun `two-chunk frame reassembles in order`() {
        val a = FrameAssembler()
        val p1 = ByteArray(700) { 0x01 } // first, no EOI required
        val p2 = ByteArray(500) { 0x02 } // last, ends with EOI
            .also { it[it.size - 2] = 0xFF.toByte(); it[it.size - 1] = 0xD9.toByte() }

        val first = packet(2, 0, 2, angle = 10f, payload = p1)
        val second = packet(2, 1, 2, angle = 10f, payload = p2)
        assertIs<FrameAssembler.Outcome.Building>(a.feed(first, first.size))
        val out = a.feed(second, second.size)
        val frame = assertIs<FrameAssembler.Outcome.Frame>(out)
        assertContentEquals(p1 + p2, frame.data)
    }

    @Test fun `chunk reordering is fixed by the TreeMap key`() {
        val a = FrameAssembler()
        // chunk_seq=0 comes second on the wire — the assembler MUST be
        // keyed by chunk_seq (TreeMap), not by arrival order, otherwise the
        // frame decodes with bytes concatenated in the wrong order.
        val p0 = ByteArray(700) { 0x11 }
        val p1 = ByteArray(500) { 0x22 }
            .also { it[it.size - 2] = 0xFF.toByte(); it[it.size - 1] = 0xD9.toByte() }
        val firstOnWire  = packet(3, 0, 2, angle = 0f, payload = p0)
        val secondOnWire = packet(3, 1, 2, angle = 0f, payload = p1)
        a.feed(firstOnWire, firstOnWire.size)
        val frame = assertIs<FrameAssembler.Outcome.Frame>(
            a.feed(secondOnWire, secondOnWire.size)
        )
        assertContentEquals(p0 + p1, frame.data)
    }

    @Test fun `mid-frame chunk without a start is dropped`() {
        // chunk_seq > 0 arrives before any chunk_seq == 0 for that frame_id.
        val orphan = packet(9, 2, 3, angle = 0f, payload = ByteArray(300))
        val out = FrameAssembler().feed(orphan, orphan.size)
        assertIs<FrameAssembler.Outcome.Dropped>(out)
    }

    @Test fun `packet shorter than the 24-byte header is Invalid`() {
        val tiny = ByteArray(10)
        assertIs<FrameAssembler.Outcome.Invalid>(FrameAssembler().feed(tiny, tiny.size))
    }

    @Test fun `synthetic EOI is appended when firmware omits it`() {
        // Same as the single-chunk case but without a trailing FFD9.
        val noEoi = ByteArray(1024) { 0x77 }
        val packet = packet(4, 0, 1, angle = 0f, payload = noEoi)
        val out = FrameAssembler().feed(packet, packet.size)
        val frame = assertIs<FrameAssembler.Outcome.Frame>(out)
        assertTrue(
            frame.data[frame.data.size - 2] == 0xFF.toByte() &&
                frame.data[frame.data.size - 1] == 0xD9.toByte(),
            "expected synthesised EOI (0xFF 0xD9) at frame tail",
        )
    }

    // ---- helpers -----------------------------------------------------------

    private fun packet(
        frameId: Int,
        chunkSeq: Int,
        chunksTotal: Int,
        angle: Float,
        payload: ByteArray,
    ): ByteArray {
        val header = ByteArray(24)
        header[0] = frameId.toByte()
        header[1] = chunkSeq.toByte()
        header[2] = chunksTotal.toByte()
        val angleBits = angle.toRawBits()
        header[3] = (angleBits and 0xff).toByte()
        header[4] = ((angleBits ushr 8) and 0xff).toByte()
        header[5] = ((angleBits ushr 16) and 0xff).toByte()
        header[6] = ((angleBits ushr 24) and 0xff).toByte()
        // 7..23 padding, zeroed by ByteArray().
        return header + payload
    }
}
