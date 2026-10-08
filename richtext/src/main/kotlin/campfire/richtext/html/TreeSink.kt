package campfire.richtext.html

import campfire.richtext.Attr
import campfire.richtext.IntList
import campfire.richtext.Node
import campfire.richtext.NodeData
import campfire.richtext.QualName

/**
 * The tree the parser builds (`once-campfire-rust/crates/richtext/src/dom.rs`, `Sink`): an arena
 * of [Node]s whose ids are the tree builder's handles, with node 0 the document. It behaves as
 * Nokogiri's tree does: adjacent text merges, and a template's contents are its ordinary
 * children, as libxml2 has no template contents.
 */
internal class TreeSink {
    val nodes = ArrayList<Node>(64)

    init {
        nodes.add(Node(NodeData.Document, -1, IntList()))
    }

    private fun newNode(data: NodeData): Int {
        nodes.add(Node(data, -1, IntList()))
        return nodes.size - 1
    }

    /** The name of element [id]. */
    fun elemName(id: Int): QualName = (nodes[id].data as NodeData.Element).name

    /** A new element; the tokenizer has already dropped duplicate attributes. */
    fun createElement(name: QualName, attrs: List<TokenAttr>): Int {
        val list = ArrayList<Attr>(attrs.size)
        for (a in attrs) list.add(Attr(a.name, a.value))
        return newNode(NodeData.Element(name, list))
    }

    /** A new comment. */
    fun createComment(text: String): Int = newNode(NodeData.Comment(text))

    /** Whether [id] is the `html` element the fragment's nodes go under (a child of the document). */
    fun isFragmentRoot(id: Int): Boolean = nodes[id].parent == 0

    private fun detach(id: Int) {
        val parent = nodes[id].parent
        if (parent >= 0) {
            nodes[id].parent = -1
            val siblings = nodes[parent].children
            val index = siblings.lastIndexOf(id)
            if (index >= 0) siblings.removeAt(index)
        }
    }

    private fun insertNodeAt(parent: Int, index: Int, node: Int) {
        detach(node)
        nodes[node].parent = parent
        nodes[parent].children.insert(index, node)
    }

    /** Inserts text at [index], merging it into the text node before it, as the DOM does. */
    private fun insertTextAt(parent: Int, index: Int, text: String) {
        val children = nodes[parent].children
        if (index > 0) {
            val previous = nodes[children[index - 1]].data
            if (previous is NodeData.Text) {
                previous.append(text)
                return
            }
        }
        val node = newNode(NodeData.Text(text))
        nodes[node].parent = parent
        children.insert(index, node)
    }

    /** Appends a node to [parent]. */
    fun appendNode(parent: Int, node: Int) = insertNodeAt(parent, nodes[parent].children.size, node)

    /** Appends text to [parent]. */
    fun appendText(parent: Int, text: String) = insertTextAt(parent, nodes[parent].children.size, text)

    /**
     * Inserts before [sibling], whose position is found from the end: foster parenting inserts
     * before an open table, which is its parent's last child, so a body of thousands of misplaced
     * elements in a table stays linear.
     */
    fun appendNodeBeforeSibling(sibling: Int, node: Int) {
        val parent = nodes[sibling].parent
        check(parent >= 0) { "sibling has a parent" }
        insertNodeAt(parent, nodes[parent].children.lastIndexOf(sibling), node)
    }

    /** Inserts text before [sibling] (see [appendNodeBeforeSibling]). */
    fun appendTextBeforeSibling(sibling: Int, text: String) {
        val parent = nodes[sibling].parent
        check(parent >= 0) { "sibling has a parent" }
        insertTextAt(parent, nodes[parent].children.lastIndexOf(sibling), text)
    }

    /** `append_based_on_parent_node` for a node: before [element] if it has a parent, else into [prevElement]. */
    fun appendNodeBasedOnParentNode(element: Int, prevElement: Int, node: Int) {
        if (nodes[element].parent >= 0) appendNodeBeforeSibling(element, node) else appendNode(prevElement, node)
    }

    /** `append_based_on_parent_node` for text. */
    fun appendTextBasedOnParentNode(element: Int, prevElement: Int, text: String) {
        if (nodes[element].parent >= 0) appendTextBeforeSibling(element, text) else appendText(prevElement, text)
    }

    /**
     * Adds the attributes [target] doesn't have yet. An `<html>` tag in the body gives its
     * attributes to the fragment's root `<html>` element, which nothing reads, so they are
     * skipped there: merging them would compare each with all the root had collected, and a body
     * of `<html>` tags would take quadratic time.
     */
    fun addAttrsIfMissing(target: Int, attrs: List<TokenAttr>) {
        if (isFragmentRoot(target)) return
        val element = nodes[target].data as? NodeData.Element ?: return
        for (a in attrs) {
            if (element.attrs.none { it.name == a.name }) element.attrs.add(Attr(a.name, a.value))
        }
    }

    /** Removes [target] from its parent. */
    fun removeFromParent(target: Int) = detach(target)

    /** Moves all of [node]'s children to the end of [newParent]. */
    fun reparentChildren(node: Int, newParent: Int) {
        val children = nodes[node].children.toArray()
        nodes[node].children.clear()
        for (child in children) {
            nodes[child].parent = -1
            appendNode(newParent, child)
        }
    }
}
