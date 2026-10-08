package campfire.richtext

/**
 * A tag and attribute allowlist, as passed to `sanitize(html, tags:, attributes:)`
 * (`once-campfire-rust/crates/richtext/src/sanitizer.rs`, `SafeList`).
 */
class SafeList(val tags: List<String>, val attributes: List<String>) {
    private val tagSet = tags.toHashSet()
    private val attributeSet = attributes.toHashSet()

    /** Whether elements named [name] stay. */
    fun allowsTag(name: String): Boolean = name in tagSet

    /** Whether attributes named [name] (qualified) stay. */
    fun allowsAttribute(name: String): Boolean = name in attributeSet

    companion object {
        /** `Rails::HTML::Concern::Scrubber::SafeList::DEFAULT_ALLOWED_TAGS`. */
        val DEFAULT_ALLOWED_TAGS = listOf(
            "a", "abbr", "acronym", "address", "b", "big", "blockquote", "br", "cite", "code", "dd", "del", "dfn", "div",
            "dl", "dt", "em", "h1", "h2", "h3", "h4", "h5", "h6", "hr", "i", "img", "ins", "kbd", "li", "mark", "ol", "p",
            "pre", "samp", "small", "span", "strong", "sub", "sup", "time", "tt", "ul", "var",
        )

        /**
         * `DEFAULT_ALLOWED_ATTRIBUTES` without `name`, which let a message clobber the page's DOM
         * globals (`<img name="body">` shadows `document.body`). Nothing Campfire's composer
         * writes has one.
         */
        val DEFAULT_ALLOWED_ATTRIBUTES =
            listOf("abbr", "alt", "cite", "class", "datetime", "height", "href", "lang", "src", "title", "width", "xml:lang")

        /** `ContentFilters::EDITOR_FORMATTING_TAGS` (reference/app/helpers/content_filters.rb). */
        val EDITOR_FORMATTING_TAGS = listOf("s", "u", "mark", "table", "thead", "tbody", "tfoot", "tr", "th", "td")

        /** `ContentFilters::EDITOR_FORMATTING_ATTRIBUTES`. */
        val EDITOR_FORMATTING_ATTRIBUTES = listOf("data-language")

        /** `ActionText::Attachment::ATTRIBUTES`. */
        val ATTACHMENT_ATTRIBUTES = listOf(
            "sgid", "content-type", "url", "href", "filename", "filesize", "width", "height", "previewable", "presentation",
            "caption", "content",
        )

        /** `ContentFilters::SanitizeTags::ALLOWED_TAGS`. */
        val SANITIZE_TAGS_ALLOWED_TAGS: List<String> = listOf(
            "a", "abbr", "acronym", "address", "b", "big", "blockquote", "br", "cite", "code", "dd", "del", "dfn", "div",
            "dl", "dt", "em", "h1", "h2", "h3", "h4", "h5", "h6", "hr", "i", "ins", "kbd", "li", "ol", "p", "pre", "samp",
            "small", "span", "strong", "sub", "sup", "time", "tt", "ul", "var",
        ) + EDITOR_FORMATTING_TAGS + listOf("action-text-attachment", "figure", "figcaption")

        /** Action View's `sanitize(html)` with no options: the sanitizer's class-level defaults. */
        val defaults = SafeList(DEFAULT_ALLOWED_TAGS, DEFAULT_ALLOWED_ATTRIBUTES)

        /**
         * `ActionText::ContentHelper.allowed_tags`/`allowed_attributes` as configured at boot:
         * Action Text's defaults, then Lexxy's additions (lexxy/engine.rb, "lexxy.sanitization"),
         * then Campfire's (reference/lib/rails_ext/action_text_allowed_tags.rb).
         */
        val actionText: SafeList = run {
            val tags = ArrayList(DEFAULT_ALLOWED_TAGS)
            tags.addAll(listOf("action-text-attachment", "figure", "figcaption"))
            tags.addAll(listOf("video", "audio", "source", "embed", "table", "tbody", "tr", "th", "td"))
            for (tag in EDITOR_FORMATTING_TAGS) if (tag !in tags) tags.add(tag)
            val attributes = ArrayList(DEFAULT_ALLOWED_ATTRIBUTES)
            attributes.addAll(ATTACHMENT_ATTRIBUTES)
            attributes.addAll(listOf("controls", "poster", "data-language", "style", "value", "start"))
            for (attribute in EDITOR_FORMATTING_ATTRIBUTES) if (attribute !in attributes) attributes.add(attribute)
            SafeList(tags, attributes)
        }

        /** `ContentFilters::SanitizeAttributes`: SanitizeTags' tags, Action Text's attributes plus `class`. */
        val contentFilter: SafeList = run {
            val attributes = ArrayList(actionText.attributes)
            if ("class" !in attributes) attributes.add("class")
            SafeList(SANITIZE_TAGS_ALLOWED_TAGS, attributes)
        }

        /** `MessagesHelper::AUTO_LINK_ALLOWED_TAGS`/`AUTO_LINK_ALLOWED_ATTRIBUTES`. */
        val autoLink: SafeList = run {
            val tags = ArrayList(DEFAULT_ALLOWED_TAGS)
            for (tag in EDITOR_FORMATTING_TAGS) if (tag !in tags) tags.add(tag)
            val attributes = ArrayList(DEFAULT_ALLOWED_ATTRIBUTES)
            attributes.addAll(EDITOR_FORMATTING_ATTRIBUTES)
            SafeList(tags, attributes)
        }
    }
}

