package campfire.richtext.html

import campfire.richtext.IntList
import campfire.richtext.Ns
import campfire.richtext.QualName

/** A token as the tree builder sees it (`tree_builder/types.rs`, `Token`). */
internal sealed class TbToken {
    class TagTok(val tag: Tag) : TbToken()
    class CommentTok(val text: String) : TbToken()
    class Chars(val split: Int, val text: String) : TbToken()
    data object NullChar : TbToken()
    data object Eof : TbToken()
}

/** What a tree construction step asks for next (`tree_builder/types.rs`, `ProcessResult`). */
internal sealed class Pr {
    data object Done : Pr()
    data object DoneAckSelfClosing : Pr()
    class SplitWhitespace(val text: String) : Pr()
    class Reprocess(val mode: Int, val token: TbToken) : Pr()
    class ReprocessForeign(val token: TbToken) : Pr()
    data object Script : Pr()
    data object ToPlaintext : Pr()
    class ToRawData(val kind: Int) : Pr()
}

/** An entry in the list of active formatting elements; [node] is -1 for a marker. */
internal class FormatEntry(val node: Int, val tag: Tag?) {
    val isMarker: Boolean get() = node < 0

    companion object {
        val MARKER = FormatEntry(-1, null)
    }
}

/**
 * html5ever 0.35's tree builder (`once-campfire-rust/crates/richtext/vendor/html5ever/src/tree_builder/mod.rs`
 * and `rules.rs`), for the fragment parsing algorithm with scripting disabled and no quirks, as
 * `dom.rs` configures it. It includes the vendored copy's backported fix (reconstruct the active
 * formatting elements before inserting `<svg>` or `<math>` in body) and its `open_elements_len`
 * accessor, which [HtmlParser] holds to Gumbo's tree depth limit. The rules are a port of the
 * `match_token!` arms, in their order; parse errors are not reported.
 */
internal class TreeBuilder(private val sink: TreeSink, private val contextElem: Int) {
    private var mode = INITIAL
    private var origMode = -1
    private val templateModes = IntList()
    private val pendingTableText = ArrayList<TbToken.Chars>()
    private val openElems = IntList(32)
    private val activeFormatting = ArrayList<FormatEntry>()
    private var headElem = -1
    private var formElem = -1
    private var framesetOk = true
    private var ignoreLf = false
    private var fosterParenting = false
    private var insertTarget = -1
    private var insertPrev = -1

    init {
        if (sink.elemName(contextElem).let { it.ns == Ns.HTML && it.local == "template" }) templateModes.add(IN_TEMPLATE)
        createRoot(emptyList())
        mode = resetInsertionMode()
    }

    /** How many elements are on the stack of open elements (the vendored `open_elements_len`). */
    fun openElementsLen(): Int = openElems.size

    /** `tokenizer_state_for_context_elem(false)`: the tokenizer state a fragment starts in. */
    fun tokenizerStateForContextElem(): Pair<Int, Int> {
        val name = sink.elemName(contextElem)
        if (name.ns != Ns.HTML) return Tokenizer.DATA to 0
        return when (name.local) {
            "title", "textarea" -> Tokenizer.RAW_DATA to RawKind.RCDATA
            "style", "xmp", "iframe", "noembed", "noframes" -> Tokenizer.RAW_DATA to RawKind.RAWTEXT
            "script" -> Tokenizer.RAW_DATA to RawKind.SCRIPT_DATA
            "plaintext" -> Tokenizer.PLAINTEXT to 0
            else -> Tokenizer.DATA to 0
        }
    }

    /** The `ignore_lf` take every token performs in `process_token`, whatever the token. */
    private fun takeIgnoreLf(): Boolean {
        val value = ignoreLf
        ignoreLf = false
        return value
    }

    /** `process_token` for a run of characters. */
    fun characters(text: String) {
        val skip = takeIgnoreLf()
        val chars = if (skip && text.startsWith('\n')) text.substring(1) else text
        if (chars.isEmpty()) return
        processToCompletion(TbToken.Chars(NOT_SPLIT, chars))
    }

    /** `process_token` for a NUL character. */
    fun nullCharacter() {
        takeIgnoreLf()
        processToCompletion(TbToken.NullChar)
    }

    /** `process_token` for a tag; returns a [SinkResult] constant or a [RawKind]. */
    fun tag(tag: Tag): Int {
        takeIgnoreLf()
        return processToCompletion(TbToken.TagTok(tag))
    }

    /** `process_token` for a comment. */
    fun comment(text: String) {
        takeIgnoreLf()
        processToCompletion(TbToken.CommentTok(text))
    }

    /** `process_token` for a doctype, which a fragment's insertion modes all ignore. */
    fun doctype() {
        takeIgnoreLf()
    }

    /** `process_token` for a parse error, which only takes the pending LF skip. */
    fun parseError() {
        takeIgnoreLf()
    }

    /** `process_token` for the end of the input. */
    fun eof() {
        takeIgnoreLf()
        processToCompletion(TbToken.Eof)
    }

    /** `TokenSink::end`. */
    fun end() {
        openElems.clear()
    }

    /** `adjusted_current_node_present_but_not_in_html_namespace`. */
    fun adjustedCurrentNodePresentButNotInHtmlNamespace(): Boolean =
        openElems.size > 0 && sink.elemName(adjustedCurrentNode()).ns != Ns.HTML

    private fun processToCompletion(first: TbToken): Int {
        var token = first
        var moreTokens: ArrayDeque<TbToken>? = null
        while (true) {
            val result = if (isForeign(token)) stepForeign(token) else step(mode, token)
            when (result) {
                Pr.Done, Pr.DoneAckSelfClosing -> {
                    token = moreTokens?.removeFirstOrNull() ?: return SinkResult.CONTINUE
                }
                is Pr.Reprocess -> {
                    mode = result.mode
                    token = result.token
                }
                is Pr.ReprocessForeign -> token = result.token
                is Pr.SplitWhitespace -> {
                    val buf = result.text
                    if (buf.isEmpty()) return SinkResult.CONTINUE
                    val isWs = isAsciiWhitespace(buf[0])
                    var end = 1
                    while (end < buf.length && isAsciiWhitespace(buf[end]) == isWs) end++
                    token = TbToken.Chars(if (isWs) WHITESPACE else NOT_WHITESPACE, buf.substring(0, end))
                    if (end < buf.length) {
                        val queue = moreTokens ?: ArrayDeque<TbToken>().also { moreTokens = it }
                        queue.addLast(TbToken.Chars(NOT_SPLIT, buf.substring(end)))
                    }
                }
                Pr.Script -> return SinkResult.SCRIPT
                Pr.ToPlaintext -> return SinkResult.PLAINTEXT
                is Pr.ToRawData -> return result.kind
            }
        }
    }

    private fun isFragment(): Boolean = contextElem >= 0

    private fun appropriatePlaceForInsertion(overrideTarget: Int) {
        val target = if (overrideTarget >= 0) overrideTarget else currentNode()
        if (!(fosterParenting && TagSets.fosterTarget(sink.elemName(target)))) {
            insertTarget = target
            insertPrev = -1
            return
        }
        for (i in openElems.size - 1 downTo 0) {
            val elem = openElems[i]
            if (htmlElemNamed(elem, "template")) {
                insertTarget = elem
                insertPrev = -1
                return
            } else if (htmlElemNamed(elem, "table")) {
                insertTarget = elem
                insertPrev = openElems[i - 1]
                return
            }
        }
        insertTarget = openElems[0]
        insertPrev = -1
    }

    private fun insertNodeAtPlace(node: Int) {
        if (insertPrev < 0) sink.appendNode(insertTarget, node) else sink.appendNodeBasedOnParentNode(insertTarget, insertPrev, node)
    }

    private fun insertTextAtPlace(text: String) {
        if (insertPrev < 0) sink.appendText(insertTarget, text) else sink.appendTextBasedOnParentNode(insertTarget, insertPrev, text)
    }

    private fun insertNodeAppropriately(node: Int, overrideTarget: Int = -1) {
        appropriatePlaceForInsertion(overrideTarget)
        insertNodeAtPlace(node)
    }

    private fun unexpected(): Pr = Pr.Done

    private fun positionInActiveFormatting(element: Int): Int {
        for (i in activeFormatting.indices) {
            if (activeFormatting[i].node == element) return i
        }
        return -1
    }

    private fun toRawTextMode(kind: Int): Pr {
        origMode = mode
        mode = TEXT
        return Pr.ToRawData(kind)
    }

    private fun parseRawData(tag: Tag, kind: Int): Pr {
        insertElementFor(tag)
        return toRawTextMode(kind)
    }

    private fun currentNode(): Int {
        check(openElems.size > 0) { "no current element" }
        return openElems.last()
    }

    private fun adjustedCurrentNode(): Int =
        if (openElems.size == 1 && contextElem >= 0) contextElem else currentNode()

