package dev.rubec.otoscope.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rubec.otoscope.capture.CaptureMode
import dev.rubec.otoscope.capture.CapturedMedia

/** Record indicator / shutter red. Deliberately not a theme colour: on a
 *  dynamic-colour device the "recording" affordance still has to read as red. */
private val RecordRed = Color(0xFFE53935)

/**
 * Bottom capture bar: the foldable caption field on top, then gallery on the
 * left, shutter in the middle, photo/video selector on the right.
 *
 * The shutter is centred with [Alignment.Center] rather than laid out in a
 * weighted [Row] so it stays put regardless of how wide the side controls get.
 */
@Composable
fun CaptureBar(
    mode: CaptureMode,
    onModeChange: (CaptureMode) -> Unit,
    recording: Boolean,
    savingPhoto: Boolean,
    elapsedMs: Long,
    latest: CapturedMedia?,
    overlayText: String,
    onOverlayTextChange: (String) -> Unit,
    onShutter: () -> Unit,
    onOpenGallery: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            var captionExpanded by rememberSaveable { mutableStateOf(false) }
            val focusManager = LocalFocusManager.current

            CaptionHeader(
                value = overlayText,
                recording = recording,
                elapsedMs = elapsedMs,
                expanded = captionExpanded,
                onToggle = {
                    captionExpanded = !captionExpanded
                    // Folding while the field holds focus would leave its
                    // selection handles floating over whatever comes next.
                    if (!captionExpanded) focusManager.clearFocus()
                },
            )
            AnimatedVisibility(visible = captionExpanded) {
                CaptionField(value = overlayText, onValueChange = onOverlayTextChange)
            }
            Spacer(Modifier.height(8.dp))
            Box(modifier = Modifier.fillMaxWidth()) {
                GalleryButton(
                    latest = latest,
                    onClick = onOpenGallery,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
                Shutter(
                    mode = mode,
                    recording = recording,
                    busy = savingPhoto,
                    onClick = onShutter,
                    modifier = Modifier.align(Alignment.Center),
                )
                ModeSelector(
                    mode = mode,
                    // Switching mid-clip would silently drop the recording, so
                    // the selector locks until it's stopped.
                    enabled = !recording,
                    onModeChange = onModeChange,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
    }
}

/**
 * One-line strip above the shutter: what the caption says, the recording clock
 * while a clip runs, and a chevron to unfold the editor. The clock shares this
 * row so the bar needn't reserve empty height for it.
 */
@Composable
private fun CaptionHeader(
    value: String,
    recording: Boolean,
    elapsedMs: Long,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            // Fixed height: the recording pill is taller than the label, and
            // the bar must not resize when a clip starts.
            .height(32.dp)
            .clickableRole(if (expanded) "Hide the overlay caption field" else "Set the overlay caption", onToggle)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.TextFields,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = value.ifBlank { "Overlay" },
            style = MaterialTheme.typography.labelMedium,
            color = if (value.isBlank()) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (recording) {
            RecordingTimer(elapsedMs)
            Spacer(Modifier.size(8.dp))
        }
        Icon(
            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * Compact single-line editor for the burnt-in caption.
 *
 * A [BasicTextField] in a bordered row rather than an `OutlinedTextField`,
 * whose 56 dp minimum and floating label are more furniture than this deserves
 * next to the shutter. Free text rather than a Left/Right toggle: people label
 * these for charts, patients and dates.
 */
@Composable
private fun CaptionField(value: String, onValueChange: (String) -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp)
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
            .padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = value,
            onValueChange = { onValueChange(it.take(MAX_OVERLAY_CHARS)) },
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium
                .copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
            decorationBox = { innerTextField ->
                if (value.isEmpty()) {
                    Text(
                        "e.g. Left ear / Right ear",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                innerTextField()
            },
        )
        if (value.isNotEmpty()) {
            Icon(
                Icons.Default.Clear,
                contentDescription = "Clear the overlay text",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickableRole("Clear the overlay text") { onValueChange("") }
                    .padding(7.dp),
            )
        }
    }
}

/** Long enough for "Right ear, post-irrigation", short enough that the caption
 *  doesn't have to shrink to illegibility to fit the frame. */
private const val MAX_OVERLAY_CHARS = 48

@Composable
private fun RecordingTimer(elapsedMs: Long) {
    val transition = rememberInfiniteTransition(label = "rec-blink")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "rec-dot",
    )
    Row(
        modifier = Modifier
            .background(RecordRed.copy(alpha = 0.15f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(RecordRed.copy(alpha = alpha), CircleShape)
        )
        Spacer(Modifier.size(8.dp))
        Text(
            formatDuration(elapsedMs),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * A ring with a fill that morphs to say what a tap will do: a white disc for a
 * still, a red disc to arm a recording, a red rounded square while one is
 * running.
 */
@Composable
private fun Shutter(
    mode: CaptureMode,
    recording: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fill by animateColorAsState(
        targetValue = when {
            mode == CaptureMode.VIDEO -> RecordRed
            busy -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            else -> MaterialTheme.colorScheme.onSurface
        },
        label = "shutter-fill",
    )
    // Morphs from disc to stop-square so the state change reads at a glance.
    val inset by animateDpAsState(if (recording) 20.dp else 6.dp, label = "shutter-inset")
    val corner by animateDpAsState(if (recording) 6.dp else 36.dp, label = "shutter-corner")
    val label = when {
        recording -> "Stop recording"
        mode == CaptureMode.VIDEO -> "Start recording"
        else -> "Take a photo"
    }
    Box(
        modifier = modifier
            .size(72.dp)
            .clip(CircleShape)
            .clickableRole(label, onClick)
            .border(3.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(inset)
                .background(fill, RoundedCornerShape(corner))
        )
    }
}

@Composable
private fun ModeSelector(
    mode: CaptureMode,
    enabled: Boolean,
    onModeChange: (CaptureMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 1f else 0.4f),
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ModeChip(
                icon = Icons.Default.PhotoCamera,
                label = "Photo mode",
                selected = mode == CaptureMode.PHOTO,
                enabled = enabled,
                onClick = { onModeChange(CaptureMode.PHOTO) },
            )
            ModeChip(
                icon = Icons.Default.Videocam,
                label = "Video mode",
                selected = mode == CaptureMode.VIDEO,
                enabled = enabled,
                onClick = { onModeChange(CaptureMode.VIDEO) },
            )
        }
    }
}

@Composable
private fun ModeChip(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val background by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        label = "mode-bg",
    )
    val tint = when {
        selected -> MaterialTheme.colorScheme.onPrimary
        enabled -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    }
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(background)
            .then(if (enabled) Modifier.clickableRole(label, onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/** Rounded-square thumbnail of the newest capture, or a library icon when
 *  nothing has been saved yet. */
@Composable
private fun GalleryButton(
    latest: CapturedMedia?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .clickableRole("Open captures", onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (latest == null) {
            Icon(
                Icons.Default.PhotoLibrary,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        } else {
            MediaThumbnail(media = latest, modifier = Modifier.fillMaxSize())
        }
    }
}