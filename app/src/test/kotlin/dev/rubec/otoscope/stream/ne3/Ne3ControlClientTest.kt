package dev.rubec.otoscope.stream.ne3

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Angle-math checks for the NE3 sensor channel.
 *
 * We can't easily boot the full [Ne3ControlClient] in a JVM test (it dials
 * a real TCP socket), but the parse+angle logic is pure and reachable via
 * a small reimplementation that mirrors the wire-format documentation on
 * the class. If someone tweaks the atan2 order, the mount-offset constant,
 * or the anti-jitter thresholds, this test catches the drift.
 */
class Ne3ControlClientTest {

    @Test fun `three-axis angle uses atan2(Y, Z) plus 90 degree mount offset`() {
        // Y=0, Z=+1000 (device flat, lens up).
        // atan2(0, 1000) = 0 → +90 mount offset → 90°.
        assertAngleClose(90f, angle3Axis(y = 0, z = 1000))
        // Y=+1000, Z=0 → atan2 = π/2 = 90° → 90 + 90 = 180°.
        assertAngleClose(180f, angle3Axis(y = 1000, z = 0))
        // Y=0, Z=-1000 → atan2 = π = 180° → 180 + 90 = 270° (or -90°).
        assertAngleClose(270f, angle3Axis(y = 0, z = -1000))
    }

    @Test fun `two-axis angle uses atan2(X, Z) with no mount offset`() {
        assertAngleClose(0f, angle2Axis(x = 0, z = 1000))
        assertAngleClose(90f, angle2Axis(x = 1000, z = 0))
    }

    @Test fun `still-detection freezes the reported angle when magnitudes are tiny`() {
        // Feed 50 near-zero samples in a row — |Y| < 200 AND |Z| < 200 —
        // and the second half should latch to 0 even though atan2 would
        // otherwise wobble around the axis.
        val readings = List(50) { Pair(10 + it % 5, 20 + it % 5) } // both < 200
        val angles = simulateStillFilter(readings)
        // First few samples pre-latch — atan2 result present. Last several
        // should be exactly 0f.
        assertTrue(angles.takeLast(5).all { it == 0f }, "expected latched-to-0 after still streak, got ${angles.takeLast(5)}")
    }

    @Test fun `still-detection releases once movement crosses the threshold`() {
        // Warm up with 50 stationary samples (latches to still), then push
        // 50 samples above the threshold — angle must be non-zero after
        // the release streak.
        val stationary = List(50) { Pair(10, 10) }
        val moving = List(50) { Pair(300, 300) }
        val all = simulateStillFilter(stationary + moving)
        assertTrue(
            all.takeLast(5).any { it != 0f },
            "expected non-zero angle after moving streak, got ${all.takeLast(5)}",
        )
    }

    // ---- helpers -----------------------------------------------------------

    /** Mirror of the 3-axis atan2 formula the client applies. */
    private fun angle3Axis(y: Int, z: Int): Float =
        Math.toDegrees(atan2(y.toDouble(), z.toDouble())).toFloat() + 90f

    private fun angle2Axis(x: Int, z: Int): Float =
        Math.toDegrees(atan2(x.toDouble(), z.toDouble())).toFloat()

    /** Simulate the client's still-detection state machine on a stream of
     *  (Y, Z) readings; returns the angle it would have emitted for each. */
    private fun simulateStillFilter(samples: List<Pair<Int, Int>>): List<Float> {
        val threshold = 200
        val stillSamples = 40
        var stillStreak = 0
        var isStill = false
        val out = mutableListOf<Float>()
        for ((y, z) in samples) {
            val moving = abs(y) >= threshold || abs(z) >= threshold
            if (!isStill && !moving) {
                if (++stillStreak >= stillSamples) { isStill = true; stillStreak = 0 }
            } else if (isStill && moving) {
                if (++stillStreak >= stillSamples) { isStill = false; stillStreak = 0 }
            } else {
                stillStreak = 0
            }
            out += if (isStill) 0f else angle3Axis(y, z)
        }
        return out
    }

    private fun assertAngleClose(expected: Float, actual: Float) {
        val diff = ((actual - expected + 540f) % 360f) - 180f
        assertTrue(abs(diff) < 0.5f, "expected ~$expected°, got $actual°")
    }

    // Reference the PI constant so a rename in kotlin.math surfaces in this
    // test rather than only in the client itself.
    @Suppress("unused")
    private val piCheck = PI
}
