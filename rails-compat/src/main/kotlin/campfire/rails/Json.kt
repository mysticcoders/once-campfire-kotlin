package campfire.rails

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints
import java.io.IOException
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.math.abs

/**
 * The JSON Rails writes and reads (`once-campfire-rust/crates/rails_compat/src/json.rs`): the json
 * gem's `JSON.generate`, and `ActiveSupport::JSON.encode` on top of it, which messages, cookies,
 * Action Cable, Jbuilder views, webhooks and Active Storage use.
 *
 * Values are plain Kotlin: `null`, [Boolean], [String], [Long] (and [BigInteger] for integers
 * above `Long.MAX_VALUE` that fit 64 unsigned bits), [Double], [List] and [Map] with string keys.
 * [parse] produces `LinkedHashMap`s, so key order survives a round trip, as serde_json's
 * `preserve_order` keeps it in Rust.
 */
object RailsJson {
    /** What [parse] returns for input that isn't one JSON document (JSON `null` parses to `null`). */
    object Invalid {
        override fun toString(): String = "RailsJson.Invalid"
    }

    private val FACTORY: JsonFactory = JsonFactory.builder()
        .streamReadConstraints(
            StreamReadConstraints.builder()
                .maxNumberLength(Int.MAX_VALUE)
                .maxStringLength(Int.MAX_VALUE)
                .maxNestingDepth(Int.MAX_VALUE)
                .maxNameLength(Int.MAX_VALUE)
                .maxDocumentLength(-1)
                .build(),
        )
        .build()

