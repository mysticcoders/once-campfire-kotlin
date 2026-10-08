package campfire.kit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The tests of `once-campfire-rust/crates/kit/src/format.rs`. */
class FormatTest {
    private fun symbols(formats: List<Mime>): List<String> = formats.map { it.symbol }

    private fun fmts(accept: String?, path: String, xhr: Boolean): List<String> =
        symbols(Format.formats(NegotiationInput(accept = accept, path = path, xhr = xhr)))

    @Test
    fun browserAcceptFallsBackToHtml() {
        val chrome = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
        assertEquals(listOf("html"), fmts(chrome, "/rooms/1", false))
        assertEquals(listOf("html"), fmts(null, "/rooms/1", false))
        assertEquals(listOf("*/*"), fmts("*/*", "/rooms/1", false))
    }

    @Test
    fun turboFormSubmission() {
        val turbo = "text/vnd.turbo-stream.html, text/html, application/xhtml+xml"
        assertEquals(listOf("turbo_stream", "html"), fmts(turbo, "/rooms/1/messages", false))
    }

    @Test
    fun qualityOrdering() {
        assertEquals(listOf("json", "html"), fmts("text/html;q=0.5, application/json", "/", false))
        assertEquals(listOf("html"), fmts("application/json, */*", "/", false))
        assertEquals(listOf("html"), fmts("*/*, application/json;q=0.1", "/", false))
        assertEquals(listOf("json", "html"), fmts("application/json;q=0.1,text/html;q=0.1", "/", false))
    }

    @Test
    fun qValuesReadLikeRails() {
        val cases = listOf(
            "text/html;q=, application/json" to listOf("html", "json"),
            "text/html; q=, application/json;q=0.5" to listOf("html", "json"),
            "text/html;q=;q=, application/json;q=0.5" to listOf("html", "json"),
            "text/html;q=0.5, application/json;q=" to listOf("json", "html"),
            "text/html;q=;x=1, application/json" to listOf("json", "html"),
            "text/html;q=;q=0.9, application/json;q=0.5" to listOf("json", "html"),
            "text/html;q=0.4;q=0.9, application/json;q=0.5" to listOf("json", "html"),
            "text/html;q=0.5.1, application/json;q=0.6" to listOf("json", "html"),
            "text/html;q=0.5.1.2, application/json;q=0.49" to listOf("html", "json"),
            "text/html;q=\"0.9\", application/json;q=0.5" to listOf("html", "json"),
            "text/html;q=\"\"0.9, application/json;q=0.5" to listOf("json", "html"),
            "text/html;q=1e-1, application/json;q=0.5" to listOf("json", "html"),
            "text/html;q=1_0, application/json;q=5" to listOf("html", "json"),
            "text/html;q=abc, application/json;q=0.1" to listOf("json", "html"),
            "text/html;q=+0.3, application/json;q=0.2" to listOf("html", "json"),
            "text/html;q= 0.3, application/json;q=0.2" to listOf("html", "json"),
            "text/html;q=1e17, application/json;q=1e18" to listOf("json", "html"),
            "text/html;q=1e19, application/json;q=1e20" to listOf("json", "html"),
            "text/html;q=-0.001, application/json;q=0" to listOf("html", "json"),
            "application/json;q=0, text/html;q=-0.001" to listOf("json", "html"),
            "text/html;q=0.5, application/json;q=+0x1" to listOf("json", "html"),
            "text/html;q=0.5, application/json;q=0x1" to listOf("html", "json"),
        )
        for ((accept, order) in cases) assertEquals(order, symbols(Format.parseAccept(accept)), accept)
        assertEquals(listOf("json", "*/*"), symbols(Format.parseAccept("*/*;q=, application/json;q=0.5")))
        assertEquals(listOf("html", "json"), symbols(Format.parseAccept("text/html;q=1e400, application/json")))
        assertEquals(listOf("json", "html"), symbols(Format.parseAccept("text/html;q=-1e400, application/json")))
    }

    @Test
    fun singleTypes() {
        assertEquals(listOf("json"), fmts("application/json", "/", false))
        assertEquals(listOf("json"), fmts("application/json; charset=utf-8", "/", false))
        assertEquals(listOf("svg"), fmts("image/svg+xml", "/", false))
        assertEquals(emptyList(), fmts("application/x-unknown", "/", false))
        assertFailsWith<InvalidMimeType> { Format.formats(NegotiationInput(accept = "garbage", path = "/")) }
    }

    @Test
    fun wildcardsExpandInRegistrationOrder() {
        assertEquals(listOf("html", "text", "js"), fmts("text/*", "/", false).subList(0, 3))
        assertTrue("turbo_stream" in fmts("text/*", "/", false))
    }

    @Test
    fun xmlFolding() {
        assertEquals(listOf("xml", "rss"), fmts("text/xml, application/rss+xml", "/", false))
        assertEquals(listOf("rss", "xml"), fmts("application/xml, application/rss+xml", "/", false))
        assertEquals(listOf("html", "xml"), fmts("text/xml;q=0.9, application/xml;q=0.5, text/html", "/", false))
    }

    @Test
    fun pathExtensionAndFormatParam() {
        assertEquals(listOf("svg"), fmts(null, "/users/1/avatar.svg", false))
        assertEquals(listOf("json"), fmts(null, "/messages.json", false))
        assertEquals(listOf("html"), fmts(null, "/x.unknownext", false))
        val input = NegotiationInput(formatParam = "json", accept = "text/html", path = "/")
        assertEquals(listOf("json"), symbols(Format.formats(input)))
        assertTrue(Format.formats(NegotiationInput(formatParam = "nope", path = "/")).isEmpty())
    }

    @Test
    fun xhrDefaultsToJs() {
        assertEquals(listOf("js"), fmts(null, "/", true))
        val input = NegotiationInput(xhr = true, contentType = "application/json", path = "/")
        assertEquals(listOf("json"), symbols(Format.formats(input)))
    }

    @Test
    fun negotiation() {
        assertEquals(Mime.HTML, Format.negotiate(listOf(Mime.TURBO_STREAM, Mime.HTML), listOf(Mime.HTML, Mime.JSON)))
        assertEquals(Mime.HTML, Format.negotiate(listOf(Mime.ALL), listOf(Mime.HTML, Mime.JSON)))
        assertNull(Format.negotiate(listOf(Mime.PNG), listOf(Mime.HTML, Mime.JSON)))
        assertEquals(Mime.PNG, Format.negotiate(listOf(Mime.PNG), listOf(Mime.HTML, Mime.ALL)))
        assertNull(Format.negotiate(emptyList(), listOf(Mime.HTML)))
    }

    @Test
    fun varyHeaderOnlyForAcceptNegotiation() {
        assertTrue(Format.shouldApplyVaryHeader(NegotiationInput(accept = "application/json")))
        assertTrue(!Format.shouldApplyVaryHeader(NegotiationInput(accept = "text/html, */*")))
        assertTrue(!Format.shouldApplyVaryHeader(NegotiationInput(formatParam = "json", accept = "application/json")))
    }
}
