package dev.rubec.otoscope.stream

import android.graphics.Bitmap
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A live, vendor-agnostic connection to one camera.
 *
 * The vendor-specific wire format lives in the implementation (`wudaopu/`,
 * `jegoat/`, ...). Everything visible above this interface is generic — the
 * ViewModel and UI never have to switch on which hardware is connected.
 *
 * Lifecycle: construct → [start] → collect flows → [close]. Flows are valid even
 * before `start` (collectors see initial / empty values).
 */
interface CameraSession {
    /** Decoded video frames as Bitmaps. */
    val frames: SharedFlow<Bitmap>

    /** Otoscope tip rotation in degrees, from an on-board accelerometer. Stays at
     *  0f for vendors that don't expose this. */
    val rotation: StateFlow<Float>

    /** Latest battery reading, null until the camera answers (or forever for
     *  vendors with no control channel implemented). */
    val battery: StateFlow<BatteryStatus?>

    /** Model name (e.g. "JesHome-P1"). Null until the camera answers. */
    val model: StateFlow<String?>

    /** Network/decode counters, useful for debug overlays. */
    val stats: StateFlow<SessionStats>

    /** Optional vendor-specific key/value lines (firmware version, resolution,
     *  Wi-Fi RSSI, …) for the in-app debug overlay. Empty map when there's
     *  nothing extra to surface. */
    val diagnostics: StateFlow<Map<String, String>>

    fun start()
    fun close()
}

/** Power state for the [CameraSession.battery] flow. */
data class BatteryStatus(val percent: Int, val charging: Boolean, val full: Boolean)

/** Snapshot of network and decoding counters, exposed via [CameraSession.stats]. */
data class SessionStats(
    val packetsReceived: Long = 0,
    val framesReceived: Long = 0,
    val framesDropped: Long = 0,
    val bytesReceived: Long = 0,
    val lastError: String? = null,
)
