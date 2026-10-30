package dev.rubec.otoscope.stream.beken

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Regression tests for [BekenFrameAssembler]: 4-byte header (frame id, last
 *  flag, 1-based chunk number, 4) followed by JPEG bytes. */
class BekenFrameAssemblerTest {

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(19) { it.toByte() } +
        byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    private fun chunks(frameId: Int, data: ByteArray, size: Int = 12): List<ByteArray> {
        val parts = data.toList().chunked(size)
        return parts.mapIndexed { i, part ->
            val last = if (i == parts.lastIndex) 1 else 0
            byteArrayOf(frameId.toByte(), last.toByte(), (i + 1).toByte(), 4) + part.toByteArray()
        }
    }

    private fun BekenFrameAssembler.feed(p: ByteArray) = feed(p, p.size)

    @Test fun `chunks are joined into the jpeg`() {
        val asm = BekenFrameAssembler()
        val packets = chunks(7, jpeg)
        assertEquals(BekenFrameAssembler.Outcome.Building, asm.feed(packets[0]))
        val frame = assertIs<BekenFrameAssembler.Outcome.Frame>(asm.feed(packets[1]))
        assertContentEquals(jpeg, frame.jpeg)
        assertEquals(7, frame.frameId)
    }

    @Test fun `out of order and repeated chunks still complete the frame`() {
        val asm = BekenFrameAssembler()
        val p = chunks(3, jpeg)
        assertEquals(BekenFrameAssembler.Outcome.Building, asm.feed(p[1]))
        assertEquals(BekenFrameAssembler.Outcome.Building, asm.feed(p[1]))
        assertContentEquals(jpeg, assertIs<BekenFrameAssembler.Outcome.Frame>(asm.feed(p[0])).jpeg)
    }

    @Test fun `a repeat of the finished frame is ignored, not counted as a lost frame`() {
        val asm = BekenFrameAssembler()
        val p = chunks(3, jpeg)
        asm.feed(p[0])
        assertIs<BekenFrameAssembler.Outcome.Frame>(asm.feed(p[1]))
        assertEquals(BekenFrameAssembler.Outcome.Building, asm.feed(p[1]))
        // The next frame must not report the phantom as dropped.
        assertEquals(BekenFrameAssembler.Outcome.Building, asm.feed(chunks(4, jpeg)[0]))
    }

    @Test fun `an unfinished frame is dropped when the next one starts`() {
        val asm = BekenFrameAssembler()
        asm.feed(chunks(1, jpeg)[0])
        assertEquals(BekenFrameAssembler.Outcome.Dropped, asm.feed(chunks(2, jpeg)[0]))
    }

    @Test fun `a missing chunk never completes the frame`() {
        val asm = BekenFrameAssembler()
        val p = chunks(5, jpeg, size = 6)
        assertEquals(4, p.size)
        for (i in listOf(0, 2, 3)) assertEquals(BekenFrameAssembler.Outcome.Building, asm.feed(p[i]))
    }

    @Test fun `data that is not a jpeg is dropped`() {
        val asm = BekenFrameAssembler()
        var last: BekenFrameAssembler.Outcome = BekenFrameAssembler.Outcome.Building
        for (p in chunks(9, ByteArray(20) { 1 })) last = asm.feed(p)
        assertEquals(BekenFrameAssembler.Outcome.Dropped, last)
    }

    @Test fun `runts and chunk number zero are ignored`() {
        val asm = BekenFrameAssembler()
        assertEquals(BekenFrameAssembler.Outcome.Building, asm.feed(byteArrayOf(), 0))
        assertEquals(BekenFrameAssembler.Outcome.Building, asm.feed(byteArrayOf(1, 0, 1, 4)))
        assertEquals(BekenFrameAssembler.Outcome.Building, asm.feed(byteArrayOf(1, 0, 0, 4, 9, 9)))
    }
}
