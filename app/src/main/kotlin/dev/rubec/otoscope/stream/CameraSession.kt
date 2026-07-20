package dev.rubec.otoscope.stream

import android.graphics.Bitmap
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A live, vendor-agnostic connection to one camera.
 *
 * The vendor-specific wire format lives in the implementation (`wudaopu/`,
 * `jegoat/`, ...). Everything visible above this interface is generic.
 * The ViewModel and UI never have to switch on which hardware is connected.
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

    /** Non-null when the session has hit an unrecoverable error and will never
     *  produce frames (e.g. the OS refused to bind our UDP sockets to the
     *  camera's Wi-Fi network: a symptom of an active VPN with lockdown mode).
     *  The value is a stable code, see [TerminalErrors]. The ViewModel maps it
     *  to a user-facing message and tears the session down. */
    val terminalError: StateFlow<String?>

    fun start()
    fun close()
}

/** Well-known values for [CameraSession.terminalError]. */
object TerminalErrors {
    /** `Network.bindSocket()` returned EPERM (or otherwise failed): the OS
     *  refused to let us send traffic on the camera's Wi-Fi. Probably
     *  caused by an "always-on with lockdown" VPN on the phone. */
    const val NETWORK_BIND_FORBIDDEN = "network_bind_forbidden"

    /** Socket setup succeeded but no video frames arrive within a reasonable
     *  window (~15 s). This happens when an active VPN routes outbound traffic
     *  away from the camera Wi-Fi even though our bind attempt succeeded.
     *  The kernel accepts the source-IP bind but Android's egress filtering still
     *  drops the packets. Detected by the ViewModel's first-packet watchdog. */
    const val CAMERA_UNREACHABLE = "camera_unreachable"
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
