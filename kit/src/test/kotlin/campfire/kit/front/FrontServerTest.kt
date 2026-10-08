package campfire.kit.front

import campfire.kit.Body
import campfire.kit.Handler
import campfire.kit.Headers
import campfire.kit.RackDeflater
import campfire.kit.Response
import campfire.kit.layered
import com.github.luben.zstd.ZstdInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The front server end to end over real sockets: the plain-HTTP tests of `crates/kit/tests/front.rs`. */
class FrontServerTest {
    private class Reply(val status: Int, val headers: List<Pair<String, String>>, val body: ByteArray) {
        fun all(name: String) = headers.filter { it.first == name }.map { it.second }

        operator fun get(name: String) = all(name).firstOrNull()
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun start(vararg vars: Pair<String, String>, app: Handler = testApp()): FrontServer {
        val env = mutableMapOf("HTTP_PORT" to freePort().toString(), "TARGET_PORT" to freePort().toString(), "LOG_REQUESTS" to "false")
        env.putAll(vars)
        return FrontServer(FrontConfig.fromLookup { env[it] }, app, workers = 4, log = {}).start()
    }

    private fun exchange(port: Int, request: String): Reply = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 10_000
        socket.getOutputStream().write(request.toByteArray())
        parse(socket.getInputStream().readAllBytes())
    }

    private fun parse(raw: ByteArray): Reply {
        val split = (0..raw.size - 4).first { raw[it] == '\r'.code.toByte() && String(raw, it, 4) == "\r\n\r\n" }
        val lines = String(raw, 0, split, Charsets.ISO_8859_1).split("\r\n")
        val status = lines[0].split(' ')[1].toInt()
        val headers = lines.drop(1).map { it.substringBefore(": ").lowercase() to it.substringAfter(": ") }
        var body = raw.copyOfRange(split + 4, raw.size)
        if (headers.any { it == ("transfer-encoding" to "chunked") }) body = dechunk(body)
        return Reply(status, headers, body)
    }

    private fun dechunk(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var at = 0
        while (true) {
            val lineEnd = (at until data.size - 1).first { data[it] == '\r'.code.toByte() && data[it + 1] == '\n'.code.toByte() }
            val size = String(data, at, lineEnd - at).trim().toInt(16)
            at = lineEnd + 2
            if (size == 0) return out.toByteArray()
            out.write(data, at, size)
            at += size + 2
        }
    }

    private fun getRequest(path: String, extra: String = "") = "GET $path HTTP/1.1\r\nHost: chat.test\r\nConnection: close\r\n$extra\r\n"

    /** An app with the kinds of responses Campfire sends, behind the app's own `Rack::Deflater`. */
    private fun testApp(): Handler {
        var renders = 0
        val app = Handler { request ->
            when (request.path) {
                "/public" -> Response(
                    200,
                    Headers().add("cache-control", "public, max-age=60").add("content-type", "text/css").add("etag", "\"v1\"")
                        .add("set-cookie", "tracked=1; path=/"),
                    Body.of("render ${renders++}"),
                )
                "/page" -> Response(200, Headers().add("content-type", "text/html; charset=utf-8"), Body.of("<p>campfire</p>".repeat(200)))
                "/headers" -> {
                    val h = request.headers
                    Response.text(
                        200,
                        "for=${h["x-forwarded-for"] ?: "-"} host=${h["x-forwarded-host"] ?: "-"} proto=${h["x-forwarded-proto"] ?: "-"} " +
                            "forwarded=${h["forwarded"] ?: "-"} start=${h["x-request-start"]?.startsWith("t=") == true} peer=${request.remote?.address?.hostAddress}",
                    )
                }
                "/slow" -> {
                    Thread.sleep(3000)
                    Response.text(200, "late")
                }
                else -> Response.text(200, "${request.body.size} bytes")
            }
        }
        return app.layered(RackDeflater())
    }

