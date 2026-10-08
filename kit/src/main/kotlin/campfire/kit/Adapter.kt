package campfire.kit

import campfire.kit.front.Tracing
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

/**
 * A controller action: it reads the request through the [Ctx] and returns the response, or stops
 * by throwing — [halt] with a response, a [KitError] for an error page, anything else for a 500
 * (`ActionFn` in `once-campfire-rust/crates/kit/src/adapter.rs`, whose `Result` the exceptions
 * stand in for).
 */
fun interface Action {
    fun call(c: Ctx): Response
}

/**
 * The path parameters of a request, worked out before the [Ctx] exists (the router's captures in
 * Rust's `RawPathParams`). Throwing a [KitError] answers the request with it, ahead of any query
 * or body error.
 */
fun interface PathParams {
    fun of(request: RequestInfo): ParamMap
}

/** A request extension: the method on the wire when `_method` overrode it (`rack.methodoverride.original_method`). */
class OriginalMethod(val method: String)

/** A request extension: the request id (`ActionDispatch::RequestId`), for logging. */
class RequestId(val id: String)

/**
 * Where requests meet actions (`once-campfire-rust/crates/kit/src/adapter.rs`): [dispatch] builds
 * a [Ctx] for a request, runs the action and finishes the response; [RailsMiddleware] is what runs
 * before routing.
 */
object Adapter {
    /** No path parameters. */
    val NO_PATH_PARAMS = PathParams { ParamMap() }

    /**
     * Runs [action] for [request] (`dispatch` in `adapter.rs`): the cookie jar from its `Cookie`
     * headers, query params, body params (parsed here unless [RailsMiddleware] already did), and
     * [pathParams]; then the action, unless one of those failed (path, query, then body), and
     * [Ctx.finish]; then [intoResponse]. A body over [KitConfig.maxBodyBytes] is a 413 page.
     */
    fun dispatch(kit: Kit, request: Request, action: Action, pathParams: PathParams = NO_PATH_PARAMS): Response {
        val originalMethod = request.extension<OriginalMethod>()?.method ?: request.method
        var bodyError: KitError? = null
        val parsed = request.extension<ParsedBody>() ?: try {
            BodyParser.parse(originalMethod, request.headers, request.body, kit.config.maxBodyBytes)
        } catch (e: BodyTooLarge) {
            bodyError = KitError.Status(e.status)
            null
        }
        val bodyParams = parsed?.params ?: ParamMap().also { if (parsed != null) bodyError = parsed.error?.toKitError() }
        val head = request.method == "HEAD"
        val info = RequestInfo(request, kit.config.proxy, request.method)
        var pathError: KitError? = null
        val path = try {
            pathParams.of(info)
        } catch (e: KitError) {
            pathError = e
            ParamMap()
        }
        var queryError: KitError? = null
        val query = try {
            Params.fromQueryString(info.queryString)
        } catch (e: ParamError) {
            queryError = e.toKitError()
            ParamMap()
        }
        val cookies = CookieJar.fromRequest(request, kit.cookieCrypto, kit.javaClock)
        val ctx = Ctx(kit, info, path, query, bodyParams, cookies)
        var error: Throwable? = pathError ?: queryError ?: bodyError
        var response: Response? = null
        if (error == null) {
            try {
                response = action.call(ctx)
            } catch (e: OutOfMemoryError) {
                throw e
            } catch (e: Throwable) {
                error = e
            }
        }
        return intoResponse(kit, ctx.finish(response, error), head)
    }

    /**
     * A finished response as the server sends it (`into_axum`): `Content-Length` from the body
     * (a file's from its length, and the file read here; a file that can't be read is an empty
     * 500), HEAD bodies dropped while keeping their `Content-Length` (`Rack::Head`), and the
     * markers the deflater reads: [AppContentLength] when the app set the length itself, and
     * [ChunkedBody] for a body of chunks, which Rust streams.
     */
    fun intoResponse(kit: Kit, response: Response, head: Boolean): Response {
        val headers = response.headers
        val appSetLength = "content-length" in headers
        when (val body = response.body) {
            is Body.Bytes -> headers["content-length"] = body.bytes.size.toString()
            is Body.Page -> headers["content-length"] = body.parts.length.toString()
            is Body.File -> {
                headers["content-length"] = body.len.toString()
                if (!head) {
                    try {
                        response.body = Body.Chunks(body.chunks())
                    } catch (e: java.io.IOException) {
                        val line = Tracing.line("ERROR", "campfire_kit::adapter", "send_file failed")
                        kit.log.line(line.append(" error=").append(e.message).append(" path=").append(body.path).toString())
                        return Response(500)
                    }
                }
            }
            is Body.Chunks -> response.putExtension(ChunkedBody)
            is Body.Empty -> if (!head && !appSetLength && response.status != 204 && response.status != 304) headers["content-length"] = "0"
        }
        if (head) {
            response.body = Body.Empty
            response.extensions.remove(BodyDigest::class.java)
        }
        if (appSetLength) response.putExtension(AppContentLength)
        return response
    }

