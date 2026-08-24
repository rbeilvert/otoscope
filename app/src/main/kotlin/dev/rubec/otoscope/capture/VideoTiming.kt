package dev.rubec.otoscope.capture

/**
 * Encoder timing and sizing rules for a recording, kept apart from
 * [VideoRecorder] so they can be exercised without a MediaCodec.
 */
internal object VideoTiming {

    /**
     * Declared frame rate. Nominal only — real timestamps come from the EGL
     * presentation time, so a camera drifting between 12 and 25 fps still
     * yields a correctly-paced clip.
     */
    const val NOMINAL_FRAME_RATE = 20

    const val I_FRAME_INTERVAL_S = 1

    /** Smallest gap we'll force between two frames, when the clock hands us a
     *  timestamp that isn't ahead of the last one. */
    const val MIN_FRAME_GAP_NS = 1_000_000L

    /** Below this a clip is a mis-tap, not a recording. */
    const val MIN_FRAMES = 2L

    /**
     * ~0.5 bits per pixel per second: generous for a circle-masked frame whose
     * corners are flat black, and enough to stop the MJPEG source's sensor
     * noise from smearing into blocking artefacts.
     */
    fun bitRate(side: Int): Int =
        (side.toLong() * side * NOMINAL_FRAME_RATE / 2)
            .coerceIn(MIN_BIT_RATE, MAX_BIT_RATE)
            .toInt()

    /**
     * Timestamp to hand the encoder for the next frame. MediaMuxer rejects
     * samples whose presentation time doesn't strictly increase, and two frames
     * can land in the same bucket after the conflated queue drops one — so a
     * repeated or backwards [proposedNs] is nudged forward, not passed through.
     */
    fun nextPresentationNs(previousNs: Long, proposedNs: Long): Long =
        if (proposedNs <= previousNs) previousNs + MIN_FRAME_GAP_NS else proposedNs

    fun isWorthKeeping(framesEncoded: Long): Boolean = framesEncoded >= MIN_FRAMES

    private const val MIN_BIT_RATE = 2_000_000L
    private const val MAX_BIT_RATE = 12_000_000L
}
