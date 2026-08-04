package dev.rubec.otoscope.stream.earfairy

import android.net.Network
import dev.rubec.otoscope.debug.FileLog as Log
import dev.rubec.otoscope.stream.BatteryStatus
import dev.rubec.otoscope.stream.bindOrTerminal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * EarFairy control channel on UDP/7099.
 *
 * The camera pushes telemetry frames while we ping it once per second; each
 * frame carries an accelerometer-derived rotation angle, battery state, a
 * device-family tag, and (on longer frames) shutter-button events and a
 * device-side rotation flag. Client-to-camera traffic is a heartbeat and —
 * for models that support it — an LED command.
 *
 * Telemetry wire format:
 * ```
 *   offset  meaning
 *   ------  ---------------------------------------------------------
 *      0    rotation high bit (0 or 1)
 *      1    rotation low byte (signed on the wire)
 *      2    battery: 1..100 = percentage, 101 = charging
 *      3    device family (constant 0x5A on EarFairy)
 *      4    optional shutter-button code ('M' = photo, 'X' = video)
 *      5-6  photo/video sequence counters (only meaningful when byte 4 set)
 *      7    device-side rotation flag: 1 = firmware requests an extra 90°
 * ```
 * Rotation encoding: when the high bit (byte 0) is set OR the low byte is
 * negative-signed, add 255 to the signed low byte to get degrees. Values
 * run 0..~382; the renderer treats that modulo 360, so no wrap handling
 * is needed. When byte 7 is 1 the camera reports a mounting flip and asks
 * us to add another 90°; that flag is one-shot — the firmware sets it
 * once at boot on flipped units and never clears it, so we treat it as
 * latched for the session.
 */
