package campfire.richtext

import campfire.ruby.Erb
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ports of the reference app's rich text tests (`once-campfire-rust/crates/richtext/tests/reference_tests.rs`):
 * test/helpers/content_filters_test.rb, messages_helper_test.rb, rich_text_helper_test.rb,
 * the test/lib/rails_ext tests, test/models/action_text_attachment_test.rb and the plain-text
 * parts of the message tests.
 */
class ReferenceTest {
    private val davidSgid =
        "eyJfcmFpbHMiOnsiZGF0YSI6ImdpZDovL2NhbXBmaXJlL1VzZXIvMT9leHBpcmVzX2luIiwicHVyIjoiYXR0YWNoYWJsZSJ9fQ==--f7d8e8773314d3310320f3cdd08e5597bb51ca1a"

    /** A Room's attachable SGID, as `rooms(:pets).to_sgid(expires_in: nil, for: "attachable")` mints it. */
    private val roomSgidMessage = "eyJfcmFpbHMiOnsiZGF0YSI6ImdpZDovL2NhbXBmaXJlL1Jvb20vMT9leHBpcmVzX2luIiwicHVyIjoiYXR0YWNoYWJsZSJ9fQ=="

    private fun david() = MentionUser(1, "David", "David – Founder", davidSgid, "/users/1", "/users/1/avatar?v=1")

    private inner class Fixtures : AttachableResolver {
        override fun locateSigned(sgid: String): SignedLookup = if (sgid == davidSgid) SignedLookup.User(david()) else SignedLookup.Invalid

        override fun findGid(gid: String): GidLookup = when (gid.substringBefore('?')) {
            "gid://campfire/User/1" -> GidLookup.User(david())
            "gid://campfire/Room/1" -> GidLookup.OtherModel
            else -> GidLookup.NotFound
        }
    }

    private fun ctx() = RenderContext(Fixtures(), "once.campfire.test")

    /** test_helper.rb's `mention_attachment_for(:david)`. */
    private fun mentionAttachmentForDavid(): String =
        "<action-text-attachment sgid=\"$davidSgid\" content-type=\"application/vnd.campfire.mention\" content=\"" +
            Attachables.renderMention(david()).replace("\"", "&quot;") + "\"></action-text-attachment>"

    private fun filtered(body: String): String {
        val ctx = ctx()
        return Filters.apply(Content.load(body, ctx), ctx).toHtml()
    }

    private fun loaded(body: String): String = Content.load(body, ctx()).toHtml()

    private fun presentation(body: String): String = RichText.messagePresentation(body, ctx())

    private val basecampUnfurl =
        "<action-text-attachment content-type=\"application/vnd.actiontext.opengraph-embed\" url=\"https://basecamp.com/assets/general/opengraph.png\" " +
            "href=\"https://basecamp.com/\" filename=\"Project management software, online collaboration\" caption=\"Trusted by millions.\"></action-text-attachment>"
    private val twitterUnfurl =
        "<action-text-attachment content-type=\"application/vnd.actiontext.opengraph-embed\" url=\"https://pbs.twimg.com/ext_tw_video_thumb/1752476502791503873/pu/img/WEAqUgarUxWjPNHD.jpg\" " +
            "href=\"https://twitter.com/dhh/status/1752476663303323939\" filename=\"DHH (@dhh)\" caption=\"We're playing.\"></action-text-attachment>"

    @Test
    fun entireMessageContainsAnUnfurledUrl() {
        val body = "<div>https://basecamp.com/$basecampUnfurl</div>"
        val result = filtered(body)
        assertNotEquals(loaded(body), result)
        assertTrue(result.contains("<div><action-text-attachment"))
    }

    @Test
    fun entireMessageContainsAnUnfurledUrlInALexxyBody() {
        val result = filtered("<p><a href=\"https://basecamp.com/\">https://basecamp.com/</a></p>$basecampUnfurl")
        assertFalse(result.contains(">https://basecamp.com/</a>"))
        assertTrue(result.contains("<action-text-attachment"))
    }

    @Test
    fun messageIncludesAdditionalTextBesidesAnUnfurledUrl() {
        val body = "<div>Hello https://basecamp.com/$basecampUnfurl</div>"
        val result = filtered(body)
        assertEquals(loaded(body), result)
        assertTrue(result.contains("<div>Hello https://basecamp.com/<action-text-attachment"))
    }

