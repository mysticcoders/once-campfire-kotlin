package campfire.richtext

/** The namespace URIs an element or attribute name can carry, as html5ever's `ns!` names them. */
object Ns {
    const val NONE = ""
    const val HTML = "http://www.w3.org/1999/xhtml"
    const val SVG = "http://www.w3.org/2000/svg"
    const val MATHML = "http://www.w3.org/1998/Math/MathML"
    const val XLINK = "http://www.w3.org/1999/xlink"
    const val XML = "http://www.w3.org/XML/1998/namespace"
    const val XMLNS = "http://www.w3.org/2000/xmlns/"
}

/**
 * An element or attribute name: html5ever's `QualName` (`once-campfire-rust/crates/richtext/src/dom.rs`).
 * Foreign attributes such as `xlink:href` keep their prefix, which Nokogiri reports and serializes.
 */
class QualName(val prefix: String?, val ns: String, val local: String) {
    override fun equals(other: Any?): Boolean =
        other is QualName && other.local == local && other.ns == ns && other.prefix == prefix

    override fun hashCode(): Int = local.hashCode() * 31 + ns.hashCode()

    override fun toString(): String = if (prefix == null) local else "$prefix:$local"

    companion object {
        /** An HTML element's name. */
        fun html(local: String): QualName = QualName(null, Ns.HTML, local)

        /** An attribute name without a namespace, as Nokogiri's `node[name] = value` creates. */
        fun attribute(local: String): QualName = QualName(null, Ns.NONE, local)
    }
}

/** One attribute of an element (`dom.rs`, `Attr`). */
class Attr(val name: QualName, var value: String) {
    /** The attribute's name as Nokogiri reports and serializes it ("xlink:href", "href"). */
    fun qualifiedName(): String = name.toString()

    /** Whether [qualifiedName] equals [qname], without building the qualified name. */
    fun hasName(qname: String): Boolean {
        val prefix = name.prefix ?: return qname == name.local
        return qname.length == prefix.length + 1 + name.local.length &&
            qname.startsWith(prefix) && qname[prefix.length] == ':' && qname.endsWith(name.local)
    }

    /** A copy that can be changed independently. */
    fun copy(): Attr = Attr(name, value)
}

/** What a node is (`dom.rs`, `NodeData`). */
sealed class NodeData {
    /** The document a parse builds its tree under. */
    data object Document : NodeData()

    /** A document fragment (`Nokogiri::XML::DocumentFragment`). */
    data object Fragment : NodeData()

    /** An element with its attributes, in source order. */
    class Element(val name: QualName, val attrs: MutableList<Attr>) : NodeData()

    /**
     * A text node. Adjacent text merges while parsing, a token at a time, so appends go to a
     * builder that [text] turns back into a string when it's read.
     */
    class Text(initial: String) : NodeData() {
        private var value: String = initial
        private var builder: StringBuilder? = null

        /** The node's text. */
        var text: String
            get() {
                val b = builder
                if (b != null) {
                    value = b.toString()
                    builder = null
                }
                return value
            }
            set(v) {
                value = v
                builder = null
            }

        /** Appends [more] in amortized constant time. */
        fun append(more: String) {
            val b = builder ?: StringBuilder(value).also { builder = it }
            b.append(more)
        }
    }

    /** A comment (and a processing instruction, which the HTML parser turns into one). */
    class Comment(val text: String) : NodeData()

    /** A doctype, which only a document can hold. */
    class Doctype(val name: String) : NodeData()
}

/** A growable list of node ids, so a node's children aren't boxed. */
class IntList(capacity: Int = 4) {
    private var items = IntArray(capacity)

    /** The number of ids in the list. */
    var size: Int = 0
        private set

    /** The id at [index]. */
    operator fun get(index: Int): Int = items[index]

    /** Appends [value]. */
    fun add(value: Int) {
        if (size == items.size) items = items.copyOf(maxOf(4, size * 2))
        items[size++] = value
    }

    /** Inserts [value] at [index], shifting the ids after it. */
    fun insert(index: Int, value: Int) {
        if (size == items.size) items = items.copyOf(maxOf(4, size * 2))
        System.arraycopy(items, index, items, index + 1, size - index)
        items[index] = value
        size++
    }