    /**
     * Rails' answer for a request no route takes (`ActionController::RoutingError`; `not_found` in
     * `adapter.rs`, the fallback of an app's own router): the public 404 page in the request's
     * format, by its `Accept` header and path extension.
     */
    fun notFound(kit: Kit, request: Request): Response {
        val input = NegotiationInput(
            formatParam = null,
            accept = RequestInfo.headerStr(request.headers, "accept"),
            contentType = RequestInfo.headerStr(request.headers, "content-type"),
            path = request.path,
            xhr = RequestInfo.headerStr(request.headers, "x-requested-with")?.equals("xmlhttprequest", ignoreCase = true) ?: false,
        )
        val format = try {
            Format.formats(input).firstOrNull()
        } catch (_: InvalidMimeType) {
            null
        }
        val head = request.method == "HEAD"
        return intoResponse(kit, PublicExceptions.render(kit.config.errorPages, 404, format, head), head)
    }

    /**
     * An app's handler with what runs before routing (`kit::app`): [RailsMiddleware], then [inner]
     * (outermost first; Campfire's public files), then [handler]. `Rack::Deflater` goes around the
     * result, as `config.ru` installs it around the whole app:
     *
     * ```
     * val app = Adapter.app(kit, router.handler(kit), publicFiles).layered(RackDeflater())
     * FrontServer(frontConfig, app).start()
     * ```
     */
    fun app(kit: Kit, handler: Handler, vararg inner: Middleware): Handler = handler.layered(RailsMiddleware(kit), *inner)
}

/**
 * The middleware that runs before routing (`rails_middleware` in `adapter.rs`):
 * `ActionDispatch::RequestId` (`X-Request-Id`, sanitized or a new UUID), `Rack::Runtime`
 * (`X-Runtime` as `"%.6f"` seconds), `ActionDispatch::SSL` (with `force_ssl`: plain HTTP redirected
 * to HTTPS, and HSTS plus `; secure` on every cookie over HTTPS), and `Rack::MethodOverride`
 * (`_method` in a POSTed form, or `X-HTTP-Method-Override`), which parses form bodies here and
 * hands them on as a [ParsedBody] extension. Responses `ActionDispatch::Static` served
 * ([StaticFile]) get neither `X-Request-Id` nor `X-Runtime`, as Rails' sit below `Static`.
 */
class RailsMiddleware(private val kit: Kit) : Middleware {
    override fun handle(request: Request, next: Handler): Response {
        val started = System.nanoTime()
        val config = kit.config
        val requestId = requestId(RequestInfo.headerStr(request.headers, "x-request-id"))
        val ssl = config.proxy.assumeSsl || RequestInfo.schemeIsHttps(request.headers, scheme(request.target))
        val response = if (config.forceSsl && !ssl) {
            redirectToHttps(request)
        } else {
            val overridden = try {
                methodOverride(request)
            } catch (e: BodyTooLarge) {
                null
            }
            if (overridden == null) {
                Response(413)
            } else {
                overridden.putExtension(RequestId(requestId))
                next.handle(overridden)
            }
        }
        val headers = response.headers
        if (response.extension<StaticFile>() == null) {
            headers["x-request-id"] = requestId
            if ("x-runtime" !in headers) headers["x-runtime"] = runtime(System.nanoTime() - started)
        }
        if (config.forceSsl && ssl) {
            headers["strict-transport-security"] = config.hsts
            flagCookiesAsSecure(headers)
        }
        return response
    }

    /**
     * `Rack::MethodOverride`: for a POST, `_method` from a form body (parsed here, and kept on the
     * request) or `X-HTTP-Method-Override` replaces the method when it's one of
     * `Rack::MethodOverride::HTTP_METHODS`. Only form data can carry `_method`, so other bodies
     * (JSON, a raw upload) are left for the action to read. Throws [BodyTooLarge].
     */
    private fun methodOverride(request: Request): Request {
        if (request.method != "POST") return request
        val media = RequestInfo.mediaType(RequestInfo.headerStr(request.headers, "content-type"))
        val parsed = if (media == null || media in FORM_TYPES) {
            BodyParser.parse("POST", request.headers, request.body, kit.config.maxBodyBytes)
        } else {
            null
        }
        val fromParam = parsed?.params?.str("_method")
        val method = (fromParam ?: RequestInfo.headerStr(request.headers, "x-http-method-override"))?.uppercase()
        val out = if (method != null && method in OVERRIDABLE_METHODS) {
            Request(method, request.target, request.headers, request.body, request.remote, request.version).also {
                it.extensions.putAll(request.extensions)
                it.putExtension(OriginalMethod("POST"))
            }
        } else {
            request
        }
        if (parsed != null) out.putExtension(parsed)
        return out
    }