    @Test
    fun unfurledTweetWithAnAvatarImageGetsTheTwitterAvatarTreatment() {
        val body = "<div>https://twitter.com/37signals/status/1750290547908952568<action-text-attachment content-type=\"application/vnd.actiontext.opengraph-embed\" " +
            "url=\"https://pbs.twimg.com/profile_images/1671940407633010689/9P5gi6LF_200x200.jpg\" href=\"https://twitter.com/37signals/status/1750290547908952568\" " +
            "filename=\"37signals (@37signals)\" caption=\"We're back up on all apps, everyone.\"></action-text-attachment></div>"
        assertTrue(presentation(body).contains("og-embed--twitter-avatar"))
    }

    @Test
    fun unfurledTweetWithAnAvatarImageInALexxyBodyGetsTheTwitterAvatarTreatment() {
        val content = "<actiontext-opengraph-embed><div class=\"og-embed gap\"><div class=\"og-embed__content\"><div class=\"og-embed__title\">" +
            "<a href=\"https://twitter.com/x/status/1\">Tweet</a></div><div class=\"og-embed__description\">desc</div></div><div class=\"og-embed__image\">" +
            "<img src=\"https://pbs.twimg.com/profile_images/x.jpg\" class=\"image center\" alt=\"\" /></div></div></actiontext-opengraph-embed>"
        val body = "<p><a href=\"https://twitter.com/x/status/1\">https://twitter.com/x/status/1</a></p><action-text-attachment " +
            "content-type=\"application/vnd.actiontext.opengraph-embed\" content=\"${Erb.htmlEscape(content)}\"></action-text-attachment>"
        assertTrue(presentation(body).contains("og-embed--twitter-avatar"))
    }

    @Test
    fun unfurledTweetWithAContentImageIsNotStyledAsAnAvatar() {
        val body = "<div>https://twitter.com/dhh/status/1748445489648050505<action-text-attachment content-type=\"application/vnd.actiontext.opengraph-embed\" " +
            "url=\"https://pbs.twimg.com/media/GEO5l04bsAA9f6H.jpg\" href=\"https://twitter.com/dhh/status/1748445489648050505\" filename=\"DHH (@dhh)\" " +
            "caption=\"MIT\"></action-text-attachment></div>"
        assertFalse(presentation(body).contains("og-embed--twitter-avatar"))
    }

    @Test
    fun entireMessageContainsAnUnfurledUrlFromXComButUnfurlsToTwitterCom() {
        for (text in listOf("https://x.com/dhh/status/1752476663303323939", "https://x.com/dhh/status/1752476663303323939?s=20")) {
            val body = "<div>$text$twitterUnfurl</div>"
            val result = filtered(body)
            assertNotEquals(loaded(body), result)
            assertTrue(result.contains("<div><action-text-attachment"))
        }
    }

    @Test
    fun messageKeepsStrikethroughUnderlineAndCodeBlockFormatting() {
        val html = presentation("<p>Hello <s>struck</s> <u>under</u> <mark>marked</mark></p><pre data-language=\"ruby\">def x<br>end</pre>")
        assertTrue(html.contains("<s>struck</s>"))
        assertTrue(html.contains("<u>under</u>"))
        assertTrue(html.contains("<mark>marked</mark>"))
        assertTrue(html.contains("<pre data-language=\"ruby\">"))
    }

    @Test
    fun messageContainsAForbiddenTag() {
        assertEquals("Hello World", filtered("Hello <img src=\"https://ssecurityrise.com/tests/billionlaughs-cache.svg\">World"))
    }

    @Test
    fun messageWithALinkUsingAnUnsafeUriScheme() {
        val result = filtered("<div><a href=\"javascript:alert(1)\">x</a></div>")
        assertFalse(result.contains("javascript:"))
        assertTrue(result.contains("<a>x</a>"))
    }

    @Test
    fun messageWithAnEventHandlerAttributeOnAnAllowedTag() {
        val result = filtered("<div><a href=\"/x\" onmouseover=\"alert(1)\">x</a> <span onclick=\"alert(2)\">y</span></div>")
        assertFalse(result.contains("onmouseover"))
        assertFalse(result.contains("onclick"))
        assertTrue(result.contains("<a href=\"/x\">x</a>"))
        assertTrue(result.contains("<span>y</span>"))
    }

