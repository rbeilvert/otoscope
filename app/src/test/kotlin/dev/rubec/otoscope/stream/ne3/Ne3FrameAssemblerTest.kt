package dev.rubec.otoscope.stream.ne3

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Regression tests for [Ne3FrameAssembler]. The wire format is documented in
 * that class's KDoc; the fixtures here match it byte-for-byte so any drift
 * in the LE reads / offsets / magic byte fails here before it ships.
 *
 * Scans always end with `FF D9` (EOI). The assembler rejects frames whose
 * stitched scan doesn't end that way — that's deliberate (see
 * `scan without EOI is Dropped`), so the fixtures must mirror what the
 * camera actually sends.
 */
class Ne3FrameAssemblerTest {

    @Test fun `single-chunk frame emits the scan bytes verbatim`() {
        val scan = scanOf(200)
        val dg = fragDatagram(frameId = 1L, chunkSeq = 0, chunkTotal = 1, payload = scan)

        val out = Ne3FrameAssembler().feed(dg, dg.size)
        val frame = assertIs<Ne3FrameAssembler.Outcome.Frame>(out)
        assertContentEquals(scan, frame.scan)
    }

    @Test fun `Q byte at packet offset 48 is lifted into Frame qualityLevel`() {
        // The vendor native lib reads byte 48 of each fragment as the active
        // Q level — this test pins the offset so a wire-format edit can't
        // silently shift it without failing CI.
        for (q in listOf(5, 10, 25, 50, 75, 100)) {
            val dg = fragDatagram(frameId = q.toLong(), chunkSeq = 0, chunkTotal = 1, payload = scanOf(20), q = q)
            val frame = assertIs<Ne3FrameAssembler.Outcome.Frame>(Ne3FrameAssembler().feed(dg, dg.size))
            assertEquals(q, frame.qualityLevel, "expected Q=$q lifted from packet offset 48")
        }
    }

    @Test fun `Q byte outside the vendor set reports qualityLevel = null`() {
        // A corrupt or shifted packet must fall back to the default header,
        // not silently load a wrong matrix. Pick a value that is NOT in
        // {5, 10, 25, 50, 75, 100} so the clamp kicks in.
        val dg = fragDatagram(frameId = 1L, chunkSeq = 0, chunkTotal = 1, payload = scanOf(20), q = 42)
        val frame = assertIs<Ne3FrameAssembler.Outcome.Frame>(Ne3FrameAssembler().feed(dg, dg.size))
        assertEquals(null, frame.qualityLevel)
    }

    @Test fun `multi-chunk frame takes Q from the first chunk only`() {
        // On the wire every fragment of a frame carries the same Q byte, but
        // the vendor lib only trusts it on fragment 0; we mirror that so a
        // corrupt late-chunk Q byte can't override an already-good read.
        val a = Ne3FrameAssembler()
        val d0 = fragDatagram(7L, chunkSeq = 0, chunkTotal = 2, payload = ByteArray(50), q = 25)
        val d1 = fragDatagram(7L, chunkSeq = 1, chunkTotal = 2, payload = scanOf(50), q = 99 /* ignored */)
        assertIs<Ne3FrameAssembler.Outcome.Building>(a.feed(d0, d0.size))
        val frame = assertIs<Ne3FrameAssembler.Outcome.Frame>(a.feed(d1, d1.size))
        assertEquals(25, frame.qualityLevel)
    }

    @Test fun `width and height are lifted from packet offsets 44 and 46`() {
        // The vendor hardware varies this per firmware (416x416 and 640x360
        // seen in the wild); pinning the offsets stops a wire-format drift
        // from silently feeding stale dimensions to the SOF0 patcher.
        val dg = fragDatagram(
            frameId = 1L, chunkSeq = 0, chunkTotal = 1,
            payload = scanOf(20), width = 416, height = 416,
        )
        val frame = assertIs<Ne3FrameAssembler.Outcome.Frame>(Ne3FrameAssembler().feed(dg, dg.size))
        assertEquals(416, frame.width)
        assertEquals(416, frame.height)
    }

    @Test fun `multi-chunk frame reassembles in seq order`() {
        val a = Ne3FrameAssembler()
        val part0 = ByteArray(100) { 0xAA.toByte() }
        val part1 = ByteArray(100) { 0xBB.toByte() }
        val part2 = scanTail(56)  // ends with FF D9

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
        val part2 = scanTail(60)  // ends with FF D9

        val d0 = fragDatagram(3L, 0, 3, part0)
        val d1 = fragDatagram(3L, 1, 3, part1)
        val d2 = fragDatagram(3L, 2, 3, part2)

        a.feed(d2, d2.size)                // arrives first
        a.feed(d0, d0.size)                // then the head
        val frame = assertIs<Ne3FrameAssembler.Outcome.Frame>(a.feed(d1, d1.size))
        assertContentEquals(part0 + part1 + part2, frame.scan)
    }

