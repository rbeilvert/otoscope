package dev.rubec.otoscope.capture

import dev.rubec.otoscope.capture.CaptureGeometry.Affine
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The capture transform is the one piece of the recording path that can be
 * wrong without anything throwing — a bad sign on the rotation, a crop offset
 * applied in the wrong order, or an accidental mirror all produce a perfectly
 * valid JPEG that just doesn't show what the user saw. These tests pin it.
 */
class CaptureGeometryTest {

    private val tolerance = 1e-3f
    private val identity = Affine(1f, 0f, 0f, 0f, 1f, 0f)

    // ---- crop and encoder sizing -------------------------------------------

    @Test fun `crop side follows the shorter edge`() {
        assertEquals(480, CaptureGeometry.cropSide(640, 480))
        assertEquals(480, CaptureGeometry.cropSide(480, 640))
        assertEquals(500, CaptureGeometry.cropSide(500, 500))
    }

    @Test fun `video side is floored to a whole macroblock`() {
        // 640x480 and 1280x720 are already aligned; the odd sizes in between
        // must round DOWN, never up — an encoder configured larger than the
        // frames it's fed produces a green border.
        assertEquals(480, CaptureGeometry.videoSide(640, 480))
        assertEquals(720, CaptureGeometry.videoSide(1280, 720))
        assertEquals(480, CaptureGeometry.videoSide(640, 490))
        assertEquals(96, CaptureGeometry.videoSide(100, 100))
    }

    @Test fun `video side never degenerates to zero`() {
        // A nonsense frame would otherwise hand MediaCodec a 0x0 format and
        // throw on configure() instead of just recording badly.
        assertEquals(16, CaptureGeometry.videoSide(8, 8))
        assertEquals(16, CaptureGeometry.videoSide(0, 0))
    }

    // ---- the transform itself ----------------------------------------------

    @Test fun `unrotated transform centre-crops a landscape frame`() {
        // 640x480 -> 480: 80px comes off each side, nothing off the top.
        val t = CaptureGeometry.captureTransform(640, 480, side = 480, rotationDegrees = 0f)
        assertMaps(t, from = 80f to 0f, to = 0f to 0f)
        assertMaps(t, from = 560f to 480f, to = 480f to 480f)
        assertMaps(t, from = 320f to 240f, to = 240f to 240f)
    }

    @Test fun `transform scales the crop to the requested side`() {
        val t = CaptureGeometry.captureTransform(640, 480, side = 240, rotationDegrees = 0f)
        assertMaps(t, from = 80f to 0f, to = 0f to 0f)
        assertMaps(t, from = 560f to 480f, to = 240f to 240f)
        assertMaps(t, from = 320f to 240f, to = 120f to 120f)
    }

    @Test fun `positive angles turn the image clockwise`() {
        // Screen coordinates, y down: a quarter turn clockwise sends the
        // crop's top-left corner to the output's top-right. Getting this sign
        // wrong would make the auto-rotation fight the user's hand.
        val t = CaptureGeometry.captureTransform(640, 480, side = 480, rotationDegrees = 90f)
        assertMaps(t, from = 80f to 0f, to = 480f to 0f)
        assertMaps(t, from = 560f to 0f, to = 480f to 480f)
    }

    @Test fun `the crop centre is a fixed point at every angle`() {
        for (degrees in 0 until 360 step 5) {
            val t = CaptureGeometry.captureTransform(640, 480, side = 480, rotationDegrees = degrees.toFloat())
            assertMaps(t, from = 320f to 240f, to = 240f to 240f, hint = "at $degrees°")
        }
    }

    @Test fun `the circular mask is always covered by real pixels`() {
        // FrameComposer paints the mask with a CLAMP BitmapShader, which would
        // smear edge pixels if the rotated crop ever failed to cover the
        // inscribed circle. It can't: a square's inscribed circle is
        // rotation-invariant. Check it by walking the mask and pulling each
        // point back into source space.
        val side = 480
        val crop = CaptureGeometry.cropSide(640, 480).toFloat()
        val left = (640 - crop) / 2f
        for (degrees in 0 until 360 step 7) {
            val t = CaptureGeometry.captureTransform(640, 480, side, degrees.toFloat())
            val back = inverseOf(sourceWidth = 640, sourceHeight = 480, side = side, degrees = degrees.toFloat())
            // Sanity: `back` really is the inverse, which also exercises the
            // affine product used to build both.
            assertAffineEquals(identity, t * back, "inverse at $degrees°")

            for (step in 0 until 72) {
                val angle = Math.toRadians(step * 5.0)
                val radius = side / 2f
                val x = side / 2f + radius * Math.cos(angle).toFloat()
                val y = side / 2f + radius * Math.sin(angle).toFloat()
                val sx = back.mapX(x, y)
                val sy = back.mapY(x, y)
                assertTrue(
                    sx >= left - 0.5f && sx <= left + crop + 0.5f &&
                        sy >= -0.5f && sy <= crop + 0.5f,
                    "mask point ($x, $y) at $degrees° samples outside the crop at ($sx, $sy)",
                )
            }
        }
    }

    // ---- the mirror rule ---------------------------------------------------

    @Test fun `a capture is never mirrored`() {
        // A mirror flips the sign of the determinant. A pure rotate-and-scale
        // keeps it positive, at every angle and every scale.
        for (degrees in 0 until 360 step 15) {
            val t = CaptureGeometry.captureTransform(640, 480, side = 480, rotationDegrees = degrees.toFloat())
            val determinant = t.a * t.d - t.b * t.c
            assertTrue(determinant > 0f, "determinant $determinant at $degrees° implies a mirror")
        }
    }

