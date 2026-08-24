package dev.rubec.otoscope.ui

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The recording timer and the gallery's clip badges both read from
 * [formatDuration]. It runs on every tick, so it stays allocation-cheap and
 * locale-independent — a device set to a locale with non-ASCII digits should
 * still show a clock a user recognises.
 */
class CaptureFormattingTest {

    @Test fun `sub-second durations read as zero`() {
        assertEquals("0:00", formatDuration(0L))
        assertEquals("0:00", formatDuration(999L))
    }

    @Test fun `seconds and minutes are zero-padded`() {
        assertEquals("0:01", formatDuration(1_000L))
        assertEquals("0:09", formatDuration(9_999L))
        assertEquals("1:00", formatDuration(60_000L))
        assertEquals("1:01", formatDuration(61_000L))
        assertEquals("9:59", formatDuration(599_000L))
        assertEquals("59:59", formatDuration(3_599_999L))
    }

    @Test fun `an hour switches to the long form`() {
        assertEquals("1:00:00", formatDuration(3_600_000L))
        assertEquals("1:01:01", formatDuration(3_661_000L))
        assertEquals("10:00:00", formatDuration(36_000_000L))
    }

    @Test fun `a negative duration clamps to zero`() {
        // The elapsed counter is derived from System.nanoTime deltas, so this
        // shouldn't happen — but a "-1:-1" on screen would be worse than a 0.
        assertEquals("0:00", formatDuration(-1L))
        assertEquals("0:00", formatDuration(-60_000L))
    }

    @Test fun `digits stay ASCII in a locale that would localise them`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG-u-nu-arab"))
            assertEquals("1:01", formatDuration(61_000L))
            assertEquals("1:01:01", formatDuration(3_661_000L))
        } finally {
            Locale.setDefault(original)
        }
    }
}
