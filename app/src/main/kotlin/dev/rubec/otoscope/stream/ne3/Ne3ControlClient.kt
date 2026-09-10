package dev.rubec.otoscope.stream.ne3

import android.net.Network
import dev.rubec.otoscope.debug.FileLog as Log
import dev.rubec.otoscope.stream.toHex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory
import kotlin.math.abs
import kotlin.math.atan2

/**
 * NE3 (HND family) sensor + button channel on TCP/2271.
 *
 * The camera opens a bidirectional TCP socket and pushes 38-byte packets
 * carrying accelerometer readings and, on some device variants, physical
 * shutter-button events. Reverse-engineered from the vendor app's
 * `Z0.a` (`GyroDataServer`) and `W0.b` (parser).
 *
 * Wire format inside each packet — one or more frames of variable length,
 * delimited by an `0xFF` marker byte:
 *
 * ```
 *   offset  size  field
 *   ------  ----  -----
 *      0     u8   marker 0xFF
 *      1     u8   payload length (frame content bytes; does not include
 *                 the two bytes of marker + length itself)
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
 * ```
 *
 * Anti-jitter mirrors the vendor app: for the 3-axis path, if both `|Y|`
 * and `|Z|` stay under [MOVEMENT_THRESHOLD] for [STILL_SAMPLES] readings
 * in a row we treat the device as stationary and freeze the reported
 * angle at 0 (the atan2 result becomes unstable at near-zero magnitudes;
 * this dampens the flicker the vendor also suppressed).
 *
 * On connect we send a 6-byte init blob (`FF 03 FE FB FA FF`) to trigger
 * the camera into pushing sensor data — some firmware waits for it.
 */
