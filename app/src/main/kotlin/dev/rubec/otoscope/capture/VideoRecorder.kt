package dev.rubec.otoscope.capture

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.FileDescriptor
import android.os.SystemClock

/**
 * H.264-in-MP4 encoder for composed otoscope frames.
 *
 * Surface input via [EglBitmapRenderer], not ByteBuffer input: a hand-rolled
 * ARGB→YUV420 conversion means guessing each encoder's colour format and
 * stride, and guessing wrong yields a silently green video rather than a throw.
 *
 * Video only — the otoscope has no microphone, so no RECORD_AUDIO. Thread-affine
 * like the renderer it owns.
 */
internal class VideoRecorder(
    /** Edge length of the square frames this instance accepts. */
    val side: Int,
    fd: FileDescriptor,
) {
    private val codec = MediaCodec.createEncoderByType(MIME_TYPE)
    private val muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val renderer: EglBitmapRenderer
    private val bufferInfo = MediaCodec.BufferInfo()

    private var trackIndex = -1
    private var muxing = false
    private var released = false
    private var lastPresentationNs = -1L

    /** Frames actually handed to the encoder. Zero means the recording has no
     *  content and the caller should discard the file rather than publish it. */
    var framesEncoded = 0L
        private set

    init {
        val format = MediaFormat.createVideoFormat(MIME_TYPE, side, side).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, VideoTiming.bitRate(side))
            setInteger(MediaFormat.KEY_FRAME_RATE, VideoTiming.NOMINAL_FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VideoTiming.I_FRAME_INTERVAL_S)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        renderer = try {
            EglBitmapRenderer(codec.createInputSurface(), side, side)
        } catch (e: Throwable) {
            runCatching { codec.release() }
            runCatching { muxer.release() }
            throw e
        }
        codec.start()
    }

    /**
     * @param frame a [side]×[side] bitmap from [FrameComposer].
     * @param presentationNs offset from the start of the recording. Nudged
     *   forward if it isn't strictly ahead of the previous frame — MediaMuxer
     *   rejects non-monotonic sample timestamps.
     */
    fun encode(frame: Bitmap, presentationNs: Long) {
        check(!released) { "recorder already finished" }
        drain(endOfStream = false)
        val pts = VideoTiming.nextPresentationNs(lastPresentationNs, presentationNs)
        lastPresentationNs = pts
        renderer.draw(frame, pts)
        framesEncoded++
    }

    /** Flushes the encoder and finalises the MP4. Safe to call after a failed
     *  [encode]; always paired with the file being published or deleted. */
    fun finish() {
        if (released) return
        try {
            codec.signalEndOfInputStream()
            drain(endOfStream = true)
        } finally {
            release()
        }
    }

    private fun release() {
        if (released) return
        released = true
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { renderer.release() }
        if (muxing) runCatching { muxer.stop() }
        runCatching { muxer.release() }
    }

    private fun drain(endOfStream: Boolean) {
        val deadline = SystemClock.elapsedRealtime() + DRAIN_DEADLINE_MS
        while (true) {
            val index = codec.dequeueOutputBuffer(
                bufferInfo,
                if (endOfStream) DRAIN_TIMEOUT_US else 0L,
            )
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    // Some encoders take a beat to emit EOS. Bail rather than
                    // wedging the encoder thread if it never shows up.
                    if (SystemClock.elapsedRealtime() > deadline) return
                }

                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // Fires exactly once, before the first sample, and carries
                    // the csd-0/csd-1 the muxer needs for the track.
                    if (!muxing) {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxing = true
                    }
                }

                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)
                    val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (muxing && buffer != null && bufferInfo.size > 0 && !isConfig) {
                        buffer.position(bufferInfo.offset)
                        buffer.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, buffer, bufferInfo)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private companion object {
        const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
        const val DRAIN_TIMEOUT_US = 10_000L
        const val DRAIN_DEADLINE_MS = 2_000L
    }
}
