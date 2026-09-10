package dev.rubec.otoscope.stream.ne3

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Regression tests for [Ne3FrameAssembler]. The wire format is documented in
 * that class's KDoc; the fixtures here match it byte-for-byte so any drift
 * in the LE reads / offsets / magic byte fails here before it ships.
 */
class Ne3FrameAssemblerTest {

    @Test fun `single-chunk frame emits the scan bytes verbatim`() {
        val scan = ByteArray(200) { (it and 0xff).toByte() }
        val dg = fragDatagram(frameId = 1L, chunkSeq = 0, chunkTotal = 1, payload = scan)

        val out = Ne3FrameAssembler().feed(dg, dg.size)
        val frame = assertIs<Ne3FrameAssembler.Outcome.Frame>(out)
        assertContentEquals(scan, frame.scan)
    }

    @Test fun `multi-chunk frame reassembles in seq order`() {
        val a = Ne3FrameAssembler()
        val part0 = ByteArray(100) { 0xAA.toByte() }
        val part1 = ByteArray(100) { 0xBB.toByte() }
        val part2 = ByteArray(56)  { 0xCC.toByte() }

        val d0 = fragDatagram(2L, 0, 3, part0)
        val d1 = fragDatagram(2L, 1, 3, part1)
        val d2 = fragDatagram(2L, 2, 3, part2)

        assertIs<Ne3FrameAssembler.Outcome.Building>(a.feed(d0, d0.size))
        assertIs<Ne3FrameAssembler.Outcome.Building>(a.feed(d1, d1.size))
        val frame = assertIs<Ne3FrameAssembler.Outcome.Frame>(a.feed(d2, d2.size))
        assertContentEquals(part0 + part1 + part2, frame.scan)
    }

    @Test fun `out-of-order chunks within a frame are re-sorted by seq`() {
        // UDP reordering is real; the TreeMap key on chunk_seq means the final
        // scan concatenation is always in the right order regardless of arrival.
        val a = Ne3FrameAssembler()
        val part0 = ByteArray(100) { 0x01 }
        val part1 = ByteArray(100) { 0x02 }
        val part2 = ByteArray(60)  { 0x03 }

        val d0 = fragDatagram(3L, 0, 3, part0)
        val d1 = fragDatagram(3L, 1, 3, part1)
        val d2 = fragDatagram(3L, 2, 3, part2)

        a.feed(d2, d2.size)                // arrives first
        a.feed(d0, d0.size)                // then the head
        val frame = assertIs<Ne3FrameAssembler.Outcome.Frame>(a.feed(d1, d1.size))
        assertContentEquals(part0 + part1 + part2, frame.scan)
    }

    @Test fun `a new frame_id drops the previous in-flight frame`() {
        val a = Ne3FrameAssembler()
        val d0 = fragDatagram(frameId = 5L, chunkSeq = 0, chunkTotal = 3, payload = ByteArray(50))
        a.feed(d0, d0.size)
        // Frame 6 arrives before frame 5 completes — the previous scan is
        // abandoned. The outcome is `Dropped` because we lost a frame in
        // flight, then the new frame starts.
        val d6 = fragDatagram(frameId = 6L, chunkSeq = 0, chunkTotal = 1, payload = ByteArray(80) { 0x77 })
        val out = a.feed(d6, d6.size)
        val frame = assertIs<Ne3FrameAssembler.Outcome.Frame>(out)
        assertEquals(80, frame.scan.size)
    }

    @Test fun `wrong magic byte is Invalid`() {
        val dg = fragDatagram(1L, 0, 1, ByteArray(20))
        dg[0] = 0x00 // magic must be 0x93
        assertIs<Ne3FrameAssembler.Outcome.Invalid>(Ne3FrameAssembler().feed(dg, dg.size))
    }