/**
 * `Rails::HTML5::SafeListSanitizer` with a `Rails::HTML::PermitScrubber`, over Loofah's HTML5
 * scrubbing helpers (rails-html-sanitizer 1.7.1, loofah 2.25.2), as
 * `once-campfire-rust/crates/richtext/src/sanitizer.rs` reproduces them: every sanitization layer
 * in the pipeline is this one scrubber with a different pair of allowlists.
 */
object Sanitizer {
    /** `SafeListSanitizer#sanitize(html, tags:, attributes:)`. Throws [RichTextError.Parse]. */
    fun sanitize(html: String, list: SafeList): String {
        if (html.isEmpty()) return ""
        val dom = Dom()
        val fragment = scrubbed(dom, html, list)
        return dom.toHtml(fragment)
    }

    /**
     * [sanitize], serialized with `<` and `>` escaped in attribute values too, so that the result
     * can be scanned with regular expressions (auto_link) without mistaking an attribute for
     * text. Rails serializes them raw; the two are the same DOM.
     */
    fun sanitizeWithEscapedAttributeBrackets(html: String, list: SafeList): String {
        if (html.isEmpty()) return ""
        val dom = Dom()
        val fragment = scrubbed(dom, html, list)
        return dom.toHtmlWithEscapedAttributeBrackets(fragment)
    }

    private fun scrubbed(dom: Dom, html: String, list: SafeList): Int {
        val fragment = dom.parseFragment(html)
        for (child in dom.children(fragment).toArray()) scrubBottomUp(dom, child, list)
        return fragment
    }

    /**
     * `Loofah::Scrubber#traverse_conditionally_bottom_up`: children (as they were before any of
     * them was scrubbed) first, then the node itself.
     */
    private fun scrubBottomUp(dom: Dom, node: Int, list: SafeList) {
        for (child in dom.children(node).toArray()) scrubBottomUp(dom, child, list)
        scrub(dom, node, list)
    }

    /**
     * `Rails::HTML::PermitScrubber#scrub`: an element outside the allowlist is unwrapped if it's
     * HTML (comments too, which have no children), and dropped with its contents if it's foreign
     * (SVG, MathML), since that carries a namespace.
     */
    private fun scrub(dom: Dom, node: Int, list: SafeList) {
        if (dom.isText(node)) return
        val name = dom.localName(node)
        if (name == null || !list.allowsTag(name)) {
            val foreign = dom.isElement(node) && !dom.isHtmlElement(node)
            if (!foreign) {
                for (child in dom.children(node).toArray()) dom.insertBefore(node, child)
            }
            dom.detach(node)
            return
        }
        scrubAttributes(dom, node, list)
    }

    /**
     * `PermitScrubber#scrub_attributes` with an attribute allowlist. Attributes are visited in
     * order, and each allowed one re-escapes every URL attribute on the node as it goes, so a
     * later URL is checked in its re-escaped form (" javascript:" has become "%20javascript:" and
     * passes).
     */
    private fun scrubAttributes(dom: Dom, node: Int, list: SafeList) {
        val element = dom.element(node) ?: return
        val isA = element.name.local == "a"
        val attrs = element.attrs
        var i = 0
        while (i < attrs.size) {
            val attr = attrs[i]
            val name = attr.qualifiedName()
            val scrubbed = !list.allowsAttribute(name) || (name in ATTR_VAL_IS_URI && !allowedUri(attr.value))
            if (scrubbed) {
                attrs.removeAt(i)
                continue
            }
            val blankSrc = name == "src" && attr.value.codePoints().allMatch { RubyText.isWhitespace(it) }
            if (blankSrc) attrs.removeAt(i) else i++
            forceCorrectAttributeEscaping(attrs, isA)
        }
        scrubStyle(dom, node)
    }

