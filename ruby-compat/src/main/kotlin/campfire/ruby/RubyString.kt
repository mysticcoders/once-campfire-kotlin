package campfire.ruby

/**
 * Ruby's `ISSPACE`: what `String#to_i` and `#to_f` skip, and `\s` in a regexp
 * (`once-campfire-rust/crates/ruby/src/string.rs`, `SPACE`).
 */
internal fun isRubySpace(c: Char): Boolean =
    c == ' ' || c == '\t' || c == '\n' || c == '\u000b' || c == '\u000c' || c == '\r'

/** The index of the first character of [s] at or after [from] that isn't Ruby's `ISSPACE`. */
internal fun skipRubySpace(s: CharSequence, from: Int = 0): Int {
    var i = from
    while (i < s.length && isRubySpace(s[i])) i++
    return i
}

/**
 * `String#strip`: NUL and ASCII whitespace off both ends, not Unicode spaces like U+00A0
 * (`once-campfire-rust/crates/ruby/src/string.rs`, `strip`). Returns [s] itself when there's
 * nothing to strip.
 */
fun strip(s: String): String {
    var start = 0
    var end = s.length
    while (start < end && isStripped(s[start])) start++
    while (end > start && isStripped(s[end - 1])) end--
    return if (start == 0 && end == s.length) s else s.substring(start, end)
}

private fun isStripped(c: Char): Boolean = c == '\u0000' || isRubySpace(c)
