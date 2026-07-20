package dev.rubec.otoscope.stream.wudaopu

import android.graphics.Bitmap
import android.net.Network
import dev.rubec.otoscope.debug.FileLog as Log
import dev.rubec.otoscope.stream.JpegDecoder
import dev.rubec.otoscope.stream.SessionStats
import dev.rubec.otoscope.stream.TerminalErrors
import dev.rubec.otoscope.stream.toHex
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

/**
 * Wudaopu video-stream client. Sends a "start preview" cmd on UDP/8032, reads
 * 24-byte-header MJPEG/YUYV chunks back on the same socket, assembles frames
 * via [FrameAssembler], and decodes them to Bitmaps.
 *
 * Vendor-specific wire format details are in `FrameAssembler.kt`.
 */
internal class WudaopuCameraClient(
    private val cameraIp: String,
    private val network: Network?,
    private val cmdPort: Int = 8032,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: DatagramSocket? = null
    private var runJob: Job? = null

    private val _frames = MutableSharedFlow<Bitmap>(replay = 0, extraBufferCapacity = 2)
    val frames: SharedFlow<Bitmap> = _frames.asSharedFlow()

    /** Camera rotation in degrees, derived from the on-board accelerometer. */
    private val _rotation = MutableStateFlow(0f)
    val rotation: StateFlow<Float> = _rotation.asStateFlow()

    private val rotationFilter = RotationFilter()

    private val _stats = MutableStateFlow(SessionStats())
    val stats: StateFlow<SessionStats> = _stats.asStateFlow()

    private val _terminalError = MutableStateFlow<String?>(null)
    val terminalError: StateFlow<String?> = _terminalError.asStateFlow()

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
        val bindResult = runCatching { network?.bindSocket(sock) }
        sock.soTimeout = 1000
        sock.receiveBufferSize = 4 * 1024 * 1024
        Log.i(
            TAG,
            "runStream: cameraIp=$cameraIp cmdPort=$cmdPort " +
                "network=${network != null} bindSocket=${bindResult.isSuccess} localPort=${sock.localPort}",
        )
        // If we have a network handle but the OS refused the bind, our packets
        // would silently route through some other interface (typically a VPN)
        // and never reach the camera. Fail fast so the ViewModel can surface a
        // specific, actionable message instead of the user waiting on frames.
        if (network != null && bindResult.isFailure) {
            Log.w(TAG, "bindSocket refused: ${bindResult.exceptionOrNull()?.message}")
            _terminalError.value = TerminalErrors.NETWORK_BIND_FORBIDDEN
            return
        }

        val cameraAddr = InetAddress.getByName(cameraIp)
        val cmdAddr = InetSocketAddress(cameraAddr, cmdPort)

        // Burst the start cmd 11x. UDP is lossy and the camera ignores the
        // first few packets if the AP is still settling.
        sendCmd(sock, cmdAddr, CMD_START_PREVIEW, burst = 11)
        Log.i(TAG, "start burst sent 11x → ${cameraAddr.hostAddress}:$cmdPort")

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

                when (val outcome = assembler.feed(recvBuf, len)) {
                    is FrameAssembler.Outcome.Frame -> {
                        val bmp = decode(outcome)
                        if (bmp != null) {
                            _rotation.value = rotationFilter.update(outcome.accelerometer)
                            _frames.tryEmit(bmp)
                            _stats.update { it.copy(framesReceived = it.framesReceived + 1) }
                        } else {
                            _stats.update { it.copy(
                                framesDropped = it.framesDropped + 1,
                                lastError = "decode failed (fmt=${outcome.format}, ${outcome.width}x${outcome.height}, ${outcome.data.size}B)",
                            ) }
                        }
                    }
                    FrameAssembler.Outcome.Dropped ->
                        _stats.update { it.copy(framesDropped = it.framesDropped + 1) }
                    FrameAssembler.Outcome.Building,
                    FrameAssembler.Outcome.Invalid -> Unit
                }
            }
        } finally {
            keepalive.cancel()
        }
    }

    private fun decode(frame: FrameAssembler.Outcome.Frame): Bitmap? = when (frame.format) {
        FrameAssembler.FORMAT_MJPEG -> JpegDecoder.decode(frame.data)
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
        private const val TAG = "WudaopuClient"
        private const val CMD_START_PREVIEW = 1
        @Suppress("unused")
        private const val CMD_STOP_PREVIEW = 2
    }
}
