package dev.rubec.otoscope.stream.beken

import org.json.JSONObject

/**
 * Wire constants and small parsers for the HND NE3 family built on a Beken
 * BK7231U ("TAIXIN MJPEG"). Reverse-engineered from the HND app
 * (`com.xiaozhen.beauty.hnd`, class `T0.d`) and checked against live traffic.
 *
 * The camera is an access point at `192.168.1.1` with three UDP ports. It
 * answers to the source port a request came from, and the vendor app binds
 * the video and sensor sockets to the same numbers it sends to, so we do too:
 *
 *  | port  | request       | reply                                          |
 *  |-------|---------------|------------------------------------------------|
 *  | 46526 | `66 30 01 01` | device info, a JSON object                     |
 *  | 52219 | `86 06 01`    | starts 24-byte sensor packets (~12 per second) |
 *  | 44506 | `20 36`       | starts the video; `20 37` stops it             |
 *
 * Not to be confused with the Bouffalo NE3 ([dev.rubec.otoscope.stream.ne3]),
 * which shares the `HNDEC_` SSID prefix but lives at `192.168.169.1`, speaks
 * UDP/8800 and has nothing listening on the ports above.
 */
internal object BekenProtocol {
    const val INFO_PORT = 46526
    const val VIDEO_PORT = 44506
    const val SENSOR_PORT = 52219

    val CMD_INFO = byteArrayOf(0x66, 0x30, 0x01, 0x01)
    val CMD_VIDEO_START = byteArrayOf(0x20, 0x36)
    val CMD_VIDEO_STOP = byteArrayOf(0x20, 0x37)
    val CMD_SENSOR_START = byteArrayOf(0x86.toByte(), 0x06, 0x01)

    /** Counts per g of the camera's accelerometer (a still camera reads ~63). */
    const val COUNTS_PER_G = 64

    /** In-plane readings below this mean the lens points straight up or down:
     *  the roll angle is noise there, so the last angle is kept. */
    const val FLAT_THRESHOLD = 8

    /** Accelerometer axes `(x, y, z)` of a 24-byte sensor packet: signed 8-bit
     *  at offsets 0, 2 and 4 (the bytes between them are noise). `x` runs along
     *  the lens axis, `y` and `z` lie in the picture plane. Null for runts. */
    fun parseSensor(packet: ByteArray, length: Int = packet.size): Triple<Int, Int, Int>? {
        if (length < 6) return null
        return Triple(packet[0].toInt(), packet[2].toInt(), packet[4].toInt())
    }

    /** The roll angle the camera computes itself, degrees in [-360, 0): signed
     *  16-bit big endian at offset 18. Null for short packets. */
    fun parseAngle(packet: ByteArray, length: Int = packet.size): Int? {
        if (length < 20) return null
        return ((packet[18].toInt() shl 8) or (packet[19].toInt() and 0xff)).toShort().toInt()
    }

    /** Picture rotation in degrees from the camera's own roll [angle], passed
     *  through unchanged. Checked on a real camera: an extra half turn put the
     *  picture upside down. See docs/beken-protocol.md. */
    fun rotationDegrees(angle: Int): Float = (((angle % 360) + 360) % 360).toFloat()

    /** The device-info JSON: `brand` is the model shown to the user, the rest
     *  goes to diagnostics. Null when [data] isn't the camera's JSON. */
    fun parseInfo(data: ByteArray, length: Int = data.size): JSONObject? {
        val end = (0 until length).firstOrNull { data[it].toInt() == 0 } ?: length
        return try {
            JSONObject(String(data, 0, end, Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }
}
