package campfire.richtext

/**
 * A URI as Ruby's `URI.parse` splits it (`once-campfire-rust/crates/richtext/src/uri.rs`, `Uri`).
 * [port] is unsigned, as Rust's `u64` (an over-long port reads as `u64::MAX`).
 */
data class RubyUri(
    val scheme: String?,
    val userinfo: String?,
    var host: String?,
    var port: ULong?,
    val path: String?,
    val opaque: String?,
    var query: String?,
    val fragment: String?,
) {
    /** `uri.is_a?(URI::HTTP)`, which includes `URI::HTTPS`. */
    fun isHttp(): Boolean = scheme != null && (scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true))

    private fun defaultPort(): ULong? = when (scheme?.uppercase()) {
        "HTTP", "WS" -> 80u
        "HTTPS", "WSS" -> 443u
        "FTP" -> 21u
        "LDAP" -> 389u
        "LDAPS" -> 636u
        else -> null
    }

    /** `URI::Generic#to_s`. */
    fun toS(): String {
        val s = StringBuilder()
        if (scheme != null) s.append(scheme).append(':')
        if (opaque != null) {
            s.append(opaque)
        } else {
            if (host != null || scheme == "file" || scheme == "postgres") s.append("//")
            if (userinfo != null) s.append(userinfo).append('@')
            if (host != null) s.append(host)
            val p = port
            if (p != null && p != defaultPort()) s.append(':').append(p.toString())
            s.append(path ?: "")
            if (query != null) s.append('?').append(query)
        }
        if (fragment != null) s.append('#').append(fragment)
        return s.toString()
    }

    /** What [parse] returns: a URI, or the error Ruby raises. */
    sealed interface Parsed {
        /** The parsed URI. */
        class Ok(val uri: RubyUri) : Parsed

        /** `URI::InvalidURIError`, which callers rescue. */
        data object InvalidUri : Parsed

        /** `URI::InvalidComponentError` (from `URI::MailTo`), which nothing rescues. */
        data object InvalidComponent : Parsed
    }

    companion object {
        /**
         * The slice of Ruby's `URI.parse` (uri 1.1, RFC 3986 parser) that the opengraph URL checks
         * and tweet URL normalization rely on, including which inputs raise which error (`uri.rs`).
         */
        fun parse(value: String): Parsed {
            if (value.any { it.code >= 0x80 }) return Parsed.InvalidUri
            val uri = splitAbsolute(value) ?: splitRelative(value) ?: return Parsed.InvalidUri
            val query = uri.query
            if (query != null) uri.query = escapeQuery(query) ?: return Parsed.InvalidUri
            uri.port = uri.port ?: uri.defaultPort()
            return checkSchemeClass(uri) ?: Parsed.Ok(uri)
        }

        private fun checkSchemeClass(uri: RubyUri): Parsed? = when (uri.scheme?.uppercase()) {
            "MAILTO" -> {
                val opaque = uri.opaque ?: uri.query?.let { "?$it" }
                if (opaque == null) {
                    Parsed.InvalidComponent
                } else {
                    val q = opaque.indexOf('?')
                    val to = if (q >= 0) opaque.substring(0, q) else opaque
                    if (mailtoToValid(to)) null else Parsed.InvalidComponent
                }
            }
            "LDAP", "LDAPS" -> if (uri.fragment != null || uri.path == null) Parsed.InvalidUri else null
            "FTP" -> if (uri.path == null) Parsed.InvalidUri else null
            else -> null
        }

        /** `/\A(?:[^@,;]+@[^@,;]+(?:\z|[,;]))*\z/` */
        private fun mailtoToValid(to: String): Boolean {
            var i = 0
            fun special(c: Char) = c == '@' || c == ',' || c == ';'
            while (i < to.length) {
                val start = i
                while (i < to.length && !special(to[i])) i++
                if (i == start || i >= to.length || to[i] != '@') return false
                i++
                val domain = i
                while (i < to.length && !special(to[i])) i++
                if (i == domain) return false
                if (i < to.length) {
                    if (to[i] == '@') return false
                    i++
                }
            }
            return true
        }

        /** `URI::Generic#query=`: rejects `%` followed by two non-hex characters and escapes the rest. */
        private fun escapeQuery(query: String): String? {
            val cleaned = query.filter { it != '\t' && it != '\r' && it != '\n' }
            for (i in 0..cleaned.length - 3) {
                if (cleaned[i] == '%' && !isHex(cleaned[i + 1]) && !isHex(cleaned[i + 2])) return null
            }
            val out = StringBuilder()
            for (i in cleaned.indices) {
                val b = cleaned[i]
                val escape = i + 2 < cleaned.length && b == '%' && isHex(cleaned[i + 1]) && isHex(cleaned[i + 2])
                if (escape || b == '!' || b in '$'..'&' || b in '('..';' || b == '=' || b in '?'..'_' || b in 'a'..'~') {
                    out.append(b)
                } else {
                    out.append('%').append("%02X".format(b.code))
                }
            }
            return out.toString()
        }

        private fun isHex(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

        /** `[!$&-.0-9;=A-Z_a-z~]`: `&-.` covers `& ' ( ) * + , - .`. */
        private fun isUnreservedOrSub(b: Char): Boolean =
            b == '!' || b == '$' || b in '&'..'.' || b in '0'..'9' || b == ';' || b == '=' || b in 'A'..'Z' || b == '_' ||
                b in 'a'..'z' || b == '~'

        private fun pctAt(s: String, i: Int): Boolean = i + 2 < s.length && s[i] == '%' && isHex(s[i + 1]) && isHex(s[i + 2])

        /** Consumes `(?:%\h\h|[class])*` possessively and returns the end index. */
        private inline fun takeWhileClass(s: String, from: Int, limit: Int = s.length, cls: (Char) -> Boolean): Int {
            var i = from
            while (true) {
                if (i + 2 < limit && pctAt(s, i)) {
                    i += 3
                } else if (i < limit && cls(s[i])) {
                    i++
                } else {
                    return i
                }
            }
        }

        private fun segChar(b: Char) = isUnreservedOrSub(b) || b == ':' || b == '@' || b == '/'

        private fun segNcChar(b: Char) = isUnreservedOrSub(b) || b == '@'

        private fun fragmentChar(b: Char) = isUnreservedOrSub(b) || b == ':' || b == '@' || b == '/' || b == '?'

        private fun userinfoChar(b: Char) = isUnreservedOrSub(b) || b == ':'

        /** Matches the IP-literal alternative of HOST (a bracketed address); returns its end. */
        private fun ipLiteral(s: String, i: Int, limit: Int): Int? {
            if (i >= limit || s[i] != '[') return null
            var close = -1
            for (j in i until limit) {
                if (s[j] == ']') {
                    close = j
                    break
                }
            }
            if (close < 0) return null
            val inner = s.substring(i + 1, close)
            val valid = if (inner.startsWith('v') || inner.startsWith('V')) {
                val future = inner.substring(1)
                val dot = future.indexOf('.')
                if (dot < 0) {
                    false
                } else {
                    val hex = future.substring(0, dot)
                    val rest = future.substring(dot + 1)
                    hex.isNotEmpty() && hex.all(::isHex) && rest.isNotEmpty() && rest.all { isUnreservedOrSub(it) || it == ':' }
                }
            } else {
                Ipv6.parses(inner) && !inner.contains('%')
            }
            return if (valid) close + 1 else null
        }

        private class Tail(val hierEnd: Int, val query: IntArray?, val fragment: IntArray?)

        /** Splits off `(?:\?(?<query>[^#]*+))?(?:\#(?<fragment>FRAGMENT))?\z` from the end of the hier-part. */
        private fun parseQueryFragmentPositions(s: String, start: Int): Tail? {
            var hierEnd = s.length
            for (i in start until s.length) {
                if (s[i] == '?' || s[i] == '#') {
                    hierEnd = i
                    break
                }
            }
            var i = hierEnd
            var query: IntArray? = null
            if (i < s.length && s[i] == '?') {
                var qEnd = s.length
                for (j in i + 1 until s.length) {
                    if (s[j] == '#') {
                        qEnd = j
                        break
                    }
                }
                query = intArrayOf(i + 1, qEnd)
                i = qEnd
            }
            var fragment: IntArray? = null
            if (i < s.length && s[i] == '#') {
                val fEnd = takeWhileClass(s, i + 1) { fragmentChar(it) }
                if (fEnd != s.length) return null
                fragment = intArrayOf(i + 1, s.length)
                i = s.length
            }
            return if (i == s.length) Tail(hierEnd, query, fragment) else null
        }

        private class Authority(val userinfo: String?, val host: String, val port: ULong?, val end: Int)

        private fun parseAuthority(value: String, start: Int, limit: Int): Authority? {
            var i = start
            var userinfo: String? = null
            val uiEnd = takeWhileClass(value, i, limit) { userinfoChar(it) }
            if (uiEnd < limit && value[uiEnd] == '@') {
                userinfo = value.substring(i, uiEnd)
                i = uiEnd + 1
            }
            val hostEnd = ipLiteral(value, i, limit) ?: takeWhileClass(value, i, limit) { isUnreservedOrSub(it) }
            val host = value.substring(i, hostEnd)
            var end = hostEnd
            var port: ULong? = null
            if (end < limit && value[end] == ':') {
                var digitsEnd = limit
                for (j in end + 1 until limit) {
                    if (value[j] !in '0'..'9') {
                        digitsEnd = j
                        break
                    }
                }
                val digits = value.substring(end + 1, digitsEnd)
                port = if (digits.isEmpty()) null else digits.toULongOrNull() ?: ULong.MAX_VALUE
                end = digitsEnd
            }
            if (end < limit && value[end] != '/') return null
            return Authority(userinfo, host, port, end)
        }

        private fun splitAbsolute(value: String): RubyUri? {
            val s = value
            if (s.isEmpty() || !s[0].isAsciiLetter()) return null
            var i = 1
            while (i < s.length && (s[i].isAsciiLetter() || s[i] in '0'..'9' || s[i] == '+' || s[i] == '-' || s[i] == '.')) i++
            if (i >= s.length || s[i] != ':') return null
            val scheme = value.substring(0, i).lowercase()
            val restStart = i + 1
            val tail = parseQueryFragmentPositions(s, restStart) ?: return null
            val hierLen = tail.hierEnd - restStart
            val query = tail.query?.let { value.substring(it[0], it[1]) }
            val fragment = tail.fragment?.let { value.substring(it[0], it[1]) }
            if (hierLen >= 2 && s[restStart] == '/' && s[restStart + 1] == '/') {
                val authority = parseAuthority(value, restStart + 2, tail.hierEnd) ?: return null
                val pathEnd = tail.hierEnd
                if (pathEnd > authority.end &&
                    (s[authority.end] != '/' || takeWhileClass(s, authority.end, pathEnd) { segChar(it) } != pathEnd)
                ) {
                    return null
                }
                return RubyUri(
                    scheme, authority.userinfo, authority.host, authority.port, value.substring(authority.end, pathEnd),
                    null, query, fragment,
                )
            }
            if (hierLen >= 1 && s[restStart] == '/') {
                if (takeWhileClass(s, restStart, tail.hierEnd) { segChar(it) } != tail.hierEnd) return null
                return RubyUri(scheme, null, null, null, value.substring(restStart, tail.hierEnd), null, query, fragment)
            }
            if (hierLen > 0) {
                if (takeWhileClass(s, restStart, tail.hierEnd) { segChar(it) } != tail.hierEnd) return null
                var opaque = value.substring(restStart, tail.hierEnd)
                if (query != null) opaque = "$opaque?$query"
                return RubyUri(scheme, null, null, null, null, opaque, null, fragment)
            }
            return RubyUri(scheme, null, null, null, "", null, query, fragment)
        }

        /** `RFC3986_relative_ref`: only validity matters here, since a relative reference is never HTTP. */
        private fun splitRelative(value: String): RubyUri? {
            val s = value
            val tail = parseQueryFragmentPositions(s, 0) ?: return null
            val hierEnd = tail.hierEnd
            val valid = if (hierEnd >= 2 && s[0] == '/' && s[1] == '/') {
                val a = parseAuthority(value, 2, hierEnd)
                a != null && takeWhileClass(s, a.end, hierEnd) { segChar(it) } == hierEnd
            } else if (hierEnd == 0 || s[0] == '/') {
                takeWhileClass(s, 0, hierEnd) { segChar(it) } == hierEnd
            } else {
                val first = takeWhileClass(s, 0, hierEnd) { segNcChar(it) }
                first > 0 && (first == hierEnd || (s[first] == '/' && takeWhileClass(s, first, hierEnd) { segChar(it) } == hierEnd))
            }
            if (!valid) return null
            return RubyUri(
                null, null, null, null, value.substring(0, hierEnd), null,
                tail.query?.let { value.substring(it[0], it[1]) }, tail.fragment?.let { value.substring(it[0], it[1]) },
            )
        }

        private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'
    }
}

