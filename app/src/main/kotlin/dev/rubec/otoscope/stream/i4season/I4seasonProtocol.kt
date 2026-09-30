package dev.rubec.otoscope.stream.i4season

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Control-channel codec for i4season cameras, reverse-engineered from the
 * Soulear app's `libWifiCamera.so`.
 *
 * Every control datagram (both directions) starts with a 12-byte
 * little-endian header:
 * ```
 *   offset  size  field
 *   ------  ----  -----
 *      0     u32  magic 0xFFEEFFEE
 *      4     u16  sequence number; the reply echoes it
 *      6     u16  command
 *      8     u8   0x01 in requests
 *      9     u8   status in replies, 0 = OK
 *     10     u16  payload length
 *     12..   …    payload
 * ```
 * Commands used here:
 *  - `0x0001` device info, to UDP/10005. The reply payload is 0x80 bytes, see [parseDevInfo].
 *  - `0x0002` licence info, to UDP/10005. The vendor app checks it locally;
 *    the camera streams regardless. We still send it once to mirror the vendor's sequence.
 *  - `0x0004` open video, to UDP/10006. Payload `u16 picPort, u16 audioPort, u32 clientId`.
 *    The camera then streams to `picPort` for a while; the vendor app re-sends
 *    it every second while no video arrives, which doubles as the keepalive.
 *  - `0x000A` LED, to UDP/10005. Payload `u8 op, u8 status, u8 brightness`; `op`
 *    is the LED id (1 = camera ring light) with `0x10` set for "write". The reply
 *    carries the resulting state in the same layout. Status 0 = off, 1 = on
 *    (2 = blink, 3 = breathe also exist). The camera stores and echoes any
 *    brightness 0..100, but the Find T's LED doesn't dim, and the vendor app
 *    only ever writes 100 or 0, so we do the same.
 */
internal object I4seasonProtocol {
    const val MAGIC = 0xFFEEFFEE.toInt()
    const val HEADER_SIZE = 12

    const val CMD_PORT = 10005
    const val VIDEO_CMD_PORT = 10006

    const val CMD_DEVINFO = 0x0001
    const val CMD_LICGET = 0x0002
    const val CMD_OPEN_VIDEO = 0x0004
    const val CMD_LED = 0x000A

    private const val LED_CAMERA = 1
    private const val LED_WRITE = 0x10

    private const val DEVINFO_SIZE = 0x80

    fun request(seq: Int, cmd: Int, payload: ByteArray = ByteArray(0)): ByteArray =
        ByteBuffer.allocate(HEADER_SIZE + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(MAGIC)
            .putShort(seq.toShort())
            .putShort(cmd.toShort())
            .put(1)
            .put(0)
            .putShort(payload.size.toShort())
            .put(payload)
            .array()

    fun openVideoPayload(picPort: Int, clientId: Int): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(picPort.toShort())
            .putShort(0) // no audio
            .putInt(clientId)
            .array()

    fun ledReadPayload(): ByteArray = byteArrayOf(LED_CAMERA.toByte(), 0, 0)

    fun ledWritePayload(on: Boolean): ByteArray =
        byteArrayOf((LED_WRITE or LED_CAMERA).toByte(), if (on) 1 else 0, if (on) 100 else 0)

    data class LedState(val on: Boolean, val brightness: Int)

    fun parseLed(payload: ByteArray): LedState? {
        if (payload.size < 3) return null
        return LedState(on = payload[1].toInt() != 0, brightness = payload[2].toInt() and 0xff)
    }

    class Reply(val seq: Int, val cmd: Int, val status: Int, val payload: ByteArray)

    /** Returns null for anything that isn't a well-formed control datagram. */
    fun parseReply(buf: ByteArray, len: Int): Reply? {
        if (len < HEADER_SIZE) return null
        val bb = ByteBuffer.wrap(buf, 0, len).order(ByteOrder.LITTLE_ENDIAN)
        if (bb.getInt(0) != MAGIC) return null
        return Reply(
            seq = bb.getShort(4).toInt() and 0xffff,
            cmd = bb.getShort(6).toInt() and 0xffff,
            status = buf[9].toInt() and 0xff,
            payload = buf.copyOfRange(HEADER_SIZE, len),
        )
    }

    data class DevInfo(
        val vendor: String,
        val product: String,
        val firmware: String,
        val ssid: String,
        val batteryPercent: Int,
    )

    /**
     * Device-info reply payload:
     * ```
     *   0x01  char[32] vendor        ("YPC" on the Find T)
     *   0x21  char[32] product       ("BK7231U-XRH-FBPRO")
     *   0x41  char[16] firmware      ("HKV41B")
     *   0x51  char[32] ssid
     *   0x71  u8[6]    mac
     *   0x77  u16      status; bits 9..15 = battery percent
     *   0x7C  u8       feature flags (not needed for streaming)
     * ```
     */
    fun parseDevInfo(payload: ByteArray): DevInfo? {
        if (payload.size < DEVINFO_SIZE) return null
        val status = (payload[0x77].toInt() and 0xff) or ((payload[0x78].toInt() and 0xff) shl 8)
        return DevInfo(
            vendor = cString(payload, 0x01, 32),
            product = cString(payload, 0x21, 32),
            firmware = cString(payload, 0x41, 16),
            ssid = cString(payload, 0x51, 32),
            batteryPercent = (status shr 9).coerceIn(0, 100),
        )
    }

    /** The vendor app adds a fixed 180° for these products (the Find T reports
     *  `BK7231U-XRH-FBPRO`); their sensor is mounted upside down relative to
     *  the lens. */
    fun mountOffsetDegrees(product: String): Float =
        if (product.contains("FBPRO") || product.contains("R1")) 180f else 0f

    /**
     * Roll angle in degrees [0, 360) from the packed accelerometer sample in
     * video header bytes 6..9. Each axis is a 9-bit magnitude plus a separate
     * sign bit, about 130 counts per g:
     * ```
     *   x = bits 20..28, sign bit 29   (probe long axis)
     *   y = bits 10..18, sign bit 19
     *   z = bits  0..8,  sign bit  9
     * ```
     * The vendor computes `atan(y / z)` and fixes the quadrant from the sign
     * bits, which is `atan2(y, z)`. Returns null when the probe points
     * straight up or down (y = z = 0) and roll is undefined.
     */
    fun rollDegrees(g: Int): Float? {
        val y = axis(g, shift = 10, signBit = 19)
        val z = axis(g, shift = 0, signBit = 9)
        if (y == 0 && z == 0) return null
        val deg = Math.toDegrees(atan2(y.toDouble(), z.toDouble())).toFloat()
        return (deg + 360f) % 360f
    }

    /** Tilt of the probe's long axis against the horizontal, in degrees. Only
     *  used for diagnostics. */
    fun slopeDegrees(g: Int): Float {
        val x = axis(g, shift = 20, signBit = 29)
        val y = axis(g, shift = 10, signBit = 19)
        val z = axis(g, shift = 0, signBit = 9)
        return Math.toDegrees(atan2(x.toDouble(), hypot(y.toDouble(), z.toDouble()))).toFloat()
    }

    private fun axis(g: Int, shift: Int, signBit: Int): Int {
        val magnitude = (g ushr shift) and 0x1ff
        return if ((g ushr signBit) and 1 == 1) -magnitude else magnitude
    }

    private fun cString(buf: ByteArray, offset: Int, max: Int): String {
        var end = offset
        while (end < offset + max && buf[end] != 0.toByte()) end++
        return String(buf, offset, end - offset, Charsets.ISO_8859_1).trim()
    }
}
