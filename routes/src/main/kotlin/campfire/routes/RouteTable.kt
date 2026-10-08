package campfire.routes

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.regex.Pattern

/**
 * Recognizes requests against a route table the way Rails' Journey does, ported from `recognize`,
 * `ROUTES_BY_VERB` and `compile` in `once-campfire-rust/crates/campfire/src/controllers.rs`: the
 * *first* route in table order whose verb and pattern match wins, and HEAD requests match GET
 * routes. Patterns use Rails syntax: `:param` matches `[^/.?]+`, `*glob` matches lazily, and
 * `(.:format)` is the optional format suffix (so `/rooms/1.json` sets `format`).
 *
 * Where Rust searches each verb's patterns at once with a `RegexSet`, this indexes each verb's
 * routes by the first segment of their literal prefix, so a path is only tried against the few
 * routes that could match it, in table order, and matches each candidate with a
 * [SegmentMatcher] rather than its regex; the result is the same first match and captures
 * (checked against a first-match regex scan in the tests). Lookup reads the path in place, so
 * recognition allocates one capture array and the params of the route that matches.
 */
class RouteTable(routes: List<Route>) {
    internal val compiled: List<CompiledRoute> = routes.mapIndexed(::CompiledRoute)

    private val byVerb: Map<String, VerbIndex> =
        compiled.groupBy { it.route.verb }.mapValues { (_, routes) -> VerbIndex(routes) }

    /** The table's rows, in order. */
    val routes: List<Route> = routes.toList()

    /**
     * The first route matching [method] and [path] (already normalized with [normalizePath]),
     * with its path parameters; [Recognition.NotFound] for an unknown verb or path, and
     * [Recognition.BadRequest] when a matched parameter isn't UTF-8 once percent-decoded.
     */
    fun recognize(method: String, path: String): Recognition {
        val index = byVerb[if (method == "HEAD") "GET" else method] ?: return Recognition.NotFound
        if (path.isEmpty() || path[0] != '/') return Recognition.NotFound
        var end = path.indexOf('/', 1)
        if (end < 0) end = path.length
        val slashes = countSlashes(path, index.slashCap)
        var spans: IntArray? = null
        for (candidate in index.candidates(path, 1, end)) {
            if (!candidate.mayMatch(path, slashes)) continue
            val captures = spans ?: IntArray(index.spanCount).also { spans = it }
            if (candidate.matches(path, captures)) return candidate.recognition(path, captures)
        }
        return Recognition.NotFound
    }

    /** The table built from [CampfireRoutes]. */
    companion object {
        /** Campfire's routes, compiled once. */
        val campfire: RouteTable = RouteTable(CampfireRoutes.all)
    }
}

/**
 * `Journey::Router::Utils.normalize_path`, as `normalize_path` in
 * `once-campfire-rust/crates/campfire/src/controllers.rs`: one leading slash, repeated slashes
 * squeezed, trailing slashes dropped, percent-escapes upcased. Returns [path] itself when it is
 * already normal, which is the usual case, so most requests allocate nothing here.
 */
fun normalizePath(path: String): String {
    if (isNormalPath(path)) return path
    val source = "/$path"
    val normalized = StringBuilder(source.length)
    for (c in source) {
        if (c == '/' && normalized.isNotEmpty() && normalized[normalized.length - 1] == '/') continue
        normalized.append(c)
    }
    while (normalized.length > 1 && normalized[normalized.length - 1] == '/') normalized.setLength(normalized.length - 1)
    var i = 0
    while (i + 2 < normalized.length) {
        if (normalized[i] == '%' && isHex(normalized[i + 1]) && isHex(normalized[i + 2])) {
            normalized.setCharAt(i + 1, normalized[i + 1].uppercaseChar())
            normalized.setCharAt(i + 2, normalized[i + 2].uppercaseChar())
            i += 3
        } else {
            i++
        }
    }
    return normalized.toString()
}

/** Whether [normalizePath] would return [path] unchanged. */
private fun isNormalPath(path: String): Boolean {
    if (path.isEmpty() || path[0] != '/') return false
    if (path.length > 1 && path[path.length - 1] == '/') return false
    for (i in 1 until path.length) {
        val c = path[i]
        if (c == '/' && path[i - 1] == '/') return false
        if (c == '%' && i + 2 < path.length && isHex(path[i + 1]) && isHex(path[i + 2]) &&
            (path[i + 1] in 'a'..'f' || path[i + 2] in 'a'..'f')
        ) {
            return false
        }
    }
    return true
}

