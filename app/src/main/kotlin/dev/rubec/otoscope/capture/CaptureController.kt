package dev.rubec.otoscope.capture

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import dev.rubec.otoscope.debug.FileLog as Log
import dev.rubec.otoscope.stream.CameraSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/** What the capture bar's shutter button does when tapped. */
enum class CaptureMode { PHOTO, VIDEO }

/** One-shot outcome of a capture, surfaced to the user as a snackbar. */
sealed interface CaptureEvent {
    data class Saved(val uri: Uri, val isVideo: Boolean) : CaptureEvent
    data class Deleted(val name: String) : CaptureEvent
    data class Failed(val message: String) : CaptureEvent
}

/**
 * Owns everything between "user tapped the shutter" and "there's a file in the
 * gallery": frame composition, JPEG writing, H.264 recording, and the listing
 * the in-app gallery renders.
 *
 * Lives as long as the ViewModel rather than as long as a session, so the
 * gallery and the caption survive a reconnect; [attach] / [detach] track which
 * session, if any, is feeding frames.
 *
 * Encoding runs on one dedicated thread — MediaCodec and its EGL context are
 * thread-affine.
 */
class CaptureController(context: Context, private val scope: CoroutineScope) {

    private val store = CaptureStore(context)
    private val encoderExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "otoscope-encoder")
    }
    private val encoderDispatcher = encoderExecutor.asCoroutineDispatcher()

    private val _overlayText = MutableStateFlow("")
    /** Caption burnt into the bottom-left corner of every capture. */
    val overlayText: StateFlow<String> = _overlayText.asStateFlow()

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    private val _recordingElapsedMs = MutableStateFlow(0L)
    val recordingElapsedMs: StateFlow<Long> = _recordingElapsedMs.asStateFlow()

    private val _savingPhoto = MutableStateFlow(false)
    val savingPhoto: StateFlow<Boolean> = _savingPhoto.asStateFlow()

    private val _media = MutableStateFlow<List<CapturedMedia>>(emptyList())
    val media: StateFlow<List<CapturedMedia>> = _media.asStateFlow()

    private val _events = MutableSharedFlow<CaptureEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<CaptureEvent> = _events

    private var session: CameraSession? = null
    private var recordJob: Job? = null

    /** Bumped on every [startRecording] so a clip that is still muxing can tell
     *  whether it is still the current one before touching shared state. */
    private var recordGeneration = 0

    fun attach(session: CameraSession) {
        this.session = session
    }

    /** Called when the stream goes away for any reason. Stops (and keeps) an
     *  in-progress recording rather than losing it. */
    fun detach() {
        stopRecording()
        session = null
    }

    fun setOverlayText(text: String) {
        _overlayText.value = text
    }

    /**
     * Writes [frame] out as a JPEG. [frame] is the latest decoded frame as held
     * by the UI, so what lands on disk is exactly the moment the user saw when
     * they tapped.
     */
    fun capturePhoto(frame: Bitmap?) {
        val source = session
        if (frame == null || source == null) {
            _events.tryEmit(CaptureEvent.Failed("No frame to capture yet"))
            return
        }
        if (_savingPhoto.value) return
        val rotation = source.rotation.value
        val overlay = _overlayText.value
        _savingPhoto.value = true
        scope.launch(Dispatchers.Default) {
            try {
                val side = CaptureGeometry.cropSide(frame.width, frame.height)
                val still = FrameComposer.compose(frame, rotation, overlay, side)
                val uri = try {
                    withContext(Dispatchers.IO) { store.saveImage(still) }
                } finally {
                    still.recycle()
                }
                _events.tryEmit(CaptureEvent.Saved(uri, isVideo = false))
                refreshMedia()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "photo capture failed: $e")
                _events.tryEmit(CaptureEvent.Failed("Couldn't save the photo: ${e.message}"))
            } finally {
                _savingPhoto.value = false
            }
        }
    }

    fun toggleRecording() {
        if (_recording.value) stopRecording() else startRecording()
    }

    fun startRecording() {
        val source = session
        if (source == null) {
            _events.tryEmit(CaptureEvent.Failed("Not streaming"))
            return
        }
        if (_recording.value) return
        _recording.value = true
        _recordingElapsedMs.value = 0L

        // Wait for the previous clip to finish muxing before touching the
        // encoder thread again: two live EGL contexts on one thread don't mix.
        val previous = recordJob
        val generation = ++recordGeneration
        recordJob = scope.launch(encoderDispatcher) {
            previous?.join()

            val startNs = System.nanoTime()
            var pending: CaptureStore.PendingVideo? = null
            var recorder: VideoRecorder? = null
            var failure: String? = null

            val ticker = launch(Dispatchers.Default) {
                while (isActive) {
                    _recordingElapsedMs.value = (System.nanoTime() - startNs) / 1_000_000
                    delay(TICK_MS)
                }
            }

            // The session's frame buffer is shared with the live preview, and a
            // collector that lingers in it stalls emission to *everyone*. Drain
            // it from its own thread into a conflated channel instead: a device
            // that can't keep up drops recorded frames and leaves the preview
            // alone. (flowOn is a no-op on a SharedFlow, hence the channel.)
            val queue = Channel<Bitmap>(Channel.CONFLATED)
            val pump = launch(Dispatchers.Default) {
                source.frames.collect { queue.trySend(it) }
            }

            try {
                val video = store.createVideo()
                pending = video
                for (frame in queue) {
                    // Sized from the first frame, so a vendor whose resolution
                    // we don't know up front still records at its native size.
                    val active = recorder ?: VideoRecorder(
                        side = CaptureGeometry.videoSide(frame.width, frame.height),
                        fd = video.descriptor.fileDescriptor,
                    ).also {
                        recorder = it
                        Log.i(TAG, "recording ${it.side}x${it.side} to ${video.uri}")
                    }
                    val composed = FrameComposer.compose(
                        frame = frame,
                        rotationDegrees = source.rotation.value,
                        overlay = _overlayText.value,
                        side = active.side,
                    )
                    try {
                        active.encode(composed, System.nanoTime() - startNs)
                    } finally {
                        composed.recycle()
                    }
                }
            } catch (e: CancellationException) {
                // The normal stop path — stopRecording() cancels this job.
            } catch (e: Throwable) {
                Log.w(TAG, "recording failed: $e")
                failure = e.message ?: e.javaClass.simpleName
            } finally {
                ticker.cancel()
                pump.cancel()
                queue.close()
                // Muxing has to complete even though the job is cancelled, and
                // has to happen on this thread because the encoder lives here.
                withContext(NonCancellable) { finalise(pending, recorder, failure) }
                // Another recording may already have started while this one was
                // muxing; leave its state alone.
                if (generation == recordGeneration) {
                    _recording.value = false
                    _recordingElapsedMs.value = 0L
                }
            }
        }
    }

    fun stopRecording() {
        val job = recordJob ?: return
        if (!_recording.value) return
        // Flip the flag here rather than waiting for the job: the shutter should
        // snap back the instant it's tapped, and finalising takes a moment.
        _recording.value = false
        job.cancel()
    }

    private fun finalise(
        pending: CaptureStore.PendingVideo?,
        recorder: VideoRecorder?,
        error: String?,
    ) {
        if (pending == null) {
            _events.tryEmit(CaptureEvent.Failed(error ?: "Couldn't start recording"))
            return
        }
        val frames = try {
            recorder?.finish()
            recorder?.framesEncoded ?: 0L
        } catch (e: Throwable) {
            Log.w(TAG, "muxer finalisation failed: $e")
            0L
        }
        runCatching { pending.descriptor.close() }

        if (error != null || !VideoTiming.isWorthKeeping(frames)) {
            // MediaMuxer may not even have written a header — drop the row
            // rather than leave an unplayable entry in the user's gallery.
            store.discard(pending.uri)
            _events.tryEmit(
                CaptureEvent.Failed(error ?: "Recording was too short to save")
            )
        } else {
            store.publish(pending.uri)
            Log.i(TAG, "saved $frames frames to ${pending.uri}")
            _events.tryEmit(CaptureEvent.Saved(pending.uri, isVideo = true))
            refreshMedia()
        }
    }

    fun refreshMedia() {
        scope.launch(Dispatchers.IO) { reloadMedia() }
    }

    fun delete(media: CapturedMedia) {
        scope.launch(Dispatchers.IO) {
            if (store.delete(media.uri)) {
                _events.tryEmit(CaptureEvent.Deleted(media.name))
            } else {
                _events.tryEmit(CaptureEvent.Failed("Couldn't delete ${media.name}"))
            }
            reloadMedia()
        }
    }

    /** Keeps the previous listing on failure: an empty gallery would read as
     *  "your captures are gone" rather than "the query failed". */
    private fun reloadMedia() {
        _media.value = runCatching { store.list() }
            .onFailure { Log.w(TAG, "gallery listing failed: $it") }
            .getOrDefault(_media.value)
    }

    /** Releases the encoder thread. Called from `ViewModel.onCleared`, which
     *  can land while a clip is still being muxed — shutting the executor down
     *  then would reject the finaliser's continuation and lose the file, so we
     *  wait the recording out first. */
    fun close() {
        detach()
        val inFlight = recordJob
        if (inFlight == null) {
            encoderExecutor.shutdown()
        } else {
            inFlight.invokeOnCompletion { encoderExecutor.shutdown() }
        }
    }

    private companion object {
        const val TAG = "CaptureController"
        const val TICK_MS = 200L
    }
}
