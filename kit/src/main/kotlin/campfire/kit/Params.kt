package campfire.kit

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints
import java.math.BigInteger

/**
 * Why parameters couldn't be parsed; each is a 400 (`once-campfire-rust/crates/kit/src/params.rs`,
 * `ParamError`). Stack traces are not filled in.
 */
sealed class ParamError(message: String) : RuntimeException(message, null, false, false) {
    /** `ParameterTypeError`: e.g. `a=1&a[b]=2`. */
    class Type(message: String) : ParamError(message)

    /** `InvalidParameterError`: bad %-encoding or invalid UTF-8. */
    class Invalid(message: String) : ParamError(message)

    /** `ParamsTooDeepError`. */
    class TooDeep : ParamError("exceeded available parameter key space")

    /** `QueryLimitError`. */
    class Limit(message: String) : ParamError(message)

    /** `ActionDispatch::Http::Parameters::ParseError` (malformed JSON or multipart). */
    class Parse : ParamError("Error occurred while parsing request parameters")

    /** The error an action answers with (`From<ParamError> for Error` in `error.rs`). */
    fun toKitError(): KitError = KitError.BadRequest(message ?: "")
}

/**
 * A parameter value: what a Rails params hash can hold (`params.rs`, `Param`). Numbers and
 * booleans come only from JSON bodies.
 */
sealed interface Param {
    data object Null : Param

    data class Bool(val value: Boolean) : Param

    /** A JSON number: a [Long], a [BigInteger] past `i64` up to `u64`, or a [Double] (serde's `Number`). */
    data class Num(val value: Number) : Param

    data class Str(val value: String) : Param

    data class File(val file: UploadedFile) : Param

    data class Arr(val items: MutableList<Param>) : Param

    data class Hash(val map: ParamMap) : Param

    val isNull: Boolean get() = this === Null

    fun asStr(): String? = (this as? Str)?.value

    fun asHash(): ParamMap? = (this as? Hash)?.map

    fun asArray(): List<Param>? = (this as? Arr)?.items

    fun asFile(): UploadedFile? = (this as? File)?.file

    /** `params[:a][:b]` style lookup; null for anything that isn't a hash. */
    operator fun get(key: String): Param? = asHash()?.get(key)

    /**
     * The value as Rails would interpolate it (`to_s`): strings as they are, numbers and booleans
     * formatted, nil as "". Null for files, arrays and hashes.
     */
    fun toS(): String? = when (this) {
        Null -> ""
        is Bool -> value.toString()
        is Num -> formatNumber(value)
        is Str -> value
        else -> null
    }

    /** ActiveSupport's `blank?`: nil, false, whitespace-only strings, and empty collections. */
    val isBlank: Boolean
        get() = when (this) {
            Null -> true
            is Bool -> !value
            is Num, is File -> false
            is Str -> value.all(::isRustWhitespace)
            is Arr -> items.isEmpty()
            is Hash -> map.isEmpty()
        }

    val isPresent: Boolean get() = !isBlank

    /** The value as compact JSON text, in insertion order (`Param#to_json`). */
    fun toJson(): String = StringBuilder().also { writeJson(it) }.toString()

    /** Appends [toJson] to [out]. */
    fun writeJson(out: StringBuilder) {
        when (this) {
            Null -> out.append("null")
            is Bool -> out.append(value)
            is Num -> out.append(formatNumber(value))
            is Str -> appendJsonString(out, value)
            is File -> {
                out.append("{\"original_filename\":")
                appendJsonString(out, file.originalFilename)
                out.append(",\"content_type\":")
                if (file.contentType == null) out.append("null") else appendJsonString(out, file.contentType)
                out.append('}')
            }
            is Arr -> {
                out.append('[')
                items.forEachIndexed { i, item ->
                    if (i > 0) out.append(',')
                    item.writeJson(out)
                }
                out.append(']')
            }
            is Hash -> map.writeJson(out)
        }
    }

    /** `params.require(:key).permit(...)`'s second half: keep only the permitted keys. */
    fun permit(filters: List<Permit>): ParamMap = asHash()?.permit(filters) ?: ParamMap()

    companion object {
        fun of(s: String): Param = Str(s)

        /** A JSON number as serde_json's `Display` writes it. */
        internal fun formatNumber(n: Number): String = if (n is Double) ryuFormat(n) else n.toString()
    }
}

