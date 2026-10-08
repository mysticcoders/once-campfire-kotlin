package campfire.kit.front

import campfire.kit.Body
import campfire.kit.ChunkedBody
import campfire.kit.Handler
import campfire.kit.Middleware
import campfire.kit.Request
import campfire.kit.RequestBodyTooLarge
import campfire.kit.Response
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The connection a request came in on (`ConnInfo` in `crates/kit/src/front/handler.rs`). The
 * server attaches none yet; without one the front uses [Request.remote] and plain HTTP.
 */
class ConnInfo(val remote: InetSocketAddress?, val tls: Boolean)

/** Where the front server's log lines go: one call per line, without the newline. */
fun interface LogSink {
    fun line(text: String)

    companion object {
        /** Standard error. (The Rust port's `tracing` subscriber writes to standard output.) */
        val STDERR = LogSink { System.err.println(it) }
    }
}

/**
 * Thruster's handler chain (`internal/handler.go`, `crates/kit/src/front/handler.rs`) as the
 * outermost [Middleware], outermost first: the request log, compression, `X-Request-Start`, the
 * response cache, and the proxy step (MAX_REQUEST_BODY and the `X-Forwarded-*` headers) in front
 * of the app. Afterwards it drops the headers Go's server won't send with a bodiless status.
 *
 * Per request it does no regex work and allocates little: the log entry holds references to the
 * request's strings, and only cacheable requests build a cache key.
 */
