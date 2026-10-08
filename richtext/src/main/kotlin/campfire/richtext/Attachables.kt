package campfire.richtext

import campfire.rails.RubyBase64
import campfire.ruby.Erb
import campfire.ruby.strip
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * What the app knows about a user, for rendering `users/_mention.html.erb`
 * (`once-campfire-rust/crates/richtext/src/attachables.rs`, `MentionUser`). Plain text and
 * mentions only read [id] and [name]; the other fields are only rendered.
 *
 * @property title `User#title`: name and bio joined with " – ".
 * @property attachableSgid `user.attachable_sgid`: a freshly minted SGID for the "attachable" purpose.
 * @property userPath `user_path(user)`.
 * @property avatarPath `fresh_user_avatar_path(user)`.
 */
data class MentionUser(
    val id: Long,
    val name: String,
    val title: String,
    val attachableSgid: String,
    val userPath: String,
    val avatarPath: String,
)

/** A record `GlobalID.find` located (`attachables.rs`, `GidLookup`). */
sealed interface GidLookup {
    /** A user. */
    data class User(val user: MentionUser) : GidLookup

    /** Found, but not a `User`: the invalid-signature fallback ignores it. */
    data object OtherModel : GidLookup

    /** `GlobalID.find` returned nil or raised `ActiveRecord::RecordNotFound`. */
    data object NotFound : GidLookup

    /** `GlobalID.find` raised anything else (an unknown model constant, say). */
    data object Raises : GidLookup
}

/** What a signed GlobalID verified for the "attachable" purpose points at (`attachables.rs`, `SignedLookup`). */
sealed interface SignedLookup {
    /** The signature verified and the user exists. */
    data class User(val user: MentionUser) : SignedLookup

    /** The signature verified (`SignedGlobalID.parse` succeeds) but the record is gone. */
    data class MissingRecord(val modelName: String) : SignedLookup

    /** Bad signature, wrong purpose, expired, or not an SGID at all. */
    data object Invalid : SignedLookup
}

/**
 * The app's access to its records (`attachables.rs`, `AttachableResolver`): the two lookups
 * Action Text performs. `campfire.rails` verifies signatures; the database side lives in the app.
 */
interface AttachableResolver {
    /**
     * `GlobalID::Locator.locate_signed(sgid, for: "attachable")` (and, when that finds nothing,
     * whether `SignedGlobalID.parse(sgid, for: "attachable")` still verifies). Campfire's only
     * attachables are users (mentions).
     */
    fun locateSigned(sgid: String): SignedLookup

    /** `GlobalID.find(gid)` for a `gid://` URI string, with no signature involved. */
    fun findGid(gid: String): GidLookup
}

/**
 * Everything rendering needs from the request and the app (`attachables.rs`, `RenderContext`).
 *
 * @property requestHost `Current.request_host`.
 */
class RenderContext(val resolver: AttachableResolver, val requestHost: String?)

/** `ActionText::Attachment::OpengraphEmbed` (reference/lib/rails_ext/actiontext_opengraph_embeds.rb). */
data class OpengraphEmbed(val href: String?, val url: String?, val filename: String?, val description: String?) {
    /** Whether the image is a Twitter avatar, which the partial styles differently. */
    fun twitterAvatar(): Boolean = (url ?: "").startsWith(TWITTER_AVATAR_URL_PREFIX)

    private companion object {
        const val TWITTER_AVATAR_URL_PREFIX = "https://pbs.twimg.com/profile_images"
    }
}

/** What an `<action-text-attachment>` attaches (`attachables.rs`, `Attachable`). */
sealed interface Attachable {
    /** A mentioned user. */
    data class User(val user: MentionUser) : Attachable

    /** A link unfurl. */
    data class Opengraph(val embed: OpengraphEmbed) : Attachable

    /** `ActionText::Attachables::ContentAttachment`. */
    data class Content(val content: String) : Attachable

    /** `ActionText::Attachables::RemoteImage`. */
    data class RemoteImage(val url: String, val width: String?, val height: String?) : Attachable

    /** Lexxy's `ActionText::Attachables::RemoteVideo`. */
    data class RemoteVideo(
        val url: String,
        val contentType: String,
        val width: String?,
        val height: String?,
        val filename: String?,
    ) : Attachable

