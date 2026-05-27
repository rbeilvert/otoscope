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
 *  - **Horizontal mirror**. The lens optics deliver a left/right-flipped image —
 *    something pointed to the user's right shows up on the left of the frame
 *    without this. We undo it with `scaleX = -1f`.
 *  - **Rotation**. The camera's on-board accelerometer feeds [rotationDegrees].
 *    We negate it because the horizontal flip reverses the visual sense of
 *    rotation — without the negation a clockwise hand motion looks
 *    counter-clockwise on screen.
 */
@Composable
fun CameraFrame(
    frame: Bitmap?,
    rotationDegrees: Float = 0f,
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
                        scaleX = -1f
                        rotationZ = -rotationDegrees
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