class Front(
    config: FrontConfig,
    private val log: LogSink = LogSink.STDERR,
    private val cache: MemoryCache = MemoryCache(config.cacheSize, config.maxCacheItemSize),
) : Middleware {
    private val maxCacheableBody = config.maxCacheItemSize.coerceAtLeast(0)
    private val compression =
        if (config.gzipCompressionEnabled) Compression(config.gzipCompressionJitter, config.gzipCompressionDisableOnAuth) else null
    private val maxRequestBody = config.maxRequestBody.coerceAtLeast(0)
    private val forwardHeaders = config.forwardHeaders
    private val logRequests = config.logRequests

    /** `Handler::call`: the chain around [next], then header suppression and the log line. */
    override fun handle(request: Request, next: Handler): Response {
        val conn = request.extension<ConnInfo>() ?: ConnInfo(request.remote, false)
        val entry = if (logRequests) RequestLog(request, conn) else null
        val response = compress(request, conn, next)
        suppressBodilessHeaders(response)
        entry?.write(response, log)
        return response
    }

    private fun compress(request: Request, conn: ConnInfo, next: Handler): Response {
        val negotiation = compression?.negotiate(request)
        setRequestStart(request)
        val (response, merge) = cached(request, conn, next)
        return if (compression != null && negotiation != null) compression.apply(negotiation, response, merge) else response
    }

    /**
     * `CacheHandler.ServeHTTP`, except that requests the cache can't serve bypass it before the
     * lookup (Thruster looks them up first, so a range request could get a whole cached body).
     */
    private fun cached(request: Request, conn: ConnInfo, next: Handler): Pair<Response, HeaderMerge> {
        if (!shouldCacheRequest(request)) {
            val response = proxy(request, conn, next)
            response.headers["x-cache"] = "bypass"
            return response to HeaderMerge.APPEND
        }

        val now = System.nanoTime()
        val variant = Variant(request)
        var key = variant.cacheKey()
        var found = cache.get(key, now)
        if (found != null) {
            variant.setResponseHeaders(found.headers)
            if (!variant.matches(found.variant)) {
                key = variant.cacheKey()
                found = cache.get(key, now)
            }
        }
        if (found != null) return hit(found, request) to HeaderMerge.REPLACE

        val head = request.method == "HEAD"
        val response = proxy(request, conn, next)
        val lifetime = cacheLifetime(response.status, response.headers)
        if (lifetime != null) response.headers.remove("set-cookie")
        response.headers["x-cache"] = "miss"
        if (lifetime == null) return response to HeaderMerge.REPLACE

        variant.setResponseHeaders(response.headers)
        val headers = response.headers.copy()
        headers.remove("x-cache")
        val expiresAt = now + lifetime * 1_000_000_000L
        val body = response.body
        when {
            head || body.length == 0L -> cache.set(key, CachedResponse(response.status, headers, Body.Empty, variant.variantHeaders()), expiresAt, now)
            body.length <= maxCacheableBody -> cache.set(key, CachedResponse(response.status, headers, body, variant.variantHeaders()), expiresAt, now)
        }
        return response to HeaderMerge.REPLACE
    }

    /**
     * The proxy (`internal/proxy_handler.go`): the request size limit Thruster's
     * `http.MaxBytesHandler` enforces (an oversized body never reaches the app and gets an empty
     * 413), the `X-Forwarded-*` headers `httputil.ReverseProxy` sets, then the app.
     */
    private fun proxy(request: Request, conn: ConnInfo, next: Handler): Response {
        if (overLimit(request, maxRequestBody)) return tooLarge()
        setForwardedHeaders(request, conn, forwardHeaders)
        return next.handle(request)
    }

    companion object {
        /**
         * Go's `http.Server` drops the headers a status can't carry when it writes them
         * (`suppressedHeaders`): `Content-Type`, `Content-Length` and `Transfer-Encoding` on a 304
         * (whether the app or the cache produced it), the length on 1xx and 204.
         */
        fun suppressBodilessHeaders(response: Response) {
            val status = response.status
            if (status == 304) response.headers.remove("content-type")
            if (status == 304 || status == 204 || status in 100..199) {
                response.headers.remove("content-length")
                response.headers.remove("transfer-encoding")
            }
        }

        /** A cache hit (`WriteCachedResponse`): the stored response, or a 304 when the request already has it. */
        internal fun hit(cached: CachedResponse, request: Request): Response {
            val notModified = wasNotModified(cached, request)
            val response = Response(if (notModified) 304 else cached.status, cached.headers.copy(), if (notModified) Body.Empty else cached.body)
            response.headers["x-cache"] = "hit"
            return response
        }

        /** `NewRequestStartHandler`: `X-Request-Start: t=<epoch millis>` unless the request has one. */
        fun setRequestStart(request: Request) {
            if (request.headers["x-request-start"].isNullOrEmpty()) {
                request.headers["x-request-start"] = "t=" + System.currentTimeMillis()
            }
        }

        /**
         * `setXForwarded` over `ProxyRequest.SetXForwarded`: the client's address is appended to
         * `X-Forwarded-For`, and `X-Forwarded-Host`/`-Proto` name this request's host and scheme.
         * With FORWARD_HEADERS the client's own `X-Forwarded-*` are kept (it's trusted to be a
         * proxy); without it they're replaced. `Forwarded` never reaches the app.
         */
        fun setForwardedHeaders(request: Request, conn: ConnInfo, forwardHeaders: Boolean) {
            val headers = request.headers
            val priorFor = if (forwardHeaders) headers.all("x-forwarded-for").joinToString(", ") else ""
            val incomingHost = headers["x-forwarded-host"]?.takeIf { it.isNotEmpty() }
            val incomingProto = headers["x-forwarded-proto"]?.takeIf { it.isNotEmpty() }
            val host = headers["host"]
            val client = conn.remote?.address?.let(::ipText) ?: ""
            headers.remove("forwarded")
            headers["x-forwarded-for"] = if (priorFor.isEmpty()) client else "$priorFor, $client"
            headers["x-forwarded-host"] = host ?: ""
            headers["x-forwarded-proto"] = if (conn.tls) "https" else "http"
            if (forwardHeaders) {
                if (incomingHost != null) headers["x-forwarded-host"] = incomingHost
                if (incomingProto != null) headers["x-forwarded-proto"] = incomingProto
            }
        }

        /**
         * MAX_REQUEST_BODY (0 for none), as `http.MaxBytesHandler` applies it: the server marked
         * the request [RequestBodyTooLarge], or its declared or read length is over [limit].
         */
        fun overLimit(request: Request, limit: Long): Boolean {
            if (limit == 0L) return false
            if (request.extension<RequestBodyTooLarge>() != null) return true
            val declared = request.headers["content-length"]?.toLongOrNull()
            return (declared != null && declared > limit) || request.body.size > limit
        }

        /** The empty 413 for a body over MAX_REQUEST_BODY. */
        fun tooLarge(): Response = Response(413)

        /**
         * An address as Rust displays a canonical `IpAddr`: dotted IPv4 (IPv4-mapped IPv6
         * included), or RFC 5952 IPv6 with the longest run of two or more zero groups as `::`.
         */
        fun ipText(address: InetAddress): String {
            if (address is Inet4Address) return address.hostAddress
            val bytes = address.address
            if (bytes.size == 4) return InetAddress.getByAddress(bytes).hostAddress
            val groups = IntArray(8) { ((bytes[2 * it].toInt() and 0xff) shl 8) or (bytes[2 * it + 1].toInt() and 0xff) }
            if (groups.sliceArray(0 until 5).all { it == 0 } && groups[5] == 0xffff) {
                return "${bytes[12].toInt() and 0xff}.${bytes[13].toInt() and 0xff}.${bytes[14].toInt() and 0xff}.${bytes[15].toInt() and 0xff}"
            }
            var bestStart = -1
            var bestLength = 0
            var i = 0
            while (i < 8) {
                if (groups[i] != 0) {
                    i++
                    continue
                }
                val start = i
                while (i < 8 && groups[i] == 0) i++
                if (i - start > bestLength) {
                    bestStart = start
                    bestLength = i - start
                }
            }
            if (bestLength < 2) bestStart = -1
            val out = StringBuilder(39)
            i = 0
            while (i < 8) {
                if (i == bestStart) {
                    out.append("::")
                    i += bestLength
                    continue
                }
                if (out.isNotEmpty() && out[out.length - 1] != ':') out.append(':')
                out.append(Integer.toHexString(groups[i]))
                i++
            }
            return out.toString()
        }

        /** A socket address as Rust displays a `SocketAddr`: `ip:port`, or `[ipv6]:port`. */
        fun socketText(address: InetSocketAddress): String {
            val ip = address.address?.let(::ipText) ?: address.hostString
            return if (address.address is Inet6Address && ':' in ip) "[$ip]:${address.port}" else "$ip:${address.port}"
        }
    }
}

