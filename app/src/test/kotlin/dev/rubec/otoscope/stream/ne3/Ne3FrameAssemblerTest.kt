package dev.rubec.otoscope.stream.ne3

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Regression tests for [Ne3FrameAssembler]. Fixtures match the wire format
 * byte-for-byte so any drift in offsets, LE reads or magic bytes fails here
 * before it ships.
 */
class Ne3FrameAssemblerTest {

    @Test fun `single-chunk frame emits the scan bytes verbatim`() {
        val scan = ByteArray(200) { (it and 0xff).toByte() }
        val dg = fragDatagram(imgNumber = 1L, chunkSeq = 0, chunkTotal = 1, payload = scan)

        val out = Ne3FrameAssembler().feed(dg, dg.size)
        val frame = assertIs<Ne3FrameAssembler.Outcome.Frame>(out)
        assertContentEquals(scan, frame.scan)
        assertEquals(1L, frame.imgNumber)
    }

    @Test fun `Q byte at packet offset 48 is lifted into Frame qualityLevel`() {
        for (q in listOf(5, 10, 25, 50, 75, 100)) {
            val dg = fragDatagram(imgNumber = q.toLong(), chunkSeq = 0, chunkTotal = 1, payload = ByteArray(20), q = q)
            val frame = assertIs<Ne3FrameAssembler.Outcome.Frame>(Ne3FrameAssembler().feed(dg, dg.size))
            assertEquals(q, frame.qualityLevel, "expected Q=$q lifted from packet offset 48")
        }
    }

    @Test fun `Q byte outside the vendor set reports qualityLevel = null`() {
        val dg = fragDatagram(imgNumber = 1L, chunkSeq = 0, chunkTotal = 1, payload = ByteArray(20), q = 42)
        val frame = assertIs<Ne3FrameAssembler.Outcome.Frame>(Ne3FrameAssembler().feed(dg, dg.size))
        assertEquals(null, frame.qualityLevel)
    }

    @Test fun `width and height are lifted from packet offsets 44 and 46`() {
        val dg = fragDatagram(
            imgNumber = 1L, chunkSeq = 0, chunkTotal = 1,
            payload = ByteArray(20), width = 416, height = 416,
        )
        val frame = assertIs<Ne3FrameAssembler.Outcome.Frame>(Ne3FrameAssembler().feed(dg, dg.size))
        assertEquals(416, frame.width)
        assertEquals(416, frame.height)
    }

    @Test fun `multi-chunk frame reassembles in seq order`() {
        val a = Ne3FrameAssembler()
        val part0 = ByteArray(1024) { 0xAA.toByte() }
        val part1 = ByteArray(1024) { 0xBB.toByte() }
        val part2 = ByteArray(500)  { 0xCC.toByte() }

        val d0 = fragDatagram(2L, 0, 3, part0)
        val d1 = fragDatagram(2L, 1, 3, part1)
        val d2 = fragDatagram(2L, 2, 3, part2)

        assertIs<Ne3FrameAssembler.Outcome.Building>(a.feed(d0, d0.size))
        assertIs<Ne3FrameAssembler.Outcome.Building>(a.feed(d1, d1.size))
        val frame = assertIs<Ne3FrameAssembler.Outcome.Frame>(a.feed(d2, d2.size))
        assertContentEquals(part0 + part1 + part2, frame.scan)
    }

    @Test fun `chunk payload is capped at 1024 bytes and the ASCII rotation suffix is parsed`() {
        // Chunk 0 payload is `1024 scan bytes + 11 ASCII rotation bytes`.
        // Only the first 1024 bytes belong in the JPEG scan — feeding the
        // rotation bytes to the decoder produces visible stitching artefacts.
        val a = Ne3FrameAssembler()
        val oversize = ByteArray(1035).apply {
            for (i in 0 until 1024) this[i] = 0xDD.toByte()
            val rot = "+0272+-0800".toByteArray(Charsets.US_ASCII)
            System.arraycopy(rot, 0, this, 1024, rot.size)
        }
        val d0 = fragDatagram(5L, 0, 2, oversize)
        val d1 = fragDatagram(5L, 1, 2, ByteArray(100) { 0xEE.toByte() })

        assertIs<Ne3FrameAssembler.Outcome.Building>(a.feed(d0, d0.size))
        val frame = assertIs<Ne3FrameAssembler.Outcome.Frame>(a.feed(d1, d1.size))
        assertEquals(1024 + 100, frame.scan.size)
        for (i in 1020 until 1024) assertEquals(0xDD.toByte(), frame.scan[i])
        assertEquals(0xEE.toByte(), frame.scan[1024])
        assertEquals(272, frame.accelX)
        assertEquals(-800, frame.accelY)
    }

    @Test fun `chunk-0 without the ASCII suffix leaves accel fields null`() {
        val dg = fragDatagram(imgNumber = 1L, chunkSeq = 0, chunkTotal = 1, payload = ByteArray(100))
        val frame = assertIs<Ne3FrameAssembler.Outcome.Frame>(Ne3FrameAssembler().feed(dg, dg.size))
        assertNull(frame.accelX)
        assertNull(frame.accelY)
    }

