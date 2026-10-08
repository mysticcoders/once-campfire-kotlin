package campfire.richtext

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `once-campfire-rust/crates/richtext/src/sanitizer.rs`'s tests. */
class SanitizerTest {
    @Test
    fun scrubsLikeRails() {
        val list = SafeList.contentFilter
        assertEquals("<div><a>x</a></div>", Sanitizer.sanitize("<div><a href=\"javascript:alert(1)\">x</a></div>", list))
        assertEquals("<a href=\"/x\">x</a>", Sanitizer.sanitize("<a href=\"/x\" onmouseover=\"alert(1)\">x</a>", list))
        assertEquals("<a>x</a>", Sanitizer.sanitize("<a href=\"data:text/html,pwned\">x</a>", list))
        assertEquals("<a href=\"a%20b\">x</a>", Sanitizer.sanitize("<a href=\"a b\">x</a><!-- c -->", list))
        assertEquals("yz", Sanitizer.sanitize("<svg><a>x</a></svg>y<script>z</script>", list))
    }

    /** The outputs are rails-html-sanitizer's, from the reference image. */
    @Test
    fun reEscapesUrlAttributesAsEachAttributeIsScrubbed() {
        val list = SafeList(listOf("img", "a"), listOf("src", "href", "name", "title", "alt"))
        assertEquals("<img href=\"%20javascript:alert(1)\">", Sanitizer.sanitize("<img src=\" \" href=\" javascript:alert(1)\">", list))
        assertEquals("<img>", Sanitizer.sanitize("<img href=\" javascript:alert(1)\">", list))
        assertEquals(
            "<a href=\"a%20b\" name=\"c%20d\" title=\"x y\">t</a>",
            Sanitizer.sanitize("<a href=\"a b\" name=\"c d\" title=\"x y\">t</a>", list),
        )
        assertEquals("<img src=\"a%20b\" alt=\"q\">", Sanitizer.sanitize("<img src=\"a b\" alt=\"q\">", list))
    }

    @Test
    fun keepsOnlyLexxysHighlightColorsInStyle() {
        val list = SafeList.actionText
        val highlight = "<mark style=\"color: var(--highlight-1);background-color: var(--highlight-bg-2);\">x</mark>"
        assertEquals(highlight, Sanitizer.sanitize(highlight, list))
        assertEquals(
            "<span style=\"color: #f00;\">x</span>",
            Sanitizer.sanitize("<span style=\"color: #f00; position: fixed; top: 0\">x</span>", list),
        )
        val rgb = "<span style=\"COLOR: rgb(1 2 3 / 50%)\">x</span>"
        assertEquals(rgb, Sanitizer.sanitize(rgb, list))
        for (hostile in listOf(
            "background-color: url(https://evil.test/beacon)",
            "color: expression(alert(1))",
            "background-color: red; background-image: url(x)",
            "color: \\72 ed",
            "color: red /* */",
            "width: 100000px",
            "",
        )) {
            val html = Sanitizer.sanitize("<span style=\"$hostile\">x</span>", list)
            assertTrue(
                !html.contains("url") && !html.contains("expression") && !html.contains('\\') && !html.contains("width"),
                "$hostile: $html",
            )
        }
        assertEquals("<span>x</span>", Sanitizer.sanitize("<span style=\"position: fixed\">x</span>", list))
    }

    @Test
    fun checksUrisLikeLoofah() {
        assertFalse(Sanitizer.allowedUri("javascript:alert(1)"))
        assertFalse(Sanitizer.allowedUri("java\nscript:alert(1)"))
        assertFalse(Sanitizer.allowedUri("javascript&#58;alert(1)"))
        assertFalse(Sanitizer.allowedUri("&#106;avascript:alert(1)"))
        assertFalse(Sanitizer.allowedUri("javascript&colon;alert(1)"))
        assertTrue(Sanitizer.allowedUri("/rooms/1"))
        assertTrue(Sanitizer.allowedUri("https://example.com"))
        assertTrue(Sanitizer.allowedUri("data:image/png;base64,xx"))
        assertFalse(Sanitizer.allowedUri("data:text/html,xx"))
        assertEquals("<a>x</a>", Sanitizer.sanitize("<a href=\"&amp;#0000000000038;#106;avascript:alert(1)\">x</a>", SafeList.contentFilter))
    }

    @Test
    fun unescapesHtmlLikeCgi() {
        for ((escaped, unescaped) in listOf(
            "&#65;" to "A",
            "&#X41;" to "A",
            "&#00000000065;" to "A",
            "&#0000000000000000000000065;" to "A",
            "&#x000000041;" to "A",
            "&#x0000000000000000000041;" to "A",
            "&#00000000000000000000000000000000106;avascript" to "javascript",
            "&#0;" to "\u0000",
            "&#65535;" to "￿",
            "&#1114110;" to String(Character.toChars(0x10fffe)),
            "&#1114111;" to "&#1114111;",
            "&#x10FFFF;" to "&#x10FFFF;",
            "&#x110000;" to "&#x110000;",
            "&#99999999999999999999;" to "&#99999999999999999999;",
            "&#18446744073709551615;" to "&#18446744073709551615;",
            "&#x10000000000000041;" to "&#x10000000000000041;",
            "&#;" to "&#;",
            "&#x;" to "&#x;",
            "&#65" to "&#65",
            "&#0x41;" to "&#0x41;",
            "&amp;#65;" to "&#65;",
        )) {
            assertEquals(unescaped, Sanitizer.cgiUnescapeHtml(escaped), escaped)
        }
    }
}