/**
 * The number of `/` in [path], counting no further than [cap]: past the most any pattern spells
 * out, the exact count no longer changes which routes [CompiledRoute.mayMatch] lets through.
 */
private fun countSlashes(path: String, cap: Int): Int {
    var count = 0
    var at = path.indexOf('/')
    while (at >= 0 && count < cap) {
        count++
        at = path.indexOf('/', at + 1)
    }
    return count
}

/** An ASCII hex digit, either case. */
private fun isHex(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

/**
 * A route with its pattern compiled: the anchored regex and parameter names `compile` in
 * `controllers.rs` produces, the [SegmentMatcher] that stands in for the regex when the
 * pattern's shape allows, and the literal text every matching path starts with.
 */
internal class CompiledRoute(val index: Int, val route: Route) {
    val regex: Pattern
    val names: Array<String>
    val literalPrefix: String = route.pattern.takeWhile { it != '(' && it != ':' && it != '*' }

    init {
        require(route.pattern.startsWith("/")) { "route patterns start with a slash: ${route.pattern}" }
        val (source, names) = compilePattern(route.pattern)
        regex = Pattern.compile(source)
        this.names = names.toTypedArray()
    }

    private val segments: SegmentMatcher? = SegmentMatcher.compile(route.pattern)

    /** The number of `/` in the pattern. */
    val slashes: Int = route.pattern.count { it == '/' }
    private val hasGlob: Boolean = '*' in route.pattern

    /**
     * A cheap test every matching path passes: it starts with [literalPrefix], and has as many
     * `/` as the pattern ([pathSlashes] of them), or at least as many when a glob can take more,
     * since no param or format can contain one.
     */
    fun mayMatch(path: String, pathSlashes: Int): Boolean =
        (if (hasGlob) pathSlashes >= slashes else pathSlashes == slashes) && path.startsWith(literalPrefix)

    /** Whether the hand matcher stands in for the regex. */
    val matchesByHand: Boolean
        get() = segments != null

    /** The first segment of [literalPrefix], without its leading slash. */
    val firstSegment: String = literalPrefix.substring(1).substringBefore('/')

    /**
     * Whether [literalPrefix] continues past its first segment, so a path can only match when its
     * own first segment is exactly [firstSegment]. Otherwise it need only start with it.
     */
    val closed: Boolean = literalPrefix.indexOf('/', 1) >= 0

    /**
     * Whether [path] matches the whole pattern, by hand or by regex, with the captures in [spans]
     * as start/end pairs (`-1` for one that took no part); [spans] holds at least two per name.
     */
    fun matches(path: String, spans: IntArray): Boolean = segments?.matches(path, spans) ?: matchesByRegex(path, spans)

    /** [matches] by the regex alone: the reference the hand matcher must agree with. */
    fun matchesByRegex(path: String, spans: IntArray): Boolean {
        val matcher = regex.matcher(path)
        if (!matcher.matches()) return false
        for (i in names.indices) {
            spans[i * 2] = matcher.start(i + 1)
            spans[i * 2 + 1] = matcher.end(i + 1)
        }
        return true
    }

    /**
     * The match as `path_params` in `controllers.rs` builds it: defaults, then captures that took
     * part (percent-decoded), then `controller` and `action`.
     */
    fun recognition(path: String, spans: IntArray): Recognition {
        val params = LinkedHashMap<String, String>(route.defaults.size + names.size + 4)
        params.putAll(route.defaults)
        for (i in names.indices) {
            val start = spans[i * 2]
            if (start < 0) continue
            params[names[i]] = unescapeUri(path.substring(start, spans[i * 2 + 1])) ?: return Recognition.BadRequest(INVALID_ENCODING)
        }
        params["controller"] = route.controller
        params["action"] = route.actionName
        return Recognition.Matched(route, index, params)
    }
}

/** The message Rails' `ActionController::BadRequest` carries for a parameter that isn't UTF-8. */
private const val INVALID_ENCODING = "Invalid path parameters: Invalid encoding"

/**
 * A Journey path spec as an anchored regex source (anchored by `Matcher.matches`, as Rust's `^…$`
 * anchors the whole text) plus its parameter names in order, as `compile` in `controllers.rs`.
 * The glob is `[^\n]+?` because Java's `.` also refuses `\r` and other line terminators, where
 * Rust's refuses only `\n`.
 */
internal fun compilePattern(pattern: String): Pair<String, List<String>> {
    val regex = StringBuilder()
    val names = ArrayList<String>()
    var i = 0
    while (i < pattern.length) {
        when (val c = pattern[i++]) {
            '(' -> regex.append("(?:")
            ')' -> regex.append(")?")
            ':', '*' -> {
                val start = i
                while (i < pattern.length && (pattern[i].isAsciiLetterOrDigit() || pattern[i] == '_')) i++
                names.add(pattern.substring(start, i))
                regex.append(if (c == ':') "([^/.?]+)" else "([^\\n]+?)")
            }
            else -> regex.append(escapeLiteral(c))
        }
    }
    return regex.toString() to names
}

/** An ASCII letter or digit (`is_ascii_alphanumeric`). */
private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

/** A literal pattern character as regex source. */
private fun escapeLiteral(c: Char): String = when {
    c.isAsciiLetterOrDigit() -> c.toString()
    c.code < 128 -> "\\$c"
    else -> Pattern.quote(c.toString())
}

/**
 * `Journey::Router::Utils.unescape_uri` (percent-decoding, leaving `%` that isn't followed by two
 * hex digits alone), then Rails' check that the result is valid UTF-8: null when it isn't, as
 * `unescape_uri` in `controllers.rs`. Returns [value] itself when it has no `%`.
 */
internal fun unescapeUri(value: String): String? {
    if (value.indexOf('%') < 0) return value
    val bytes = value.toByteArray(Charsets.UTF_8)
    val decoded = ByteArray(bytes.size)
    var length = 0
    var i = 0
    while (i < bytes.size) {
        val b = bytes[i]
        if (b == '%'.code.toByte() && i + 2 < bytes.size && hexValue(bytes[i + 1]) >= 0 && hexValue(bytes[i + 2]) >= 0) {
            decoded[length++] = (hexValue(bytes[i + 1]) * 16 + hexValue(bytes[i + 2])).toByte()
            i += 3
        } else {
            decoded[length++] = b
            i++
        }
    }
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return try {
        decoder.decode(ByteBuffer.wrap(decoded, 0, length)).toString()
    } catch (_: CharacterCodingException) {
        null
    }
}

/** The value of an ASCII hex digit byte, or -1. */
private fun hexValue(b: Byte): Int = when (val c = b.toInt().toChar()) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> -1
}