    @Test
    fun messageWithADataUriLink() {
        val result = filtered("<div><a href=\"data:text/html,pwned\">x</a></div>")
        assertFalse(result.contains("data:"))
        assertTrue(result.contains("<a>x</a>"))
    }

    @Test
    fun messageWithASafeLinkAndFormattingIsPreserved() {
        val result = filtered(
            "<div><a href=\"https://example.com\">example</a> <strong>bold</strong> <code>code</code><ul><li>one</li><li>two</li></ul></div>",
        )
        assertTrue(result.contains("<a href=\"https://example.com\">example</a>"))
        assertTrue(result.contains("<strong>bold</strong>"))
        assertTrue(result.contains("<code>code</code>"))
        assertTrue(result.contains("<ul><li>one</li><li>two</li></ul>"))
    }

    @Test
    fun sanitizeAttributesNeutralizesUnsafeInputAndPreservesBenignContent() {
        val body = "<div><a href=\"javascript:alert(1)\" onclick=\"x()\">link</a> <a href=\"data:text/html,pwned\">data</a> " +
            "<span class=\"cf-twitter-avatar\" onmouseover=\"y()\">avatar</span> <img src=\"https://evil.example/x.svg\"> Hey ${mentionAttachmentForDavid()}</div>"
        val result = Filters.sanitizeAttributes(Content.load(body, ctx())).toHtml()
        for (forbidden in listOf("javascript:", "data:text/html", "onclick", "onmouseover", "evil.example")) {
            assertFalse(result.contains(forbidden), "$forbidden in $result")
        }
        assertTrue(result.contains("<span class=\"cf-twitter-avatar\">avatar</span>"))
        assertTrue(result.contains(">link<"))
        assertTrue(result.contains("<action-text-attachment sgid=\"$davidSgid\""))
    }

    @Test
    fun messageWithFormattingSavedUnderTrixRendersUnchanged() {
        val body = "<div>Hello <strong>bold</strong> <em>it</em> <del>gone</del> <a href=\"https://example.com/\">link</a><br>second line</div>" +
            "<h1>Heading</h1><blockquote>quoted</blockquote><pre>line 1\nline 2</pre><ul><li>one</li></ul><ol><li>first</li></ol>"
        assertEquals(body, filtered(body))
        assertTrue(presentation(body).contains(body))
    }

    @Test
    fun messageWithATableKeepsTheTable() {
        val body = "<figure class=\"lexxy-content__table-wrapper\"><table><tbody><tr><th><p>Name</p></th></tr><tr><td><p>Jason</p></td></tr></tbody></table></figure>"
        assertEquals(body, filtered(body))
        val html = presentation(body)
        val table = html.substring(html.indexOf("<table>"))
        assertTrue(table.contains("<th><p>Name</p></th>") && table.contains("<td><p>Jason</p></td>"))
    }

    @Test
    fun messageWithAMentionAttachment() {
        val result = filtered("<div>Hey ${mentionAttachmentForDavid()}</div>")
        assertTrue(result.contains("<action-text-attachment sgid=\"$davidSgid\" content-type=\"application/vnd.campfire.mention\" content=\""))
    }

    @Test
    fun messagePresentationNeutralizesUnsafeUriSchemesInLinks() {
        val html = presentation("<div><a href=\"javascript:alert(1)\">x</a></div>")
        assertFalse(html.contains("javascript:"))
        assertTrue(html.contains("<a>x</a>"))
    }

    @Test
    fun messagePresentationStripsEventHandlerAttributesFromAllowedTags() {
        val html = presentation("<div><a href=\"/x\" onmouseover=\"alert(1)\">x</a></div>")
        assertFalse(html.contains("onmouseover"))
        assertTrue(html.contains("<a href=\"/x\">x</a>"))
    }

    @Test
    fun messagePresentationPreservesSafeLinksAndFormatting() {
        val html = presentation("<div><a href=\"https://example.com\">example</a> <strong>bold</strong></div>")
        assertTrue(html.contains("<a href=\"https://example.com\">example</a>"))
        assertTrue(html.contains("<strong>bold</strong>"))
    }

