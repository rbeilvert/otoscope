package dev.rubec.otoscope.stream.wudaopu

import android.net.Network
import dev.rubec.otoscope.debug.FileLog as Log
import dev.rubec.otoscope.stream.BatteryStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Wudaopu settings/status channel on UDP/50000.
 *
 * Wire format mirrors the streaming channel: 24-byte request with magic
 * `0x9999` + 16-bit command + 32-bit counter + 16 zero bytes. The camera
 * echoes the magic+cmd at the start of every reply, with a command-specific
 * payload following.
 */
internal class WudaopuControlClient(
    private val cameraIp: String,
    private val network: Network?,
    private val port: Int = 50000,
) {

    /** Identifying info from `sendGetBoardInfo` — JSON has at least `model` and
     *  `ssid_head` keys; the model is the part we surface. */
    data class BoardInfo(val model: String, val raw: JSONObject)

    private val counter = AtomicInteger(1)
    // The original retries up to 4x on no/invalid response. Serialise to avoid
    // mixing replies from concurrent calls on the same socket.
    private val mutex = Mutex()

    suspend fun getBattery(): BatteryStatus? = mutex.withLock {
        val reply = sendCommand(CMD_GET_BATTERY) ?: return@withLock null
        if (reply.size < 20) return@withLock null
        // Two u32 LE words at fixed offsets carry the battery state.
        //   extra[15..0]   percent (0..100)
        //   main[16]       cable connected
        //   main[17]       full (only meaningful while plugged in)
        // The remaining bits vary by firmware revision but don't affect the
        // four fields above on any hardware we've tested.
        val main = readU32LE(reply, 8)
        val extra = readU32LE(reply, 16)
        Log.d(TAG, "battery raw: main=0x%08x extra=0x%08x".format(main, extra))

        val percent = (extra and 0xFFFF).coerceIn(0, 100)
        val status = main ushr 16
        val isPluggedIn = (status and 0x01) != 0
        val isFull = isPluggedIn && (status and 0x02) != 0
        return@withLock BatteryStatus(
            percent = percent,
            charging = isPluggedIn && !isFull,
            full = isFull,
        )
    }

    suspend fun getBoardInfo(): BoardInfo? = mutex.withLock {
        val reply = sendCommand(CMD_GET_BOARD_INFO, recvBufSize = 1400)
        if (reply == null) {
            Log.d(TAG, "board_info: no reply")
            return@withLock null
        }
        if (reply.size < 32) return@withLock null
        val len = readU32LE(reply, 12)
        if (len <= 0 || 32 + len > reply.size) return@withLock null
        val json = String(reply, 32, len, Charsets.UTF_8).trimEnd(' ')
        return@withLock try {
            val obj = JSONObject(json)
            val model = obj.optString("model", "")
            if (model.isEmpty()) null else BoardInfo(model, obj)
        } catch (e: JSONException) {
            Log.w(TAG, "board_info JSON parse failed: ${e.message} (raw=$json)")
            null
        }
    }

    /** Firmware version as a single integer (e.g. 19 → "v19"). The camera also
     *  echoes a status word at offset 8 that's non-zero on success — older
     *  firmware returns 0 there to signal "not supported". */
    suspend fun getRemoteVersion(): Int? = mutex.withLock {
        val reply = sendCommand(CMD_GET_REMOTE_VERSION) ?: return@withLock null
        if (reply.size < 20) return@withLock null
        val status = readU32LE(reply, 8)
        if (status == 0) return@withLock null
        val version = readU32LE(reply, 16)
        // Original firmware clamps to the same upper bound before reporting.
        if (version <= 0 || version > 9_999_998) return@withLock null
        return@withLock version
    }

    private suspend fun sendCommand(cmd: Int, recvBufSize: Int = 1024): ByteArray? =
        withContext(Dispatchers.IO) {
            val sock = DatagramSocket()
            try {
                network?.bindSocket(sock)
                sock.soTimeout = 600

                val seq = counter.getAndIncrement()
                val sendBuf = ByteArray(24).apply {
                    this[0] = 0x99.toByte()
                    this[1] = 0x99.toByte()
                    this[2] = (cmd and 0xff).toByte()
                    this[3] = ((cmd ushr 8) and 0xff).toByte()
                    this[4] = (seq and 0xff).toByte()
                    this[5] = ((seq ushr 8) and 0xff).toByte()
                    this[6] = ((seq ushr 16) and 0xff).toByte()
                    this[7] = ((seq ushr 24) and 0xff).toByte()
                }
                val dst = InetSocketAddress(cameraIp, port)
                val recvBuf = ByteArray(recvBufSize)
                val recvPacket = DatagramPacket(recvBuf, recvBuf.size)

                repeat(4) {
                    try {
                        sock.send(DatagramPacket(sendBuf, sendBuf.size, dst))
                        sock.receive(recvPacket)
                        val replyMagic = readU16LE(recvBuf, 0)
                        val replyCmd = readU16LE(recvBuf, 2)
                        if (replyMagic == 0x9999 && replyCmd == cmd) {
                            return@withContext recvBuf.copyOf(recvPacket.length)
                        }
                    } catch (_: SocketTimeoutException) {
                        // try again
                    }
                }
                null
            } catch (e: Exception) {
                Log.w(TAG, "cmd 0x${cmd.toString(16)} failed: ${e.message}")
                null
            } finally {
                runCatching { sock.close() }
            }
        }

    private fun readU16LE(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8)

    private fun readU32LE(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or
            ((b[off + 1].toInt() and 0xff) shl 8) or
            ((b[off + 2].toInt() and 0xff) shl 16) or
            ((b[off + 3].toInt() and 0xff) shl 24)

    companion object {
        private const val TAG = "WudaopuCtrl"
        private const val CMD_GET_BATTERY = 0x1017
        private const val CMD_GET_BOARD_INFO = 0x1060
        private const val CMD_GET_REMOTE_VERSION = 0x1002
    }
}