    /** `ActionText::Attachables::MissingAttachable`, remembering the model a still-valid SGID named. */
    data class Missing(val signedModel: String?) : Attachable

    /** `attachable_content_type`, which only some attachables define; others raise. */
    fun attachableContentType(): String = when (this) {
        is User -> Attachables.MENTION_CONTENT_TYPE
        is Opengraph -> Attachables.OPENGRAPH_EMBED_CONTENT_TYPE
        else -> throw RichTextError.Raised("NoMethodError: attachable_content_type")
    }
}

/** The attachment node's caption plus its resolved attachable (`attachables.rs`, `Attachment`). */
class Attachment(val attachable: Attachable, val caption: String?)

/**
 * A plain-text representation replaces the attachment node (`attachables.rs`,
 * `PlainTextRepresentation`): strings are parsed as markup in the node's parent, while a content
 * attachment's fragment is moved in as is.
 */
sealed interface PlainTextRepresentation {
    /** Markup parsed in the node's parent. */
    class Html(val html: String) : PlainTextRepresentation

    /** A content attachment's content, loaded as rich text. */
    class Content(val content: String) : PlainTextRepresentation
}

/**
 * Resolving `<action-text-attachment>` nodes to what they attach, and rendering each attachable's
 * partial, as Action Text, Lexxy and Campfire's extensions do (`attachables.rs`).
 */
object Attachables {
    const val MENTION_CONTENT_TYPE = "application/vnd.campfire.mention"
    const val OPENGRAPH_EMBED_CONTENT_TYPE = "application/vnd.actiontext.opengraph-embed"

    /** The regex crate's `.` matches anything but `\n`, which Java's `(?d)` mode reproduces. */
    private val OPENGRAPH_CONTENT_TYPE_RE = Regex("(?d)application/vnd.actiontext.opengraph-embed")
    private val IMAGE_CONTENT_TYPE_RE = Regex("(?md)^image(/.+|$)")
    private val VIDEO_CONTENT_TYPE_RE = Regex("(?md)^video(/.+|$)")

    /** Matched over the Marshal dump's bytes, read as Latin-1 so that each byte is one character. */
    private val MARSHALED_GID_RE = Regex("gid://campfire/[^/]+/[0-9]+")

    /**
     * `^[-a-z]+://|^(?:cid|data):|^//` under `(?mi)`; the regex crate's case folding adds U+017F
     * and U+212A to `[a-z]`.
     */
    private val ASSET_URI_RE = Regex("(?md)^[-a-zA-Z\\u017F\\u212A]+://|^(?:[cC][iI][dD]|[dD][aA][tT][aA]):|^//")

    /**
     * Campfire's `ActionText::Attachment.from_node` (reference/lib/rails_ext/action_text_attachables.rb):
     * an opengraph embed, else a User found through a possibly invalid SGID, else Action Text's own
     * lookup (as extended by Lexxy).
     */
    fun attachmentFromNode(dom: Dom, node: Int, ctx: RenderContext): Attachment {
        val attachable = attachableFromNode(dom, node, ctx)
        return Attachment(attachable, RubyText.presence(dom.attr(node, "caption")))
    }

    private fun attachableFromNode(dom: Dom, node: Int, ctx: RenderContext): Attachable {
        opengraphEmbedFromNode(dom, node, ctx)?.let { return Attachable.Opengraph(it) }
        attachableFromPossiblyExpiredSgid(dom.attr(node, "sgid"), ctx)?.let { return Attachable.User(it) }
        return actionTextAttachableFromNode(dom, node, ctx)
    }

    /**
     * `ActionText::Attachable.from_node`, with Lexxy's RemoteVideo fallback for missing
     * attachables. `Content#attachables` (and so `Message#mentionees`) uses this directly, without
     * Campfire's invalid-signature fallback.
     */
    fun actionTextAttachableFromNode(dom: Dom, node: Int, ctx: RenderContext): Attachable {
        val sgid = dom.attr(node, "sgid")
        val signed = if (sgid != null) ctx.resolver.locateSigned(sgid) else SignedLookup.Invalid
        if (signed is SignedLookup.User) return Attachable.User(signed.user)
        val contentType = dom.attr(node, "content-type")
        val content = dom.attr(node, "content")
        if (content != null && contentType != null && contentType.contains("html") && !RubyText.isBlank(content)) {
            return Attachable.Content(content)
        }
        val url = dom.attr(node, "url")
        if (url != null) {
            if (IMAGE_CONTENT_TYPE_RE.containsMatchIn(contentType ?: "")) {
                return Attachable.RemoteImage(url, dom.attr(node, "width"), dom.attr(node, "height"))
            }
            if (VIDEO_CONTENT_TYPE_RE.containsMatchIn(contentType ?: "")) {
                return Attachable.RemoteVideo(
                    url, contentType ?: "", dom.attr(node, "width"), dom.attr(node, "height"), dom.attr(node, "filename"),
                )
            }
        }
        return Attachable.Missing((signed as? SignedLookup.MissingRecord)?.modelName)
    }