    private fun editableAttachment(body: String): Pair<String, String> {
        val value = RichText.editableValue(body, ctx())!!
        val dom = Dom()
        val root = dom.parseFragment(value)
        val node = dom.descendants(root).first { dom.localName(it) == "action-text-attachment" }
        return dom.attr(node, "content-type")!! to dom.attr(node, "content")!!
    }

    @Test
    fun editableBodyRendersLegacyOpengraphEmbedsIntoTheContentAttribute() {
        val (_, content) = editableAttachment(
            "<div>https://example.com/ <action-text-attachment content-type=\"application/vnd.actiontext.opengraph-embed\" url=\"https://example.com/image.png\" " +
                "href=\"https://example.com/\" filename=\"Example title\" caption=\"Example description\"></action-text-attachment></div>",
        )
        assertTrue(content.contains("<a rel=\"noreferrer\" target=\"_blank\" href=\"https://example.com/\">Example title</a>"))
        assertTrue(content.contains("<div class=\"og-embed__description\">Example description</div>"))
        assertTrue(content.contains("<img src=\"https://example.com/image.png\""))
    }

    @Test
    fun editableBodyRebuildsAHandWrittenEmbedFromItsValidatedDetails() {
        val content = "<actiontext-opengraph-embed data-controller=\"pwn\" data-action=\"click->pwn#run\"> <div class=\"og-embed\"><div class=\"og-embed__title\">" +
            "<a href=\"/rooms/1\">Free cookies</a></div> <div class=\"og-embed__image\"><img src=\"/rooms/1/avatar\" data-action=\"load->pwn#run\"></div></div> " +
            "</actiontext-opengraph-embed>"
        val body = "<p><action-text-attachment content-type=\"application/vnd.actiontext.opengraph-embed\" url=\"https://example.com/image.png\" " +
            "content=\"${Erb.htmlEscape(content)}\"></action-text-attachment></p>"
        val (_, rebuilt) = editableAttachment(body)
        assertTrue(rebuilt.contains("Free cookies"))
        assertFalse(rebuilt.contains("rooms/1"))
        assertFalse(rebuilt.contains("data-"))
        assertTrue(!rebuilt.contains("<a ") && !rebuilt.contains("<img"))
    }

    @Test
    fun editableBodyRestoresTheContentTypeOfAMentionEditedUnderTrix() {
        val (contentType, content) = editableAttachment(
            "<div>Hey <action-text-attachment sgid=\"$davidSgid\" content-type=\"application/octet-stream\"></action-text-attachment></div>",
        )
        assertEquals("application/vnd.campfire.mention", contentType)
        assertTrue(content.contains("David"))
    }

    @Test
    fun editableBodyLeavesBodiesWithoutAttachmentsUnchanged() {
        assertEquals("<p>Plain text</p>", RichText.editableValue("<p>Plain text</p>", ctx()))
    }

    private fun attachmentPlainText(sgid: String?): String {
        val attribute = if (sgid != null) " sgid=\"$sgid\"" else ""
        return RichText.toPlainText("<action-text-attachment$attribute></action-text-attachment>", ctx())
    }

    @Test
    fun fromNodeWithAValidSgid() {
        assertEquals("@David", attachmentPlainText(davidSgid))
    }

    @Test
    fun fromNodeWithARails7Sgid() {
        val marshaled = byteArrayOf(0x04, 0x08, 'I'.code.toByte(), '"'.code.toByte(), 0x1a) + "gid://campfire/User/1".toByteArray() +
            byteArrayOf(0x06, ':'.code.toByte(), 0x06, 'E'.code.toByte(), 'T'.code.toByte())
        val message = Base64.getUrlEncoder().encodeToString(marshaled)
        val payload = "{\"_rails\":{\"message\":\"$message\",\"exp\":null,\"pur\":\"attachable\"}}"
        val sgid = Base64.getEncoder().encodeToString(payload.toByteArray()) + "--invalidsignature"
        assertEquals("@David", attachmentPlainText(sgid))
    }

    @Test
    fun lookupUserAttachableWithInvalidSignature() {
        val message = davidSgid.substringBefore("--")
        assertEquals("@David", attachmentPlainText("$message--invalid"))
    }

