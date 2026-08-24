package dev.rubec.otoscope.capture

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.graphics.createBitmap

/**
 * Bakes a live camera frame into the still we hand to the encoder or the JPEG
 * writer: the same square, circle-masked, orientation-corrected view the user
 * sees in [dev.rubec.otoscope.ui.CameraFrame], on black, plus the [overlay]
 * caption burnt into the corner — and never mirrored, however the viewport's
 * toggle is set (see [CaptureGeometry.captureTransform]).
 *
 * The mask is a [BitmapShader] circle rather than a `clipPath` so its edge is
 * antialiased. It can never expose the shader's clamp region: a square's
 * inscribed circle is rotation-invariant, and the mask is that circle.
 *
 * Every dimension and the transform come from [CaptureGeometry].
 */
internal object FrameComposer {

    /**
     * @param frame source frame, possibly a `Config.HARDWARE` bitmap straight
     *   out of [dev.rubec.otoscope.stream.JpegDecoder].
     * @param rotationDegrees clockwise angle from the camera's accelerometer.
     * @param overlay caption to burn in; blank or null draws nothing.
     * @param side edge length of the returned square bitmap.
     */
    fun compose(frame: Bitmap, rotationDegrees: Float, overlay: String?, side: Int): Bitmap {
        // A hardware bitmap can't back a software shader, so read it back first.
        // The copy is also our defence against the decoder handing us a frame
        // whose config we don't expect.
        val readable = frame.readableCopy()
        try {
            val out = createBitmap(side, side)
            val canvas = Canvas(out)
            canvas.drawColor(Color.BLACK)

            val transform = CaptureGeometry.captureTransform(
                sourceWidth = readable.width,
                sourceHeight = readable.height,
                side = side,
                rotationDegrees = rotationDegrees,
            )
            val matrix = Matrix().apply { setValues(transform.toMatrixValues()) }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                isFilterBitmap = true
                shader = BitmapShader(readable, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                    .apply { setLocalMatrix(matrix) }
            }
            canvas.drawCircle(side / 2f, side / 2f, side / 2f, paint)

            overlay?.trim()?.takeIf { it.isNotEmpty() }?.let { drawCaption(canvas, side, it) }
            return out
        } finally {
            if (readable !== frame) readable.recycle()
        }
    }

    /** Small white caption with a drop shadow, so it stays legible where it
     *  crosses the bright part of the image rather than the black corner. */
    private fun drawCaption(canvas: Canvas, side: Int, text: String) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = CaptureGeometry.captionTextSize(side)
        }
        val caption = CaptureGeometry.caption(side, paint.measureText(text))
        paint.textSize = caption.textSize
        paint.setShadowLayer(caption.shadowRadius, 0f, caption.shadowOffsetY, SHADOW_COLOR)
        canvas.drawText(text, caption.x, caption.baselineY, paint)
    }

    private val SHADOW_COLOR = Color.argb(0xB0, 0, 0, 0)

    /** Identity for software bitmaps, a CPU-side readback for hardware ones. */
    private fun Bitmap.readableCopy(): Bitmap =
        if (config == Bitmap.Config.ARGB_8888 && !isRecycled) this
        else copy(Bitmap.Config.ARGB_8888, false)
            ?: error("cannot read back a ${config} frame")
}