    @Test fun `a higher img_number starts a fresh frame and reports Dropped if the previous had chunks`() {
        val a = Ne3FrameAssembler()
        val d0 = fragDatagram(imgNumber = 5L, chunkSeq = 0, chunkTotal = 3, payload = ByteArray(50))
        a.feed(d0, d0.size)
        val d6 = fragDatagram(imgNumber = 6L, chunkSeq = 0, chunkTotal = 1, payload = ByteArray(80))
        val out = a.feed(d6, d6.size)
        val frame = assertIs<Ne3FrameAssembler.Outcome.Frame>(out)
        assertEquals(6L, frame.imgNumber)
        assertEquals(80, frame.scan.size)
    }

    @Test fun `a lower img_number than the in-flight frame is discarded`() {
        val a = Ne3FrameAssembler()
        val d10 = fragDatagram(imgNumber = 10L, chunkSeq = 0, chunkTotal = 2, payload = ByteArray(50))
        a.feed(d10, d10.size)
        val d8 = fragDatagram(imgNumber = 8L, chunkSeq = 1, chunkTotal = 2, payload = ByteArray(50))
        assertIs<Ne3FrameAssembler.Outcome.Invalid>(a.feed(d8, d8.size))
    }

    @Test fun `out-of-order chunks within a frame are re-sorted by seq`() {
        val a = Ne3FrameAssembler()
        val part0 = ByteArray(100) { 0x01 }
        val part1 = ByteArray(100) { 0x02 }
        val part2 = ByteArray(60)  { 0x03 }

        val d0 = fragDatagram(3L, 0, 3, part0)
        val d1 = fragDatagram(3L, 1, 3, part1)
        val d2 = fragDatagram(3L, 2, 3, part2)

        assertIs<Ne3FrameAssembler.Outcome.Building>(a.feed(d0, d0.size))
        assertIs<Ne3FrameAssembler.Outcome.Building>(a.feed(d2, d2.size))  // out of order
        val frame = assertIs<Ne3FrameAssembler.Outcome.Frame>(a.feed(d1, d1.size))
        assertContentEquals(part0 + part1 + part2, frame.scan)
    }

    @Test fun `a complete-looking frame missing an inner chunk stays Building`() {
        val a = Ne3FrameAssembler()
        val c0 = fragDatagram(1L, chunkSeq = 0, chunkTotal = 3, payload = ByteArray(20))
        val c2 = fragDatagram(1L, chunkSeq = 2, chunkTotal = 3, payload = ByteArray(20))
        assertIs<Ne3FrameAssembler.Outcome.Building>(a.feed(c0, c0.size))
        assertIs<Ne3FrameAssembler.Outcome.Building>(a.feed(c2, c2.size))
    }

    @Test fun `wrong magic byte is Invalid`() {
        val dg = fragDatagram(1L, 0, 1, ByteArray(20))
        dg[0] = 0x00 // magic must be 0x93
        assertIs<Ne3FrameAssembler.Outcome.Invalid>(Ne3FrameAssembler().feed(dg, dg.size))
    }

    @Test fun `mcu-ctl surfaces as CtlMsg with seq + payload past the 12-byte header`() {
        val payload = byteArrayOf(0xFF.toByte(), 0x04, 0x56, 0x00, 0x90.toByte(), 0x00)
        val dg = ByteArray(Ne3FrameAssembler.CTLMSG_HEADER_SIZE + payload.size)
        dg[0] = Ne3FrameAssembler.PACKET_MAGIC
        dg[1] = Ne3FrameAssembler.MSG_TYPE_MCU_CTL.toByte()
        writeU16LE(dg, 2, dg.size)
        writeU32LE(dg, 4, 0x12345678.toInt())
        writeU16LE(dg, 8, payload.size)
        System.arraycopy(payload, 0, dg, Ne3FrameAssembler.CTLMSG_HEADER_SIZE, payload.size)
        val out = assertIs<Ne3FrameAssembler.Outcome.CtlMsg>(Ne3FrameAssembler().feed(dg, dg.size))
        assertEquals(0x12345678L, out.seq)
        assertContentEquals(payload, out.payload)
    }

    @Test fun `other non-fragment msg_types surface as Other with the 8-byte-stripped payload`() {
        val payload = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val dg = ByteArray(Ne3FrameAssembler.COMMON_HEADER_SIZE + payload.size)
        dg[0] = Ne3FrameAssembler.PACKET_MAGIC
        dg[1] = Ne3FrameAssembler.MSG_TYPE_ACK.toByte()
        System.arraycopy(payload, 0, dg, Ne3FrameAssembler.COMMON_HEADER_SIZE, payload.size)
        val out = assertIs<Ne3FrameAssembler.Outcome.Other>(Ne3FrameAssembler().feed(dg, dg.size))
        assertEquals(Ne3FrameAssembler.MSG_TYPE_ACK, out.msgType)
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

    private fun fragDatagram(
        imgNumber: Long,
        chunkSeq: Int,
        chunkTotal: Int,
        payload: ByteArray,
        q: Int = 75,
        width: Int = 640,
        height: Int = 360,
    ): ByteArray {
        val dg = ByteArray(Ne3FrameAssembler.FRAG_HEADER_SIZE + payload.size)
        dg[0] = Ne3FrameAssembler.PACKET_MAGIC
        dg[1] = Ne3FrameAssembler.MSG_TYPE_FRAG.toByte()
        writeU16LE(dg, 2, dg.size)
        writeU64LE(dg, 8, imgNumber)
        writeU64LE(dg, 16, imgNumber)
        writeU32LE(dg, 32, chunkSeq)
        writeU32LE(dg, 36, chunkTotal)
        writeU16LE(dg, 44, width)
        writeU16LE(dg, 46, height)
        dg[48] = (q and 0xff).toByte()
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
