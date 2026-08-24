package dev.rubec.otoscope.ui

import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import java.util.Locale

/** Makes an icon-only control tappable and announceable. Clip before this so
 *  the ripple follows the control's shape. */
internal fun Modifier.clickableRole(label: String, onClick: () -> Unit): Modifier = this
    .semantics { contentDescription = label }
    .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)

/** `m:ss`, or `h:mm:ss` for the rare long recording. */
internal fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}
