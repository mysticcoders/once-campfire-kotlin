package campfire.richtext.html

/**
 * html5ever 0.35's character reference tokenizer (`vendor/html5ever/src/tokenizer/char_ref/mod.rs`):
 * consumes one `&...` reference after the `&`, pushing back whatever turns out not to be part of
 * it, and emitting the same parse errors. [result] holds the referenced code points (none for
 * "not a reference") once [step] returns [DONE].
 */
internal class CharRefTokenizer(private val inAttribute: Boolean) {
    private var state = BEGIN
    private var base = 10
    var result: IntArray? = null
        private set
    private var num = 0L
    private var numTooBig = false
    private var seenDigit = false
    private var hexMarker = -1
    private val nameBuf = StringBuilder()
    private var nameMatch = -1L
    private var nameLen = 0

    private fun finishNone(): Int {
        result = IntArray(0)
        return DONE
    }

    private fun finishOne(c: Int): Int {
        result = intArrayOf(c)
        return DONE
    }

    /** Advances by one step: [DONE], [PROGRESS], or [STUCK] waiting for input. */
    fun step(tokenizer: Tokenizer, input: BufferQueue): Int {
        if (result != null) return DONE
        return when (state) {
            BEGIN -> doBegin(tokenizer, input)
            OCTOTHORPE -> doOctothorpe(tokenizer, input)
            NUMERIC -> doNumeric(tokenizer, input)
            NUMERIC_SEMICOLON -> doNumericSemicolon(tokenizer, input)
            NAMED -> doNamed(tokenizer, input)
            else -> doBogusName(tokenizer, input)
        }
    }

    private fun doBegin(tokenizer: Tokenizer, input: BufferQueue): Int {
        val c = tokenizer.peek(input)
        if (c < 0) return STUCK
        return when {
            isAsciiAlphanumeric(c) -> {
                state = NAMED
                PROGRESS
            }
            c == '#'.code -> {
                tokenizer.discardChar(input)
                state = OCTOTHORPE
                PROGRESS
            }
            else -> finishNone()
        }
    }

    private fun doOctothorpe(tokenizer: Tokenizer, input: BufferQueue): Int {
        val c = tokenizer.peek(input)
        if (c < 0) return STUCK
        if (c == 'x'.code || c == 'X'.code) {
            tokenizer.discardChar(input)
            hexMarker = c
            base = 16
        } else {
            hexMarker = -1
            base = 10
        }
        state = NUMERIC
        return PROGRESS
    }

    private fun doNumeric(tokenizer: Tokenizer, input: BufferQueue): Int {
        val c = tokenizer.peek(input)
        if (c < 0) return STUCK
        val n = digit(c, base)
        if (n >= 0) {
            tokenizer.discardChar(input)
            num = (num * base) and 0xffffffffL
            if (num > 0x10FFFF) numTooBig = true
            num = (num + n) and 0xffffffffL
            seenDigit = true
            return PROGRESS
        }
        if (!seenDigit) return unconsumeNumeric(tokenizer, input)
        state = NUMERIC_SEMICOLON
        return PROGRESS
    }

    private fun doNumericSemicolon(tokenizer: Tokenizer, input: BufferQueue): Int {
        val c = tokenizer.peek(input)
        if (c < 0) return STUCK
        if (c == ';'.code) tokenizer.discardChar(input) else tokenizer.emitError()
        return finishNumeric(tokenizer)
    }

    private fun unconsumeNumeric(tokenizer: Tokenizer, input: BufferQueue): Int {
        val unconsume = StringBuilder("#")
        if (hexMarker >= 0) unconsume.append(hexMarker.toChar())
        input.pushFront(unconsume)
        tokenizer.emitError()
        return finishNone()
    }

    private fun finishNumeric(tokenizer: Tokenizer): Int {
        val n = num
        val c: Int
        val error: Boolean
        when {
            n > 0x10FFFF || numTooBig -> {
                c = 0xfffd
                error = true
            }
            n == 0L || n in 0xD800L..0xDFFFL -> {
                c = 0xfffd
                error = true
            }
            n in 0x80L..0x9FL -> {
                val replacement = C1_REPLACEMENTS[(n - 0x80).toInt()]
                c = if (replacement != 0) replacement else n.toInt()
                error = true
            }
            n in 0x01L..0x08L || n == 0x0BL || n in 0x0DL..0x1FL || n == 0x7FL || n in 0xFDD0L..0xFDEFL -> {
                c = n.toInt()
                error = true
            }
            (n and 0xFFFE) == 0xFFFEL -> {
                c = n.toInt()
                error = true
            }
            else -> {
                c = n.toInt()
                error = false
            }
        }
        if (error) tokenizer.emitError()
        return finishOne(c)
    }

