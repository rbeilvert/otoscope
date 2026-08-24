package dev.rubec.otoscope.stream.earfairy

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * End-to-end handshake tests for [RtspClient] using a real socket pair.
 *
 * The vendor camera lives on a soft-AP we can't hit from CI, but the RTSP
 * signalling itself is standard TCP text — we can spin up a canned server
 * on localhost that speaks the exact reply shape the EarFairy firmware
 * emits, and verify the client goes through the OPTIONS → DESCRIBE →
 * SETUP → PLAY handshake in order.
 */
class RtspClientTest {

    @Test(timeout = 5_000) fun `handshake runs the full four-step sequence and captures the session id`() {
        // The `Session` header on SETUP responses is what the client passes
        // to PLAY and TEARDOWN. Our fake server includes the extra
        // `;timeout=60` suffix real cameras add, so the parser has to strip it.
        val script = listOf(
            RtspStep(expectedMethod = "OPTIONS", reply = "RTSP/1.0 200 OK\r\nCSeq: 1\r\nPublic: OPTIONS,DESCRIBE,SETUP,PLAY,TEARDOWN\r\n\r\n"),
            RtspStep(
                expectedMethod = "DESCRIBE",
                reply = buildDescribeReply(cseq = 2, sdp = """
                    v=0
                    o=- 0 0 IN IP4 127.0.0.1
                    s=Otoscope test
                    m=video 0 RTP/AVP 26
                    a=control:track0
                """.trimIndent()),
            ),
            RtspStep(
                expectedMethod = "SETUP",
                reply = "RTSP/1.0 200 OK\r\nCSeq: 3\r\nSession: 12345678;timeout=60\r\nTransport: RTP/AVP;unicast;client_port=1234-1235;server_port=6970-6971\r\n\r\n",
            ),
            RtspStep(
                expectedMethod = "PLAY",
                reply = "RTSP/1.0 200 OK\r\nCSeq: 4\r\nSession: 12345678\r\nRTP-Info: url=rtsp://127.0.0.1/webcam/track0;seq=1000\r\n\r\n",
            ),
        )
        val server = FakeRtspServer(script)
        val port = server.start()

        try {
            val sock = Socket(InetAddress.getLoopbackAddress(), port)
            val client = RtspClient(sock, "rtsp://127.0.0.1:$port/webcam")
            client.handshake("RTP/AVP;unicast;client_port=1234-1235")
            sock.close()
        } finally {
            server.stop()
        }
        val requests = server.awaitCompletion()

        // Method sequence is the load-bearing behaviour — a refactor that
        // reordered SETUP and PLAY, or skipped DESCRIBE, would flunk here.
        assertEquals(
            listOf("OPTIONS", "DESCRIBE", "SETUP", "PLAY"),
            requests.map { it.method },
        )

        // CSeq must monotonically increase across the four requests.
        val cseqs = requests.mapNotNull { it.headers["CSeq"]?.toIntOrNull() }
        assertEquals(listOf(1, 2, 3, 4), cseqs)

        // SETUP must announce the transport header the caller passed in.
        val setup = requests.first { it.method == "SETUP" }
        assertEquals("RTP/AVP;unicast;client_port=1234-1235", setup.headers["Transport"])

        // PLAY must carry the session id extracted from SETUP's Session
        // header, with the `;timeout=` suffix stripped off.
        val play = requests.first { it.method == "PLAY" }
        assertEquals("12345678", play.headers["Session"])
    }

    @Test(timeout = 5_000) fun `non-200 SETUP response throws RtspStatusException with the status code`() {
        // 461 Unsupported Transport is the response the real EarFairy firmware
        // sends if we ask for TCP interleave. The client MUST surface it so
        // the video client can back off to UDP.
        val script = listOf(
            RtspStep("OPTIONS", "RTSP/1.0 200 OK\r\nCSeq: 1\r\n\r\n"),
            RtspStep(
                "DESCRIBE",
                buildDescribeReply(cseq = 2, sdp = "v=0\r\nm=video 0 RTP/AVP 26\r\na=control:*"),
            ),
            RtspStep("SETUP", "RTSP/1.0 461 Unsupported Transport\r\nCSeq: 3\r\n\r\n"),
        )
        val server = FakeRtspServer(script)
        val port = server.start()

        try {
            val sock = Socket(InetAddress.getLoopbackAddress(), port)
            val client = RtspClient(sock, "rtsp://127.0.0.1:$port/webcam")
            val ex = assertFailsWith<RtspStatusException> {
                client.handshake("RTP/AVP/TCP;unicast;interleaved=0-1")
            }
            assertEquals(461, ex.code)
            assertEquals("SETUP", ex.op)
            sock.close()
        } finally {
            server.stop()
        }
    }

