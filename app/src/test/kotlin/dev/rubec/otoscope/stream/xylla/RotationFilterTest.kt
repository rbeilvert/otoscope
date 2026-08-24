package dev.rubec.otoscope.stream.xylla

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [RotationFilter] does two things worth pinning: it decodes the 3×10-bit
 * packed accelerometer into an angle, and it holds the previous angle while
 * the camera sits still (std-dev ≤ 3 on every axis over the sliding window).
 * These tests fence both behaviours.
 */
class RotationFilterTest {

    @Test fun `holds zero until the sliding window is full`() {
        val f = RotationFilter()
        // A steady-state reading below the WINDOW threshold must not produce
        // an angle yet — the filter needs 20 samples before it decides
        // whether the camera is moving.
        val steady = packAxes(x = 100, y = 0, z = 300)
        for (i in 0 until 19) {
            assertEquals(0f, f.update(steady))
        }
    }

    @Test fun `motionless input keeps last angle even after window fills`() {
        val f = RotationFilter()
        // First fill the window with completely identical samples. std-dev
        // stays at 0 on every axis, so the "still" branch fires and the
        // filter refuses to produce a new angle.
        val steady = packAxes(x = 200, y = 100, z = 400)
        repeat(30) { f.update(steady) }
        assertEquals(0f, f.update(steady))
    }

    @Test fun `applies the 180 degree mount offset for zero-Y camera-flat pose`() {
        val f = RotationFilter()
        // Warm up with a moving baseline so the "still" gate opens, then feed
        // the pose we want to measure. Camera flat, lens up: Y ≈ 0, Z > 0.
        // atan(0 / Z) = 0, no quadrant flips, +π offset ⇒ 180°.
        val moving = intArrayOf(50, 100, 150, 200, 250, 300, 350, 400, 450, 500,
            50, 100, 150, 200, 250, 300, 350, 400, 450, 500)
        for (raw in moving) f.update(packAxes(x = raw, y = raw, z = 400))
        val angle = f.update(packAxes(x = 200, y = 0, z = 400))
        assertTrue(abs(angle - 180f) < 0.5f, "expected ~180°, got $angle")
    }

    @Test fun `holds the last angle when only one axis moves too little`() {
        val f = RotationFilter()
        val steady = packAxes(x = 100, y = 100, z = 400)
        repeat(30) { f.update(steady) }
        // A tiny wobble on one axis (< std-dev 3) shouldn't unstick the
        // filter — the "moving?" gate ORs all three axes.
        for (delta in listOf(1, -1, 0, 1, -1, 0)) {
            val out = f.update(packAxes(x = 100 + delta, y = 100, z = 400))
            assertEquals(0f, out)
        }
    }

    // ---- helpers -----------------------------------------------------------

    /** Pack three 10-bit axes into a single u32 the way the wire format does. */
    private fun packAxes(x: Int, y: Int, z: Int): Int =
        ((x and 0x3FF) shl 20) or ((y and 0x3FF) shl 10) or (z and 0x3FF)
}
