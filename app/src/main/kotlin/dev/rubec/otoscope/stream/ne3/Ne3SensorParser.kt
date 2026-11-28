package dev.rubec.otoscope.stream.ne3

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.atan2

/**
 * Rotation state for an NE3 session. [Ne3VideoClient] feeds the per-frame
 * accelerometer reading [Ne3FrameAssembler] lifts off chunk 0 into
 * [setRotationFromInlineAccel]; [rotation] is read by the UI's auto-rotate.
 *
 * This hardware family exposes no battery readout.
 */
internal class Ne3SensorParser {

    private val _rotation = MutableStateFlow(0f)
    val rotation: StateFlow<Float> = _rotation.asStateFlow()

    /** Updates [rotation] (clockwise degrees) from a 2-axis accelerometer
     *  reading: `atan2(x, y) + 90°`. The ne3-scope reference rotates its
     *  OpenCV view by `-(angle) - 90` counter-clockwise, which is the same
     *  turn. The axis-aligned (0, 1024) reading uses a hard-coded 90° for
     *  `atan2`, as the reference does. */
    fun setRotationFromInlineAccel(x: Int, y: Int) {
        val angle = if (x == 0 && y == 1024) {
            90.0
        } else {
            Math.toDegrees(atan2(x.toDouble(), y.toDouble()))
        }
        _rotation.value = (angle + MOUNT_OFFSET_DEG).toFloat()
    }

    private companion object {
        /** Lens orientation relative to the accelerometer axes. */
        const val MOUNT_OFFSET_DEG = 90.0
    }
}