    @Test(timeout = 5_000) fun `handshake resolves aggregate control URL when SDP uses star`() {
        // Aggregate control: SETUP is sent against the base URL, not a
        // per-track suffix. Real EarFairy firmware uses `a=control:*`.
        val script = listOf(
            RtspStep("OPTIONS", "RTSP/1.0 200 OK\r\nCSeq: 1\r\n\r\n"),
            RtspStep(
                "DESCRIBE",
                buildDescribeReply(cseq = 2, sdp = "v=0\r\nm=video 0 RTP/AVP 26\r\na=control:*"),
            ),
            RtspStep("SETUP", "RTSP/1.0 200 OK\r\nCSeq: 3\r\nSession: 99\r\n\r\n"),
            RtspStep("PLAY", "RTSP/1.0 200 OK\r\nCSeq: 4\r\n\r\n"),
        )
        val server = FakeRtspServer(script)
        val port = server.start()

        try {
            val sock = Socket(InetAddress.getLoopbackAddress(), port)
            val client = RtspClient(sock, "rtsp://127.0.0.1:$port/webcam")
            client.handshake("RTP/AVP;unicast;client_port=1234-1235")
            sock.close()
        } finally {
            server.stop()
        }
        val setup = server.awaitCompletion().first { it.method == "SETUP" }
        // Aggregate → SETUP target is the base URL, not `<base>/track0`.
        assertTrue(
            setup.url.endsWith("/webcam"),
            "aggregate control should SETUP against the base URL, got ${setup.url}",
        )
    }

    // ---- helpers -----------------------------------------------------------

    private fun buildDescribeReply(cseq: Int, sdp: String): String {
        val body = sdp.replace(Regex("(\r\n|\n)"), "\r\n").trimEnd() + "\r\n"
        return "RTSP/1.0 200 OK\r\n" +
            "CSeq: $cseq\r\n" +
            "Content-Type: application/sdp\r\n" +
            "Content-Length: ${body.toByteArray(Charsets.US_ASCII).size}\r\n" +
            "\r\n" +
            body
    }

    private data class RtspStep(val expectedMethod: String, val reply: String)

    /** Captured client request for later assertion. */
    private data class SeenRequest(
        val method: String,
        val url: String,
        val headers: Map<String, String>,
    )

    /**
     * Serves one client with a fixed reply script. Records every request it
     * saw so tests can assert against method / URL / header contents once
     * the handshake completes.
     */
    private class FakeRtspServer(private val script: List<RtspStep>) {
        private val serverSocket = ServerSocket(0, /* backlog */ 1, InetAddress.getLoopbackAddress())
        private val seen = mutableListOf<SeenRequest>()
        private lateinit var worker: Thread
        @Volatile private var failure: Throwable? = null

        fun start(): Int {
            worker = thread(start = true, name = "fake-rtsp") {
                runCatching {
                    serverSocket.accept().use { client ->
                        val input = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.US_ASCII))
                        val output = client.getOutputStream()
                        for (step in script) {
                            val req = readOneRequest(input)
                            seen += req
                            check(req.method == step.expectedMethod) {
                                "expected ${step.expectedMethod}, got ${req.method}"
                            }
                            output.write(step.reply.toByteArray(Charsets.US_ASCII))
                            output.flush()
                        }
                    }
                }.onFailure { failure = it }
            }
            return serverSocket.localPort
        }

        fun stop() {
            runCatching { serverSocket.close() }
        }

        fun awaitCompletion(): List<SeenRequest> {
            worker.join(3_000)
            failure?.let { throw AssertionError("fake RTSP server failed: ${it.message}", it) }
            return seen
        }

        private fun readOneRequest(input: BufferedReader): SeenRequest {
            val requestLine = requireNotNull(input.readLine()) { "client closed before sending a request" }
            val parts = requestLine.split(' ')
            check(parts.size >= 2) { "malformed request line: $requestLine" }
            val method = parts[0]
            val url = parts[1]

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    headers[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
                }
            }
            return SeenRequest(method, url, headers)
        }
    }
}
