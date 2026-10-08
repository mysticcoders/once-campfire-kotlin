package campfire.kit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The tests of `once-campfire-rust/crates/kit/src/response.rs`. */
class ResponsesTest {
    @Test
    fun headerValuesWithControlCharactersGoOutAsPumaWritesThem() {
        fun values(value: String): List<String> = Response(200).header("x-test", "replaced").header("x-test", value).headers.all("x-test")
        assertEquals(listOf("a\tb"), values("a\tb"))
        assertEquals(listOf("é"), values("é"))
        assertEquals(listOf(""), values(""))
        assertEquals(listOf("x", "y; z"), values("x\ny; z"))
        assertEquals(listOf("", "a"), values("\na\n\n"))
        assertEquals(listOf("y"), values("x\r\ny"))
        assertTrue(values("x\ry").isEmpty())
        assertTrue(values("x\u0001y").isEmpty())
        assertTrue(values("x\u007fy").isEmpty())
        assertTrue(values("\n").isEmpty())
    }

    @Test
    fun contentDispositionLikeRails() {
        assertEquals("inline", Responses.contentDisposition("inline", null))
        assertEquals(
            "attachment; filename=\"logo.png\"; filename*=UTF-8''logo.png",
            Responses.contentDisposition("attachment", "logo.png"),
        )
        assertEquals(
            "inline; filename=\"resume 1.pdf\"; filename*=UTF-8''r%C3%A9sum%C3%A9%201.pdf",
            Responses.contentDisposition("inline", "résumé 1.pdf"),
        )
        assertEquals(
            "inline; filename=\"%3F%3F%22x%22.txt\"; filename*=UTF-8''%E6%97%A5%E6%9C%AC%22x%22.txt",
            Responses.contentDisposition("inline", "日本\"x\".txt"),
        )
        assertEquals(
            "attachment; filename=\"Lodz x.pdf\"; filename*=UTF-8''%C5%81%C3%B3d%C5%BA%20%C3%97.pdf",
            Responses.contentDisposition("attachment", "Łódź ×.pdf"),
        )
    }

    @Test
    fun cacheControlNormalization() {
        assertEquals(
            "max-age=300, public, stale-while-revalidate=604800",
            CacheControl(maxAge = 300, public = true, staleWhileRevalidate = 604800).toHeader(),
        )
        assertEquals("max-age=0, private, must-revalidate", CacheControl(maxAge = 0, mustRevalidate = true).toHeader())
        assertEquals("no-store", CacheControl(noStore = true, maxAge = 5).toHeader())
        assertNull(CacheControl().toHeader())
    }

    @Test
    fun sendTypesByExtension() {
        assertEquals("image/png", Responses.send(SendOptions(filename = "Logo.PNG"), Body.Empty).headers["content-type"])
        assertEquals("application/octet-stream", Responses.send(SendOptions(filename = ".png"), Body.Empty).headers["content-type"])
        assertEquals("application/octet-stream", Responses.send(SendOptions(), Body.Empty).headers["content-type"])
    }
}