    companion object {
        /** `Rack::MethodOverride::HTTP_METHODS` */
        private val OVERRIDABLE_METHODS = setOf("GET", "HEAD", "PUT", "POST", "DELETE", "OPTIONS", "PATCH", "LINK", "UNLINK")

        private val FORM_TYPES = setOf("application/x-www-form-urlencoded", "multipart/form-data", "multipart/related", "multipart/mixed")

        private const val MAX_REQUEST_ID = 255

        /**
         * `ActionDispatch::RequestId#make_request_id`: the incoming id with anything but
         * alphanumerics, `_`, `-` and `@` removed, at most 255 characters; a new UUID v4 when
         * there's none (or it's blank). An id that needs no cleaning is used as it is.
         */
        internal fun requestId(incoming: String?): String {
            if (incoming == null || rustTrim(incoming).isEmpty()) return uuid()
            var clean = incoming.length <= MAX_REQUEST_ID
            if (clean) for (c in incoming) if (!requestIdChar(c)) {
                clean = false
                break
            }
            if (clean) return incoming
            val out = StringBuilder(minOf(incoming.length, MAX_REQUEST_ID))
            var i = 0
            while (i < incoming.length && out.length < MAX_REQUEST_ID) {
                val cp = incoming.codePointAt(i)
                if (Character.isAlphabetic(cp) || isNumeric(cp) || cp == '_'.code || cp == '-'.code || cp == '@'.code) out.appendCodePoint(cp)
                i += Character.charCount(cp)
            }
            return out.toString()
        }

        private fun requestIdChar(c: Char): Boolean =
            c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-' || c == '@'

        /** Rust's `char::is_numeric`: the Unicode `Nd`, `Nl` and `No` categories. */
        private fun isNumeric(cp: Int): Boolean = when (Character.getType(cp)) {
            Character.DECIMAL_DIGIT_NUMBER.toInt(), Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt() -> true
            else -> false
        }

        /**
         * A random (version 4) UUID, as `uuid::Uuid::new_v4` makes one, from the thread's fast
         * generator: request ids only need to be unique, not unguessable.
         */
        internal fun uuid(): String {
            val random = ThreadLocalRandom.current()
            val msb = (random.nextLong() and -0xf001L) or 0x4000L
            val lsb = (random.nextLong() and 0x3fffffffffffffffL) or Long.MIN_VALUE
            return UUID(msb, lsb).toString()
        }

        /** `format!("{:.6}", elapsed.as_secs_f64())` from nanoseconds, without `String.format`. */
        internal fun runtime(nanos: Long): String {
            val micros = (nanos + 500) / 1000
            val fraction = (micros % 1_000_000).toInt()
            val out = StringBuilder(16).append(micros / 1_000_000).append('.')
            var scale = 100_000
            while (scale > 0) {
                out.append(('0'.code + fraction / scale % 10).toChar())
                scale /= 10
            }
            return out.toString()
        }

        /** The scheme of an absolute-form request target, lowercased; null for origin form. */
        private fun scheme(target: String): String? {
            if (target.startsWith('/')) return null
            val sep = target.indexOf("://")
            return if (sep > 0) asciiLowercase(target.substring(0, sep)) else null
        }

        /**
         * `ActionDispatch::SSL#redirect_to_https`: 301 for GET/HEAD, 308 otherwise, to the same
         * host (the last `X-Forwarded-Host`, else `Host`, without its port) and path.
         */
        internal fun redirectToHttps(request: Request): Response {
            val raw = request.headers["x-forwarded-host"] ?: request.headers["host"]
            var host = raw?.takeIf(::isVisible)?.let { rustTrim(it.substring(it.lastIndexOf(',') + 1)) } ?: "localhost"
            val colon = host.lastIndexOf(':')
            if (colon >= 0 && (colon + 1 until host.length).all { host[it] in '0'..'9' }) host = host.substring(0, colon)
            val path = if (request.target.startsWith('/')) request.target else RequestInfo(request).fullpath()
            val status = if (request.method == "GET" || request.method == "HEAD") 301 else 308
            val response = Response(status)
            response.headers.add("content-type", "text/html")
            response.headers.add("location", "https://$host$path")
            return response
        }

        /** Whether [value] passes `HeaderValue::to_str`: visible ASCII, spaces and tabs. */
        private fun isVisible(value: String): Boolean = value.all { it == '\t' || it in ' '..'~' }

        /** `ActionDispatch::SSL#flag_cookies_as_secure!` */
        internal fun flagCookiesAsSecure(headers: Headers) {
            val cookies = headers.all("set-cookie")
            if (cookies.isEmpty()) return
            headers.remove("set-cookie")
            for (cookie in cookies) headers.add("set-cookie", if (isSecure(cookie)) cookie else "$cookie; secure")
        }

        private fun isSecure(cookie: String): Boolean {
            var start = cookie.indexOf(';')
            while (start >= 0) {
                var end = cookie.indexOf(';', start + 1)
                if (end < 0) end = cookie.length
                if (rustTrim(cookie.substring(start + 1, end)).equals("secure", ignoreCase = true)) return true
                start = if (end < cookie.length) end else -1
            }
            return false
        }
    }
}