/**
 * Rust's `Ipv6Addr::from_str` (`core::net::parser`), which `uri.rs` uses to validate a bracketed
 * host: eight hex groups of up to four digits, one `::` for a run of zero groups, and an embedded
 * IPv4 address (no leading zeros in its octets) in place of the last two groups.
 */
internal object Ipv6 {
    /** Whether [s] is an IPv6 address Rust accepts, consumed entirely. */
    fun parses(s: String): Boolean {
        val p = Parser(s)
        return p.readIpv6() && p.pos == s.length
    }

    private class Parser(val s: String) {
        var pos = 0

        fun readIpv6(): Boolean {
            val start = pos
            val (headSize, headIpv4) = readGroups(8)
            if (headSize == 8) return true
            if (headIpv4) {
                pos = start
                return false
            }
            if (!readGivenChar(':') || !readGivenChar(':')) {
                pos = start
                return false
            }
            val limit = 8 - (headSize + 1)
            readGroups(limit)
            return true
        }

        private fun readGivenChar(c: Char): Boolean {
            if (pos < s.length && s[pos] == c) {
                pos++
                return true
            }
            return false
        }

        private fun readGroups(limit: Int): Pair<Int, Boolean> {
            for (i in 0 until limit) {
                if (i < limit - 1) {
                    val save = pos
                    if ((i == 0 || readGivenChar(':')) && readIpv4()) return (i + 2) to true
                    pos = save
                }
                val save = pos
                if (!((i == 0 || readGivenChar(':')) && readNumber(16, 4, true) >= 0)) {
                    pos = save
                    return i to false
                }
            }
            return limit to false
        }

        private fun readIpv4(): Boolean {
            val start = pos
            for (i in 0 until 4) {
                if (i > 0 && !readGivenChar('.')) {
                    pos = start
                    return false
                }
                val n = readNumber(10, 3, false)
                if (n < 0 || n > 255) {
                    pos = start
                    return false
                }
            }
            return true
        }

        private fun readNumber(radix: Int, maxDigits: Int, allowZeroPrefix: Boolean): Int {
            val start = pos
            val hasLeadingZero = pos < s.length && s[pos] == '0'
            var result = 0
            var count = 0
            while (pos < s.length) {
                val d = Character.digit(s[pos], radix).takeIf { s[pos].code < 128 } ?: -1
                if (d < 0) break
                pos++
                result = result * radix + d
                count++
                if (count > maxDigits) {
                    pos = start
                    return -1
                }
            }
            if (count == 0 || (!allowZeroPrefix && hasLeadingZero && count > 1)) {
                pos = start
                return -1
            }
            return result
        }
    }
}
