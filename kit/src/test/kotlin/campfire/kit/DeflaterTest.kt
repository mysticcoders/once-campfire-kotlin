package campfire.kit

import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The tests of `crates/kit/src/deflater.rs`, and the middleware's header changes. */
class DeflaterTest {
    private fun best(header: String): String? =
        RackDeflater.selectBestEncoding(listOf("gzip", "identity"), RackDeflater.parseAcceptEncoding(header))

    private fun gunzip(data: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(data)).readAllBytes()

    private fun joined(chunks: List<ByteArray>): ByteArray = chunks.fold(ByteArray(0)) { a, b -> a + b }

    private fun run(acceptEncoding: String?, response: () -> Response): Response {
        val headers = Headers()
        if (acceptEncoding != null) headers.add("accept-encoding", acceptEncoding)
        return RackDeflater().handle(Request("GET", "/rooms/1?x=1", headers)) { response() }
    }

    @Test
    fun encodingSelection() {
        assertEquals("gzip", best("gzip, deflate, br"))
        assertEquals("identity", best(""))
        assertEquals("identity", best("br"))
        assertEquals("identity", best("gzip;q=0"))
        assertEquals("identity", best("identity;q=0.5, gzip;q=0.1"))
        assertEquals("gzip", best("*"))
        assertNull(best("identity;q=0, *;q=0"))
        assertNull(best("gzip;q=0, identity;q=0"))
    }

    @Test
    fun qValuesReadLikeRack() {
        assertEquals("identity", best("identity;q="))
        assertNull(best("identity;q=0"))
        assertEquals("gzip", best("gzip;q="))
        assertEquals("gzip", best("gzip;q=abc"))
        assertEquals("gzip", best("gzip;Q=0.5, identity;q=0.9"))
        assertEquals("gzip", best("gzip;q=.5"))
        assertEquals("identity", best("gzip;q=."))
        assertEquals("identity", best("gzip;q=0..5, identity;q=0.1"))
        assertEquals("identity", best("gzip;q=0.5.1, identity;q=0.6"))
        assertEquals(
            listOf("gzip" to 0.5, "identity" to 1.0, "br" to 1.0),
            RackDeflater.parseAcceptEncoding("gzip;q=0.5.1, identity;q=, br;q=1."),
        )
    }

    @Test
    fun words() {
        assertTrue(Ascii.hasWord("no-transform, public", "no-transform"))
        assertFalse(Ascii.hasWord("xno-transform", "no-transform"))
        assertTrue(Ascii.hasWord("identity", "identity"))
    }

    @Test
    fun gzipRoundTripAndEmptyBodies() {
        for (input in listOf(ByteArray(0), "hello world".toByteArray())) {
            val bytes = joined(RackDeflater.gzipStream(listOf(input), 0))
            assertTrue(bytes.size >= 20)
            assertContentEquals(input, gunzip(bytes))
        }
        val header = RackDeflater.gzipStream(emptyList(), 0x01020304)[0]
        assertContentEquals(byteArrayOf(0x1f, 0x8b.toByte(), 8, 0, 4, 3, 2, 1, 0, 3), header.copyOf(10))
    }

    @Test
    fun digestedBodiesAreGzippedOnce() {
        val body = "<a href=\"/rooms/1\">Room</a>".repeat(300).toByteArray()
        val first = RackDeflater.gzipDigested(body, BodyDigest(sha256(body)), 0)
        assertContentEquals(joined(RackDeflater.gzipStream(listOf(body), 0)), first, "what gzipStream sends")
        val kept = assertNotNull(RackDeflater.GZIPPED.get(GzipKey(sha256(body), 0)))
        val again = RackDeflater.gzipDigested(body, BodyDigest(sha256(body)), 0)
        assertSame(kept, again, "not gzipped again")

        val other = "<a href=\"/rooms/2\">Room</a>".repeat(300).toByteArray()
        val gzip = RackDeflater.gzipDigested(other, BodyDigest(sha256(other)), 0)
        assertContentEquals(joined(RackDeflater.gzipStream(listOf(other), 0)), gzip)
    }

