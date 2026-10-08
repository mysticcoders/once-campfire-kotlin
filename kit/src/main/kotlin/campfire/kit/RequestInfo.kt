package campfire.kit

/**
 * An IPv4 or IPv6 address as Rust's `std::net::IpAddr` parses and prints it, without the DNS
 * lookups `java.net.InetAddress` can make (`once-campfire-rust/crates/kit/src/request.rs`). Four
 * bytes for IPv4, sixteen for IPv6; an IPv4-mapped IPv6 address stays IPv6.
 */
class IpAddr private constructor(private val bytes: ByteArray) {
    val isV4: Boolean get() = bytes.size == 4

    /** The address bytes (a copy). */
    fun octets(): ByteArray = bytes.copyOf()

    internal fun byteAt(i: Int): Int = bytes[i].toInt() and 0xff

    override fun equals(other: Any?): Boolean = other is IpAddr && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    /**
     * Rust's `Display`: dotted decimal; for IPv6, lowercase hex with the longest run of two or
     * more zero groups (the first, on a tie) as `::`, and `::ffff:a.b.c.d` for IPv4-mapped ones.
     */
    override fun toString(): String {
        if (isV4) return "${byteAt(0)}.${byteAt(1)}.${byteAt(2)}.${byteAt(3)}"
        val groups = IntArray(8) { (byteAt(it * 2) shl 8) or byteAt(it * 2 + 1) }
        if (groups[0] == 0 && groups[1] == 0 && groups[2] == 0 && groups[3] == 0 && groups[4] == 0 && groups[5] == 0xffff) {
            return "::ffff:${byteAt(12)}.${byteAt(13)}.${byteAt(14)}.${byteAt(15)}"
        }
        var bestStart = 0
        var bestLen = 0
        var curStart = 0
        var curLen = 0
        for (i in 0 until 8) {
            if (groups[i] == 0) {
                if (curLen == 0) curStart = i
                curLen++
                if (curLen > bestLen) {
                    bestStart = curStart
                    bestLen = curLen
                }
            } else {
                curLen = 0
            }
        }
        val out = StringBuilder(39)
        if (bestLen > 1) {
            appendGroups(out, groups, 0, bestStart)
            out.append("::")
            appendGroups(out, groups, bestStart + bestLen, 8)
        } else {
            appendGroups(out, groups, 0, 8)
        }
        return out.toString()
    }

    private fun appendGroups(out: StringBuilder, groups: IntArray, from: Int, to: Int) {
        for (i in from until to) {
            if (i > from) out.append(':')
            out.append(Integer.toHexString(groups[i]))
        }
    }