    private fun doNamed(tokenizer: Tokenizer, input: BufferQueue): Int {
        val c = tokenizer.peek(input)
        if (c < 0) return STUCK
        tokenizer.discardChar(input)
        nameBuf.append(c.toChar())
        val m = Entities.lookup(nameBuf) ?: return finishNamed(tokenizer, input, c)
        if (m != 0L) {
            nameMatch = m
            nameLen = nameBuf.length
        }
        return PROGRESS
    }

    private fun unconsumeName(input: BufferQueue) {
        input.pushFront(nameBuf)
    }

    private fun finishNamed(tokenizer: Tokenizer, input: BufferQueue, endChar: Int): Int {
        if (nameMatch < 0) {
            if (endChar >= 0 && isAsciiAlphanumeric(endChar)) {
                state = BOGUS_NAME
                return PROGRESS
            }
            if (endChar == ';'.code && nameBuf.length > 1) tokenizer.emitError()
            unconsumeName(input)
            return finishNone()
        }
        val lastMatched = nameBuf[nameLen - 1].code
        val nextAfter = if (nameLen == nameBuf.length) -1 else nameBuf[nameLen].code
        val unconsumeAll = when {
            lastMatched == ';'.code -> false
            inAttribute && nextAfter == '='.code -> true
            inAttribute && nextAfter >= 0 && isAsciiAlphanumeric(nextAfter) -> true
            else -> {
                tokenizer.emitError()
                false
            }
        }
        if (unconsumeAll) {
            unconsumeName(input)
            return finishNone()
        }
        input.pushFront(nameBuf.substring(nameLen))
        tokenizer.clearIgnoreLf()
        val c1 = (nameMatch shr 21).toInt()
        val c2 = (nameMatch and 0x1FFFFF).toInt()
        result = if (c2 == 0) intArrayOf(c1) else intArrayOf(c1, c2)
        return DONE
    }

    private fun doBogusName(tokenizer: Tokenizer, input: BufferQueue): Int {
        val c = tokenizer.peek(input)
        if (c < 0) return STUCK
        tokenizer.discardChar(input)
        nameBuf.append(c.toChar())
        if (isAsciiAlphanumeric(c)) return PROGRESS
        if (c == ';'.code) tokenizer.emitError()
        unconsumeName(input)
        return finishNone()
    }

    /** `end_of_file`: settles the reference with the input ended. */
    fun endOfFile(tokenizer: Tokenizer, input: BufferQueue) {
        while (result == null) {
            when (state) {
                BEGIN -> finishNone()
                NUMERIC -> if (!seenDigit) {
                    unconsumeNumeric(tokenizer, input)
                } else {
                    tokenizer.emitError()
                    finishNumeric(tokenizer)
                }
                NUMERIC_SEMICOLON -> {
                    tokenizer.emitError()
                    finishNumeric(tokenizer)
                }
                NAMED -> finishNamed(tokenizer, input, -1)
                BOGUS_NAME -> {
                    unconsumeName(input)
                    finishNone()
                }
                OCTOTHORPE -> {
                    input.pushFront("#")
                    tokenizer.emitError()
                    finishNone()
                }
            }
        }
    }

    companion object {
        const val STUCK = 0
        const val PROGRESS = 1
        const val DONE = 2

        private const val BEGIN = 0
        private const val OCTOTHORPE = 1
        private const val NUMERIC = 2
        private const val NUMERIC_SEMICOLON = 3
        private const val NAMED = 4
        private const val BOGUS_NAME = 5

        /** `C1_REPLACEMENTS` (web_atoms): what U+0080 to U+009F references mean, 0 for none. */
        private val C1_REPLACEMENTS = intArrayOf(
            0x20ac, 0, 0x201a, 0x0192, 0x201e, 0x2026, 0x2020, 0x2021, 0x02c6, 0x2030, 0x0160, 0x2039, 0x0152, 0,
            0x017d, 0, 0, 0x2018, 0x2019, 0x201c, 0x201d, 0x2022, 0x2013, 0x2014, 0x02dc, 0x2122, 0x0161, 0x203a,
            0x0153, 0, 0x017e, 0x0178,
        )

        private fun isAsciiAlphanumeric(c: Int): Boolean =
            c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code

        /** Rust's `char::to_digit` for base 10 or 16: ASCII digits (and letters for hex) only. */
        private fun digit(c: Int, base: Int): Int = when {
            c in '0'.code..'9'.code -> c - '0'.code
            base == 16 && c in 'a'.code..'f'.code -> c - 'a'.code + 10
            base == 16 && c in 'A'.code..'F'.code -> c - 'A'.code + 10
            else -> -1
        }
    }
}
