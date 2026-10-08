package campfire.ruby

private val HEX = "0123456789ABCDEF".toCharArray()

/**
 * `CGI.escape`: everything but `A-Za-z0-9_.-~` is percent-encoded (as UTF-8), and a space becomes
 * `+` (`once-campfire-rust/crates/ruby/src/uri.rs`, `cgi_escape`).
 */
fun cgiEscape(s: String): String = percentEncode(s, spaceAsPlus = true)

/**
 * `ERB::Util.url_encode`: everything but `A-Za-z0-9_.-~` is percent-encoded (as UTF-8), a space as
 * `%20` (`once-campfire-rust/crates/ruby/src/uri.rs`, `url_encode`). Addressable's
 * `encode_component(s, UNRESERVED)` keeps the same set.
 */
fun urlEncode(s: String): String = percentEncode(s, spaceAsPlus = false)

/**
 * Percent-encodes the UTF-8 bytes of [s] outside the unreserved set. Returns [s] itself when every
 * character is unreserved; otherwise encodes each character's UTF-8 bytes straight into the
 * output, without an intermediate byte array. An unpaired surrogate is encoded as `?`, as
 * `String.toByteArray` would.
 */
private fun percentEncode(s: String, spaceAsPlus: Boolean): String {
    var i = 0
    while (i < s.length && isUnreserved(s[i])) i++
    if (i == s.length) return s
    val out = StringBuilder(s.length + 16)
    out.append(s, 0, i)
    while (i < s.length) {
        val c = s[i]
        when {
            isUnreserved(c) -> out.append(c)
            c == ' ' && spaceAsPlus -> out.append('+')
            c.code < 0x80 -> appendByte(out, c.code)
            c.code < 0x800 -> {
                appendByte(out, 0xc0 or (c.code shr 6))
                appendByte(out, 0x80 or (c.code and 0x3f))
            }
            Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> {
                val code = Character.toCodePoint(c, s[i + 1])
                appendByte(out, 0xf0 or (code shr 18))
                appendByte(out, 0x80 or ((code shr 12) and 0x3f))
                appendByte(out, 0x80 or ((code shr 6) and 0x3f))
                appendByte(out, 0x80 or (code and 0x3f))
                i++
            }
            Character.isSurrogate(c) -> appendByte(out, '?'.code)
            else -> {
                appendByte(out, 0xe0 or (c.code shr 12))
                appendByte(out, 0x80 or ((c.code shr 6) and 0x3f))
                appendByte(out, 0x80 or (c.code and 0x3f))
            }
        }
        i++
    }
    return out.toString()
}

private fun isUnreserved(c: Char): Boolean =
    c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '_' || c == '.' || c == '-' || c == '~'

private fun appendByte(out: StringBuilder, byte: Int) {
    out.append('%').append(HEX[byte shr 4]).append(HEX[byte and 0xf])
}