    @Test fun `two frames interleaved land in separate ring slots and both complete`() {
        // This is THE regression test for the stitching bug a reporter saw
        // on NE3. Chunks arrive A0, B0, A1, B1 — if the assembler only kept
        // one slot, B0's arrival would evict A, and A1 arriving later would
        // in turn evict B. The 4-slot ring keeps both frames in flight so
        // each completes with its own chunks only.
        //
        // Frame IDs 1 and 2 map to slots 0 and 1 under the `(id-1) & 3` rule.
        val a = Ne3FrameAssembler()
        val a0 = fragDatagram(1L, chunkSeq = 0, chunkTotal = 2, payload = ByteArray(10) { 0xA0.toByte() })
        val b0 = fragDatagram(2L, chunkSeq = 0, chunkTotal = 2, payload = ByteArray(10) { 0xB0.toByte() })
        val a1 = fragDatagram(1L, chunkSeq = 1, chunkTotal = 2, payload = scanTail(10) { 0xA1.toByte() })
        val b1 = fragDatagram(2L, chunkSeq = 1, chunkTotal = 2, payload = scanTail(10) { 0xB1.toByte() })

        assertIs<Ne3FrameAssembler.Outcome.Building>(a.feed(a0, a0.size))
        assertIs<Ne3FrameAssembler.Outcome.Building>(a.feed(b0, b0.size))
        val fa = assertIs<Ne3FrameAssembler.Outcome.Frame>(a.feed(a1, a1.size))
        val fb = assertIs<Ne3FrameAssembler.Outcome.Frame>(a.feed(b1, b1.size))

        // Frame A: 10 bytes of 0xA0 + 10 bytes of 0xA1 (ending FF D9). No 0xB bytes anywhere.
        assertEquals(20, fa.scan.size)
        assertEquals(0xA0.toByte(), fa.scan[0])
        assertEquals(0xA1.toByte(), fa.scan[10])
        // Frame B is symmetric.
        assertEquals(20, fb.scan.size)
        assertEquals(0xB0.toByte(), fb.scan[0])
        assertEquals(0xB1.toByte(), fb.scan[10])
    }

    @Test fun `a stray chunk whose seq exceeds the slot's expected count is Invalid`() {
        // Guards against the subtle case where a corrupt packet (bit-flipped
        // chunk_total) claims a chunk_seq that fits its own chunkTotal but
        // not the slot's. Accepting it would fill a missing slot in the
        // completion check and trick the assembler into emitting a mix of
        // real and bogus chunks.
        val a = Ne3FrameAssembler()
        // Legit first chunk declares chunk_total = 3 → slot.expected = 3.
        val c0 = fragDatagram(1L, chunkSeq = 0, chunkTotal = 3, payload = ByteArray(20))
        assertIs<Ne3FrameAssembler.Outcome.Building>(a.feed(c0, c0.size))
        // Corrupt stray: same frame_id, but chunkTotal=8 and chunkSeq=5.
        // seq (5) >= slot.expected (3) → rejected.
        val stray = fragDatagram(1L, chunkSeq = 5, chunkTotal = 8, payload = ByteArray(20))
        assertIs<Ne3FrameAssembler.Outcome.Invalid>(a.feed(stray, stray.size))
    }

    @Test fun `a complete-looking frame missing an inner chunk stays Building`() {
        // Chunks {0, 2} received, chunk_total=3, size=2. Legacy code checked
        // `size < expected`, which this clearly satisfies; the newer strict
        // check also demands lastKey == expected-1, which (0,2) does not.
        // Covers the case where someone later weakens the check back to size.
        val a = Ne3FrameAssembler()
        val c0 = fragDatagram(1L, chunkSeq = 0, chunkTotal = 3, payload = ByteArray(20))
        val c2 = fragDatagram(1L, chunkSeq = 2, chunkTotal = 3, payload = scanTail(20))
        assertIs<Ne3FrameAssembler.Outcome.Building>(a.feed(c0, c0.size))
        assertIs<Ne3FrameAssembler.Outcome.Building>(a.feed(c2, c2.size))
    }

    @Test fun `scan without a trailing EOI marker is Dropped`() {
        // The camera's scans end with FF D9. A completed frame without that
        // tail is a sign of stitching or truncation; drop rather than feed a
        // mis-assembled frame to the decoder.
        val payload = ByteArray(50) { 0x42 }  // deliberately no FF D9
        val dg = fragDatagram(frameId = 1L, chunkSeq = 0, chunkTotal = 1, payload = payload)
        assertIs<Ne3FrameAssembler.Outcome.Dropped>(Ne3FrameAssembler().feed(dg, dg.size))
    }