/** The strong-parameters notion of a permitted scalar (`params.rs`, `is_permitted_scalar`). */
private val Param.isPermittedScalar: Boolean get() = this !is Param.Arr && this !is Param.Hash

/**
 * An insertion-ordered string-keyed map, like the Ruby `Hash` behind Rails params (`params.rs`,
 * `ParamMap`). Hashed, so params with many keys cost linear time, not quadratic. Equal when the
 * same keys map to equal values in the same order, as Ruby hashes compare.
 */
class ParamMap() : Iterable<Map.Entry<String, Param>> {
    private val entries = LinkedHashMap<String, Param>()

    constructor(pairs: Iterable<Pair<String, Param>>) : this() {
        for ((k, v) in pairs) entries[k] = v
    }

    operator fun get(key: String): Param? = entries[key]

    /** The value at [key] when it's a string. */
    fun str(key: String): String? = entries[key]?.asStr()

    fun containsKey(key: String): Boolean = entries.containsKey(key)

    /** Ruby's `hash[key] = value`: replaces in place, keeping the original position. */
    operator fun set(key: String, value: Param) {
        entries[key] = value
    }

    /** Removes [key], keeping the order of the rest. */
    fun remove(key: String): Param? = entries.remove(key)

    val keys: Set<String> get() = entries.keys

    val size: Int get() = entries.size

    fun isEmpty(): Boolean = entries.isEmpty()

    override fun iterator(): Iterator<Map.Entry<String, Param>> = entries.entries.iterator()

    /** Ruby's `Hash#merge!`: later values win, existing keys keep their position. */
    fun merge(other: ParamMap) {
        for ((k, v) in other.entries) entries[k] = v
    }

    /** A shallow copy. */
    fun copy(): ParamMap = ParamMap().also { it.entries.putAll(entries) }

    /**
     * `params.require(:key)`: the value when it's present (or `false`), else
     * [KitError.ParameterMissing].
     */
    fun require(key: String): Param {
        val value = entries[key]
        if (value != null && (value.isPresent || value == Param.Bool(false))) return value
        throw KitError.ParameterMissing(key)
    }

    /** `params.fetch(:key, default)`. */
    fun fetch(key: String, default: Param): Param = entries[key] ?: default

    /** `ActionController::Parameters#permit`, for the filter shapes Campfire uses. */
    fun permit(filters: List<Permit>): ParamMap {
        val permitted = ParamMap()
        for (filter in filters) {
            when (filter) {
                is Permit.Key -> {
                    val value = entries[filter.key]
                    if (value != null && value.isPermittedScalar) permitted[filter.key] = value
                    for ((k, v) in entries) {
                        if (isMultiParameterKey(k, filter.key) && v.isPermittedScalar) permitted[k] = v
                    }
                }
                is Permit.ScalarArray -> {
                    val value = entries[filter.key]
                    if (value is Param.Arr && value.items.all { it.isPermittedScalar }) {
                        permitted[filter.key] = Param.Arr(ArrayList(value.items))
                    }
                }
                is Permit.AnyHash -> {
                    val value = entries[filter.key]
                    if (value is Param.Hash) permitted[filter.key] = Param.Hash(permitAny(value.map))
                }
                is Permit.Nested -> permitNested(permitted, filter)
            }
        }
        return permitted
    }

    /** `permit(key: [...])` / `permit(key: {...})`: a hash, a `fields_for` hash, or an array of hashes. */
    private fun permitNested(permitted: ParamMap, filter: Permit.Nested) {
        when (val value = entries[filter.key]) {
            is Param.Hash if isFieldsForStyle(value.map) -> {
                val each = ParamMap()
                for ((k, v) in value.map) {
                    if (v is Param.Hash) each[k] = Param.Hash(v.map.permit(filter.filters))
                }
                permitted[filter.key] = Param.Hash(each)
            }
            is Param.Hash -> permitted[filter.key] = Param.Hash(value.map.permit(filter.filters))
            is Param.Arr -> {
                val hashes = ArrayList<Param>()
                for (item in value.items) if (item is Param.Hash) hashes.add(Param.Hash(item.map.permit(filter.filters)))
                permitted[filter.key] = Param.Arr(hashes)
            }
            else -> {}
        }
    }

    /** The map as compact JSON text, in insertion order. */
    fun toJson(): String = StringBuilder().also { writeJson(it) }.toString()

