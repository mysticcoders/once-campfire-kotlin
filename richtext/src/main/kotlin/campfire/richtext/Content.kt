package campfire.richtext

import campfire.ruby.strip

/**
 * `ActionText::Content`: a fragment of rich text living in a [Dom] arena
 * (`once-campfire-rust/crates/richtext/src/content.rs`). Each Ruby step that serializes a node and
 * parses the markup back (`Fragment#replace` with a string, `inner_html=`, a filter returning
 * HTML) does the same here, in the same parse context, because those round trips are where the
 * output takes its shape.
 */
class Content(val dom: Dom, val root: Int) {
    /** The content serialized as Nokogiri does. */
    fun toHtml(): String = dom.toHtml(root)

    /** `ActionText::Content#to_plain_text`. */
    fun toPlainText(ctx: RenderContext): String {
        val dom = this.dom.copy()
        for (node in attachmentNodes(dom, root)) {
            sanitizeContentAttribute(dom, node)
            val attachment = Attachables.attachmentFromNode(dom, node, ctx)
            when (val text = Attachables.attachmentPlainText(attachment)) {
                is PlainTextRepresentation.Html -> dom.replaceWithHtml(node, text.html)
                is PlainTextRepresentation.Content -> {
                    val fragment = loadInto(dom, text.content, ctx)
                    dom.replaceWithNodes(node, dom.children(fragment).toArray())
                }
            }
        }
        return PlainText.nodeToPlainText(dom, root)
    }

    /**
     * `render_action_text_content(content)`: attachments and galleries rendered, then sanitized
     * with Action Text's allowlist.
     */
    fun render(ctx: RenderContext): String = renderNested(ctx, 0)

    /** [render], for content [depth] content attachments down. */
    internal fun renderNested(ctx: RenderContext, depth: Int): String {
        val dom = this.dom.copy()
        renderAttachments(dom, root, ctx, depth)
        renderAttachmentGalleries(dom, root, ctx, depth)
        return Sanitizer.sanitize(dom.toHtml(root), SafeList.actionText)
    }

    /**
     * `Content#to_s`: the content partial inside `layouts/action_text/contents/_content.html.erb`,
     * which Campfire overrides with a `lexxy-content` wrapper.
     */
    fun toRenderedHtmlWithLayout(ctx: RenderContext): String = "<div class=\"lexxy-content\">\n  ${render(ctx)}\n</div>\n"

