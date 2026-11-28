package dev.rubec.otoscope.stream.ne3

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers [Ne3SensorParser.setRotationFromInlineAccel]: `atan2(x, y) + 90°`
 * (the ne3-scope reference's `-angle - 90` counter-clockwise view rotation,
 * expressed clockwise) and the (0, 1024) axis-aligned special case.
 */
class Ne3SensorParserTest {

    @Test fun `rotation starts at zero before any reading arrives`() {
        assertEquals(0f, Ne3SensorParser().rotation.value)
    }

    @Test fun `axis-aligned (0, 1024) reading uses the hard-coded 90 degrees plus the mount offset`() {
        val p = Ne3SensorParser()
        p.setRotationFromInlineAccel(x = 0, y = 1024)
        assertEquals(180f, p.rotation.value)
    }

    @Test fun `general reading is atan2(x, y) plus 90 degrees`() {
        val p = Ne3SensorParser()
        // atan2(272, -800) ≈ 161.22° (the reporter's captured frame); the
        // reference turns that view 251.22° clockwise.
        p.setRotationFromInlineAccel(x = 272, y = -800)
        assertAngleClose(251.22f, p.rotation.value)
    }

    @Test fun `cardinal angles decode as expected`() {
        val p = Ne3SensorParser()
        // atan2(0, 1) = 0°
        p.setRotationFromInlineAccel(x = 0, y = 1)
        assertAngleClose(90f, p.rotation.value)
        // atan2(1, 0) = +90° (a non-special positive-x point, unlike (0, 1024))
        p.setRotationFromInlineAccel(x = 1, y = 0)
        assertAngleClose(180f, p.rotation.value)
        // atan2(0, -1) = 180°
        p.setRotationFromInlineAccel(x = 0, y = -1)
        assertAngleClose(270f, p.rotation.value)
        // atan2(-1, 0) = -90°
        p.setRotationFromInlineAccel(x = -1, y = 0)
        assertAngleClose(0f, p.rotation.value)
    }

    private fun assertAngleClose(expected: Float, actual: Float) {
        val diff = ((actual - expected + 540f) % 360f) - 180f
        assertTrue(abs(diff) < 0.5f, "expected ~$expected°, got $actual°")
    }
}
