package campfire.assets

import campfire.ruby.Rack
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * The parts of a request ActionDispatch::Static looks at (`once-campfire-rust/crates/assets/src/serve.rs`,
 * `StaticRequest`). [path] is the raw (still percent-encoded) request path, without the query string.
 */
data class StaticRequest(
    val method: String,
    val path: String,
    val acceptEncoding: String? = null,
    val range: String? = null,
    val ifModifiedSince: String? = null,
)

/**
 * A static file response (`serve.rs`, `StaticResponse`). [headers] are in the order Rack builds
 * them, with Rack's spelling ("Cache-Control" comes from the app's config, the rest are
 * lowercase); HTTP/1.1 and HTTP/2 treat them case-insensitively. The body is
 * `body[bodyOffset, bodyOffset + bodyLength)`, which shares the loaded file's bytes: callers must
 * not modify [body]. HEAD responses have an empty body. Full 200 responses are built once per file
 * and shared between requests.
 */
class Served(val status: Int, val headers: List<Pair<String, String>>, val body: ByteArray, val bodyOffset: Int = 0, val bodyLength: Int = body.size) {
    /** The first header named [name], compared case-insensitively. */
    fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    /** The body as its own array (a copy unless it's the whole of [body]). */
    fun bodyBytes(): ByteArray = if (bodyOffset == 0 && bodyLength == body.size) body else body.copyOfRange(bodyOffset, bodyOffset + bodyLength)
}

/**
 * ActionDispatch::Static (actionpack/lib/action_dispatch/middleware/static.rb) over Rack::Files,
 * serving reference/public plus the precompiled public/assets, with the headers from
 * reference/config/environments/production.rb (`public_file_server.headers`)
 * (`once-campfire-rust/crates/assets/src/serve.rs`).
 *
 * Every URL path a request can resolve to is looked up once, at load, along with its precompressed
 * siblings and the full 200 response for each outcome, so a plain GET or HEAD is a path clean-up,
 * a hash lookup and a shared response. Ranges build their response per request.
 */
internal object StaticFiles {
    /** The last `config.public_file_server.headers` assignment in production.rb wins. */
    private const val CACHE_CONTROL = "public, max-age=2592000"

    /** Rack::Files::MULTIPART_BOUNDARY */
    private const val MULTIPART_BOUNDARY = "AaB03x"

    private val UNSATISFIABLE = "Byte range unsatisfiable\n".toByteArray(Charsets.US_ASCII)
    private val EMPTY = ByteArray(0)
    private val NOT_MODIFIED = Served(304, emptyList(), EMPTY)

    /**
     * Rack::Mime.mime_type(ext, nil) for the extensions a Campfire deploy serves (and the other
     * common web ones); lookups are case-insensitive.
     */
    private val MIME_TYPES = mapOf(
        ".avif" to "image/avif", ".css" to "text/css", ".csv" to "text/csv", ".gif" to "image/gif",
        ".gz" to "application/x-gzip", ".htm" to "text/html", ".html" to "text/html", ".ico" to "image/vnd.microsoft.icon",
        ".jpeg" to "image/jpeg", ".jpg" to "image/jpeg", ".js" to "text/javascript", ".mjs" to "text/javascript",
        ".json" to "application/json", ".m4a" to "audio/mp4a-latm", ".mp3" to "audio/mpeg", ".mp4" to "video/mp4",
        ".ogg" to "application/ogg", ".otf" to "font/otf", ".pdf" to "application/pdf", ".png" to "image/png",
        ".svg" to "image/svg+xml", ".ttf" to "font/ttf", ".txt" to "text/plain", ".wav" to "audio/x-wav",
        ".webm" to "video/webm", ".webp" to "image/webp", ".woff" to "font/woff", ".woff2" to "font/woff2",
        ".xml" to "application/xml", ".zip" to "application/zip",
    )

    /** One way of answering a resolved path: the file sent, FileHandler's content headers, and the full GET and HEAD responses. */
    private class Outcome(val file: ByteArray, val contentHeaders: List<Pair<String, String>>) {
        val get: Served = build("GET", file, contentHeaders, null)
        val head: Served = build("HEAD", file, contentHeaders, null)
    }

    /**
     * FileHandler#try_files for one candidate path: the plain file and its `.br` and `.gz`
     * siblings (for compressible types only), each with the content headers it would be sent with.
     */
    private class Resolution(contentType: String, plain: ByteArray?, br: ByteArray?, gzip: ByteArray?) {
        private val br: Outcome?
        private val gzip: Outcome?
        private val plain: Outcome?

