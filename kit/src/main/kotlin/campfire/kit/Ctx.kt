package campfire.kit

import campfire.kit.front.Tracing
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.HexFormat

/**
 * Options for `redirect_to` (`Redirect` in `once-campfire-rust/crates/kit/src/ctx.rs`). [status]
 * defaults to 302 Found, like Rails.
 */
data class Redirect(
    val status: Int? = null,
    val notice: String? = null,
    val alert: String? = null,
    val allowOtherHost: Boolean = false,
)

/**
 * Validators for `fresh_when` / `stale?` (`Freshness` in `ctx.rs`). [etag] is the weak validator,
 * already expanded to a cache key (e.g. `record.cache_key_with_version`); [template] is the
 * template digest `ETagWithTemplateDigest` would add.
 */
data class Freshness(
    val etag: String? = null,
    val strongEtag: String? = null,
    val lastModified: Instant? = null,
    val public: Boolean = false,
    val template: String? = null,
) {
    companion object {
        fun etag(validator: String): Freshness = Freshness(etag = validator)
    }
}

/**
 * Everything a controller action touches, in place of a Rails controller instance
 * (`once-campfire-rust/crates/kit/src/ctx.rs`). [Adapter.dispatch] builds one per request, runs
 * the action with it and turns the outcome into the response with [finish].
 *
 * An action is an [Action], `(Ctx) -> Response`. Before-actions are ordered calls at the top of
 * it that stop the chain with [halt] (a response) or by throwing a [KitError] (an error page):
 *
 * ```
 * val update = Action { c ->
 *     requireAuthentication(c)
 *     c.verifyAuthenticityToken()
 *     ensureCanAdminister(c)                 // halt(c.head(403))
 *     val room = c.params.require("room").permit(permitKeys("name"))
 *     c.redirectTo("/rooms/1")
 * }
 * ```
 *
 * `params` are the body params, then the query string's, then the path's, merged as Rails merges
 * them. Response headers set before the response exists ([headers], e.g. `X-Version` in a
 * before-action) and [cacheControl] are applied to whatever response the action returns.
 */
