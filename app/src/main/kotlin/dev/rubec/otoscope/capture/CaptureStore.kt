package dev.rubec.otoscope.capture

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore

/** One saved capture, as listed by [CaptureStore.list]. */
data class CapturedMedia(
    val uri: Uri,
    val name: String,
    val isVideo: Boolean,
    /** Wall-clock milliseconds, for grouping and display. */
    val addedAtMs: Long,
    /** Clip length; 0 for stills. */
    val durationMs: Long,
    val sizeBytes: Long,
)

/**
 * Reads and writes captures in the shared media collections, under
 * `Pictures/Otoscope` and `Movies/Otoscope`.
 *
 * No storage permission anywhere: since API 29 an app may insert freely and
 * query back the rows it owns, which is exactly the in-app gallery's scope. A
 * reinstall loses that ownership, so older captures stay on disk and in the
 * user's gallery app but drop out of ours.
 *
 * All methods block on the content resolver — call them off the main thread.
 */
internal class CaptureStore(context: Context) {

    private val resolver = context.contentResolver
    private val images = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val videos = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /** The row a capture starts life as: named, typed, filed in the album, and
     *  flagged pending so no other app sees a half-written file. */
    private fun pendingValues(isVideo: Boolean, topLevelDir: String): ContentValues {
        val now = System.currentTimeMillis()
        return ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, CaptureFiles.fileName(isVideo, now))
            put(MediaStore.MediaColumns.MIME_TYPE, CaptureFiles.mimeType(isVideo))
            put(MediaStore.MediaColumns.RELATIVE_PATH, CaptureFiles.relativePath(topLevelDir))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(MediaStore.MediaColumns.DATE_TAKEN, now)
        }
    }

    /** Writes [bitmap] as a JPEG and returns its content URI. */
    fun saveImage(bitmap: Bitmap): Uri {
        val values = pendingValues(isVideo = false, topLevelDir = Environment.DIRECTORY_PICTURES)
        val uri = resolver.insert(images, values) ?: error("media store rejected the insert")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                    "JPEG encoding failed"
                }
            } ?: error("cannot open $uri for writing")
        } catch (e: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        publish(uri)
        return uri
    }

    /** An MP4 row that exists but is still flagged pending. The caller writes
     *  through [descriptor], then calls [CaptureStore.publish] or
     *  [CaptureStore.discard]. */
    class PendingVideo(val uri: Uri, val descriptor: ParcelFileDescriptor)

    fun createVideo(): PendingVideo {
        val values = pendingValues(isVideo = true, topLevelDir = Environment.DIRECTORY_MOVIES)
        val uri = resolver.insert(videos, values) ?: error("media store rejected the insert")
        // "rw" rather than "w": MediaMuxer seeks back to patch up the moov atom
        // when it finalises the file.
        val fd = try {
            resolver.openFileDescriptor(uri, "rw") ?: error("cannot open $uri for writing")
        } catch (e: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        return PendingVideo(uri, fd)
    }

    /** Clears IS_PENDING so the file becomes visible to other apps. */
    fun publish(uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        runCatching { resolver.update(uri, values, null, null) }
    }

    fun discard(uri: Uri) {
        runCatching { resolver.delete(uri, null, null) }
    }

    fun delete(uri: Uri): Boolean = runCatching { resolver.delete(uri, null, null) > 0 }
        .getOrDefault(false)

    /** Newest first, stills and clips interleaved by capture time. */
    fun list(): List<CapturedMedia> =
        (query(images, Environment.DIRECTORY_PICTURES, isVideo = false) +
            query(videos, Environment.DIRECTORY_MOVIES, isVideo = true))
            .sortedByDescending { it.addedAtMs }

    private fun query(collection: Uri, topLevelDir: String, isVideo: Boolean): List<CapturedMedia> {
        // DURATION is only asked for on the video collection: a few OEM media
        // providers throw rather than return null for it on images.
        val projection = listOfNotNull(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DURATION.takeIf { isVideo },
        ).toTypedArray()
        // LIKE rather than an equality test on RELATIVE_PATH: the stored value
        // carries a trailing slash and normalisation has varied across
        // releases.
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val args = arrayOf(CaptureFiles.albumSelectionArg(topLevelDir))
        val order = "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        return resolver.query(collection, projection, selection, args, order)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val durationCol = cursor.getColumnIndex(MediaStore.MediaColumns.DURATION)
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        CapturedMedia(
                            uri = ContentUris.withAppendedId(collection, cursor.getLong(idCol)),
                            name = cursor.getString(nameCol) ?: "",
                            isVideo = isVideo,
                            // DATE_ADDED is in seconds, unlike DATE_TAKEN.
                            addedAtMs = cursor.getLong(dateCol) * 1000L,
                            durationMs = if (durationCol >= 0 && !cursor.isNull(durationCol)) {
                                cursor.getLong(durationCol)
                            } else {
                                0L
                            },
                            sizeBytes = cursor.getLong(sizeCol),
                        )
                    )
                }
            }
        }.orEmpty()
    }

    private companion object {
        const val JPEG_QUALITY = 95
    }
}