    companion object {
        /** `IpAddr::from_str`: strict dotted-quad IPv4 (no leading zeros) or RFC 4291 IPv6 text; null otherwise. */
        fun parse(s: String): IpAddr? = parseV4(s, 0, s.length)?.let { IpAddr(it) } ?: parseV6(s)?.let { IpAddr(it) }

        /** The address of [address] (an `InetAddress` already resolved, so no lookup is made). */
        fun of(address: java.net.InetAddress): IpAddr = IpAddr(address.address)

        private fun parseV4(s: String, from: Int, to: Int): ByteArray? {
            val out = ByteArray(4)
            var i = from
            for (octet in 0 until 4) {
                if (octet > 0) {
                    if (i >= to || s[i] != '.') return null
                    i++
                }
                val start = i
                var value = 0
                while (i < to && s[i] in '0'..'9' && i - start < 3) {
                    value = value * 10 + (s[i] - '0')
                    i++
                }
                val digits = i - start
                if (digits == 0 || value > 255 || (digits > 1 && s[start] == '0')) return null
                out[octet] = value.toByte()
            }
            return if (i == to) out else null
        }

        /** Rust's `Parser::read_ipv6_addr`: head groups, then optionally `::` and tail groups. */
        private fun parseV6(s: String): ByteArray? {
            val head = IntArray(8)
            val (headSize, headEnd, headV4) = readGroups(s, 0, head, 8)
            var pos = headEnd
            val out = ByteArray(16)
            if (headSize == 8) {
                if (pos != s.length) return null
                for (i in 0 until 8) setGroup(out, i, head[i])
                return out
            }
            if (headV4) return null
            if (!s.startsWith("::", pos)) return null
            pos += 2
            val tail = IntArray(8)
            val limit = 8 - (headSize + 1)
            val (tailSize, tailEnd, _) = readGroups(s, pos, tail, limit)
            if (tailEnd != s.length) return null
            for (i in 0 until headSize) setGroup(out, i, head[i])
            for (i in 0 until tailSize) setGroup(out, 8 - tailSize + i, tail[i])
            return out
        }

        private fun setGroup(out: ByteArray, i: Int, value: Int) {
            out[i * 2] = (value shr 8).toByte()
            out[i * 2 + 1] = value.toByte()
        }

        /**
         * Rust's `read_groups`: up to [limit] colon-separated groups of 1-4 hex digits from
         * [from], the last two of which may be an embedded IPv4 address. Returns how many groups
         * were read, where reading stopped, and whether it ended with IPv4.
         */
        private fun readGroups(s: String, from: Int, groups: IntArray, limit: Int): Triple<Int, Int, Boolean> {
            var pos = from
            var i = 0
            while (i < limit) {
                val start = if (i > 0) {
                    if (pos < s.length && s[pos] == ':' && !(pos + 1 < s.length && s[pos + 1] == ':')) pos + 1 else return Triple(i, pos, false)
                } else {
                    pos
                }
                if (i < limit - 1) {
                    val v4End = v4Prefix(s, start)
                    if (v4End > 0) {
                        val v4 = parseV4(s, start, v4End)
                        if (v4 != null) {
                            groups[i] = ((v4[0].toInt() and 0xff) shl 8) or (v4[1].toInt() and 0xff)
                            groups[i + 1] = ((v4[2].toInt() and 0xff) shl 8) or (v4[3].toInt() and 0xff)
                            return Triple(i + 2, v4End, true)
                        }
                    }
                }
                var end = start
                var value = 0
                while (end < s.length && end - start < 4 && hexDigit(s[end]) >= 0) {
                    value = (value shl 4) or hexDigit(s[end])
                    end++
                }
                if (end == start) return Triple(i, pos, false)
                groups[i] = value
                pos = end
                i++
            }
            return Triple(i, pos, false)
        }

        /**
         * Where an IPv4 address read at [from] ends, or 0: the longest prefix of the run of digits
         * and dots there that parses. Anything shorter than the run leaves characters that fail
         * the whole address, as Rust's greedy read does.
         */
        private fun v4Prefix(s: String, from: Int): Int {
            var end = from
            var dots = 0
            while (end < s.length && end - from < 15 && (s[end] in '0'..'9' || s[end] == '.')) {
                if (s[end] == '.') dots++
                end++
            }
            if (dots == 0) return 0
            for (stop in end downTo from + 1) if (parseV4(s, from, stop) != null) return stop
            return 0
        }

        private fun hexDigit(c: Char): Int = when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> -1
        }
    }
}

/** An IP network (`IPAddr.new("10.0.0.0/8")`; `request.rs`, `IpNet`). */
class IpNet private constructor(private val addr: IpAddr, private val prefix: Int) {
    /** Whether [ip] is in this network; never across IPv4 and IPv6. */
    fun contains(ip: IpAddr): Boolean {
        if (ip.isV4 != addr.isV4) return false
        val full = prefix / 8
        for (i in 0 until full) if (addr.byteAt(i) != ip.byteAt(i)) return false
        val rest = prefix % 8
        if (rest == 0) return true
        val mask = (0xff shl (8 - rest)) and 0xff
        return (addr.byteAt(full) and mask) == (ip.byteAt(full) and mask)
    }

    override fun equals(other: Any?): Boolean = other is IpNet && other.addr == addr && other.prefix == prefix

    override fun hashCode(): Int = addr.hashCode() * 31 + prefix

    override fun toString(): String = "$addr/$prefix"

    companion object {
        /** `IpNet::from_str`: an address with an optional `/prefix` (at most 32 or 128). */
        fun parse(s: String): IpNet {
            val slash = s.indexOf('/')
            val addrText = if (slash < 0) s else s.substring(0, slash)
            val addr = IpAddr.parse(addrText) ?: throw IllegalArgumentException("invalid IP \"$s\"")
            val max = if (addr.isV4) 32 else 128
            val prefix = if (slash < 0) {
                max
            } else {
                val text = s.substring(slash + 1)
                val p = if (text.isNotEmpty() && text.length <= 3 && text.all { it in '0'..'9' }) text.toInt() else -1
                if (p < 0 || p > max) throw IllegalArgumentException("invalid prefix \"$s\"")
                p
            }
            return IpNet(addr, prefix)
        }
    }
}