    @Test
    fun cachesPublicResponsesAndGzipsThroughTheDeflater() {
        val server = start()
        try {
            val first = exchange(server.httpPort, getRequest("/public", "Accept-Encoding: identity\r\n"))
            assertEquals(200, first.status)
            assertEquals("miss", first["x-cache"])
            assertNull(first["set-cookie"])
            assertEquals(listOf("Accept-Encoding"), first.all("vary"))
            assertNotNull(first["date"])
            assertEquals("render 0", String(first.body))
            assertEquals("8", first["content-length"])

            val second = exchange(server.httpPort, getRequest("/public?", "Accept-Encoding: identity\r\n"))
            assertEquals("hit" to "render 0", second["x-cache"] to String(second.body))

            val gzip = exchange(server.httpPort, getRequest("/public", "Accept-Encoding: gzip\r\n"))
            assertEquals("miss", gzip["x-cache"])
            assertEquals("gzip", gzip["content-encoding"])
            assertEquals("chunked", gzip["transfer-encoding"], "the deflater's body goes out chunked")
            assertNull(gzip["content-length"])
            assertEquals("render 1", String(GZIPInputStream(ByteArrayInputStream(gzip.body)).readAllBytes()))
            val gzipHit = exchange(server.httpPort, getRequest("/public", "Accept-Encoding: gzip\r\n"))
            assertEquals("hit", gzipHit["x-cache"])
            assertNotNull(gzipHit["content-length"], "a hit has a length")

            val revalidated = exchange(server.httpPort, getRequest("/public", "Accept-Encoding: identity\r\nIf-None-Match: \"v1\"\r\n"))
            assertEquals(304 to "hit", revalidated.status to revalidated["x-cache"])
            assertTrue(revalidated.body.isEmpty())
            assertNull(revalidated["content-length"])
        } finally {
            server.stop()
        }
    }

    @Test
    fun compressesWhatTheAppLeftUnencoded() {
        val server = start()
        try {
            val zstd = exchange(server.httpPort, getRequest("/page", "Accept-Encoding: zstd\r\n"))
            assertEquals("zstd", zstd["content-encoding"])
            assertContentEquals("<p>campfire</p>".repeat(200).toByteArray(), ZstdInputStream(ByteArrayInputStream(zstd.body)).readAllBytes())
            assertNull(exchange(server.httpPort, getRequest("/page")).get("content-encoding"))
            val head = exchange(server.httpPort, "HEAD /page HTTP/1.1\r\nHost: chat.test\r\nConnection: close\r\nAccept-Encoding: br\r\n\r\n")
            assertNull(head["content-encoding"])
        } finally {
            server.stop()
        }
    }

    @Test
    fun forwardsClientAddresses() {
        val server = start()
        val untrusting = start("FORWARD_HEADERS" to "false")
        try {
            val reply = exchange(
                server.httpPort,
                getRequest("/headers", "X-Forwarded-For: 203.0.113.9\r\nX-Forwarded-Proto: https\r\nForwarded: for=1.2.3.4\r\n"),
            )
            assertEquals("for=203.0.113.9, 127.0.0.1 host=chat.test proto=https forwarded=- start=true peer=127.0.0.1", String(reply.body))
            val plain = exchange(untrusting.httpPort, getRequest("/headers", "X-Forwarded-For: 203.0.113.9\r\nX-Forwarded-Proto: https\r\n"))
            assertEquals("for=127.0.0.1 host=chat.test proto=http forwarded=- start=true peer=127.0.0.1", String(plain.body))
        } finally {
            server.stop()
            untrusting.stop()
        }
    }

    @Test
    fun theAppStillListensOnTheTargetPort() {
        val server = start()
        try {
            val reply = exchange(assertNotNull(server.targetPort), getRequest("/public"))
            assertEquals(200, reply.status)
            assertNull(reply["x-cache"])
            assertNull(reply["date"])
            assertEquals("tracked=1; path=/", reply["set-cookie"])
        } finally {
            server.stop()
        }
    }