    /**
     * `attachable_from_possibly_expired_sgid`: reads the GlobalID out of an SGID without checking
     * its signature, and only ever returns a User.
     */
    internal fun attachableFromPossiblyExpiredSgid(sgid: String?, ctx: RenderContext): MentionUser? {
        if (sgid == null) return null
        val fields = sgid.split("--")
        if (fields.all { it.isEmpty() }) return null
        val decoded = decodeBase64(fields[0])
        val validUtf8 = isStrictUtf8(decoded)
        val json = RubyText.jsonParse(String(decoded, Charsets.UTF_8))
        if (json === RubyText.NOT_JSON) {
            if (validUtf8) throw RichTextError.Raised("JSON::ParserError")
            throw RichTextError.Unrenderable("JSON::ParserError")
        }
        if (json !is Map<*, *>) throw RichTextError.Raised("NoMethodError: dig")
        val rails = when (val r = json["_rails"]) {
            null -> null
            is Map<*, *> -> r
            else -> throw RichTextError.Raised("TypeError: dig")
        }
        fun truthy(v: Any?): Any? = v.takeIf { it != null && it != false }
        val data = truthy(rails?.get("data"))
        val gid: String? = if (data != null) {
            data as? String
        } else {
            val message = truthy(rails?.get("message"))
            if (message != null) {
                if (message !is String) throw RichTextError.Raised("NoMethodError: unpack1")
                val bytes = decodeBase64(message)
                MARSHALED_GID_RE.find(String(bytes, Charsets.ISO_8859_1))?.value?.let {
                    String(it.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
                }
            } else {
                null
            }
        }
        if (gid == null) return null
        return when (val found = ctx.resolver.findGid(gid)) {
            is GidLookup.User -> found.user
            GidLookup.OtherModel, GidLookup.NotFound -> null
            GidLookup.Raises -> throw RichTextError.Raised("GlobalID.find")
        }
    }

    /** `Base64.strict_decode64(message) rescue Base64.urlsafe_decode64(message)`. */
    private fun decodeBase64(message: String): ByteArray =
        RubyBase64.strictDecode(message) ?: RubyBase64.urlsafeDecode(message) ?: throw RichTextError.Raised("ArgumentError: invalid base64")

    private fun isStrictUtf8(bytes: ByteArray): Boolean = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        true
    } catch (_: CharacterCodingException) {
        false
    }

    /** `ActionText::Attachment::OpengraphEmbed.from_node`; null when the node isn't an embed. */
    fun opengraphEmbedFromNode(dom: Dom, node: Int, ctx: RenderContext): OpengraphEmbed? {
        val contentType = dom.attr(node, "content-type") ?: return null
        if (!OPENGRAPH_CONTENT_TYPE_RE.containsMatchIn(contentType)) return null
        val host = ctx.requestHost ?: ""
        return if (RubyText.presence(dom.attr(node, "filename")) != null) {
            OpengraphEmbed(
                href = webUrl(dom.attr(node, "href"), host),
                url = webUrl(dom.attr(node, "url"), host),
                filename = dom.attr(node, "filename"),
                description = dom.attr(node, "caption"),
            )
        } else {
            embedFromContent(dom.attr(node, "content") ?: "", host)
        }
    }

