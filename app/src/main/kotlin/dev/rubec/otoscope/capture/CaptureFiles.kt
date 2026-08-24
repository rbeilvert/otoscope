package dev.rubec.otoscope.capture

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Names and media-store paths for saved captures.
 *
 * The writer's `RELATIVE_PATH` and the gallery query's `LIKE` argument both
 * come from here: changing one alone would make every existing capture vanish
 * from the in-app gallery. The top-level directory is a parameter to keep this
 * file Android-free; [CaptureStore] passes `DIRECTORY_PICTURES` / `_MOVIES`.
 */
internal object CaptureFiles {

    /** Sub-directory of Pictures/ and Movies/ that this app writes to. */
    const val ALBUM = "Otoscope"

    private const val PREFIX = "otoscope-"
    private const val STAMP_PATTERN = "yyyyMMdd-HHmmss-SSS"

    /**
     * `otoscope-20260824-193012-045.jpg`. Milliseconds are in the name because
     * a user can tap the shutter twice inside a second and the media store
     * would otherwise hand the second file a `(1)` suffix.
     */
    fun fileName(
        isVideo: Boolean,
        atMs: Long,
        zone: TimeZone = TimeZone.getDefault(),
    ): String {
        val format = SimpleDateFormat(STAMP_PATTERN, Locale.ROOT).apply { timeZone = zone }
        return "$PREFIX${format.format(Date(atMs))}${if (isVideo) ".mp4" else ".jpg"}"
    }

    fun mimeType(isVideo: Boolean): String = if (isVideo) "video/mp4" else "image/jpeg"

    /** Value for `MediaStore.MediaColumns.RELATIVE_PATH`. */
    fun relativePath(topLevelDir: String): String = "$topLevelDir/$ALBUM"

    /**
     * `LIKE` argument that finds this app's captures again. Deliberately looser
     * than an equality test on [relativePath]: the stored value carries a
     * trailing slash, and normalisation has varied across releases.
     */
    fun albumSelectionArg(topLevelDir: String): String = "${relativePath(topLevelDir)}%"
}