/**
 * How to read proxy headers (`ActionDispatch::RemoteIp`, `ActionDispatch::AssumeSSL`;
 * `request.rs`, `ProxyConfig`).
 */
data class ProxyConfig(
    /** `config.action_dispatch.trusted_proxies`; Rails' `TRUSTED_PROXIES` by default. */
    val trustedProxies: List<IpNet> = DEFAULT_TRUSTED_PROXIES,
    /** `config.action_dispatch.ip_spoofing_check`. */
    val ipSpoofingCheck: Boolean = true,
    /** `config.assume_ssl`: treat every request as HTTPS (TLS terminates at the proxy). */
    val assumeSsl: Boolean = false,
) {
    companion object {
        /** `ActionDispatch::RemoteIp::TRUSTED_PROXIES`. */
        val DEFAULT_TRUSTED_PROXIES: List<IpNet> = listOf(
            "127.0.0.0/8", "::1/128", "fc00::/7", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "fe80::/10",
        ).map(IpNet::parse)
    }
}

/**
 * The request as a Rails controller sees it (`once-campfire-rust/crates/kit/src/request.rs`,
 * `Request`): [method] after `_method` override, [originalMethod] as on the wire, URL pieces with
 * proxy-derived host and protocol, `remote_ip`, and the raw body. `ssl?` is decided up front as in
 * Rust; `remote_ip` is computed on first use (the same answer, without the cost on requests that
 * never ask).
 */
