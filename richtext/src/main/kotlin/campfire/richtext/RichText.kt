package campfire.richtext

import campfire.rails.RailsJson
import campfire.ruby.strip

/** How `messages/_message.html.erb` shows a text message body (`once-campfire-rust/crates/richtext/src/lib.rs`, `Presentation`). */
sealed interface Presentation {
    /** The rendered body. */
    data class Html(val html: String) : Presentation

    /** `message_tag` rescued an exception: render `messages/_unrenderable.html.erb` instead. */
    data object Unrenderable : Presentation
}

/**
 * Campfire's Action Text content pipeline: what a stored message body looks like on screen, in
 * the editor, and as plain text (`once-campfire-rust/crates/richtext/src/lib.rs`).
 *
 * The Rails app is the oracle (reference/app/helpers/messages_helper.rb,
 * reference/app/helpers/content_filters/, reference/app/helpers/rich_text_helper.rb and
 * reference/lib/rails_ext/). Every step reproduces the Ruby it mirrors, including where Nokogiri
 * serializes markup and parses it back, because the output is shaped by those round trips.
 * Failures Ruby would raise are thrown as [RichTextError].
 */
object RichText {
    /**
     * The text branch of `MessagesHelper#message_presentation`:
     * `auto_link h(TextMessagePresentationFilters.apply(message.body.body)), ...`.
     */
    fun messagePresentation(body: String, ctx: RenderContext): String {
        val content = Content.load(body, ctx)
        val filtered = Filters.apply(content, ctx)
        val rendered = filtered.toRenderedHtmlWithLayout(ctx)
        return Autolink.autoLink(rendered, SafeList.autoLink)
    }

    /**
     * [messagePresentation] with `message_presentation`'s rescue: an exception renders as an
     * empty string, unless logging it raises again, in which case the whole message is
     * unrenderable. This is what the app calls to render a message body on a fragment-cache miss.
     */
    fun presentMessage(body: String, ctx: RenderContext): Presentation = try {
        Presentation.Html(messagePresentation(body, ctx))
    } catch (_: RichTextError.Unrenderable) {
        Presentation.Unrenderable
    } catch (_: RichTextError) {
        Presentation.Html("")
    }

    /**
     * `message.body.to_plain_text` (Action Text's `RichText#to_plain_text`), which feeds
     * `Message#plain_text_body`: the search index, push notification bodies, the webhook's plain
     * body, emoji detection and `/play` commands.
     */
    fun toPlainText(body: String, ctx: RenderContext): String = Content.load(body, ctx).toPlainText(ctx)

    /**
     * The value Lexxy's editor receives when editing a message: `RichTextHelper#editable_body`,
     * then Lexxy's `render_custom_attachments_in`. Null when the body is blank (no `value`
     * attribute). The caller HTML-escapes it into the `<lexxy-editor value="...">` attribute.
     */
    fun editableValue(body: String, ctx: RenderContext): String? {
        val dom = Dom()
        val root = dom.parseFragment(strip(body))
        for (node in Content.attachmentNodes(dom, root)) {
            val attachment = Attachables.attachmentFromNode(dom, node, ctx)
            if (attachment.attachable is Attachable.Missing) {
                dom.detach(node)
                continue
            }
            val contentType = attachment.attachable.attachableContentType()
            val content = Content.renderAttachmentHtml(attachment, ctx)
            dom.setAttr(node, "content-type", contentType)
            dom.setAttr(node, "content", content)
        }
        val editable = dom.toHtml(root)
        if (RubyText.isBlank(editable)) return null

        val lexxy = Dom()
        val lexxyRoot = lexxy.parseFragment(strip(editable))
        for (node in Content.attachmentNodes(lexxy, lexxyRoot)) {
            val url = lexxy.attr(node, "url")
            if (url == null || RubyText.isBlank(url)) {
                val attachment = Attachables.attachmentFromNode(lexxy, node, ctx)
                val content = Content.renderAttachmentHtml(attachment, ctx)
                lexxy.setAttr(node, "content", RailsJson.encode(content))
            }
        }
        return lexxy.toHtml(lexxyRoot)
    }

    /** `Message::Mentionee#mentioned_users`: users attached with a verified SGID, once each. */
    fun mentionedUsers(body: String, ctx: RenderContext): List<MentionUser> {
        val content = Content.load(body, ctx)
        val users = ArrayList<MentionUser>()
        for (node in Content.attachmentNodes(content.dom, content.root)) {
            val attachable = Attachables.actionTextAttachableFromNode(content.dom, node, ctx)
            if (attachable is Attachable.User && users.none { it.id == attachable.user.id }) users.add(attachable.user)
        }
        return users
    }

    /**
     * `Webhook#without_recipient_mentions`: the plain body with the bot's own "@Name" removed and
     * leading and trailing Unicode whitespace trimmed.
     */
    fun withoutRecipientMentions(plainText: String, recipientName: String): String =
        RubyText.trimWhitespace(plainText.replace("@$recipientName", ""))
}