    private fun currentNodeIn(set: (QualName) -> Boolean): Boolean = set(sink.elemName(currentNode()))

    private fun adoptionAgency(subject: String) {
        if (currentNodeNamed(subject) && positionInActiveFormatting(currentNode()) < 0) {
            pop()
            return
        }
        repeat(8) {
            var fmtElemIndex = -1
            for (i in activeFormatting.size - 1 downTo 0) {
                val entry = activeFormatting[i]
                if (entry.isMarker) break
                if (entry.tag!!.name == subject) {
                    fmtElemIndex = i
                    break
                }
            }
            if (fmtElemIndex < 0) {
                processEndTagInBody(Tag(false, subject, false, ArrayList()))
                return
            }
            val fmtElem = activeFormatting[fmtElemIndex].node
            val fmtElemTag = activeFormatting[fmtElemIndex].tag!!
            val fmtElemStackIndex = openElems.lastIndexOf(fmtElem)
            if (fmtElemStackIndex < 0) {
                activeFormatting.removeAt(fmtElemIndex)
                return
            }
            if (!inScope(TagSets.defaultScope) { it == fmtElem }) return
            var furthestBlockIndex = -1
            for (i in fmtElemStackIndex until openElems.size) {
                if (TagSets.specialTag(sink.elemName(openElems[i]))) {
                    furthestBlockIndex = i
                    break
                }
            }
            if (furthestBlockIndex < 0) {
                openElems.truncate(fmtElemStackIndex)
                activeFormatting.removeAt(fmtElemIndex)
                return
            }
            val furthestBlock = openElems[furthestBlockIndex]
            val commonAncestor = openElems[fmtElemStackIndex - 1]
            var bookmarkReplace = true
            var bookmarkNode = fmtElem
            var node: Int
            var nodeIndex = furthestBlockIndex
            var lastNode = furthestBlock
            var innerCounter = 0
            while (true) {
                innerCounter++
                nodeIndex--
                node = openElems[nodeIndex]
                if (node == fmtElem) break
                if (innerCounter > 3) {
                    val position = positionInActiveFormatting(node)
                    if (position >= 0) activeFormatting.removeAt(position)
                    openElems.removeAt(nodeIndex)
                    continue
                }
                val nodeFormattingIndex = positionInActiveFormatting(node)
                if (nodeFormattingIndex < 0) {
                    openElems.removeAt(nodeIndex)
                    continue
                }
                val tag = activeFormatting[nodeFormattingIndex].tag!!
                val newElement = sink.createElement(QualName.html(tag.name), tag.attrs)
                openElems[nodeIndex] = newElement
                activeFormatting[nodeFormattingIndex] = FormatEntry(newElement, tag)
                node = newElement
                if (lastNode == furthestBlock) {
                    bookmarkReplace = false
                    bookmarkNode = node
                }
                sink.removeFromParent(lastNode)
                sink.appendNode(node, lastNode)
                lastNode = node
            }
            sink.removeFromParent(lastNode)
            insertNodeAppropriately(lastNode, commonAncestor)
            val newElement = sink.createElement(QualName.html(fmtElemTag.name), fmtElemTag.attrs)
            val newEntry = FormatEntry(newElement, fmtElemTag)
            sink.reparentChildren(furthestBlock, newElement)
            sink.appendNode(furthestBlock, newElement)
            if (bookmarkReplace) {
                val index = positionInActiveFormatting(bookmarkNode)
                check(index >= 0) { "bookmark not found in active formatting elements" }
                activeFormatting[index] = newEntry
            } else {
                val index = positionInActiveFormatting(bookmarkNode)
                check(index >= 0) { "bookmark not found in active formatting elements" }
                activeFormatting.add(index + 1, newEntry)
                val oldIndex = positionInActiveFormatting(fmtElem)
                check(oldIndex >= 0) { "formatting element not found in active formatting elements" }
                activeFormatting.removeAt(oldIndex)
            }
            removeFromStack(fmtElem)
            val newFurthestBlockIndex = openElems.indexOf(furthestBlock)
            check(newFurthestBlockIndex >= 0) { "furthest block missing from open element stack" }
            openElems.insert(newFurthestBlockIndex + 1, newElement)
        }
    }

    private fun push(elem: Int) = openElems.add(elem)

    private fun pop(): Int {
        val elem = openElems.removeLast()
        check(elem >= 0) { "no current element" }
        return elem
    }

    private fun removeFromStack(elem: Int) {
        val position = openElems.lastIndexOf(elem)
        if (position >= 0) openElems.removeAt(position)
    }

    private fun isMarkerOrOpen(entry: FormatEntry): Boolean = entry.isMarker || openElems.lastIndexOf(entry.node) >= 0

    private fun reconstructActiveFormattingElements() {
        if (activeFormatting.isEmpty()) return
        if (isMarkerOrOpen(activeFormatting[activeFormatting.size - 1])) return
        var entryIndex = activeFormatting.size - 1
        while (true) {
            if (entryIndex == 0) break
            entryIndex--
            if (isMarkerOrOpen(activeFormatting[entryIndex])) {
                entryIndex++
                break
            }
        }
        while (true) {
            val tag = activeFormatting[entryIndex].tag ?: error("Found marker during formatting element reconstruction")
            val newElement = insertElement(true, Ns.HTML, tag.name, tag.attrs)
            activeFormatting[entryIndex] = FormatEntry(newElement, tag)
            if (entryIndex == activeFormatting.size - 1) break
            entryIndex++
        }
    }

    private fun htmlElem(): Int = openElems[0]

    private fun bodyElem(): Int {
        if (openElems.size <= 1) return -1
        val node = openElems[1]
        return if (htmlElemNamed(node, "body")) node else -1
    }

    private inline fun inScope(scope: (QualName) -> Boolean, pred: (Int) -> Boolean): Boolean {
        for (i in openElems.size - 1 downTo 0) {
            val node = openElems[i]
            if (pred(node)) return true
            if (scope(sink.elemName(node))) return false
        }
        return false
    }

    private fun elemIn(elem: Int, set: (QualName) -> Boolean): Boolean = set(sink.elemName(elem))

    private fun htmlElemNamed(elem: Int, name: String): Boolean {
        val n = sink.elemName(elem)
        return n.ns == Ns.HTML && n.local == name
    }

    private fun inHtmlElemNamed(name: String): Boolean {
        for (i in 0 until openElems.size) if (htmlElemNamed(openElems[i], name)) return true
        return false
    }

    private fun currentNodeNamed(name: String): Boolean = htmlElemNamed(currentNode(), name)

    private fun inScopeNamed(scope: (QualName) -> Boolean, name: String): Boolean = inScope(scope) { htmlElemNamed(it, name) }

    private fun generateImpliedEndTags(set: (QualName) -> Boolean) {
        while (openElems.size > 0 && set(sink.elemName(openElems.last()))) pop()
    }

    private fun generateImpliedEndExcept(except: String) {
        generateImpliedEndTags { p -> if (p.ns == Ns.HTML && p.local == except) false else TagSets.cursoryImpliedEnd(p) }
    }

    private fun popUntilCurrent(set: (QualName) -> Boolean) {
        while (!currentNodeIn(set)) openElems.removeLast()
    }

    private inline fun popUntil(pred: (QualName) -> Boolean): Int {
        var n = 0
        while (true) {
            n++
            val elem = openElems.removeLast()
            if (elem < 0) break
            if (pred(sink.elemName(elem))) break
        }
        return n
    }

    private fun popUntilNamed(name: String): Int = popUntil { it.ns == Ns.HTML && it.local == name }

    private fun expectToClose(name: String) {
        popUntilNamed(name)
    }

    private fun closePElement() {
        generateImpliedEndTags(TagSets.impliedButP)
        expectToClose("p")
    }

    private fun closePElementInButtonScope() {
        if (inScopeNamed(TagSets.buttonScope, "p")) closePElement()
    }

    private fun isTypeHidden(tag: Tag): Boolean {
        val type = tag.attrs.firstOrNull { it.name.ns == Ns.NONE && it.name.prefix == null && it.name.local == "type" } ?: return false
        return type.value.equals("hidden", ignoreCase = true) && type.value.all { it.code < 128 }
    }

    private fun fosterParentInBody(token: TbToken): Pr {
        fosterParenting = true
        val res = step(IN_BODY, token)
        fosterParenting = false
        return res
    }

    private fun processCharsInTable(token: TbToken): Pr {
        if (currentNodeIn(TagSets.tableOuterChars)) {
            check(pendingTableText.isEmpty())
            origMode = mode
            return Pr.Reprocess(IN_TABLE_TEXT, token)
        }
        return fosterParentInBody(token)
    }

