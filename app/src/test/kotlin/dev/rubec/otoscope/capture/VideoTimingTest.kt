package dev.rubec.otoscope.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Encoder timing rules. Two of these guard crashes rather than cosmetics:
 * `MediaMuxer.writeSampleData` throws on a timestamp that doesn't advance, and
 * publishing a sample-less MP4 leaves an unplayable file in the user's gallery.
 */
class VideoTimingTest {

    // ---- presentation timestamps -------------------------------------------

    @Test fun `the first frame keeps its own timestamp`() {
        // VideoRecorder starts with lastPresentationNs = -1.
        assertEquals(0L, VideoTiming.nextPresentationNs(previousNs = -1L, proposedNs = 0L))
    }

    @Test fun `an advancing clock passes straight through`() {
        assertEquals(2_000_000L, VideoTiming.nextPresentationNs(1_000L, 2_000_000L))
    }

    @Test fun `a repeated timestamp is nudged forward`() {
        // Two frames can land in the same bucket after the conflated queue
        // drops one. Equal is not "strictly increasing", so it has to move.
        assertEquals(
            1_000L + VideoTiming.MIN_FRAME_GAP_NS,
            VideoTiming.nextPresentationNs(1_000L, 1_000L),
        )
    }

    @Test fun `a backwards timestamp is nudged forward`() {
        assertEquals(
            5_000_000L + VideoTiming.MIN_FRAME_GAP_NS,
            VideoTiming.nextPresentationNs(5_000_000L, 4_000_000L),
        )
    }

    @Test fun `a pathological clock still yields a strictly increasing timeline`() {
        // Repeats, stalls and a jump backwards all in one sequence — whatever
        // comes out must never stall or regress, or the muxer throws mid-clip.
        val proposals = listOf(0L, 0L, 1L, 1L, 500L, 400L, 400L, 10_000_000L, 9_000_000L, 10_000_001L)
        var previous = -1L
        val timeline = proposals.map { proposed ->
            VideoTiming.nextPresentationNs(previous, proposed).also { previous = it }
        }
        timeline.zipWithNext { earlier, later ->
            assertTrue(later > earlier, "timeline stalled or regressed: $timeline")
        }
    }

    // ---- bitrate -----------------------------------------------------------

    @Test fun `bitrate tracks the frame area in the usual range`() {
        // 480² at 20 fps ≈ 2.3 Mbps, 720² ≈ 5.2 Mbps.
        assertEquals(2_304_000, VideoTiming.bitRate(480))
        assertEquals(5_184_000, VideoTiming.bitRate(720))
    }

    @Test fun `bitrate is clamped at both ends`() {
        // A small frame still deserves a usable bitrate...
        assertEquals(2_000_000, VideoTiming.bitRate(16))
        assertEquals(2_000_000, VideoTiming.bitRate(240))
        // ...and a large one can't ask for an absurd allocation.
        assertEquals(12_000_000, VideoTiming.bitRate(2_000))
    }

    @Test fun `bitrate does not overflow on an implausible frame size`() {
        // side² × 20 exceeds Int.MAX_VALUE well before 10 000², which is why
        // the arithmetic runs in Long before being clamped back down.
        assertEquals(12_000_000, VideoTiming.bitRate(10_000))
        assertTrue(VideoTiming.bitRate(100_000) > 0)
    }

    @Test fun `bitrate never leaves the clamp for any plausible side`() {
        for (side in 16..1_600 step 16) {
            val rate = VideoTiming.bitRate(side)
            assertTrue(rate in 2_000_000..12_000_000, "side=$side gave $rate")
        }
    }

    // ---- keeping or discarding a clip --------------------------------------

    @Test fun `a clip with no usable frames is discarded`() {
        assertFalse(VideoTiming.isWorthKeeping(0L))
        assertFalse(VideoTiming.isWorthKeeping(1L))
    }

    @Test fun `a clip with frames is kept`() {
        assertTrue(VideoTiming.isWorthKeeping(2L))
        assertTrue(VideoTiming.isWorthKeeping(600L))
    }
}
