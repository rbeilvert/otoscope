package dev.rubec.otoscope.capture

import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The geometry behind a saved capture, with no `android.graphics` in sight.
 *
 * [FrameComposer] owns the pixels; this owns the arithmetic that decides where
 * they land — the crop, the rotation direction, the caption placement. The
 * composer feeds [Affine.toMatrixValues] straight into `Matrix.setValues`, so
 * there's no second copy of these rules to drift.
 *
 * Coordinates are source-frame pixels mapping to output pixels, y pointing
 * **down** — the `android.graphics` and Compose convention, in which a positive
 * angle turns clockwise.
 */
internal object CaptureGeometry {

    /**
     * A 2×3 affine map, laid out the way `android.graphics.Matrix` reads it.
     */
    data class Affine(
        val a: Float,
        val b: Float,
        val tx: Float,
        val c: Float,
        val d: Float,
        val ty: Float,
    ) {
        fun mapX(x: Float, y: Float): Float = a * x + b * y + tx
        fun mapY(x: Float, y: Float): Float = c * x + d * y + ty

        /** `this ∘ [inner]`: apply [inner] first, then this. */
        operator fun times(inner: Affine): Affine = Affine(
            a = a * inner.a + b * inner.c,
            b = a * inner.b + b * inner.d,
            tx = a * inner.tx + b * inner.ty + tx,
            c = c * inner.a + d * inner.c,
            d = c * inner.b + d * inner.d,
            ty = c * inner.tx + d * inner.ty + ty,
        )

        /** Row-major 3×3, the order `Matrix.setValues` expects. */
        fun toMatrixValues(): FloatArray = floatArrayOf(a, b, tx, c, d, ty, 0f, 0f, 1f)
    }

    fun translation(dx: Float, dy: Float): Affine = Affine(1f, 0f, dx, 0f, 1f, dy)

    fun scaling(factor: Float): Affine = Affine(factor, 0f, 0f, 0f, factor, 0f)

    /** Clockwise rotation by [degrees] about ([cx], [cy]). */
    fun rotation(degrees: Float, cx: Float = 0f, cy: Float = 0f): Affine {
        val radians = Math.toRadians(degrees.toDouble())
        val cosine = cos(radians).toFloat()
        val sine = sin(radians).toFloat()
        return translation(cx, cy) *
            Affine(cosine, -sine, 0f, sine, cosine, 0f) *
            translation(-cx, -cy)
    }

    /** Edge of the centre-crop square: a still is as big as the frame's shorter
     *  side allows without letterboxing. */
    fun cropSide(width: Int, height: Int): Int = min(width, height)

    /**
     * Edge of a recorded frame. Rounded down to a multiple of 16, because
     * plenty of hardware H.264 encoders quietly produce garbage or refuse to
     * configure on odd macroblock alignments, and floored at one macroblock so
     * a nonsense frame size can't hand MediaCodec a zero.
     */
    fun videoSide(width: Int, height: Int): Int =
        ((cropSide(width, height) / 16) * 16).coerceAtLeast(16)

    /**
     * Source-pixel → output-pixel transform for a capture: centre-crop to a
     * square, scale that square up to [side], then turn it [rotationDegrees]
     * clockwise about the centre. The viewport's `ContentScale.Crop` followed
     * by `rotationZ`, in that order.
     *
     * Never mirrored, whatever the viewport's toggle says. The viewport draws
     * `mirror ∘ rotation(-angle)`, the same map as `rotation(angle) ∘ mirror`,
     * so dropping the mirror leaves exactly `rotation(angle)` — hence no
     * parameter for the toggle here.
     */
    fun captureTransform(
        sourceWidth: Int,
        sourceHeight: Int,
        side: Int,
        rotationDegrees: Float,
    ): Affine {
        val crop = cropSide(sourceWidth, sourceHeight)
        val centre = side / 2f
        return rotation(rotationDegrees, centre, centre) *
            scaling(side.toFloat() / crop) *
            translation(-(sourceWidth - crop) / 2f, -(sourceHeight - crop) / 2f)
    }

    /** Where the burnt-in caption goes and how it's drawn. Everything scales
     *  with the frame so a 480² clip and a 720² still read the same. */
    data class Caption(
        val x: Float,
        val baselineY: Float,
        val textSize: Float,
        val shadowRadius: Float,
        val shadowOffsetY: Float,
    )

    /** Caption size before shrink-to-fit — the size the text has to be measured
     *  at to call [caption]. */
    fun captionTextSize(side: Int): Float = side * CAPTION_TEXT_RATIO

    /**
     * Caption placement in a [side]-pixel frame, given how wide the text
     * measured at [captionTextSize]. A long label shrinks to the available
     * width instead of running off the edge; a short one is left alone, and a
     * zero measurement (an empty string) can't divide its way to a NaN.
     */
    fun caption(side: Int, measuredWidth: Float): Caption {
        val margin = side * CAPTION_MARGIN_RATIO
        val base = captionTextSize(side)
        val available = side - 2 * margin
        return Caption(
            x = margin,
            baselineY = side - margin,
            textSize = if (measuredWidth > available) base * (available / measuredWidth) else base,
            shadowRadius = side * CAPTION_SHADOW_RATIO,
            shadowOffsetY = side * CAPTION_SHADOW_OFFSET_RATIO,
        )
    }

    private const val CAPTION_MARGIN_RATIO = 0.05f
    private const val CAPTION_TEXT_RATIO = 0.048f
    private const val CAPTION_SHADOW_RATIO = 0.012f
    private const val CAPTION_SHADOW_OFFSET_RATIO = 0.004f
}
