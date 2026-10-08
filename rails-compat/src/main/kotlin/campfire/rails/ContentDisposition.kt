package campfire.rails

/**
 * `ActionDispatch::Http::ContentDisposition.format` (actionpack's
 * `action_dispatch/http/content_disposition.rb`), after
 * `once-campfire-rust/crates/rails_compat/src/content_disposition.rs`: as `send_file`, `send_data`
 * and Active Storage name a download, an ASCII `filename=` for old clients, transliterated with
 * I18n's default approximations, and the full name in RFC 5987's `filename*=`.
 */
object ContentDisposition {
    private val HEX = "0123456789ABCDEF".toCharArray()

    /** `ContentDisposition.format(disposition:, filename:)` for a filename. */
    fun format(disposition: String, filename: String): String {
        val out = StringBuilder(disposition.length + filename.length * 3 + 32)
        out.append(disposition).append("; filename=\"")
        percentEscape(out, transliterate(filename), ::traditional)
        out.append("\"; filename*=UTF-8''")
        percentEscape(out, filename, ::rfc5987)
        return out.toString()
    }

    /** What `TRADITIONAL_ESCAPED_CHAR` leaves alone: ``[ A-Za-z0-9!#$+.^_`|~-]``. */
    private fun traditional(b: Int): Boolean = b == ' '.code || alphanumeric(b) || "!#$+.^_`|~-".indexOf(b.toChar()) >= 0

    /** What `RFC_5987_ESCAPED_CHAR` leaves alone: ``[A-Za-z0-9!#$&+.^_`|~-]``. */
    private fun rfc5987(b: Int): Boolean = alphanumeric(b) || "!#$&+.^_`|~-".indexOf(b.toChar()) >= 0

    private fun alphanumeric(b: Int): Boolean = b in 'a'.code..'z'.code || b in 'A'.code..'Z'.code || b in '0'.code..'9'.code

    /**
     * `percent_escape(string, pattern)`: each escaped character's UTF-8 bytes as `%XX`. The kept
     * characters are all ASCII, so going byte by byte is the same.
     */
    private fun percentEscape(out: StringBuilder, s: String, keep: (Int) -> Boolean) {
        for (byte in s.toByteArray(Charsets.UTF_8)) {
            val b = byte.toInt() and 0xff
            if (keep(b)) out.append(b.toChar()) else out.append('%').append(HEX[b shr 4]).append(HEX[b and 0xf])
        }
    }

    /** `I18n.transliterate` with the default approximations, and "?" for anything else non-ASCII. */
    private fun transliterate(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (cp < 128) {
                out.append(cp.toChar())
            } else {
                val index = ContentDispositionApproximations.CODE_POINTS.binarySearch(cp)
                out.append(if (index >= 0) ContentDispositionApproximations.REPLACEMENTS[index] else "?")
            }
            i += Character.charCount(cp)
        }
        return out.toString()
    }
}
