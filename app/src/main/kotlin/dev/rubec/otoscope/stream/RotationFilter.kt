package dev.rubec.otoscope.stream

import kotlin.math.atan
import kotlin.math.sqrt

/**
 * Translates the camera's packed accelerometer reading into a rotation angle
 * (degrees), with the same jitter-suppression strategy as the original AIR-Look
 * app (StreamSelf.doExecuteMJPEG): only update the angle once the camera has
 * actually moved (std-dev > 3 on any axis over the last 20 samples). When the
 * camera sits still the last computed angle is held — that prevents a steady
 * hand from causing the image to drift while watching it.
 */
class RotationFilter {

    private val xs = ArrayDeque<Int>()
    private val ys = ArrayDeque<Int>()
    private val zs = ArrayDeque<Int>()
    private var lastAngle = 0f

    fun update(accelerometer: Int): Float {
        val regX = (accelerometer ushr 20) and 0x3FF
        val regY = (accelerometer ushr 10) and 0x3FF
        val regZ = accelerometer and 0x3FF

        // Each axis is 0..1023 with 512 as zero. Mirror values >= 512 around 512
        // so we end up with a 0..512 magnitude — directly matches the original.
        val magX = if (regX >= 512) 1024 - regX else regX
        val magY = if (regY >= 512) 1024 - regY else regY
        val magZ = if (regZ >= 512) 1024 - regZ else regZ

        push(xs, magX); push(ys, magY); push(zs, magZ)

        if (xs.size < WINDOW) return lastAngle
        val moving = stddev(xs) > 3f || stddev(ys) > 3f || stddev(zs) > 3f
        if (!moving) return lastAngle

        if (magZ == 0) return lastAngle

        var theta = atan(magY.toDouble() / magZ.toDouble())
        if (regZ > 512) theta = Math.PI - theta
        if (regY > 512) theta = 2.0 * Math.PI - theta

        // The original app's rotation is in screen-clockwise degrees, but on this
        // hardware the lens is mounted 180° relative to that frame of reference,
        // so the corrected image was coming out upside-down. Adding π brings it
        // back upright; the modulo keeps the resulting angle in [0, 360°).
        theta = (theta + Math.PI) % (2.0 * Math.PI)

        lastAngle = Math.toDegrees(theta).toFloat()
        return lastAngle
    }

    private fun push(buf: ArrayDeque<Int>, v: Int) {
        buf.addLast(v)
        if (buf.size > WINDOW) buf.removeFirst()
    }

    private fun stddev(samples: ArrayDeque<Int>): Float {
        val mean = samples.sum().toDouble() / samples.size
        var sq = 0.0
        for (s in samples) {
            val d = s - mean
            sq += d * d
        }
        return sqrt(sq / samples.size).toFloat()
    }

    companion object {
        private const val WINDOW = 20
    }
}