    /** Removes the id at [index]. */
    fun removeAt(index: Int) {
        System.arraycopy(items, index + 1, items, index, size - index - 1)
        size--
    }

    /** The first index of [value], or -1. */
    fun indexOf(value: Int): Int {
        for (i in 0 until size) if (items[i] == value) return i
        return -1
    }

    /** The last index of [value], or -1. */
    fun lastIndexOf(value: Int): Int {
        for (i in size - 1 downTo 0) if (items[i] == value) return i
        return -1
    }

    /** Removes every id. */
    fun clear() {
        size = 0
    }

    /** The last id; the list must not be empty. */
    fun last(): Int = items[size - 1]

    /** Removes and returns the last id, or -1 when the list is empty. */
    fun removeLast(): Int = if (size == 0) -1 else items[--size]

    /** Keeps only the first [n] ids. */
    fun truncate(n: Int) {
        if (n < size) size = n
    }

    /** Replaces the id at [index]. */
    operator fun set(index: Int, value: Int) {
        items[index] = value
    }

    /** The ids as an array, so callers can change the list while iterating. */
    fun toArray(): IntArray = items.copyOf(size)

    /** A copy of the list. */
    fun copy(): IntList = IntList(0).also { it.items = items.copyOf(maxOf(size, 1)); it.size = size }
}

/** A node in a [Dom] arena (`dom.rs`, `Node`). [parent] is -1 for a detached node. */
class Node(var data: NodeData, var parent: Int, val children: IntList) {
    /** A copy whose children list (and element attributes) can change independently. */
    fun copy(): Node {
        val copiedData = when (val d = data) {
            is NodeData.Element -> NodeData.Element(d.name, d.attrs.mapTo(ArrayList(d.attrs.size)) { it.copy() })
            is NodeData.Text -> NodeData.Text(d.text)
            else -> d
        }
        return Node(copiedData, parent, children.copy())
    }
}

/**
 * Gumbo's limits, which Nokogiri raises `ArgumentError` for exceeding (`dom.rs`, `ParseError`;
 * `Nokogiri::Gumbo::DEFAULT_MAX_TREE_DEPTH`, `DEFAULT_MAX_ATTRIBUTES`).
 */
enum class ParseError(val message: String) {
    TreeDepthExceeded("Document tree depth limit exceeded"),
    TooManyAttributes("Attributes per element limit exceeded"),
}

/**
 * The context a fragment is parsed in (`dom.rs`, `Context`): Nokogiri parses `Document#fragment`
 * in a `body` context and `Node#fragment` (used by `replace` and `inner_html=`) in the node's own.
 */
class Context(val name: QualName) {
    companion object {
        /** The `body` context of `Document#fragment`. */
        val BODY = Context(QualName.html("body"))

        /** An HTML element's context. */
        fun html(local: String): Context = if (local == "body") BODY else Context(QualName.html(local))
    }
}

/**
 * A small arena DOM that behaves like the Nokogiri HTML5 (Gumbo) trees Action Text works on
 * (`once-campfire-rust/crates/richtext/src/dom.rs`). Parsing goes through [HtmlParser], which
 * implements the same WHATWG tree construction as Gumbo and html5ever; serialization reproduces
 * Nokogiri's `html_standard_serialize` (`Nokogiri::HTML5::Node#write_to`), which is what
 * `ActionText::HtmlConversion.node_to_html` and Loofah's `to_html` produce for HTML5 documents.
 */
class Dom private constructor(private val nodes: ArrayList<Node>) {
    constructor() : this(ArrayList())

    /** A deep copy of the whole arena (`Dom::clone`); ids stay the same. */
    fun copy(): Dom = Dom(nodes.mapTo(ArrayList(nodes.size)) { it.copy() })

    /** The node with [id]. */
    fun node(id: Int): Node = nodes[id]

    private fun push(data: NodeData): Int {
        nodes.add(Node(data, -1, IntList()))
        return nodes.size - 1
    }

    /** A new, empty document fragment. */
    fun newFragment(): Int = push(NodeData.Fragment)

    /** A new, detached HTML element with attributes in the given order. */
    fun createElement(local: String, attrs: List<Pair<String, String>>): Int {
        val list = attrs.mapTo(ArrayList(attrs.size)) { (k, v) -> Attr(QualName.attribute(k), v) }
        return push(NodeData.Element(QualName.html(local), list))
    }

