package campfire.kit.front

import campfire.kit.Body
import campfire.kit.ChunkedBody
import campfire.kit.Headers
import campfire.kit.Request
import campfire.kit.Response
import com.github.luben.zstd.ZstdInputStream
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The tests of `crates/kit/src/front/compression.rs`. */
class CompressionTest {
    private fun response(contentType: String, body: String): Response {
        val response = Response(200, body = Body.of(body))
        if (contentType.isNotEmpty()) response.headers["content-type"] = contentType
        return response
    }

    private fun negotiation(acceptEncoding: String): Negotiation =
        Compression(32, false).negotiate(Request("GET", "/", Headers().add("accept-encoding", acceptEncoding)))

    private fun apply(acceptEncoding: String, response: Response): Response =
        Compression(32, false).apply(negotiation(acceptEncoding), response, HeaderMerge.APPEND)

    private fun bytes(response: Response): ByteArray = response.body.chunks().fold(ByteArray(0)) { a, b -> a + b }

    private fun gunzip(data: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(data)).readAllBytes()

    /** The FCOMMENT field of a gzip header that has no other optional fields. */
    private fun gzipComment(data: ByteArray): String? {
        if (data[3].toInt() and 0x10 == 0) return null
        val end = (10 until data.size).first { data[it] == 0.toByte() }
        return String(data, 10, end - 10, Charsets.ISO_8859_1)
    }

    @Test
    fun selectsZstdAtEqualQuality() {
        assertEquals(Encoding.ZSTD, Compression.selectEncoding("GET", "gzip, deflate, br, zstd"))
        assertEquals(Encoding.GZIP, Compression.selectEncoding("GET", "gzip, zstd;q=0.5"))
        assertEquals(Encoding.GZIP, Compression.selectEncoding("GET", "gzip"))
        assertEquals(Encoding.ZSTD, Compression.selectEncoding("GET", "zstd"))
        assertEquals(Encoding.NONE, Compression.selectEncoding("GET", "gzip;q=0, br"))
        assertEquals(Encoding.NONE, Compression.selectEncoding("GET", null))
        assertEquals(Encoding.NONE, Compression.selectEncoding("HEAD", "gzip"))
        assertEquals(Encoding.GZIP, Compression.selectEncoding("GET", " GZIP ; q=0.8 , zstd;q=0.7"))
        assertEquals(Encoding.NONE, Compression.selectEncoding("GET", "gzip;q=abc"))
    }

    @Test
    fun filtersCompressedTypes() {
        assertTrue(Compression.contentTypeFilter("text/html; charset=utf-8"))
        assertTrue(Compression.contentTypeFilter("image/svg+xml"))
        assertFalse(Compression.contentTypeFilter("image/png"))
        assertFalse(Compression.contentTypeFilter("image/jpeg"))
        assertFalse(Compression.contentTypeFilter("video/mp4"))
        assertFalse(Compression.contentTypeFilter("application/zip"))
        assertFalse(Compression.contentTypeFilter("application/gzip"))
    }

    @Test
    fun compressesUnencodedBodiesWithZstd() {
        val body = "hello campfire ".repeat(200)
        val response = apply("zstd", response("text/html; charset=utf-8", body))
        assertEquals("zstd", response.headers["content-encoding"])
        assertEquals(listOf("Accept-Encoding"), response.headers.all("vary"))
        assertContentEquals(body.toByteArray(), ZstdInputStream(ByteArrayInputStream(bytes(response))).readAllBytes())
    }

    @Test
    fun compressesWithGzipAndAJitterComment() {
        val body = "hello campfire ".repeat(200)
        val response = apply("gzip", response("text/css", body))
        assertEquals("gzip", response.headers["content-encoding"])
        val data = bytes(response)
        assertEquals(body, String(gunzip(data)))
        val comment = assertNotNull(gzipComment(data))
        assertTrue(comment.isNotEmpty() && comment.length <= 32 && "Padding-Padding-Padding-Padding-P".startsWith(comment))
        assertEquals(0xff, data[9].toInt() and 0xff, "flate2's unknown OS")
    }

