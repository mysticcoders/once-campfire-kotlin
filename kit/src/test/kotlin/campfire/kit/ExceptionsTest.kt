package campfire.kit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The tests of `once-campfire-rust/crates/kit/src/exceptions.rs`. */
class ExceptionsTest {
    private fun text(response: Response) = String(Responses.bodyBytes(response.body)!!, Charsets.UTF_8)

    @Test
    fun jsonErrors() {
        val response = PublicExceptions.render(ErrorPages.NONE, 422, Mime.JSON, false)
        assertEquals("""{"status":422,"error":"Unprocessable Content"}""", text(response))
    }

    @Test
    fun htmlPages() {
        val pages = ErrorPages(mapOf(404 to "<h1>404</h1>".toByteArray()))
        val response = PublicExceptions.render(pages, 404, Mime.HTML, false)
        assertEquals(404, response.status)
        assertEquals("<h1>404</h1>", text(response))
        assertEquals("text/html; charset=UTF-8", response.headers["content-type"])
        val missing = PublicExceptions.render(pages, 400, null, false)
        assertEquals(400, missing.status)
        assertTrue(text(missing).isEmpty())
    }

    @Test
    fun xmlAndYamlErrors() {
        val xml = PublicExceptions.render(ErrorPages.NONE, 406, Mime.XML, false)
        assertEquals(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<hash>\n  <status type=\"integer\">406</status>\n  <error>Not Acceptable</error>\n</hash>\n",
            text(xml),
        )
        assertEquals("application/xml; charset=UTF-8", xml.headers["content-type"])
        assertEquals("124", xml.headers["content-length"])
        val yaml = PublicExceptions.render(ErrorPages.NONE, 406, Mime.YAML, false)
        assertEquals("---\n:status: 406\n:error: Not Acceptable\n", text(yaml))
    }

    @Test
    fun headErrorsAreEmptyInTheRequestFormat() {
        val head = PublicExceptions.render(ErrorPages.NONE, 404, Mime.ALL, true)
        assertEquals("*/*; charset=UTF-8", head.headers["content-type"])
        assertEquals("0", head.headers["content-length"])
        val html = PublicExceptions.render(ErrorPages.NONE, 404, null, true)
        assertEquals("text/html; charset=UTF-8", html.headers["content-type"])
    }

    @Test
    fun theReferencePagesAreOnTheClasspath() {
        for (status in listOf(404, 422, 500, 502)) {
            val page = ErrorPages.PUBLIC[status]
            assertTrue(page != null && String(page).contains("<html"), "$status")
        }
    }
}
