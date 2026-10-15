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

    /** Updates [rotation] from a 2-axis accelerometer reading. Angle is
     *  `atan2(x, y)` in degrees, with 90° hard-coded for the axis-aligned
     *  (0, 1024) case the ne3-scope reference also special-cases. */
    fun setRotationFromInlineAccel(x: Int, y: Int) {
        _rotation.value = if (x == 0 && y == 1024) {
            90f
        } else {
            Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toFloat()
        }
    }
}