/**
 * Thruster's request log line (`internal/logging_handler.go`, `RequestLog` in
 * `crates/kit/src/front/handler.rs`), captured before the request is changed and written once the
 * response is ready, with the same fields as the Rust port's `tracing` event under target
 * `thruster`. The Rust port writes it when the body has been sent; here it's written when the
 * response leaves the middleware, so `dur` excludes the network write.
 */
internal class RequestLog(request: Request, conn: ConnInfo) {
    private val started = System.nanoTime()
    private val path = request.path
    private val query = request.query ?: ""
    private val method = request.method
    private val proto = request.version
    private val reqContentLength = request.headers["content-length"]?.toLongOrNull() ?: if (request.body.isEmpty()) 0L else -1L
    private val reqContentType = request.headers["content-type"] ?: ""
    private val remoteAddr = request.headers["x-forwarded-for"].let { forwarded ->
        if (forwarded.isNullOrEmpty()) conn.remote?.let(Front::socketText) ?: "" else forwarded
    }
    private val userAgent = request.headers["user-agent"] ?: ""

    /** Writes the line for [response] to [sink]. */
    fun write(response: Response, sink: LogSink) {
        val bytes = if (method == "HEAD") 0L else response.body.length
        val line = Tracing.line("INFO", "thruster", "Request")
            .append(" path=").append(path)
            .append(" status=").append(response.status)
            .append(" dur=").append((System.nanoTime() - started) / 1_000_000)
            .append(" method=").append(method)
            .append(" req_content_length=").append(reqContentLength)
            .append(" req_content_type=").append(reqContentType)
            .append(" resp_content_length=").append(bytes)
            .append(" resp_content_type=").append(response.headers["content-type"] ?: "")
            .append(" remote_addr=").append(remoteAddr)
            .append(" user_agent=").append(userAgent)
            .append(" cache=").append(response.headers["x-cache"] ?: "")
            .append(" query=").append(query)
            .append(" proto=\"").append(proto).append('"')
        sink.line(line.toString())
    }
}

/**
 * Lines in the shape `tracing_subscriber::fmt` gives the Rust port's events without ANSI colors:
 * `2026-10-07T18:02:29.123456Z  INFO thruster: Request path=/ ...` (Display fields bare, string
 * fields quoted).
 */
internal object Tracing {
    private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC)

    /** A line's start: timestamp, padded level, target and message; fields are appended as ` name=value`. */
    fun line(level: String, target: String, message: String): StringBuilder {
        val out = StringBuilder(256)
        TIMESTAMP.formatTo(Instant.now(), out)
        out.append(' ')
        repeat(5 - level.length) { out.append(' ') }
        return out.append(level).append(' ').append(target).append(": ").append(message)
    }
}