    /** serde_json's `remaining_depth`: the 128th nested array or object is refused. */
    private const val MAX_DEPTH = 128
    private val U64_MAX: BigInteger = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)

    private val CONTROL_ESCAPES = Array(0x20) { c ->
        when (c) {
            0x08 -> "\\b"
            0x09 -> "\\t"
            0x0a -> "\\n"
            0x0c -> "\\f"
            0x0d -> "\\r"
            else -> "\\u00" + "0123456789abcdef"[c shr 4] + "0123456789abcdef"[c and 0xf]
        }
    }

    /** `::JSON.generate` / `JSON.dump`: plain JSON, non-ASCII left as UTF-8, `/` unescaped. */
    fun generate(value: Any?): String = StringBuilder(64).also { write(it, value, false) }.toString()

    /**
     * `ActiveSupport::JSON.encode` with `escape_html_entities_in_json` (the default): like
     * [generate], plus `<`, `>` and `&` escaped as `<`, `>` and `&`. U+2028/U+2029
     * are *not* escaped: `load_defaults` 8.1+ turns `escape_js_separators_in_json` off.
     */
    fun encode(value: Any?): String = StringBuilder(64).also { write(it, value, true) }.toString()

    /**
     * Re-escapes already-encoded JSON (`json.rs`, `escape_html_entities`). `<`, `>` and `&` can
     * only appear inside JSON strings, so replacing them anywhere in the document is safe, and
     * doing it twice is harmless.
     */
    fun escapeHtmlEntities(json: String): String {
        if (json.indexOf('<') < 0 && json.indexOf('>') < 0 && json.indexOf('&') < 0) return json
        val out = StringBuilder(json.length + 16)
        for (c in json) {
            when (c) {
                '<' -> out.append("\\u003c")
                '>' -> out.append("\\u003e")
                '&' -> out.append("\\u0026")
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    /**
     * `serde_json::from_slice` (`json.rs`, `parse`): one JSON document in strict UTF-8, with
     * optional surrounding whitespace, or [Invalid]. Like serde_json it refuses a byte order mark,
     * lone surrogate escapes, numbers out of `f64` range and nesting 128 deep; `-0` is a float, and
     * integers beyond 64 bits become floats.
     */
    fun parse(bytes: ByteArray): Any? {
        if (!isStrictUtf8WithoutNul(bytes) || startsWithBom(bytes)) return Invalid
        return try {
            FACTORY.createParser(bytes).use { parser ->
                val first = parser.nextToken() ?: return Invalid
                val value = read(parser, first, MAX_DEPTH)
                if (parser.nextToken() != null) Invalid else value
            }
        } catch (_: IOException) {
            Invalid
        } catch (_: NotJson) {
            Invalid
        }
    }

    /**
     * A finite float as the json gem writes it, which is not `Float#to_s`: json 2.21.2's
     * `fpconv_dtoa` writes `1e15` as `1e+15` and `1e-5` as `0.00001` (`json.rs`, `float`). The
     * digits are the shortest that round-trip.
     */
    internal fun formatFloat(f: Double): String {
        val sign = if (f < 0.0 || (f == 0.0 && 1.0 / f < 0.0)) "-" else ""
        if (f == 0.0) return sign + "0.0"
        val (digits, exponent) = shortestDigits(abs(f))
        val k = exponent + 1 - digits.length
        val body = if (k >= 0 && exponent < 15) {
            digits + "0".repeat(k) + ".0"
        } else if (k < 0 && (k > -7 || abs(exponent) < 10)) {
            val point = exponent + 1
            if (point <= 0) "0." + "0".repeat(-point) + digits else digits.substring(0, point) + "." + digits.substring(point)
        } else {
            val fraction = if (digits.length > 1) "." + digits.substring(1) else ""
            digits.substring(0, 1) + fraction + "e" + (if (exponent < 0) '-' else '+') + abs(exponent)
        }
        return sign + body
    }

    /**
     * The shortest round-trip digits of a positive finite `f` and the power of ten of the first
     * one (Rust's `{:e}`). `Double.toString` is shortest since JDK 19, except that where one digit
     * would do it may pick a closer two-digit decimal (`4.9E-324`), so those are retried with one.
     */
    private fun shortestDigits(f: Double): Pair<String, Int> {
        val s = f.toString()
        val e = s.indexOf('E')
        val mantissa = if (e < 0) s else s.substring(0, e)
        val exp10 = if (e < 0) 0 else s.substring(e + 1).toInt()
        val dot = mantissa.indexOf('.')
        val all = mantissa.substring(0, dot) + mantissa.substring(dot + 1)
        val first = all.indexOfFirst { it != '0' }
        val last = all.indexOfLast { it != '0' }
        val digits = all.substring(first, last + 1)
        val exponent = dot + exp10 - first - 1
        if (digits.length == 2) {
            val lower = digits[0] - '0'
            val candidates = if (digits[1] < '5') listOf(lower, lower + 1) else listOf(lower + 1, lower)
            for (candidate in candidates) {
                val (one, oneExponent) = if (candidate == 10) "1" to exponent + 1 else candidate.toString() to exponent
                if ("${one}e$oneExponent".toDouble() == f) return one to oneExponent
            }
        }
        return digits to exponent
    }

    /** Writes `value` compactly; `escapeHtml` adds `ActiveSupport::JSON`'s `<>&` escapes. */
    private fun write(out: StringBuilder, value: Any?, escapeHtml: Boolean) {
        when (value) {
            null -> out.append("null")
            is CharSequence -> writeString(out, value, escapeHtml)
            is Boolean -> out.append(if (value) "true" else "false")
            is Long, is Int, is Short, is Byte, is BigInteger -> out.append(value.toString())
            is Double -> writeDouble(out, value)
            is Float -> writeDouble(out, value.toString().toDouble())
            is BigDecimal -> writeDouble(out, value.toDouble())
            is Number -> writeDouble(out, value.toDouble())
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) out.append(',')
                    first = false
                    writeString(out, k.toString(), escapeHtml)
                    out.append(':')
                    write(out, v, escapeHtml)
                }
                out.append('}')
            }
            is Iterable<*> -> writeArray(out, value.iterator(), escapeHtml)
            is Array<*> -> writeArray(out, value.iterator(), escapeHtml)
            is Char -> writeString(out, value.toString(), escapeHtml)
            else -> throw IllegalArgumentException("not a JSON value: ${value::class.qualifiedName}")
        }
    }

    /** serde_json writes a non-finite float as `null`, which is `Float#as_json` in Rails too. */
    private fun writeDouble(out: StringBuilder, value: Double) {
        if (value.isFinite()) out.append(formatFloat(value)) else out.append("null")
    }

    /** A JSON array of the iterator's elements. */
    private fun writeArray(out: StringBuilder, elements: Iterator<*>, escapeHtml: Boolean) {
        out.append('[')
        var first = true
        for (element in elements) {
            if (!first) out.append(',')
            first = false
            write(out, element, escapeHtml)
        }
        out.append(']')
    }

    /**
     * A JSON string as serde_json and the json gem write it: quote, backslash and control
     * characters escaped (lowercase hex), everything else as is, plus `<>&` when `escapeHtml`.
     * Unescaped runs are copied in one append.
     */
    private fun writeString(out: StringBuilder, s: CharSequence, escapeHtml: Boolean) {
        out.append('"')
        var start = 0
        for (i in 0 until s.length) {
            val c = s[i]
            val escape = when {
                c == '"' -> "\\\""
                c == '\\' -> "\\\\"
                c < ' ' -> CONTROL_ESCAPES[c.code]
                !escapeHtml -> null
                c == '<' -> "\\u003c"
                c == '>' -> "\\u003e"
                c == '&' -> "\\u0026"
                else -> null
            }
            if (escape != null) {
                out.append(s, start, i).append(escape)
                start = i + 1
            }
        }
        out.append(s, start, s.length).append('"')
    }

    /** Reads the value starting at `token`; `depth` is serde_json's remaining nesting budget. */
    private fun read(parser: JsonParser, token: JsonToken, depth: Int): Any? = when (token) {
        JsonToken.START_OBJECT -> {
            if (depth - 1 == 0) throw NotJson
            val map = LinkedHashMap<String, Any?>()
            while (true) {
                val next = parser.nextToken()
                if (next == JsonToken.END_OBJECT) break
                if (next != JsonToken.FIELD_NAME) throw NotJson
                val name = checkedString(parser.currentName())
                map[name] = read(parser, parser.nextToken() ?: throw NotJson, depth - 1)
            }
            map
        }
        JsonToken.START_ARRAY -> {
            if (depth - 1 == 0) throw NotJson
            val list = ArrayList<Any?>()
            while (true) {
                val next = parser.nextToken() ?: throw NotJson
                if (next == JsonToken.END_ARRAY) break
                list.add(read(parser, next, depth - 1))
            }
            list
        }
        JsonToken.VALUE_STRING -> checkedString(parser.text)
        JsonToken.VALUE_NUMBER_INT -> integer(parser)
        JsonToken.VALUE_NUMBER_FLOAT -> parser.doubleValue.also { if (!it.isFinite()) throw NotJson }
        JsonToken.VALUE_TRUE -> true
        JsonToken.VALUE_FALSE -> false
        JsonToken.VALUE_NULL -> null
        else -> throw NotJson
    }

    /**
     * serde_json's integers: a positive one is a `u64` if it fits, a negative one an `i64`, `-0` is
     * the float `-0.0`, and anything wider is a float.
     */
    private fun integer(parser: JsonParser): Any {
        return when (parser.numberType) {
            JsonParser.NumberType.INT, JsonParser.NumberType.LONG -> {
                val value = parser.longValue
                if (value == 0L && parser.text.startsWith('-')) -0.0 else value
            }
            else -> {
                val big = parser.bigIntegerValue
                if (big.signum() > 0 && big <= U64_MAX) {
                    big
                } else {
                    big.toDouble().also { if (!it.isFinite()) throw NotJson }
                }
            }
        }
    }

    /** serde_json refuses `\uD800`-style escapes that leave a lone surrogate; jackson doesn't. */
    private fun checkedString(s: String): String {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length || !Character.isLowSurrogate(s[i + 1])) throw NotJson
                i += 2
            } else {
                if (Character.isLowSurrogate(c)) throw NotJson
                i++
            }
        }
        return s
    }

    private fun startsWithBom(bytes: ByteArray): Boolean =
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()

    /**
     * Strict UTF-8 (no overlong forms, surrogates or code points past U+10FFFF), as Rust's
     * `str::from_utf8` checks it, and no NUL bytes, which JSON never allows unescaped and whose
     * presence would make jackson guess UTF-16 or UTF-32.
     */
    private fun isStrictUtf8WithoutNul(bytes: ByteArray): Boolean = Utf8.isStrict(bytes, allowNul = false)

    /** Aborts a parse without the cost of a stack trace. */
    private object NotJson : RuntimeException(null, null, false, false)
}