    /**
     * `attributes_from_content`: the details Lexxy serializes as the embed's content markup. Rails
     * parses this with `Nokogiri::HTML` (libxml2's HTML4 parser); the HTML5 parser agrees with it
     * on the markup the embed partial and Lexxy produce.
     */
    private fun embedFromContent(content: String, host: String): OpengraphEmbed {
        val dom = Dom()
        val fragment = dom.parseFragment(content)
        val all = dom.descendants(fragment)
        fun withClass(cls: String): Int = all.firstOrNull { hasClass(dom, it, cls) } ?: -1
        val title = withClass("og-embed__title")
        val link = if (title >= 0) dom.descendants(title).firstOrNull { dom.localName(it) == "a" } ?: -1 else -1
        val image = all.firstOrNull { n -> dom.localName(n) == "img" && dom.ancestors(n).any { hasClass(dom, it, "og-embed__image") } } ?: -1
        val description = withClass("og-embed__description")
        val named = if (link >= 0) link else title
        return OpengraphEmbed(
            href = webUrl(if (link >= 0) dom.attr(link, "href") else null, host),
            url = webUrl(if (image >= 0) dom.attr(image, "src") else null, host),
            filename = if (named >= 0) strip(dom.textContent(named)) else null,
            description = if (description >= 0) strip(dom.textContent(description)) else null,
        )
    }

    private fun hasClass(dom: Dom, node: Int, cls: String): Boolean {
        val value = dom.attr(node, "class") ?: return false
        return value.split(' ', '\t', '\n', '\r').any { it == cls }
    }

    /** `web_url`: an absolute http(s) URL on a named host other than this Campfire's, else null. */
    fun webUrl(value: String?, requestHost: String): String? {
        if (value == null || RubyText.isBlank(value)) return null
        return when (val parsed = RubyUri.parse(value)) {
            RubyUri.Parsed.InvalidUri -> null
            RubyUri.Parsed.InvalidComponent -> throw RichTextError.Raised("URI::InvalidComponentError")
            is RubyUri.Parsed.Ok -> if (parsed.uri.isHttp() && elsewhere(parsed.uri.host, requestHost)) value else null
        }
    }

    private fun elsewhere(host: String?, requestHost: String): Boolean {
        if (host == null || !namedHost(host)) return false
        return canonicalHost(host) != canonicalHost(requestHost)
    }

    private fun namedHost(host: String): Boolean {
        if (RubyText.isBlank(host) || host.contains('%') || !host.contains('.')) return false
        val trimmed = host.trimEnd('.')
        if (trimmed.isEmpty()) throw RichTextError.Raised("NoMethodError: match?")
        val label = trimmed.substring(trimmed.lastIndexOf('.') + 1)
        return label.any { it in 'a'..'z' || it in 'A'..'Z' } && !label.lowercase().startsWith("0x")
    }

    private fun canonicalHost(host: String): String {
        val lower = host.lowercase()
        return if (lower.endsWith('.')) lower.substring(0, lower.length - 1) else lower
    }

    /**
     * `render_action_text_attachment(attachment)`: the attachable's partial, chomped.
     * [renderContent] renders a nested content attachment's own content (`ContentAttachment#to_html`).
     */
    fun renderAttachment(attachment: Attachment, renderContent: (String) -> String): String {
        val html = when (val a = attachment.attachable) {
            is Attachable.User -> renderMention(a.user)
            is Attachable.Opengraph -> renderOpengraphEmbed(a.embed)
            is Attachable.Missing -> "☒"
            is Attachable.Content -> "<figure class=\"attachment attachment--content\">\n  ${renderContent(a.content)}\n</figure>\n"
            is Attachable.RemoteImage -> {
                val out = StringBuilder("<figure class=\"attachment attachment--preview\">\n  ")
                out.append(imageTag(a.url, a.width, a.height)).append('\n')
                appendCaption(out, attachment.caption)
                out.append("</figure>\n").toString()
            }
            is Attachable.RemoteVideo -> {
                val out = StringBuilder("<figure class=\"attachment attachment--preview attachment--video\">\n  <video controls=\"controls\"")
                if (a.width != null) out.append(" width=\"").append(Erb.htmlEscape(a.width)).append('"')
                if (a.height != null) out.append(" height=\"").append(Erb.htmlEscape(a.height)).append('"')
                out.append(">\n    <source src=\"").append(Erb.htmlEscape(a.url)).append("\" type=\"")
                    .append(Erb.htmlEscape(a.contentType)).append("\">\n</video>")
                appendCaption(out, attachment.caption)
                out.append("</figure>\n").toString()
            }
        }
        return RubyText.chomp(html)
    }