    @Test
    fun theTargetPortIsLoopbackOnlyAndLimited() {
        val server = start("HTTP_READ_TIMEOUT" to "1", "MAX_REQUEST_BODY" to "10")
        try {
            val target = assertNotNull(server.targetPort)
            nonLoopbackAddress()?.let { address ->
                val reached = runCatching { Socket().use { it.connect(InetSocketAddress(address, target), 1000) } }.isSuccess
                assertTrue(!reached, "reachable on $address")
            }
            val large = exchange(target, "POST /upload HTTP/1.1\r\nHost: x\r\nConnection: close\r\nContent-Length: 11\r\n\r\nhello world")
            assertEquals(413, large.status)
            assertClosedWithin(target, "GET / HTTP/1.1\r\nHost: x\r\n", 5000)
        } finally {
            server.stop()
        }
    }

    @Test
    fun limitsRequestBodies() {
        val server = start("MAX_REQUEST_BODY" to "10")
        try {
            val small = exchange(server.httpPort, "POST /upload HTTP/1.1\r\nHost: x\r\nConnection: close\r\nContent-Length: 5\r\n\r\nhello")
            assertEquals(200 to "5 bytes", small.status to String(small.body))
            val large = exchange(server.httpPort, "POST /upload HTTP/1.1\r\nHost: x\r\nConnection: close\r\nContent-Length: 11\r\n\r\nhello world")
            assertEquals(413, large.status)
            assertEquals("bypass", large["x-cache"])
            val chunked = "POST /upload HTTP/1.1\r\nHost: x\r\nConnection: close\r\nTransfer-Encoding: chunked\r\n\r\n6\r\nhello \r\n5\r\nworld\r\n0\r\n\r\n"
            assertEquals(413, exchange(server.httpPort, chunked).status)
        } finally {
            server.stop()
        }
    }

    @Test
    fun closesIdleAndSlowConnections() {
        val server = start("HTTP_IDLE_TIMEOUT" to "3", "HTTP_READ_TIMEOUT" to "1", "HTTP_WRITE_TIMEOUT" to "2")
        try {
            Socket("127.0.0.1", server.httpPort).use { socket ->
                socket.soTimeout = 5000
                socket.getOutputStream().write("GET /private HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                val buffer = ByteArray(4096)
                val n = socket.getInputStream().read(buffer)
                assertTrue(n > 0 && String(buffer, 0, 12) == "HTTP/1.1 200")
                val started = System.nanoTime()
                assertEquals(-1, socket.getInputStream().read(buffer), "closed")
                val idle = (System.nanoTime() - started) / 1_000_000
                assertTrue(idle in 800..2500, "closed after $idle ms: the read timeout, the shorter of the two")
            }
            assertClosedWithin(server.httpPort, "GET / HTTP/1.1\r\nHost: x\r\n", 5000)
            Socket("127.0.0.1", server.httpPort).use { socket ->
                socket.soTimeout = 5000
                socket.getOutputStream().write("GET /slow HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                assertEquals(-1, socket.getInputStream().read(ByteArray(4096)), "dropped without a response")
            }
        } finally {
            server.stop()
        }
    }

    private fun assertClosedWithin(port: Int, partial: String, millis: Int) = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = millis
        socket.getOutputStream().write(partial.toByteArray())
        val n = try {
            socket.getInputStream().read(ByteArray(4096))
        } catch (_: SocketTimeoutException) {
            throw AssertionError("not closed within $millis ms")
        }
        assertEquals(-1, n, "closed")
    }

    /** This machine's address on its default route, if it has one. */
    private fun nonLoopbackAddress(): java.net.InetAddress? = runCatching {
        java.net.DatagramSocket().use { socket ->
            socket.connect(InetSocketAddress("192.0.2.1", 9))
            socket.localAddress.takeIf { !it.isLoopbackAddress && !it.isAnyLocalAddress }
        }
    }.getOrNull()
}