    private fun resetInsertionMode(): Int {
        for (i in openElems.size - 1 downTo 0) {
            val last = i == 0
            val node = if (last && contextElem >= 0) contextElem else openElems[i]
            val name = sink.elemName(node)
            if (name.ns != Ns.HTML) continue
            when (name.local) {
                "select" -> {
                    for (j in i - 1 downTo 0) {
                        val ancestor = openElems[j]
                        if (htmlElemNamed(ancestor, "template")) {
                            return IN_SELECT
                        } else if (htmlElemNamed(ancestor, "table")) {
                            return IN_SELECT_IN_TABLE
                        }
                    }
                    return IN_SELECT
                }
                "td", "th" -> if (!last) return IN_CELL
                "tr" -> return IN_ROW
                "tbody", "thead", "tfoot" -> return IN_TABLE_BODY
                "caption" -> return IN_CAPTION
                "colgroup" -> return IN_COLUMN_GROUP
                "table" -> return IN_TABLE
                "template" -> {
                    check(templateModes.size > 0) { "no template insertion mode" }
                    return templateModes.last()
                }
                "head" -> if (!last) return IN_HEAD
                "body" -> return IN_BODY
                "frameset" -> return IN_FRAMESET
                "html" -> return if (headElem < 0) BEFORE_HEAD else AFTER_HEAD
            }
        }
        return IN_BODY
    }

    private fun closeTheCell() {
        generateImpliedEndTags(TagSets.cursoryImpliedEnd)
        popUntil(TagSets.tdTh)
        clearActiveFormattingToMarker()
    }

    private fun appendText(text: String): Pr {
        appropriatePlaceForInsertion(-1)
        insertTextAtPlace(text)
        return Pr.Done
    }

    private fun appendComment(text: String): Pr {
        val comment = sink.createComment(text)
        insertNodeAppropriately(comment)
        return Pr.Done
    }

    private fun appendCommentToDoc(text: String): Pr {
        sink.appendNode(0, sink.createComment(text))
        return Pr.Done
    }

    private fun appendCommentToHtml(text: String): Pr {
        sink.appendNode(htmlElem(), sink.createComment(text))
        return Pr.Done
    }

    private fun createRoot(attrs: List<TokenAttr>) {
        val elem = sink.createElement(QualName.html("html"), attrs)
        push(elem)
        sink.appendNode(0, elem)
    }

    private fun insertElement(push: Boolean, ns: String, name: String, attrs: List<TokenAttr>): Int {
        val elem = sink.createElement(QualName(null, ns, name), attrs)
        appropriatePlaceForInsertion(-1)
        insertNodeAtPlace(elem)
        if (push) push(elem)
        return elem
    }

    private fun insertElementFor(tag: Tag): Int = insertElement(true, Ns.HTML, tag.name, tag.attrs)

    private fun insertAndPopElementFor(tag: Tag): Int = insertElement(false, Ns.HTML, tag.name, tag.attrs)

    private fun insertPhantom(name: String): Int = insertElement(true, Ns.HTML, name, emptyList())

    private fun createFormattingElementFor(tag: Tag): Int {
        var firstMatch = -1
        var matches = 0
        for (i in activeFormatting.size - 1 downTo 0) {
            val entry = activeFormatting[i]
            if (entry.isMarker) break
            if (tag.equivModuloAttrOrder(entry.tag!!)) {
                firstMatch = i
                matches++
            }
        }
        if (matches >= 3) activeFormatting.removeAt(firstMatch)
        val elem = insertElement(true, Ns.HTML, tag.name, tag.attrs)
        activeFormatting.add(FormatEntry(elem, tag))
        return elem
    }

    private fun clearActiveFormattingToMarker() {
        while (activeFormatting.isNotEmpty()) {
            val entry = activeFormatting.removeAt(activeFormatting.size - 1)
            if (entry.isMarker) break
        }
    }

    private fun processEndTagInBody(tag: Tag) {
        var matchIdx = -1
        for (i in openElems.size - 1 downTo 0) {
            val elem = openElems[i]
            if (htmlElemNamed(elem, tag.name)) {
                matchIdx = i
                break
            }
            if (elemIn(elem, TagSets.specialTag)) return
        }
        if (matchIdx < 0) return
        generateImpliedEndExcept(tag.name)
        openElems.truncate(matchIdx)
    }

    private fun handleMisnestedATags() {
        var node = -1
        for (i in activeFormatting.size - 1 downTo 0) {
            val entry = activeFormatting[i]
            if (entry.isMarker) break
            if (htmlElemNamed(entry.node, "a")) {
                node = entry.node
                break
            }
        }
        if (node < 0) return
        adoptionAgency("a")
        val index = positionInActiveFormatting(node)
        if (index >= 0) activeFormatting.removeAt(index)
        removeFromStack(node)
    }

    private fun isForeign(token: TbToken): Boolean {
        if (token === TbToken.Eof) return false
        if (openElems.size == 0) return false
        val name = sink.elemName(adjustedCurrentNode())
        if (name.ns == Ns.HTML) return false
        val startTag = (token as? TbToken.TagTok)?.tag?.takeIf { it.isStart }
        val charsOrNull = token is TbToken.Chars || token === TbToken.NullChar
        if (TagSets.mathmlTextIntegrationPoint(name)) {
            if (charsOrNull) return false
            if (startTag != null && startTag.name != "mglyph" && startTag.name != "malignmark") return false
        }
        if (TagSets.svgHtmlIntegrationPoint(name)) {
            if (charsOrNull || startTag != null) return false
        }
        if (name.ns == Ns.MATHML && name.local == "annotation-xml") {
            if (startTag != null && startTag.name == "svg") return false
            if (charsOrNull || startTag != null) return true
        }
        return true
    }

    private fun enterForeign(tag: Tag, ns: String): Pr {
        when (ns) {
            Ns.MATHML -> adjustMathmlAttributes(tag)
            Ns.SVG -> adjustSvgAttributes(tag)
        }
        adjustForeignAttributes(tag)
        return if (tag.selfClosing) {
            insertElement(false, ns, tag.name, tag.attrs)
            Pr.DoneAckSelfClosing
        } else {
            insertElement(true, ns, tag.name, tag.attrs)
            Pr.Done
        }
    }

    private fun adjustSvgAttributes(tag: Tag) {
        for (a in tag.attrs) {
            val replacement = SVG_ATTRIBUTES[a.name.local] ?: continue
            a.name = QualName(null, Ns.NONE, replacement)
        }
    }

    private fun adjustMathmlAttributes(tag: Tag) {
        for (a in tag.attrs) {
            if (a.name.local == "definitionurl") a.name = QualName(null, Ns.NONE, "definitionURL")
        }
    }

    private fun adjustForeignAttributes(tag: Tag) {
        for (a in tag.attrs) {
            val replacement = FOREIGN_ATTRIBUTES[a.name.local] ?: continue
            a.name = replacement
        }
    }

    private fun foreignStartTag(original: Tag): Pr {
        val currentNs = sink.elemName(adjustedCurrentNode()).ns
        var tag = original
        when (currentNs) {
            Ns.MATHML -> adjustMathmlAttributes(tag)
            Ns.SVG -> {
                val adjusted = SVG_TAG_NAMES[tag.name]
                if (adjusted != null) tag = tag.renamed(adjusted)
                adjustSvgAttributes(tag)
            }
        }
        adjustForeignAttributes(tag)
        return if (tag.selfClosing) {
            insertElement(false, currentNs, tag.name, tag.attrs)
            Pr.DoneAckSelfClosing
        } else {
            insertElement(true, currentNs, tag.name, tag.attrs)
            Pr.Done
        }
    }

    private fun unexpectedStartTagInForeignContent(tag: Tag): Pr {
        while (!currentNodeIn { n -> n.ns == Ns.HTML || TagSets.mathmlTextIntegrationPoint(n) || TagSets.svgHtmlIntegrationPoint(n) }) {
            pop()
        }
        return step(mode, TbToken.TagTok(tag))
    }

    @Suppress("CyclomaticComplexMethod")
    private fun step(mode: Int, token: TbToken): Pr = when (mode) {
        BEFORE_HEAD -> stepBeforeHead(token)
        IN_HEAD -> stepInHead(token)
        IN_HEAD_NOSCRIPT -> stepInHeadNoscript(token)
        AFTER_HEAD -> stepAfterHead(token)
        IN_BODY -> stepInBody(token)
        TEXT -> stepText(token)
        IN_TABLE -> stepInTable(token)
        IN_TABLE_TEXT -> stepInTableText(token)
        IN_CAPTION -> stepInCaption(token)
        IN_COLUMN_GROUP -> stepInColumnGroup(token)
        IN_TABLE_BODY -> stepInTableBody(token)
        IN_ROW -> stepInRow(token)
        IN_CELL -> stepInCell(token)
        IN_SELECT -> stepInSelect(token)
        IN_SELECT_IN_TABLE -> stepInSelectInTable(token)
        IN_TEMPLATE -> stepInTemplate(token)
        AFTER_BODY -> stepAfterBody(token)
        IN_FRAMESET -> stepInFrameset(token)
        AFTER_FRAMESET -> stepAfterFrameset(token)
        AFTER_AFTER_BODY -> stepAfterAfterBody(token)
        AFTER_AFTER_FRAMESET -> stepAfterAfterFrameset(token)
        else -> stepBeforeHtml(token)
    }

