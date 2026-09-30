package dev.rubec.otoscope.stream.i4season

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Regression tests for the i4season [FrameAssembler]: 16-byte (type 1) or
 * 28-byte (type 6) headers, a global packet sequence, a last-chunk flag and a
 * chunk count. No per-chunk index, so any gap drops the frame.
 */
class FrameAssemblerTest {

    private val jpegHead = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x11, 0x22)
    private val jpegTail = byteArrayOf(0x33, 0xFF.toByte(), 0xD9.toByte(), 0, 0, 0) // zero padding

    @Test fun `multi-chunk frame reassembles, trims padding and exposes metadata`() {
        val a = FrameAssembler()
        val g = 0x00908e7f
        assertIs<FrameAssembler.Outcome.Building>(a.feed(packet(seq = 10, frame = 3, last = false, count = 2, g = g, payload = jpegHead)))
        val frame = assertIs<FrameAssembler.Outcome.Frame>(
            a.feed(packet(seq = 11, frame = 3, last = true, count = 2, g = g, payload = jpegTail)),
        )
        assertContentEquals(jpegHead + jpegTail.copyOf(3), frame.data)
        assertEquals(g, frame.gsensor)
        assertEquals(640, frame.width)
        assertEquals(480, frame.height)
    }

    @Test fun `sequence wraps from 255 to 0 without breaking the frame`() {
        val a = FrameAssembler()
        a.feed(packet(seq = 255, frame = 1, last = false, count = 2, payload = jpegHead))
        assertIs<FrameAssembler.Outcome.Frame>(a.feed(packet(seq = 0, frame = 1, last = true, count = 2, payload = jpegTail)))
    }

    @Test fun `sequence gap drops the frame`() {
        val a = FrameAssembler()
        a.feed(packet(seq = 1, frame = 2, last = false, count = 3, payload = jpegHead))
        // seq 2 lost on the air.
        assertIs<FrameAssembler.Outcome.Dropped>(a.feed(packet(seq = 3, frame = 2, last = true, count = 3, payload = jpegTail)))
    }

    @Test fun `joining mid-frame drops it on the chunk count`() {
        val a = FrameAssembler()
        assertIs<FrameAssembler.Outcome.Dropped>(
            a.feed(packet(seq = 50, frame = 7, last = true, count = 14, payload = jpegTail)),
        )
    }

    @Test fun `new frame id while one is in flight reports the old one dropped`() {
        val a = FrameAssembler()
        a.feed(packet(seq = 1, frame = 4, last = false, count = 2, payload = jpegHead))
        assertIs<FrameAssembler.Outcome.Dropped>(a.feed(packet(seq = 2, frame = 5, last = false, count = 2, payload = jpegHead)))
        assertIs<FrameAssembler.Outcome.Frame>(a.feed(packet(seq = 3, frame = 5, last = true, count = 2, payload = jpegTail)))
    }

    @Test fun `payload that isn't a JPEG is dropped`() {
        val a = FrameAssembler()
        assertIs<FrameAssembler.Outcome.Dropped>(
            a.feed(packet(seq = 1, frame = 1, last = true, count = 1, payload = byteArrayOf(1, 2, 3, 4))),
        )
    }

    @Test fun `accelerometer sample is null when flags bit0 is clear`() {
        val frame = assertIs<FrameAssembler.Outcome.Frame>(
            FrameAssembler().feed(packet(seq = 1, frame = 1, last = true, count = 1, flags = 0, payload = jpegHead + jpegTail)),
        )
        assertNull(frame.gsensor)
    }

    @Test fun `type 6 packets skip the 28-byte header`() {
        val frame = assertIs<FrameAssembler.Outcome.Frame>(
            FrameAssembler().feed(packet(type = 6, seq = 1, frame = 1, last = true, count = 1, payload = jpegHead + jpegTail)),
        )
        assertContentEquals(jpegHead + jpegTail.copyOf(3), frame.data)
    }

    @Test fun `unknown packet types and short packets are Invalid`() {
        val a = FrameAssembler()
        val audio = ByteArray(40).also { it[0] = 5 }
        assertIs<FrameAssembler.Outcome.Invalid>(a.feed(audio, audio.size))
        val short = ByteArray(10).also { it[0] = 1 }
        assertIs<FrameAssembler.Outcome.Invalid>(a.feed(short, short.size))
    }

    private fun FrameAssembler.feed(p: ByteArray) = feed(p, p.size)

    private fun packet(
        type: Int = 1,
        seq: Int,
        frame: Int,
        last: Boolean,
        count: Int,
        flags: Int = 1,
        g: Int = 0,
        payload: ByteArray,
    ): ByteArray {
        val header = ByteArray(if (type == 6) FrameAssembler.HEADER_V6 else FrameAssembler.HEADER_V1)
        header[0] = type.toByte()
        header[1] = seq.toByte()
        header[2] = frame.toByte()
        header[3] = if (last) 1 else 0
        header[4] = count.toByte()
        header[5] = flags.toByte()
        for (i in 0 until 4) header[6 + i] = (g ushr (8 * i)).toByte()
        header[10] = 0x6E; header[11] = 0xB0.toByte()
        header[12] = 0x80.toByte(); header[13] = 0x02 // 640
        header[14] = 0xE0.toByte(); header[15] = 0x01 // 480
        return header + payload
    }
}