    @Test fun `stripping the viewport's mirror leaves the capture rotation`() {
        // This is the identity FrameComposer relies on to ignore the flip
        // toggle. The viewport draws `mirror ∘ rotation(-angle)`; undoing the
        // mirror gives `rotation(+angle)`, which is what a capture applies —
        // so the same angle is correct whether the toggle is on or off.
        val centre = 240f
        val angle = 37f
        val mirror = mirrorX(centre)
        val viewport = mirror * CaptureGeometry.rotation(-angle, centre, centre)
        val capture = CaptureGeometry.rotation(angle, centre, centre)

        assertAffineEquals(capture, viewport * mirror, "viewport ∘ mirror")

        // Guard against a vacuous pass: the mirror has to actually do something.
        assertTrue(
            hypot(
                (mirror.mapX(0f, 0f) - 0f).toDouble(),
                (mirror.mapY(0f, 0f) - 0f).toDouble(),
            ) > 1.0,
            "mirrorX behaved like the identity",
        )
    }

    @Test fun `mirroring twice is the identity`() {
        val mirror = mirrorX(240f)
        assertAffineEquals(identity, mirror * mirror)
    }

    // ---- caption -----------------------------------------------------------

    @Test fun `caption sits at the bottom-left inside the frame`() {
        val caption = CaptureGeometry.caption(480, measuredWidth = 100f)
        assertEquals(24f, caption.x, tolerance)
        assertEquals(456f, caption.baselineY, tolerance)
        assertTrue(caption.baselineY < 480f, "baseline runs off the bottom edge")
        assertTrue(caption.x < 480f / 2f, "caption isn't on the left")
    }

    @Test fun `caption scales with the frame`() {
        assertEquals(23.04f, CaptureGeometry.captionTextSize(480), tolerance)
        assertEquals(34.56f, CaptureGeometry.captionTextSize(720), tolerance)
        // Margin and shadow scale too, so a 720² still isn't a 480² still with
        // a smaller-looking label.
        val small = CaptureGeometry.caption(480, measuredWidth = 10f)
        val large = CaptureGeometry.caption(720, measuredWidth = 10f)
        assertTrue(large.x > small.x)
        assertTrue(large.textSize > small.textSize)
        assertTrue(large.shadowRadius > small.shadowRadius)
        assertTrue(large.shadowOffsetY > small.shadowOffsetY)
    }

    @Test fun `a caption that fits keeps the base size`() {
        assertEquals(
            CaptureGeometry.captionTextSize(480),
            CaptureGeometry.caption(480, measuredWidth = 100f).textSize,
            tolerance,
        )
    }

    @Test fun `an over-wide caption shrinks to the available width`() {
        // available = 480 - 2×24 = 432, measured at double that, so it halves.
        assertEquals(
            CaptureGeometry.captionTextSize(480) / 2f,
            CaptureGeometry.caption(480, measuredWidth = 864f).textSize,
            tolerance,
        )
    }

    @Test fun `an unmeasurable caption keeps the base size`() {
        // Paint.measureText returns 0 for an empty string, and dividing by it
        // would hand Paint a NaN text size.
        val textSize = CaptureGeometry.caption(480, measuredWidth = 0f).textSize
        assertFalse(textSize.isNaN())
        assertEquals(CaptureGeometry.captionTextSize(480), textSize, tolerance)
    }

    @Test fun `caption size stays positive and never exceeds the base`() {
        val base = CaptureGeometry.captionTextSize(480)
        for (measured in listOf(0f, 1f, 100f, 431f, 432f, 433f, 1_000f, 10_000f)) {
            val textSize = CaptureGeometry.caption(480, measured).textSize
            assertTrue(textSize > 0f && textSize <= base + tolerance, "measured=$measured gave $textSize")
        }
    }

    // ---- helpers -----------------------------------------------------------

    /** The viewport's `scaleX = -1f`, about the vertical axis through [cx].
     *  Lives here rather than in [CaptureGeometry] because a capture never
     *  mirrors — the app has no use for it, only this proof does. */
    private fun mirrorX(cx: Float): Affine =
        CaptureGeometry.translation(cx, 0f) *
            Affine(-1f, 0f, 0f, 0f, 1f, 0f) *
            CaptureGeometry.translation(-cx, 0f)

    /** Inverse of [CaptureGeometry.captureTransform], composed from the same
     *  primitives: undo the rotation, the scale, then the crop offset. */
    private fun inverseOf(sourceWidth: Int, sourceHeight: Int, side: Int, degrees: Float): Affine {
        val crop = CaptureGeometry.cropSide(sourceWidth, sourceHeight)
        val centre = side / 2f
        return CaptureGeometry.translation(
            (sourceWidth - crop) / 2f,
            (sourceHeight - crop) / 2f,
        ) *
            CaptureGeometry.scaling(crop.toFloat() / side) *
            CaptureGeometry.rotation(-degrees, centre, centre)
    }

    private fun assertMaps(
        t: Affine,
        from: Pair<Float, Float>,
        to: Pair<Float, Float>,
        hint: String = "",
    ) {
        assertEquals(to.first, t.mapX(from.first, from.second), tolerance, "x $hint")
        assertEquals(to.second, t.mapY(from.first, from.second), tolerance, "y $hint")
    }

    private fun assertAffineEquals(expected: Affine, actual: Affine, hint: String = "") {
        assertEquals(expected.a, actual.a, tolerance, "a $hint")
        assertEquals(expected.b, actual.b, tolerance, "b $hint")
        assertEquals(expected.tx, actual.tx, tolerance, "tx $hint")
        assertEquals(expected.c, actual.c, tolerance, "c $hint")
        assertEquals(expected.d, actual.d, tolerance, "d $hint")
        assertEquals(expected.ty, actual.ty, tolerance, "ty $hint")
    }
}