/** Strict UTF-8 validation shared by [RailsJson] and the null serializer (`metadata.rs`). */
internal object Utf8 {
    /** Whether `bytes` is well-formed UTF-8, as Rust's `str::from_utf8` decides it. */
    fun isStrict(bytes: ByteArray, allowNul: Boolean = true): Boolean {
        var i = 0
        val n = bytes.size
        while (i < n) {
            val b = bytes[i].toInt() and 0xff
            if (b < 0x80) {
                if (b == 0 && !allowNul) return false
                i++
                continue
            }
            val width: Int
            val min: Int
            var cp: Int
            when {
                b in 0xC2..0xDF -> { width = 2; min = 0x80; cp = b and 0x1f }
                b in 0xE0..0xEF -> { width = 3; min = 0x800; cp = b and 0x0f }
                b in 0xF0..0xF4 -> { width = 4; min = 0x10000; cp = b and 0x07 }
                else -> return false
            }
            if (i + width > n) return false
            for (j in 1 until width) {
                val cont = bytes[i + j].toInt() and 0xff
                if (cont and 0xC0 != 0x80) return false
                cp = (cp shl 6) or (cont and 0x3f)
            }
            if (cp < min || cp > 0x10FFFF || cp in 0xD800..0xDFFF) return false
            i += width
        }
        return true
    }

    /** `String::from_utf8`: the string, or null if `bytes` isn't strict UTF-8. */
    fun decode(bytes: ByteArray): String? = if (isStrict(bytes)) String(bytes, Charsets.UTF_8) else null
}