    /** A new, detached text node. */
    fun createText(text: String): Int = push(NodeData.Text(text))

    /** The children of [id], in order. */
    fun children(id: Int): IntList = nodes[id].children

    /** The parent of [id], or -1. */
    fun parent(id: Int): Int = nodes[id].parent

    /** The element data of [id], if it is an element. */
    fun element(id: Int): NodeData.Element? = nodes[id].data as? NodeData.Element

    /** Whether [id] is an element. */
    fun isElement(id: Int): Boolean = nodes[id].data is NodeData.Element

    /** Whether [id] is a text node. */
    fun isText(id: Int): Boolean = nodes[id].data is NodeData.Text

    /** Nokogiri's `Node#name`: the local name for elements, "text", "comment" and so on otherwise. */
    fun name(id: Int): String = when (val d = nodes[id].data) {
        is NodeData.Element -> d.name.local
        is NodeData.Text -> "text"
        is NodeData.Comment -> "comment"
        NodeData.Fragment -> "#document-fragment"
        NodeData.Document -> "document"
        is NodeData.Doctype -> d.name
    }

    /** The element's local name, if [id] is an element. */
    fun localName(id: Int): String? = element(id)?.name?.local

    /** Whether [id] is an element in the HTML namespace. */
    fun isHtmlElement(id: Int): Boolean = element(id)?.name?.ns == Ns.HTML

    /** The value of the attribute whose qualified name is [name]. */
    fun attr(id: Int, name: String): String? {
        val e = element(id) ?: return null
        for (a in e.attrs) if (a.hasName(name)) return a.value
        return null
    }

    /** Whether [id] has the attribute [name]. */
    fun hasAttr(id: Int, name: String): Boolean = attr(id, name) != null

    /** Nokogiri's `node[name] = value`: updates in place, or appends a new attribute. */
    fun setAttr(id: Int, name: String, value: String) {
        val e = element(id) ?: return
        for (a in e.attrs) {
            if (a.hasName(name)) {
                a.value = value
                return
            }
        }
        e.attrs.add(Attr(QualName.attribute(name), value))
    }

    /** Removes the attribute [name], returning its value. */
    fun removeAttr(id: Int, name: String): String? {
        val e = element(id) ?: return null
        val index = e.attrs.indexOfFirst { it.hasName(name) }
        if (index < 0) return null
        return e.attrs.removeAt(index).value
    }

    /** The attributes of [id] as qualified name and value pairs. */
    fun attrs(id: Int): List<Pair<String, String>> =
        element(id)?.attrs?.map { it.qualifiedName() to it.value } ?: emptyList()

    /** Element children only (Nokogiri's `Node#elements`). */
    fun elementChildren(id: Int): List<Int> {
        val children = nodes[id].children
        val out = ArrayList<Int>(children.size)
        for (i in 0 until children.size) if (isElement(children[i])) out.add(children[i])
        return out
    }

    /** All descendants in document order, excluding [id] itself. */
    fun descendants(id: Int): IntArray {
        val out = IntList(16)
        val stack = IntList(16)
        pushChildrenReversed(id, stack)
        while (stack.size > 0) {
            val n = stack[stack.size - 1]
            stack.removeAt(stack.size - 1)
            out.add(n)
            pushChildrenReversed(n, stack)
        }
        return out.toArray()
    }

    private fun pushChildrenReversed(id: Int, stack: IntList) {
        val children = nodes[id].children
        for (i in children.size - 1 downTo 0) stack.add(children[i])
    }

    /** The ancestors of [id], nearest first. */
    fun ancestors(id: Int): List<Int> {
        val out = ArrayList<Int>()
        var current = parent(id)
        while (current >= 0) {
            out.add(current)
            current = parent(current)
        }
        return out
    }

    /** Removes [id] from its parent. */
    fun detach(id: Int) {
        val parent = nodes[id].parent
        if (parent >= 0) {
            nodes[id].parent = -1
            val siblings = nodes[parent].children
            val index = siblings.indexOf(id)
            if (index >= 0) siblings.removeAt(index)
        }
    }

    /** Moves [child] to the end of [parent]'s children. */
    fun append(parent: Int, child: Int) {
        detach(child)
        nodes[child].parent = parent
        nodes[parent].children.add(child)
    }