class Ctx(
    val kit: Kit,
    val request: RequestInfo,
    pathParams: ParamMap,
    queryParams: ParamMap,
    requestParams: ParamMap,
    val cookies: CookieJar,
) {
    /** `params`: body params, then query params, then path params. */
    var params: ParamMap = ParamMap.combined(requestParams, queryParams, pathParams)
    var queryParams: ParamMap = queryParams
    var requestParams: ParamMap = requestParams
    var pathParams: ParamMap = pathParams

    /** Response headers set before the response exists, merged without overriding what it sets. */
    val headers = Headers()

    /** `response.cache_control`, applied to the final response. */
    var cacheControl = CacheControl()

    private val session = Session(kit.config.session)
    private var flash: Flash? = null

    @PublishedApi
    internal val currentValues = HashMap<Class<*>, Any>(4)
    private var markedForSameOriginVerification = false
    private var formats: List<Mime>? = null
    private var formatsInvalid = false
    private var renderedFormat: Mime? = null
    private var live = false

    /** The application state given to [Kit]. */
    inline fun <reified S> state(): S = kit.state as S

    val clock: campfire.rails.Clock get() = kit.clock

    /** `Time.current`. */
    fun now(): Instant = kit.clock.now()

    /** Replaces the path params, rebuilding [params] (`install_path_params` in `controllers.rs`). */
    fun installPathParams(pathParams: ParamMap) {
        params = ParamMap.combined(requestParams, queryParams, pathParams)
        this.pathParams = pathParams
    }

    /** Stores a per-request value such as the current user or session (`Current.user = ...`), one per type. */
    inline fun <reified T : Any> setCurrent(value: T) {
        currentValues[T::class.java] = value
    }

    inline fun <reified T : Any> current(): T? = currentValues[T::class.java] as T?

    inline fun <reified T : Any> takeCurrent(): T? = currentValues.remove(T::class.java) as T?

    fun param(key: String): Param? = params[key]

    fun paramStr(key: String): String? = params.str(key)

    /**
     * `wrap_parameters format: [:json]`: for a JSON request, nest the body params under [key]
     * unless it's already there. [include] is the model's attribute names when it has a model.
     */
    fun wrapParameters(key: String, include: List<String>? = null) {
        val isJson = try {
            Format.contentMimeType(request.contentType) == Mime.JSON
        } catch (_: InvalidMimeType) {
            false
        }
        if (!isJson || params.containsKey(key)) return
        val wrapped = ParamMap()
        for ((k, v) in requestParams) {
            val keep = if (include != null) k in include else k != "authenticity_token" && k != "_method" && k != "utf8"
            if (keep) wrapped[k] = v
        }
        params[key] = Param.Hash(wrapped)
        requestParams[key] = Param.Hash(wrapped.copy())
    }

    /** `session`, loaded from the cookie on first use. */
    fun session(): Session = session.load(cookies)

    /** `reset_session`: new session id, no data, no flash. */
    fun resetSession() {
        session.reset()
        flash = null
    }

    /** `flash`, loaded from the session on first use. */
    fun flash(): Flash {
        flash?.let { return it }
        return Flash.fromSessionValue(session()["flash"]).also { flash = it }
    }

    /**
     * The `verify_authenticity_token` before-action, by `Sec-Fetch-Site` rather than tokens (Rails
     * main's `protect_from_forgery using: :header_only`): pages carry no per-request token, so they
     * render the same until what they show changes. Browsers send the header on every request to a
     * secure origin; without it (an old browser, or plain HTTP where browsers don't send it) a
     * write is only allowed when neither the request nor the app uses SSL, where the
     * `SameSite=Lax` session cookie and the `Origin` check are the protection. Throws
     * [KitError.InvalidAuthenticityToken].
     */
    fun verifyAuthenticityToken() {
        markedForSameOriginVerification = request.isGet
        if (request.isGet || request.isHead) return
        if (!validRequestOrigin()) {
            throw KitError.InvalidAuthenticityToken(
                "HTTP Origin header (${request.origin ?: ""}) didn't match request.base_url (${request.baseUrl()})",
            )
        }
        when (val site = request.header("sec-fetch-site")) {
            "same-origin", "same-site" -> return
            null -> if (!request.isSsl && !kit.config.forceSsl) return
            "cross-site" -> throw KitError.InvalidAuthenticityToken("Sec-Fetch-Site header (cross-site) indicates a cross-site request")
        }
        val other = request.header("sec-fetch-site")?.let { "Some(${RustDebug.str(it)})" } ?: "None"
        throw KitError.InvalidAuthenticityToken("Sec-Fetch-Site header is missing or invalid ($other)")
    }

    private fun validRequestOrigin(): Boolean {
        if (!kit.config.forgeryProtectionOriginCheck) return true
        return when (val origin = request.origin) {
            "null" -> throw KitError.InvalidAuthenticityToken("The browser returned a 'null' origin")
            null -> true
            else -> origin == request.baseUrl()
        }
    }

    /** `request.formats`, worked out once; an invalid `Accept` header is a 406 like Rails' `InvalidType`. */
    fun formats(): List<Mime> {
        formats?.let { return it }
        if (formatsInvalid) throw KitError.UnknownFormat()
        return try {
            Format.formats(negotiationInput()).also { formats = it }
        } catch (_: InvalidMimeType) {
            formatsInvalid = true
            throw KitError.UnknownFormat()
        }
    }

    private fun negotiationInput() = NegotiationInput(
        formatParam = params.str("format"),
        accept = request.header("accept"),
        contentType = request.contentType,
        path = request.path,
        xhr = request.isXhr,
    )

    /**
     * `_set_vary_header`, which every `render` runs (not `head` or `redirect_to`): `Vary: Accept`
     * when the format came from the `Accept` header, unless the response already varies.
     */
    fun setVaryHeader(response: Response): Response {
        if ("vary" in response.headers || !Format.shouldApplyVaryHeader(negotiationInput())) return response
        return response.header("vary", "Accept")
    }

    /** `request.format`: the first format; null is Rails' `Mime::NullType`. */
    fun format(): Mime? = formats().firstOrNull()

    /**
     * `respond_to do |format| ... end`: the first format the client accepts among [offered] (in
     * the order the block declares them), or 406 via [KitError.UnknownFormat].
     */
    fun respondTo(vararg offered: Mime): Mime {
        val list = offered.asList()
        val chosen = Format.negotiate(formats(), list) ?: throw KitError.UnknownFormat()
        val format = if (chosen == Mime.ALL) list.firstOrNull() ?: Mime.HTML else chosen
        renderedFormat = format
        return format
    }

    /** The format a render will use: the `respond_to` choice, else the first request format. */
    fun renderedFormat(): Mime {
        renderedFormat?.let { return it }
        val first = try {
            formats().firstOrNull()
        } catch (_: KitError.UnknownFormat) {
            null
        }
        return if (first != null && first != Mime.ALL) first else Mime.HTML
    }

    /** `turbo_frame_request?` */
    val isTurboFrameRequest: Boolean get() = turboFrameRequestId?.let { rustTrim(it).isNotEmpty() } ?: false

    val turboFrameRequestId: String? get() = request.header("turbo-frame")

    /** `render html:` / a template, as `text/html; charset=utf-8`. */
    fun renderHtml(status: Int, html: String): Response = renderAs(status, Responses.HTML_UTF8, Body.of(html))

    fun renderHtml(status: Int, html: ByteArray): Response = renderAs(status, Responses.HTML_UTF8, Body.Bytes(html))

    /** `render` with an explicit content type (`render json:`, ...), setting `Vary` as a render does. */
    fun renderAs(status: Int, contentType: String, body: Body): Response = setVaryHeader(Responses.withBody(status, contentType, body))

    fun renderAs(status: Int, contentType: String, body: String): Response = renderAs(status, contentType, Body.of(body))

    fun html(html: String): Response = renderHtml(200, html)

    /**
     * A rendered template, labelled with *the template's* format: Rails sets `rendered_format`
     * from the template the lookup found, not from the request, so an `.html.erb`-only action
     * answers `text/html` even when the `Accept` header prefers `text/vnd.turbo-stream.html`
     * (every Turbo form submission, and the redirect fetch follows). Pick the template with
     * [respondTo] (the implicit render's lookup) when an action has several.
     */
    fun render(status: Int, template: Mime, body: Body): Response {
        renderedFormat = template
        return renderAs(status, contentTypeOf(template), body)
    }

    fun render(status: Int, template: Mime, body: String): Response = render(status, template, Body.of(body))

    fun render(status: Int, template: Mime, body: ByteArray): Response = render(status, template, Body.Bytes(body))

    /**
     * [render] for a template that recorded where its cached fragments went (`render_spliced`):
     * a [Body.Page], whose ETag and gzip come from its parts. A page with no fragment part left
     * (every fragment folded into the text) is sent as plain bytes, as `Body::spliced` does, so
     * its ETag is the SHA-256 of the body.
     */
    fun renderPage(status: Int, template: Mime, parts: PageParts): Response = render(status, template, pageBody(parts))

    /** `render turbo_stream:` (`text/vnd.turbo-stream.html`). */
    fun turboStream(html: String): Response = renderAs(200, Responses.TURBO_STREAM_UTF8, Body.of(html))

    /** `render json:` for JSON text the caller has encoded. */
    fun json(status: Int, json: String): Response = renderAs(status, Responses.JSON_UTF8, Body.of(json))

    /** `head status`: no body; a bare content type (no charset) unless the status has no content. */
    fun head(status: Int): Response {
        val response = Response(status)
        if (!isBodiless(status) && status != 205) response.headers.add("content-type", renderedFormat().string)
        return response
    }

    /** `head status, location: url` */
    fun headWithLocation(status: Int, location: String): Response {
        val url = computeLocation(location)
        return head(status).header("location", url)
    }

    /** `redirect_to location, status:, notice:, alert:, allow_other_host:` (302 by default). Throws [KitError.UnsafeRedirect]. */
    fun redirectTo(location: String, options: Redirect = Redirect()): Response {
        options.notice?.let { flash().setNotice(it) }
        options.alert?.let { flash().setAlert(it) }
        val url = computeLocation(location)
        for (c in url) {
            if (c.code in 0x00..0x08 || c.code in 0x0A..0x1F) {
                throw KitError.UnsafeRedirect("The redirect URL $url contains illegal characters")
            }
        }
        if (!options.allowOtherHost && !urlHostAllowed(url)) throw KitError.UnsafeRedirect("Unsafe redirect to ${RustDebug.str(url)}")
        return Response(options.status ?: 302).header("content-type", Responses.HTML_UTF8).header("location", url)
    }

    /** `redirect_back_or_to fallback`: the referer when it's on this host. */
    fun redirectBackOrTo(fallback: String): Response {
        val referer = request.referer
        return if (referer != null && urlHostAllowed(referer)) redirectTo(referer) else redirectTo(fallback)
    }

    /**
     * `_compute_redirect_to_location` for strings: absolute URLs pass through, paths get the
     * request's protocol and host, path-relative URLs raise (`action_on_path_relative_redirect`).
     */
    private fun computeLocation(location: String): String {
        val url = if (isAbsoluteUrl(location)) {
            location
        } else {
            if (location.isNotEmpty() && !location.startsWith('/') && !location.startsWith('?')) {
                throw KitError.UnsafeRedirect("Path relative URL redirect detected: ${RustDebug.str(location)}")
            }
            request.protocol + request.hostWithPort() + location
        }
        if (url.indexOf('\u0000') < 0 && url.indexOf('\r') < 0 && url.indexOf('\n') < 0) return url
        val out = StringBuilder(url.length)
        for (c in url) if (c != '\u0000' && c != '\r' && c != '\n') out.append(c)
        return out.toString()
    }

    /** `_url_host_allowed?` */
    private fun urlHostAllowed(url: String): Boolean {
        val host = urlHost(url) ?: return url.startsWith('/') && !url.startsWith("//")
        return host.equals(request.host(), ignoreCase = true)
    }

    /**
     * An absolute URL for [path] on this request's host (what `*_url` helpers produce with
     * `default_url_options` from `SetCurrentRequest`).
     */
    fun urlFor(path: String): String = request.protocol + request.hostWithPort() + path

    /**
     * `send_file path, type:, disposition:, filename:`: the whole file, its name as the filename
     * unless one is given. A path that isn't a readable regular file is a 500.
     */
    fun sendFile(path: Path, options: SendOptions): Response {
        val size = try {
            if (Files.isRegularFile(path)) Files.size(path) else null
        } catch (_: java.io.IOException) {
            null
        } ?: throw KitError.internal("Cannot read file $path")
        val named = if (options.filename == null) options.copy(filename = path.fileName?.toString()) else options
        return Responses.send(named, Body.File(path, 0, size))
    }

    /** `send_data data, type:, disposition:, filename:` */
    fun sendData(data: ByteArray, options: SendOptions): Response = Responses.send(options, Body.Bytes(data))

    /** `fresh_when`: sets ETag/Last-Modified and returns a 304 when the request is fresh, else null. */
    fun freshWhen(freshness: Freshness): Response? {
        cacheControl.noStore = false
        if (freshness.strongEtag != null || freshness.etag != null || freshness.template != null) {
            val strong = freshness.strongEtag
            val etag = combineEtags(strong ?: freshness.etag, freshness)
            setHeader("etag", if (strong == null) "W/\"$etag\"" else "\"$etag\"")
        }
        freshness.lastModified?.let { setHeader("last-modified", HttpDate.format(it)) }
        if (freshness.public) cacheControl.public = true
        return if (isFresh(headers["etag"], headers["last-modified"])) head(304) else null
    }

    /** `stale?`: the inverse of freshness, after setting the validators. */
    fun stale(freshness: Freshness): Boolean = freshWhen(freshness) == null

    /**
     * `[validator, *etaggers]` digested like `ActiveSupport::Digest.hexdigest(expand_cache_key(...))`.
     * The etaggers are turbo-rails' frame etagger, `ETagWithTemplateDigest` and `ETagWithFlash`.
     */
    private fun combineEtags(validator: String?, freshness: Freshness): String {
        val parts = ArrayList<String>(4)
        validator?.let(parts::add)
        if (isTurboFrameRequest) parts.add("frame")
        freshness.template?.let(parts::add)
        val flash = flash()
        if (!flash.isEmpty()) parts.add(flash.keys.joinToString("&") { "$it=${RustDebug.option(flash[it], true)}" })
        return HEX.formatHex(sha256(parts.joinToString("/").toByteArray(Charsets.UTF_8)), 0, 16)
    }

    /** `expires_in seconds, public:, stale_while_revalidate:, ...`, which also sets `Date`. */
    fun expiresIn(seconds: Long, options: ExpiresIn = ExpiresIn()) {
        cacheControl.noStore = false
        cacheControl.maxAge = seconds
        cacheControl.public = options.public
        cacheControl.mustRevalidate = options.mustRevalidate
        cacheControl.staleWhileRevalidate = options.staleWhileRevalidate
        cacheControl.staleIfError = options.staleIfError
        cacheControl.immutable = options.immutable
        if ("date" !in headers) setHeader("date", HttpDate.format(now()))
    }

    /** `expires_now` */
    fun expiresNow() {
        cacheControl = CacheControl(noCache = true)
    }

    /** `no_store` */
    fun noStore() {
        cacheControl = CacheControl(noStore = true)
    }

    /**
     * Sets a response header ahead of the response (`response.headers[...] = ...`), as Puma would
     * write the value ([Responses.setHeaderValue]).
     */
    fun setHeader(name: String, value: String) = Responses.setHeaderValue(headers, name, value)

    /**
     * A controller that includes `ActionController::Live` (`ActiveStorage::Streaming` does): its
     * `make_response!` builds the response with `Live::Response.new`, which skips
     * `ActionDispatch::Response.create` and so `config.action_dispatch.default_headers`, and its
     * body is never digested for an ETag.
     */
    fun useLiveResponse() {
        live = true
    }

    /**
     * Turns the action's outcome (its [response], or the [error] it threw) into the response Rails
     * would send (`Ctx::finish`): halts and errors resolved, flash and session committed into
     * cookies, cache headers, default headers, the ETag and 304. An error answers with the error
     * page alone: no cookies, no default headers.
     */
    fun finish(response: Response?, error: Throwable?): Response {
        val out = when {
            error == null -> response ?: throw IllegalArgumentException("finish needs a response or an error")
            error is KitError.Halt -> error.response
            else -> return errorResponse(kitError(error))
        }
        if (headers.size > 0) for ((name, value) in headers) if (name !in out.headers) out.headers.add(name, value)
        try {
            verifySameOriginRequest(out)
            commit(out)
        } catch (e: Exception) {
            return errorResponse(kitError(e))
        }
        applyCacheHeaders(out)
        if (!live) for ((name, value) in kit.config.defaultHeaders) if (name !in out.headers) out.headers.add(name, value)
        if ("content-type" !in out.headers && !isBodiless(out.status) && out.status != 205) out.headers.add("content-type", Responses.HTML_UTF8)
        rackEtag(out, !live)
        conditionalGet(out)
        return out
    }

    /** `verify_same_origin_request`: a GET that renders JavaScript for a non-XHR request is a cross-origin `<script>` embed. */
    private fun verifySameOriginRequest(response: Response) {
        val contentType = response.headers["content-type"] ?: return
        val javascript = contentType.startsWith("text/javascript") || contentType.startsWith("application/javascript")
        if (markedForSameOriginVerification && javascript && !request.isXhr) throw KitError.InvalidCrossOriginRequest()
    }

    /** `commit_flash`, `commit_session`, then the cookie jar's `write`. */
    private fun commit(response: Response) {
        val pending = flash
        if (pending != null) {
            flash = null
            val hasFlashKey = session().containsKey("flash")
            if (!pending.isEmpty() || hasFlashKey) session()["flash"] = pending.toSessionValue()
        }
        if (session.isLoaded && session.containsKey("flash") && session["flash"] == null) session.remove("flash")
        session.commit(cookies, now())
        if (!cookies.hasSetCookies) return
        for (cookie in cookies.setCookieHeaders(request.isSsl, request.host())) response.headers.add("set-cookie", cookie)
    }

    /** `handle_conditional_get!` and `merge_and_normalize_cache_control!`. */
    private fun applyCacheHeaders(response: Response) {
        if ("cache-control" in response.headers) return
        val value = if (cacheControl.isEmpty() && ("etag" in response.headers || "last-modified" in response.headers)) {
            REVALIDATE
        } else {
            cacheControl.toHeader() ?: return
        }
        response.headers.add("cache-control", value)
    }

    /** `Rack::ConditionalGet` */
    private fun conditionalGet(response: Response) {
        if ((request.method != "GET" && request.method != "HEAD") || response.status != 200) return
        if (isFresh(response.headers["etag"], response.headers["last-modified"])) {
            response.status = 304
            response.headers.remove("content-type")
            response.headers.remove("content-length")
            response.body = Body.Empty
        }
    }

    /**
     * `request.fresh?(response)` with `strict_freshness` (the 8.0 default), which `fresh_when` and
     * `Rack::ConditionalGet` both go by here: an `If-None-Match` list naming the ETag (or `*`), or
     * else an `If-Modified-Since` no earlier than `Last-Modified`. (Rack's own check wants the whole
     * `If-None-Match` to equal the ETag.)
     */
    private fun isFresh(etag: String?, lastModified: String?): Boolean {
        val ifNoneMatch = request.header("if-none-match")
        if (ifNoneMatch != null) return etag != null && listNames(ifNoneMatch, etag)
        val since = request.header("if-modified-since")?.let(::parseHttpDate) ?: return false
        val modified = lastModified?.let(::parseHttpDate) ?: return false
        return !since.isBefore(modified)
    }

    /** Whether the comma-separated [list] has [etag] or `*` as one of its trimmed items. */
    private fun listNames(list: String, etag: String): Boolean {
        var start = 0
        while (start <= list.length) {
            var end = list.indexOf(',', start)
            if (end < 0) end = list.length
            var from = start
            var to = end
            while (from < to && isRustWhitespace(list[from])) from++
            while (to > from && isRustWhitespace(list[to - 1])) to--
            val length = to - from
            if ((length == etag.length && list.regionMatches(from, etag, 0, length)) || (length == 1 && list[from] == '*')) return true
            start = end + 1
        }
        return false
    }

    /**
     * What `ShowExceptions` + `PublicExceptions` render for an error raised in the action, logged
     * with its causes: at error level for a 5xx ("request failed"), info otherwise ("request
     * rejected").
     */
    private fun errorResponse(error: KitError): Response {
        val failed = error.status in 500..599
        val line = Tracing.line(if (failed) "ERROR" else "INFO", "campfire_kit::ctx", if (failed) "request failed" else "request rejected")
        line.append(" error=").append(error.message).append(" path=").append(RustDebug.str(request.path))
        kit.log.line(line.toString())
        val format = try {
            formats().firstOrNull()
        } catch (_: KitError) {
            null
        }
        return PublicExceptions.render(kit.config.errorPages, error.status, format, request.isHead)
    }

    companion object {
        private val HEX: HexFormat = HexFormat.of()
        private const val REVALIDATE = "max-age=0, private, must-revalidate"

        /** A status that carries no body, so no `Content-Type` (1xx, 204, 304; `head` also skips 205). */
        internal fun isBodiless(status: Int): Boolean = status in 100..199 || status == 204 || status == 304

        /** [error] as the kit error it answers with: an unknown exception is an [KitError.Internal]. */
        internal fun kitError(error: Throwable): KitError = when (error) {
            is KitError -> error
            is ParamError -> error.toKitError()
            else -> KitError.Internal(error)
        }

        private fun contentTypeOf(template: Mime): String = when (template) {
            Mime.HTML -> Responses.HTML_UTF8
            Mime.TURBO_STREAM -> Responses.TURBO_STREAM_UTF8
            Mime.JSON -> Responses.JSON_UTF8
            else -> template.string + "; charset=utf-8"
        }

        /** A page as a [Body.Page], or as bytes when it has no fragment part (`Body::spliced`). */
        internal fun pageBody(parts: PageParts): Body =
            if (parts.parts.any { it is PageParts.Part.Frag }) Body.Page(parts) else Body.Bytes(Responses.joined(parts.chunks()))

        /**
         * `Rack::ETag` (installed as `Rack::ETag, "no-cache"`): weak SHA-256 ETags for 200/201
         * bodies without validators, and a default `Cache-Control`. A Live response's body is a
         * `Live::Buffer`, which doesn't respond to `to_ary`, so it's never digested: whatever such
         * a controller renders goes out with `no-cache` and no ETag.
         */
        internal fun rackEtag(response: Response, digestible: Boolean) {
            var digested = false
            val skip = !digestible || "etag" in response.headers || "last-modified" in response.headers
            if ((response.status == 200 || response.status == 201) && !skip) {
                val etag = bodyEtag(response)
                if (etag != null) {
                    response.headers.add("etag", etag)
                    digested = true
                }
            }
            if ("cache-control" !in response.headers) response.headers.add("cache-control", if (digested) REVALIDATE else "no-cache")
        }

        /**
         * `Rack::ETag`'s weak ETag of a non-empty body. A page of cached fragments hashes its parts'
         * digests rather than the whole body; another body keeps its SHA-256 for the gzip cache
         * ([BodyDigest]).
         */
        private fun bodyEtag(response: Response): String? = when (val body = response.body) {
            is Body.Page -> body.parts.etag()
            is Body.Bytes -> if (body.bytes.isEmpty()) {
                null
            } else {
                val sha = sha256(body.bytes)
                response.putExtension(BodyDigest(sha))
                "W/\"" + HEX.formatHex(sha, 0, 16) + "\""
            }
            else -> null
        }

        /** Whether [location] is absolute: protocol-relative, or a scheme (a letter, then letters, digits, `+-.`) and `:`. */
        private fun isAbsoluteUrl(location: String): Boolean {
            if (location.startsWith("//")) return true
            if (location.isEmpty() || !isAsciiLetter(location[0])) return false
            val colon = location.indexOf(':')
            if (colon < 0) return false
            for (i in 0 until colon) {
                val c = location[i]
                if (!isAsciiLetter(c) && c !in '0'..'9' && c != '-' && c != '+' && c != '.') return false
            }
            return true
        }

        private fun isAsciiLetter(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z'

        /** The host of an absolute or protocol-relative URL, null for paths (`url_host` in `ctx.rs`). */
        internal fun urlHost(url: String): String? {
            val rest = if (url.startsWith("//")) {
                url.substring(2)
            } else {
                val sep = url.indexOf("://")
                if (sep < 0) return null
                val scheme = url.substring(0, sep)
                if (scheme.isEmpty() || !scheme.all { isAsciiLetter(it) || it in '0'..'9' || it == '+' || it == '-' || it == '.' }) return null
                url.substring(sep + 3)
            }
            var end = 0
            while (end < rest.length && rest[end] != '/' && rest[end] != '?' && rest[end] != '#') end++
            val authority = rest.substring(0, end)
            val hostPort = authority.substring(authority.lastIndexOf('@') + 1)
            if (hostPort.startsWith('[')) {
                val close = hostPort.indexOf(']', 1)
                return "[" + (if (close < 0) hostPort.substring(1) else hostPort.substring(1, close)) + "]"
            }
            val colon = hostPort.indexOf(':')
            return if (colon < 0) hostPort else hostPort.substring(0, colon)
        }

        /**
         * An HTTP date (`clock::parse_httpdate`, `Time.httpdate`/`Time.rfc2822`), null when
         * malformed. Java's RFC 1123 parser stands in for jiff's RFC 2822 one; both read what
         * browsers send.
         */
        internal fun parseHttpDate(value: String): Instant? = try {
            ZonedDateTime.parse(rustTrim(value), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        } catch (_: java.time.DateTimeException) {
            null
        }
    }
}