        init {
            val headers = ArrayList<Pair<String, String>>(4)
            headers.add("content-type" to contentType)
            if (br != null) headers.add("vary" to "accept-encoding")
            this.br = br?.let { Outcome(it, headers + ("content-encoding" to "br")) }
            if (gzip != null) headers.add("vary" to "accept-encoding")
            this.gzip = gzip?.let { Outcome(it, headers + ("content-encoding" to "gzip")) }
            this.plain = plain?.let { Outcome(it, headers.toList()) }
        }

        /** The outcome for a request's Accept-Encoding: br, then gzip, when present and accepted; else the plain file. */
        fun choose(acceptEncoding: String?): Outcome? {
            if (br == null && gzip == null) return plain
            val accept = acceptEncoding ?: ""
            if (br != null && accepts(accept, "br")) return br
            if (gzip != null && accepts(accept, "gzip")) return gzip
            return plain
        }
    }

    private val resolutions: Map<String, Resolution> = resolveAll(Embedded.files)

    /** FileHandler#attempt: the response for a GET or HEAD of a public file, or null (`serve.rs`, `serve`). */
    fun serve(request: StaticRequest): Served? {
        if (request.method != "GET" && request.method != "HEAD") return null
        val path = cleanPath(request.path) ?: return null
        val extname = fileExtname(path)
        val outcome = resolve(path, request.acceptEncoding)
            ?: if (mimeType(extname) == null && extname != ".html") {
                resolve("$path.html", request.acceptEncoding) ?: resolve("$path/index.html", request.acceptEncoding)
            } else {
                null
            }
        return serveFile(request, outcome ?: return null)
    }

    private fun resolve(path: String, acceptEncoding: String?): Outcome? = resolutions[path]?.choose(acceptEncoding)

    /**
     * Every path a candidate can name: each file's own path, and each `.br`/`.gz` file's base path
     * (which try_files serves compressed even when the plain file is missing).
     */
    private fun resolveAll(files: Map<String, ByteArray>): Map<String, Resolution> {
        val paths = LinkedHashSet<String>()
        for (url in files.keys) {
            paths.add(url)
            if (url.endsWith(".br")) paths.add(url.removeSuffix(".br"))
            if (url.endsWith(".gz")) paths.add(url.removeSuffix(".gz"))
        }
        val out = HashMap<String, Resolution>(paths.size * 2)
        for (path in paths) {
            val contentType = mimeType(fileExtname(path)) ?: "text/plain"
            out[path] = if (compressible(contentType)) {
                Resolution(contentType, files[path], files["$path.br"], files["$path.gz"])
            } else {
                Resolution(contentType, files[path], null, null)
            }
        }
        return out
    }

    /** Rack::Files#serving, then FileHandler#serve's `headers.update(content_headers)`; full responses are prebuilt. */
    private fun serveFile(request: StaticRequest, outcome: Outcome): Served {
        if (request.ifModifiedSince == Embedded.builtAt) return NOT_MODIFIED
        val ranges = request.range?.let { Rack.byteRanges(it, outcome.file.size.toLong()) }
        if (ranges == null) return if (request.method == "HEAD") outcome.head else outcome.get
        return build(request.method, outcome.file, outcome.contentHeaders, ranges)
    }

    /**
     * Rack::Files#serving's response for [ranges] (null for the whole file, empty for 416), with
     * the content headers merged in: a header already present is overwritten in place (so the
     * multipart content-type always becomes the file's), others are appended.
     */
    private fun build(method: String, file: ByteArray, contentHeaders: List<Pair<String, String>>, ranges: List<LongRange>?): Served {
        val size = file.size
        var headers = mutableListOf("last-modified" to Embedded.builtAt, "content-type" to "", "Cache-Control" to CACHE_CONTROL)
        var status = 200
        var body = file
        var offset = 0
        var length = size
        when {
            ranges == null -> {}
            ranges.isEmpty() -> {
                headers = mutableListOf(
                    "content-type" to "",
                    "content-length" to UNSATISFIABLE.size.toString(),
                    "x-cascade" to "pass",
                    "content-range" to "bytes */$size",
                )
                status = 416
                body = UNSATISFIABLE
                length = body.size
            }
            ranges.size == 1 -> {
                val range = ranges[0]
                headers.add("content-range" to "bytes ${range.first}-${range.last}/$size")
                status = 206
                offset = range.first.toInt()
                length = (range.last - range.first + 1).toInt()
            }
            else -> {
                body = multipart(file, contentHeaders[0].second, ranges)
                length = body.size
                headers[1] = "content-type" to "multipart/byteranges; boundary=$MULTIPART_BOUNDARY"
                status = 206
            }
        }
        if (status != 416) headers.add("content-length" to length.toString())
        for (header in contentHeaders) {
            val existing = headers.indexOfFirst { it.first == header.first }
            if (existing >= 0) headers[existing] = header else headers.add(header)
        }
        if (method == "HEAD") return Served(status, headers, EMPTY)
        return Served(status, headers, body, offset, length)
    }

