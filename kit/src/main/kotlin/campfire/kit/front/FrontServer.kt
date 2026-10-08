package campfire.kit.front

import campfire.kit.Handler
import campfire.kit.HttpServer
import campfire.kit.Middleware
import campfire.kit.ServerOptions
import campfire.kit.layered

/**
 * The front server (`crates/kit/src/front.rs`): what Thruster 0.1.23 did in front of the
 * reference's Puma, in process, over plain HTTP/1.1.
 *
 * - HTTP_PORT gets every request through [Front] (cache, compression, `X-Forwarded-*`,
 *   MAX_REQUEST_BODY, the request log), with a `Date` on every response and the HTTP_*_TIMEOUTs.
 * - The app still listens on TARGET_PORT by itself, as Puma did behind Thruster: no `Date`, no
 *   cache or compression, but on TARGET_BIND (loopback) and with the front's timeouts and
 *   MAX_REQUEST_BODY, since whoever reaches it can claim any `X-Forwarded-*`. It isn't started
 *   when TARGET_PORT is HTTP_PORT.
 *
 * TLS (TLS_DOMAIN, ACME, HTTPS_PORT) and HTTP/2 (H2C_ENABLED) aren't served yet: [start] refuses a
 * configuration with TLS_DOMAIN rather than serve it over plain HTTP.
 */
class FrontServer(
    private val config: FrontConfig,
    private val app: Handler,
    private val workers: Int = Runtime.getRuntime().availableProcessors(),
    private val log: LogSink = LogSink.STDERR,
) {
    private var front: HttpServer? = null
    private var upstream: HttpServer? = null

    /** Binds HTTP_PORT and (unless it's the same port) TARGET_PORT, and returns once both accept connections. */
    fun start(): FrontServer = apply {
        check(!config.hasTls) { "TLS_DOMAIN is set, but this front server serves plain HTTP only" }
        if (config.targetPort == config.httpPort) {
            log.line(Tracing.line("WARN", "campfire_kit::front", "TARGET_PORT is the front server's port; not listening on it separately").append(" port=").append(config.targetPort).toString())
        } else {
            upstream = HttpServer(options(config.targetPort, Front.ipText(config.targetBind), date = false), app.layered(BodyLimit(config.maxRequestBody))).start()
        }
        front = HttpServer(options(config.httpPort, null, date = true), app.layered(Front(config, log))).start()
        log.line(Tracing.line("INFO", "campfire_kit::front", "Server started").append(" http=:").append(config.httpPort).toString())
    }

    /** The front listener's port (the chosen one when HTTP_PORT is 0). */
    val httpPort: Int get() = front?.port ?: config.httpPort

    /** The app's own listener's port, or null when it isn't listening separately. */
    val targetPort: Int? get() = upstream?.port

    /** Blocks until the front listener closes. */
    fun await() {
        front?.await()
    }

    /** Stops both listeners. */
    fun stop() {
        front?.stop()
        upstream?.stop()
        log.line(Tracing.line("INFO", "campfire_kit::front", "Server stopped").toString())
    }

    private fun options(port: Int, bind: String?, date: Boolean) = ServerOptions(
        port = port,
        bind = bind,
        workers = workers,
        date = date,
        maxRequestBody = maxRequestBody(config.maxRequestBody),
        readTimeoutMillis = config.httpReadTimeout.toMillis(),
        writeTimeoutMillis = config.httpWriteTimeout.toMillis(),
        idleTimeoutMillis = config.httpIdleTimeout.toMillis(),
        oversizedToHandler = true,
    )

    /**
     * MAX_REQUEST_BODY as the app listener's own layer (`limited_app_service` in
     * `crates/kit/src/front.rs`): an oversized body gets the empty 413 without reaching the app.
     */
    class BodyLimit(maxRequestBody: Long) : Middleware {
        private val limit = maxRequestBody.coerceAtLeast(0)

        override fun handle(request: campfire.kit.Request, next: Handler): campfire.kit.Response =
            if (Front.overLimit(request, limit)) Front.tooLarge() else next.handle(request)
    }

    private companion object {
        /** The aggregator's limit: MAX_REQUEST_BODY, or the largest Netty takes when it's 0 (no limit). */
        fun maxRequestBody(limit: Long): Int = if (limit <= 0 || limit >= Int.MAX_VALUE) Int.MAX_VALUE else limit.toInt()
    }
}
