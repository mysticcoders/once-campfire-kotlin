package campfire.richtext

import campfire.rails.RailsJson
import campfire.ruby.floatToS
import java.math.BigInteger

/**
 * Ruby and Active Support string behaviors the pipeline depends on, beyond `campfire.ruby`'s
 * (`once-campfire-rust/crates/richtext/src/ruby.rs`), plus the Unicode character classes Rust's
 * standard library and regex crate use, which Java's don't match exactly.
 */
object RubyText {
    /**
     * Rust's `char::is_whitespace`: Unicode's White_Space property (Java's `isWhitespace` leaves
     * out U+00A0, U+2007 and U+202F and adds U+001C to U+001F).
     */
    fun isWhitespace(cp: Int): Boolean = when (cp) {
        in 0x09..0x0d, 0x20, 0x85, 0xa0, 0x1680, in 0x2000..0x200a, 0x2028, 0x2029, 0x202f, 0x205f, 0x3000 -> true
        else -> false
    }

    /**
     * The regex crate's Unicode `\w` (and Ruby's `\p{Word}`): Alphabetic, marks, decimal digits,
     * connector punctuation and the join controls.
     */
    fun isWordChar(cp: Int): Boolean {
        if (Character.isAlphabetic(cp)) return true
        return when (Character.getType(cp).toByte()) {
            Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK,
            Character.DECIMAL_DIGIT_NUMBER, Character.CONNECTOR_PUNCTUATION,
            -> true
            else -> cp == 0x200c || cp == 0x200d
        }
    }

    /** Active Support's `String#blank?`: empty or only Unicode whitespace (`/\A[[:space:]]*\z/`). */
    fun isBlank(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (!isWhitespace(cp)) return false
            i += Character.charCount(cp)
        }
        return true
    }

    /** `Object#presence` for an optional string. */
    fun presence(s: String?): String? = s?.takeIf { !isBlank(it) }

    /** Rust's `str::trim_matches(char::is_whitespace)` (and `str::trim`): Unicode whitespace off both ends. */
    fun trimWhitespace(s: String): String {
        var start = 0
        while (start < s.length) {
            val cp = s.codePointAt(start)
            if (!isWhitespace(cp)) break
            start += Character.charCount(cp)
        }
        var end = s.length
        while (end > start) {
            val cp = s.codePointBefore(end)
            if (!isWhitespace(cp)) break
            end -= Character.charCount(cp)
        }
        return s.substring(start, end)
    }

    /** `String#chomp("")`: removes every trailing `\n` or `\r\n`, but not a lone `\r`. */
    fun chompNewlines(s: String): String {
        var end = s.length
        while (true) {
            if (end >= 2 && s[end - 2] == '\r' && s[end - 1] == '\n') {
                end -= 2
            } else if (end >= 1 && s[end - 1] == '\n') {
                end -= 1
            } else {
                return s.substring(0, end)
            }
        }
    }

    /** `String#chomp` with no argument: removes one trailing `\r\n`, `\n` or `\r`. */
    fun chomp(s: String): String = when {
        s.endsWith("\r\n") -> s.substring(0, s.length - 2)
        s.endsWith('\n') || s.endsWith('\r') -> s.substring(0, s.length - 1)
        else -> s
    }

    /** Action View's `truncate(text, length:, omission:)` with the default separator, counting characters. */
    fun truncate(text: String, length: Int, omission: String): String {
        val chars = text.codePointCount(0, text.length)
        if (chars <= length) return text
        val keep = maxOf(0, length - omission.codePointCount(0, omission.length))
        return text.substring(0, text.offsetByCodePoints(0, keep)) + omission
    }

    /** What [jsonParse] returns for input Ruby's `JSON.parse` rejects. */
    val NOT_JSON: Any = RailsJson.Invalid

    /**
     * Ruby's `JSON.parse`, which (unlike serde_json) also skips `/* */` and `//` comments; values
     * are [RailsJson]'s. Returns [NOT_JSON] when the input doesn't parse.
     */
    fun jsonParse(s: String): Any? {
        val stripped = stripJsonComments(s) ?: return NOT_JSON
        return RailsJson.parse(stripped.toByteArray(Charsets.UTF_8))
    }

    private fun stripJsonComments(s: String): String? {
        val out = StringBuilder(s.length)
        var inString = false
        var i = 0
        while (i < s.length) {
            val c = s[i++]
            if (inString) {
                out.append(c)
                if (c == '\\') {
                    if (i >= s.length) return null
                    out.append(s[i++])
                } else if (c == '"') {
                    inString = false
                }
                continue
            }
            val next = if (i < s.length) s[i] else '\u0000'
            when {
                c == '"' -> {
                    inString = true
                    out.append(c)
                }
                c == '/' && i < s.length && next == '*' -> {
                    i++
                    var closed = false
                    while (i < s.length) {
                        val d = s[i++]
                        if (d == '*' && i < s.length && s[i] == '/') {
                            i++
                            closed = true
                            break
                        }
                    }
                    if (!closed) return null
                    out.append(' ')
                }
                c == '/' && i < s.length && next == '/' -> {
                    i++
                    while (i < s.length) {
                        if (s[i++] == '\n') break
                    }
                    out.append(' ')
                }
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    /** `Object#to_s` of a parsed JSON value, as Nokogiri's `create_element` applies to attribute values. */
    fun jsonValueToS(v: Any?): String = when (v) {
        is String -> v
        null -> ""
        else -> jsonValueInspect(v)
    }

    /** `Object#inspect` for parsed JSON values, in Ruby 3.4's format (`{"a" => 1}`). */
    fun jsonValueInspect(v: Any?): String = when (v) {
        null -> "nil"
        is Boolean -> v.toString()
        is Long -> v.toString()
        is BigInteger -> v.toString()
        is Double -> floatToS(v)
        is String -> stringInspect(v)
        is List<*> -> v.joinToString(", ", "[", "]") { jsonValueInspect(it) }
        is Map<*, *> -> if (v.isEmpty()) {
            "{}"
        } else {
            v.entries.joinToString(", ", "{", "}") { (k, value) -> "${stringInspect(k as String)} => ${jsonValueInspect(value)}" }
        }
        else -> v.toString()
    }

    /** `String#inspect` for a UTF-8 string. */
    internal fun stringInspect(s: String): String {
        val out = StringBuilder("\"")
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val next = if (i + 1 < s.length) s[i + 1] else '\u0000'
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\t' -> out.append("\\t")
                c == '\r' -> out.append("\\r")
                c == '\u000c' -> out.append("\\f")
                c == '\u000b' -> out.append("\\v")
                c == '\u0008' -> out.append("\\b")
                c == '\u0007' -> out.append("\\a")
                c == '\u001b' -> out.append("\\e")
                c == '#' && i + 1 < s.length && (next == '{' || next == '$' || next == '@') -> out.append("\\#")
                c.code < 0x20 || c == '\u007f' -> out.append("\\x").append("%02X".format(c.code))
                else -> out.append(c)
            }
            i++
        }
        return out.append('"').toString()
    }
}