class RequestInfo(
    val request: Request,
    private val proxy: ProxyConfig = ProxyConfig(),
    val method: String = request.method,
) {
    /** The method on the wire: [OriginalMethod] when `_method` overrode it, else the request's. */
    val originalMethod: String get() = request.extension<OriginalMethod>()?.method ?: request.method
    val headers: Headers get() = request.headers

    /** The TCP peer (`REMOTE_ADDR`). */
    val peer: IpAddr? = request.remote?.address?.let(IpAddr::of)

    private val target = Target.of(request)

    val path: String get() = target.path

    /** The query string, "" when there is none. */
    val queryString: String get() = target.query ?: ""

    val isSsl: Boolean = proxy.assumeSsl || schemeIsHttps(request.headers, target.scheme)

    private val remoteIpResult: String? by lazy(LazyThreadSafetyMode.NONE) { calculateRemoteIp(request.headers, peer, proxy) }

    /** A header's first value, when it's visible ASCII as `HeaderValue::to_str` requires. */
    fun header(name: String): String? = headerStr(request.headers, name)

    val isGet: Boolean get() = method == "GET"
    val isHead: Boolean get() = method == "HEAD"
    val isPost: Boolean get() = method == "POST"

    /** `request.xhr?` */
    val isXhr: Boolean get() = header("x-requested-with")?.let { asciiLowercase(it).contains("xmlhttprequest") } ?: false

    /** `request.fullpath` */
    fun fullpath(): String {
        val q = target.query
        return if (q.isNullOrEmpty()) target.path else "${target.path}?$q"
    }

    /** `request.protocol`: `"https://"` or `"http://"`. */
    val protocol: String get() = if (isSsl) "https://" else "http://"

    private fun rawHostWithPort(): String {
        val forwarded = header("x-forwarded-host")
        if (forwarded != null && rustTrim(forwarded).isNotEmpty()) {
            return rustTrimStart(forwarded.substring(forwarded.lastIndexOf(',') + 1))
        }
        return header("host") ?: target.authority ?: "localhost"
    }

    /** The index of a `:port` suffix of all digits in [raw], or -1. */
    private fun portSeparator(raw: String): Int {
        val colon = raw.lastIndexOf(':')
        if (colon < 0 || colon == raw.length - 1) return -1
        for (i in colon + 1 until raw.length) if (raw[i] !in '0'..'9') return -1
        return colon
    }

    /** `request.host` (X-Forwarded-Host aware, port stripped). */
    fun host(): String {
        val raw = rawHostWithPort()
        val colon = portSeparator(raw)
        return if (colon < 0) raw else raw.substring(0, colon)
    }

    /** `request.port`: the host's port, or the scheme's default when there's none (or it's past 65535). */
    fun port(): Int {
        val raw = rawHostWithPort()
        val colon = portSeparator(raw)
        if (colon < 0) return standardPort
        var value = 0
        for (i in colon + 1 until raw.length) value = minOf(value * 10 + (raw[i] - '0'), 65536)
        return if (value <= 65535) value else standardPort
    }

    private val standardPort: Int get() = if (isSsl) 443 else 80

    /** `request.host_with_port`: the port only when it isn't the scheme's default. */
    fun hostWithPort(): String {
        val port = port()
        return if (port == standardPort) host() else "${host()}:$port"
    }

    /** `request.base_url`, e.g. `https://campfire.example.com`. */
    fun baseUrl(): String = protocol + hostWithPort()

    /** `request.url` */
    fun url(): String = baseUrl() + fullpath()

    val origin: String? get() = header("origin")
    val referer: String? get() = header("referer")
    val userAgent: String? get() = header("user-agent")

    /** `Content-Type`, when present and not empty. */
    val contentType: String? get() = header("content-type")?.takeIf { it.isNotEmpty() }

    /** The `type/subtype` part of `Content-Type`, lowercased (`Rack::MediaType.type`). */
    val mediaType: String? get() = mediaType(contentType)

    /**
     * `request.raw_post` / `request.body.read`. The Rust port leaves it empty for multipart
     * bodies, which are parsed into params instead; see [BodyParser].
     */
    val rawPost: ByteArray get() = request.body

    /** `request.remote_ip`, per `ActionDispatch::RemoteIp::GetIp`; [KitError.IpSpoofAttack] when the check fails. */
    fun remoteIp(): String = remoteIpResult ?: throw KitError.IpSpoofAttack()

    /**
     * The request target split as `http::Uri` splits it: an absolute-form target's scheme and
     * authority, and the path ("/" when an absolute form has none) and query, a fragment dropped.
     */
    private class Target(val scheme: String?, val authority: String?, val path: String, val query: String?) {
        companion object {
            fun of(request: Request): Target {
                val t = request.target
                if (t.startsWith("/") && t.indexOf('#') < 0) return Target(null, null, request.path, request.query)
                var rest = t.substringBefore('#')
                var scheme: String? = null
                var authority: String? = null
                val sep = rest.indexOf("://")
                if (sep > 0 && rest.substring(0, sep).all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) {
                    scheme = asciiLowercase(rest.substring(0, sep))
                    val after = rest.substring(sep + 3)
                    var end = 0
                    while (end < after.length && after[end] != '/' && after[end] != '?') end++
                    authority = after.substring(0, end)
                    rest = after.substring(end)
                    if (rest.isEmpty() || rest.startsWith("?")) rest = "/$rest"
                }
                val q = rest.indexOf('?')
                return if (q < 0) Target(scheme, authority, rest, null) else Target(scheme, authority, rest.substring(0, q), rest.substring(q + 1))
            }
        }
    }

    companion object {
        /** `Rack::MediaType.type`: the part of [contentType] before `;` or `,`, trimmed and lowercased; null when empty. */
        fun mediaType(contentType: String?): String? {
            if (contentType == null) return null
            var end = 0
            while (end < contentType.length && contentType[end] != ';' && contentType[end] != ',') end++
            val base = asciiLowercase(rustTrim(contentType.substring(0, end)))
            return base.ifEmpty { null }
        }

        /** A header's first value when every character is visible ASCII, space or tab (`HeaderValue::to_str`). */
        fun headerStr(headers: Headers, name: String): String? {
            val value = headers[name] ?: return null
            for (c in value) if (c != '\t' && (c < ' ' || c > '~')) return null
            return value
        }

        /** `Rack::Request#scheme` → `ssl?`, from proxy headers or an `https` absolute-form target. */
        fun schemeIsHttps(headers: Headers, scheme: String?): Boolean {
            if (headerStr(headers, "x-forwarded-ssl") == "on") return true
            val forwarded = headerStr(headers, "forwarded")
            if (forwarded != null) {
                val proto = forwardedValues(forwarded, "proto").lastOrNull()
                if (proto != null && proto in SCHEMES) return proto == "https" || proto == "wss"
            }
            for (name in arrayOf("x-forwarded-proto", "x-forwarded-scheme")) {
                val value = headerStr(headers, name) ?: continue
                val found = splitHeader(value).lastOrNull { it in SCHEMES }
                if (found != null) return found == "https" || found == "wss"
            }
            return scheme == "https"
        }

        private val SCHEMES = setOf("https", "http", "wss", "ws")

        /** [value] trimmed and split on commas, spaces and tabs, empty pieces dropped. */
        private fun splitHeader(value: String): List<String> {
            val trimmed = rustTrim(value)
            val out = ArrayList<String>(2)
            var start = 0
            for (i in 0..trimmed.length) {
                if (i == trimmed.length || trimmed[i] == ',' || trimmed[i] == ' ' || trimmed[i] == '\t') {
                    if (i > start) out.add(trimmed.substring(start, i))
                    start = i + 1
                }
            }
            return out
        }

        /** `Rack::Utils.forwarded_values(header)[param]`, for the simple unquoted and quoted forms. */
        private fun forwardedValues(header: String, param: String): List<String> {
            val values = ArrayList<String>(1)
            var start = 0
            for (i in 0..header.length) {
                if (i == header.length || header[i] == ',' || header[i] == ';') {
                    val element = header.substring(start, i)
                    val eq = element.indexOf('=')
                    if (eq >= 0 && rustTrim(element.substring(0, eq)).equals(param, ignoreCase = true)) {
                        values.add(rustTrim(element.substring(eq + 1)).trim('"'))
                    }
                    start = i + 1
                }
            }
            return values
        }

        /** Strip a port and IPv6 brackets: `Rack::Request#split_authority(...)[1]`. */
        private fun authorityAddress(authority: String): String {
            val a = rustTrim(authority)
            if (a.startsWith('[')) {
                val close = a.indexOf(']', 1)
                return if (close < 0) a.substring(1) else a.substring(1, close)
            }
            if (IpAddr.parse(a) != null) return a
            val colon = a.lastIndexOf(':')
            if (colon >= 0) {
                val host = a.substring(0, colon)
                var digits = true
                for (i in colon + 1 until a.length) if (a[i] !in '0'..'9') digits = false
                if (digits && host.indexOf(':') < 0) return host
            }
            return a
        }

        private fun sanitizeIps(ips: List<String>): MutableList<IpAddr> {
            val out = ArrayList<IpAddr>(ips.size)
            for (ip in ips) IpAddr.parse(ip.trim('[', ']'))?.let(out::add)
            return out
        }

        private fun forwardedFor(headers: Headers): List<String> {
            val forwarded = headerStr(headers, "forwarded")
            if (forwarded != null) {
                val values = forwardedValues(forwarded, "for")
                if (values.isNotEmpty()) return values.map(::authorityAddress)
            }
            val xff = headerStr(headers, "x-forwarded-for") ?: return emptyList()
            return splitHeader(xff).map(::authorityAddress)
        }

        /** `ActionDispatch::RemoteIp::GetIp#calculate_ip`; null for a spoofing attack. */
        private fun calculateRemoteIp(headers: Headers, peer: IpAddr?, proxy: ProxyConfig): String? {
            val clientIpHeader = headerStr(headers, "client-ip")
            val clientIps = if (clientIpHeader == null) {
                ArrayList()
            } else {
                sanitizeIps(rustTrim(clientIpHeader).split(',', ' ', '\t'))
            }
            clientIps.reverse()
            val forwardedIps = sanitizeIps(forwardedFor(headers))
            forwardedIps.reverse()

            if (proxy.ipSpoofingCheck && clientIps.isNotEmpty() && forwardedIps.isNotEmpty() && clientIps.last() !in forwardedIps) {
                return null
            }

            val ips = forwardedIps + clientIps
            val trusted = { ip: IpAddr -> proxy.trustedProxies.any { it.contains(ip) } }
            val chosen = ips.firstOrNull { !trusted(it) }
                ?: peer?.takeIf { !trusted(it) }
                ?: ips.lastOrNull()
                ?: peer
            return chosen?.toString() ?: ""
        }
    }
}