    /** Appends [toJson] to [out]. */
    fun writeJson(out: StringBuilder) {
        out.append('{')
        var first = true
        for ((k, v) in entries) {
            if (!first) out.append(',')
            first = false
            appendJsonString(out, k)
            out.append(':')
            v.writeJson(out)
        }
        out.append('}')
    }

    override fun equals(other: Any?): Boolean {
        if (other !is ParamMap || other.entries.size != entries.size) return false
        val mine = entries.entries.iterator()
        val theirs = other.entries.entries.iterator()
        while (mine.hasNext()) {
            val a = mine.next()
            val b = theirs.next()
            if (a.key != b.key || a.value != b.value) return false
        }
        return true
    }

    override fun hashCode(): Int = entries.hashCode()

    override fun toString(): String = toJson()

    companion object {
        /**
         * The params an action sees, as `Ctx::new` (`ctx.rs`) builds them: the request (body)
         * params, then the query string's, then the path's, later ones winning.
         */
        fun combined(requestParams: ParamMap, queryParams: ParamMap, pathParams: ParamMap): ParamMap =
            requestParams.copy().also {
                it.merge(queryParams)
                it.merge(pathParams)
            }
    }
}

/** `/\A#{key}\(\d+[if]?\)\z/`: a multi-parameter attribute such as `born_on(1i)`. */
private fun isMultiParameterKey(candidate: String, key: String): Boolean {
    if (!candidate.startsWith(key) || candidate.length < key.length + 2) return false
    if (candidate[key.length] != '(' || candidate[candidate.length - 1] != ')') return false
    var end = candidate.length - 1
    if (end > key.length + 1 && (candidate[end - 1] == 'i' || candidate[end - 1] == 'f')) end--
    val start = key.length + 1
    if (start >= end) return false
    for (i in start until end) if (candidate[i] !in '0'..'9') return false
    return true
}

/** Rails' `fields_for` shape: every key an (optionally negative) integer, every value a hash. */
private fun isFieldsForStyle(map: ParamMap): Boolean {
    if (map.isEmpty()) return false
    for ((k, v) in map) {
        val start = if (k.startsWith('-')) 1 else 0
        if (start >= k.length) return false
        for (i in start until k.length) if (k[i] !in '0'..'9') return false
        if (v !is Param.Hash) return false
    }
    return true
}

/** `permit(key: {})`: any scalars, arrays of scalars or hashes, recursively. */
private fun permitAny(map: ParamMap): ParamMap {
    val permitted = ParamMap()
    for ((k, v) in map) {
        permitted[k] = when (v) {
            is Param.Hash -> Param.Hash(permitAny(v.map))
            is Param.Arr -> {
                val kept = ArrayList<Param>(v.items.size)
                for (item in v.items) {
                    when (item) {
                        is Param.Hash -> kept.add(Param.Hash(permitAny(item.map)))
                        is Param.Arr -> {}
                        else -> kept.add(item)
                    }
                }
                Param.Arr(kept)
            }
            else -> v
        }
    }
    return permitted
}

/** A `permit` filter (`params.rs`, `Permit`). */
sealed interface Permit {
    /** `:name` */
    data class Key(val key: String) : Permit

    /** `name: []` */
    data class ScalarArray(val key: String) : Permit

    /** `name: {}` */
    data class AnyHash(val key: String) : Permit

    /** `name: [ ... ]` / `name: { ... }` */
    data class Nested(val key: String, val filters: List<Permit>) : Permit
}

/** Shorthand for a list of [Permit.Key]s: `permitKeys("name", "avatar")` (`params.rs`, `permit_keys`). */
fun permitKeys(vararg keys: String): List<Permit> = keys.map { Permit.Key(it) }

/**
 * A file part of a multipart body (`ActionDispatch::Http::UploadedFile`; `params.rs`,
 * `UploadedFile`). The Rust port spools it to a temp file; here the body is already in memory,
 * so the file holds its bytes. Two files are equal only when they're the same upload.
 */
