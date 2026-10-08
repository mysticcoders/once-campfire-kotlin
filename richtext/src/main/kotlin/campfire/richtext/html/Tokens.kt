package campfire.richtext.html

import campfire.richtext.QualName

/** A tag's attribute as the tokenizer and tree builder carry it (markup5ever's `Attribute`). */
internal class TokenAttr(var name: QualName, val value: String)

/** A start or end tag token (html5ever's `tokenizer::Tag`). */
internal class Tag(
    val isStart: Boolean,
    val name: String,
    val selfClosing: Boolean,
    val attrs: MutableList<TokenAttr>,
) {
    /** A copy with another name and the same flags and attributes. */
    fun renamed(newName: String): Tag = Tag(isStart, newName, selfClosing, attrs)

    /** The attributes in a canonical order, computed once, since the Noah's Ark check compares a tag with many others. */
    private val sortedAttrs: List<TokenAttr> by lazy(LazyThreadSafetyMode.NONE) { attrs.sortedWith(ATTR_ORDER) }

    /**
     * `Tag::equiv_modulo_attr_order`: the same kind, name and attributes, ignoring their order
     * and the self-closing flag.
     */
    fun equivModuloAttrOrder(other: Tag): Boolean {
        if (isStart != other.isStart || name != other.name || attrs.size != other.attrs.size) return false
        if (attrs.isEmpty() || this === other) return true
        val mine = sortedAttrs
        val theirs = other.sortedAttrs
        for (i in mine.indices) {
            val a = mine[i]
            val b = theirs[i]
            if (a !== b && (a.value != b.value || a.name != b.name)) return false
        }
        return true
    }

    private companion object {
        val ATTR_ORDER = Comparator<TokenAttr> { a, b ->
            var c = (a.name.prefix ?: "").compareTo(b.name.prefix ?: "")
            if (c == 0) c = a.name.ns.compareTo(b.name.ns)
            if (c == 0) c = a.name.local.compareTo(b.name.local)
            if (c == 0) c = a.value.compareTo(b.value)
            c
        }
    }
}

/** What the tree builder tells the tokenizer after a tag (html5ever's `TokenSinkResult`). */
internal object SinkResult {
    const val CONTINUE = -1
    const val PLAINTEXT = -2
    const val SCRIPT = -3
}

/** The tokenizer's raw text kinds (`states::RawKind`), which [SinkResult] also uses. */
internal object RawKind {
    const val RCDATA = 0
    const val RAWTEXT = 1
    const val SCRIPT_DATA = 2
    const val SCRIPT_DATA_ESCAPED = 3
    const val SCRIPT_DATA_DOUBLE_ESCAPED = 4
}

/** Where the tokenizer sends tokens (html5ever's `TokenSink`, one method per kind of token). */
internal interface TokenSink {
    /** `CharacterTokens`: a run of characters, never containing NUL. */
    fun characters(text: String)

    /** `NullCharacterToken`. */
    fun nullCharacter()

    /** `TagToken`; returns a [SinkResult] constant or a [RawKind] to switch the tokenizer to. */
    fun tag(tag: Tag): Int

    /** `CommentToken`. */
    fun comment(text: String)

    /** `DoctypeToken`, whose details a fragment parse never uses. */
    fun doctype()

    /** `EOFToken`. */
    fun eof()

    /** `ParseError`, which still counts as a token (it resets the tree builder's pending LF skip). */
    fun parseError()

    /** The end of tokenization. */
    fun end()

    /** Whether CDATA sections are allowed: the adjusted current node isn't in the HTML namespace. */
    fun adjustedCurrentNodePresentButNotInHtmlNamespace(): Boolean
}
