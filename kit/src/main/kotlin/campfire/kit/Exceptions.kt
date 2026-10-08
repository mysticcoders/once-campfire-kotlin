package campfire.kit

/**
 * The `public/<status>.html` pages, held in memory (`ErrorPages` in
 * `once-campfire-rust/crates/kit/src/exceptions.rs`). The pages are shared and never copied: a
 * response wraps the same array.
 */
class ErrorPages(pages: Map<Int, ByteArray> = emptyMap()) {
    private val pages: Map<Int, ByteArray> = HashMap(pages)

    operator fun get(status: Int): ByteArray? = pages[status]

    companion object {
        /** No pages: every error is an empty body with its status. */
        val NONE = ErrorPages()

        /**
         * The reference's `public/404.html`, `422.html`, `500.html` and `502.html`, as the Rust app
         * embeds them (`error_pages` in `crates/campfire/src/app.rs`). The build copies them from
         * `reference/public` onto the classpath under `campfire/kit/public/`.
         */
        val PUBLIC: ErrorPages by lazy {
            val pages = HashMap<Int, ByteArray>()
            for (status in intArrayOf(404, 422, 500, 502)) {
                ErrorPages::class.java.getResourceAsStream("/campfire/kit/public/$status.html")?.use { pages[status] = it.readAllBytes() }
            }
            ErrorPages(pages)
        }
    }
}

/**
 * `ActionDispatch::PublicExceptions`: what an error looks like on the wire in production
 * (`once-campfire-rust/crates/kit/src/exceptions.rs`).
 *
 * JSON, XML and YAML requests get the `{ status:, error: }` hash in that format (the formats the
 * hash responds to `to_<format>` for); everything else gets `public/<status>.html`, or an empty
 * body with the status when that file doesn't exist. HEAD gets an empty body in the request's
 * format, `*` `/` `*` included.
 *
 * Unlike rendered responses (`charset=utf-8`), these say `charset=UTF-8`: PublicExceptions and
 * `ShowExceptions#pass_response` interpolate `ActionDispatch::Response.default_charset`, which
 * the railtie sets from `config.encoding`.
 */
object PublicExceptions {
    private const val CHARSET = "; charset=UTF-8"
    private const val HTML_UTF8 = "text/html; charset=UTF-8"
    private val EMPTY = ByteArray(0)

    /** The error page for [status], in [format] (`request.formats.first`; null is HTML), without a body for HEAD. */
    fun render(pages: ErrorPages, status: Int, format: Mime?, head: Boolean): Response {
        if (head) return withLength(status, contentType(format), EMPTY)
        val error = reason(status)
        val body = when (format?.symbol) {
            "json" -> "{\"status\":$status,\"error\":\"$error\"}"
            "xml" -> "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<hash>\n  <status type=\"integer\">$status</status>\n  <error>${xmlEscape(error)}</error>\n</hash>\n"
            "yaml" -> "---\n:status: $status\n:error: $error\n"
            else -> null
        }
        if (body != null) return withLength(status, contentType(format), body.toByteArray(Charsets.UTF_8))
        return withLength(status, HTML_UTF8, pages[status] ?: EMPTY)
    }

    private fun contentType(format: Mime?): String = if (format == null || format == Mime.HTML) HTML_UTF8 else format.string + CHARSET

    /**
     * `render_format` and `pass_response` set `Content-Length` themselves, so `Rack::Deflater`
     * leaves the empty ones alone (`should_deflate?` skips `Content-Length: 0`).
     */
    private fun withLength(status: Int, contentType: String, body: ByteArray): Response {
        val response = Responses.withBody(status, contentType, body)
        response.headers.add("content-length", body.size.toString())
        return response
    }

    /** Builder's text escaping (`Hash#to_xml`). */
    private fun xmlEscape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** `Rack::Utils::HTTP_STATUS_CODES`: the RFC 9110 names where they differ from the `http` crate's. */
    fun reason(status: Int): String = when (status) {
        422 -> "Unprocessable Content"
        413 -> "Content Too Large"
        else -> KitError.canonicalReason(status) ?: "Internal Server Error"
    }
}