class UploadedFile(
    val originalFilename: String,
    val contentType: String?,
    /** The raw part headers (`UploadedFile#headers`). */
    val headers: String,
    private val bytes: ByteArray,
) {
    val size: Long get() = bytes.size.toLong()

    /** The file's contents (shared, not copied). */
    fun read(): ByteArray = bytes

    override fun toString(): String = "UploadedFile($originalFilename, $contentType, $size bytes)"

    companion object {
        /** A file from [bytes] with no part headers; handy for tests and raw-body attachments. */
        fun fromBytes(originalFilename: String, contentType: String?, bytes: ByteArray): UploadedFile =
            UploadedFile(originalFilename, contentType, "", bytes)
    }
}

/**
 * A pair after %-decoding but before the UTF-8 check, which Rails performs in the builder (so
 * pairs whose top-level key is empty are skipped without raising) (`params.rs`, `RawPair`). A key
 * or text value that isn't valid UTF-8 holds its lossy decoding with `valid = false`.
 */
class RawPair(val key: String, val keyValid: Boolean, val value: PairValue?) {
    companion object {
        fun text(key: String, value: String?): RawPair = RawPair(key, true, value?.let { PairValue.Text(it, true) })

        fun file(key: String, file: UploadedFile): RawPair = RawPair(key, true, PairValue.File(file))
    }
}

/** A pair's value: decoded text (and whether it was valid UTF-8), or an uploaded file. */
sealed interface PairValue {
    class Text(val text: String, val valid: Boolean) : PairValue

    class File(val file: UploadedFile) : PairValue
}

/**
 * Rails request parameter parsing (`once-campfire-rust/crates/kit/src/params.rs`): a port of
 * `ActionDispatch::ParamBuilder` (Rails 8.2, itself derived from `Rack::QueryParser`) plus the pair
 * splitting of `ActionDispatch::QueryParser.each_pair` and `Rack::QueryParser#parse_query_pairs`,
 * and the deep munging applied to JSON bodies (`Request::Utils::NoNilParamEncoder`).
 */
object Params {
    /** Rails' `ActionDispatch::ParamBuilder.default` depth limit. */
    const val DEPTH_LIMIT = 100

    /** `Rack::QueryParser` limits, applied to form bodies (Rack parses those, not Rails). */
    const val FORM_BYTESIZE_LIMIT = 4 * 1024 * 1024
    const val FORM_PARAMS_LIMIT = 4096

    /** serde_json's nesting limit: a 128th nested array or object is an error. */
    private const val JSON_DEPTH_LIMIT = 128

    private val JSON: JsonFactory = JsonFactory.builder()
        .streamReadConstraints(
            StreamReadConstraints.builder()
                .maxNestingDepth(JSON_DEPTH_LIMIT + 1)
                .maxNumberLength(Int.MAX_VALUE)
                .maxStringLength(Int.MAX_VALUE)
                .maxNameLength(Int.MAX_VALUE)
                .build(),
        )
        .build()

    /**
     * `ActionDispatch::QueryParser.each_pair`: split on `&` and the spaces after it, skip empty parts, split on the
     * first `=`, and `URI.decode_www_form_component` both halves. No size limits (Rails applies
     * none to the query string).
     */
    fun queryPairs(qs: String): List<RawPair> {
        val pairs = ArrayList<RawPair>()
        splitPairs(qs) { start, end -> pairs.add(decodePair(qs, start, end)) }
        return pairs
    }

    /**
     * `Rack::Request#form_pairs` for an urlencoded body: Rack's limits, and the trailing `\0`
     * Safari once appended is dropped. Raw non-UTF-8 bytes are [ParamError.Invalid].
     */
    fun formPairs(body: ByteArray): List<RawPair> {
        if (body.size > FORM_BYTESIZE_LIMIT) {
            throw ParamError.Limit("total query size exceeds limit ($FORM_BYTESIZE_LIMIT)")
        }
        val length = if (body.isNotEmpty() && body[body.size - 1] == 0.toByte()) body.size - 1 else body.size
        var total = 1
        for (i in 0 until length) if (body[i] == '&'.code.toByte()) total++
        if (total > FORM_PARAMS_LIMIT) {
            throw ParamError.Limit("total number of query parameters ($total) exceeds limit ($FORM_PARAMS_LIMIT)")
        }
        if (!isValidUtf8(body, 0, length)) throw ParamError.Invalid("Invalid encoding for parameter")
        return queryPairs(String(body, 0, length, Charsets.UTF_8))
    }