    /** Moves [new] to just before [reference], which must have a parent. */
    fun insertBefore(reference: Int, new: Int) {
        detach(new)
        val parent = nodes[reference].parent
        check(parent >= 0) { "reference node has a parent" }
        val index = nodes[parent].children.indexOf(reference)
        nodes[new].parent = parent
        nodes[parent].children.insert(index, new)
    }

    /** The text content of a node, as libxml2's `xmlNodeGetContent` computes it. */
    fun textContent(id: Int): String = when (val d = nodes[id].data) {
        is NodeData.Text -> d.text
        is NodeData.Comment -> d.text
        else -> {
            val out = StringBuilder()
            for (n in descendants(id)) {
                val data = nodes[n].data
                if (data is NodeData.Text) out.append(data.text)
            }
            out.toString()
        }
    }

    /** The text of a text node, or null. */
    fun text(id: Int): String? = (nodes[id].data as? NodeData.Text)?.text

    /** A detached deep copy of [id] and its descendants. */
    fun deepClone(id: Int): Int {
        val copy = push(nodes[id].copy().data)
        val children = nodes[id].children.toArray()
        for (child in children) {
            val childCopy = deepClone(child)
            nodes[childCopy].parent = copy
            nodes[copy].children.add(childCopy)
        }
        return copy
    }

    /** `Nokogiri::HTML5::Document#fragment(html)`: a new fragment parsed in a `body` context. */
    fun parseFragment(html: String): Int {
        val fragment = newFragment()
        for (node in parseNodes(html, Context.BODY)) append(fragment, node)
        return fragment
    }

    /** The context Nokogiri uses when parsing markup for a node (`Node#fragment`). */
    fun contextFor(id: Int): Context {
        val e = element(id) ?: return Context.BODY
        return Context(e.name)
    }

    /**
     * Parses [html] in [context] and returns the resulting top-level nodes, detached. Throws
     * [RichTextError.Parse] when the markup exceeds Gumbo's limits.
     */
    fun parseNodes(html: String, context: Context): IntArray {
        val parsed = HtmlParser.parse(html, context)
        val offset = nodes.size
        val top = parsed.topLevel
        for (node in parsed.nodes) {
            if (node.parent >= 0) node.parent += offset
            val children = node.children
            val shifted = IntList(children.size)
            for (i in 0 until children.size) shifted.add(children[i] + offset)
            nodes.add(Node(node.data, node.parent, shifted))
        }
        val result = IntArray(top.size) { top[it] + offset }
        for (n in result) nodes[n].parent = -1
        return result
    }

    /** Nokogiri's `node.inner_html = html` (via `children=`), parsed in the node's context. */
    fun setInnerHtml(id: Int, html: String) {
        val newChildren = parseNodes(html, contextFor(id))
        val old = nodes[id].children
        for (i in 0 until old.size) nodes[old[i]].parent = -1
        old.clear()
        for (child in newChildren) append(id, child)
    }

    /** Nokogiri's `node.replace(html)`: the markup is parsed in the context of the node's parent. */
    fun replaceWithHtml(id: Int, html: String) {
        val parent = parent(id)
        if (parent < 0) return
        val replacements = parseNodes(html, contextFor(parent))
        for (n in replacements) insertBefore(id, n)
        detach(id)
    }

    /** Replaces a node with other (detached) nodes. */
    fun replaceWithNodes(id: Int, replacements: IntArray) {
        if (parent(id) < 0) return
        for (n in replacements) insertBefore(id, n)
        detach(id)
    }

    /** `node.to_html` for an HTML5 document: the node itself (children only for a fragment). */
    fun toHtml(id: Int): String = serialize(id, brackets = false)

    /**
     * [toHtml], but with `<` and `>` escaped in attribute values as well, as the current HTML
     * serialization algorithm does. Nokogiri leaves them raw, which lets auto_link's regular
     * expressions mistake an attribute value for text.
     */
    fun toHtmlWithEscapedAttributeBrackets(id: Int): String = serialize(id, brackets = true)

    /** The serialized children of [id]. */
    fun innerHtml(id: Int): String {
        val out = StringBuilder()
        serializeChildren(id, false, out)
        return out.toString()
    }

