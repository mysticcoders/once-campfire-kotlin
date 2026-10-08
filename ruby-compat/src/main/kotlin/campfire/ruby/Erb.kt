package campfire.ruby

/**
 * `ERB::Util.html_escape` (and `h`): `& < > " '` become `&amp; &lt; &gt; &quot; &#39;`
 * (`once-campfire-rust/crates/ruby/src/erb.rs`).
 */
object Erb {
    /**
     * `ERB::Util.html_escape(s)` (`erb.rs`, `html_escape`). Returns [s] itself, without copying,
     * when it has nothing to escape.
     */
    fun htmlEscape(s: String): String {
        val first = firstEscaped(s, 0)
        if (first < 0) return s
        val out = StringBuilder(s.length + 16)
        writeHtmlEscaped(out, s)
        return out.toString()
    }

    /** `ERB::Util.html_escape(s)` appended to [out] (`erb.rs`, `push_html_escaped`). */
    fun appendHtmlEscaped(out: StringBuilder, s: CharSequence) {
        writeHtmlEscaped(out, s)
    }

    /**
     * `ERB::Util.html_escape(s)` written to [dest] (`erb.rs`, `write_html_escaped`): the text
     * between the escaped characters goes a run at a time, with no intermediate string.
     */
    fun writeHtmlEscaped(dest: Appendable, s: CharSequence) {
        var last = 0
        var index = firstEscaped(s, 0)
        while (index >= 0) {
            dest.append(s, last, index)
            dest.append(replacement(s[index]))
            last = index + 1
            index = firstEscaped(s, last)
        }
        dest.append(s, last, s.length)
    }

    /** The index of the first character of [s] at or after [from] that escaping replaces, or -1. */
    private fun firstEscaped(s: CharSequence, from: Int): Int {
        for (i in from until s.length) {
            when (s[i]) {
                '&', '<', '>', '"', '\'' -> return i
            }
        }
        return -1
    }

    /** What one of the five escaped characters becomes. */
    private fun replacement(c: Char): String = when (c) {
        '&' -> "&amp;"
        '<' -> "&lt;"
        '>' -> "&gt;"
        '"' -> "&quot;"
        else -> "&#39;"
    }
}
