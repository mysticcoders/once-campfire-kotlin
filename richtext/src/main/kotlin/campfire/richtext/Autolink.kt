package campfire.richtext

import campfire.ruby.Erb
import campfire.ruby.urlEncode

/**
 * rails_autolink 1.1.8's `auto_link(text, html: { target: "_blank" }, sanitize_options: ...)`, as
 * `MessagesHelper#message_presentation` calls it (`once-campfire-rust/crates/richtext/src/autolink.rs`).
 * It works on the serialized HTML with regular expressions, so the exact serialization from the
 * earlier steps matters.
 *
 * One deliberate difference (the Rust port's) closes a stored XSS in rails_autolink: the
 * sanitized HTML is serialized with `<` and `>` escaped in attribute values. Nokogiri leaves them
 * raw, so a URL after a `>` in a `title` looked like text to `auto_linked?`, and the
 * `<a href="...">` inserted there closed the attribute and turned the rest of its value into
 * markup. With them escaped, every `<` and `>` in the text is a tag's, so auto_link only ever
 * inserts links between tags.
 */
object Autolink {
    /** An ASCII word matched as the regex crate's `(?i)` does: `k` and `s` also match U+212A and U+017F. */
    private fun caseless(word: String): String = word.map { c ->
        when (c) {
            'k' -> "[kK\\u212A]"
            's' -> "[sS\\u017F]"
            in 'a'..'z' -> "[$c${c.uppercaseChar()}]"
            else -> Regex.escape(c.toString())
        }
    }.joinToString("")

    private val SCHEMES = listOf(
        "ed2k", "ftp", "http", "https", "irc", "mailto", "news", "gopher", "nntp", "telnet", "webcal", "xmpp", "callto",
        "feed", "svn", "urn", "aim", "rsync", "tag", "ssh", "sftp", "rtsp", "afs", "file",
    )

    /** `AUTO_LINK_RE`. Ruby's `\s` and `\w` are ASCII-only. */
    private val AUTO_LINK_RE = Regex(
        "(?:((?:" + SCHEMES.joinToString("|") { caseless(it) } + "):)//|" + caseless("www") +
            "\\.[a-zA-Z0-9_])[^ \\t\\r\\n\\x0B\\x0C<\\u00A0\"]+",
    )

    /** `AUTO_EMAIL_RE` without its lookbehind, which is checked separately; matched at a position. */
    private val AUTO_EMAIL_RE =
        Regex("[a-zA-Z0-9_.!#$%+-]\\.?[a-zA-Z0-9_.!#$%&'*/=?^`{|}~+-]*@[a-zA-Z0-9_-]+(?:\\.[a-zA-Z0-9_-]+)+").toPattern()

    /**
     * `AUTO_LINK_CRE[2]`: `/<a\b.*?>/i`, where `.` stops at newlines (Java's `(?d)`) and `\b` is
     * Unicode's (Java's `(?U)`); tried only at a `<`.
     */
    private val OPEN_ANCHOR_RE = Regex("(?dU)<[aA]\\b.*?>").toPattern()

    private fun isEmailLocalChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "_.!#$%&'*/=?^`{|}~+-"

    /**
     * What `auto_linked?(left, right)` asks about the text around a match, where `left` is
     * everything before it and `right` everything after (`autolink.rs`, `TagIndex`).
     * rails_autolink runs its regular expressions over the whole of `left` for every match,
     * which is quadratic (cubic for `rindex`); this indexes the text once so that each question
     * is a binary search. The answers are the same.
     */
    internal class TagIndex(text: String) {
        private val lts = IntList(16)
        private val gts = IntList(16)
        private var firstDanglingNewline = -1
        private val openAnchorStarts = IntList()
        private val openAnchorEnds = IntList()
        private val closeAnchors = IntList()

