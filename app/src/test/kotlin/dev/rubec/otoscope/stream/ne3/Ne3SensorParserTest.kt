package dev.rubec.otoscope.stream.ne3

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Wire-level tests for [Ne3SensorParser]. The parser is a pure state
 * machine, so these fixtures feed it synthesised bytes matching the
 * vendor's `0xFF`-delimited frame format and check the rotation / shutter
 * / deviceType outputs. If anyone shifts a field offset, flips the atan2
 * order, or tweaks the still-detection thresholds, the drift fails here.
 */
class Ne3SensorParserTest {

    @Test fun `three-axis frame reports device type and non-zero angle`() {
        val p = Ne3SensorParser()
        // dev_type=87 (0x57), Y=+1000, Z=0: atan2(1000, 0) = π/2 → 90° + 90° mount offset = 180°
        p.feed(threeAxisFrame(y = 1000, z = 0), 12)
        assertEquals(87, p.deviceType.value)
        assertAngleClose(180f, p.rotation.value)
    }

    @Test fun `two-axis frame uses atan2(X, Z) with no mount offset`() {
        val p = Ne3SensorParser()
        // dev_type=85 (0x55), X=+1000, Z=0: atan2(1000, 0) = π/2 → 90°
        p.feed(twoAxisFrame(x = 1000, z = 0), 12)
        assertEquals(85, p.deviceType.value)
        assertAngleClose(90f, p.rotation.value)
    }

    @Test fun `shutter frame emits the raw code to the shutter flow`() = runTest {
        val p = Ne3SensorParser()
        // dev_type=86 (0x56), code=0x90 (press). Collector must be at its
        // suspension point before we feed — a MutableSharedFlow with
        // replay=0 drops items with no live subscriber. UNDISPATCHED runs
        // the collector until its first suspend before continuing here.
        val codes = mutableListOf<Int>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            p.shutter.take(1).toList(codes)
        }
        p.feed(shutterFrame(code = 0x90), 6)
        advanceUntilIdle()
        job.cancel()
        assertEquals(listOf(0x90), codes)
    }

    @Test fun `still-detection latches to zero after a long run of near-zero samples`() {
        val p = Ne3SensorParser()
        // Feed 50 near-zero 3-axis samples (|Y| < 200 and |Z| < 200). The
        // vendor needs 40 consecutive such samples to latch; after that
        // the angle is forced to 0 regardless of what atan2 would return.
        repeat(50) { p.feed(threeAxisFrame(y = 10, z = 10), 12) }
        assertEquals(0f, p.rotation.value)
        assertTrue(p.isStillLatched)
    }

    @Test fun `still-latch releases when movement crosses the threshold`() {
        val p = Ne3SensorParser()
        // Latch stationary first.
        repeat(50) { p.feed(threeAxisFrame(y = 10, z = 10), 12) }
        assertTrue(p.isStillLatched)
        // Then feed 50 moving samples (|Y| >= 200): the latch should drop
        // and the reported angle should move off zero.
        repeat(50) { p.feed(threeAxisFrame(y = 300, z = 300), 12) }
        assertTrue(!p.isStillLatched)
        assertTrue(p.rotation.value != 0f, "expected non-zero angle after release, got ${p.rotation.value}")
    }

    @Test fun `unknown device types are skipped without an infinite loop`() {
        val p = Ne3SensorParser()
        // dev_type=0x99 is not in {85,86,87}. The parser should advance
        // past the frame and set deviceType, but not touch rotation.
        val buf = ByteArray(12)
        buf[0] = 0xFF.toByte()
        buf[1] = 0x08         // payload length
        buf[2] = 0x99.toByte() // unknown dev type
        p.feed(buf, buf.size)
        assertEquals(0x99, p.deviceType.value)
        assertEquals(0f, p.rotation.value)
    }

    @Test fun `payload beyond the end of the buffer is bounded-checked`() {
        val p = Ne3SensorParser()
        // Marker present, length byte says there are more bytes than we
        // actually hold. The parser must not read past len.
        val buf = ByteArray(5)
        buf[0] = 0xFF.toByte()
        buf[1] = 0x40 // claims 64-byte payload
        buf[2] = 0x57 // dev_type 87 (3-axis)
        p.feed(buf, buf.size)
        // No crash is the main assertion; deviceType stays null because
        // we never advanced past the length check.
        assertEquals(null, p.deviceType.value)
    }

    @Test fun `multiple frames in one feed call all parse`() {
        val p = Ne3SensorParser()
        // Concatenate a shutter-press frame and a 3-axis frame.
        val a = shutterFrame(code = 0x90)
        val b = threeAxisFrame(y = 0, z = 1000)
        val joined = ByteArray(a.size + b.size)
        System.arraycopy(a, 0, joined, 0, a.size)
        System.arraycopy(b, 0, joined, a.size, b.size)
        p.feed(joined, joined.size)
        assertEquals(87, p.deviceType.value) // the second (last) frame's dev type sticks
        assertAngleClose(90f, p.rotation.value) // atan2(0, 1000) = 0° + 90° offset = 90°
    }

    // ---- helpers -----------------------------------------------------------

    private fun threeAxisFrame(y: Int, z: Int, x: Int = 0): ByteArray {
        val buf = ByteArray(12)
        buf[0] = 0xFF.toByte()
        buf[1] = 0x0A // payload length (10 bytes after the length byte)
        buf[2] = 0x57 // dev_type = 87 (3-axis)
        buf[3] = 0x00 // flag
        writeS16BE(buf, 5, x)
        writeS16BE(buf, 7, y)
        writeS16BE(buf, 9, z)
        return buf
    }

    private fun twoAxisFrame(x: Int, z: Int): ByteArray {
        val buf = ByteArray(12)
        buf[0] = 0xFF.toByte()
        buf[1] = 0x0A
        buf[2] = 0x55 // dev_type = 85 (2-axis)
        buf[3] = 0x00
        writeS16BE(buf, 5, x)
        writeS16BE(buf, 9, z)
        return buf
    }

    private fun shutterFrame(code: Int): ByteArray {
        val buf = ByteArray(6)
        buf[0] = 0xFF.toByte()
        buf[1] = 0x04
        buf[2] = 0x56
        buf[3] = 0x00
        buf[4] = (code and 0xff).toByte()
        buf[5] = 0x00
        return buf
    }

    private fun writeS16BE(b: ByteArray, off: Int, v: Int) {
        b[off] = ((v shr 8) and 0xff).toByte()
        b[off + 1] = (v and 0xff).toByte()
    }

    private fun assertAngleClose(expected: Float, actual: Float) {
        val diff = ((actual - expected + 540f) % 360f) - 180f
        assertTrue(abs(diff) < 0.5f, "expected ~$expected°, got $actual°")
    }
}
