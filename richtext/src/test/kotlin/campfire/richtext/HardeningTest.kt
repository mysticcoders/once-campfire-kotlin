package campfire.richtext

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression tests for places where the port deliberately diverges from the Rails pipeline to
 * close a hole or bound the work a message body can cause (`once-campfire-rust/crates/richtext/tests/hardening.rs`).
 * Time bounds are the Rust debug build's: a cold JVM, with its JIT still warming up, is the
 * closer comparison.
 */
class HardeningTest {
    private object NoRecords : AttachableResolver {
        override fun locateSigned(sgid: String): SignedLookup = SignedLookup.Invalid

        override fun findGid(gid: String): GidLookup = GidLookup.NotFound
    }

    private fun ctx() = RenderContext(NoRecords, "once.campfire.test")

    private fun presentation(body: String): String = RichText.messagePresentation(body, ctx())

    /** Every element and attribute name in [html] as a browser would parse it. */
    private fun parsedMarkup(html: String): List<String> {
        val dom = Dom()
        val root = dom.parseFragment(html)
        val names = ArrayList<String>()
        for (node in dom.descendants(root)) {
            val name = dom.localName(node) ?: continue
            if (name == "div" && dom.attr(node, "class") == "lexxy-content") continue
            names.add(name)
            names.addAll(dom.attrs(node).map { (attr, _) -> "$name[$attr]" })
        }
        return names
    }

    private fun assertQuick(startedNanos: Long, what: String, boundMillis: Long = 5_000) {
        val elapsed = (System.nanoTime() - startedNanos) / 1_000_000
        assertTrue(elapsed < boundMillis, "$what took $elapsed ms")
    }

    @Test
    fun aUrlAfterAGreaterThanSignInAnAttributeCannotBreakOutOfIt() {
        val html = presentation("<p title=\"x> http://evil.test/ <img src=x onerror=alert(1)>\">hi</p>")
        val markup = parsedMarkup(html)
        assertFalse(markup.any { it == "img" || it.contains("onerror") }, html)
        assertEquals(listOf("p", "p[title]"), markup, html)
    }

    @Test
    fun anEmailAddressAfterAGreaterThanSignInAnAttributeCannotBreakOutOfIt() {
        val html = presentation("<p><abbr title=\"x> me@evil.test <img src=x onerror=alert(1)>\">hi</abbr></p>")
        assertEquals(listOf("p", "abbr", "abbr[title]"), parsedMarkup(html), html)
    }

    @Test
    fun nameAttributesCantClobberThePagesGlobals() {
        val html = presentation("<p><a name=\"body\" href=\"/x\">x</a><span name=\"cookie\">y</span></p>")
        assertEquals(listOf("p", "a", "a[href]", "span"), parsedMarkup(html), html)
    }

    @Test
    fun urlsInTextAreStillLinked() {
        val html = presentation("<p>see http://example.com/a?b=1&amp;c=2 and me@example.com</p>")
        assertTrue(
            html.contains(
                "<p>see <a target=\"_blank\" href=\"http://example.com/a?b=1&amp;c=2\">http://example.com/a?b=1&amp;c=2</a> and " +
                    "<a target=\"_blank\" href=\"mailto:me@example.com\">me@example.com</a></p>",
            ),
            html,
        )
    }

    @Test
    fun manyBareDomainsAutolinkInLinearTime() {
        val body = "<p>www.a.com</p>".repeat(64 * 1024 / 16)
        val started = System.nanoTime()
        val html = presentation(body)
        assertQuick(started, "autolinking 64 KB of bare domains")
        assertEquals(64 * 1024 / 16, html.split("<a target=\"_blank\" href=\"http://www.a.com\">").size - 1)
    }

    /** Content attachments nested [levels] deep, each saying which level it is. */
    private fun nestedContentAttachments(levels: Int, padding: String): String {
        var body = ""
        for (level in levels downTo 1) {
            val content = "<p>level $level$padding</p>$body".replace("&", "&amp;").replace("\"", "&quot;")
            body = "<action-text-attachment content-type=\"text/html\" content=\"$content\"></action-text-attachment>"
        }
        return body
    }

    @Test
    fun contentAttachmentsRenderEightLevelsDeep() {
        val html = presentation(nestedContentAttachments(12, ""))
        for (level in 1..12) assertEquals(level <= 8, html.contains("level $level<"), "level $level in $html")
    }