    /** Calls [each] with the bounds of every non-empty `&`-separated part, leading spaces dropped after the first. */
    private inline fun splitPairs(qs: String, each: (Int, Int) -> Unit) {
        var start = 0
        var first = true
        while (start <= qs.length) {
            var end = qs.indexOf('&', start)
            if (end < 0) end = qs.length
            var from = start
            if (!first) while (from < end && qs[from] == ' ') from++
            if (from < end) each(from, end)
            first = false
            start = end + 1
        }
    }

    /** One `key=value` part, decoded. */
    private fun decodePair(qs: String, start: Int, end: Int): RawPair {
        val eq = qs.indexOf('=', start).let { if (it < 0 || it >= end) -1 else it }
        return if (eq < 0) {
            val key = decodeComponent(qs, start, end)
            RawPair(key.text, key.valid, null)
        } else {
            val key = decodeComponent(qs, start, eq)
            val value = decodeComponent(qs, eq + 1, end)
            RawPair(key.text, key.valid, PairValue.Text(value.text, value.valid))
        }
    }

    private class Decoded(val text: String, val valid: Boolean)

    /** [decodeWwwFormComponent] of `s[start, end)` as text; the substring itself when there's nothing to decode. */
    private fun decodeComponent(s: String, start: Int, end: Int): Decoded {
        var plain = true
        for (i in start until end) {
            val c = s[i]
            if (c == '%' || c == '+') {
                plain = false
                break
            }
        }
        val part = s.substring(start, end)
        if (plain) return Decoded(part, true)
        val bytes = decodeWwwFormComponent(part)
        return Decoded(String(bytes, Charsets.UTF_8), isValidUtf8(bytes))
    }

