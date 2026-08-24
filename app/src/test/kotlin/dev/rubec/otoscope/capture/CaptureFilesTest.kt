package dev.rubec.otoscope.capture

import java.time.Instant
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Naming and album paths. The path matters more than it looks: the writer
 * builds `RELATIVE_PATH` and the gallery query builds the `LIKE` argument that
 * has to match it, so a change to one and not the other would make every
 * existing capture disappear from the in-app gallery while leaving the files on
 * disk.
 */
class CaptureFilesTest {

    private val utc = TimeZone.getTimeZone("UTC")
    private val at = Instant.parse("2026-08-24T19:30:12.045Z").toEpochMilli()

    @Test fun `still names carry a millisecond stamp`() {
        assertEquals("otoscope-20260824-193012-045.jpg", CaptureFiles.fileName(false, at, utc))
    }

    @Test fun `clip names differ only by extension`() {
        assertEquals("otoscope-20260824-193012-045.mp4", CaptureFiles.fileName(true, at, utc))
    }

    @Test fun `two captures a millisecond apart get different names`() {
        // Without the milliseconds in the stamp, a double tap on the shutter
        // would collide and the media store would append its own " (1)".
        assertNotEquals(
            CaptureFiles.fileName(false, at, utc),
            CaptureFiles.fileName(false, at + 1, utc),
        )
    }

    @Test fun `names use a fixed pattern whatever the device timezone`() {
        val pattern = Regex("""otoscope-\d{8}-\d{6}-\d{3}\.(jpg|mp4)""")
        for (zone in listOf("UTC", "Europe/Paris", "Pacific/Kiritimati", "America/St_Johns")) {
            val name = CaptureFiles.fileName(true, at, TimeZone.getTimeZone(zone))
            assertTrue(pattern.matches(name), "$zone produced $name")
        }
    }

    @Test fun `names are safe for a media store display name`() {
        val name = CaptureFiles.fileName(false, at, utc)
        for (forbidden in listOf('/', '\\', ':', '*', '?', '"', '<', '>', '|', ' ')) {
            assertTrue(forbidden !in name, "'$forbidden' in $name")
        }
    }

    @Test fun `mime types match the extensions`() {
        assertEquals("image/jpeg", CaptureFiles.mimeType(isVideo = false))
        assertEquals("video/mp4", CaptureFiles.mimeType(isVideo = true))
    }

    @Test fun `captures land in an album under the standard directories`() {
        assertEquals("Pictures/Otoscope", CaptureFiles.relativePath("Pictures"))
        assertEquals("Movies/Otoscope", CaptureFiles.relativePath("Movies"))
    }

    @Test fun `the gallery query matches what the writer wrote`() {
        // The media store stores RELATIVE_PATH with a trailing slash, so the
        // read side has to be a prefix match, not an equality test.
        for (dir in listOf("Pictures", "Movies")) {
            val stored = CaptureFiles.relativePath(dir) + "/"
            val selection = CaptureFiles.albumSelectionArg(dir)
            assertTrue(selection.endsWith("%"), "$selection is not a LIKE prefix pattern")
            assertTrue(
                stored.startsWith(selection.removeSuffix("%")),
                "stored path $stored wouldn't match $selection",
            )
        }
    }

    @Test fun `the album selection does not match a neighbouring folder`() {
        // "Pictures/Otoscope%" must not swallow "Pictures/Otoscopy/" — the
        // pattern is anchored on the full album name.
        val selection = CaptureFiles.albumSelectionArg("Pictures").removeSuffix("%")
        assertTrue(!"Pictures/Otoscopy/".startsWith(selection))
        assertTrue(!"Pictures/Camera/".startsWith(selection))
    }
}