    @Test
    fun deeplyNestedContentAttachmentsRenderQuickly() {
        val body = nestedContentAttachments(200, "x".repeat(1000))
        assertTrue(body.length > 200_000)
        val started = System.nanoTime()
        presentation(body)
        assertQuick(started, "rendering 200 nested content attachments")
    }

    /** Both the page and the search index (which is written inside the database transaction) have to refuse [body]. */
    private fun assertRefusedQuickly(body: String, what: String) {
        val started = System.nanoTime()
        assertFailsWith<RichTextError>("$what rendered") { RichText.messagePresentation(body, ctx()) }
        assertFailsWith<RichTextError>("$what was indexed") { RichText.toPlainText(body, ctx()) }
        assertQuick(started, what)
    }

    @Test
    fun deeplyNestedElementsAreRefusedQuickly() {
        assertRefusedQuickly("<div>".repeat(80_000), "400 KB of nested <div>s")
        assertRefusedQuickly("<a><b>".repeat(80_000), "480 KB of <a><b>")
        assertRefusedQuickly("<a><div><div>".repeat(30_000), "390 KB of <a><div><div>")
    }

    @Test
    fun theRestOfABodyIsNotReadOnceItIsTooDeep() {
        val tooDeep = "<div>".repeat(401)
        val rest = "x".repeat(16 * 1024 * 1024)
        for ((what, body) in listOf(
            "tags" to tooDeep + "<a><b>".repeat(rest.length / 6),
            "a comment" to "$tooDeep<!--$rest",
            "a tag name" to "$tooDeep<$rest",
        )) {
            val started = System.nanoTime()
            assertFailsWith<RichTextError> { RichText.messagePresentation(body, ctx()) }
            assertFailsWith<RichTextError> { RichText.toPlainText(body, ctx()) }
            assertQuick(started, "refusing 16 MB of $what", boundMillis = 1_000)
        }
    }

    @Test
    fun aTagWithTooManyAttributesIsRefusedQuickly() {
        val attributes = (1..64_000).joinToString(" ") { "a$it=1" }
        assertRefusedQuickly("<b $attributes>x</b>", "a tag with 64,000 attributes")
    }

    @Test
    fun htmlTagsInTheBodyParseInLinearTime() {
        val body = (0 until 200).joinToString("") { tag ->
            "<html " + (1..Dom.MAX_ATTRIBUTES).joinToString(" ") { "a${tag * Dom.MAX_ATTRIBUTES + it}" } + ">"
        }
        assertTrue(body.length > 500_000)
        val started = System.nanoTime()
        assertEquals("", RichText.toPlainText(body, ctx()))
        assertEquals(presentation(""), presentation(body))
        assertQuick(started, "550 KB of <html> tags, each with 400 new attributes")
    }

    @Test
    fun elementsMisplacedInATableParseInLinearTime() {
        val body = "<table>" + "<br>".repeat(200_000)
        val started = System.nanoTime()
        RichText.toPlainText(body, ctx())
        assertQuick(started, "800 KB of <br>s in a table")
    }

    /** Every SGID names a user who has since been deleted. */
    private object DeletedUsers : AttachableResolver {
        override fun locateSigned(sgid: String): SignedLookup = SignedLookup.MissingRecord("User")

        override fun findGid(gid: String): GidLookup = GidLookup.NotFound
    }

    private val deletedMention =
        "<p>Hi <action-text-attachment sgid=\"eyJfcmFpbHMiOnsiZGF0YSI6ImdpZDovL2NhbXBmaXJlL1VzZXIvNj9leHBpcmVzX2luIiwicHVyIjoiYXR0YWNoYWJsZSJ9fQ==" +
            "--fc4f83a239475557295b8e2f5ff55482bebc9bfe\" content-type=\"application/vnd.campfire.mention\"></action-text-attachment>, welcome</p>"

    @Test
    fun aMentionOfADeletedUserLeavesTheRestOfTheMessage() {
        val html = RichText.messagePresentation(deletedMention, RenderContext(DeletedUsers, null))
        assertTrue(html.contains("Hi") && html.contains('☒') && html.contains("welcome"), html)
    }

    @Test
    fun aMentionOfADeletedUserLeavesTheEditor() {
        val value = RichText.editableValue(deletedMention, RenderContext(DeletedUsers, null))!!
        assertTrue(!value.contains("action-text-attachment") && value.contains("welcome"), value)
    }
}