    companion object {
        /** The element name of an Action Text attachment. */
        const val ATTACHMENT_TAG = "action-text-attachment"

        /**
         * How deep content attachments render inside one another. Each level parses and sanitizes
         * everything nested below it again, so Rails' unbounded nesting makes rendering quadratic
         * in the body's size; deeper content attachments render empty. Nothing Campfire's
         * composer makes nests them at all.
         */
        const val MAX_CONTENT_ATTACHMENT_DEPTH = 8

        private const val GALLERY_ATTACHMENT_PRESENTATION = "gallery"

        /** `ActionText::TrixAttachment::ATTRIBUTES`, in order, with their dashed attachment names. */
        private val TRIX_ATTRIBUTES = listOf(
            "sgid" to "sgid", "contentType" to "content-type", "url" to "url", "href" to "href", "filename" to "filename",
            "filesize" to "filesize", "width" to "width", "height" to "height", "previewable" to "previewable",
            "content" to "content", "caption" to "caption", "presentation" to "presentation",
        )

        /** `ActionText::Content.new(html)`, which is also how a stored body loads: canonicalized. */
        fun load(html: String, ctx: RenderContext): Content {
            val dom = Dom()
            val root = loadInto(dom, html, ctx)
            return Content(dom, root)
        }

        /** `ActionText::Content.new(html, canonicalize: false)`, i.e. `Fragment.from_html`. */
        fun wrap(html: String): Content {
            val dom = Dom()
            return Content(dom, dom.parseFragment(strip(html)))
        }

        /** Loads (and canonicalizes) [html] as a new fragment in [dom]. */
        fun loadInto(dom: Dom, html: String, ctx: RenderContext): Int {
            val root = dom.parseFragment(strip(html))
            convertTrixAttachments(dom, root, ctx)
            for (node in attachmentNodes(dom, root)) dom.setInnerHtml(node, "")
            for (gallery in attachmentGalleryNodes(dom, root)) {
                dom.replaceWithHtml(gallery, "<div>${dom.innerHtml(gallery)}</div>")
            }
            return root
        }

        /** The `<action-text-attachment>` elements under [root], in document order. */
        fun attachmentNodes(dom: Dom, root: Int): IntArray = dom.descendants(root).filter { dom.localName(it) == ATTACHMENT_TAG }.toIntArray()

        /**
         * `fragment_by_converting_trix_attachments`: `figure[data-trix-attachment]` (or any
         * element carrying the attribute) becomes an `<action-text-attachment>`, or disappears if
         * it has none of the attachment attributes.
         */
        private fun convertTrixAttachments(dom: Dom, root: Int, ctx: RenderContext) {
            val nodes = dom.descendants(root).filter { dom.hasAttr(it, "data-trix-attachment") }
            for (node in nodes) {
                val attributes = LinkedHashMap<String, Any?>()
                for (name in listOf("data-trix-attachment", "data-trix-attributes")) {
                    val json = dom.attr(node, name)
                    val parsed = if (json != null) RubyText.jsonParse(json).takeIf { it !== RubyText.NOT_JSON } else null
                    when (parsed) {
                        null, false -> Unit
                        is Map<*, *> -> for ((key, value) in parsed) {
                            if (TRIX_ATTRIBUTES.any { it.first == key }) attributes[key as String] = value
                        }
                        else -> throw RichTextError.Raised("NoMethodError: merge")
                    }
                }
                val elementAttrs = ArrayList<Pair<String, String>>()
                for (name in SafeList.ATTACHMENT_ATTRIBUTES) {
                    val trixName = TRIX_ATTRIBUTES.firstOrNull { it.second == name }?.first ?: continue
                    if (attributes.containsKey(trixName)) elementAttrs.add(name to RubyText.jsonValueToS(attributes[trixName]))
                }
                val replacement = if (elementAttrs.isEmpty()) {
                    ""
                } else {
                    val element = dom.createElement(ATTACHMENT_TAG, elementAttrs)
                    Attachables.attachmentFromNode(dom, element, ctx)
                    dom.toHtml(element)
                }
                dom.replaceWithHtml(node, replacement)
            }
        }

        /**
         * `render_attachments`' first step: an attachment's `content` attribute is sanitized with
         * Action Text's allowlist, and dropped if that leaves nothing.
         */
        private fun sanitizeContentAttribute(dom: Dom, node: Int) {
            val content = dom.removeAttr(node, "content") ?: return
            val sanitized = Sanitizer.sanitize(content, SafeList.actionText)
            if (!RubyText.isBlank(sanitized)) dom.setAttr(node, "content", sanitized)
        }

        /**
         * `Attachment#with_full_attributes`: a new node carrying the node's attachment attributes,
         * the attachable's own (sgid and content type, for a user), and the node's sgid if it had one.
         */
        private fun nodeWithFullAttributes(dom: Dom, node: Int, attachable: Attachable): Int {
            val attrs = ArrayList<Pair<String, String>>()
            for (name in SafeList.ATTACHMENT_ATTRIBUTES) {
                val value = when {
                    name == "sgid" && attachable is Attachable.User -> dom.attr(node, "sgid") ?: attachable.user.attachableSgid
                    name == "content-type" && attachable is Attachable.User -> Attachables.MENTION_CONTENT_TYPE
                    else -> dom.attr(node, name)
                }
                if (value != null) attrs.add(name to value)
            }
            if (attrs.isEmpty()) throw RichTextError.Raised("NoMethodError: node for nil")
            return dom.createElement(ATTACHMENT_TAG, attrs)
        }

        /**
         * `render_action_text_attachment`, with nested content attachments rendered through
         * `ContentAttachment#to_html` (the content partial, without the layout).
         */
        fun renderAttachmentHtml(attachment: Attachment, ctx: RenderContext): String = renderAttachmentHtmlAt(attachment, ctx, 0)

        private fun renderAttachmentHtmlAt(attachment: Attachment, ctx: RenderContext, depth: Int): String =
            Attachables.renderAttachment(attachment) { content ->
                if (depth >= MAX_CONTENT_ATTACHMENT_DEPTH) "" else load(content, ctx).renderNested(ctx, depth + 1) + "\n"
            }

        private fun renderAttachments(dom: Dom, root: Int, ctx: RenderContext, depth: Int) {
            for (node in attachmentNodes(dom, root)) {
                sanitizeContentAttribute(dom, node)
                val found = Attachables.attachmentFromNode(dom, node, ctx)
                val full = nodeWithFullAttributes(dom, node, found.attachable)
                val attachment = Attachment(found.attachable, RubyText.presence(dom.attr(full, "caption")))
                dom.setInnerHtml(full, renderAttachmentHtmlAt(attachment, ctx, depth))
                dom.replaceWithHtml(node, dom.toHtml(full))
            }
        }

        private fun isGalleryAttachment(dom: Dom, node: Int): Boolean =
            dom.localName(node) == ATTACHMENT_TAG && dom.attr(node, "presentation") == GALLERY_ATTACHMENT_PRESENTATION

        /**
         * `AttachmentGallery.find_attachment_gallery_nodes`: `div:has(A + A)` for gallery
         * attachments A, whose children are all gallery attachments or newline/space text.
         */
        fun attachmentGalleryNodes(dom: Dom, root: Int): List<Int> = dom.descendants(root).filter { div ->
            dom.localName(div) == "div" &&
                dom.descendants(div).any { n ->
                    isGalleryAttachment(dom, n) && run {
                        val siblings = dom.elementChildren(dom.parent(n))
                        val index = siblings.indexOf(n)
                        index > 0 && isGalleryAttachment(dom, siblings[index - 1])
                    }
                } &&
                dom.children(div).toArray().all { child ->
                    val text = dom.text(child)
                    if (text != null) text.all { it == '\n' || it == ' ' } else isGalleryAttachment(dom, child)
                }
        }

        private fun renderAttachmentGalleries(dom: Dom, root: Int, ctx: RenderContext, depth: Int) {
            for (gallery in attachmentGalleryNodes(dom, root)) {
                val members = dom.descendants(gallery).filter { isGalleryAttachment(dom, it) }
                val rendered = StringBuilder()
                for (member in members) {
                    val attachment = Attachables.attachmentFromNode(dom, member, ctx)
                    val full = nodeWithFullAttributes(dom, member, attachment.attachable)
                    dom.setInnerHtml(full, renderAttachmentHtmlAt(attachment, ctx, depth))
                    rendered.append(dom.toHtml(full))
                }
                dom.replaceWithHtml(gallery, "<div class=\"attachment-gallery attachment-gallery--${members.size}\">\n  $rendered\n</div>")
            }
        }
    }
}
