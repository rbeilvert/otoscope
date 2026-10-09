package dev.rubec.otoscope.stream.ne3

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Decodes the NE3 family's sensor + shutter wire format into rotation and
 * button events.
 *
 * Two transports feed the same frame shape and are both wired to this
 * parser so a single [rotation] / [shutter] stream drives the UI regardless
 * of which channel the firmware revision happens to speak:
 *  - [Ne3ControlClient] on TCP/2271 (older HND firmwares, Xylla-style
 *    sensor channel).
 *  - [Ne3VideoClient] on UDP/8800 msg_type=4 (newer HND firmwares, same
 *    port as video; wire format verified against the vendor's native
 *    `W0/b.f` parser in `libbl_vii_jni.so`).
 *
 * Frame layout inside the input bytes (one or more frames per feed call,
 * variable length, delimited by an `0xFF` marker byte):
 *
 * ```
 *   offset  size  field
 *   ------  ----  -----
 *      0     u8   marker 0xFF
 *      1     u8   payload length (bytes after the marker + length pair)
 *      2     u8   device type — 85 or 87 = accelerometer,
 *                 86 = physical shutter button
 *      3     u8   flag (unknown; ignored)
 *   depending on device type:
 *      dt=85 (2-axis):  X hi @ 5, X lo @ 6, Z hi @ 9, Z lo @ 10
 *                       angle = atan2(X, Z) * 180 / π
 *      dt=87 (3-axis):  X hi @ 5, X lo @ 6,
 *                       Y hi @ 7, Y lo @ 8,
 *                       Z hi @ 9, Z lo @ 10
 *                       angle = atan2(Y, Z) * 180 / π + 90
 *      dt=86 (shutter): shutter code = byte @ 4
 *                       (0x90 = press, 0x91 = release in the vendor UI)
 * ```
 *
 * Anti-jitter mirrors the vendor app: for the 3-axis path, if both `|Y|`
 * and `|Z|` stay under [MOVEMENT_THRESHOLD] for [STILL_SAMPLES] readings
 * in a row we treat the device as stationary and freeze the reported
 * angle at 0 (the atan2 result becomes unstable at near-zero magnitudes;
 * this dampens the flicker the vendor also suppressed).
 *
 * Battery is NOT emitted — the HND vendor app ships no battery widget and
 * no code path in its native lib or smali reads a battery value from any
 * channel. The parser only reports what the hardware actually sends.
 */
internal class Ne3SensorParser {

    private val _rotation = MutableStateFlow(0f)
    val rotation: StateFlow<Float> = _rotation.asStateFlow()

    /** Non-null once we've seen a frame — the device-type byte it carried.
     *  Useful in the debug overlay for confirming which hardware variant is
     *  on the other end. */
    private val _deviceType = MutableStateFlow<Int?>(null)
    val deviceType: StateFlow<Int?> = _deviceType.asStateFlow()

    /** Physical shutter-button events (dev_type=86). The raw byte at
     *  frame-offset 4 is surfaced so a UI layer can map 0x90/0x91 to
     *  press/release — the vendor's native lib does the mapping in Java
     *  rather than in the parser, and we follow suit. */
    private val _shutter = MutableSharedFlow<Int>(replay = 0, extraBufferCapacity = 4)
    val shutter: SharedFlow<Int> = _shutter.asSharedFlow()

    // Anti-jitter state — see class KDoc.
    private var stillStreak = 0
    private var isStill = false

    /** Feed [len] bytes from [buf] into the parser. Walks the buffer looking
     *  for `0xFF <len> <dt>` frames and emits updates on [rotation] /
     *  [shutter] as each one lands. Returns the count of accelerometer
     *  samples emitted (0 or 1 in practice — the vendor app only ever finds
     *  one per packet). */
    fun feed(buf: ByteArray, len: Int): Int {
        var i = 0
        var emitted = 0
        while (i < len) {
            if ((buf[i].toInt() and 0xff) != 0xFF) { i++; continue }
            // Need at least marker + length + dev_type + flag + one field.
            if (i + 6 > len) break
            val payloadLen = buf[i + 1].toInt() and 0xff
            // The reported length is the payload — the frame itself needs
            // 2 more bytes on top for the marker + length. Bounds check
            // matches the vendor's `payloadLen + 2` guard.
            if (i + 2 + payloadLen > len) break
            val devType = buf[i + 2].toInt() and 0xff
            _deviceType.value = devType

            when (devType) {
                DEV_TYPE_ACCEL_3AXIS -> {
                    if (i + 11 > len) break
                    val y = readS16BE(buf, i + 7)
                    val z = readS16BE(buf, i + 9)
                    _rotation.value = updateAngle3Axis(y, z)
                    emitted++
                    i += 12
                }
                DEV_TYPE_ACCEL_2AXIS -> {
                    if (i + 11 > len) break
                    val x = readS16BE(buf, i + 5)
                    val z = readS16BE(buf, i + 9)
                    _rotation.value = updateAngle2Axis(x, z)
                    emitted++
                    i += 12
                }
                DEV_TYPE_SHUTTER -> {
                    val code = buf[i + 4].toInt() and 0xff
                    _shutter.tryEmit(code)
                    i += 6
                }
                else -> {
                    // Unknown dev_type — the device-type StateFlow still
                    // reports it so a caller / debug overlay can surface
                    // it, but we advance past the frame rather than drop
                    // into a byte-scan loop that would hit the same marker
                    // forever.
                    i += 2 + payloadLen
                }
            }
        }
        return emitted
    }

    /** Current anti-jitter state — exposed only for log diagnostics. */
    val isStillLatched: Boolean get() = isStill

    private fun updateAngle3Axis(y: Int, z: Int): Float {
        val moving = abs(y) >= MOVEMENT_THRESHOLD || abs(z) >= MOVEMENT_THRESHOLD
        if (!isStill && !moving) {
            if (++stillStreak >= STILL_SAMPLES) { isStill = true; stillStreak = 0 }
        } else if (isStill && moving) {
            if (++stillStreak >= STILL_SAMPLES) { isStill = false; stillStreak = 0 }
        } else {
            stillStreak = 0
        }
        if (isStill) return 0f
        return Math.toDegrees(atan2(y.toDouble(), z.toDouble())).toFloat() + 90f
    }

    private fun updateAngle2Axis(x: Int, z: Int): Float =
        Math.toDegrees(atan2(x.toDouble(), z.toDouble())).toFloat()

    private fun readS16BE(b: ByteArray, off: Int): Int {
        val hi = b[off].toInt() and 0xff
        val lo = b[off + 1].toInt() and 0xff
        val u16 = (hi shl 8) or lo
        return if (u16 and 0x8000 != 0) u16 - 0x10000 else u16
    }

    companion object {
        // Device-type values seen on the wire; mirrored from the vendor's
        // S0/b predicates and PreviewActivity$g shutter dispatcher.
        const val DEV_TYPE_ACCEL_2AXIS = 85
        const val DEV_TYPE_SHUTTER = 86
        const val DEV_TYPE_ACCEL_3AXIS = 87

        // Shutter codes the vendor UI maps to press / release.
        const val SHUTTER_CODE_PRESS = 0x90
        const val SHUTTER_CODE_RELEASE = 0x91

        // Anti-jitter, matching the vendor's numeric thresholds.
        private const val MOVEMENT_THRESHOLD = 200
        private const val STILL_SAMPLES = 40
    }
}
