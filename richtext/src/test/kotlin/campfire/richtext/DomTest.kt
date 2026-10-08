package campfire.richtext

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `once-campfire-rust/crates/richtext/src/dom.rs`'s tests. Gumbo's answers come from Nokogiri
 * 1.19.4 in the reference image.
 */
class DomTest {
    private fun roundtrip(html: String): String {
        val dom = Dom()
        return dom.toHtml(dom.parseFragment(html))
    }

    /** The parse's outcome: "ok", or the [ParseError] it raised. */
    private fun parse(html: String): String = try {
        Dom().parseFragment(html)
        "ok"
    } catch (e: RichTextError.Parse) {
        e.error.name
    }

    private fun numberedAttributes(range: IntRange): String = range.joinToString(" ") { "a$it=$it" }

    private val ok = "ok"
    private val tooDeep = ParseError.TreeDepthExceeded.name
    private val tooMany = ParseError.TooManyAttributes.name

    @Test
    fun serializesLikeNokogiri() {
        assertEquals("x", roundtrip("<td>x</td>"))
        assertEquals("<p></p><table><tbody><tr><td>a</td></tr></tbody></table>", roundtrip("<p><table><tr><td>a</td></tr></table>"))
        assertEquals(
            "<a title=\"a<b>c\" href=\"x&amp;y&nbsp;z\">t&lt;&nbsp;&gt;\"'</a>",
            roundtrip("<a title='a<b>c' href=\"x&y z\">t&lt; >\"'</a>"),
        )
        val dom = Dom()
        val f = dom.parseFragment("<a title='a<b>c'>d>e</a>")
        assertEquals("<a title=\"a&lt;b&gt;c\">d&gt;e</a>", dom.toHtmlWithEscapedAttributeBrackets(f))
        assertEquals("<pre>\nx</pre>", roundtrip("<pre>\n\nx</pre>"))
        assertEquals("<noscript><b>x</b></noscript>", roundtrip("<noscript><b>x</b></noscript>"))
        assertEquals("<!--?php x ?-->", roundtrip("<?php x ?>"))
        assertEquals("<svg viewBox=\"0 0 1 1\"><clipPath></clipPath></svg>", roundtrip("<SVG viewBox='0 0 1 1'><CLIPPATH/></SVG>"))
    }

    @Test
    fun dropsOnlyALeadingByteOrderMark() {
        assertEquals("﻿x", roundtrip("﻿﻿x"))
        assertEquals("<script></script>﻿x", roundtrip("<script></script>﻿x"))
    }

    @Test
    fun enforcesGumboTreeDepthLimit() {
        fun b(n: Int) = "<b>".repeat(n)
        assertEquals(ok, parse(b(400)))
        assertEquals(tooDeep, parse(b(401)))
        assertEquals(tooDeep, parse("${b(401)}x"))
        assertEquals(tooDeep, parse("${b(400)}<p>"))
        assertEquals(tooDeep, parse("${b(397)}<table><td>"))
    }

    @Test
    fun countsOpenElementsAsGumboDoesRatherThanTheFinalTreesDepth() {
        fun b(n: Int) = "<b>".repeat(n)
        fun s(n: Int) = "<span>".repeat(n)
        fun div(n: Int) = "<div>".repeat(n)
        assertEquals(ok, parse("${b(400)}<br>"))
        assertEquals(ok, parse("${b(400)}</p>"))
        assertEquals(tooDeep, parse(b(401) + "</b>".repeat(401)))
        assertEquals(ok, parse("<b>${s(300)}${div(10)}</b>".repeat(3)))
        assertEquals(tooDeep, parse("<b>${s(390)}${div(10)}</b>${div(300)}"))
        val bs = (1..399).joinToString("") { "<b id=$it>" }
        val reopened = "<p>$bs</p><div><div><table>x"
        assertEquals(ok, parse(reopened))
        assertEquals(tooDeep, parse("$reopened<!---->"))
    }

    @Test
    fun enforcesGumboAttributeLimit() {
        assertEquals(ok, parse("<p ${numberedAttributes(1..400)}>x</p>"))
        assertEquals(tooMany, parse("<p ${numberedAttributes(1..401)}>x</p>"))
        assertEquals(ok, parse("<p ${List(1000) { "a=1" }.joinToString(" ")}>x</p>"))
        assertEquals(ok, parse("<textarea><p ${numberedAttributes(1..401)}>"))
    }

    @Test
    fun countsAttributesAsGumbosTokenizerDoes() {
        val attributes = numberedAttributes(1..400)
        assertEquals(tooMany, parse("<p $attributes a1=again>x</p>"))
        assertEquals(tooMany, parse("<p>x</p $attributes a401>"))
        assertEquals(tooMany, parse("<p $attributes a401"))
    }

    @Test
    fun keepsWhatTheTokenizerAlreadyDeduplicated() {
        assertEquals("<p title=\"a\" id=\"c\">x</p>", roundtrip("<p title=a TITLE=b id=c title=d>x</p>"))
        assertEquals(
            "<svg xlink:href=\"a\" href=\"b\" viewBox=\"c\"><a xlink:href=\"d\">x</a></svg>",
            roundtrip("<svg xlink:href=a href=b viewbox=c><a xlink:href=d>x</a></svg>"),
        )
    }
}
