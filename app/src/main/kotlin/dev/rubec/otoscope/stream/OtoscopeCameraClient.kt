package dev.rubec.otoscope.stream

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Network
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

class OtoscopeCameraClient(
    private val cameraIp: String,
    private val network: Network?,
    private val cmdPort: Int = 8032,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: DatagramSocket? = null
    private var runJob: Job? = null

    private val _frames = MutableSharedFlow<Bitmap>(replay = 0, extraBufferCapacity = 2)
    val frames: SharedFlow<Bitmap> = _frames.asSharedFlow()

    /** Camera rotation in degrees, derived from the camera's on-board accelerometer. */
    private val _rotation = MutableStateFlow(0f)
    val rotation: StateFlow<Float> = _rotation.asStateFlow()

    private val rotationFilter = RotationFilter()

    data class Stats(
        val packetsReceived: Long = 0,
        val framesReceived: Long = 0,
        val framesDropped: Long = 0,
        val bytesReceived: Long = 0,
        val lastError: String? = null,
    )

    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats.asStateFlow()

    fun start() {
        if (runJob != null) return
        runJob = scope.launch {
            try {
                runStream()
            } catch (e: Exception) {
                Log.e(TAG, "stream loop crashed", e)
                _stats.update { it.copy(lastError = "${e.javaClass.simpleName}: ${e.message}") }
            }
        }
    }

    private suspend fun runStream() {
        val sock = DatagramSocket().also { socket = it }
        network?.bindSocket(sock)
        sock.soTimeout = 1000
        sock.receiveBufferSize = 4 * 1024 * 1024

        val cameraAddr = InetAddress.getByName(cameraIp)
        val cmdAddr = InetSocketAddress(cameraAddr, cmdPort)

        // Initial start cmd burst (11x, mirroring the original UDP-reliability strategy).
        sendCmd(sock, cmdAddr, CMD_START_PREVIEW, burst = 11)

        val keepalive = scope.launch {
            while (isActive) {
                delay(800)
                runCatching { sendCmd(sock, cmdAddr, CMD_START_PREVIEW, burst = 1) }
                    .onFailure { Log.w(TAG, "keepalive failed: ${it.message}") }
            }
        }

        try {
            val assembler = FrameAssembler()
            val recvBuf = ByteArray(8192)
            val packet = DatagramPacket(recvBuf, recvBuf.size)
            var firstPacketLogged = false

            while (scope.isActive) {
                packet.length = recvBuf.size
                try {
                    sock.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (e: Exception) {
                    _stats.update { it.copy(lastError = e.message) }
                    Log.w(TAG, "recv: ${e.message}")
                    continue
                }

                val len = packet.length
                _stats.update { it.copy(
                    packetsReceived = it.packetsReceived + 1,
                    bytesReceived = it.bytesReceived + len,
                ) }

                if (!firstPacketLogged) {
                    firstPacketLogged = true
                    Log.i(TAG, "first packet $len bytes: ${recvBuf.toHex(0, minOf(len, 32))}")
                }

                val outcome = assembler.feed(recvBuf, len)
                when (outcome.result) {
                    FrameAssembler.FeedResult.Frame -> {
                        val frame = outcome.frame ?: continue
                        val bmp = decode(frame)
                        if (bmp != null) {
                            _rotation.value = rotationFilter.update(frame.accelerometer)
                            _frames.tryEmit(bmp)
                            _stats.update { it.copy(framesReceived = it.framesReceived + 1) }
                        } else {
                            _stats.update { it.copy(
                                framesDropped = it.framesDropped + 1,
                                lastError = "decode failed (fmt=${frame.format}, ${frame.width}x${frame.height}, ${frame.data.size}B)",
                            ) }
                        }
                    }
                    FrameAssembler.FeedResult.Dropped -> {
                        _stats.update { it.copy(framesDropped = it.framesDropped + 1) }
                    }
                    FrameAssembler.FeedResult.Building,
                    FrameAssembler.FeedResult.Invalid -> Unit
                }
            }
        } finally {
            keepalive.cancel()
        }
    }

    private fun decode(frame: FrameAssembler.Frame): Bitmap? = when (frame.format) {
        FrameAssembler.FORMAT_MJPEG ->
            BitmapFactory.decodeByteArray(frame.data, 0, frame.data.size)
        FrameAssembler.FORMAT_YUYV -> null // not yet implemented
        else -> null
    }

    private fun sendCmd(sock: DatagramSocket, dst: InetSocketAddress, cmd: Int, burst: Int) {
        val packet = ByteArray(24).apply {
            this[0] = 0x99.toByte()
            this[1] = 0x99.toByte()
            this[2] = (cmd and 0xff).toByte()
            this[3] = ((cmd ushr 8) and 0xff).toByte()
        }
        val dg = DatagramPacket(packet, packet.size, dst)
        repeat(burst) { sock.send(dg) }
    }

    fun close() {
        runJob?.cancel()
        runJob = null
        runCatching { socket?.close() }
        socket = null
        scope.cancel()
    }

    companion object {
        private const val TAG = "OtoscopeClient"
        private const val CMD_START_PREVIEW = 1
        private const val CMD_STOP_PREVIEW = 2
    }
}

private fun ByteArray.toHex(off: Int, len: Int): String =
    (off until off + len).joinToString(" ") { "%02x".format(this[it]) }