    @Test
    fun gzipRoundTripOfAPageLargerThanTheWindow() {
        val page = (0 until 3000).joinToString("") { n -> "<div id=\"message_$n\" class=\"message\">${"hello there ".repeat(n % 13)}</div>\n" }.toByteArray()
        assertTrue(page.size > 4 * 32 * 1024)
        val chunks = (page.indices step 40_000).map { page.copyOfRange(it, minOf(page.size, it + 40_000)) }
        assertContentEquals(page, gunzip(joined(RackDeflater.gzipStream(chunks, 0))))
    }

    @Test
    fun gzipsAndMergesVary() {
        val response = run("gzip, deflate") {
            Response(200, Headers().add("content-type", "text/html").add("vary", "Accept").add("content-length", "5"), Body.of("hello"))
        }
        assertEquals("gzip", response.headers["content-encoding"])
        assertEquals(listOf("Accept,Accept-Encoding"), response.headers.all("vary"))
        assertNull(response.headers["content-length"])
        assertNotNull(response.extension<ChunkedBody>())
        assertEquals("hello", String(gunzip(joined(response.body.chunks()))))

        val noVary = run("gzip") { Response.text(200, "hi") }
        assertEquals("Accept-Encoding", noVary.headers["vary"])
        val already = run("gzip") { Response(200, Headers().add("vary", "accept-encoding"), Body.of("hi")) }
        assertEquals("accept-encoding", already.headers["vary"])
        val star = run("gzip") { Response(200, Headers().add("vary", "*"), Body.of("hi")) }
        assertEquals("*", star.headers["vary"])
    }

    @Test
    fun identityOnlyMergesVary() {
        val response = run(null) { Response.text(200, "hello") }
        assertNull(response.headers["content-encoding"])
        assertEquals("Accept-Encoding", response.headers["vary"])
        assertNull(response.extension<ChunkedBody>())
    }

    @Test
    fun noAcceptableEncodingIsA406() {
        val response = run("identity;q=0, *;q=0") { Response.text(200, "hello") }
        assertEquals(406, response.status)
        val message = "An acceptable encoding for the requested resource /rooms/1?x=1 could not be found."
        assertEquals(message, String(joined(response.body.chunks())))
        assertEquals("text/plain", response.headers["content-type"])
        assertEquals(message.length.toString(), response.headers["content-length"])
    }

    @Test
    fun exemptions() {
        for (status in listOf(101, 204, 304)) assertNull(run("gzip") { Response(status) }.headers["content-encoding"], "$status")
        assertNull(run("gzip") { Response(200, Headers().add("cache-control", "no-transform"), Body.of("x")) }.headers["vary"])
        assertEquals("br", run("gzip") { Response(200, Headers().add("content-encoding", "br"), Body.of("x")) }.headers["content-encoding"])
        assertEquals("gzip", run("gzip") { Response(200, Headers().add("content-encoding", "identity"), Body.of("x")) }.headers["content-encoding"])
        val static = run("gzip") { Response(200, Headers().add("content-length", "0")).also { it.putExtension(StaticFile) } }
        assertNull(static.headers["content-encoding"])
        val rendered = run("gzip") { Response(200, Headers().add("content-length", "0")) }
        assertEquals("gzip", rendered.headers["content-encoding"], "a length the app didn't set itself")
    }

    @Test
    fun theMtimeComesFromLastModified() {
        val response = run("gzip") { Response(200, Headers().add("last-modified", "Thu, 01 Jan 1970 00:01:40 GMT"), Body.of("x")) }
        val data = joined(response.body.chunks())
        assertEquals(100, (data[4].toInt() and 0xff) or ((data[5].toInt() and 0xff) shl 8))
        assertEquals(3, data[9].toInt())
    }

    @Test
    fun pagesAreSplicedFromTheirParts() {
        val fragment = Fragment("<div class=\"message\">${"hello ".repeat(400)}</div>".toByteArray())
        val builder = PageParts.Builder().append("<html><body>")
        builder.fragment(fragment)
        val parts = builder.append("</body></html>").build()
        val response = run("gzip") { Response(200, Headers().add("content-type", "text/html"), Body.Page(parts)) }
        val data = joined(response.body.chunks())
        assertContentEquals(joined(parts.chunks()), gunzip(data))
        assertContentEquals(joined(parts.gzipChunks(0)), data)
    }
}