    /**
     * Ruby's `URI.decode_www_form_component`: `+` is a space, `%XX` is a byte, and a `%` not
     * followed by two hex digits is [ParamError.Invalid].
     */
    fun decodeWwwFormComponent(s: String): ByteArray {
        val bytes = s.toByteArray(Charsets.UTF_8)
        val out = ByteArray(bytes.size)
        var n = 0
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i]
            when (b) {
                '+'.code.toByte() -> {
                    out[n++] = ' '.code.toByte()
                    i++
                }
                '%'.code.toByte() -> {
                    val hi = if (i + 1 < bytes.size) hexValue(bytes[i + 1]) else -1
                    val lo = if (i + 2 < bytes.size) hexValue(bytes[i + 2]) else -1
                    if (hi < 0 || lo < 0) throw ParamError.Invalid("invalid %-encoding ($s)")
                    out[n++] = ((hi shl 4) or lo).toByte()
                    i += 3
                }
                else -> {
                    out[n++] = b
                    i++
                }
            }
        }
        return if (n == out.size) out else out.copyOf(n)
    }

    private fun hexValue(b: Byte): Int = when (val c = b.toInt().toChar()) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    /** `ActionDispatch::ParamBuilder.from_pairs`. */
    fun fromPairs(pairs: Iterable<RawPair>): ParamMap {
        val params = ParamMap()
        for (pair in pairs) {
            if (!pair.keyValid) {
                if (topLevelKey(pair.key).isEmpty()) continue
                throw ParamError.Invalid("Invalid encoding for parameter")
            }
            if (topLevelKey(pair.key).isEmpty()) continue
            val value = when (val v = pair.value) {
                null -> Param.Null
                is PairValue.File -> Param.File(v.file)
                is PairValue.Text -> {
                    if (!v.valid) throw ParamError.Invalid("Invalid encoding for parameter: ${v.text}")
                    Param.Str(v.text)
                }
            }
            storeNestedParam(params, pair.key, value, 0)
        }
        return params
    }

    /** `ActionDispatch::ParamBuilder.from_query_string`. */
    fun fromQueryString(qs: String): ParamMap = fromPairs(queryPairs(qs))

    /**
     * The query-string parser as JSON text, for differential tests against
     * `ActionDispatch::ParamBuilder.from_query_string(qs)` in the reference container: `null`
     * when Rails would raise (a 400); see [tryParseNested] for the error itself.
     */
    fun parseNested(qs: String): String = try {
        tryParseNested(qs)
    } catch (_: ParamError) {
        "null"
    }

    /** [fromQueryString] as JSON text, throwing the [ParamError]. */
    fun tryParseNested(qs: String): String = fromQueryString(qs).toJson()

    private fun topLevelKey(name: String): String {
        val start = name.indexOf('[', 1)
        return if (start < 0 || name.isEmpty()) name else name.substring(0, start)
    }

    /**
     * What `store_nested_param` returned: the params hash it was given, a one-element array (for
     * a trailing `[]` below the top level), or nil (for an empty key).
     */
    private sealed interface Stored {
        data object Params : Stored

        class Array(val items: MutableList<Param>) : Stored

        data object Nil : Stored
    }

    private fun Stored.toParam(params: ParamMap): Param = when (this) {
        Stored.Params -> Param.Hash(params)
        is Stored.Array -> Param.Arr(items)
        Stored.Nil -> Param.Null
    }

    /** `ParamBuilder#store_nested_param`. */
    private fun storeNestedParam(params: ParamMap, name: String, v: Param, depth: Int): Stored {
        if (depth >= DEPTH_LIMIT) throw ParamError.TooDeep()

        val k: String
        val after: String
        if (depth == 0) {
            val start = name.indexOf('[', 1)
            if (start >= 1) {
                k = name.substring(0, start)
                after = name.substring(start)
            } else {
                k = name
                after = ""
            }
        } else if (name.startsWith("[]")) {
            k = "[]"
            after = name.substring(2)
        } else if (name.startsWith('[') && name.indexOf(']', 1) >= 0) {
            val start = name.indexOf(']', 1)
            k = name.substring(1, start)
            after = name.substring(start + 1)
        } else {
            k = name
            after = ""
        }

        if (k.isEmpty()) return Stored.Nil

        if (after.isEmpty()) {
            if (k == "[]" && depth != 0) return Stored.Array(if (v.isNull) ArrayList() else arrayListOf(v))
            params[k] = v
        } else if (after == "[") {
            params[name] = v
        } else if (after == "[]") {
            val array = arraySlot(params, k)
            if (!v.isNull) array.add(v)
        } else if (after.startsWith("[]")) {
            val nested = after.substring(2)
            val childKey = hashInArrayKey(nested) ?: nested
            val array = arraySlot(params, k)
            val last = array.lastOrNull()
            if (last is Param.Hash && !paramsHashHasKey(last.map, childKey)) {
                storeNestedParam(last.map, childKey, v, depth + 1)
            } else {
                val child = ParamMap()
                val stored = storeNestedParam(child, childKey, v, depth + 1)
                array.add(stored.toParam(child))
            }
        } else {
            val child = when (val existing = params[k]) {
                null, Param.Null -> ParamMap()
                is Param.Hash -> existing.map
                else -> throw ParamError.Type("expected Hash (got ${rubyClass(existing)}) for param `$k'")
            }
            val stored = storeNestedParam(child, after, v, depth + 1)
            params[k] = stored.toParam(child)
        }
        return Stored.Params
    }

    /** `x[][y]`'s `y`, when what follows the `[]` is a single bracketed name. */
    private fun hashInArrayKey(nested: String): String? {
        if (nested.length < 2 || nested[0] != '[' || nested[nested.length - 1] != ']') return null
        val inner = nested.substring(1, nested.length - 1)
        if (inner.isEmpty() || inner.indexOf('[') >= 0 || inner.indexOf(']') >= 0) return null
        return inner
    }

    /** `params[k] ||= []` followed by the Array type check. */
    private fun arraySlot(params: ParamMap, k: String): MutableList<Param> {
        val existing = params[k]
        if (existing == null || existing.isNull) {
            val items = ArrayList<Param>()
            params[k] = Param.Arr(items)
            return items
        }
        if (existing is Param.Arr) return existing.items
        throw ParamError.Type("expected Array (got ${rubyClass(existing)}) for param `$k'")
    }

    /** `params_hash_has_key?`: whether the `[`/`]`-separated path in [key] already exists. */
    private fun paramsHashHasKey(hash: ParamMap, key: String): Boolean {
        if (key.contains("[]")) return false
        var current: ParamMap? = hash
        var start = 0
        while (start <= key.length) {
            var end = start
            while (end < key.length && key[end] != '[' && key[end] != ']') end++
            if (end > start) {
                val map = current ?: return false
                val value = map[key.substring(start, end)] ?: return false
                current = value.asHash()
            }
            start = end + 1
        }
        return true
    }

    private fun rubyClass(param: Param): String = when (param) {
        Param.Null -> "NilClass"
        is Param.Bool -> if (param.value) "TrueClass" else "FalseClass"
        is Param.Num -> "Integer"
        is Param.Str -> "String"
        is Param.File -> "ActionDispatch::Http::UploadedFile"
        is Param.Arr -> "Array"
        is Param.Hash -> "ActiveSupport::HashWithIndifferentAccess"
    }

    /**
     * `ParamBuilder.from_hash` for a JSON body: `Parameters::DEFAULT_PARSERS[:json]` wraps
     * non-hash documents as `{ "_json" => data }`, and nils are compacted out of arrays
     * (`NoNilParamEncoder`, i.e. deep munge). Malformed JSON is [ParamError.Parse], with
     * serde_json's strictness: UTF-8 only, no lone surrogates, no infinite numbers, at most 127
     * levels of nesting.
     */
    fun fromJsonBody(body: ByteArray): ParamMap {
        if (!isValidUtf8(body) || hasBomOrNul(body)) throw ParamError.Parse()
        val value = try {
            JSON.createParser(body).use { parser ->
                val token = parser.nextToken() ?: throw ParamError.Parse()
                val value = readJson(parser, token, 0)
                if (parser.nextToken() != null) throw ParamError.Parse()
                value
            }
        } catch (_: java.io.IOException) {
            throw ParamError.Parse()
        }
        return if (value is Param.Hash) value.map else ParamMap().also { it["_json"] = value }
    }

    /** A byte-order mark, or a NUL in the first four bytes (where Jackson would detect UTF-16 or UTF-32). */
    private fun hasBomOrNul(body: ByteArray): Boolean {
        if (body.size >= 3 && body[0] == 0xef.toByte() && body[1] == 0xbb.toByte() && body[2] == 0xbf.toByte()) return true
        for (i in 0 until minOf(4, body.size)) if (body[i] == 0.toByte()) return true
        return false
    }

    /** The value starting at [token], as `Param::from_json` converts serde's `Value`. */
    private fun readJson(parser: JsonParser, token: JsonToken, depth: Int): Param = when (token) {
        JsonToken.START_OBJECT -> {
            if (depth + 1 >= JSON_DEPTH_LIMIT) throw ParamError.Parse()
            val map = ParamMap()
            while (true) {
                val next = parser.nextToken()
                if (next == JsonToken.END_OBJECT) break
                val name = parser.currentName()
                if (hasLoneSurrogate(name)) throw ParamError.Parse()
                map[name] = readJson(parser, parser.nextToken(), depth + 1)
            }
            Param.Hash(map)
        }
        JsonToken.START_ARRAY -> {
            if (depth + 1 >= JSON_DEPTH_LIMIT) throw ParamError.Parse()
            val items = ArrayList<Param>()
            while (true) {
                val next = parser.nextToken()
                if (next == JsonToken.END_ARRAY) break
                if (next == JsonToken.VALUE_NULL) continue
                items.add(readJson(parser, next, depth + 1))
            }
            Param.Arr(items)
        }
        JsonToken.VALUE_STRING -> {
            val text = parser.text
            if (hasLoneSurrogate(text)) throw ParamError.Parse()
            Param.Str(text)
        }
        JsonToken.VALUE_NUMBER_INT -> Param.Num(jsonInteger(parser))
        JsonToken.VALUE_NUMBER_FLOAT -> {
            val d = parser.doubleValue
            if (d.isInfinite()) throw ParamError.Parse()
            Param.Num(d)
        }
        JsonToken.VALUE_TRUE -> Param.Bool(true)
        JsonToken.VALUE_FALSE -> Param.Bool(false)
        JsonToken.VALUE_NULL -> Param.Null
        else -> throw ParamError.Parse()
    }

    private val U64_MAX: BigInteger = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)

    /**
     * An integer literal as serde_json reads it: `u64` or `i64` when it fits, a double otherwise
     * (and `-0` is the double -0.0).
     */
    private fun jsonInteger(parser: JsonParser): Number {
        if (parser.text == "-0") return -0.0
        return when (parser.numberType) {
            JsonParser.NumberType.INT, JsonParser.NumberType.LONG -> parser.longValue
            else -> {
                val big = parser.bigIntegerValue
                if (big.signum() > 0 && big <= U64_MAX) {
                    big
                } else {
                    val d = big.toDouble()
                    if (d.isInfinite()) throw ParamError.Parse()
                    d
                }
            }
        }
    }
}