    @Test
    fun lookupInvalidSgidForAnAttachableRequiringAValidSgid() {
        for (sgid in listOf("$roomSgidMessage--invalid", "$roomSgidMessage--f7d8e8773314d3310320f3cdd08e5597bb51ca1ainvalid")) {
            assertEquals("", attachmentPlainText(sgid))
            val html = presentation("<p><action-text-attachment sgid=\"$sgid\"></action-text-attachment></p>")
            assertTrue(html.contains("☒"), html)
            assertFalse(html.contains("mention"))
        }
    }

    @Test
    fun lookupAttachableWithNilSgid() {
        assertTrue(presentation("<p><action-text-attachment content-type=\"application/pdf\"></action-text-attachment></p>").contains("☒"))
    }

    @Test
    fun keepsAbsoluteHttpAndHttpsLinksAndImages() {
        assertEquals("http://example.com/page", Attachables.webUrl("http://example.com/page", ""))
        assertEquals("https://example.com/image.png", Attachables.webUrl("https://example.com/image.png", ""))
    }

    @Test
    fun dropsALinkAndAnImageThatArentWebUrls() {
        for (value in listOf(
            "javascript:alert(1)", "data:text/html,pwned", "vbscript:msgbox(1)", "//example.com/image.png", "/rooms/1", "rooms/1", "",
            "http://exa mple.com/ ", "https:/rooms/1", "https:rooms/1", "http:/rooms/1", "https://", "http://:80/rooms/1",
        )) {
            assertNull(Attachables.webUrl(value, ""), value)
        }
    }

    @Test
    fun dropsALinkAndAnImageOnThisCampfiresOwnHostHoweverItIsSpelled() {
        for (value in listOf(
            "https://once.campfire.test/rooms/1",
            "http://once.campfire.test/rooms/1",
            "https://ONCE.Campfire.Test/rooms/1",
            "https://once.campfire.test./rooms/1",
            "https://%6fnce.campfire.test/rooms/1",
            "https://%77ww.example.com/x.png",
        )) {
            assertNull(Attachables.webUrl(value, "once.campfire.test"), value)
        }
        assertNotNull(Attachables.webUrl("https://example.com/page", "once.campfire.test"))
    }

    @Test
    fun dropsALinkAndAnImageOnABareAddressRatherThanADomainName() {
        for (value in listOf(
            "http://127.0.0.1/rooms/1", "http://2130706433/rooms/1", "http://0177.0.0.1/rooms/1", "http://0x7f.0.0.1/rooms/1",
            "http://1.2.3.0xff/rooms/1", "http://[::1]/rooms/1", "http://localhost/rooms/1", "https://203.0.113.10/image.png",
        )) {
            assertNull(Attachables.webUrl(value, ""), value)
        }
    }

    @Test
    fun keepsAnInternationalizedDomainWrittenInPunycode() {
        assertNotNull(Attachables.webUrl("https://xn--80aswg.xn--p1ai/page", ""))
    }

    @Test
    fun rendersTheTitleAndTheDescriptionAsText() {
        val html = presentation(
            "<action-text-attachment content-type=\"application/vnd.actiontext.opengraph-embed\" href=\"https://example.com/page\" " +
                "url=\"https://example.com/image.png\" filename=\"&lt;b&gt;Title&lt;/b&gt;\" caption=\"&lt;img src=x onerror=alert(1)&gt;\"></action-text-attachment>",
        )
        assertFalse(html.contains("<b>"))
        assertFalse(html.contains("<img src=x"))
        assertTrue(html.contains("&lt;b&gt;Title&lt;/b&gt;"))
    }

    @Test
    fun richTextBodyIsConvertedToPlainTextForIndexing() {
        assertEquals("My hovercraft is full of eels", RichText.toPlainText("<span>My hovercraft is full of eels</span>", ctx()))
        assertEquals("First post!", RichText.toPlainText("First post!", ctx()))
    }

    @Test
    fun mentioneesAreTheMentionedUsersOnceEach() {
        val body = "<div>Hey ${mentionAttachmentForDavid()} ${mentionAttachmentForDavid()}</div>"
        assertEquals(listOf(1L), RichText.mentionedUsers(body, ctx()).map { it.id })
    }

    @Test
    fun webhookPlainBodyDropsTheRecipientsMentions() {
        val plain = RichText.toPlainText("<p>${mentionAttachmentForDavid()} hello</p>", ctx())
        assertEquals("@David hello", plain)
        assertEquals("hello", RichText.withoutRecipientMentions(plain, "David"))
    }
}
