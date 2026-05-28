package dev.rubec.otoscope.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale

/**
 * Renders the latest camera frame inside a circular mask.
 *
 * Two transformations live here:
 *  - **Horizontal mirror**. When [flipEnabled] is true, the image is flipped
 *    horizontally with `scaleX = -1f`. This corrects for lens optics that
 *    deliver a left/right-flipped image.
 *  - **Rotation**. The camera's on-board accelerometer feeds [rotationDegrees].
 *    When [flipEnabled] is true, we negate the rotation because the horizontal
 *    flip reverses the visual sense of rotation — without the negation a
 *    clockwise hand motion would look counter-clockwise on screen.
 */
@Composable
fun CameraFrame(
    frame: Bitmap?,
    rotationDegrees: Float = 0f,
    flipEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (frame != null) {
            Image(
                bitmap = frame.asImageBitmap(),
                contentDescription = "Otoscope camera view",
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = if (flipEnabled) -1f else 1f
                        rotationZ = if (flipEnabled) -rotationDegrees else rotationDegrees
                    },
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                "Waiting for frames…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
