package campfire.routes

/**
 * A route pattern matched by hand instead of by regex, giving the same match and captures as the
 * anchored regex [compilePattern] builds (the regex `compile` in
 * `once-campfire-rust/crates/campfire/src/controllers.rs` builds), for the shapes every Campfire
 * route has: literals, `:param`s each followed by a literal starting with `/` or by the end, at
 * most one `*glob`, and an optional `(.:format)` at the very end.
 *
 * For those shapes the regex never needs to backtrack into a choice: a param (`[^/.?]+`) can't
 * consume the `/` or `.` that follows it, so it is always maximal; and the lazy glob
 * (`[^\n]+?`) followed by `(?:\.([^/.?]+))?$` stops at the last `.` when what follows it is a
 * format, and runs to the end otherwise. [compile] returns null for any other shape, which then
 * matches by regex. The tests check both against each other over a large corpus of paths.
 */
internal class SegmentMatcher private constructor(private val tokens: Array<Token>) {
    /** One piece of a pattern. */
    private sealed interface Token

    /** Literal text, compared exactly. */
    private class Literal(val text: String) : Token

    /** `:name`, capture [slot]. */
    private class Param(val slot: Int) : Token

    /** `*name`, capture [slot]. */
    private class Glob(val slot: Int) : Token

    /** `(.:format)`, capture [slot], always the last token. */
    private class Format(val slot: Int) : Token

    /**
     * Whether [path] matches the whole pattern. Captures go into [spans] as start/end pairs per
     * parameter slot, `-1` for an optional one that took no part.
     */
    fun matches(path: String, spans: IntArray): Boolean {
        var pos = 0
        for (token in tokens) {
            when (token) {
                is Literal -> {
                    if (!path.startsWith(token.text, pos)) return false
                    pos += token.text.length
                }
                is Param -> {
                    val end = paramEnd(path, pos)
                    if (end == pos) return false
                    spans[token.slot * 2] = pos
                    spans[token.slot * 2 + 1] = end
                    pos = end
                }
                is Glob -> return matchGlob(path, pos, token.slot, spans)
                is Format -> return matchFormat(path, pos, token.slot, spans)
            }
        }
        return pos == path.length
    }

    /** The glob from [start], then the optional format the glob is always followed by, then the end. */
    private fun matchGlob(path: String, start: Int, slot: Int, spans: IntArray): Boolean {
        val format = tokens.last() as? Format
        val dot = path.lastIndexOf('.')
        val end = if (format != null && dot > start && isFormat(path, dot + 1)) dot else path.length
        if (end == start || path.indexOf('\n', start).let { it in 0 until end }) return false
        spans[slot * 2] = start
        spans[slot * 2 + 1] = end
        if (format == null) return true
        return matchFormat(path, end, format.slot, spans)
    }

    /** The optional `.format` from [start] to the end, or nothing left at all. */
    private fun matchFormat(path: String, start: Int, slot: Int, spans: IntArray): Boolean {
        spans[slot * 2] = -1
        spans[slot * 2 + 1] = -1
        if (start == path.length) return true
        if (path[start] != '.' || !isFormat(path, start + 1)) return false
        spans[slot * 2] = start + 1
        spans[slot * 2 + 1] = path.length
        return true
    }

    /** Factory for [SegmentMatcher]. */
    companion object {
        /** The pattern's tokens, or null when its shape needs the regex. */
        fun compile(pattern: String): SegmentMatcher? {
            val formatSuffix = "(.:format)"
            val body = pattern.removeSuffix(formatSuffix)
            if ('(' in body || ')' in body) return null
            val tokens = ArrayList<Token>()
            var slot = 0
            var i = 0
            while (i < body.length) {
                val c = body[i]
                if (c == ':' || c == '*') {
                    i++
                    while (i < body.length && (body[i] in 'a'..'z' || body[i] in 'A'..'Z' || body[i] in '0'..'9' || body[i] == '_')) i++
                    tokens.add(if (c == ':') Param(slot++) else Glob(slot++))
                } else {
                    val start = i
                    while (i < body.length && body[i] != ':' && body[i] != '*') i++
                    tokens.add(Literal(body.substring(start, i)))
                }
            }
            if (body.length != pattern.length) tokens.add(Format(slot))
            return if (isSupported(tokens)) SegmentMatcher(tokens.toTypedArray()) else null
        }

        /** Every param is followed by a `/` literal, the format or the end; a glob only by the format or the end. */
        private fun isSupported(tokens: List<Token>): Boolean = tokens.withIndex().all { (i, token) ->
            val next = tokens.getOrNull(i + 1)
            when (token) {
                is Param -> next == null || next is Format || (next is Literal && next.text.startsWith("/"))
                is Glob -> next == null || next is Format
                is Literal, is Format -> true
            }
        }

        /** Where a `[^/.?]+` run starting at [start] ends. */
        private fun paramEnd(path: String, start: Int): Int {
            var i = start
            while (i < path.length) {
                val c = path[i]
                if (c == '/' || c == '.' || c == '?') break
                i++
            }
            return i
        }

        /** Whether `path[start..]` is a nonempty `[^/.?]+` run to the end. */
        private fun isFormat(path: String, start: Int): Boolean = start < path.length && paramEnd(path, start) == path.length
    }
}