    /**
     * Where Loofah's `scrub_css_attribute` runs `style` through its CSS scrubber, this keeps only
     * what Lexxy writes: highlight colors (`color` and `background-color`, which Lexxy's own paste
     * filter also limits `style` to) with plain color values. A message's presentation drops
     * `style` in auto_link anyway, but the HTML body bots and webhooks get keeps it.
     */
    private fun scrubStyle(dom: Dom, node: Int) {
        val style = dom.attr(node, "style") ?: return
        val declarations = style.split(';').filter { RubyText.trimWhitespace(it).isNotEmpty() }.map { declaration ->
            val colon = declaration.indexOf(':')
            val property = if (colon >= 0) declaration.substring(0, colon) else declaration
            val value = if (colon >= 0) declaration.substring(colon + 1) else ""
            asciiLowercase(RubyText.trimWhitespace(property)) to RubyText.trimWhitespace(value)
        }
        fun allowed(d: Pair<String, String>) = d.first in ALLOWED_STYLE_PROPERTIES && isPlainColor(d.second)
        if (declarations.isNotEmpty() && declarations.all(::allowed)) return
        val scrubbed = declarations.filter(::allowed).joinToString("") { (property, value) -> "$property: $value;" }
        if (scrubbed.isEmpty()) dom.removeAttr(node, "style") else dom.setAttr(node, "style", scrubbed)
    }

    private val ALLOWED_STYLE_PROPERTIES = setOf("color", "background-color")

    /**
     * Unicode White_Space as a character class body: the regex crate's `\s`, which Java's isn't.
     * `[a-z]` under the regex crate's `(?i)` also matches U+017F and U+212A, which fold to `s`
     * and `k`.
     */
    private const val WS = "\\t\\n\\x0B\\f\\r \\u0085\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000"
    private const val LETTERS = "a-zA-Z\\u017F\\u212A"

    private val PLAIN_COLOR = Regex(
        "\\A(?:[$LETTERS]+|#[0-9a-fA-F]{3,8}|[vV][aA][rR]\\([$WS]*--[${LETTERS}0-9_-]+[$WS]*\\)|" +
            "(?:[rR][gG][bB]|[rR][gG][bB][aA]|[hH][sS\\u017F][lL]|[hH][sS\\u017F][lL][aA])\\([0-9$LETTERS.,%$WS/+-]*\\))\\z",
    )

    /**
     * A color keyword, a hex color, a custom property (`var(--highlight-1)`, as Lexxy's highlights
     * are), or an `rgb()`/`hsl()` color: nothing that can load a URL, escape, or run an expression.
     */
    private fun isPlainColor(value: String): Boolean = PLAIN_COLOR.matches(value)

    /** `Loofah::HTML5::SafeList::ATTR_VAL_IS_URI`. */
    private val ATTR_VAL_IS_URI = setOf("action", "cite", "href", "longdesc", "poster", "preload", "src", "xlink:href", "xml:base")

    /**
     * `Loofah::HTML5::Scrub.force_correct_attribute_escaping!` (libxml2 builds only, which CRuby
     * is): spaces and double quotes in `href`, `action`, `src` and an `a`'s `name` become `%20`
     * and `%22`. The value is written back through `Nokogiri::XML::Attr#value=`, where libxml2
     * drops the C0 controls XML 1.0 doesn't allow.
     */
    private fun forceCorrectAttributeEscaping(attrs: List<Attr>, isA: Boolean) {
        for (attr in attrs) {
            val name = attr.qualifiedName()
            val qualifies = name == "href" || name == "action" || name == "src" || (name == "name" && isA)
            if (qualifies && attr.value.any(::isEscaped)) attr.value = escapeAttributeValue(attr.value)
        }
    }

    private fun isEscaped(c: Char): Boolean = c == ' ' || c == '"' || (c < ' ' && c != '\t' && c != '\n' && c != '\r')