    private fun stepBeforeHtml(token: TbToken): Pr {
        if (token is TbToken.Chars) {
            if (token.split == NOT_SPLIT) return Pr.SplitWhitespace(token.text)
            if (token.split == WHITESPACE) return Pr.Done
        }
        if (token is TbToken.CommentTok) return appendCommentToDoc(token.text)
        if (token is TbToken.TagTok) {
            val tag = token.tag
            if (tag.isStart && tag.name == "html") {
                createRoot(tag.attrs)
                this.mode = BEFORE_HEAD
                return Pr.Done
            }
            if (!tag.isStart && tag.name !in HEAD_BODY_HTML_BR) return unexpected()
        }
        createRoot(emptyList())
        return Pr.Reprocess(BEFORE_HEAD, token)
    }

    private fun stepBeforeHead(token: TbToken): Pr {
        if (token is TbToken.Chars) {
            if (token.split == NOT_SPLIT) return Pr.SplitWhitespace(token.text)
            if (token.split == WHITESPACE) return Pr.Done
        }
        if (token is TbToken.CommentTok) return appendComment(token.text)
        if (token is TbToken.TagTok) {
            val tag = token.tag
            if (tag.isStart && tag.name == "html") return step(IN_BODY, token)
            if (tag.isStart && tag.name == "head") {
                headElem = insertElementFor(tag)
                this.mode = IN_HEAD
                return Pr.Done
            }
            if (!tag.isStart && tag.name !in HEAD_BODY_HTML_BR) return unexpected()
        }
        headElem = insertPhantom("head")
        return Pr.Reprocess(IN_HEAD, token)
    }

    private fun stepInHead(token: TbToken): Pr {
        if (token is TbToken.Chars) {
            if (token.split == NOT_SPLIT) return Pr.SplitWhitespace(token.text)
            if (token.split == WHITESPACE) return appendText(token.text)
        }
        if (token is TbToken.CommentTok) return appendComment(token.text)
        if (token is TbToken.TagTok) {
            val tag = token.tag
            if (tag.isStart) {
                when (tag.name) {
                    "html" -> return step(IN_BODY, token)
                    "base", "basefont", "bgsound", "link", "meta" -> {
                        insertAndPopElementFor(tag)
                        return Pr.DoneAckSelfClosing
                    }
                    "title" -> return parseRawData(tag, RawKind.RCDATA)
                    "noframes", "style", "noscript" -> {
                        return if (tag.name == "noscript") {
                            insertElementFor(tag)
                            this.mode = IN_HEAD_NOSCRIPT
                            Pr.Done
                        } else {
                            parseRawData(tag, RawKind.RAWTEXT)
                        }
                    }
                    "script" -> {
                        val elem = sink.createElement(QualName.html("script"), tag.attrs)
                        insertNodeAppropriately(elem)
                        openElems.add(elem)
                        return toRawTextMode(RawKind.SCRIPT_DATA)
                    }
                    "template" -> {
                        activeFormatting.add(FormatEntry.MARKER)
                        framesetOk = false
                        this.mode = IN_TEMPLATE
                        templateModes.add(IN_TEMPLATE)
                        insertElementFor(tag)
                        return Pr.Done
                    }
                    "head" -> return unexpected()
                }
            } else {
                when (tag.name) {
                    "head" -> {
                        pop()
                        this.mode = AFTER_HEAD
                        return Pr.Done
                    }
                    "body", "html", "br" -> Unit
                    "template" -> {
                        if (inHtmlElemNamed("template")) {
                            generateImpliedEndTags(TagSets.thoroughImpliedEnd)
                            expectToClose("template")
                            clearActiveFormattingToMarker()
                            templateModes.removeLast()
                            this.mode = resetInsertionMode()
                        }
                        return Pr.Done
                    }
                    else -> return unexpected()
                }
            }
        }
        pop()
        return Pr.Reprocess(AFTER_HEAD, token)
    }

    private fun stepInHeadNoscript(token: TbToken): Pr {
        if (token is TbToken.TagTok) {
            val tag = token.tag
            if (tag.isStart && tag.name == "html") return step(IN_BODY, token)
            if (!tag.isStart && tag.name == "noscript") {
                pop()
                this.mode = IN_HEAD
                return Pr.Done
            }
        }
        if (token is TbToken.Chars) {
            if (token.split == NOT_SPLIT) return Pr.SplitWhitespace(token.text)
            if (token.split == WHITESPACE) return step(IN_HEAD, token)
        }
        if (token is TbToken.CommentTok) return step(IN_HEAD, token)
        if (token is TbToken.TagTok) {
            val tag = token.tag
            if (tag.isStart) {
                when (tag.name) {
                    "basefont", "bgsound", "link", "meta", "noframes", "style" -> return step(IN_HEAD, token)
                    "head", "noscript" -> return unexpected()
                }
            } else if (tag.name != "br") {
                return unexpected()
            }
        }
        pop()
        return Pr.Reprocess(IN_HEAD, token)
    }