        init {
            var unclosedLt = -1
            val matcher = OPEN_ANCHOR_RE.matcher(text)
            for (i in text.indices) {
                when (text[i]) {
                    '<' -> {
                        lts.add(i)
                        if (unclosedLt < 0) unclosedLt = i
                        if (i + 3 < text.length && text.regionMatches(i + 1, "/a>", 0, 3, ignoreCase = true) && asciiOnly(text, i + 1, 3)) {
                            closeAnchors.add(i)
                        }
                        val afterPrevious = openAnchorEnds.size == 0 || openAnchorEnds.last() <= i
                        if (afterPrevious) {
                            matcher.region(i, text.length)
                            if (matcher.lookingAt()) {
                                openAnchorStarts.add(i)
                                openAnchorEnds.add(matcher.end())
                            }
                        }
                    }
                    '>' -> {
                        gts.add(i)
                        unclosedLt = -1
                    }
                    '\n' -> if (firstDanglingNewline < 0 && unclosedLt >= 0 && unclosedLt + 2 <= i) firstDanglingNewline = i
                }
            }
        }

        private fun asciiOnly(text: String, from: Int, count: Int): Boolean {
            for (j in from until from + count) if (text[j].code >= 128) return false
            return true
        }

        /** `auto_linked?(text[..start], text[end..])`: inside a tag, or inside an unclosed `<a>`. */
        fun autoLinked(start: Int, end: Int): Boolean = (openTagAtLineEnd(start) && closesTag(end)) || insideAnchor(start)

        /** `left =~ /<[^>]+$/`. */
        private fun openTagAtLineEnd(start: Int): Boolean {
            if (firstDanglingNewline in 0 until start) return true
            val gtCount = partitionPoint(gts) { it < start }
            val lastGt = if (gtCount > 0) gts[gtCount - 1] else -1
            val firstLtAfterIndex = partitionPoint(lts) { lastGt >= 0 && it <= lastGt }
            if (firstLtAfterIndex >= lts.size) return false
            return lts[firstLtAfterIndex] + 2 <= start
        }

        /** `right =~ /^[^>]*>/`, which matches whenever `right` has a `>` at all. */
        private fun closesTag(end: Int): Boolean = gts.size > 0 && gts.last() >= end

        /** `(i = left.rindex(/<a\b.*?>/i)) && left[i..] !~ /<\/a>/i`: the last `<a ...>` wholly in `left` isn't closed before `left` ends. */
        private fun insideAnchor(start: Int): Boolean {
            val before = partitionPoint(openAnchorEnds) { it <= start }
            if (before == 0) return false
            val anchorEnd = openAnchorEnds[before - 1]
            val closeIndex = partitionPoint(closeAnchors) { it < anchorEnd }
            if (closeIndex >= closeAnchors.size) return true
            return closeAnchors[closeIndex] + 4 > start
        }