    @Test fun `a new frame_id in the same slot drops the previous in-flight frame`() {
        // Slot 0 holds in-flight frame 1. Frame 5 arrives — also slot 0
        // under (5-1)&3 = 0. The in-flight frame is evicted; the new one
        // starts fresh.
        val a = Ne3FrameAssembler()
        val d0 = fragDatagram(frameId = 1L, chunkSeq = 0, chunkTotal = 3, payload = ByteArray(50))
        a.feed(d0, d0.size)
        val d5 = fragDatagram(frameId = 5L, chunkSeq = 0, chunkTotal = 1, payload = scanTail(80))
        val out = a.feed(d5, d5.size)
        val frame = assertIs<Ne3FrameAssembler.Outcome.Frame>(out)
        assertEquals(80, frame.scan.size)
    }

    @Test fun `wrong magic byte is Invalid`() {
        val dg = fragDatagram(1L, 0, 1, scanOf(20))
        dg[0] = 0x00 // magic must be 0x93
        assertIs<Ne3FrameAssembler.Outcome.Invalid>(Ne3FrameAssembler().feed(dg, dg.size))
    }

    @Test fun `mcu-ctl surfaces as CtlMsg with seq + payload past the 12-byte header`() {
        // Wire layout mirrors the vendor's handle_mcu_msg_ctlmsg: magic,
        // msg_type, u16 total-length, u32 seq, u16 payload-length, 2
        // reserved, then the sensor-frame payload. Pin the 12-byte split
        // so a rewrite of the assembler can't silently feed stale metadata
        // into the sensor parser.
        val payload = byteArrayOf(0xFF.toByte(), 0x04, 0x56, 0x00, 0x90.toByte(), 0x00)
        val dg = ByteArray(Ne3FrameAssembler.CTLMSG_HEADER_SIZE + payload.size)
        dg[0] = Ne3FrameAssembler.PACKET_MAGIC
        dg[1] = Ne3FrameAssembler.MSG_TYPE_MCU_CTL.toByte()
        writeU16LE(dg, 2, dg.size)
        writeU32LE(dg, 4, 0x12345678.toInt()) // seq
        writeU16LE(dg, 8, payload.size)
        // 10..11 reserved
        System.arraycopy(payload, 0, dg, Ne3FrameAssembler.CTLMSG_HEADER_SIZE, payload.size)
        val out = assertIs<Ne3FrameAssembler.Outcome.CtlMsg>(Ne3FrameAssembler().feed(dg, dg.size))
        assertEquals(0x12345678L, out.seq)
        assertContentEquals(payload, out.payload)
    }

    @Test fun `other non-fragment msg_types surface as Other with the 8-byte-stripped payload`() {
        // ACK (msg_type=2) and queryinfo-resp (msg_type=8) have no
        // decoded wire layout yet; the assembler just strips the shared
        // 8-byte prelude and hands back the rest.
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
        val dg = fragDatagram(1L, chunkSeq = 3, chunkTotal = 3, payload = scanOf(10))
        assertIs<Ne3FrameAssembler.Outcome.Invalid>(Ne3FrameAssembler().feed(dg, dg.size))
    }

    @Test fun `chunk_total of zero is Invalid`() {
        val dg = fragDatagram(1L, chunkSeq = 0, chunkTotal = 0, payload = scanOf(10))
        assertIs<Ne3FrameAssembler.Outcome.Invalid>(Ne3FrameAssembler().feed(dg, dg.size))
    }

    // ---- helpers -----------------------------------------------------------

    /** A scan payload of exactly [size] bytes, ending with the mandatory
     *  `FF D9` EOI marker. Content before the marker is a simple ramp so
     *  content-based assertions are deterministic. */
    private fun scanOf(size: Int): ByteArray {
        require(size >= 2) { "scan must be large enough to hold FF D9" }
        val b = ByteArray(size) { (it and 0xff).toByte() }
        b[size - 2] = 0xFF.toByte()
        b[size - 1] = 0xD9.toByte()
        return b
    }

    /** The last fragment of a multi-chunk scan: [size] bytes of [fill]
     *  followed by `FF D9`. The caller decides what to fill with (useful
     *  when assembling specific scans with content assertions). */
    private fun scanTail(size: Int, fill: (Int) -> Byte = { 0 }): ByteArray {
        require(size >= 2) { "tail must be large enough to hold FF D9" }
        val b = ByteArray(size) { fill(it) }
        b[size - 2] = 0xFF.toByte()
        b[size - 1] = 0xD9.toByte()
        return b
    }

    /** Build one NE3-format fragment datagram: 56-byte header + payload.
     *  Defaults mirror the vendor's most common encoder settings so the
     *  pre-existing tests stay meaningful without naming every field. */
    private fun fragDatagram(
        frameId: Long,
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
        // offset 4: reserved u32
        writeU64LE(dg, 8, frameId)
        // offsets 16..31: reserved u64s (left zero)
        writeU32LE(dg, 32, chunkSeq)
        writeU32LE(dg, 36, chunkTotal)
        // offsets 40..43: reserved
        writeU16LE(dg, 44, width)
        writeU16LE(dg, 46, height)
        dg[48] = (q and 0xff).toByte()
        // offsets 49..55: reserved
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
