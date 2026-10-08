package campfire.assets

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `serve.rs` behavior the reference fixtures don't reach: header order, path cleaning, the
 * extensionless fallbacks, 416, and the precompressed-sibling lookup.
 */
class StaticFilesTest {
    private fun serve(path: String, method: String = "GET", acceptEncoding: String? = null, range: String? = null) =
        Assets.serve(StaticRequest(method, path, acceptEncoding, range))

    private val builtAt = Embedded.builtAt

    @Test
    fun fullResponsesHaveRacksHeaderOrderAndAreShared() {
        val response = serve("/robots.txt")!!
        assertEquals(
            listOf("last-modified" to builtAt, "content-type" to "text/plain", "Cache-Control" to "public, max-age=2592000", "content-length" to "99"),
            response.headers,
        )
        assertSame(response, serve("/robots.txt"))
    }

    @Test
    fun singleRangesSliceTheFile() {
        val full = serve("/robots.txt")!!.bodyBytes()
        val response = serve("/robots.txt", range = "bytes=2-5")!!
        assertEquals(206, response.status)
        assertEquals(
            listOf(
                "last-modified" to builtAt, "content-type" to "text/plain", "Cache-Control" to "public, max-age=2592000",
                "content-range" to "bytes 2-5/99", "content-length" to "4",
            ),
            response.headers,
        )
        assertContentEquals(full.copyOfRange(2, 6), response.bodyBytes())
    }

    @Test
    fun unsatisfiableRangesAre416() {
        val response = serve("/robots.txt", range = "bytes=200-")!!
        assertEquals(416, response.status)
        assertEquals(
            listOf("content-type" to "text/plain", "content-length" to "25", "x-cascade" to "pass", "content-range" to "bytes */99"),
            response.headers,
        )
        assertEquals("Byte range unsatisfiable\n", String(response.bodyBytes()))
    }

    @Test
    fun onlyGetAndHeadAreServed() {
        assertNull(serve("/robots.txt", method = "POST"))
        assertNotNull(serve("/robots.txt", method = "HEAD"))
    }

    @Test
    fun pathsAreCleanedLikeRack() {
        val robots = serve("/robots.txt")!!
        assertSame(robots, serve("/assets/../robots.txt"))
        assertSame(robots, serve("//robots.txt/"))
        assertSame(robots, serve("/%72obots.txt"))
        assertSame(robots, serve("/%FF/../robots.txt"))
        assertNull(serve("/robots.txt%00"))
        assertNull(serve("robots.txt"))
        assertEquals("/a/b", StaticFiles.cleanPath("/./a//b/"))
        assertEquals("a", StaticFiles.cleanPath("a"))
        assertNull(StaticFiles.cleanPath("/%FF"))
    }

    @Test
    fun extensionlessPathsFallBackToHtml() {
        val response = serve("/404")!!
        assertEquals("text/html", response.header("content-type"))
        assertContentEquals(serve("/404.html")!!.bodyBytes(), response.bodyBytes())
        assertNull(serve("/nope"))
    }

    @Test
    fun precompressedSiblingsAreServedToClientsThatAcceptThem() {
        val gz = Assets.manifest.first { it.first == "lexxy.js.gz" }.second
        val base = "/assets/" + gz.removeSuffix(".gz")
        assertNull(serve(base))
        val response = serve(base, acceptEncoding = "deflate, gzip;q=0.8")!!
        assertEquals(
            listOf(
                "last-modified" to builtAt, "content-type" to "text/plain", "Cache-Control" to "public, max-age=2592000",
                "content-length" to response.bodyLength.toString(), "vary" to "accept-encoding", "content-encoding" to "gzip",
            ),
            response.headers,
        )
        assertContentEquals(serve("/assets/$gz")!!.bodyBytes(), response.bodyBytes())
        assertEquals("application/x-gzip", serve("/assets/$gz")!!.header("content-type"))
    }

    @Test
    fun acceptEncodingMatchesWholeWords() {
        assertTrue(StaticFiles.accepts("gzip, deflate", "gzip"))
        assertTrue(StaticFiles.accepts("x-GZIP", "gzip"))
        assertFalse(StaticFiles.accepts("gzipped", "gzip"))
        assertFalse(StaticFiles.accepts("identity;gzip", "gzip"))
        assertTrue(StaticFiles.accepts("br;q=1", "br"))
    }
}
