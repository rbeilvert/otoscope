package dev.rubec.otoscope.stream.earfairy

import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.Socket

/**
 * Minimal RTSP client for the OTOSCOPE use case. Speaks RTSP over TCP for the
 * signalling side (OPTIONS / DESCRIBE / SETUP / PLAY / TEARDOWN); the actual
 * transport for the RTP data flow is whatever the caller announces via the
 * [handshake] `transport` argument — the EarFairy path passes a UDP unicast
 * `RTP/AVP;unicast;client_port=X-Y`. No authentication, no session resume,
 * no seeking. Just what's needed to hit PLAY on a JPEG stream.
 *
 * Not thread-safe. Caller owns the [socket] and closes it on teardown.
 */
internal class RtspClient(
    private val socket: Socket,
    private val baseUrl: String,
) {
    private val input: BufferedInputStream = BufferedInputStream(socket.getInputStream())
    private val output: OutputStream = socket.getOutputStream()
    private var cseq: Int = 0
    private var sessionId: String? = null

    fun handshake(transport: String) {
        options()
        val sdp = describe()
        val controlUrl = resolveControlUrl(sdp)
        setup(controlUrl, transport)
        play()
    }

    private fun options() {
        send("OPTIONS", baseUrl)
        val response = readResponse()
        response.requireOk("OPTIONS")
    }

    private fun describe(): String {
        send("DESCRIBE", baseUrl, extraHeaders = listOf("Accept: application/sdp"))
        val response = readResponse()
        response.requireOk("DESCRIBE")
        return response.body
    }

    private fun setup(controlUrl: String, transport: String) {
        send("SETUP", controlUrl, extraHeaders = listOf("Transport: $transport"))
        val response = readResponse()
        response.requireOk("SETUP")
        // Session header looks like `Session: 12345678;timeout=60`. Split off
        // the parameters after the semicolon; we just need the id.
        val session = response.header("Session")
            ?: error("SETUP response missing Session header")
        sessionId = session.substringBefore(';').trim()
    }

    private fun play() {
        val session = sessionId ?: error("PLAY called before SETUP")
        send(
            "PLAY",
            baseUrl,
            extraHeaders = listOf("Session: $session", "Range: npt=0.000-"),
        )
        val response = readResponse()
        response.requireOk("PLAY")
    }

    fun teardown() {
        // Best-effort. If the socket is already dead, skip.
        val session = sessionId ?: return
        runCatching {
            send("TEARDOWN", baseUrl, extraHeaders = listOf("Session: $session"))
        }
    }

    private fun send(method: String, url: String, extraHeaders: List<String> = emptyList()) {
        cseq += 1
        val sb = StringBuilder()
        sb.append("$method $url RTSP/1.0\r\n")
        sb.append("CSeq: $cseq\r\n")
        sb.append("User-Agent: Otoscope\r\n")
        for (h in extraHeaders) sb.append("$h\r\n")
        sb.append("\r\n")
        output.write(sb.toString().toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    /** Read one RTSP response. The response is line-terminated by `\r\n`, with
     *  a blank line separating headers from the optional body. Uses BufferedInputStream
     *  so the leftover interleaved-data bytes stay buffered for the caller. */
    private fun readResponse(): Response {
        val statusLine = readCrLfLine()
        val headers = mutableListOf<Pair<String, String>>()
        while (true) {
            val line = readCrLfLine()
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers += line.substring(0, idx).trim() to line.substring(idx + 1).trim()
        }
        val contentLength = headers.firstOrNull { it.first.equals("Content-Length", ignoreCase = true) }
            ?.second?.trim()?.toIntOrNull() ?: 0
        val body = if (contentLength > 0) {
            val buf = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(buf, read, contentLength - read)
                if (n < 0) error("Unexpected EOF reading RTSP body")
                read += n
            }
            String(buf, Charsets.US_ASCII)
        } else ""
        return Response(statusLine, headers, body)
    }

    /** Read bytes until `\r\n`; return the line without the terminator.
     *  Streams a byte at a time — RTSP responses are tiny so this is fine. */
    private fun readCrLfLine(): String {
        val out = StringBuilder()
        var lastWasCr = false
        while (true) {
            val b = input.read()
            if (b < 0) error("Unexpected EOF reading RTSP line")
            val c = b.toChar()
            if (lastWasCr && c == '\n') {
                out.setLength(out.length - 1) // strip the trailing CR we already appended
                return out.toString()
            }
            out.append(c)
            lastWasCr = c == '\r'
        }
    }

    /** Resolve the video track's control URL from the SDP body.
     *
     *  Servers can express it three ways:
     *    - Absolute URL: `a=control:rtsp://host:port/path/track0`
     *    - Relative to Content-Base or the request URL: `a=control:track0`
     *    - "*": use the base URL as-is (aggregate control).
     */
    private fun resolveControlUrl(sdp: String): String {
        // We only care about the first `m=video` block. Walk the SDP, remember
        // whether we're inside the video section, and pick its `a=control:...`.
        var inVideo = false
        var control: String? = null
        for (raw in sdp.lineSequence()) {
            val line = raw.trimEnd()
            if (line.startsWith("m=")) inVideo = line.startsWith("m=video")
            if (inVideo && line.startsWith("a=control:")) {
                control = line.removePrefix("a=control:").trim()
                break
            }
        }
        val relative = control ?: error("SDP has no video control URL")
        return when {
            relative == "*" -> baseUrl
            relative.startsWith("rtsp://", ignoreCase = true) -> relative
            baseUrl.endsWith('/') -> baseUrl + relative
            else -> "$baseUrl/$relative"
        }
    }

    private data class Response(
        val statusLine: String,
        val headers: List<Pair<String, String>>,
        val body: String,
    ) {
        fun header(name: String): String? =
            headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

        fun requireOk(op: String) {
            // Status line looks like `RTSP/1.0 200 OK`. Slice out the code.
            val parts = statusLine.split(' ', limit = 3)
            val code = parts.getOrNull(1)?.toIntOrNull()
            if (code != 200) throw RtspStatusException(op, code ?: 0, statusLine)
        }
    }
}

/** Non-200 RTSP status raised by [RtspClient]. [code] lets callers branch on
 *  well-known failures without parsing the raw status line — 461 for
 *  Unsupported Transport, 454 for Session Not Found, etc. */
internal class RtspStatusException(
    val op: String,
    val code: Int,
    val statusLine: String,
) : RuntimeException("$op failed: $statusLine")