    private fun appendCaption(out: StringBuilder, caption: String?) {
        if (caption == null) return
        out.append("    <figcaption class=\"attachment__caption\">\n      ").append(Erb.htmlEscape(caption))
            .append("\n    </figcaption>\n")
    }

    /** reference/app/views/users/_mention.html.erb, with `avatar_tag` (users/avatars_helper.rb). */
    fun renderMention(user: MentionUser): String =
        "<span class=\"mention\" sgid=\"${Erb.htmlEscape(user.attachableSgid)}\"><a title=\"${Erb.htmlEscape(user.title)}\" " +
            "class=\"btn avatar\" data-turbo-frame=\"_top\" href=\"${Erb.htmlEscape(user.userPath)}\"><img aria-hidden=\"true\" " +
            "src=\"${Erb.htmlEscape(user.avatarPath)}\" width=\"48\" height=\"48\" /></a> ${Erb.htmlEscape(user.name)}</span>\n"

    /** reference/app/views/action_text/attachables/_opengraph_embed.html.erb */
    fun renderOpengraphEmbed(embed: OpengraphEmbed): String {
        val title = when {
            embed.href != null -> {
                val text = if (embed.filename != null) {
                    Erb.htmlEscape(RubyText.truncate(embed.filename, 280, "…"))
                } else {
                    Erb.htmlEscape(embed.href)
                }
                "<a rel=\"noreferrer\" target=\"_blank\" href=\"${Erb.htmlEscape(embed.href)}\">$text</a>"
            }
            embed.filename != null -> Erb.htmlEscape(RubyText.truncate(embed.filename, 280, "…"))
            else -> ""
        }
        val out = StringBuilder()
        out.append("<figure class=\"attachment attachment--content attachment--og\">\n  <actiontext-opengraph-embed>\n")
            .append("    <div class=\"og-embed gap ").append(if (embed.twitterAvatar()) "og-embed--twitter-avatar" else "")
            .append("\">\n      <div class=\"og-embed__content\">\n        <div class=\"og-embed__title\">\n          ")
            .append(title)
            .append("\n        </div>\n        <div class=\"og-embed__description\">")
            .append(Erb.htmlEscape(RubyText.truncate(embed.description ?: "", 560, "…")))
            .append("</div>\n      </div>\n")
        if (embed.url != null) {
            out.append("        <div class=\"og-embed__image\">\n          <img src=\"").append(Erb.htmlEscape(embed.url))
                .append("\" class=\"image center\" alt=\"\">\n        </div>\n")
        }
        out.append("    </div>\n  </actiontext-opengraph-embed>\n</figure>\n")
        return out.toString()
    }

    /**
     * `image_tag(url, width:, height:)` for a remote image. Sources that aren't URLs go through
     * the asset pipeline, which raises for anything it doesn't know; a rooted path passes through.
     */
    private fun imageTag(url: String, width: String?, height: String?): String {
        val src = when {
            RubyText.isBlank(url) -> ""
            ASSET_URI_RE.containsMatchIn(url) || url.startsWith('/') -> url
            else -> throw RichTextError.Raised("Propshaft::MissingAssetError")
        }
        val out = StringBuilder("<img")
        if (width != null) out.append(" width=\"").append(Erb.htmlEscape(width)).append('"')
        if (height != null) out.append(" height=\"").append(Erb.htmlEscape(height)).append('"')
        out.append(" src=\"").append(Erb.htmlEscape(src)).append("\" />")
        return out.toString()
    }

    /** `Attachment#to_plain_text`. */
    fun attachmentPlainText(attachment: Attachment): PlainTextRepresentation {
        val caption = attachment.caption
        return when (val a = attachment.attachable) {
            is Attachable.User -> PlainTextRepresentation.Html("@${a.user.name}")
            is Attachable.Opengraph -> PlainTextRepresentation.Html("")
            is Attachable.Content -> PlainTextRepresentation.Content(a.content)
            is Attachable.RemoteImage -> PlainTextRepresentation.Html("[${caption ?: "Image"}]")
            is Attachable.RemoteVideo -> PlainTextRepresentation.Html("[${caption ?: a.filename ?: "Video"}]")
            is Attachable.Missing -> PlainTextRepresentation.Html(caption ?: "")
        }
    }
}
