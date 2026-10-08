package campfire.kit

/**
 * The errors an action (or the kit around it) raises, with the status Rails answers them with:
 * `once-campfire-rust/crates/kit/src/error.rs`'s `Error`, as
 * `ActionDispatch::ExceptionWrapper.rescue_responses` maps exception classes to statuses. Messages
 * match the Rust `Display` in its alternate (`{:#}`) form, which is what the request log prints.
 * Stack traces are not filled in: these are answers, not bugs.
 *
 * [Halt] is not an error at all: it's how a before-action that rendered or redirected stops the
 * chain (see [halt]).
 */
sealed class KitError(message: String?, val status: Int, cause: Throwable? = null) : RuntimeException(message, cause, false, false) {
    /**
     * A before-action (or the action itself) produced [response]; stop and send it (`Error::Halt`).
     * The message is built only when asked for.
     */
    class Halt(val response: Response) : KitError(null, response.status) {
        override val message: String get() = "halted with ${statusLine(response.status)}"
    }

    /** `ActionController::BadRequest`, `ParamError`s, `Parameters::ParseError` (400). */
    class BadRequest(val detail: String) : KitError("bad request: $detail", 400)

    /** `ActionController::ParameterMissing` (400). */
    class ParameterMissing(val param: String) :
        KitError("param is missing or the value is empty or invalid: $param", 400)

    /** `ActionController::InvalidAuthenticityToken`, the `:exception` forgery strategy (422). */
    class InvalidAuthenticityToken(val detail: String) : KitError("invalid authenticity token: $detail", 422)

    /** `ActionController::InvalidCrossOriginRequest` (422). */
    class InvalidCrossOriginRequest : KitError("invalid cross-origin request", 422)

    /** `ActionController::UnknownFormat` / `MissingExactTemplate` (406). */
    class UnknownFormat : KitError("unknown format", 406)

    /** `ActiveRecord::RecordNotFound`, `ActionController::RoutingError` (404). */
    class NotFound : KitError("not found", 404)

    /** `ActionController::UnknownHttpMethod` (405). */
    class MethodNotAllowed : KitError("unknown http method", 405)

    /** `ActionDispatch::Cookies::CookieOverflow`, which Rails doesn't rescue (500). */
    class CookieOverflow(val detail: String) : KitError("$detail cookie overflowed", 500)

    /** `ActionController::Redirecting::UnsafeRedirectError` and friends (500). */
    class UnsafeRedirect(val detail: String) : KitError("unsafe redirect: $detail", 500)

    /** `ActionDispatch::RemoteIp::IpSpoofAttackError` (500). */
    class IpSpoofAttack : KitError("IP spoofing attack", 500)

    /** Any other status an app wants to map an error to (`Error::Status`). */
    class Status(status: Int) : KitError(statusLine(status), status)

    /**
     * Like [Status], keeping the error that caused it for the log line (`Error::WithStatus`, built
     * by `Error::with_status`): "422 Unprocessable Entity: validation failed: name can't be blank".
     */
    class WithStatus(status: Int, cause: Throwable) : KitError("${statusLine(status)}: ${causeChain(cause)}", status, cause)

    /**
     * Anything else: a 500, logged with its causes (`Error::Internal`, anyhow's `{:#}`). An
     * exception an action throws that isn't a [KitError] is answered as one of these, as Rust
     * answers a panicking action.
     */
    class Internal(cause: Throwable) : KitError(causeChain(cause), 500, cause)

    /** A plain message as a cause, without a stack trace (`anyhow!(message)`). */
    private class Message(message: String) : RuntimeException(message, null, false, false)

    companion object {
        /** `Error::internal(anyhow!(message))`. */
        fun internal(message: String): Internal = Internal(Message(message))

        /**
         * `http::StatusCode`'s `Display`: the code and its canonical reason (`404 Not Found`), or
         * `<unknown status code>` past the table.
         */
        fun statusLine(status: Int): String = "$status ${canonicalReason(status) ?: "<unknown status code>"}"

        /** An error and its causes, outermost first, joined by ": " as anyhow's `{:#}` prints them. */
        fun causeChain(error: Throwable): String {
            val out = StringBuilder()
            var current: Throwable? = error
            var depth = 0
            while (current != null && depth < 16) {
                if (depth > 0) out.append(": ")
                out.append(current.message ?: current.javaClass.name)
                current = current.cause.takeIf { it !== current }
                depth++
            }
            return out.toString()
        }

        /** `http::StatusCode::canonical_reason` (http 1.5). */
        fun canonicalReason(status: Int): String? = when (status) {
            100 -> "Continue"
            101 -> "Switching Protocols"
            102 -> "Processing"
            103 -> "Early Hints"
            200 -> "OK"
            201 -> "Created"
            202 -> "Accepted"
            203 -> "Non Authoritative Information"
            204 -> "No Content"
            205 -> "Reset Content"
            206 -> "Partial Content"
            207 -> "Multi-Status"
            208 -> "Already Reported"
            226 -> "IM Used"
            300 -> "Multiple Choices"
            301 -> "Moved Permanently"
            302 -> "Found"
            303 -> "See Other"
            304 -> "Not Modified"
            305 -> "Use Proxy"
            307 -> "Temporary Redirect"
            308 -> "Permanent Redirect"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            402 -> "Payment Required"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            406 -> "Not Acceptable"
            407 -> "Proxy Authentication Required"
            408 -> "Request Timeout"
            409 -> "Conflict"
            410 -> "Gone"
            411 -> "Length Required"
            412 -> "Precondition Failed"
            413 -> "Payload Too Large"
            414 -> "URI Too Long"
            415 -> "Unsupported Media Type"
            416 -> "Range Not Satisfiable"
            417 -> "Expectation Failed"
            418 -> "I'm a teapot"
            421 -> "Misdirected Request"
            422 -> "Unprocessable Entity"
            423 -> "Locked"
            424 -> "Failed Dependency"
            425 -> "Too Early"
            426 -> "Upgrade Required"
            428 -> "Precondition Required"
            429 -> "Too Many Requests"
            431 -> "Request Header Fields Too Large"
            451 -> "Unavailable For Legal Reasons"
            500 -> "Internal Server Error"
            501 -> "Not Implemented"
            502 -> "Bad Gateway"
            503 -> "Service Unavailable"
            504 -> "Gateway Timeout"
            505 -> "HTTP Version Not Supported"
            506 -> "Variant Also Negotiates"
            507 -> "Insufficient Storage"
            508 -> "Loop Detected"
            510 -> "Not Extended"
            511 -> "Network Authentication Required"
            else -> null
        }
    }
}

/**
 * Stop the before-action chain with [response], like a Rails callback that renders or redirects
 * (`halt` in `once-campfire-rust/crates/kit/src/error.rs`):
 *
 * ```
 * fun ensureCanAdminister(c: Ctx) {
 *     if (!canAdminister(c)) halt(c.head(403))
 * }
 * ```
 */
fun halt(response: Response): Nothing = throw KitError.Halt(response)
