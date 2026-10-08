package campfire.kit

/**
 * Responses as controllers build them, before the kit finishes them (cookies, default headers,
 * ETags, conditional GET, HEAD): `once-campfire-rust/crates/kit/src/response.rs` mapped onto
 * [Response] and [Body]. Rust's `Body::Bytes` is [Body.Bytes], `Body::Parts` is [Body.Page],
 * `Body::File` is [Body.File]; a streaming body is [Body.Chunks].
 */
object Responses {
    const val HTML_UTF8 = "text/html; charset=utf-8"
    const val JSON_UTF8 = "application/json; charset=utf-8"
    const val TURBO_STREAM_UTF8 = "text/vnd.turbo-stream.html; charset=utf-8"

    /** `Response::with_body`: [status] with a `Content-Type` and [body]. */
    fun withBody(status: Int, contentType: String, body: Body): Response =
        Response(status).header("content-type", contentType).also { it.body = body }

    /** [withBody] for bytes. */
    fun withBody(status: Int, contentType: String, body: ByteArray): Response = withBody(status, contentType, Body.Bytes(body))

    /**
     * Replaces [name] with [value] the way Puma writes a Rack header (puma 7.2.1,
     * `Puma::Request#str_headers`; `set_header_value` in `response.rs`). A value with a line break
     * goes out one line per header line (`split("\n")`, which drops trailing empty lines), and a
     * line holding any other control character (`ILLEGAL_HEADER_VALUE_REGEX`, and DEL, which
     * Thruster would refuse with a 502) is left out. Values come from the request at times, like
     * `?disposition=` on a proxied blob.
     */
    fun setHeaderValue(headers: Headers, name: String, value: String) {
        if (isValidHeaderValue(value, 0, value.length)) {
            headers[name] = value
            return
        }
        headers.remove(name)
        var end = value.length
        while (end > 0 && value[end - 1] == '\n') end--
        if (end == 0) return
        var start = 0
        while (start <= end) {
            var stop = value.indexOf('\n', start)
            if (stop < 0 || stop > end) stop = end
            if (isValidHeaderValue(value, start, stop)) headers.add(name, value.substring(start, stop))
            if (stop == end) break
            start = stop + 1
        }
    }

    /** `HeaderValue::from_str`: no control characters but tab, and no DEL (non-ASCII is fine). */
    private fun isValidHeaderValue(value: String, from: Int, to: Int): Boolean {
        for (i in from until to) {
            val c = value[i]
            if ((c < ' ' && c != '\t') || c == '\u007f') return false
        }
        return true
    }

    /** `Content-Disposition` as `ActionDispatch::Http::ContentDisposition#to_s` builds it. */
    fun contentDisposition(disposition: String, filename: String?): String =
        if (filename == null) disposition else campfire.rails.ContentDisposition.format(disposition, filename)

    /**
     * `send_file_headers!` then the body, file or bytes, whole (as Rails' `send_file` and
     * `send_data` do, ignoring `Range`): the type from the options or the filename's extension
     * (else `application/octet-stream`), `Content-Disposition` unless it's off, and
     * `Content-Transfer-Encoding: binary`.
     */
    fun send(options: SendOptions, body: Body): Response {
        val contentType = options.contentType
            ?: options.filename?.let(::extension)?.let { Format.lookupByExtension(it.lowercase()) }?.string
            ?: "application/octet-stream"
        val response = Response(options.status).header("content-type", contentType)
        options.disposition?.let { response.header("content-disposition", contentDisposition(it, options.filename)) }
        response.header("content-transfer-encoding", "binary")
        response.body = body
        return response
    }

    /** Rust's `Path::extension` of a file name: after the last dot, unless the name starts with its only dot. */
    internal fun extension(filename: String): String? {
        val name = filename.substring(filename.lastIndexOf('/') + 1)
        if (name == "..") return null
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) null else name.substring(dot + 1)
    }

    /** The bytes a [body] holds when it's in memory; null for files. */
    fun bodyBytes(body: Body): ByteArray? = when (body) {
        is Body.Bytes -> body.bytes
        is Body.Empty -> ByteArray(0)
        is Body.File -> null
        else -> joined(body.chunks())
    }

    /** [chunks] as one array. */
    internal fun joined(chunks: List<ByteArray>): ByteArray {
        if (chunks.size == 1) return chunks[0]
        val out = ByteArray(chunks.sumOf { it.size })
        var at = 0
        for (chunk in chunks) {
            System.arraycopy(chunk, 0, out, at, chunk.size)
            at += chunk.size
        }
        return out
    }
}

/** Sets (replaces) a header as Puma would write the value ([Responses.setHeaderValue]), for chaining (`Response::header`). */
fun Response.header(name: String, value: String): Response = apply { Responses.setHeaderValue(headers, name, value) }

/**
 * Options for `send_file` / `send_data` (`SendOptions` in `response.rs`). [contentType] (`type:`)
 * defaults to the filename's MIME type, else `application/octet-stream`; [disposition] defaults to
 * `attachment`, and null leaves the header out.
 */
data class SendOptions(
    val filename: String? = null,
    val contentType: String? = null,
    val disposition: String? = "attachment",
    val status: Int = 200,
) {
    companion object {
        /** `type: contentType, disposition: :inline`. */
        fun inline(contentType: String): SendOptions = SendOptions(contentType = contentType, disposition = "inline")
    }
}

/**
 * `Cache-Control` directives set by `expires_in`, `fresh_when(public:)`, `no_store` and friends,
 * normalized like `ActionDispatch::Http::Cache::Response#merge_and_normalize_cache_control!`
 * (`CacheControl` in `response.rs`).
 */
data class CacheControl(
    var maxAge: Long? = null,
    var public: Boolean = false,
    var private: Boolean = false,
    var mustRevalidate: Boolean = false,
    var noCache: Boolean = false,
    var noStore: Boolean = false,
    var mustUnderstand: Boolean = false,
    var staleWhileRevalidate: Long? = null,
    var staleIfError: Long? = null,
    var immutable: Boolean = false,
    val extras: MutableList<String> = ArrayList(0),
) {
    fun isEmpty(): Boolean = this == EMPTY

    /** The header value, or null when nothing is set. */
    fun toHeader(): String? {
        if (isEmpty()) return null
        val options = ArrayList<String>(6)
        if (noStore) {
            if (private) options.add("private")
            if (mustUnderstand) options.add("must-understand")
            options.add("no-store")
        } else if (noCache) {
            if (public) options.add("public")
            options.add("no-cache")
            options.addAll(extras)
        } else {
            maxAge?.let { options.add("max-age=$it") }
            options.add(if (public) "public" else "private")
            if (mustRevalidate) options.add("must-revalidate")
            staleWhileRevalidate?.let { options.add("stale-while-revalidate=$it") }
            staleIfError?.let { options.add("stale-if-error=$it") }
            if (immutable) options.add("immutable")
            options.addAll(extras)
        }
        return options.joinToString(", ")
    }

    private companion object {
        val EMPTY = CacheControl()
    }
}

/** Options for `expires_in` (`ExpiresIn` in `response.rs`). */
data class ExpiresIn(
    val public: Boolean = false,
    val mustRevalidate: Boolean = false,
    val staleWhileRevalidate: Long? = null,
    val staleIfError: Long? = null,
    val immutable: Boolean = false,
)
