package dev.rubec.otoscope.stream.ne3

import android.net.Network
import dev.rubec.otoscope.debug.FileLog as Log
import dev.rubec.otoscope.stream.toHex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * NE3 (HND family) sensor + button channel on TCP/2271.
 *
 * On firmwares that expose this optional channel the camera opens a
 * bidirectional TCP socket and pushes 38-byte packets containing the
 * same `0xFF`-delimited sensor frames the UDP ctlmsg channel carries;
 * all decoding is delegated to the shared [Ne3SensorParser]. On
 * firmwares that don't open the port the connect fails with
 * `ECONNREFUSED` and we fall back to the UDP ctlmsg path on
 * [Ne3VideoClient] — rotation and shutter still work either way.
 *
 * On connect we send a 6-byte init blob (`FF 03 FE FB FA FF`) to trigger
 * the camera into pushing sensor data — some firmwares stay silent until
 * they receive it.
 */
internal class Ne3ControlClient(
    private val cameraIp: String,
    private val network: Network?,
    private val parser: Ne3SensorParser,
    private val port: Int = 2271,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: Socket? = null
    private var runJob: Job? = null

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
        } catch (e: java.net.ConnectException) {
            // Expected on firmware revisions that don't expose the optional
            // sensor channel on TCP/2271 — rotation and shutter telemetry
            // just arrive via the UDP ctlmsg path instead, if at all.
            Log.i(TAG, "sensor channel $cameraIp:$port not available (${e.message}); UDP ctlmsg will carry rotation if the firmware emits it")
            return
        } catch (e: Exception) {
            Log.w(TAG, "connect $cameraIp:$port failed: ${e.message}")
            return
        }
        sock.tcpNoDelay = true
        sock.keepAlive = true
        // Match the vendor app's tiny buffers — a Ne3 sensor packet is only
        // 38 bytes and we don't want to sit on stale data.
        sock.sendBufferSize = 44
        sock.receiveBufferSize = 44
        Log.i(TAG, "connected $cameraIp:$port localPort=${sock.localPort}")

        val out = sock.getOutputStream()
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

            if (packetsSeen == 1L) {
                Log.i(TAG, "first packet $n bytes: ${buf.toHex(0, n.coerceAtMost(38))}")
            }

            samplesEmitted += parser.feed(buf, n)

            val now = System.currentTimeMillis()
            val elapsed = now - lastReportMs
            if (elapsed >= REPORT_INTERVAL_MS) {
                val hz = samplesEmitted * 1000.0 / elapsed
                Log.i(
                    TAG,
                    "telemetry: $packetsSeen packets, $samplesEmitted samples in ${elapsed}ms " +
                        "(%.1f Hz), devType=${parser.deviceType.value}, angle=%.1f° still=${parser.isStillLatched}".format(hz, parser.rotation.value),
                )
                packetsSeen = 0
                samplesEmitted = 0
                lastReportMs = now
            }
        }
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

        /** Summary log cadence — one line every 5 s once telemetry starts. */
        private const val REPORT_INTERVAL_MS = 5_000L
    }
}