    @Test fun `non-fragment msg_type surfaces as Other with the payload`() {
        // A MCU control message: msg_type=4 with an 8-byte common header
        // + a short payload. The assembler should surface it (not drop it)
        // so the caller can log the payload for future decoding.
        val payload = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val dg = ByteArray(Ne3FrameAssembler.COMMON_HEADER_SIZE + payload.size)
        dg[0] = Ne3FrameAssembler.PACKET_MAGIC
        dg[1] = Ne3FrameAssembler.MSG_TYPE_MCU_CTL.toByte()
        System.arraycopy(payload, 0, dg, Ne3FrameAssembler.COMMON_HEADER_SIZE, payload.size)
        val out = assertIs<Ne3FrameAssembler.Outcome.Other>(Ne3FrameAssembler().feed(dg, dg.size))
        assertEquals(Ne3FrameAssembler.MSG_TYPE_MCU_CTL, out.msgType)
        assertContentEquals(payload, out.payload)
    }

    @Test fun `packet shorter than the 8-byte common header is Invalid`() {
        val tiny = ByteArray(4) { 0x93.toByte() }
        assertIs<Ne3FrameAssembler.Outcome.Invalid>(Ne3FrameAssembler().feed(tiny, tiny.size))
    }

    @Test fun `fragment shorter than the 56-byte fragment header is Invalid`() {
        val dg = ByteArray(30) { 0 }
        dg[0] = Ne3FrameAssembler.PACKET_MAGIC
        dg[1] = Ne3FrameAssembler.MSG_TYPE_FRAG.toByte()
        assertIs<Ne3FrameAssembler.Outcome.Invalid>(Ne3FrameAssembler().feed(dg, dg.size))
    }

    @Test fun `chunk_seq greater than or equal to chunk_total is Invalid`() {
        val dg = fragDatagram(1L, chunkSeq = 3, chunkTotal = 3, payload = ByteArray(10))
        assertIs<Ne3FrameAssembler.Outcome.Invalid>(Ne3FrameAssembler().feed(dg, dg.size))
    }

    @Test fun `chunk_total of zero is Invalid`() {
        val dg = fragDatagram(1L, chunkSeq = 0, chunkTotal = 0, payload = ByteArray(10))
        assertIs<Ne3FrameAssembler.Outcome.Invalid>(Ne3FrameAssembler().feed(dg, dg.size))
    }

    // ---- helpers -----------------------------------------------------------

    /** Build one NE3-format fragment datagram: 56-byte header + payload. */
    private fun fragDatagram(
        frameId: Long,
        chunkSeq: Int,
        chunkTotal: Int,
        payload: ByteArray,
    ): ByteArray {
        val dg = ByteArray(Ne3FrameAssembler.FRAG_HEADER_SIZE + payload.size)
        dg[0] = Ne3FrameAssembler.PACKET_MAGIC
        dg[1] = Ne3FrameAssembler.MSG_TYPE_FRAG.toByte()
        writeU16LE(dg, 2, dg.size)
        // offset 4: reserved u32
        writeU64LE(dg, 8, frameId)
        // offsets 16..31: reserved u64s (left zero)
        writeU32LE(dg, 32, chunkSeq)
        writeU32LE(dg, 36, chunkTotal)
        // offsets 40..55: reserved
        System.arraycopy(payload, 0, dg, Ne3FrameAssembler.FRAG_HEADER_SIZE, payload.size)
        return dg
    }

    private fun writeU16LE(b: ByteArray, off: Int, value: Int) {
        b[off] = (value and 0xff).toByte()
        b[off + 1] = ((value ushr 8) and 0xff).toByte()
    }

    private fun writeU32LE(b: ByteArray, off: Int, value: Int) {
        b[off] = (value and 0xff).toByte()
        b[off + 1] = ((value ushr 8) and 0xff).toByte()
        b[off + 2] = ((value ushr 16) and 0xff).toByte()
        b[off + 3] = ((value ushr 24) and 0xff).toByte()
    }

    private fun writeU64LE(b: ByteArray, off: Int, value: Long) {
        for (i in 0 until 8) b[off + i] = ((value ushr (i * 8)) and 0xff).toByte()
    }
}