/**
 * One verb's routes, indexed by the first segment of their literal prefix. For each segment that
 * some route spells out, the candidates are, in table order, the routes whose prefix continues
 * past exactly that segment plus the routes whose prefix ends inside it (`/up` for `up.json`);
 * any other segment can only match the latter, checked with `startsWith`. The index only narrows
 * the routes tried: every candidate is still checked in full by [CompiledRoute.matches].
 */
private class VerbIndex(routes: List<CompiledRoute>) {
    private val open: Array<CompiledRoute> = routes.filter { !it.closed }.toTypedArray()

    /** One more `/` than any of the verb's patterns has. */
    val slashCap: Int = routes.maxOf { it.slashes } + 1

    /** The capture array size that fits every route of the verb. */
    val spanCount: Int = routes.maxOf { it.names.size } * 2
    private val keys: Array<String?>
    private val values: Array<Array<CompiledRoute>?>
    private val mask: Int

    init {
        val segments = routes.filter { it.closed }.map { it.firstSegment }.distinct()
        var capacity = 8
        while (capacity < segments.size * 4) capacity *= 2
        mask = capacity - 1
        keys = arrayOfNulls(capacity)
        values = arrayOfNulls(capacity)
        for (segment in segments) {
            var slot = hash(segment, 0, segment.length) and mask
            while (keys[slot] != null) slot = (slot + 1) and mask
            keys[slot] = segment
            values[slot] = routes
                .filter { if (it.closed) it.firstSegment == segment else segment.startsWith(it.firstSegment) }
                .toTypedArray()
        }
    }

    /** The routes that could match a path whose first segment is `path[start, end)`, in table order. */
    fun candidates(path: String, start: Int, end: Int): Array<CompiledRoute> {
        val length = end - start
        var slot = hash(path, start, end) and mask
        while (true) {
            val key = keys[slot] ?: return open
            if (key.length == length && path.regionMatches(start, key, 0, length)) return values[slot]!!
            slot = (slot + 1) and mask
        }
    }

    /** `String.hashCode` of `text[start, end)`, spread, without taking the substring. */
    private fun hash(text: String, start: Int, end: Int): Int {
        var h = 0
        for (i in start until end) h = 31 * h + text[i].code
        return h xor (h ushr 16)
    }
}