    private fun stepAfterHead(token: TbToken): Pr {
        if (token is TbToken.Chars) {
            if (token.split == NOT_SPLIT) return Pr.SplitWhitespace(token.text)
            if (token.split == WHITESPACE) return appendText(token.text)
        }
        if (token is TbToken.CommentTok) return appendComment(token.text)
        if (token is TbToken.TagTok) {
            val tag = token.tag
            if (tag.isStart) {
                when (tag.name) {
                    "html" -> return step(IN_BODY, token)
                    "body" -> {
                        insertElementFor(tag)
                        framesetOk = false
                        this.mode = IN_BODY
                        return Pr.Done
                    }
                    "frameset" -> {
                        insertElementFor(tag)
                        this.mode = IN_FRAMESET
                        return Pr.Done
                    }
                    "base", "basefont", "bgsound", "link", "meta", "noframes", "script", "style", "template", "title" -> {
                        check(headElem >= 0) { "no head element" }
                        val head = headElem
                        push(head)
                        val result = step(IN_HEAD, token)
                        removeFromStack(head)
                        return result
                    }
                    "head" -> return unexpected()
                }
            } else {
                when (tag.name) {
                    "template" -> return step(IN_HEAD, token)
                    "body", "html", "br" -> Unit
                    else -> return unexpected()
                }
            }
        }
        insertPhantom("body")
        return Pr.Reprocess(IN_BODY, token)
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    private fun stepInBody(token: TbToken): Pr {
        when (token) {
            TbToken.NullChar -> return unexpected()
            is TbToken.Chars -> {
                reconstructActiveFormattingElements()
                if (anyNotWhitespace(token.text)) framesetOk = false
                return appendText(token.text)
            }
            is TbToken.CommentTok -> return appendComment(token.text)
            TbToken.Eof -> {
                return if (templateModes.size > 0) step(IN_TEMPLATE, token) else Pr.Done
            }
            is TbToken.TagTok -> {
                val tag = token.tag
                return if (tag.isStart) startTagInBody(token, tag) else endTagInBody(token, tag)
            }
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun startTagInBody(token: TbToken, tag: Tag): Pr {
        when (tag.name) {
            "html" -> {
                if (!inHtmlElemNamed("template")) sink.addAttrsIfMissing(htmlElem(), tag.attrs)
                return Pr.Done
            }
            "base", "basefont", "bgsound", "link", "meta", "noframes", "script", "style", "template", "title" ->
                return step(IN_HEAD, token)
            "body" -> {
                val body = bodyElem()
                if (body >= 0 && openElems.size != 1 && !inHtmlElemNamed("template")) {
                    framesetOk = false
                    sink.addAttrsIfMissing(body, tag.attrs)
                }
                return Pr.Done
            }
            "frameset" -> {
                if (!framesetOk) return Pr.Done
                val body = bodyElem()
                if (body < 0) return Pr.Done
                sink.removeFromParent(body)
                openElems.truncate(1)
                insertElementFor(tag)
                this.mode = IN_FRAMESET
                return Pr.Done
            }
            "address", "article", "aside", "blockquote", "center", "details", "dialog", "dir", "div", "dl", "fieldset",
            "figcaption", "figure", "footer", "header", "hgroup", "main", "nav", "ol", "p", "search", "section", "summary",
            "ul", "menu",
            -> {
                closePElementInButtonScope()
                insertElementFor(tag)
                return Pr.Done
            }
            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                closePElementInButtonScope()
                if (currentNodeIn(TagSets.headingTag)) pop()
                insertElementFor(tag)
                return Pr.Done
            }
            "pre", "listing" -> {
                closePElementInButtonScope()
                insertElementFor(tag)
                ignoreLf = true
                framesetOk = false
                return Pr.Done
            }
            "form" -> {
                if (formElem >= 0 && !inHtmlElemNamed("template")) return Pr.Done
                closePElementInButtonScope()
                val elem = insertElementFor(tag)
                if (!inHtmlElemNamed("template")) formElem = elem
                return Pr.Done
            }
            "li", "dd", "dt" -> {
                val list = tag.name == "li"
                framesetOk = false
                var toClose: String? = null
                for (i in openElems.size - 1 downTo 0) {
                    val name = sink.elemName(openElems[i])
                    val canClose = name.ns == Ns.HTML && if (list) name.local == "li" else (name.local == "dd" || name.local == "dt")
                    if (canClose) {
                        toClose = name.local
                        break
                    }
                    if (TagSets.extraSpecial(name)) break
                }
                if (toClose != null) {
                    generateImpliedEndExcept(toClose)
                    expectToClose(toClose)
                }
                closePElementInButtonScope()
                insertElementFor(tag)
                return Pr.Done
            }
            "plaintext" -> {
                closePElementInButtonScope()
                insertElementFor(tag)
                return Pr.ToPlaintext
            }
            "button" -> {
                if (inScopeNamed(TagSets.defaultScope, "button")) {
                    generateImpliedEndTags(TagSets.cursoryImpliedEnd)
                    popUntilNamed("button")
                }
                reconstructActiveFormattingElements()
                insertElementFor(tag)
                framesetOk = false
                return Pr.Done
            }
            "a" -> {
                handleMisnestedATags()
                reconstructActiveFormattingElements()
                createFormattingElementFor(tag)
                return Pr.Done
            }
            "b", "big", "code", "em", "font", "i", "s", "small", "strike", "strong", "tt", "u" -> {
                reconstructActiveFormattingElements()
                createFormattingElementFor(tag)
                return Pr.Done
            }
            "nobr" -> {
                reconstructActiveFormattingElements()
                if (inScopeNamed(TagSets.defaultScope, "nobr")) {
                    adoptionAgency("nobr")
                    reconstructActiveFormattingElements()
                }
                createFormattingElementFor(tag)
                return Pr.Done
            }
            "applet", "marquee", "object" -> {
                reconstructActiveFormattingElements()
                insertElementFor(tag)
                activeFormatting.add(FormatEntry.MARKER)
                framesetOk = false
                return Pr.Done
            }
            "table" -> {
                closePElementInButtonScope()
                insertElementFor(tag)
                framesetOk = false
                this.mode = IN_TABLE
                return Pr.Done
            }
            "area", "br", "embed", "img", "keygen", "wbr", "input" -> {
                val keepFramesetOk = tag.name == "input" && isTypeHidden(tag)
                reconstructActiveFormattingElements()
                insertAndPopElementFor(tag)
                if (!keepFramesetOk) framesetOk = false
                return Pr.DoneAckSelfClosing
            }
            "param", "source", "track" -> {
                insertAndPopElementFor(tag)
                return Pr.DoneAckSelfClosing
            }
            "hr" -> {
                closePElementInButtonScope()
                insertAndPopElementFor(tag)
                framesetOk = false
                return Pr.DoneAckSelfClosing
            }
            "image" -> return step(IN_BODY, TbToken.TagTok(tag.renamed("img")))
            "textarea" -> {
                ignoreLf = true
                framesetOk = false
                return parseRawData(tag, RawKind.RCDATA)
            }
            "xmp" -> {
                closePElementInButtonScope()
                reconstructActiveFormattingElements()
                framesetOk = false
                return parseRawData(tag, RawKind.RAWTEXT)
            }
            "iframe" -> {
                framesetOk = false
                return parseRawData(tag, RawKind.RAWTEXT)
            }
            "noembed" -> return parseRawData(tag, RawKind.RAWTEXT)
            "select" -> {
                reconstructActiveFormattingElements()
                insertElementFor(tag)
                framesetOk = false
                this.mode = when (this.mode) {
                    IN_TABLE, IN_CAPTION, IN_TABLE_BODY, IN_ROW, IN_CELL -> IN_SELECT_IN_TABLE
                    else -> IN_SELECT
                }
                return Pr.Done
            }
            "optgroup", "option" -> {
                if (currentNodeNamed("option")) pop()
                reconstructActiveFormattingElements()
                insertElementFor(tag)
                return Pr.Done
            }
            "rb", "rtc" -> {
                if (inScopeNamed(TagSets.defaultScope, "ruby")) generateImpliedEndTags(TagSets.cursoryImpliedEnd)
                insertElementFor(tag)
                return Pr.Done
            }
            "rp", "rt" -> {
                if (inScopeNamed(TagSets.defaultScope, "ruby")) generateImpliedEndExcept("rtc")
                insertElementFor(tag)
                return Pr.Done
            }
            "math" -> {
                reconstructActiveFormattingElements()
                return enterForeign(tag, Ns.MATHML)
            }
            "svg" -> {
                reconstructActiveFormattingElements()
                return enterForeign(tag, Ns.SVG)
            }
            "caption", "col", "colgroup", "frame", "head", "tbody", "td", "tfoot", "th", "thead", "tr" -> return unexpected()
            else -> {
                reconstructActiveFormattingElements()
                insertElementFor(tag)
                return Pr.Done
            }
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun endTagInBody(token: TbToken, tag: Tag): Pr {
        when (tag.name) {
            "template" -> return step(IN_HEAD, token)
            "body" -> {
                if (inScopeNamed(TagSets.defaultScope, "body")) this.mode = AFTER_BODY
                return Pr.Done
            }
            "html" -> {
                return if (inScopeNamed(TagSets.defaultScope, "body")) Pr.Reprocess(AFTER_BODY, token) else Pr.Done
            }
            "address", "article", "aside", "blockquote", "button", "center", "details", "dialog", "dir", "div", "dl",
            "fieldset", "figcaption", "figure", "footer", "header", "hgroup", "listing", "main", "menu", "nav", "ol", "pre",
            "search", "section", "summary", "ul",
            -> {
                if (inScopeNamed(TagSets.defaultScope, tag.name)) {
                    generateImpliedEndTags(TagSets.cursoryImpliedEnd)
                    expectToClose(tag.name)
                }
                return Pr.Done
            }
            "form" -> {
                if (!inHtmlElemNamed("template")) {
                    val node = formElem
                    formElem = -1
                    if (node < 0) return Pr.Done
                    if (!inScope(TagSets.defaultScope) { it == node }) return Pr.Done
                    generateImpliedEndTags(TagSets.cursoryImpliedEnd)
                    removeFromStack(node)
                } else {
                    if (!inScopeNamed(TagSets.defaultScope, "form")) return Pr.Done
                    generateImpliedEndTags(TagSets.cursoryImpliedEnd)
                    popUntilNamed("form")
                }
                return Pr.Done
            }
            "p" -> {
                if (!inScopeNamed(TagSets.buttonScope, "p")) insertPhantom("p")
                closePElement()
                return Pr.Done
            }
            "li", "dd", "dt" -> {
                val inScope = if (tag.name == "li") {
                    inScopeNamed(TagSets.listItemScope, tag.name)
                } else {
                    inScopeNamed(TagSets.defaultScope, tag.name)
                }
                if (inScope) {
                    generateImpliedEndExcept(tag.name)
                    expectToClose(tag.name)
                }
                return Pr.Done
            }
            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                if (inScope(TagSets.defaultScope) { elemIn(it, TagSets.headingTag) }) {
                    generateImpliedEndTags(TagSets.cursoryImpliedEnd)
                    popUntil(TagSets.headingTag)
                }
                return Pr.Done
            }
            "a", "b", "big", "code", "em", "font", "i", "nobr", "s", "small", "strike", "strong", "tt", "u" -> {
                adoptionAgency(tag.name)
                return Pr.Done
            }
            "applet", "marquee", "object" -> {
                if (inScopeNamed(TagSets.defaultScope, tag.name)) {
                    generateImpliedEndTags(TagSets.cursoryImpliedEnd)
                    expectToClose(tag.name)
                    clearActiveFormattingToMarker()
                }
                return Pr.Done
            }
            "br" -> return step(IN_BODY, TbToken.TagTok(Tag(true, tag.name, tag.selfClosing, ArrayList())))
            else -> {
                processEndTagInBody(tag)
                return Pr.Done
            }
        }
    }

    private fun stepText(token: TbToken): Pr = when (token) {
        is TbToken.Chars -> appendText(token.text)
        TbToken.Eof -> {
            pop()
            val orig = origMode
            origMode = -1
            Pr.Reprocess(orig, token)
        }
        is TbToken.TagTok -> {
            check(!token.tag.isStart) { "impossible case in Text mode" }
            pop()
            this.mode = origMode
            origMode = -1
            if (token.tag.name == "script") Pr.Script else Pr.Done
        }
        else -> error("impossible case in Text mode")
    }

    @Suppress("CyclomaticComplexMethod")
    private fun stepInTable(token: TbToken): Pr {
        when (token) {
            TbToken.NullChar, is TbToken.Chars -> return processCharsInTable(token)
            is TbToken.CommentTok -> return appendComment(token.text)
            TbToken.Eof -> return step(IN_BODY, token)
            is TbToken.TagTok -> {
                val tag = token.tag
                if (tag.isStart) {
                    when (tag.name) {
                        "caption" -> {
                            popUntilCurrent(TagSets.tableScope)
                            activeFormatting.add(FormatEntry.MARKER)
                            insertElementFor(tag)
                            this.mode = IN_CAPTION
                            return Pr.Done
                        }
                        "colgroup" -> {
                            popUntilCurrent(TagSets.tableScope)
                            insertElementFor(tag)
                            this.mode = IN_COLUMN_GROUP
                            return Pr.Done
                        }
                        "col" -> {
                            popUntilCurrent(TagSets.tableScope)
                            insertPhantom("colgroup")
                            return Pr.Reprocess(IN_COLUMN_GROUP, token)
                        }
                        "tbody", "tfoot", "thead" -> {
                            popUntilCurrent(TagSets.tableScope)
                            insertElementFor(tag)
                            this.mode = IN_TABLE_BODY
                            return Pr.Done
                        }
                        "td", "th", "tr" -> {
                            popUntilCurrent(TagSets.tableScope)
                            insertPhantom("tbody")
                            return Pr.Reprocess(IN_TABLE_BODY, token)
                        }
                        "table" -> {
                            if (inScopeNamed(TagSets.tableScope, "table")) {
                                popUntilNamed("table")
                                return Pr.Reprocess(resetInsertionMode(), token)
                            }
                            return Pr.Done
                        }
                        "style", "script", "template" -> return step(IN_HEAD, token)
                        "input" -> {
                            if (isTypeHidden(tag)) {
                                insertAndPopElementFor(tag)
                                return Pr.DoneAckSelfClosing
                            }
                            return fosterParentInBody(TbToken.TagTok(tag))
                        }
                        "form" -> {
                            if (!inHtmlElemNamed("template") && formElem < 0) formElem = insertAndPopElementFor(tag)
                            return Pr.Done
                        }
                    }
                } else {
                    when (tag.name) {
                        "table" -> {
                            if (inScopeNamed(TagSets.tableScope, "table")) {
                                popUntilNamed("table")
                                this.mode = resetInsertionMode()
                            }
                            return Pr.Done
                        }
                        "body", "caption", "col", "colgroup", "html", "tbody", "td", "tfoot", "th", "thead", "tr" -> return unexpected()
                        "template" -> return step(IN_HEAD, token)
                    }
                }
                return fosterParentInBody(token)
            }
        }
    }

    private fun stepInTableText(token: TbToken): Pr {
        if (token === TbToken.NullChar) return unexpected()
        if (token is TbToken.Chars) {
            pendingTableText.add(token)
            return Pr.Done
        }
        val pending = ArrayList(pendingTableText)
        pendingTableText.clear()
        val containsNonspace = pending.any {
            when (it.split) {
                WHITESPACE -> false
                NOT_WHITESPACE -> true
                else -> anyNotWhitespace(it.text)
            }
        }
        if (containsNonspace) {
            for (chars in pending) {
                check(fosterParentInBody(chars) == Pr.Done) { "not prepared to handle this!" }
            }
        } else {
            for (chars in pending) appendText(chars.text)
        }
        val orig = origMode
        origMode = -1
        return Pr.Reprocess(orig, token)
    }

    private fun stepInCaption(token: TbToken): Pr {
        if (token is TbToken.TagTok) {
            val tag = token.tag
            val closesCaption = if (tag.isStart) {
                tag.name in CAPTION_CLOSERS
            } else {
                tag.name == "table" || tag.name == "caption"
            }
            if (closesCaption) {
                if (inScopeNamed(TagSets.tableScope, "caption")) {
                    generateImpliedEndTags(TagSets.cursoryImpliedEnd)
                    expectToClose("caption")
                    clearActiveFormattingToMarker()
                    if (!tag.isStart && tag.name == "caption") {
                        this.mode = IN_TABLE
                        return Pr.Done
                    }
                    return Pr.Reprocess(IN_TABLE, token)
                }
                return unexpected()
            }
            if (!tag.isStart && tag.name in CAPTION_IGNORED_END_TAGS) return unexpected()
        }
        return step(IN_BODY, token)
    }

    private fun stepInColumnGroup(token: TbToken): Pr {
        if (token is TbToken.Chars) {
            if (token.split == NOT_SPLIT) return Pr.SplitWhitespace(token.text)
            if (token.split == WHITESPACE) return appendText(token.text)
        }
        if (token is TbToken.CommentTok) return appendComment(token.text)
        if (token is TbToken.TagTok) {
            val tag = token.tag
            if (tag.isStart) {
                when (tag.name) {
                    "html" -> return step(IN_BODY, token)
                    "col" -> {
                        insertAndPopElementFor(tag)
                        return Pr.DoneAckSelfClosing
                    }
                    "template" -> return step(IN_HEAD, token)
                }
            } else {
                when (tag.name) {
                    "colgroup" -> {
                        if (currentNodeNamed("colgroup")) {
                            pop()
                            this.mode = IN_TABLE
                        }
                        return Pr.Done
                    }
                    "col" -> return unexpected()
                    "template" -> return step(IN_HEAD, token)
                }
            }
        }
        if (token === TbToken.Eof) return step(IN_BODY, token)
        if (currentNodeNamed("colgroup")) {
            pop()
            return Pr.Reprocess(IN_TABLE, token)
        }
        return unexpected()
    }

    private fun stepInTableBody(token: TbToken): Pr {
        if (token is TbToken.TagTok) {
            val tag = token.tag
            if (tag.isStart) {
                when (tag.name) {
                    "tr" -> {
                        popUntilCurrent(TagSets.tableBodyContext)
                        insertElementFor(tag)
                        this.mode = IN_ROW
                        return Pr.Done
                    }
                    "th", "td" -> {
                        popUntilCurrent(TagSets.tableBodyContext)
                        insertPhantom("tr")
                        return Pr.Reprocess(IN_ROW, token)
                    }
                    "caption", "col", "colgroup", "tbody", "tfoot", "thead" -> return tableBodyToTable(token)
                }
            } else {
                when (tag.name) {
                    "tbody", "tfoot", "thead" -> {
                        if (inScopeNamed(TagSets.tableScope, tag.name)) {
                            popUntilCurrent(TagSets.tableBodyContext)
                            pop()
                            this.mode = IN_TABLE
                        }
                        return Pr.Done
                    }
                    "table" -> return tableBodyToTable(token)
                    "body", "caption", "col", "colgroup", "html", "td", "th", "tr" -> return unexpected()
                }
            }
        }
        return step(IN_TABLE, token)
    }

    private fun tableBodyToTable(token: TbToken): Pr {
        if (inScope(TagSets.tableScope) { elemIn(it, TagSets.tableOuterBody) }) {
            popUntilCurrent(TagSets.tableBodyContext)
            pop()
            return Pr.Reprocess(IN_TABLE, token)
        }
        return unexpected()
    }

    private fun stepInRow(token: TbToken): Pr {
        if (token is TbToken.TagTok) {
            val tag = token.tag
            if (tag.isStart) {
                when (tag.name) {
                    "th", "td" -> {
                        popUntilCurrent(TagSets.tableRowContext)
                        insertElementFor(tag)
                        this.mode = IN_CELL
                        activeFormatting.add(FormatEntry.MARKER)
                        return Pr.Done
                    }
                    "caption", "col", "colgroup", "tbody", "tfoot", "thead", "tr" -> return rowToTableBody(token)
                }
            } else {
                when (tag.name) {
                    "tr" -> {
                        if (inScopeNamed(TagSets.tableScope, "tr")) {
                            popUntilCurrent(TagSets.tableRowContext)
                            val node = pop()
                            check(htmlElemNamed(node, "tr"))
                            this.mode = IN_TABLE_BODY
                        }
                        return Pr.Done
                    }
                    "table" -> return rowToTableBody(token)
                    "tbody", "tfoot", "thead" -> {
                        if (inScopeNamed(TagSets.tableScope, tag.name)) {
                            if (inScopeNamed(TagSets.tableScope, "tr")) {
                                popUntilCurrent(TagSets.tableRowContext)
                                val node = pop()
                                check(htmlElemNamed(node, "tr"))
                                return Pr.Reprocess(IN_TABLE_BODY, token)
                            }
                            return Pr.Done
                        }
                        return unexpected()
                    }
                    "body", "caption", "col", "colgroup", "html", "td", "th" -> return unexpected()
                }
            }
        }
        return step(IN_TABLE, token)
    }

    private fun rowToTableBody(token: TbToken): Pr {
        if (inScopeNamed(TagSets.tableScope, "tr")) {
            popUntilCurrent(TagSets.tableRowContext)
            val node = pop()
            check(htmlElemNamed(node, "tr"))
            return Pr.Reprocess(IN_TABLE_BODY, token)
        }
        return unexpected()
    }

    private fun stepInCell(token: TbToken): Pr {
        if (token is TbToken.TagTok) {
            val tag = token.tag
            if (tag.isStart) {
                if (tag.name in CELL_CLOSERS) {
                    if (inScope(TagSets.tableScope) { elemIn(it, TagSets.tdTh) }) {
                        closeTheCell()
                        return Pr.Reprocess(IN_ROW, token)
                    }
                    return unexpected()
                }
            } else {
                when (tag.name) {
                    "td", "th" -> {
                        if (inScopeNamed(TagSets.tableScope, tag.name)) {
                            generateImpliedEndTags(TagSets.cursoryImpliedEnd)
                            expectToClose(tag.name)
                            clearActiveFormattingToMarker()
                            this.mode = IN_ROW
                        }
                        return Pr.Done
                    }
                    "body", "caption", "col", "colgroup", "html" -> return unexpected()
                    "table", "tbody", "tfoot", "thead", "tr" -> {
                        if (inScopeNamed(TagSets.tableScope, tag.name)) {
                            closeTheCell()
                            return Pr.Reprocess(IN_ROW, token)
                        }
                        return unexpected()
                    }
                }
            }
        }
        return step(IN_BODY, token)
    }

    @Suppress("CyclomaticComplexMethod")
    private fun stepInSelect(token: TbToken): Pr {
        when (token) {
            TbToken.NullChar -> return unexpected()
            is TbToken.Chars -> return appendText(token.text)
            is TbToken.CommentTok -> return appendComment(token.text)
            TbToken.Eof -> return step(IN_BODY, token)
            is TbToken.TagTok -> {
                val tag = token.tag
                if (tag.isStart) {
                    when (tag.name) {
                        "html" -> return step(IN_BODY, token)
                        "option" -> {
                            if (currentNodeNamed("option")) pop()
                            insertElementFor(tag)
                            return Pr.Done
                        }
                        "optgroup" -> {
                            if (currentNodeNamed("option")) pop()
                            if (currentNodeNamed("optgroup")) pop()
                            insertElementFor(tag)
                            return Pr.Done
                        }
                        "hr" -> {
                            if (currentNodeNamed("option")) pop()
                            if (currentNodeNamed("optgroup")) pop()
                            insertElementFor(tag)
                            pop()
                            return Pr.DoneAckSelfClosing
                        }
                        "select" -> return selectStartOrEnd()
                        "input", "keygen", "textarea" -> {
                            if (inScopeNamed(TagSets.selectScope, "select")) {
                                popUntilNamed("select")
                                return Pr.Reprocess(resetInsertionMode(), token)
                            }
                            return Pr.Done
                        }
                        "script", "template" -> return step(IN_HEAD, token)
                    }
                } else {
                    when (tag.name) {
                        "optgroup" -> {
                            if (openElems.size >= 2 && currentNodeNamed("option") && htmlElemNamed(openElems[openElems.size - 2], "optgroup")) {
                                pop()
                            }
                            if (currentNodeNamed("optgroup")) pop()
                            return Pr.Done
                        }
                        "option" -> {
                            if (currentNodeNamed("option")) pop()
                            return Pr.Done
                        }
                        "select" -> return selectStartOrEnd()
                        "template" -> return step(IN_HEAD, token)
                    }
                }
                return unexpected()
            }
        }
    }

    private fun selectStartOrEnd(): Pr {
        if (inScopeNamed(TagSets.selectScope, "select")) {
            popUntilNamed("select")
            this.mode = resetInsertionMode()
        }
        return Pr.Done
    }

    private fun stepInSelectInTable(token: TbToken): Pr {
        if (token is TbToken.TagTok) {
            val tag = token.tag
            if (tag.name in SELECT_IN_TABLE_CLOSERS) {
                if (tag.isStart) {
                    popUntilNamed("select")
                    return Pr.Reprocess(resetInsertionMode(), token)
                }
                if (inScopeNamed(TagSets.tableScope, tag.name)) {
                    popUntilNamed("select")
                    return Pr.Reprocess(resetInsertionMode(), token)
                }
                return Pr.Done
            }
        }
        return step(IN_SELECT, token)
    }

    private fun stepInTemplate(token: TbToken): Pr {
        when (token) {
            is TbToken.Chars, is TbToken.CommentTok -> return step(IN_BODY, token)
            TbToken.Eof -> {
                if (!inHtmlElemNamed("template")) return Pr.Done
                popUntilNamed("template")
                clearActiveFormattingToMarker()
                templateModes.removeLast()
                this.mode = resetInsertionMode()
                return Pr.Reprocess(resetInsertionMode(), token)
            }
            is TbToken.TagTok -> {
                val tag = token.tag
                if (tag.isStart) {
                    when (tag.name) {
                        "base", "basefont", "bgsound", "link", "meta", "noframes", "script", "style", "template", "title" ->
                            return step(IN_HEAD, token)
                        "caption", "colgroup", "tbody", "tfoot", "thead" -> return switchTemplateMode(IN_TABLE, token)
                        "col" -> return switchTemplateMode(IN_COLUMN_GROUP, token)
                        "tr" -> return switchTemplateMode(IN_TABLE_BODY, token)
                        "td", "th" -> return switchTemplateMode(IN_ROW, token)
                        else -> return switchTemplateMode(IN_BODY, token)
                    }
                }
                if (tag.name == "template") return step(IN_HEAD, token)
                return unexpected()
            }
            TbToken.NullChar -> return unexpected()
        }
    }

    private fun switchTemplateMode(newMode: Int, token: TbToken): Pr {
        templateModes.removeLast()
        templateModes.add(newMode)
        return Pr.Reprocess(newMode, token)
    }

    private fun stepAfterBody(token: TbToken): Pr {
        if (token is TbToken.Chars) {
            if (token.split == NOT_SPLIT) return Pr.SplitWhitespace(token.text)
            if (token.split == WHITESPACE) return step(IN_BODY, token)
        }
        if (token is TbToken.CommentTok) return appendCommentToHtml(token.text)
        if (token is TbToken.TagTok) {
            val tag = token.tag
            if (tag.isStart && tag.name == "html") return step(IN_BODY, token)
            if (!tag.isStart && tag.name == "html") {
                if (!isFragment()) this.mode = AFTER_AFTER_BODY
                return Pr.Done
            }
        }
        if (token === TbToken.Eof) return Pr.Done
        return Pr.Reprocess(IN_BODY, token)
    }

    private fun stepInFrameset(token: TbToken): Pr {
        if (token is TbToken.Chars) {
            if (token.split == NOT_SPLIT) return Pr.SplitWhitespace(token.text)
            if (token.split == WHITESPACE) return appendText(token.text)
        }
        if (token is TbToken.CommentTok) return appendComment(token.text)
        if (token is TbToken.TagTok) {
            val tag = token.tag
            if (tag.isStart) {
                when (tag.name) {
                    "html" -> return step(IN_BODY, token)
                    "frameset" -> {
                        insertElementFor(tag)
                        return Pr.Done
                    }
                    "frame" -> {
                        insertAndPopElementFor(tag)
                        return Pr.DoneAckSelfClosing
                    }
                    "noframes" -> return step(IN_HEAD, token)
                }
            } else if (tag.name == "frameset") {
                if (openElems.size != 1) {
                    pop()
                    if (!isFragment() && !currentNodeNamed("frameset")) this.mode = AFTER_FRAMESET
                }
                return Pr.Done
            }
        }
        if (token === TbToken.Eof) return Pr.Done
        return unexpected()
    }

    private fun stepAfterFrameset(token: TbToken): Pr {
        if (token is TbToken.Chars) {
            if (token.split == NOT_SPLIT) return Pr.SplitWhitespace(token.text)
            if (token.split == WHITESPACE) return appendText(token.text)
        }
        if (token is TbToken.CommentTok) return appendComment(token.text)
        if (token is TbToken.TagTok) {
            val tag = token.tag
            if (tag.isStart && tag.name == "html") return step(IN_BODY, token)
            if (!tag.isStart && tag.name == "html") {
                this.mode = AFTER_AFTER_FRAMESET
                return Pr.Done
            }
            if (tag.isStart && tag.name == "noframes") return step(IN_HEAD, token)
        }
        if (token === TbToken.Eof) return Pr.Done
        return unexpected()
    }

    private fun stepAfterAfterBody(token: TbToken): Pr {
        if (token is TbToken.Chars) {
            if (token.split == NOT_SPLIT) return Pr.SplitWhitespace(token.text)
            if (token.split == WHITESPACE) return step(IN_BODY, token)
        }
        if (token is TbToken.CommentTok) return appendCommentToDoc(token.text)
        if (token is TbToken.TagTok && token.tag.isStart && token.tag.name == "html") return step(IN_BODY, token)
        if (token === TbToken.Eof) return Pr.Done
        return Pr.Reprocess(IN_BODY, token)
    }

    private fun stepAfterAfterFrameset(token: TbToken): Pr {
        if (token is TbToken.Chars) {
            if (token.split == NOT_SPLIT) return Pr.SplitWhitespace(token.text)
            if (token.split == WHITESPACE) return step(IN_BODY, token)
        }
        if (token is TbToken.CommentTok) return appendCommentToDoc(token.text)
        if (token is TbToken.TagTok && token.tag.isStart && token.tag.name == "html") return step(IN_BODY, token)
        if (token === TbToken.Eof) return Pr.Done
        if (token is TbToken.TagTok && token.tag.isStart && token.tag.name == "noframes") return step(IN_HEAD, token)
        return unexpected()
    }

    @Suppress("CyclomaticComplexMethod")
    private fun stepForeign(token: TbToken): Pr {
        when (token) {
            TbToken.NullChar -> return appendText("�")
            is TbToken.Chars -> {
                if (anyNotWhitespace(token.text)) framesetOk = false
                return appendText(token.text)
            }
            is TbToken.CommentTok -> return appendComment(token.text)
            TbToken.Eof -> error("impossible case in foreign content")
            is TbToken.TagTok -> {
                val tag = token.tag
                if (tag.isStart) {
                    if (tag.name in FOREIGN_BREAKOUT) return unexpectedStartTagInForeignContent(tag)
                    if (tag.name == "font") {
                        val breaks = tag.attrs.any {
                            it.name.ns == Ns.NONE && it.name.prefix == null &&
                                (it.name.local == "color" || it.name.local == "face" || it.name.local == "size")
                        }
                        return if (breaks) unexpectedStartTagInForeignContent(tag) else foreignStartTag(tag)
                    }
                    return foreignStartTag(tag)
                }
                if (tag.name == "br" || tag.name == "p") return unexpectedStartTagInForeignContent(tag)
                var first = true
                var stackIdx = openElems.size - 1
                while (true) {
                    if (stackIdx == 0) return Pr.Done
                    val nodeName = sink.elemName(openElems[stackIdx])
                    val html = nodeName.ns == Ns.HTML
                    val eq = asciiEqualsIgnoreCase(nodeName.local, tag.name)
                    if (!first && html) return step(mode, token)
                    if (eq) {
                        openElems.truncate(stackIdx)
                        return Pr.Done
                    }
                    if (first) first = false
                    stackIdx--
                }
            }
        }
    }

    companion object {
        const val INITIAL = 0
        const val BEFORE_HTML = 1
        const val BEFORE_HEAD = 2
        const val IN_HEAD = 3
        const val IN_HEAD_NOSCRIPT = 4
        const val AFTER_HEAD = 5
        const val IN_BODY = 6
        const val TEXT = 7
        const val IN_TABLE = 8
        const val IN_TABLE_TEXT = 9
        const val IN_CAPTION = 10
        const val IN_COLUMN_GROUP = 11
        const val IN_TABLE_BODY = 12
        const val IN_ROW = 13
        const val IN_CELL = 14
        const val IN_SELECT = 15
        const val IN_SELECT_IN_TABLE = 16
        const val IN_TEMPLATE = 17
        const val AFTER_BODY = 18
        const val IN_FRAMESET = 19
        const val AFTER_FRAMESET = 20
        const val AFTER_AFTER_BODY = 21
        const val AFTER_AFTER_FRAMESET = 22

        const val NOT_SPLIT = 0
        const val WHITESPACE = 1
        const val NOT_WHITESPACE = 2

        private val HEAD_BODY_HTML_BR = hashSetOf("head", "body", "html", "br")
        private val CAPTION_CLOSERS = hashSetOf("caption", "col", "colgroup", "tbody", "td", "tfoot", "th", "thead", "tr")
        private val CAPTION_IGNORED_END_TAGS =
            hashSetOf("body", "col", "colgroup", "html", "tbody", "td", "tfoot", "th", "thead", "tr")
        private val CELL_CLOSERS = hashSetOf("caption", "col", "colgroup", "tbody", "td", "tfoot", "th", "thead", "tr")
        private val SELECT_IN_TABLE_CLOSERS = hashSetOf("caption", "table", "tbody", "tfoot", "thead", "tr", "td", "th")
        private val FOREIGN_BREAKOUT = hashSetOf(
            "b", "big", "blockquote", "body", "br", "center", "code", "dd", "div", "dl", "dt", "em", "embed", "h1", "h2",
            "h3", "h4", "h5", "h6", "head", "hr", "i", "img", "li", "listing", "menu", "meta", "nobr", "ol", "p", "pre",
            "ruby", "s", "small", "span", "strong", "strike", "sub", "sup", "table", "tt", "u", "ul", "var",
        )

        private val SVG_TAG_NAMES = listOf(
            "altGlyph", "altGlyphDef", "altGlyphItem", "animateColor", "animateMotion", "animateTransform", "clipPath",
            "feBlend", "feColorMatrix", "feComponentTransfer", "feComposite", "feConvolveMatrix", "feDiffuseLighting",
            "feDisplacementMap", "feDistantLight", "feDropShadow", "feFlood", "feFuncA", "feFuncB", "feFuncG", "feFuncR",
            "feGaussianBlur", "feImage", "feMerge", "feMergeNode", "feMorphology", "feOffset", "fePointLight",
            "feSpecularLighting", "feSpotLight", "feTile", "feTurbulence", "foreignObject", "glyphRef", "linearGradient",
            "radialGradient", "textPath",
        ).associateBy { it.lowercase() }

        private val SVG_ATTRIBUTES = listOf(
            "attributeName", "attributeType", "baseFrequency", "baseProfile", "calcMode", "clipPathUnits",
            "diffuseConstant", "edgeMode", "filterUnits", "glyphRef", "gradientTransform", "gradientUnits", "kernelMatrix",
            "kernelUnitLength", "keyPoints", "keySplines", "keyTimes", "lengthAdjust", "limitingConeAngle", "markerHeight",
            "markerUnits", "markerWidth", "maskContentUnits", "maskUnits", "numOctaves", "pathLength",
            "patternContentUnits", "patternTransform", "patternUnits", "pointsAtX", "pointsAtY", "pointsAtZ",
            "preserveAlpha", "preserveAspectRatio", "primitiveUnits", "refX", "refY", "repeatCount", "repeatDur",
            "requiredExtensions", "requiredFeatures", "specularConstant", "specularExponent", "spreadMethod",
            "startOffset", "stdDeviation", "stitchTiles", "surfaceScale", "systemLanguage", "tableValues", "targetX",
            "targetY", "textLength", "viewBox", "viewTarget", "xChannelSelector", "yChannelSelector", "zoomAndPan",
        ).associateBy { it.lowercase() }

        /**
         * `adjust_foreign_attributes`. html5ever's `qualname!("" xmlns "xmlns")` gives `xmlns` an
         * empty (not absent) prefix, so it serializes as `:xmlns`, and so does this.
         */
        private val FOREIGN_ATTRIBUTES = mapOf(
            "xlink:actuate" to QualName("xlink", Ns.XLINK, "actuate"),
            "xlink:arcrole" to QualName("xlink", Ns.XLINK, "arcrole"),
            "xlink:href" to QualName("xlink", Ns.XLINK, "href"),
            "xlink:role" to QualName("xlink", Ns.XLINK, "role"),
            "xlink:show" to QualName("xlink", Ns.XLINK, "show"),
            "xlink:title" to QualName("xlink", Ns.XLINK, "title"),
            "xlink:type" to QualName("xlink", Ns.XLINK, "type"),
            "xml:lang" to QualName("xml", Ns.XML, "lang"),
            "xml:space" to QualName("xml", Ns.XML, "space"),
            "xmlns" to QualName("", Ns.XMLNS, "xmlns"),
            "xmlns:xlink" to QualName("xmlns", Ns.XMLNS, "xlink"),
        )

        private fun isAsciiWhitespace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\n' || c == '\u000c' || c == '\r'

        private fun anyNotWhitespace(text: String): Boolean {
            for (c in text) if (!isAsciiWhitespace(c)) return true
            return false
        }

        private fun asciiEqualsIgnoreCase(a: String, b: String): Boolean {
            if (a.length != b.length) return false
            for (i in a.indices) {
                var x = a[i]
                var y = b[i]
                if (x in 'A'..'Z') x += 32
                if (y in 'A'..'Z') y += 32
                if (x != y) return false
            }
            return true
        }
    }
}
