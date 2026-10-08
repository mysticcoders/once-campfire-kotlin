package campfire.kit

/**
 * ASCII-only text checks the middleware runs on header values on every request, written as scans
 * so the hot path needs no regex and no lowercased copies (Rust's `(?-u)\b` regexes and
 * `to_ascii_lowercase` comparisons in `crates/kit/src/front/` and `crates/kit/src/deflater.rs`).
 */
internal object Ascii {
    /** `[0-9A-Za-z_]`, the characters an ASCII `\b` separates from the rest. */
    fun isWord(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'

    /** Whether [c] is the space a Rust `str::trim` drops (ASCII whitespace here). */
    fun isSpace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000c' || c == '\u000b'

    /** Whether [word] appears in [haystack] with a word boundary on each side (`/\bword\b/`). */
    fun hasWord(haystack: String, word: String): Boolean = wordAt(haystack, word, 0) >= 0

    /** The index of the first occurrence of [word] at or after [from] bounded by `\b` on both sides, or -1. */
    fun wordAt(haystack: String, word: String, from: Int): Int {
        var i = haystack.indexOf(word, from)
        while (i >= 0) {
            val end = i + word.length
            if (boundary(haystack, i) && boundary(haystack, end)) return i
            i = haystack.indexOf(word, i + 1)
        }
        return -1
    }

    /** Whether position [at] of [s] is an ASCII word boundary (`\b`): a word character on exactly one side. */
    fun boundary(s: String, at: Int): Boolean {
        val before = at > 0 && isWord(s[at - 1])
        val after = at < s.length && isWord(s[at])
        return before != after
    }

    /** Whether `s[start, end)` equals [word] ignoring ASCII case ([word] is lowercase). */
    fun regionEquals(s: String, start: Int, end: Int, word: String): Boolean {
        if (end - start != word.length) return false
        for (k in word.indices) if (lower(s[start + k]) != word[k]) return false
        return true
    }

    /** [c] lowercased if it's an ASCII capital. */
    fun lower(c: Char): Char = if (c in 'A'..'Z') c + 32 else c

    /** The start of `s[start, end)` with leading spaces skipped. */
    fun trimStart(s: String, start: Int, end: Int): Int {
        var i = start
        while (i < end && isSpace(s[i])) i++
        return i
    }

    /** The end of `s[start, end)` with trailing spaces dropped. */
    fun trimEnd(s: String, start: Int, end: Int): Int {
        var j = end
        while (j > start && isSpace(s[j - 1])) j--
        return j
    }

    /** Whether any comma-separated token of [list], trimmed, equals [token] ignoring ASCII case. */
    fun listHas(list: String, token: String): Boolean {
        var start = 0
        while (start <= list.length) {
            var end = list.indexOf(',', start)
            if (end < 0) end = list.length
            val a = trimStart(list, start, end)
            if (regionEquals(list, a, trimEnd(list, a, end), token)) return true
            start = end + 1
        }
        return false
    }
}
