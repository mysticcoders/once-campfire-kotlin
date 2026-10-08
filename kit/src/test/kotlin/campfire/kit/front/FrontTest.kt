package campfire.kit.front

import campfire.kit.Body
import campfire.kit.ChunkedBody
import campfire.kit.Handler
import campfire.kit.Headers
import campfire.kit.Request
import campfire.kit.RequestBodyTooLarge
import campfire.kit.Response
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The front middleware (`crates/kit/src/front/handler.rs`) called directly with a stub app: the
 * request-level checks of `crates/kit/tests/front.rs` that need no socket.
 */
class FrontTest {
    private val client = InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 50000)

    private fun front(vararg vars: Pair<String, String>, log: LogSink = LogSink {}): Front {
        val map = vars.toMap()
        return Front(FrontConfig.fromLookup { map[it] }, log)
    }

    private fun get(target: String, vararg pairs: Pair<String, String>, method: String = "GET", body: ByteArray = Request.EMPTY): Request {
        val headers = Headers().add("host", "chat.test")
        for ((n, v) in pairs) headers.add(n, v)
        return Request(method, target, headers, body, client)
    }

    private fun text(response: Response) = String(response.body.chunks().fold(ByteArray(0)) { a, b -> a + b })

    private class App : Handler {
        val renders = AtomicInteger()
        var last: Request? = null

        override fun handle(request: Request): Response {
            last = request
            return when (request.path) {
                "/public" -> Response(
                    200,
                    Headers().add("cache-control", "public, max-age=60").add("content-type", "text/css").add("etag", "\"v1\"")
                        .add("set-cookie", "tracked=1; path=/").add("vary", "Accept-Encoding"),
                    Body.of("render ${renders.getAndIncrement()}"),
                )
                "/private" -> Response(
                    200,
                    Headers().add("cache-control", "max-age=0, private, must-revalidate").add("vary", "Accept-Encoding").add("set-cookie", "session=1; path=/"),
                    Body.of("private"),
                )
                "/page" -> Response(200, Headers().add("content-type", "text/html; charset=utf-8"), Body.of("<p>campfire</p>".repeat(200)))
                "/not-modified" -> Response(304, Headers().add("content-type", "text/html").add("content-length", "10").add("etag", "\"x\""))
                else -> Response.text(200, "${request.body.size} bytes")
            }
        }
    }

    @Test
    fun cachesPublicResponses() {
        val app = App()
        val front = front()
        val first = front.handle(get("/public", "accept-encoding" to "identity"), app)
        assertEquals(200, first.status)
        assertEquals("miss", first.headers["x-cache"])
        assertNull(first.headers["set-cookie"], "cacheable responses lose their cookies")
        assertEquals(listOf("Accept-Encoding"), first.headers.all("vary"))
        assertEquals("render 0", text(first))

        val second = front.handle(get("/public?", "accept-encoding" to "identity"), app)
        assertEquals("hit", second.headers["x-cache"])
        assertEquals("render 0", text(second))
        assertEquals("\"v1\"", second.headers["etag"])

        val other = front.handle(get("/public", "accept-encoding" to "br"), app)
        assertEquals("miss" to "render 1", other.headers["x-cache"] to text(other), "another Accept-Encoding is another variant")
        val again = front.handle(get("/public", "accept-encoding" to "br"), app)
        assertEquals("hit" to "render 1", again.headers["x-cache"] to text(again))

        val revalidated = front.handle(get("/public", "accept-encoding" to "identity", "if-none-match" to "\"v1\""), app)
        assertEquals(304 to "hit", revalidated.status to revalidated.headers["x-cache"])
        assertEquals(0L, revalidated.body.length)
        assertNull(revalidated.headers["content-type"], "suppressed on a 304")

        val ranged = front.handle(get("/public", "accept-encoding" to "identity", "range" to "bytes=0-1"), app)
        assertEquals("bypass", ranged.headers["x-cache"], "ranges go to the app")
        assertEquals(3, app.renders.get())

        val long = "/public?pad=" + "x".repeat(4096)
        repeat(2) { assertEquals("bypass", front.handle(get(long, "accept-encoding" to "identity"), app).headers["x-cache"]) }
        assertEquals(5, app.renders.get())

        repeat(2) {
            val private = front.handle(get("/private"), app)
            assertEquals("miss", private.headers["x-cache"])
            assertEquals("session=1; path=/", private.headers["set-cookie"])
            assertEquals(listOf("Accept-Encoding"), private.headers.all("vary"))
        }
    }

    @Test
    fun headRequestsAreCachedWithoutABody() {
        val app = App()
        val front = front()
        assertEquals("miss", front.handle(get("/public", method = "HEAD"), app).headers["x-cache"])
        val hit = front.handle(get("/public", method = "HEAD"), app)
        assertEquals("hit", hit.headers["x-cache"])
        assertEquals(0L, hit.body.length)
    }

    @Test
    fun bypassedRequestsRepeatVary() {
        val app = App()
        val reply = front().handle(get("/headers", method = "POST"), app)
        assertEquals("bypass", reply.headers["x-cache"])
        assertEquals(listOf("Accept-Encoding"), reply.headers.all("vary"))
        val withVary = Handler { request -> app.handle(request).also { it.headers["vary"] = "Accept-Encoding" } }
        assertEquals(listOf("Accept-Encoding", "Accept-Encoding"), front().handle(get("/headers", method = "POST"), withVary).headers.all("vary"))
    }

    @Test
    fun compressesWhatTheAppLeftUnencoded() {
        val app = App()
        val zstd = front().handle(get("/page", "accept-encoding" to "zstd"), app)
        assertEquals("zstd", zstd.headers["content-encoding"])
        assertNull(front().handle(get("/page"), app).headers["content-encoding"])
        assertNull(front().handle(get("/page", "accept-encoding" to "gzip", method = "HEAD"), app).headers["content-encoding"])
        assertNull(front("GZIP_COMPRESSION_ENABLED" to "false").handle(get("/page", "accept-encoding" to "gzip"), app).headers["vary"])
    }

    @Test
    fun forwardsClientAddresses() {
        val app = App()
        front().handle(get("/headers", "x-forwarded-for" to "203.0.113.9", "x-forwarded-proto" to "https", "forwarded" to "for=1.2.3.4"), app)
        val seen = assertNotNull(app.last).headers
        assertEquals("203.0.113.9, 127.0.0.1", seen["x-forwarded-for"])
        assertEquals("chat.test", seen["x-forwarded-host"])
        assertEquals("https", seen["x-forwarded-proto"])
        assertNull(seen["forwarded"])
        assertTrue(seen["x-request-start"]!!.startsWith("t="))

        front("FORWARD_HEADERS" to "false").handle(get("/headers", "x-forwarded-for" to "203.0.113.9", "x-forwarded-proto" to "https"), app)
        val untrusted = assertNotNull(app.last).headers
        assertEquals("127.0.0.1", untrusted["x-forwarded-for"])
        assertEquals("http", untrusted["x-forwarded-proto"])

        val request = get("/headers", "x-request-start" to "t=1")
        request.putExtension(ConnInfo(InetSocketAddress(InetAddress.getByName("::ffff:10.0.0.1"), 1), tls = true))
        front("FORWARD_HEADERS" to "false").handle(request, app)
        assertEquals("10.0.0.1", app.last!!.headers["x-forwarded-for"])
        assertEquals("https", app.last!!.headers["x-forwarded-proto"])
        assertEquals("t=1", app.last!!.headers["x-request-start"])
    }

    @Test
    fun limitsRequestBodies() {
        val app = App()
        val front = front("MAX_REQUEST_BODY" to "10")
        val small = front.handle(get("/upload", "content-length" to "5", method = "POST", body = "hello".toByteArray()), app)
        assertEquals(200 to "5 bytes", small.status to text(small))
        val large = front.handle(get("/upload", "content-length" to "11", method = "POST", body = "hello world".toByteArray()), app)
        assertEquals(413, large.status)
        assertEquals(0L, large.body.length)
        val marked = get("/upload", method = "POST")
        marked.putExtension(RequestBodyTooLarge)
        assertEquals(413, front.handle(marked, app).status)
        assertEquals(200, front().handle(get("/upload", method = "POST", body = ByteArray(100)), app).status, "0 is no limit")
    }

    @Test
    fun suppressesHeadersABodilessStatusCantCarry() {
        val response = front().handle(get("/not-modified"), App())
        assertEquals(304, response.status)
        assertNull(response.headers["content-type"])
        assertNull(response.headers["content-length"])
        assertEquals("\"x\"", response.headers["etag"])
        val noContent = Response(204, Headers().add("content-type", "text/plain").add("content-length", "0"))
        Front.suppressBodilessHeaders(noContent)
        assertEquals("text/plain", noContent.headers["content-type"])
        assertNull(noContent.headers["content-length"])
    }

    @Test
    fun logsOneLinePerRequest() {
        val lines = ArrayList<String>()
        val front = front(log = { lines.add(it) })
        front.handle(get("/public?a=1", "user-agent" to "curl/8.7.1", "accept-encoding" to "identity"), App())
        val line = lines.single()
        assertTrue(Regex("""^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{6}Z  INFO thruster: Request """).containsMatchIn(line), line)
        val fields = line.substringAfter("Request ")
        assertTrue(
            Regex(
                "^path=/public status=200 dur=\\d+ method=GET req_content_length=0 req_content_type= resp_content_length=8 " +
                    "resp_content_type=text/css remote_addr=127.0.0.1:50000 user_agent=curl/8.7.1 cache=miss query=a=1 proto=\"HTTP/1.1\"$",
            ).matches(fields),
            fields,
        )
        front.handle(get("/x", "x-forwarded-for" to "203.0.113.9"), App())
        assertTrue(" remote_addr=203.0.113.9 " in lines[1], lines[1])
        front("LOG_REQUESTS" to "false", log = { lines.add(it) }).handle(get("/x"), App())
        assertEquals(2, lines.size)
    }

    @Test
    fun formatsAddressesLikeRust() {
        assertEquals("::1", Front.ipText(InetAddress.getByName("::1")))
        assertEquals("2001:db8::1:0:0:1", Front.ipText(InetAddress.getByName("2001:db8:0:0:1:0:0:1")))
        assertEquals("2001:db8:0:1:1:1:1:1", Front.ipText(InetAddress.getByName("2001:db8:0:1:1:1:1:1")))
        assertEquals("[::1]:80", Front.socketText(InetSocketAddress(InetAddress.getByName("::1"), 80)))
        assertEquals("127.0.0.1:80", Front.socketText(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 80)))
    }

    @Test
    fun cachedHitsHaveALength() {
        val app = Handler {
            Response(200, Headers().add("cache-control", "public, max-age=60"), Body.of("x".repeat(100))).also { it.putExtension(ChunkedBody) }
        }
        val front = front()
        assertNotNull(front.handle(get("/a"), app).extension<ChunkedBody>())
        assertNull(front.handle(get("/a"), app).extension<ChunkedBody>())
    }
}
