package campfire.kit

/**
 * Rust's `char::is_whitespace` (Unicode `White_Space`), which the Rust port's `str::trim` and
 * `split_whitespace` use. Kotlin's `Char.isWhitespace` differs: it counts U+001C..U+001F and not
 * U+0085.
 */
internal fun isRustWhitespace(c: Char): Boolean = when (c) {
    ' ', '\t', '\n', '\u000b', '\u000c', '\r', '\u0085', ' ', ' ', ' ', ' ', ' ', ' ',
    '　' -> true
    else -> c in ' '..' '
}

/** Rust's `str::trim`, returning [s] itself when there's nothing to trim. */
internal fun rustTrim(s: String): String {
    var start = 0
    var end = s.length
    while (start < end && isRustWhitespace(s[start])) start++
    while (end > start && isRustWhitespace(s[end - 1])) end--
    return if (start == 0 && end == s.length) s else s.substring(start, end)
}

/** Rust's `str::trim_end`. */
internal fun rustTrimEnd(s: String): String {
    var end = s.length
    while (end > 0 && isRustWhitespace(s[end - 1])) end--
    return if (end == s.length) s else s.substring(0, end)
}

/** Rust's `str::trim_start`. */
internal fun rustTrimStart(s: String): String {
    var start = 0
    while (start < s.length && isRustWhitespace(s[start])) start++
    return if (start == 0) s else s.substring(start)
}

/** Rust's `u8::is_ascii_whitespace`: space, tab, LF, FF and CR (not VT). */
internal fun isAsciiWhitespace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\n' || c == '\u000c' || c == '\r'

/** ASCII lowercasing (`to_ascii_lowercase`), returning [s] itself when it has no capitals. */
internal fun asciiLowercase(s: String): String {
    var i = 0
    while (i < s.length && s[i] !in 'A'..'Z') i++
    if (i == s.length) return s
    val chars = s.toCharArray()
    while (i < chars.size) {
        if (chars[i] in 'A'..'Z') chars[i] = chars[i] + 32
        i++
    }
    return String(chars)
}

/**
 * Whether [bytes] in `[from, to)` are well-formed UTF-8 as Rust's `str::from_utf8` judges it: no
 * overlong forms, no surrogates, nothing past U+10FFFF.
 */
internal fun isValidUtf8(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): Boolean {
    var i = from
    while (i < to) {
        val b = bytes[i].toInt() and 0xff
        if (b < 0x80) {
            i++
            continue
        }
        val width: Int
        var lo = 0x80
        var hi = 0xbf
        when (b) {
            in 0xc2..0xdf -> width = 2
            in 0xe0..0xef -> {
                width = 3
                if (b == 0xe0) lo = 0xa0
                if (b == 0xed) hi = 0x9f
            }
            in 0xf0..0xf4 -> {
                width = 4
                if (b == 0xf0) lo = 0x90
                if (b == 0xf4) hi = 0x8f
            }
            else -> return false
        }
        if (i + width > to) return false
        val second = bytes[i + 1].toInt() and 0xff
        if (second < lo || second > hi) return false
        for (k in 2 until width) {
            val next = bytes[i + k].toInt() and 0xff
            if (next < 0x80 || next > 0xbf) return false
        }
        i += width
    }
    return true
}

/** Whether [s] holds a UTF-16 surrogate that isn't half of a pair. */
internal fun hasLoneSurrogate(s: String): Boolean {
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (Character.isHighSurrogate(c)) {
            if (i + 1 >= s.length || !Character.isLowSurrogate(s[i + 1])) return true
            i += 2
            continue
        }
        if (Character.isLowSurrogate(c)) return true
        i++
    }
    return false
}

/**
 * JSON text the way `serde_json` writes it: compact, `"`, `\` and control characters escaped
 * (`\n`-style where there is one, `\u00xx` in lowercase hex otherwise), everything else as is.
 */
internal fun appendJsonString(out: StringBuilder, s: String) {
    out.append('"')
    for (c in s) {
        when {
            c == '"' -> out.append("\\\"")
            c == '\\' -> out.append("\\\\")
            c == '\n' -> out.append("\\n")
            c == '\r' -> out.append("\\r")
            c == '\t' -> out.append("\\t")
            c == '\b' -> out.append("\\b")
            c == '\u000c' -> out.append("\\f")
            c < ' ' -> out.append("\\u00").append(HEX_LOWER[c.code shr 4]).append(HEX_LOWER[c.code and 0xf])
            else -> out.append(c)
        }
    }
    out.append('"')
}

private const val HEX_LOWER = "0123456789abcdef"

/**
 * A finite double as `serde_json` (via the `ryu` crate) writes it: the shortest digits that read
 * back, `100.0` and `0.001` plainly, `1e20` and `1.5e-7` with an exponent (ryu's `format64`).
 * Java's `Double.toString` gives the same shortest digits since JDK 19.
 */
internal fun ryuFormat(d: Double): String {
    if (d == 0.0) return if (1.0 / d < 0) "-0.0" else "0.0"
    val java = java.lang.Double.toString(Math.abs(d))
    val ePos = java.indexOf('E')
    val mantissa = if (ePos < 0) java else java.substring(0, ePos)
    var exponent = if (ePos < 0) 0 else java.substring(ePos + 1).toInt()
    val dot = mantissa.indexOf('.')
    val raw = StringBuilder(mantissa.length)
    for (c in mantissa) if (c != '.') raw.append(c)
    exponent -= mantissa.length - dot - 1
    var start = 0
    while (start < raw.length - 1 && raw[start] == '0') start++
    var end = raw.length
    while (end > start + 1 && raw[end - 1] == '0') {
        end--
        exponent++
    }
    val digits = raw.substring(start, end)
    val length = digits.length
    val kk = length + exponent
    val out = StringBuilder(24)
    if (d < 0) out.append('-')
    when {
        exponent >= 0 && kk <= 16 -> {
            out.append(digits)
            repeat(exponent) { out.append('0') }
            out.append(".0")
        }
        kk in 1..16 -> out.append(digits, 0, kk).append('.').append(digits, kk, length)
        kk > -5 && kk <= 0 -> {
            out.append("0.")
            repeat(-kk) { out.append('0') }
            out.append(digits)
        }
        length == 1 -> out.append(digits).append('e').append(kk - 1)
        else -> out.append(digits[0]).append('.').append(digits, 1, length).append('e').append(kk - 1)
    }
    return out.toString()
}