    /** Rack::Files' multipart/byteranges body: each part's headers, then its bytes, then the closing boundary. */
    private fun multipart(file: ByteArray, contentType: String, ranges: List<LongRange>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (range in ranges) {
            val head = "\r\n--$MULTIPART_BOUNDARY\r\ncontent-type: $contentType\r\ncontent-range: bytes ${range.first}-${range.last}/${file.size}\r\n\r\n"
            out.write(head.toByteArray(Charsets.UTF_8))
            out.write(file, range.first.toInt(), (range.last - range.first + 1).toInt())
        }
        out.write("\r\n--$MULTIPART_BOUNDARY--\r\n".toByteArray(Charsets.UTF_8))
        return out.toByteArray()
    }

    /**
     * FileHandler#clean_path: chomp("/"), percent-decode, reject NUL, then
     * Rack::Utils.clean_path_info, on bytes as Rack does with a binary PATH_INFO; null when the
     * result isn't UTF-8 (no file has such a name). A path without `%` is cleaned as text.
     */
    fun cleanPath(pathInfo: String): String? {
        val chomped = pathInfo.removeSuffix("/")
        if ('%' !in chomped) {
            if ('\u0000' in chomped) return null
            return cleanSegments(chomped.split('/')).let { (leading, parts) -> (if (leading) "/" else "") + parts.joinToString("/") }
        }
        val bytes = unescapePath(chomped)
        if (bytes.contains(0)) return null
        val segments = ArrayList<String>()
        var start = 0
        for (i in 0..bytes.size) {
            if (i == bytes.size || bytes[i] == '/'.code.toByte()) {
                segments.add(String(bytes, start, i - start, Charsets.ISO_8859_1))
                start = i + 1
            }
        }
        val (leading, parts) = cleanSegments(segments)
        val cleaned = ((if (leading) "/" else "") + parts.joinToString("/")).toByteArray(Charsets.ISO_8859_1)
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(cleaned)).toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    /** Rack::Utils.clean_path_info over split segments: drops "" and ".", pops for ".."; whether to lead with "/". */
    private fun cleanSegments(parts: List<String>): Pair<Boolean, List<String>> {
        val clean = ArrayList<String>(parts.size)
        for (part in parts) {
            when (part) {
                "", "." -> {}
                ".." -> clean.removeLastOrNull()
                else -> clean.add(part)
            }
        }
        return (parts.isEmpty() || parts[0].isEmpty()) to clean
    }

    /** URI::RFC2396_Parser#unescape: %XX sequences become bytes, anything else is kept. */
    private fun unescapePath(path: String): ByteArray {
        val bytes = path.toByteArray(Charsets.UTF_8)
        val out = java.io.ByteArrayOutputStream(bytes.size)
        var i = 0
        while (i < bytes.size) {
            if (bytes[i] == '%'.code.toByte() && i + 2 < bytes.size) {
                val h = Character.digit(bytes[i + 1].toInt().toChar(), 16)
                val l = Character.digit(bytes[i + 2].toInt().toChar(), 16)
                if (h >= 0 && l >= 0 && bytes[i + 1] >= 0 && bytes[i + 2] >= 0) {
                    out.write(h * 16 + l)
                    i += 3
                    continue
                }
            }
            out.write(bytes[i].toInt())
            i++
        }
        return out.toByteArray()
    }

    /** FileHandler's compressible_content_types: `/\A(?:text\/|application\/javascript|image\/svg\+xml)/`. */
    private fun compressible(contentType: String) =
        contentType.startsWith("text/") || contentType.startsWith("application/javascript") || contentType.startsWith("image/svg+xml")

    /** `accept_encoding.any? { |enc, _| /\b#{encoding}\b/i.match?(enc) }` over Rack's parsed header. */
    fun accepts(acceptEncoding: String, encoding: String): Boolean {
        for (part in acceptEncoding.split(',')) {
            val value = asciiLowercase(part.substringBefore(';').trim())
            var at = value.indexOf(encoding)
            while (at >= 0) {
                val before = if (at > 0) value[at - 1] else null
                val after = value.getOrNull(at + encoding.length)
                if (!isWordChar(before) && !isWordChar(after)) return true
                at = value.indexOf(encoding, at + 1)
            }
        }
        return false
    }

    private fun isWordChar(c: Char?) = c != null && (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_')

    private fun asciiLowercase(s: String) = String(CharArray(s.length) { s[it].let { c -> if (c in 'A'..'Z') c + 32 else c } })

    /** File.extname. */
    private fun fileExtname(path: String): String {
        val trimmed = path.substringAfterLast('/').trimStart('.')
        val dot = trimmed.lastIndexOf('.')
        return if (dot < 0) "" else trimmed.substring(dot)
    }

    private fun mimeType(extname: String): String? = MIME_TYPES[asciiLowercase(extname)]
}