    private fun serialize(id: Int, brackets: Boolean): String {
        val out = StringBuilder()
        when (nodes[id].data) {
            NodeData.Fragment, NodeData.Document -> serializeChildren(id, brackets, out)
            else -> serializeNode(id, brackets, out)
        }
        return out.toString()
    }

    private fun serializeChildren(id: Int, brackets: Boolean, out: StringBuilder) {
        val children = nodes[id].children
        for (i in 0 until children.size) serializeNode(children[i], brackets, out)
    }

    /**
     * Serializes one node the way Nokogiri's `html_standard_serialize` does: attribute values
     * escape `&`, U+00A0 and `"`; text escapes `&`, U+00A0, `<` and `>` except inside raw text
     * elements; void HTML elements have no end tag.
     */
    private fun serializeNode(id: Int, brackets: Boolean, out: StringBuilder) {
        when (val d = nodes[id].data) {
            is NodeData.Element -> {
                out.append('<')
                appendTagName(d.name, out)
                for (attr in d.attrs) {
                    out.append(' ')
                    if (attr.name.prefix != null) out.append(attr.name.prefix).append(':')
                    out.append(attr.name.local)
                    out.append("=\"")
                    escapeAttribute(attr.value, brackets, out)
                    out.append('"')
                }
                out.append('>')
                if (d.name.ns == Ns.HTML && isVoidElement(d.name.local)) return
                serializeChildren(id, brackets, out)
                out.append("</")
                appendTagName(d.name, out)
                out.append('>')
            }
            is NodeData.Text -> {
                val parent = nodes[id].parent
                val parentElement = if (parent >= 0) element(parent) else null
                if (parentElement != null && parentElement.name.ns == Ns.HTML && isRawTextElement(parentElement.name.local)) {
                    out.append(d.text)
                } else {
                    escapeText(d.text, out)
                }
            }
            is NodeData.Comment -> out.append("<!--").append(d.text).append("-->")
            is NodeData.Doctype -> out.append("<!DOCTYPE ").append(d.name).append('>')
            NodeData.Fragment, NodeData.Document -> serializeChildren(id, brackets, out)
        }
    }

    companion object {
        /** Gumbo's `DEFAULT_MAX_TREE_DEPTH`. */
        const val MAX_TREE_DEPTH = 400

        /** Gumbo's `DEFAULT_MAX_ATTRIBUTES`. */
        const val MAX_ATTRIBUTES = 400

        private val VOID_ELEMENTS = hashSetOf(
            "area", "base", "basefont", "bgsound", "br", "col", "embed", "frame", "hr", "img", "input", "keygen",
            "link", "meta", "param", "source", "track", "wbr",
        )

        private val RAW_TEXT_ELEMENTS =
            hashSetOf("style", "script", "xmp", "iframe", "noembed", "noframes", "plaintext", "noscript")

        /** Whether an HTML element named [local] is void (`dom.rs`, `is_void_element`). */
        fun isVoidElement(local: String): Boolean = local in VOID_ELEMENTS

        private fun isRawTextElement(local: String): Boolean = local in RAW_TEXT_ELEMENTS

        /** HTML, SVG and MathML elements serialize by local name; others with their prefix. */
        private fun appendTagName(name: QualName, out: StringBuilder) {
            if (name.ns == Ns.HTML || name.ns == Ns.SVG || name.ns == Ns.MATHML || name.prefix == null) {
                out.append(name.local)
            } else {
                out.append(name.prefix).append(':').append(name.local)
            }
        }

        private fun escapeAttribute(value: String, brackets: Boolean, out: StringBuilder) {
            var last = 0
            for (i in value.indices) {
                val replacement = when (value[i]) {
                    '&' -> "&amp;"
                    ' ' -> "&nbsp;"
                    '"' -> "&quot;"
                    '<' -> if (brackets) "&lt;" else null
                    '>' -> if (brackets) "&gt;" else null
                    else -> null
                } ?: continue
                out.append(value, last, i).append(replacement)
                last = i + 1
            }
            out.append(value, last, value.length)
        }

        private fun escapeText(value: String, out: StringBuilder) {
            var last = 0
            for (i in value.indices) {
                val replacement = when (value[i]) {
                    '&' -> "&amp;"
                    ' ' -> "&nbsp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    else -> continue
                }
                out.append(value, last, i).append(replacement)
                last = i + 1
            }
            out.append(value, last, value.length)
        }
    }
}
