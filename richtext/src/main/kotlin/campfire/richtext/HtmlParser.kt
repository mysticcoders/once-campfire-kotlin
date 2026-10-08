package campfire.richtext

import campfire.richtext.html.BufferQueue
import campfire.richtext.html.Tag
import campfire.richtext.html.TokenSink
import campfire.richtext.html.Tokenizer
import campfire.richtext.html.TreeBuilder
import campfire.richtext.html.TreeSink

/** A parsed fragment: the parse's nodes (local ids) and the ids of its top-level nodes. */
class ParsedTree internal constructor(val nodes: ArrayList<Node>, val topLevel: IntList)

/**
 * `html5ever::driver::parse_fragment` as `once-campfire-rust/crates/richtext/src/dom.rs` drives it
 * (`parse_with_html5ever`), over the port of html5ever 0.35 in [campfire.richtext.html]: a
 * fragment parse in a context element, without scripting, held to Gumbo's limits as it goes, the
 * way Gumbo is, rather than checked once the tree is built (html5ever's scope checks walk the
 * stack of open elements, so building a deeply nested tree in full takes quadratic time).
 */
object HtmlParser {
    /**
     * Parses [html] in [context]. Throws [RichTextError.Parse] past Gumbo's tree depth limit
     * (Nokogiri's `ArgumentError: Document tree depth limit exceeded`) or its attributes-per-element
     * limit.
     */
    fun parse(html: String, context: Context): ParsedTree {
        val sink = TreeSink()
        val contextElement = sink.createElement(context.name, emptyList())
        val treeBuilder = TreeBuilder(sink, contextElement)
        val (state, kind) = treeBuilder.tokenizerStateForContextElem()
        val input = BufferQueue()
        val limit = DepthLimit(treeBuilder, input)
        val tokenizer = Tokenizer(limit, state, kind, Dom.MAX_ATTRIBUTES)
        input.pushBack(if (html.startsWith('﻿')) html.substring(1) else html)
        try {
            tokenizer.feed(input)
            tokenizer.end()
        } catch (_: DepthExceeded) {
            throw RichTextError.Parse(ParseError.TreeDepthExceeded)
        }
        if (tokenizer.tooManyAttributes) throw RichTextError.Parse(ParseError.TooManyAttributes)
        val root = sink.nodes[0].children.let { children ->
            (0 until children.size).map { children[it] }.firstOrNull { id ->
                (sink.nodes[id].data as? NodeData.Element)?.name?.let { it.ns == Ns.HTML && it.local == "html" } == true
            } ?: 0
        }
        return ParsedTree(sink.nodes, sink.nodes[root].children.copy())
    }

    /** Ends a parse that went past the tree depth limit, without reading the rest of the input. */
    private object DepthExceeded : RuntimeException(null, null, false, false) {
        private fun readResolve(): Any = DepthExceeded
    }

    /**
     * Gumbo's tree depth limit (`dom.rs`, `DepthLimit`). Before it reads each token, Gumbo stops,
     * as if the input had ended there, once the stack of open elements holds more than
     * `max_tree_depth` elements, and Nokogiri adds one to the limit for a fragment's `html`
     * element. So the stack is checked as soon as a token has been handled, before the tokenizer
     * reads on (the next token can be all the rest of the input). Gumbo doesn't check after the
     * end of the input, though, when text left pending in a table can reopen formatting elements
     * past the limit. Since the result is an error either way, the parse stops there.
     */
    private class DepthLimit(private val treeBuilder: TreeBuilder, private val input: BufferQueue) : TokenSink {
        private fun check() {
            if (treeBuilder.openElementsLen() > MAX_OPEN_ELEMENTS) {
                input.clear()
                throw DepthExceeded
            }
        }

        override fun characters(text: String) {
            treeBuilder.characters(text)
            check()
        }

        override fun nullCharacter() {
            treeBuilder.nullCharacter()
            check()
        }

        override fun tag(tag: Tag): Int {
            val result = treeBuilder.tag(tag)
            check()
            return result
        }

        override fun comment(text: String) {
            treeBuilder.comment(text)
            check()
        }

        override fun doctype() {
            treeBuilder.doctype()
            check()
        }

        override fun eof() = treeBuilder.eof()

        override fun parseError() {
            treeBuilder.parseError()
            check()
        }

        override fun end() = treeBuilder.end()

        override fun adjustedCurrentNodePresentButNotInHtmlNamespace(): Boolean =
            treeBuilder.adjustedCurrentNodePresentButNotInHtmlNamespace()

        companion object {
            const val MAX_OPEN_ELEMENTS = Dom.MAX_TREE_DEPTH + 1
        }
    }
}
