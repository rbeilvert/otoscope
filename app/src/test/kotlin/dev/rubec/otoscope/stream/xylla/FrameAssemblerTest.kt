package dev.rubec.otoscope.stream.xylla

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Regression tests for [FrameAssembler]. The wire format is documented in
 * that class's KDoc; the fixtures here match it byte-for-byte so any drift
 * (e.g. someone flips a little-endian read to big-endian) shows up here
 * before it ships.
 */
class FrameAssemblerTest {

    @Test fun `single-chunk MJPEG frame emits with correct metadata`() {
        val payload = ByteArray(200) { it.toByte() }
        val chunk = chunk(
            format = FrameAssembler.FORMAT_MJPEG,
            width = 640,
            height = 480,
            totalSize = payload.size,
            chunkSeq = 0,
            accelerometer = 0x12345678,
            payload = payload,
        )
        val out = FrameAssembler().feed(chunk, chunk.size)
        val frame = assertIs<FrameAssembler.Outcome.Frame>(out)
        assertEquals(FrameAssembler.FORMAT_MJPEG, frame.format)
        assertEquals(640, frame.width)
        assertEquals(480, frame.height)
        assertEquals(0x12345678, frame.accelerometer)
        assertContentEquals(payload, frame.data)
    }

    @Test fun `multi-chunk frame reassembles in sequence`() {
        val a = FrameAssembler()
        val part1 = ByteArray(100) { 0xAA.toByte() }
        val part2 = ByteArray(100) { 0xBB.toByte() }
        val part3 = ByteArray(56) { 0xCC.toByte() }
        val total = part1.size + part2.size + part3.size

        assertIs<FrameAssembler.Outcome.Building>(
            a.feed(chunk(1, 640, 480, total, 0, 0, part1).let { it }, /* len= */ 24 + part1.size)
        )
        assertIs<FrameAssembler.Outcome.Building>(
            a.feed(chunk(1, 640, 480, total, 1, 0, part2), 24 + part2.size)
        )
        val out = a.feed(chunk(1, 640, 480, total, 2, 0, part3), 24 + part3.size)
        val frame = assertIs<FrameAssembler.Outcome.Frame>(out)
        assertEquals(total, frame.data.size)
        assertContentEquals(part1 + part2 + part3, frame.data)
    }

    @Test fun `chunk with wrong magic byte is rejected as Invalid`() {
        val bad = chunk(1, 320, 240, 100, 0, 0, ByteArray(100))
        bad[0] = 0x00 // magic must be 0x66
        assertIs<FrameAssembler.Outcome.Invalid>(FrameAssembler().feed(bad, bad.size))
    }

    @Test fun `packet shorter than the 24-byte header is Invalid`() {
        val tiny = ByteArray(10) { 0x66 }
        assertIs<FrameAssembler.Outcome.Invalid>(FrameAssembler().feed(tiny, tiny.size))
    }

    @Test fun `chunk_len larger than the datagram is Invalid`() {
        val payload = ByteArray(100)
        val chunk = chunk(1, 320, 240, 500, 0, 0, payload)
        // Claim chunk_len = 999 but only 100 bytes of payload actually arrived.
        writeU16LE(chunk, off = 14, value = 999)
        assertIs<FrameAssembler.Outcome.Invalid>(FrameAssembler().feed(chunk, chunk.size))
    }

    @Test fun `total_size == 0 or oversized frame is Invalid`() {
        val payload = ByteArray(100)
        val zeroSize = chunk(1, 320, 240, 0, 0, 0, payload)
        assertIs<FrameAssembler.Outcome.Invalid>(FrameAssembler().feed(zeroSize, zeroSize.size))

        val huge = chunk(1, 320, 240, 8 * 1024 * 1024, 0, 0, payload)
        assertIs<FrameAssembler.Outcome.Invalid>(FrameAssembler().feed(huge, huge.size))
    }

    @Test fun `missing chunk in the middle drops the frame`() {
        val a = FrameAssembler()
        val total = 300
        val p0 = ByteArray(100) { 1 }
        val p2 = ByteArray(100) { 3 }
        a.feed(chunk(1, 640, 480, total, 0, 0, p0), 24 + p0.size)
        // Skip chunk_seq=1 entirely.
        assertIs<FrameAssembler.Outcome.Dropped>(
            a.feed(chunk(1, 640, 480, total, 2, 0, p2), 24 + p2.size)
        )
    }

    @Test fun `new frame with chunk_seq=0 starts fresh after prior partial`() {
        val a = FrameAssembler()
        val partial = ByteArray(50) { 1 }
        a.feed(chunk(1, 640, 480, 200, 0, 0, partial), 24 + partial.size)
        // Now a completely new frame arrives (single chunk, chunk_seq=0).
        val fresh = ByteArray(80) { 2 }
        val out = a.feed(chunk(1, 640, 480, fresh.size, 0, 0, fresh), 24 + fresh.size)
        val frame = assertNotNull(out as? FrameAssembler.Outcome.Frame)
        assertContentEquals(fresh, frame.data)
    }

    // ---- helpers -----------------------------------------------------------

    /** Build one Xylla-format UDP packet: 24-byte header + payload. Matches
     *  the field layout documented in FrameAssembler.kt. */
    private fun chunk(
        format: Int,
        width: Int,
        height: Int,
        totalSize: Int,
        chunkSeq: Int,
        accelerometer: Int,
        payload: ByteArray,
    ): ByteArray {
        val chunkLen = payload.size
        val packet = ByteArray(24 + chunkLen)
        packet[0] = 0x66            // magic
        packet[1] = 0x01            // opaque type
        packet[2] = format.toByte() // format
        packet[3] = 0x00            // flag
        writeU32LE(packet, 4, totalSize)
        writeU16LE(packet, 8, width)
        writeU16LE(packet, 10, height)
        writeU16LE(packet, 12, chunkSeq)
        writeU16LE(packet, 14, chunkLen)
        writeU32LE(packet, 16, accelerometer)
        // 20..23 reserved (0)
        System.arraycopy(payload, 0, packet, 24, payload.size)
        return packet
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
}
