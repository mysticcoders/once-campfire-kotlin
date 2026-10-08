package campfire.richtext

/** `ActionText::PlainTextConversion` (`once-campfire-rust/crates/richtext/src/plain_text.rs`). */
object PlainText {
    /** `PlainTextConversion.node_to_plain_text`: a bottom-up reduction keyed on each node's name. */
    fun nodeToPlainText(dom: Dom, node: Int): String = RubyText.chompNewlines(plainTextFor(dom, node))

    private fun childValues(dom: Dom, node: Int): String {
        val children = dom.children(node)
        if (children.size == 1) return plainTextFor(dom, children[0])
        val out = StringBuilder()
        for (child in children.toArray()) out.append(plainTextFor(dom, child))
        return out.toString()
    }

    private fun plainTextFor(dom: Dom, node: Int): String = when (dom.name(node)) {
        "script", "style", "unsupported" -> ""
        "h1", "p" -> plainTextForBlock(childValues(dom, node))
        "ul", "ol" -> {
            val text = plainTextForBlock(childValues(dom, node))
            if (listDepth(dom, node) > 0) "\n$text" else text
        }
        "br" -> "\n"
        "text" -> RubyText.chompNewlines(dom.textContent(node))
        "div" -> RubyText.chompNewlines(childValues(dom, node)) + "\n"
        "figcaption" -> "[" + RubyText.chompNewlines(childValues(dom, node)) + "]"
        "blockquote" -> blockquote(plainTextForBlock(childValues(dom, node)))
        "li" -> {
            val bullet = bulletForLi(dom, node)
            val text = RubyText.chompNewlines(childValues(dom, node))
            val depth = listDepth(dom, node)
            val indentation = if (depth > 1) "  ".repeat(depth - 1) else ""
            "$indentation$bullet $text\n"
        }
        else -> childValues(dom, node)
    }

    /** `text.insert(text.rindex(/\S/) + 1, "”")`, then `text.index(/\S/)` for "“"; blank is "“”". */
    private fun blockquote(block: String): String {
        if (RubyText.isBlank(block)) return "“”"
        fun isSpace(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\u000b' || c == '\u000c' || c == '\r'
        val text = StringBuilder(block)
        val last = text.indexOfLast { !isSpace(it) }
        if (last >= 0) text.insert(last + 1, '”')
        val first = text.indexOfFirst { !isSpace(it) }
        if (first >= 0) text.insert(first, '“')
        return text.toString()
    }

    private fun plainTextForBlock(childValues: String): String = RubyText.chompNewlines(childValues) + "\n\n"

    private fun isList(name: String): Boolean = name == "ul" || name == "ol"

    private fun listDepth(dom: Dom, node: Int): Int = dom.ancestors(node).count { isList(dom.name(it)) }

    private fun bulletForLi(dom: Dom, node: Int): String {
        val list = dom.ancestors(node).map { dom.name(it) }.firstOrNull { isList(it) }
        if (list == "ol") {
            val parent = dom.parent(node)
            val index = if (parent >= 0) dom.elementChildren(parent).indexOf(node).coerceAtLeast(0) else 0
            return "${index + 1}."
        }
        return "•"
    }
}