    @Test
    fun framesLikeGoSServer() {
        val small = apply("gzip", response("text/css", "hello campfire ".repeat(200)))
        assertNull(small.extension<ChunkedBody>(), "under Go's 2 KB buffer: a length")
        assertNull(small.headers["content-length"])
        val random = java.util.Random(1)
        val noisy = (0 until 8000).map { 'a' + random.nextInt(26) }.joinToString("")
        val large = apply("gzip", response("text/css", noisy))
        assertNotNull(large.extension<ChunkedBody>(), "over Go's 2 KB buffer: chunked")
        val image = apply("gzip", response("image/png", "x".repeat(70_000)))
        assertNull(image.headers["content-encoding"])
        assertNotNull(image.extension<ChunkedBody>(), "streamed past the 64 KB jitter buffer")
    }

    @Test
    fun withoutJitterNoComment() {
        val compression = Compression(0, false)
        val request = Request("GET", "/", Headers().add("accept-encoding", "gzip"))
        val response = compression.apply(compression.negotiate(request), response("text/css", "hello campfire ".repeat(200)), HeaderMerge.APPEND)
        val data = bytes(response)
        assertNull(gzipComment(data))
        assertEquals("hello campfire ".repeat(200), String(gunzip(data)))
    }

    @Test
    fun leavesEncodedSmallAndIncompressibleBodiesAlone() {
        val body = "x".repeat(2000)
        val encoded = response("text/html", body)
        encoded.headers["content-encoding"] = "gzip"
        encoded.headers["vary"] = "Accept-Encoding"
        val passed = apply("gzip", encoded)
        assertEquals("gzip", passed.headers["content-encoding"])
        assertEquals(listOf("Accept-Encoding", "Accept-Encoding"), passed.headers.all("vary"))
        assertEquals(2000, bytes(passed).size)

        assertNull(apply("gzip", response("text/html", "small")).headers["content-encoding"])
        assertNull(apply("gzip", response("image/png", body)).headers["content-encoding"])
        val plain = apply("", response("text/html", body))
        assertNull(plain.headers["content-encoding"])
        assertEquals(2000, bytes(plain).size)
    }

    @Test
    fun varyIsReplacedByTheCacheAndAppendedOtherwise() {
        val withVary = response("text/html", "x")
        withVary.headers["vary"] = "Accept"
        val replaced = Compression(32, false).apply(negotiation("gzip"), withVary, HeaderMerge.REPLACE)
        assertEquals(listOf("Accept"), replaced.headers.all("vary"))
        val plain = Compression(32, false).apply(negotiation("gzip"), response("text/html", "x"), HeaderMerge.REPLACE)
        assertEquals(listOf("Accept-Encoding"), plain.headers.all("vary"))
    }

    @Test
    fun sniffsAMissingContentType() {
        val response = apply("gzip", response("", "<!DOCTYPE html><p>".repeat(100)))
        assertEquals("text/html; charset=utf-8", response.headers["content-type"])
        assertEquals("gzip", response.headers["content-encoding"])
        assertEquals("text/plain; charset=utf-8", Compression.detectContentType("plain words".toByteArray()))
        assertEquals("image/png", Compression.detectContentType(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0)))
        assertEquals("application/octet-stream", Compression.detectContentType(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun guardVetoesUserSpecificResponses() {
        val compression = Compression(32, true)
        val withCookie = Request("GET", "/", Headers().add("accept-encoding", "gzip").add("cookie", "a=b"))
        val response = compression.apply(compression.negotiate(withCookie), response("text/html", "x".repeat(2000)), HeaderMerge.APPEND)
        assertNull(response.headers["content-encoding"])
        val request = Request("GET", "/", Headers().add("accept-encoding", "gzip"))
        val private = Response(200, Headers().add("cache-control", "private").add("content-type", "text/html"), Body.of("x".repeat(2000)))
        assertNull(compression.apply(compression.negotiate(request), private, HeaderMerge.APPEND).headers["content-encoding"])
        val varyCookie = Response(200, Headers().add("vary", "Accept, Cookie").add("content-type", "text/html"), Body.of("x".repeat(2000)))
        assertNull(compression.apply(compression.negotiate(request), varyCookie, HeaderMerge.REPLACE).headers["content-encoding"])
    }

    @Test
    fun theVetoHeaderIsNeverSent() {
        val vetoed = response("text/html", "x".repeat(2000))
        vetoed.headers[NO_COMPRESSION] = "1"
        val response = apply("gzip", vetoed)
        assertNull(response.headers["content-encoding"])
        assertFalse(NO_COMPRESSION in response.headers)
    }

    @Test
    fun crc32cMatchesTheStandardCheckValue() {
        assertEquals(0xE3069283.toInt(), Compression.crc32c("123456789".toByteArray()))
    }
}
