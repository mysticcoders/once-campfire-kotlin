package campfire.richtext

import campfire.ruby.strip

/**
 * `ContentFilters::TextMessagePresentationFilters` (reference/app/helpers/content_filters/,
 * `once-campfire-rust/crates/richtext/src/filters.rs`): RemoveSoloUnfurledLinkText, SanitizeTags,
 * SanitizeAttributes, applied in that order.
 */
object Filters {
    /** Applies the three filters in order. */
    fun apply(content: Content, ctx: RenderContext): Content =
        sanitizeAttributes(sanitizeTags(removeSoloUnfurledLinkText(content, ctx)))

    /** `RemoveSoloUnfurledLinkText`: a message that is nothing but a link to what it unfurls shows just the unfurl. */
    fun removeSoloUnfurledLinkText(content: Content, ctx: RenderContext): Content {
        val dom = content.dom
        val root = content.root
        val unfurledLinks = dom.descendants(root).filter {
            dom.localName(it) == Content.ATTACHMENT_TAG && dom.attr(it, "content-type") == Attachables.OPENGRAPH_EMBED_CONTENT_TYPE
        }
        val soloUnfurledUrl = if (unfurledLinks.size == 1) {
            Attachables.opengraphEmbedFromNode(dom, unfurledLinks[0], ctx)?.href
        } else {
            null
        }
        val plainText = content.toPlainText(ctx)
        val applicable = normalizeTweetUrl(soloUnfurledUrl) == normalizeTweetUrl(plainText)
        if (!applicable) return content
        val isTrixBody = dom.descendants(root).any { dom.localName(it) == "div" }
        if (isTrixBody) {
            val unfurl = dom.toHtml(unfurledLinks[0])
            for (div in dom.descendants(root).filter { dom.localName(it) == "div" }) dom.setInnerHtml(div, unfurl)
        } else {
            for (p in dom.descendants(root).filter { dom.localName(it) == "p" }) {
                val hasAttachment = dom.descendants(p).any { dom.localName(it) == Content.ATTACHMENT_TAG }
                if (!hasAttachment) dom.detach(p)
            }
        }
        return content
    }

    private val TWITTER_DOMAINS = listOf("x.com", "twitter.com")

    /** `normalize_tweet_url`: x.com becomes twitter.com and the query goes, for Twitter URLs only. */
    internal fun normalizeTweetUrl(url: String?): String? {
        if (url == null) return null
        val isTwitterUrl = !RubyText.isBlank(url) && TWITTER_DOMAINS.any { strip(url).contains(it) }
        if (!isTwitterUrl) return url
        return when (val parsed = RubyUri.parse(url)) {
            RubyUri.Parsed.InvalidUri -> url
            RubyUri.Parsed.InvalidComponent -> throw RichTextError.Raised("URI::InvalidComponentError")
            is RubyUri.Parsed.Ok -> {
                val uri = parsed.uri
                if (uri.host?.lowercase() == "x.com") uri.host = "twitter.com"
                uri.query = null
                uri.toS()
            }
        }
    }

    private val SANITIZE_TAGS = SafeList.SANITIZE_TAGS_ALLOWED_TAGS.toHashSet()

    /** `SanitizeTags`: removes every element outside the allowlist, together with its contents. */
    fun sanitizeTags(content: Content): Content {
        val dom = content.dom
        val disallowed = dom.descendants(content.root).filter { n -> dom.localName(n)?.let { it !in SANITIZE_TAGS } ?: false }
        for (node in disallowed) dom.detach(node)
        return content
    }

    /** `SanitizeAttributes`: scrubs attributes with Rails' safe-list sanitizer over SanitizeTags' own tags. */
    fun sanitizeAttributes(content: Content): Content = Content.wrap(Sanitizer.sanitize(content.toHtml(), SafeList.contentFilter))
}