        /** Rust's `partition_point`: the first index whose element fails [pred] (which must hold for a prefix). */
        private inline fun partitionPoint(list: IntList, pred: (Int) -> Boolean): Int {
            var lo = 0
            var hi = list.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (pred(list[mid])) lo = mid + 1 else hi = mid
            }
            return lo
        }
    }

    /**
     * How many of each bracket a URL has, kept up to date as trailing punctuation is stripped
     * (rather than recounted for each character stripped, as rails_autolink does).
     */
    private class BracketCounts(s: String) {
        private val counts = IntArray(6)

        init {
            for (c in s) adjust(c, 1)
        }

        fun count(bracket: Char): Int = BRACKETS.indexOf(bracket).let { if (it < 0) 0 else counts[it] }

        fun remove(c: Char) = adjust(c, -1)

        private fun adjust(c: Char, delta: Int) {
            val i = BRACKETS.indexOf(c)
            if (i >= 0) counts[i] += delta
        }

        companion object {
            const val BRACKETS = "[](){}"
        }
    }

    /** Brackets whose closing half may end a URL when the URL opened it. */
    private fun openingBracket(closing: Char): Char? = when (closing) {
        ']' -> '['
        ')' -> '('
        '}' -> '{'
        else -> null
    }

    /**
     * `auto_link(html, html: { target: "_blank" }, sanitize_options: { tags:, attributes: })`.
     * Throws [RichTextError.Parse] when the sanitizer's parse exceeds Gumbo's limits.
     */
    fun autoLink(text: String, sanitizeOptions: SafeList): String {
        if (RubyText.isBlank(text)) return ""
        val sanitized = Sanitizer.sanitizeWithEscapedAttributeBrackets(text, sanitizeOptions)
        return autoLinkEmailAddresses(autoLinkUrls(sanitized))
    }

    private fun autoLinkUrls(text: String): String {
        val out = StringBuilder(text.length + 64)
        var last = 0
        val tags = TagIndex(text)
        for (m in AUTO_LINK_RE.findAll(text)) {
            val start = m.range.first
            val end = m.range.last + 1
            out.append(text, last, start)
            last = end
            val scheme = m.groups[1]
            var href = m.value
            if (tags.autoLinked(start, end)) {
                out.append(href)
                continue
            }
            val punctuation = StringBuilder()
            val brackets = BracketCounts(href)
            while (href.isNotEmpty()) {
                val cp = href.codePointBefore(href.length)
                if (RubyText.isWordChar(cp) || cp == '/'.code || cp == '-'.code || cp == '='.code || cp == ';'.code) break
                val width = Character.charCount(cp)
                val removed = href.substring(href.length - width)
                href = href.substring(0, href.length - width)
                punctuation.insert(0, removed)
                if (width == 1) {
                    val c = removed[0]
                    brackets.remove(c)
                    val opening = openingBracket(c)
                    if (opening != null && brackets.count(opening) > brackets.count(c)) {
                        href += removed
                        punctuation.delete(0, 1)
                        break
                    }
                }
            }
            var trailingGt = ""
            if (href.endsWith("&gt;")) {
                href = href.substring(0, href.length - 4)
                trailingGt = "&gt;"
            }
            val linkText = Sanitizer.sanitize(href, SafeList.defaults)
            if (scheme == null) href = "http://$href"
            val sanitizedHref = Sanitizer.sanitize(href, SafeList.defaults)
            out.append("<a target=\"_blank\" href=\"").append(sanitizedHref.replace("\"", "&quot;")).append("\">").append(linkText).append("</a>")
            out.append(Erb.htmlEscape(punctuation.toString()))
            out.append(trailingGt)
        }
        out.append(text, last, text.length)
        return out.toString()
    }

    private fun autoLinkEmailAddresses(text: String): String {
        if (text.indexOf('@') < 0) return text
        val out = StringBuilder(text.length + 64)
        var copied = 0
        var position = 0
        val tags = TagIndex(text)
        val matcher = AUTO_EMAIL_RE.matcher(text)
        while (position < text.length) {
            val precededByLocalChar = position > 0 && isEmailLocalChar(text[position - 1]) && !Character.isLowSurrogate(text[position - 1])
            var found = false
            if (!precededByLocalChar) {
                matcher.region(position, text.length)
                found = matcher.lookingAt()
            }
            if (!found) {
                position += if (Character.isHighSurrogate(text[position]) && position + 1 < text.length) 2 else 1
                continue
            }
            val start = matcher.start()
            val end = matcher.end()
            val email = text.substring(start, end)
            out.append(text, copied, start)
            if (tags.autoLinked(start, end)) {
                out.append(email)
            } else {
                val sanitized = Sanitizer.sanitize(email, SafeList.defaults)
                val display = if (sanitized == email) Erb.htmlEscape(email) else sanitized
                val href = "mailto:" + urlEncode(sanitized).replace("%40", "@")
                out.append("<a target=\"_blank\" href=\"").append(Erb.htmlEscape(href)).append("\">").append(display).append("</a>")
            }
            copied = end
            position = maxOf(end, position + 1)
        }
        out.append(text, copied, text.length)
        return out.toString()
    }
}
