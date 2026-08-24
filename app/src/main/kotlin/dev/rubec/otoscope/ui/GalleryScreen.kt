package dev.rubec.otoscope.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.util.Size
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.rubec.otoscope.capture.CapturedMedia
import dev.rubec.otoscope.ui.theme.otoscopeTopAppBarColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * In-app review of everything the capture bar has saved: a thumbnail grid that
 * opens into a one-item viewer with share and delete.
 *
 * A full-screen overlay on the streaming view rather than a separate
 * destination, so the session — and any running recording — carries on
 * underneath.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    media: List<CapturedMedia>,
    onClose: () -> Unit,
    onDelete: (CapturedMedia) -> Unit,
) {
    var opened: CapturedMedia? by remember { mutableStateOf(null) }

    // Resolved against the live listing rather than rendered from `opened`
    // directly, so deleting the open item drops straight back to the grid.
    val current = opened?.let { sel -> media.firstOrNull { it.uri == sel.uri } }

    BackHandler { if (current != null) opened = null else onClose() }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (current != null) {
            MediaViewer(
                media = current,
                onClose = { opened = null },
                onDelete = { onDelete(current) },
            )
        } else {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Captures") },
                        navigationIcon = {
                            IconButton(onClick = onClose) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to the camera")
                            }
                        },
                        colors = otoscopeTopAppBarColors(),
                    )
                }
            ) { padding ->
                if (media.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "No captures yet.\nUse the shutter button to save a photo or a clip.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(112.dp),
                        modifier = Modifier.fillMaxSize().padding(padding),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(media, key = { it.uri.toString() }) { item ->
                            GalleryTile(item, onClick = { opened = item })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GalleryTile(media: CapturedMedia, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .clickableRole(
                if (media.isVideo) "Open clip ${media.name}" else "Open photo ${media.name}",
                onClick,
            )
    ) {
        MediaThumbnail(media = media, modifier = Modifier.fillMaxSize())
        if (media.isVideo) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.size(2.dp))
                Text(
                    formatDuration(media.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaViewer(media: CapturedMedia, onClose: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(media.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                .format(Date(media.addedAtMs)),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to the grid")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = if (media.isVideo) "video/mp4" else "image/jpeg"
                            putExtra(Intent.EXTRA_STREAM, media.uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(share, "Share capture"))
                    }) {
                        Icon(Icons.Default.Share, contentDescription = "Share")
                    }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete")
                    }
                },
                colors = otoscopeTopAppBarColors(),
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            if (media.isVideo) {
                VideoPlayer(media)
            } else {
                StillViewer(media)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete capture?") },
            text = { Text("${media.name} will be removed from your device.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun StillViewer(media: CapturedMedia) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, media.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                ImageDecoder.decodeBitmap(
                    ImageDecoder.createSource(context.contentResolver, media.uri)
                )
            }.getOrNull()
        }
    }
    val still = bitmap
    if (still == null) {
        CircularProgressIndicator()
    } else {
        // fillMaxSize, not fillMaxWidth: with the height left unbounded the
        // painter falls back to the bitmap's pixel height as dp, which on a
        // dense screen renders a 480² still at a third of the width.
        Image(
            bitmap = still.asImageBitmap(),
            contentDescription = media.name,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
    }
}

/**
 * Plain [VideoView] — enough for reviewing a few seconds of otoscope footage,
 * and it avoids pulling Media3/ExoPlayer in for it. Tap to pause and resume.
 */
@Composable
private fun VideoPlayer(media: CapturedMedia) {
    var view: VideoView? by remember { mutableStateOf(null) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickableRole("Play or pause") {
                view?.let { if (it.isPlaying) it.pause() else it.start() }
            },
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { ctx ->
                VideoView(ctx).apply {
                    setVideoURI(media.uri)
                    setOnPreparedListener { player ->
                        player.isLooping = true
                        start()
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { view = it },
            onRelease = { it.stopPlayback() },
        )
    }
}

/** Media-store thumbnail for [media], on black so a still that isn't square
 *  still fills its tile. Callers clip to whatever shape they want. */
@Composable
internal fun MediaThumbnail(media: CapturedMedia, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val thumbnail by produceState<Bitmap?>(null, media.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.loadThumbnail(media.uri, Size(THUMBNAIL_PX, THUMBNAIL_PX), null)
            }.getOrNull()
        }
    }
    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        thumbnail?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

/** Thumbnails are only ever shown at 48–112 dp, so 256 px covers the densest
 *  screen without asking the media store to decode a full frame. */
private const val THUMBNAIL_PX = 256