internal class EarFairyControlClient(
    private val cameraIp: String,
    private val network: Network?,
    private val port: Int = 7099,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: DatagramSocket? = null
    private var sendJob: Job? = null
    private var recvJob: Job? = null
    private var addr: InetSocketAddress? = null

    private val _terminalError = MutableStateFlow<String?>(null)
    val terminalError: StateFlow<String?> = _terminalError.asStateFlow()

    private val _rotation = MutableStateFlow(0f)
    val rotation: StateFlow<Float> = _rotation.asStateFlow()

    private val _battery = MutableStateFlow<BatteryStatus?>(null)
    val battery: StateFlow<BatteryStatus?> = _battery.asStateFlow()

    /** Local echo of the LED state — the camera doesn't include it in
     *  telemetry, so we track what we last commanded. Starts true because
     *  [start] forces the light on right after the socket is up (see the
     *  note there for why); the UI toggle then reflects that ground truth. */
    private val _ledEnabled = MutableStateFlow(true)
    val ledEnabled: StateFlow<Boolean> = _ledEnabled.asStateFlow()

    /** Latched device-side rotation flag: the firmware sets byte 7 == 1 once
     *  on flipped units at session start and never clears it, so we add an
     *  extra 90° from the moment we first see it until the session ends. */
    private var deviceExtraRotation = false

    fun start() {
        if (sendJob != null) return

        val sock = DatagramSocket()
        socket = sock
        bindOrTerminal(sock, network, TAG)?.let {
            _terminalError.value = it
            return
        }
        sock.soTimeout = 1000

        addr = InetSocketAddress(InetAddress.getByName(cameraIp), port)
        Log.i(TAG, "runControl: cameraIp=$cameraIp port=$port network=${network != null} localPort=${sock.localPort}")

        sendJob = scope.launch {
            val heartbeatPkt = DatagramPacket(HEARTBEAT, HEARTBEAT.size, addr)
            while (isActive) {
                runCatching { sock.send(heartbeatPkt) }
                    .onFailure { Log.w(TAG, "heartbeat send failed: ${it.message}") }
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }

        recvJob = scope.launch {
            val recvBuf = ByteArray(64)
            val packet = DatagramPacket(recvBuf, recvBuf.size)
            while (isActive) {
                packet.length = recvBuf.size
                try {
                    sock.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (e: Exception) {
                    Log.w(TAG, "recv: ${e.message}")
                    continue
                }
                parseTelemetry(recvBuf, packet.length)
            }
        }

        // Force the LED on at connect time so every session starts from a
        // known-good state: the UI toggle can't disagree with the physical
        // light, and clinical use gets predictable illumination without the
        // user having to think about a "was it on last time?" question.
        setLed(true)
    }

    /** Turn the ring-light on or off. Fire-and-forget — the camera doesn't
     *  ack. Updates the local [ledEnabled] flow immediately for UI feedback. */
    fun setLed(on: Boolean) {
        val sock = socket ?: return
        val dst = addr ?: return
        val payload = byteArrayOf(CMD_LED, if (on) LED_ON else LED_OFF)
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    sock.send(DatagramPacket(payload, payload.size, dst))
                }
            }.onFailure { Log.w(TAG, "led send failed: ${it.message}") }
        }
        _ledEnabled.value = on
    }

    fun close() {
        sendJob?.cancel(); sendJob = null
        recvJob?.cancel(); recvJob = null
        runCatching { socket?.close() }; socket = null
        scope.cancel()
    }

    /** Decode a telemetry frame per the class KDoc. Short frames (len < 8)
     *  still carry rotation and battery; the shutter-button code and the
     *  device-side rotation flag only appear on len >= 8. */
    private fun parseTelemetry(buf: ByteArray, len: Int) {
        if (len < 4) return
        val raw = decodeAngle(buf[0].toInt() and 0xff, buf[1].toInt())

        // Byte 7 is a firmware-side rotation-flip flag — some hardware
        // revisions signal a mounting flip this way. The firmware sets it
        // once at boot on those units and never toggles, so we latch it.
        if (len >= 8 && (buf[7].toInt() and 0xff) == 1) deviceExtraRotation = true

        // Two independent offsets: [MOUNT_OFFSET_DEG] compensates for the
        // Y-201's mount (accelerometer zero sits 90° CCW of the lens's
        // up-axis), and the byte-7 latch adds another 90° on firmware
        // revisions that request it — so a unit that reports both still
        // lands upright.
        val extra = MOUNT_OFFSET_DEG + (if (deviceExtraRotation) 90 else 0)
        _rotation.value = (raw + extra).toFloat()

        // Battery: 1..100 is percentage, 101 is the "charging" sentinel the
        // firmware emits when the camera is plugged in.
        val b2 = buf[2].toInt() and 0xff
        _battery.value = if (b2 == BATT_CHARGING_SENTINEL) {
            BatteryStatus(percent = 100, charging = true, full = false)
        } else {
            val pct = b2.coerceIn(0, 100)
            BatteryStatus(percent = pct, charging = false, full = pct >= 100)
        }
    }

    companion object {
        private const val TAG = "EarFairyCtrl"
        // The camera streams telemetry for ~3 s after each client-side ping,
        // then goes silent. 1000 ms keeps the stream continuous with margin;
        // 500 ms is wasted traffic and 5 s produces a visible ~2 s stall
        // per cycle where rotation compensation stops updating.
        private const val HEARTBEAT_INTERVAL_MS = 1_000L
        // Two-byte heartbeat. Payload is opaque; the firmware only cares
        // that *something* arrived on the control port to keep the stream open.
        private val HEARTBEAT = byteArrayOf(0x01, 0x01)

        // LED command — first byte is the opcode, second is 1=off / 2=on.
        private const val CMD_LED: Byte = 0x05
        private const val LED_ON: Byte = 0x02
        private const val LED_OFF: Byte = 0x01

        // Battery byte 2: values 1..100 are the raw percentage; 101 is a
        // reserved sentinel the firmware emits when the camera is plugged
        // in and charging.
        private const val BATT_CHARGING_SENTINEL = 101

        // Rotation offset for the Y-201 hardware we've bench-tested. Byte 7
        // stays 0 on this unit, yet the image sits 90° CCW at rest without
        // this correction. Applied on top of any byte-7-latched offset.
        private const val MOUNT_OFFSET_DEG = 90

        /** Rotation decoder. When the high-bit byte is set OR the low byte is
         *  negative-signed, add 255 to the signed low byte to get degrees.
         *  Range comes out 0..~382; rendering treats it as a rotation modulo
         *  360 so wrapping is fine. */
        fun decodeAngle(byte0Unsigned: Int, byte1Signed: Int): Int {
            val signed = byte1Signed.toByte().toInt()  // sign-extend
            return if (byte0Unsigned == 1 || signed < 0) signed + 255 else signed
        }
    }
}