internal class Ne3ControlClient(
    private val cameraIp: String,
    private val network: Network?,
    private val port: Int = 2271,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: Socket? = null
    private var runJob: Job? = null

    private val _rotation = MutableStateFlow(0f)
    val rotation: StateFlow<Float> = _rotation.asStateFlow()

    /** Non-null once the camera has revealed its device type (85/86/87).
     *  Useful in the debug overlay for confirming which flavour of hardware
     *  is on the other end of the socket. */
    private val _deviceType = MutableStateFlow<Int?>(null)
    val deviceType: StateFlow<Int?> = _deviceType.asStateFlow()

    /** Physical shutter-button events (dev_type=86). We surface the raw
     *  code so a UI layer can eventually bind it to photo/video capture;
     *  for now the client just logs it. */
    private val _shutter = MutableSharedFlow<Int>(replay = 0, extraBufferCapacity = 4)
    val shutter: SharedFlow<Int> = _shutter.asSharedFlow()

    // Anti-jitter state — see class KDoc.
    private var stillStreak = 0
    private var isStill = false

    fun start() {
        if (runJob != null) return
        runJob = scope.launch {
            try {
                runControl()
            } catch (e: Exception) {
                Log.e(TAG, "control loop crashed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun runControl() {
        val sf: SocketFactory = network?.socketFactory ?: SocketFactory.getDefault()
        val sock = sf.createSocket().also { socket = it }
        try {
            sock.connect(InetSocketAddress(cameraIp, port), CONNECT_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.w(TAG, "connect $cameraIp:$port failed: ${e.message}")
            return
        }
        sock.tcpNoDelay = true
        sock.keepAlive = true
        // Match the vendor app's tiny buffers — a Ne3 gyro packet is only
        // 38 bytes and we don't want to sit on stale data.
        sock.sendBufferSize = 44
        sock.receiveBufferSize = 44
        Log.i(TAG, "connected $cameraIp:$port localPort=${sock.localPort}")

        val out = sock.getOutputStream()
        // Init handshake — same 6 bytes the vendor app pushes on connect.
        runCatching {
            out.write(INIT_HANDSHAKE)
            out.flush()
        }.onFailure { Log.w(TAG, "init handshake send failed: ${it.message}") }

        val input = sock.getInputStream()
        val buf = ByteArray(64)
        var packetsSeen = 0L
        var samplesEmitted = 0L
        var lastReportMs = System.currentTimeMillis()

        while (scope.isActive && !sock.isClosed) {
            val n = try {
                input.read(buf, 0, buf.size)
            } catch (e: Exception) {
                if (scope.isActive) Log.w(TAG, "read failed: ${e.message}")
                break
            }
            if (n < 0) {
                Log.i(TAG, "peer closed control socket")
                break
            }
            if (n == 0) continue
            packetsSeen++

            // Only log the very first packet — after that the summary line
            // below handles ongoing visibility without spam.
            if (packetsSeen == 1L) {
                Log.i(TAG, "first packet $n bytes: ${buf.toHex(0, n.coerceAtMost(38))}")
            }

            samplesEmitted += parsePacket(buf, n)

            val now = System.currentTimeMillis()
            val elapsed = now - lastReportMs
            if (elapsed >= REPORT_INTERVAL_MS) {
                val hz = samplesEmitted * 1000.0 / elapsed
                Log.i(
                    TAG,
                    "telemetry: $packetsSeen packets, $samplesEmitted samples in ${elapsed}ms " +
                        "(%.1f Hz), devType=${_deviceType.value}, angle=%.1f° still=$isStill".format(hz, _rotation.value),
                )
                packetsSeen = 0
                samplesEmitted = 0
                lastReportMs = now
            }
        }
    }

    /** Walk the 38-byte packet looking for `0xFF <len> <dt>` frames.
     *  Returns the count of accelerometer samples emitted (0 or 1 in
     *  practice — the vendor app only ever finds one frame per packet). */
    private fun parsePacket(buf: ByteArray, len: Int): Int {
        var i = 0
        var emitted = 0
        while (i < len) {
            // Skip until an FF marker byte.
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
                    // dt=87: three int16 BE fields — X, Y, Z.
                    if (i + 11 > len) break
                    val x = readS16BE(buf, i + 5)
                    val y = readS16BE(buf, i + 7)
                    val z = readS16BE(buf, i + 9)
                    _rotation.value = updateAngle3Axis(x, y, z)
                    emitted++
                    i += 12
                }
                DEV_TYPE_ACCEL_2AXIS -> {
                    // dt=85: X hi/lo at 5/6, Z hi/lo at 9/10.
                    if (i + 11 > len) break
                    val x = readS16BE(buf, i + 5)
                    val z = readS16BE(buf, i + 9)
                    _rotation.value = updateAngle2Axis(x, z)
                    emitted++
                    i += 12
                }
                DEV_TYPE_SHUTTER -> {
                    // dt=86: single shutter code byte at offset 4.
                    val code = buf[i + 4].toInt() and 0xff
                    Log.i(TAG, "shutter code=$code")
                    _shutter.tryEmit(code)
                    i += 6
                }
                else -> {
                    // Unknown dev_type — log once per session for future
                    // decoding, then advance by the header size to avoid
                    // an infinite loop on the same marker byte.
                    Log.w(TAG, "unknown dev_type=$devType payloadLen=$payloadLen frame=${buf.toHex(i, (2 + payloadLen).coerceAtMost(len - i))}")
                    i += 2 + payloadLen
                }
            }
        }
        return emitted
    }

    private fun updateAngle3Axis(@Suppress("UNUSED_PARAMETER") x: Int, y: Int, z: Int): Float {
        val moving = abs(y) >= MOVEMENT_THRESHOLD || abs(z) >= MOVEMENT_THRESHOLD
        if (!isStill && !moving) {
            if (++stillStreak >= STILL_SAMPLES) {
                isStill = true; stillStreak = 0
            }
        } else if (isStill && moving) {
            if (++stillStreak >= STILL_SAMPLES) {
                isStill = false; stillStreak = 0
            }
        } else {
            stillStreak = 0
        }
        if (isStill) return 0f
        // atan2(y, z) — vendor formula. +90° corrects for the mounting.
        val degrees = Math.toDegrees(atan2(y.toDouble(), z.toDouble())).toFloat() + 90f
        return degrees
    }

    private fun updateAngle2Axis(x: Int, z: Int): Float {
        return Math.toDegrees(atan2(x.toDouble(), z.toDouble())).toFloat()
    }

    /** Read a big-endian signed 16-bit integer from [b] at [off]. */
    private fun readS16BE(b: ByteArray, off: Int): Int {
        val hi = b[off].toInt() and 0xff
        val lo = b[off + 1].toInt() and 0xff
        val u16 = (hi shl 8) or lo
        return if (u16 and 0x8000 != 0) u16 - 0x10000 else u16
    }

    fun close() {
        runJob?.cancel()
        runJob = null
        runCatching { socket?.close() }
        socket = null
        scope.cancel()
    }

    companion object {
        private const val TAG = "Ne3ControlClient"

        private const val CONNECT_TIMEOUT_MS = 10_000

        /** 6-byte init blob the vendor app writes right after connecting.
         *  Some firmwares stay silent until they receive it. */
        private val INIT_HANDSHAKE: ByteArray = byteArrayOf(
            0xFF.toByte(), 0x03, 0xFE.toByte(), 0xFB.toByte(), 0xFA.toByte(), 0xFF.toByte(),
        )

        // Device-type values seen on the wire.
        private const val DEV_TYPE_ACCEL_2AXIS = 85
        private const val DEV_TYPE_SHUTTER = 86
        private const val DEV_TYPE_ACCEL_3AXIS = 87

        // Anti-jitter, matching the vendor's numeric thresholds.
        private const val MOVEMENT_THRESHOLD = 200
        private const val STILL_SAMPLES = 40

        /** Summary log cadence — one line every 5 s once telemetry starts. */
        private const val REPORT_INTERVAL_MS = 5_000L
    }
}