    private fun escapeAttributeValue(value: String): String {
        val escaped = StringBuilder(value.length + 8)
        for (c in value) {
            when {
                c == ' ' -> escaped.append("%20")
                c == '"' -> escaped.append("%22")
                c == '\t' || c == '\n' || c == '\r' -> escaped.append(c)
                c < ' ' -> Unit
                else -> escaped.append(c)
            }
        }
        return escaped.toString()
    }

    private val ALLOWED_PROTOCOLS = setOf(
        "afs", "aim", "callto", "data", "ed2k", "fax", "ftp", "gopher", "http", "https", "irc", "line", "mailto", "modem",
        "news", "nntp", "rsync", "rtsp", "sftp", "sms", "ssh", "tag", "tel", "telnet", "urn", "webcal", "xmpp",
    )

    private val ALLOWED_URI_DATA_MEDIATYPES = setOf("image/gif", "image/jpeg", "image/png", "text/css", "text/plain")

    /** `Loofah::HTML5::Scrub::CONTROL_CHARACTERS`: /[`\u0000- \u007f\u0080-ā]/ */
    private fun isControlCharacter(cp: Int): Boolean = cp == '`'.code || cp <= 0x20 || cp == 0x7f || cp in 0x80..0x101

    private fun withoutControls(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (!isControlCharacter(cp)) out.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    /** `Loofah::HTML5::Scrub.allowed_uri?`. */
    fun allowedUri(uri: String): Boolean {
        val decoded = decodeNumericCharacterReferences(cgiUnescapeHtml(withoutControls(uri)))
        var s = withoutControls(decoded)
        s = s.replace("&Tab;", "").replace("&NewLine;", "")
        s = s.replace("&colon;", ":")
        s = s.lowercase()
        val protocol = protocolBeforeSeparator(s) ?: return true
        if (protocol !in ALLOWED_PROTOCOLS) return false
        if (protocol == "data") return dataUriMediatype(s)?.let { it in ALLOWED_URI_DATA_MEDIATYPES } ?: false
        return true
    }

    /**
     * Matches `\A[a-z][a-z0-9+\-.]*` followed by `PROTOCOL_SEPARATOR`
     * (`/:|(&#0*58)|(&#x0*3a)|(%|&#37;)3A/i`) and returns the scheme.
     */
    private fun protocolBeforeSeparator(s: String): String? {
        if (s.isEmpty() || s[0] !in 'a'..'z') return null
        var end = 1
        while (end < s.length && (s[end] in 'a'..'z' || s[end] in '0'..'9' || s[end] == '+' || s[end] == '-' || s[end] == '.')) end++
        return if (separatorLen(s.substring(end)) != null) s.substring(0, end) else null
    }

    private fun separatorLen(s: String): Int? {
        val lower = asciiLowercase(s)
        if (lower.startsWith(':')) return 1
        if (lower.startsWith("&#x")) {
            val rest = lower.substring(3)
            val zeros = rest.length - rest.trimStart('0').length
            if (rest.startsWith("3a", zeros)) return 3 + zeros + 2
        }
        if (lower.startsWith("&#")) {
            val rest = lower.substring(2)
            val zeros = rest.length - rest.trimStart('0').length
            if (rest.startsWith("58", zeros)) return 2 + zeros + 2
        }
        if (lower.startsWith("%3a")) return 3
        if (lower.startsWith("&#37;3a")) return 7
        return null
    }

    /** `Loofah::HTML5::Scrub.data_uri_mediatype`. */
    private fun dataUriMediatype(s: String): String? {
        val rest = if (s.startsWith("data:")) s.substring(5) else s
        val comma = rest.indexOf(',')
        if (comma < 0) return null
        var metadata = rest.substring(0, comma)
        if (metadata.endsWith(";base64")) metadata = metadata.substring(0, metadata.length - 7)
        val semicolon = metadata.indexOf(';')
        val mediatype = (if (semicolon >= 0) metadata.substring(0, semicolon) else metadata)
            .trim { it == ' ' || it == '\t' || it == '\n' || it == '\u000b' || it == '\u000c' || it == '\r' || it == '\u0000' }
        fun tchar(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "!#$%&'*+-.^_`|~"
        val slash = mediatype.indexOf('/')
        val valid = slash >= 0 && slash > 0 && slash < mediatype.length - 1 &&
            mediatype.substring(0, slash).all(::tchar) && mediatype.substring(slash + 1).all(::tchar)
        return if (valid) mediatype else "text/plain"
    }

    /** `CGI.unescapeHTML`: the five named entities plus terminated numeric references. */
    fun cgiUnescapeHtml(s: String): String {
        val first = s.indexOf('&')
        if (first < 0) return s
        val out = StringBuilder(s.length)
        out.append(s, 0, first)
        var pos = first
        while (pos < s.length) {
            val amp = s.indexOf('&', pos)
            if (amp < 0) {
                out.append(s, pos, s.length)
                break
            }
            out.append(s, pos, amp)
            val consumed = unescapeOne(s, amp, out)
            if (consumed > 0) {
                pos = amp + consumed
            } else {
                out.append('&')
                pos = amp + 1
            }
        }
        return out.toString()
    }

    private val NAMED = arrayOf("&apos;" to "'", "&amp;" to "&", "&quot;" to "\"", "&gt;" to ">", "&lt;" to "<")

    /** Appends what the reference at [start] means and returns its length, or 0 when it isn't one. */
    private fun unescapeOne(s: String, start: Int, out: StringBuilder): Int {
        for ((name, value) in NAMED) {
            if (s.startsWith(name, start)) {
                out.append(value)
                return name.length
            }
        }
        val end = s.indexOf(';', start)
        if (end < 0) return 0
        val body = s.substring(start + 1, end)
        val code = when {
            body.startsWith("#x") || body.startsWith("#X") -> numericReference(body.substring(2), 16)
            body.startsWith("#") -> numericReference(body.substring(1), 10)
            else -> -1
        }
        if (code < 0 || code in 0xD800..0xDFFF) return 0
        out.appendCodePoint(code)
        return end + 1 - start
    }

    /**
     * The code point of a numeric reference's digits, when `CGI.unescapeHTML` decodes it: any
     * number of leading zeros, and below U+10FFFF (cgi's `escape.c` skips `cc >= charlimit`).
     */
    private fun numericReference(digits: String, radix: Int): Int {
        if (digits.isEmpty() || !digits.all { isDigit(it, radix) }) return -1
        val significant = digits.trimStart('0')
        if (significant.length > (if (radix == 16) 6 else 7)) return -1
        val code = if (significant.isEmpty()) 0 else significant.toInt(radix)
        return if (code < 0x10ffff) code else -1
    }

    private fun isDigit(c: Char, radix: Int): Boolean =
        c in '0'..'9' || (radix == 16 && (c in 'a'..'f' || c in 'A'..'F'))

    /**
     * `Loofah::HTML5::Scrub.decode_numeric_character_references`: `&#(x[0-9a-f]+|[0-9]+);?` (case
     * insensitive), skipping references with too many significant digits or invalid code points.
     */
    private fun decodeNumericCharacterReferences(s: String): String {
        if (s.indexOf("&#") < 0) return s
        val out = StringBuilder(s.length)
        var i = 0
        var copied = 0
        while (i < s.length) {
            if (s[i] == '&' && i + 1 < s.length && s[i + 1] == '#') {
                val start = i + 2
                val hex = start < s.length && (s[start] == 'x' || s[start] == 'X')
                val digitsStart = if (hex) start + 1 else start
                var end = digitsStart
                while (end < s.length && isDigit(s[end], if (hex) 16 else 10)) end++
                if (end > digitsStart) {
                    val digits = s.substring(digitsStart, end)
                    val fullEnd = if (end < s.length && s[end] == ';') end + 1 else end
                    val significant = digits.trimStart('0')
                    val limit = if (hex) 6 else 7
                    val decoded = if (significant.length <= limit) {
                        (if (significant.isEmpty()) 0 else significant.toInt(if (hex) 16 else 10))
                            .takeIf { it <= 0x10ffff && it !in 0xD800..0xDFFF }
                    } else {
                        null
                    }
                    out.append(s, copied, i)
                    if (decoded != null) out.appendCodePoint(decoded) else out.append(s, i, fullEnd)
                    i = fullEnd
                    copied = i
                    continue
                }
            }
            i++
        }
        out.append(s, copied, s.length)
        return out.toString()
    }

    private fun asciiLowercase(s: String): String {
        var i = 0
        while (i < s.length && s[i] !in 'A'..'Z') i++
        if (i == s.length) return s
        val chars = s.toCharArray()
        for (j in i until chars.size) if (chars[j] in 'A'..'Z') chars[j] = chars[j] + 32
        return String(chars)
    }
}
