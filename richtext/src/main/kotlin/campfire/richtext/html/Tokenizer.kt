package campfire.richtext.html

import campfire.richtext.Ns
import campfire.richtext.QualName

/**
 * html5ever 0.35's HTML tokenizer (`once-campfire-rust/crates/richtext/vendor/html5ever/src/tokenizer/mod.rs`),
 * ported state for state so that it emits the same tokens, parse errors included, at the same
 * points: the tree builder's handling of a newline after `<pre>` depends on which token comes
 * next, and Gumbo's tree depth limit is checked after every token.
 *
 * The vendored copy's attribute limit is kept: once a tag has [maxAttributes] attributes, starting
 * another sets [tooManyAttributes] and attributes past the limit are dropped without the
 * duplicate scan, as Gumbo's tokenizer does. Scripting is off and errors are inexact, as
 * `dom.rs` configures html5ever, and the data state scans runs the way html5ever's SIMD fast path
 * does (newlines inside a run, rather than as tokens of their own).
 */
internal class Tokenizer(
    private val sink: TokenSink,
    initialState: Int,
    initialKind: Int,
    private val maxAttributes: Int,
) {
    private var state = initialState
    private var kind = initialKind
    private var atEof = false
    private var charRef: CharRefTokenizer? = null
    private var currentChar = 0
    private var reconsume = false
    private var ignoreLf = false
    private var tagIsStart = true
    private val tagName = StringBuilder()
    private var tagSelfClosing = false
    private var tagAttrs = ArrayList<TokenAttr>()
    private val attrName = StringBuilder()
    private val attrValue = StringBuilder()
    private val commentBuf = StringBuilder()
    private var lastStartTagName: String? = null
    private val tempBuf = StringBuilder()
    private val setResult = SetResult()

    /** Whether a tag started more attributes than [maxAttributes] (`too_many_attributes`). */
    var tooManyAttributes = false
        private set

    /** `Tokenizer::feed`: runs the state machine over [input] until it needs more. */
    fun feed(input: BufferQueue) {
        if (input.isEmpty()) return
        run(input)
    }

    private fun run(input: BufferQueue) {
        while (step(input) == CONTINUE) Unit
    }

    /** `Tokenizer::end`: the end of the input, with any pending lookahead given up. */
    fun end() {
        val input = BufferQueue()
        val pending = charRef
        if (pending != null) {
            charRef = null
            pending.endOfFile(this, input)
            processCharRef(pending.result!!)
        }
        atEof = true
        run(input)
        while (eofStep() == CONTINUE) Unit
        sink.end()
    }

    private fun atMaxAttributes(): Boolean = tagAttrs.size >= maxAttributes

    private fun preprocessedChar(first: Int, input: BufferQueue): Int {
        var c = first
        if (ignoreLf) {
            ignoreLf = false
            if (c == '\n'.code) {
                c = input.next()
                if (c < 0) return -1
            }
        }
        if (c == '\r'.code) {
            ignoreLf = true
            c = '\n'.code
        }
        currentChar = c
        return c
    }

    /** `get_char`: the next preprocessed character, or -1 when the input has run out. */
    internal fun getChar(input: BufferQueue): Int {
        if (reconsume) {
            reconsume = false
            return currentChar
        }
        val c = input.next()
        if (c < 0) return -1
        return preprocessedChar(c, input)
    }

    /**
     * `pop_except_from`: fills [setResult] with a run outside [set] or a single character, and
     * returns false when the input has run out.
     */
    private fun popExceptFrom(input: BufferQueue, set: SmallCharSet): Boolean {
        if (reconsume || ignoreLf) {
            val c = getChar(input)
            if (c < 0) return false
            setResult.run = null
            setResult.char = c
            return true
        }
        if (!input.popExceptFrom(set, setResult)) return false
        if (setResult.run == null) {
            val c = preprocessedChar(setResult.char, input)
            if (c < 0) return false
            setResult.char = c
        }
        return true
    }

    /** `eat`: whether the input starts with [pattern]; null to suspend for more input. */
    private fun eat(input: BufferQueue, pattern: String, ignoreCase: Boolean): Boolean? {
        if (ignoreLf) {
            ignoreLf = false
            if (peek(input) == '\n'.code) discardChar(input)
        }
        input.pushFront(tempBuf)
        tempBuf.setLength(0)
        val matched = input.eat(pattern, ignoreCase)
        if (matched == null) {
            if (atEof) return false
            input.drainTo(tempBuf)
            return null
        }
        return matched
    }

    /** `peek`: the next raw character (or the one to reconsume), or -1. */
    internal fun peek(input: BufferQueue): Int = if (reconsume) currentChar else input.peek()

    /** `discard_char`: drops one raw input character, or the one to reconsume. */
    internal fun discardChar(input: BufferQueue) {
        if (reconsume) reconsume = false else input.next()
    }

    /** Clears the CR-LF skip, as a named character reference does when it pushes input back. */
    internal fun clearIgnoreLf() {
        ignoreLf = false
    }

    /** Whether a character reference is being consumed inside an attribute value. */
    private fun inAttributeValue(): Boolean = state == ATTRIBUTE_VALUE

    internal fun emitError() = sink.parseError()

    private fun emitChar(c: Int) {
        if (c == 0) sink.nullCharacter() else sink.characters(charString(c))
    }

    private fun emitChars(s: String) = sink.characters(s)

    private fun charString(c: Int): String = if (c < 128) ASCII[c] else c.toChar().toString()

    private fun emitCurrentTag(): Int {
        finishAttribute()
        val name = tagName.toString()
        tagName.setLength(0)
        if (tagIsStart) {
            lastStartTagName = name
        } else {
            if (tagAttrs.isNotEmpty()) emitError()
            if (tagSelfClosing) emitError()
        }
        val tag = Tag(tagIsStart, name, tagSelfClosing, tagAttrs)
        tagAttrs = ArrayList()
        when (val result = sink.tag(tag)) {
            SinkResult.CONTINUE -> Unit
            SinkResult.PLAINTEXT -> state = PLAINTEXT
            SinkResult.SCRIPT -> state = DATA
            else -> {
                state = RAW_DATA
                kind = result
            }
        }
        return CONTINUE
    }

    private fun emitTempBuf() {
        val s = tempBuf.toString()
        tempBuf.setLength(0)
        emitChars(s)
    }

    private fun emitCurrentComment() {
        val s = commentBuf.toString()
        commentBuf.setLength(0)
        sink.comment(s)
    }

    private fun discardTag() {
        tagName.setLength(0)
        tagSelfClosing = false
        tagAttrs = ArrayList()
    }

    private fun createTag(start: Boolean, c: Int) {
        discardTag()
        tagName.append(c.toChar())
        tagIsStart = start
    }

    private fun haveAppropriateEndTag(): Boolean {
        val last = lastStartTagName ?: return false
        return !tagIsStart && tagName.length == last.length && tagName.toString() == last
    }

    private fun createAttribute(c: Int) {
        finishAttribute()
        if (atMaxAttributes()) tooManyAttributes = true
        attrName.append(c.toChar())
    }

    private fun finishAttribute() {
        if (attrName.isEmpty()) return
        if (atMaxAttributes()) {
            attrName.setLength(0)
            attrValue.setLength(0)
            return
        }
        val name = attrName.toString()
        var dup = false
        for (a in tagAttrs) {
            if (a.name.local == name) {
                dup = true
                break
            }
        }
        if (dup) {
            emitError()
            attrName.setLength(0)
            attrValue.setLength(0)
        } else {
            attrName.setLength(0)
            tagAttrs.add(TokenAttr(QualName(null, Ns.NONE, name), attrValue.toString()))
            attrValue.setLength(0)
        }
    }

    private fun emitDoctype() = sink.doctype()

    private fun consumeCharRef() {
        charRef = CharRefTokenizer(inAttributeValue())
    }

    private fun emitEof() = sink.eof()

    private fun to(s: Int, k: Int = kind): Int {
        state = s
        kind = k
        return CONTINUE
    }

    private fun reconsumeIn(s: Int, k: Int = kind): Int {
        reconsume = true
        return to(s, k)
    }

    private fun emitTag(s: Int): Int {
        state = s
        return emitCurrentTag()
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun step(input: BufferQueue): Int {
        if (charRef != null) return stepCharRef(input)
        when (state) {
            DATA -> while (true) {
                val ok = if (!reconsume && !ignoreLf) {
                    val first = input.peek()
                    if (first < 0) return SUSPEND
                    if (first == '\r'.code || first == 0 || first == '&'.code || first == '<'.code || first == '\n'.code) {
                        popExceptFrom(input, DATA_SET)
                    } else {
                        setResult.run = input.popDataRun()
                        setResult.char = -1
                        true
                    }
                } else {
                    popExceptFrom(input, DATA_SET)
                }
                if (!ok) return SUSPEND
                val run = setResult.run
                if (run != null) {
                    emitChars(run)
                    continue
                }
                when (val c = setResult.char) {
                    0 -> {
                        emitError()
                        emitChar(0)
                    }
                    '&'.code -> {
                        consumeCharRef()
                        return CONTINUE
                    }
                    '<'.code -> return to(TAG_OPEN)
                    else -> emitChar(c)
                }
            }

            RAW_DATA -> when (kind) {
                RawKind.RCDATA -> while (true) {
                    if (!popExceptFrom(input, RCDATA_SET)) return SUSPEND
                    val run = setResult.run
                    if (run != null) {
                        emitChars(run)
                        continue
                    }
                    when (val c = setResult.char) {
                        0 -> {
                            emitError()
                            emitChar(0xfffd)
                        }
                        '&'.code -> {
                            consumeCharRef()
                            return CONTINUE
                        }
                        '<'.code -> return to(RAW_LESS_THAN_SIGN, RawKind.RCDATA)
                        else -> emitChar(c)
                    }
                }
                RawKind.RAWTEXT, RawKind.SCRIPT_DATA -> while (true) {
                    if (!popExceptFrom(input, RAWTEXT_SET)) return SUSPEND
                    val run = setResult.run
                    if (run != null) {
                        emitChars(run)
                        continue
                    }
                    when (val c = setResult.char) {
                        0 -> {
                            emitError()
                            emitChar(0xfffd)
                        }
                        '<'.code -> return to(RAW_LESS_THAN_SIGN, kind)
                        else -> emitChar(c)
                    }
                }
                RawKind.SCRIPT_DATA_ESCAPED -> while (true) {
                    if (!popExceptFrom(input, SCRIPT_ESCAPED_SET)) return SUSPEND
                    val run = setResult.run
                    if (run != null) {
                        emitChars(run)
                        continue
                    }
                    when (val c = setResult.char) {
                        0 -> {
                            emitError()
                            emitChar(0xfffd)
                        }
                        '-'.code -> {
                            emitChar('-'.code)
                            return to(SCRIPT_DATA_ESCAPED_DASH, RawKind.SCRIPT_DATA_ESCAPED)
                        }
                        '<'.code -> return to(RAW_LESS_THAN_SIGN, RawKind.SCRIPT_DATA_ESCAPED)
                        else -> emitChar(c)
                    }
                }
                else -> while (true) {
                    if (!popExceptFrom(input, SCRIPT_ESCAPED_SET)) return SUSPEND
                    val run = setResult.run
                    if (run != null) {
                        emitChars(run)
                        continue
                    }
                    when (val c = setResult.char) {
                        0 -> {
                            emitError()
                            emitChar(0xfffd)
                        }
                        '-'.code -> {
                            emitChar('-'.code)
                            return to(SCRIPT_DATA_ESCAPED_DASH, RawKind.SCRIPT_DATA_DOUBLE_ESCAPED)
                        }
                        '<'.code -> {
                            emitChar('<'.code)
                            return to(RAW_LESS_THAN_SIGN, RawKind.SCRIPT_DATA_DOUBLE_ESCAPED)
                        }
                        else -> emitChar(c)
                    }
                }
            }

            PLAINTEXT -> while (true) {
                if (!popExceptFrom(input, PLAINTEXT_SET)) return SUSPEND
                val run = setResult.run
                if (run != null) {
                    emitChars(run)
                    continue
                }
                val c = setResult.char
                if (c == 0) {
                    emitError()
                    emitChar(0xfffd)
                } else {
                    emitChar(c)
                }
            }

            TAG_OPEN -> {
                val c = getChar(input)
                if (c < 0) return SUSPEND
                return when (c) {
                    '!'.code -> to(MARKUP_DECLARATION_OPEN)
                    '/'.code -> to(END_TAG_OPEN)
                    '?'.code -> {
                        emitError()
                        commentBuf.setLength(0)
                        reconsumeIn(BOGUS_COMMENT)
                    }
                    else -> {
                        val cl = lowerAsciiLetter(c)
                        if (cl >= 0) {
                            createTag(true, cl)
                            to(TAG_NAME)
                        } else {
                            emitError()
                            emitChar('<'.code)
                            reconsumeIn(DATA)
                        }
                    }
                }
            }

            END_TAG_OPEN -> {
                val c = getChar(input)
                if (c < 0) return SUSPEND
                if (c == '>'.code) {
                    emitError()
                    return to(DATA)
                }
                val cl = lowerAsciiLetter(c)
                return if (cl >= 0) {
                    createTag(false, cl)
                    to(TAG_NAME)
                } else {
                    emitError()
                    commentBuf.setLength(0)
                    reconsumeIn(BOGUS_COMMENT)
                }
            }

            TAG_NAME -> while (true) {
                val c = getChar(input)
                if (c < 0) return SUSPEND
                when (c) {
                    '\t'.code, '\n'.code, 0x0c, ' '.code -> return to(BEFORE_ATTRIBUTE_NAME)
                    '/'.code -> return to(SELF_CLOSING_START_TAG)
                    '>'.code -> return emitTag(DATA)
                    0 -> {
                        emitError()
                        tagName.append('�')
                    }
                    else -> tagName.append(asciiLower(c).toChar())
                }
            }

            RAW_LESS_THAN_SIGN -> return rawLessThanSign(input)

            RAW_END_TAG_OPEN -> {
                val c = getChar(input)
                if (c < 0) return SUSPEND
                val cl = lowerAsciiLetter(c)
                return if (cl >= 0) {
                    createTag(false, cl)
                    tempBuf.append(c.toChar())
                    to(RAW_END_TAG_NAME)
                } else {
                    emitChar('<'.code)
                    emitChar('/'.code)
                    reconsumeIn(RAW_DATA)
                }
            }

            RAW_END_TAG_NAME -> while (true) {
                val c = getChar(input)
                if (c < 0) return SUSPEND
                if (haveAppropriateEndTag()) {
                    when (c) {
                        '\t'.code, '\n'.code, 0x0c, ' '.code -> {
                            tempBuf.setLength(0)
                            return to(BEFORE_ATTRIBUTE_NAME)
                        }
                        '/'.code -> {
                            tempBuf.setLength(0)
                            return to(SELF_CLOSING_START_TAG)
                        }
                        '>'.code -> {
                            tempBuf.setLength(0)
                            return emitTag(DATA)
                        }
                    }
                }
                val cl = lowerAsciiLetter(c)
                if (cl >= 0) {
                    tagName.append(cl.toChar())
                    tempBuf.append(c.toChar())
                } else {
                    discardTag()
                    emitChar('<'.code)
                    emitChar('/'.code)
                    emitTempBuf()
                    return reconsumeIn(RAW_DATA)
                }
            }

            SCRIPT_DATA_ESCAPE_START -> {
                if (kind == RawKind.SCRIPT_DATA_DOUBLE_ESCAPED) {
                    while (true) {
                        val c = getChar(input)
                        if (c < 0) return SUSPEND
                        when (c) {
                            '\t'.code, '\n'.code, 0x0c, ' '.code, '/'.code, '>'.code -> {
                                val esc = if (tempBuf.contentEquals("script")) {
                                    RawKind.SCRIPT_DATA_DOUBLE_ESCAPED
                                } else {
                                    RawKind.SCRIPT_DATA_ESCAPED
                                }
                                emitChar(c)
                                return to(RAW_DATA, esc)
                            }
                            else -> {
                                val cl = lowerAsciiLetter(c)
                                if (cl >= 0) {
                                    tempBuf.append(cl.toChar())
                                    emitChar(c)
                                } else {
                                    return reconsumeIn(RAW_DATA, RawKind.SCRIPT_DATA_ESCAPED)
                                }
                            }
                        }
                    }
                } else {
                    val c = getChar(input)
                    if (c < 0) return SUSPEND
                    return if (c == '-'.code) {
                        emitChar('-'.code)
                        to(SCRIPT_DATA_ESCAPE_START_DASH)
                    } else {
                        reconsumeIn(RAW_DATA, RawKind.SCRIPT_DATA)
                    }
                }
            }

            SCRIPT_DATA_ESCAPE_START_DASH -> {
                val c = getChar(input)
                if (c < 0) return SUSPEND
                return if (c == '-'.code) {
                    emitChar('-'.code)
                    to(SCRIPT_DATA_ESCAPED_DASH_DASH, RawKind.SCRIPT_DATA_ESCAPED)
                } else {
                    reconsumeIn(RAW_DATA, RawKind.SCRIPT_DATA)
                }
            }

            SCRIPT_DATA_ESCAPED_DASH -> {
                val c = getChar(input)
                if (c < 0) return SUSPEND
                return when (c) {
                    '-'.code -> {
                        emitChar('-'.code)
                        to(SCRIPT_DATA_ESCAPED_DASH_DASH)
                    }
                    '<'.code -> {
                        if (kind == RawKind.SCRIPT_DATA_DOUBLE_ESCAPED) emitChar('<'.code)
                        to(RAW_LESS_THAN_SIGN)
                    }
                    0 -> {
                        emitError()
                        emitChar(0xfffd)
                        to(RAW_DATA)
                    }
                    else -> {
                        emitChar(c)
                        to(RAW_DATA)
                    }
                }
            }

            SCRIPT_DATA_ESCAPED_DASH_DASH -> while (true) {
                val c = getChar(input)
                if (c < 0) return SUSPEND
                when (c) {
                    '-'.code -> emitChar('-'.code)
                    '<'.code -> {
                        if (kind == RawKind.SCRIPT_DATA_DOUBLE_ESCAPED) emitChar('<'.code)
                        return to(RAW_LESS_THAN_SIGN)
                    }
                    '>'.code -> {
                        emitChar('>'.code)
                        return to(RAW_DATA, RawKind.SCRIPT_DATA)
                    }
                    0 -> {
                        emitError()
                        emitChar(0xfffd)
                        return to(RAW_DATA)
                    }
                    else -> {
                        emitChar(c)
                        return to(RAW_DATA)
                    }
                }
            }

            SCRIPT_DATA_DOUBLE_ESCAPE_END -> while (true) {
                val c = getChar(input)
                if (c < 0) return SUSPEND
                when (c) {
                    '\t'.code, '\n'.code, 0x0c, ' '.code, '/'.code, '>'.code -> {
                        val esc = if (tempBuf.contentEquals("script")) {
                            RawKind.SCRIPT_DATA_ESCAPED
                        } else {
                            RawKind.SCRIPT_DATA_DOUBLE_ESCAPED
                        }
                        emitChar(c)
                        return to(RAW_DATA, esc)
                    }
                    else -> {
                        val cl = lowerAsciiLetter(c)
                        if (cl >= 0) {
                            tempBuf.append(cl.toChar())
                            emitChar(c)
                        } else {
                            return reconsumeIn(RAW_DATA, RawKind.SCRIPT_DATA_DOUBLE_ESCAPED)
                        }
                    }
                }
            }

            BEFORE_ATTRIBUTE_NAME -> while (true) {
                val c = getChar(input)
                if (c < 0) return SUSPEND
                when (c) {
                    '\t'.code, '\n'.code, 0x0c, ' '.code -> Unit
                    '/'.code -> return to(SELF_CLOSING_START_TAG)
                    '>'.code -> return emitTag(DATA)
                    0 -> {
                        emitError()
                        createAttribute(0xfffd)
                        return to(ATTRIBUTE_NAME)
                    }
                    else -> {
                        val cl = lowerAsciiLetter(c)
                        if (cl >= 0) {
                            createAttribute(cl)
                        } else {
                            if (c == '"'.code || c == '\''.code || c == '<'.code || c == '='.code) emitError()
                            createAttribute(c)
                        }
                        return to(ATTRIBUTE_NAME)
                    }
                }
            }

            ATTRIBUTE_NAME -> while (true) {
                val c = getChar(input)
                if (c < 0) return SUSPEND
                when (c) {
                    '\t'.code, '\n'.code, 0x0c, ' '.code -> return to(AFTER_ATTRIBUTE_NAME)
                    '/'.code -> return to(SELF_CLOSING_START_TAG)
                    '='.code -> return to(BEFORE_ATTRIBUTE_VALUE)
                    '>'.code -> return emitTag(DATA)
                    0 -> {
                        emitError()
                        attrName.append('�')
                    }
                    else -> {
                        val cl = lowerAsciiLetter(c)
                        if (cl >= 0) {
                            attrName.append(cl.toChar())
                        } else {
                            if (c == '"'.code || c == '\''.code || c == '<'.code) emitError()
                            attrName.append(c.toChar())
                        }
                    }
                }
            }

            AFTER_ATTRIBUTE_NAME -> while (true) {
                val c = getChar(input)
                if (c < 0) return SUSPEND
                when (c) {
                    '\t'.code, '\n'.code, 0x0c, ' '.code -> Unit
                    '/'.code -> return to(SELF_CLOSING_START_TAG)
                    '='.code -> return to(BEFORE_ATTRIBUTE_VALUE)
                    '>'.code -> return emitTag(DATA)
                    0 -> {
                        emitError()
                        createAttribute(0xfffd)
                        return to(ATTRIBUTE_NAME)
                    }
                    else -> {
                        val cl = lowerAsciiLetter(c)
                        if (cl >= 0) {
                            createAttribute(cl)
                        } else {
                            if (c == '"'.code || c == '\''.code || c == '<'.code) emitError()
                            createAttribute(c)
                        }
                        return to(ATTRIBUTE_NAME)
                    }
                }
            }

            BEFORE_ATTRIBUTE_VALUE -> while (true) {
                val c = peek(input)
                if (c < 0) return SUSPEND
                when (c) {
                    '\t'.code, '\n'.code, '\r'.code, 0x0c, ' '.code -> discardChar(input)
                    '"'.code -> {
                        discardChar(input)
                        return to(ATTRIBUTE_VALUE, DOUBLE_QUOTED)
                    }
                    '\''.code -> {
                        discardChar(input)
                        return to(ATTRIBUTE_VALUE, SINGLE_QUOTED)
                    }
                    '>'.code -> {
                        discardChar(input)
                        emitError()
                        return emitTag(DATA)
                    }
                    else -> return to(ATTRIBUTE_VALUE, UNQUOTED)
                }
            }

            ATTRIBUTE_VALUE -> when (kind) {
                DOUBLE_QUOTED -> while (true) {
                    if (!popExceptFrom(input, DOUBLE_QUOTED_SET)) return SUSPEND
                    val run = setResult.run
                    if (run != null) {
                        attrValue.append(run)
                        continue
                    }
                    when (val c = setResult.char) {
                        '"'.code -> return to(AFTER_ATTRIBUTE_VALUE_QUOTED)
                        '&'.code -> {
                            consumeCharRef()
                            return CONTINUE
                        }
                        0 -> {
                            emitError()
                            attrValue.append('�')
                        }
                        else -> attrValue.append(c.toChar())
                    }
                }
                SINGLE_QUOTED -> while (true) {
                    if (!popExceptFrom(input, SINGLE_QUOTED_SET)) return SUSPEND
                    val run = setResult.run
                    if (run != null) {
                        attrValue.append(run)
                        continue
                    }
                    when (val c = setResult.char) {
                        '\''.code -> return to(AFTER_ATTRIBUTE_VALUE_QUOTED)
                        '&'.code -> {
                            consumeCharRef()
                            return CONTINUE
                        }
                        0 -> {
                            emitError()
                            attrValue.append('�')
                        }
                        else -> attrValue.append(c.toChar())
                    }
                }
                else -> while (true) {
                    if (!popExceptFrom(input, UNQUOTED_SET)) return SUSPEND
                    val run = setResult.run
                    if (run != null) {
                        attrValue.append(run)
                        continue
                    }
                    when (val c = setResult.char) {
                        '\t'.code, '\n'.code, 0x0c, ' '.code -> return to(BEFORE_ATTRIBUTE_NAME)
                        '&'.code -> {
                            consumeCharRef()
                            return CONTINUE
                        }
                        '>'.code -> return emitTag(DATA)
                        0 -> {
                            emitError()
                            attrValue.append('�')
                        }
                        else -> {
                            if (c == '"'.code || c == '\''.code || c == '<'.code || c == '='.code || c == '`'.code) emitError()
                            attrValue.append(c.toChar())
                        }
                    }
                }
            }

            AFTER_ATTRIBUTE_VALUE_QUOTED -> {
                val c = getChar(input)
                if (c < 0) return SUSPEND
                return when (c) {
                    '\t'.code, '\n'.code, 0x0c, ' '.code -> to(BEFORE_ATTRIBUTE_NAME)
                    '/'.code -> to(SELF_CLOSING_START_TAG)
                    '>'.code -> emitTag(DATA)
                    else -> {
                        emitError()
                        reconsumeIn(BEFORE_ATTRIBUTE_NAME)
                    }
                }
            }

            SELF_CLOSING_START_TAG -> {
                val c = getChar(input)
                if (c < 0) return SUSPEND
                return if (c == '>'.code) {
                    tagSelfClosing = true
                    emitTag(DATA)
                } else {
                    emitError()
                    reconsumeIn(BEFORE_ATTRIBUTE_NAME)
                }
            }

            COMMENT_START -> {
                val c = getChar(input)
                if (c < 0) return SUSPEND
                return when (c) {
                    '-'.code -> to(COMMENT_START_DASH)
                    0 -> {
                        emitError()
                        commentBuf.append('�')
                        to(COMMENT)
                    }
                    '>'.code -> {
                        emitError()
                        emitCurrentComment()
                        to(DATA)
                    }
                    else -> {
                        commentBuf.append(c.toChar())
                        to(COMMENT)
                    }
                }
            }

            COMMENT_START_DASH -> {
                val c = getChar(input)
                if (c < 0) return SUSPEND
                return when (c) {
                    '-'.code -> to(COMMENT_END)
                    0 -> {
                        emitError()
                        commentBuf.append("-�")
                        to(COMMENT)
                    }
                    '>'.code -> {
                        emitError()
                        emitCurrentComment()
                        to(DATA)
                    }
                    else -> {
                        commentBuf.append('-').append(c.toChar())
                        to(COMMENT)
                    }
                }
            }

            COMMENT -> while (true) {
                val c = getChar(input)
                if (c < 0) return SUSPEND
                when (c) {
                    '<'.code -> {
                        commentBuf.append('<')
                        return to(COMMENT_LESS_THAN_SIGN)
                    }
                    '-'.code -> return to(COMMENT_END_DASH)
                    0 -> {
                        emitError()
                        commentBuf.append('�')
                    }
                    else -> commentBuf.append(c.toChar())
                }
            }

            COMMENT_LESS_THAN_SIGN -> while (true) {
                val c = getChar(input)
                if (c < 0) return SUSPEND
                when (c) {
                    '!'.code -> {
                        commentBuf.append('!')
                        return to(COMMENT_LESS_THAN_SIGN_BANG)
                    }
                    '<'.code -> commentBuf.append('<')
                    else -> return reconsumeIn(COMMENT)
                }
            }

            COMMENT_LESS_THAN_SIGN_BANG -> {
                val c = getChar(input)
                if (c < 0) return SUSPEND
                return if (c == '-'.code) to(COMMENT_LESS_THAN_SIGN_BANG_DASH) else reconsumeIn(COMMENT)
            }

            COMMENT_LESS_THAN_SIGN_BANG_DASH -> {
                val c = getChar(input)
                if (c < 0) return SUSPEND
                return if (c == '-'.code) to(COMMENT_LESS_THAN_SIGN_BANG_DASH_DASH) else reconsumeIn(COMMENT_END_DASH)
            }

            COMMENT_LESS_THAN_SIGN_BANG_DASH_DASH -> {
                val c = getChar(input)
                if (c < 0) return SUSPEND
                if (c != '>'.code) emitError()
                return reconsumeIn(COMMENT_END)
            }

            COMMENT_END_DASH -> {
                val c = getChar(input)
                if (c < 0) return SUSPEND
                return when (c) {
                    '-'.code -> to(COMMENT_END)
                    0 -> {
                        emitError()
                        commentBuf.append("-�")
                        to(COMMENT)
                    }
                    else -> {
                        commentBuf.append('-').append(c.toChar())
                        to(COMMENT)
                    }
                }
            }

            COMMENT_END -> while (true) {
                val c = getChar(input)
                if (c < 0) return SUSPEND
                when (c) {
                    '>'.code -> {
                        emitCurrentComment()
                        return to(DATA)
                    }
                    '!'.code -> return to(COMMENT_END_BANG)
                    '-'.code -> commentBuf.append('-')
                    else -> {
                        commentBuf.append("--")
                        return reconsumeIn(COMMENT)
                    }
                }
            }

            COMMENT_END_BANG -> {
                val c = getChar(input)
                if (c < 0) return SUSPEND
                return when (c) {
                    '-'.code -> {
                        commentBuf.append("--!")
                        to(COMMENT_END_DASH)
                    }
                    '>'.code -> {
                        emitError()
                        emitCurrentComment()
                        to(DATA)
                    }
                    0 -> {
                        emitError()
                        commentBuf.append("--!�")
                        to(COMMENT)
                    }
                    else -> {
                        commentBuf.append("--!").append(c.toChar())
                        to(COMMENT)
                    }
                }
            }

            DOCTYPE -> {
                val c = getChar(input)
                if (c < 0) return SUSPEND
                return when (c) {
                    '\t'.code, '\n'.code, 0x0c, ' '.code -> to(BEFORE_DOCTYPE_NAME)
                    '>'.code -> reconsumeIn(BEFORE_DOCTYPE_NAME)
                    else -> {
                        emitError()
                        reconsumeIn(BEFORE_DOCTYPE_NAME)
                    }
                }
            }

            BEFORE_DOCTYPE_NAME -> while (true) {
                val c = getChar(input)
                if (c < 0) return SUSPEND
                when (c) {
                    '\t'.code, '\n'.code, 0x0c, ' '.code -> Unit
                    0 -> {
                        emitError()
                        return to(DOCTYPE_NAME)
                    }
                    '>'.code -> {
                        emitError()
                        emitDoctype()
                        return to(DATA)
                    }
                    else -> return to(DOCTYPE_NAME)
                }
            }

            DOCTYPE_NAME -> while (true) {
                val c = getChar(input)
                if (c < 0) return SUSPEND
                when (c) {
                    '\t'.code, '\n'.code, 0x0c, ' '.code -> {
                        tempBuf.setLength(0)
                        return to(AFTER_DOCTYPE_NAME)
                    }
                    '>'.code -> {
                        emitDoctype()
                        return to(DATA)
                    }
                    0 -> emitError()
                    else -> Unit
                }
            }

            AFTER_DOCTYPE_NAME -> while (true) {
                val public = eat(input, "public", true) ?: return SUSPEND
                if (public) return to(AFTER_DOCTYPE_KEYWORD, PUBLIC)
                val system = eat(input, "system", true) ?: return SUSPEND
                if (system) return to(AFTER_DOCTYPE_KEYWORD, SYSTEM)
                val c = getChar(input)
                if (c < 0) return SUSPEND
                when (c) {
                    '\t'.code, '\n'.code, 0x0c, ' '.code -> Unit
                    '>'.code -> {
                        emitDoctype()
                        return to(DATA)
                    }
                    else -> {
                        emitError()
                        return reconsumeIn(BOGUS_DOCTYPE)
                    }
                }
            }

            AFTER_DOCTYPE_KEYWORD -> {
                val c = getChar(input)
                if (c < 0) return SUSPEND
                return when (c) {
                    '\t'.code, '\n'.code, 0x0c, ' '.code -> to(BEFORE_DOCTYPE_IDENTIFIER)
                    '"'.code -> {
                        emitError()
                        to(DOCTYPE_IDENTIFIER_DOUBLE_QUOTED)
                    }
                    '\''.code -> {
                        emitError()
                        to(DOCTYPE_IDENTIFIER_SINGLE_QUOTED)
                    }
                    '>'.code -> {
                        emitError()
                        emitDoctype()
                        to(DATA)
                    }
                    else -> {
                        emitError()
                        reconsumeIn(BOGUS_DOCTYPE)
                    }
                }
            }

            BEFORE_DOCTYPE_IDENTIFIER -> while (true) {
                val c = getChar(input)
                if (c < 0) return SUSPEND
                when (c) {
                    '\t'.code, '\n'.code, 0x0c, ' '.code -> Unit
                    '"'.code -> return to(DOCTYPE_IDENTIFIER_DOUBLE_QUOTED)
                    '\''.code -> return to(DOCTYPE_IDENTIFIER_SINGLE_QUOTED)
                    '>'.code -> {
                        emitError()
                        emitDoctype()
                        return to(DATA)
                    }
                    else -> {
                        emitError()
                        return reconsumeIn(BOGUS_DOCTYPE)
                    }
                }
            }

            DOCTYPE_IDENTIFIER_DOUBLE_QUOTED -> while (true) {
                val c = getChar(input)
                if (c < 0) return SUSPEND
                when (c) {
                    '"'.code -> return to(AFTER_DOCTYPE_IDENTIFIER)
                    0 -> emitError()
                    '>'.code -> {
                        emitError()
                        emitDoctype()
                        return to(DATA)
                    }
                    else -> Unit
                }
            }

            DOCTYPE_IDENTIFIER_SINGLE_QUOTED -> while (true) {
                val c = getChar(input)
                if (c < 0) return SUSPEND
                when (c) {
                    '\''.code -> return to(AFTER_DOCTYPE_IDENTIFIER)
                    0 -> emitError()
                    '>'.code -> {
                        emitError()
                        emitDoctype()
                        return to(DATA)
                    }
                    else -> Unit
                }
            }

            AFTER_DOCTYPE_IDENTIFIER -> if (kind == PUBLIC) {
                val c = getChar(input)
                if (c < 0) return SUSPEND
                return when (c) {
                    '\t'.code, '\n'.code, 0x0c, ' '.code -> to(BETWEEN_DOCTYPE_PUBLIC_AND_SYSTEM_IDENTIFIERS)
                    '>'.code -> {
                        emitDoctype()
                        to(DATA)
                    }
                    '"'.code -> {
                        emitError()
                        to(DOCTYPE_IDENTIFIER_DOUBLE_QUOTED, SYSTEM)
                    }
                    '\''.code -> {
                        emitError()
                        to(DOCTYPE_IDENTIFIER_SINGLE_QUOTED, SYSTEM)
                    }
                    else -> {
                        emitError()
                        reconsumeIn(BOGUS_DOCTYPE)
                    }
                }
            } else {
                while (true) {
                    val c = getChar(input)
                    if (c < 0) return SUSPEND
                    when (c) {
                        '\t'.code, '\n'.code, 0x0c, ' '.code -> Unit
                        '>'.code -> {
                            emitDoctype()
                            return to(DATA)
                        }
                        else -> {
                            emitError()
                            return reconsumeIn(BOGUS_DOCTYPE)
                        }
                    }
                }
            }

            BETWEEN_DOCTYPE_PUBLIC_AND_SYSTEM_IDENTIFIERS -> while (true) {
                val c = getChar(input)
                if (c < 0) return SUSPEND
                when (c) {
                    '\t'.code, '\n'.code, 0x0c, ' '.code -> Unit
                    '>'.code -> {
                        emitDoctype()
                        return to(DATA)
                    }
                    '"'.code -> return to(DOCTYPE_IDENTIFIER_DOUBLE_QUOTED, SYSTEM)
                    '\''.code -> return to(DOCTYPE_IDENTIFIER_SINGLE_QUOTED, SYSTEM)
                    else -> {
                        emitError()
                        return reconsumeIn(BOGUS_DOCTYPE)
                    }
                }
            }

            BOGUS_DOCTYPE -> while (true) {
                val c = getChar(input)
                if (c < 0) return SUSPEND
                when (c) {
                    '>'.code -> {
                        emitDoctype()
                        return to(DATA)
                    }
                    0 -> emitError()
                    else -> Unit
                }
            }

            BOGUS_COMMENT -> while (true) {
                val c = getChar(input)
                if (c < 0) return SUSPEND
                when (c) {
                    '>'.code -> {
                        emitCurrentComment()
                        return to(DATA)
                    }
                    0 -> {
                        emitError()
                        commentBuf.append('�')
                    }
                    else -> commentBuf.append(c.toChar())
                }
            }

            MARKUP_DECLARATION_OPEN -> {
                val dashes = eat(input, "--", false) ?: return SUSPEND
                if (dashes) {
                    commentBuf.setLength(0)
                    return to(COMMENT_START)
                }
                val doctype = eat(input, "doctype", true) ?: return SUSPEND
                if (doctype) return to(DOCTYPE)
                if (sink.adjustedCurrentNodePresentButNotInHtmlNamespace()) {
                    val cdata = eat(input, "[CDATA[", false) ?: return SUSPEND
                    if (cdata) {
                        tempBuf.setLength(0)
                        return to(CDATA_SECTION)
                    }
                }
                emitError()
                commentBuf.setLength(0)
                return to(BOGUS_COMMENT)
            }

            CDATA_SECTION -> while (true) {
                val c = getChar(input)
                if (c < 0) return SUSPEND
                when (c) {
                    ']'.code -> return to(CDATA_SECTION_BRACKET)
                    0 -> {
                        emitTempBuf()
                        emitChar(0)
                    }
                    else -> tempBuf.append(c.toChar())
                }
            }

            CDATA_SECTION_BRACKET -> {
                val c = getChar(input)
                if (c < 0) return SUSPEND
                return if (c == ']'.code) {
                    to(CDATA_SECTION_END)
                } else {
                    tempBuf.append(']')
                    reconsumeIn(CDATA_SECTION)
                }
            }

            CDATA_SECTION_END -> while (true) {
                val c = getChar(input)
                if (c < 0) return SUSPEND
                when (c) {
                    ']'.code -> tempBuf.append(']')
                    '>'.code -> {
                        emitTempBuf()
                        return to(DATA)
                    }
                    else -> {
                        tempBuf.append("]]")
                        return reconsumeIn(CDATA_SECTION)
                    }
                }
            }
        }
        error("unknown tokenizer state $state")
    }

    /** The less-than-sign states of the raw text kinds, script data's escapes included. */
    private fun rawLessThanSign(input: BufferQueue): Int {
        val c = getChar(input)
        if (c < 0) return SUSPEND
        when (kind) {
            RawKind.SCRIPT_DATA_ESCAPED -> {
                if (c == '/'.code) {
                    tempBuf.setLength(0)
                    return to(RAW_END_TAG_OPEN, RawKind.SCRIPT_DATA_ESCAPED)
                }
                val cl = lowerAsciiLetter(c)
                return if (cl >= 0) {
                    tempBuf.setLength(0)
                    tempBuf.append(cl.toChar())
                    emitChar('<'.code)
                    emitChar(c)
                    to(SCRIPT_DATA_ESCAPE_START, RawKind.SCRIPT_DATA_DOUBLE_ESCAPED)
                } else {
                    emitChar('<'.code)
                    reconsumeIn(RAW_DATA, RawKind.SCRIPT_DATA_ESCAPED)
                }
            }
            RawKind.SCRIPT_DATA_DOUBLE_ESCAPED -> {
                return if (c == '/'.code) {
                    tempBuf.setLength(0)
                    emitChar('/'.code)
                    to(SCRIPT_DATA_DOUBLE_ESCAPE_END)
                } else {
                    reconsumeIn(RAW_DATA, RawKind.SCRIPT_DATA_DOUBLE_ESCAPED)
                }
            }
            else -> {
                if (c == '/'.code) {
                    tempBuf.setLength(0)
                    return to(RAW_END_TAG_OPEN)
                }
                if (c == '!'.code && kind == RawKind.SCRIPT_DATA) {
                    emitChar('<'.code)
                    emitChar('!'.code)
                    return to(SCRIPT_DATA_ESCAPE_START, RawKind.SCRIPT_DATA_ESCAPED)
                }
                emitChar('<'.code)
                return reconsumeIn(RAW_DATA)
            }
        }
    }

    private fun stepCharRef(input: BufferQueue): Int {
        val tok = charRef!!
        charRef = null
        return when (tok.step(this, input)) {
            CharRefTokenizer.DONE -> {
                processCharRef(tok.result!!)
                CONTINUE
            }
            CharRefTokenizer.STUCK -> {
                charRef = tok
                SUSPEND
            }
            else -> {
                charRef = tok
                CONTINUE
            }
        }
    }

    private fun processCharRef(chars: IntArray) {
        val list = if (chars.isEmpty()) intArrayOf('&'.code) else chars
        for (c in list) {
            when (state) {
                DATA -> emitCodePoint(c)
                RAW_DATA -> if (kind == RawKind.RCDATA) emitCodePoint(c) else error("char ref in raw data")
                ATTRIBUTE_VALUE -> attrValue.appendCodePoint(c)
                else -> error("state $state should not be reachable in process_char_ref")
            }
        }
    }

    private fun emitCodePoint(c: Int) {
        if (c < 0x10000) emitChar(c) else sink.characters(String(Character.toChars(c)))
    }

    private fun eofStep(): Int {
        when (state) {
            DATA, PLAINTEXT -> {
                emitEof()
                return SUSPEND
            }
            RAW_DATA -> {
                if (kind == RawKind.SCRIPT_DATA_ESCAPED || kind == RawKind.SCRIPT_DATA_DOUBLE_ESCAPED) {
                    emitError()
                    return to(DATA)
                }
                emitEof()
                return SUSPEND
            }
            TAG_NAME, BEFORE_ATTRIBUTE_NAME, ATTRIBUTE_NAME, AFTER_ATTRIBUTE_NAME, ATTRIBUTE_VALUE,
            AFTER_ATTRIBUTE_VALUE_QUOTED, SELF_CLOSING_START_TAG, SCRIPT_DATA_ESCAPED_DASH,
            SCRIPT_DATA_ESCAPED_DASH_DASH,
            -> {
                emitError()
                return to(DATA)
            }
            BEFORE_ATTRIBUTE_VALUE -> return reconsumeIn(ATTRIBUTE_VALUE, UNQUOTED)
            TAG_OPEN -> {
                emitError()
                emitChar('<'.code)
                return to(DATA)
            }
            END_TAG_OPEN -> {
                emitError()
                emitChar('<'.code)
                emitChar('/'.code)
                return to(DATA)
            }
            RAW_LESS_THAN_SIGN -> {
                if (kind == RawKind.SCRIPT_DATA_DOUBLE_ESCAPED) return to(RAW_DATA)
                emitChar('<'.code)
                return to(RAW_DATA)
            }
            RAW_END_TAG_OPEN -> {
                emitChar('<'.code)
                emitChar('/'.code)
                return to(RAW_DATA)
            }
            RAW_END_TAG_NAME -> {
                emitChar('<'.code)
                emitChar('/'.code)
                emitTempBuf()
                return to(RAW_DATA)
            }
            SCRIPT_DATA_ESCAPE_START -> return to(RAW_DATA)
            SCRIPT_DATA_ESCAPE_START_DASH -> return to(RAW_DATA, RawKind.SCRIPT_DATA)
            SCRIPT_DATA_DOUBLE_ESCAPE_END -> return to(RAW_DATA, RawKind.SCRIPT_DATA_DOUBLE_ESCAPED)
            COMMENT_START, COMMENT_START_DASH, COMMENT, COMMENT_END_DASH, COMMENT_END, COMMENT_END_BANG -> {
                emitError()
                emitCurrentComment()
                return to(DATA)
            }
            COMMENT_LESS_THAN_SIGN, COMMENT_LESS_THAN_SIGN_BANG -> return reconsumeIn(COMMENT)
            COMMENT_LESS_THAN_SIGN_BANG_DASH -> return reconsumeIn(COMMENT_END_DASH)
            COMMENT_LESS_THAN_SIGN_BANG_DASH_DASH -> return reconsumeIn(COMMENT_END)
            DOCTYPE, BEFORE_DOCTYPE_NAME -> {
                emitError()
                emitDoctype()
                return to(DATA)
            }
            DOCTYPE_NAME, AFTER_DOCTYPE_NAME, AFTER_DOCTYPE_KEYWORD, BEFORE_DOCTYPE_IDENTIFIER,
            DOCTYPE_IDENTIFIER_DOUBLE_QUOTED, DOCTYPE_IDENTIFIER_SINGLE_QUOTED, AFTER_DOCTYPE_IDENTIFIER,
            BETWEEN_DOCTYPE_PUBLIC_AND_SYSTEM_IDENTIFIERS,
            -> {
                emitError()
                emitDoctype()
                return to(DATA)
            }
            BOGUS_DOCTYPE -> {
                emitDoctype()
                return to(DATA)
            }
            BOGUS_COMMENT -> {
                emitCurrentComment()
                return to(DATA)
            }
            MARKUP_DECLARATION_OPEN -> {
                emitError()
                return to(BOGUS_COMMENT)
            }
            CDATA_SECTION -> {
                emitTempBuf()
                emitError()
                return to(DATA)
            }
            CDATA_SECTION_BRACKET -> {
                tempBuf.append(']')
                return to(CDATA_SECTION)
            }
            CDATA_SECTION_END -> {
                tempBuf.append("]]")
                return to(CDATA_SECTION)
            }
        }
        error("unknown tokenizer state $state")
    }

    companion object {
        private const val CONTINUE = 0
        private const val SUSPEND = 1

        const val DATA = 0
        const val PLAINTEXT = 1
        const val TAG_OPEN = 2
        const val END_TAG_OPEN = 3
        const val TAG_NAME = 4
        const val RAW_DATA = 5
        const val RAW_LESS_THAN_SIGN = 6
        const val RAW_END_TAG_OPEN = 7
        const val RAW_END_TAG_NAME = 8
        const val SCRIPT_DATA_ESCAPE_START = 9
        const val SCRIPT_DATA_ESCAPE_START_DASH = 10
        const val SCRIPT_DATA_ESCAPED_DASH = 11
        const val SCRIPT_DATA_ESCAPED_DASH_DASH = 12
        const val SCRIPT_DATA_DOUBLE_ESCAPE_END = 13
        const val BEFORE_ATTRIBUTE_NAME = 14
        const val ATTRIBUTE_NAME = 15
        const val AFTER_ATTRIBUTE_NAME = 16
        const val BEFORE_ATTRIBUTE_VALUE = 17
        const val ATTRIBUTE_VALUE = 18
        const val AFTER_ATTRIBUTE_VALUE_QUOTED = 19
        const val SELF_CLOSING_START_TAG = 20
        const val BOGUS_COMMENT = 21
        const val MARKUP_DECLARATION_OPEN = 22
        const val COMMENT_START = 23
        const val COMMENT_START_DASH = 24
        const val COMMENT = 25
        const val COMMENT_LESS_THAN_SIGN = 26
        const val COMMENT_LESS_THAN_SIGN_BANG = 27
        const val COMMENT_LESS_THAN_SIGN_BANG_DASH = 28
        const val COMMENT_LESS_THAN_SIGN_BANG_DASH_DASH = 29
        const val COMMENT_END_DASH = 30
        const val COMMENT_END = 31
        const val COMMENT_END_BANG = 32
        const val DOCTYPE = 33
        const val BEFORE_DOCTYPE_NAME = 34
        const val DOCTYPE_NAME = 35
        const val AFTER_DOCTYPE_NAME = 36
        const val AFTER_DOCTYPE_KEYWORD = 37
        const val BEFORE_DOCTYPE_IDENTIFIER = 38
        const val DOCTYPE_IDENTIFIER_DOUBLE_QUOTED = 39
        const val DOCTYPE_IDENTIFIER_SINGLE_QUOTED = 40
        const val AFTER_DOCTYPE_IDENTIFIER = 41
        const val BETWEEN_DOCTYPE_PUBLIC_AND_SYSTEM_IDENTIFIERS = 42
        const val BOGUS_DOCTYPE = 43
        const val CDATA_SECTION = 44
        const val CDATA_SECTION_BRACKET = 45
        const val CDATA_SECTION_END = 46

        private const val UNQUOTED = 0
        private const val SINGLE_QUOTED = 1
        private const val DOUBLE_QUOTED = 2
        private const val PUBLIC = 0
        private const val SYSTEM = 1

        private val DATA_SET = SmallCharSet('\r', '\u0000', '&', '<', '\n')
        private val RCDATA_SET = SmallCharSet('\r', '\u0000', '&', '<', '\n')
        private val RAWTEXT_SET = SmallCharSet('\r', '\u0000', '<', '\n')
        private val SCRIPT_ESCAPED_SET = SmallCharSet('\r', '\u0000', '-', '<', '\n')
        private val PLAINTEXT_SET = SmallCharSet('\r', '\u0000', '\n')
        private val DOUBLE_QUOTED_SET = SmallCharSet('\r', '"', '&', '\u0000', '\n')
        private val SINGLE_QUOTED_SET = SmallCharSet('\r', '\'', '&', '\u0000', '\n')
        private val UNQUOTED_SET = SmallCharSet('\r', '\t', '\n', '\u000c', ' ', '&', '>', '\u0000')

        private val ASCII = Array(128) { it.toChar().toString() }

        /** `lower_ascii_letter`: the lowercase letter for an ASCII letter, or -1. */
        fun lowerAsciiLetter(c: Int): Int = when (c) {
            in 'a'.code..'z'.code -> c
            in 'A'.code..'Z'.code -> c + 32
            else -> -1
        }

        private fun asciiLower(c: Int): Int = if (c in 'A'.code..'Z'.code) c + 32 else c
    }
}
