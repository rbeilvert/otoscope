package dev.rubec.otoscope.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import dev.rubec.otoscope.capture.CaptureGeometry

/**
 * Renders the latest camera frame inside a circular mask.
 *
 * Two transformations live here:
 *  - **Horizontal mirror**. When [flipEnabled] is true the image is flipped
 *    horizontally with `scaleX = -1f`. Corrects for lens optics that deliver
 *    a left/right-flipped image.
 *  - **Rotation**. The camera's on-board accelerometer feeds [rotationDegrees]
 *    for vendors that expose it. When [flipEnabled] is true we negate the
 *    rotation because the horizontal flip reverses the visual sense — without
 *    the negation a clockwise hand motion would look counter-clockwise.
 *
 *  The angle is passed straight through to `rotationZ` per telemetry sample —
 *  no session-level EMA and no Compose-level tween. We tried both and neither
 *  produced a visibly smoother image; the accelerometer already gives us a
 *  stable stream at ~20 Hz, and any extra filter only adds perceivable lag.
 *
 *  [overlayText] previews the caption that [dev.rubec.otoscope.capture.FrameComposer]
 *  burns into saved captures. Its placement comes from the same
 *  [CaptureGeometry.caption] the burn-in uses, but it's drawn *outside* both the
 *  circular clip and the mirrored layer.
 */
@Composable
fun CameraFrame(
    frame: Bitmap?,
    modifier: Modifier = Modifier,
    rotationDegrees: Float = 0f,
    flipEnabled: Boolean = true,
    overlayText: String = "",
) {
    // Only changes when the frame is laid out, not per video frame.
    var sidePx by remember { mutableIntStateOf(0) }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .onSizeChanged { sidePx = it.width },
        contentAlignment = Alignment.Center,
    ) {
        if (frame != null) {
            Image(
                bitmap = frame.asImageBitmap(),
                contentDescription = "Otoscope camera view",
                modifier = Modifier
                    .fillMaxSize()
                    // Clip here rather than on the parent: the mask belongs to
                    // the image, and the caption below has to escape it.
                    .clip(CircleShape)
                    .graphicsLayer {
                        scaleX = if (flipEnabled) -1f else 1f
                        rotationZ = if (flipEnabled) -rotationDegrees else rotationDegrees
                    },
                contentScale = ContentScale.Crop,
            )
            if (overlayText.isNotBlank() && sidePx > 0) {
                CaptionPreview(overlayText.trim(), sidePx, Modifier.align(Alignment.BottomStart))
            }
        } else {
            Text(
                "Waiting for frames…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The caption as it will be burnt in, at the size and offset
 * [CaptureGeometry.caption] gives for a [sidePx]-wide frame. One divergence: an
 * over-long caption ellipsises here where the burn-in shrinks the type to fit.
 */
@Composable
private fun CaptionPreview(text: String, sidePx: Int, modifier: Modifier = Modifier) {
    val caption = remember(sidePx) { CaptureGeometry.caption(sidePx, measuredWidth = 0f) }
    with(LocalDensity.current) {
        Text(
            text,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = caption.textSize.toSp(),
                shadow = Shadow(
                    color = Color.Black.copy(alpha = 0.7f),
                    offset = Offset(0f, caption.shadowOffsetY),
                    blurRadius = caption.shadowRadius,
                ),
            ),
            modifier = modifier.padding(
                start = caption.x.toDp(),
                bottom = (sidePx - caption.baselineY).toDp(),
            ),
        )
    }
}
