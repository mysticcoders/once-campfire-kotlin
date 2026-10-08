package campfire.kit

/**
 * The body as read (`once-campfire-rust/crates/kit/src/body.rs`, `ParsedBody`): the raw bytes
 * (empty for multipart, whose parts become params) and the params parsed from them, or the
 * [ParamError] that parsing raised (a 400 when an action reads params).
 */
class ParsedBody(val raw: ByteArray, val params: ParamMap?, val error: ParamError?) {
    /** The params, throwing the parse error. */
    fun paramsOrThrow(): ParamMap = params ?: throw error!!

    companion object {
        fun empty(): ParsedBody = ParsedBody(Request.EMPTY, ParamMap(), null)
    }
}

/** The body was larger than allowed: a 413 (`body.rs`, `BodyError::TooLarge`). */
class BodyTooLarge : RuntimeException("request body too large", null, false, false) {
    val status: Int get() = 413
}

/**
 * Reading request bodies into params the way `Rack::Request#form_pairs` and
 * `ActionDispatch::Request#POST` do (`once-campfire-rust/crates/kit/src/body.rs`): JSON by content
 * type, urlencoded forms (also for a POST with no content type), and multipart, whose file parts
 * become [UploadedFile]s. The Rust port streams multipart through the `multer` crate and spools
 * files to disk; here the whole body is already in memory, so the parser walks it with multer's
 * rules (`Multipart::poll_next_field`, `StreamBuffer::read_field_data`).
 */
object BodyParser {
    /** `Rack::Utils.multipart_total_part_limit`. */
    const val MULTIPART_PART_LIMIT = 4096

    /**
     * The most of a non-multipart body read into memory (forms are limited to 4 MB after that, as
     * in Rack); more is a 413. Rails reads any size.
     */
    const val MAX_BUFFERED_BODY = 16 * 1024 * 1024

    /** `Rack::Utils.multipart_file_limit`. */
    const val MULTIPART_FILE_LIMIT = 128

    /**
     * The most a multipart body's text fields may hold together, since they're kept in memory
     * (Rack's `BUFFERED_UPLOAD_BYTESIZE_LIMIT`). More is a 413. Files aren't counted.
     */
    const val MULTIPART_TEXT_LIMIT = 16 * 1024 * 1024

    /** The largest multipart body, files included, when no smaller limit is configured (Rack's `PARSER_BYTESIZE_LIMIT`). */
    const val MULTIPART_BYTESIZE_LIMIT: Long = 10L * 1024 * 1024 * 1024

    private val MULTIPART_TYPES = setOf("multipart/form-data", "multipart/related", "multipart/mixed")

    /**
     * Parses [body]. [originalMethod] is the method on the wire (Rack's `form_data?` treats a
     * content-type-less POST as a form); [limit] is the configured body limit, if any. Throws
     * [BodyTooLarge].
     */
    fun parse(originalMethod: String, headers: Headers, body: ByteArray, limit: Int? = null): ParsedBody {
        val contentType = RequestInfo.headerStr(headers, "content-type")?.takeIf { it.isNotEmpty() }
        val media = RequestInfo.mediaType(contentType)

        if (media in MULTIPART_TYPES && contentType != null) {
            val boundary = parseBoundary(contentType)
            if (boundary != null) return parseMultipart(body, boundary, limit)
        }

        if (body.size > minOf(limit ?: Int.MAX_VALUE, MAX_BUFFERED_BODY)) throw BodyTooLarge()

        val isJson = try {
            Format.contentMimeType(contentType) == Mime.JSON
        } catch (_: InvalidMimeType) {
            false
        }
        return try {
            val params = if (isJson && body.isNotEmpty()) {
                Params.fromJsonBody(body)
            } else if (media == "application/x-www-form-urlencoded" ||
                (contentType == null && originalMethod == "POST") ||
                media in MULTIPART_TYPES
            ) {
                Params.fromPairs(Params.formPairs(body))
            } else {
                ParamMap()
            }
            ParsedBody(body, params, null)
        } catch (e: ParamError) {
            ParsedBody(body, null, e)
        }
    }

    /** Why reading a multipart body stopped early: too large (a 413), or a params error. */
    private class TooLarge : RuntimeException(null, null, false, false)

    private fun parseMultipart(body: ByteArray, boundary: String, limit: Int?): ParsedBody {
        val max = if (limit == null) MULTIPART_BYTESIZE_LIMIT else minOf(limit.toLong(), MULTIPART_BYTESIZE_LIMIT)
        if (body.size > max) throw BodyTooLarge()
        val pairs = ArrayList<RawPair>()
        var parts = 0
        var files = 0
        var text = 0L
        try {
            val reader = MultipartReader(body, boundary)
            while (true) {
                val headers = reader.nextField() ?: break
                parts++
                if (parts > MULTIPART_PART_LIMIT) throw ParamError.Limit("too many multipart parts")
                val part = Part.fromHeaders(headers)
                val filename = part.filename
                when {
                    filename == "" -> reader.fieldData()
                    filename != null -> {
                        files++
                        if (files > MULTIPART_FILE_LIMIT) throw ParamError.Limit("too many files")
                        val data = reader.fieldData()
                        val upload = UploadedFile(filename, part.contentType, part.head, data)
                        pairs.add(RawPair.file(part.name(), upload))
                    }
                    else -> {
                        val data = reader.fieldData { available ->
                            if (text + available > MULTIPART_TEXT_LIMIT) throw TooLarge()
                        }
                        text += data.size
                        if (text > MULTIPART_TEXT_LIMIT) throw TooLarge()
                        pairs.add(RawPair(part.name(), true, PairValue.Text(String(data, Charsets.UTF_8), isValidUtf8(data))))
                    }
                }
            }
        } catch (_: TooLarge) {
            throw BodyTooLarge()
        } catch (e: ParamError) {
            return ParsedBody(Request.EMPTY, null, e)
        }
        return try {
            ParsedBody(Request.EMPTY, Params.fromPairs(pairs), null)
        } catch (e: ParamError) {
            ParsedBody(Request.EMPTY, null, e)
        }
    }

    /**
     * `multer::parse_boundary`: the `boundary` parameter of a `multipart/form-data` content type,
     * read with the `mime` crate's strict grammar (tokens, optional quoted values, a space allowed
     * only after `;`). Null when the type is anything else or doesn't parse.
     */
    internal fun parseBoundary(contentType: String): String? {
        val s = contentType
        var i = 0
        while (i < s.length && isToken(s[i])) i++
        if (i == 0 || i >= s.length || s[i] != '/') return null
        val slash = i
        i++
        val subStart = i
        while (i < s.length && s[i] != ';') {
            if (!isToken(s[i])) return null
            i++
        }
        if (i == subStart && i < s.length) return null
        val type = asciiLowercase(s.substring(0, slash))
        val subtype = asciiLowercase(s.substring(subStart, i))
        var boundary: String? = null
        var start = i + 1
        i = start
        while (start < s.length) {
            if (s[start] == ' ') {
                start++
                i = start
                continue
            }
            while (i < s.length && isToken(s[i])) i++
            if (i >= s.length || s[i] != '=' || i == start) return null
            val name = asciiLowercase(s.substring(start, i))
            i++
            start = i
            val value: String
            if (i < s.length && s[i] == '"') {
                i++
                start = i
                while (true) {
                    if (i >= s.length) return null
                    val c = s[i]
                    if (c == '"' && i > start) break
                    if (c.code <= 31 || c.code == 127) return null
                    i++
                }
                value = s.substring(start, i)
                i++
                while (i < s.length && s[i] == ' ') i++
                if (i < s.length && s[i] != ';') return null
                i++
                start = i
            } else {
                while (i < s.length && s[i] != ';') {
                    if (!isToken(s[i])) return null
                    i++
                }
                if (i < s.length && i == start) return null
                value = s.substring(start, i)
                i++
                start = i
            }
            if (name == "boundary" && boundary == null) boundary = value
        }
        if (type != "multipart" || subtype != "form-data") return null
        return boundary
    }

    /** The `mime` crate's token characters. */
    private fun isToken(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || "!#$%&'*+-.^_`|~".indexOf(c) >= 0

    /**
     * Walks a multipart body as multer does: skip to the first `--boundary`, then for each part
     * the boundary, optional transport padding, CRLF, headers up to a blank line, and data up to
     * `CRLF--boundary`; `--` after a boundary ends the body. Anything else is [ParamError.Parse].
     */
    private class MultipartReader(private val body: ByteArray, boundary: String) {
        private val dashBoundary = ("--$boundary").toByteArray(Charsets.UTF_8)
        private val delimiter = ("\r\n--$boundary").toByteArray(Charsets.UTF_8)
        private var pos = 0
        private var started = false
        private var done = false

        /** The next part's headers (lowercased names, last of a repeated name winning), or null after the closing boundary. */
        fun nextField(): List<Pair<String, ByteArray>>? {
            if (done) return null
            if (!started) {
                pos = indexOf(body, dashBoundary, 0).takeIf { it >= 0 } ?: throw ParamError.Parse()
                started = true
            }
            if (!matchesAt(dashBoundary, pos)) throw ParamError.Parse()
            pos += dashBoundary.size
            if (pos + 2 > body.size) throw ParamError.Parse()
            if (body[pos] == '-'.code.toByte() && body[pos + 1] == '-'.code.toByte()) {
                done = true
                return null
            }
            while (pos < body.size && (body[pos] == ' '.code.toByte() || body[pos] == '\t'.code.toByte())) pos++
            if (pos >= body.size) throw ParamError.Parse()
            if (pos + 2 > body.size || body[pos] != '\r'.code.toByte() || body[pos + 1] != '\n'.code.toByte()) throw ParamError.Parse()
            pos += 2
            val end = indexOf(body, CRLF_CRLF, pos).takeIf { it >= 0 } ?: throw ParamError.Parse()
            val headers = parseHeaders(body, pos, end + 4)
            pos = end + 4
            return headers
        }

        /**
         * The current part's data, up to the next delimiter. [check] sees how much is left when
         * there's no delimiter, before that's a [ParamError.Parse] (multer hands out what it has
         * before finding the stream incomplete).
         */
        fun fieldData(check: (Long) -> Unit = {}): ByteArray {
            val at = indexOf(body, delimiter, pos)
            if (at < 0) {
                check((body.size - pos).toLong())
                throw ParamError.Parse()
            }
            val data = body.copyOfRange(pos, at)
            pos = at + 2
            return data
        }

        private fun matchesAt(pattern: ByteArray, at: Int): Boolean {
            if (at + pattern.size > body.size) return false
            for (i in pattern.indices) if (body[at + i] != pattern[i]) return false
            return true
        }
    }

    private val CRLF_CRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())

    /** The first index of [pattern] in [haystack] at or after [from], or -1. */
    private fun indexOf(haystack: ByteArray, pattern: ByteArray, from: Int): Int {
        if (pattern.isEmpty()) return from
        val first = pattern[0]
        val last = haystack.size - pattern.size
        var i = from
        outer@ while (i <= last) {
            if (haystack[i] != first) {
                i++
                continue
            }
            for (k in 1 until pattern.size) {
                if (haystack[i + k] != pattern[k]) {
                    i++
                    continue@outer
                }
            }
            return i
        }
        return -1
    }

    /** multer's `MAX_HEADERS`. */
    private const val MAX_HEADERS = 32

    /**
     * `httparse::parse_headers` over `bytes[from, to)` (which ends with a blank line), then
     * multer's conversion into a `HeaderMap`: names are tokens, lowercased; values are visible
     * ASCII, spaces, tabs or obs-text, trailing whitespace trimmed; a repeated name keeps its
     * first position and its last value. More than 32 headers, or anything malformed, is
     * [ParamError.Parse].
     */
    private fun parseHeaders(bytes: ByteArray, from: Int, to: Int): List<Pair<String, ByteArray>> {
        val headers = ArrayList<Pair<String, ByteArray>>(4)
        var count = 0
        var i = from
        while (true) {
            if (i >= to) throw ParamError.Parse()
            if (bytes[i] == '\r'.code.toByte()) {
                if (i + 1 < to && bytes[i + 1] == '\n'.code.toByte()) return headers
                throw ParamError.Parse()
            }
            if (bytes[i] == '\n'.code.toByte()) return headers
            val nameStart = i
            while (i < to && bytes[i] != ':'.code.toByte()) {
                if (!isToken((bytes[i].toInt() and 0xff).toChar())) throw ParamError.Parse()
                i++
            }
            if (i == nameStart || i >= to) throw ParamError.Parse()
            val name = asciiLowercase(String(bytes, nameStart, i - nameStart, Charsets.ISO_8859_1))
            i++
            while (i < to && (bytes[i] == ' '.code.toByte() || bytes[i] == '\t'.code.toByte())) i++
            val valueStart = i
            while (i < to && bytes[i] != '\r'.code.toByte() && bytes[i] != '\n'.code.toByte()) {
                val b = bytes[i].toInt() and 0xff
                if (b != '\t'.code && (b < 0x20 || b == 0x7f)) throw ParamError.Parse()
                i++
            }
            var valueEnd = i
            while (valueEnd > valueStart && (bytes[valueEnd - 1] == ' '.code.toByte() || bytes[valueEnd - 1] == '\t'.code.toByte())) valueEnd--
            if (i >= to) throw ParamError.Parse()
            if (bytes[i] == '\r'.code.toByte()) {
                if (i + 1 >= to || bytes[i + 1] != '\n'.code.toByte()) throw ParamError.Parse()
                i += 2
            } else {
                i++
            }
            count++
            if (count > MAX_HEADERS) throw ParamError.Parse()
            val value = bytes.copyOfRange(valueStart, valueEnd)
            val existing = headers.indexOfFirst { it.first == name }
            if (existing >= 0) headers[existing] = name to value else headers.add(name to value)
        }
    }

    /**
     * A multipart part's `Content-Disposition` and `Content-Type`, parsed like
     * `Rack::Multipart::Parser#consume_boundary` (`body.rs`, `Part`).
     */
    internal class Part(
        var name: String? = null,
        var filename: String? = null,
        var contentType: String? = null,
        var head: String = "",
    ) {
        /** Rack names nameless parts after the file, or `"<content type>[]"`. */
        fun name(): String {
            val n = name
            if (!n.isNullOrEmpty()) return n
            return filename ?: "${contentType ?: "text/plain"}[]"
        }

        companion object {
            fun fromHeaders(headers: List<Pair<String, ByteArray>>): Part {
                val head = StringBuilder()
                for ((k, v) in headers) head.append(k).append(": ").append(String(v, Charsets.UTF_8)).append("\r\n")
                fun header(name: String): String? = headers.firstOrNull { it.first == name }?.second?.let { String(it, Charsets.UTF_8) }
                val disposition = header("content-disposition")
                val part = if (disposition != null) parseDisposition(disposition) else Part(name = header("content-id"))
                part.contentType = header("content-type")
                part.head = head.toString()
                return part
            }
        }
    }

    /** `Content-Disposition`'s `name`, `filename` and `filename*` parameters (`body.rs`, `parse_disposition`). */
    internal fun parseDisposition(value: String): Part {
        val part = Part()
        var filenameStar: String? = null
        val semi = value.indexOf(';')
        var rest = if (semi < 0) "" else value.substring(semi + 1)
        while (true) {
            val eq = rest.indexOf('=')
            if (eq < 0) break
            val param = asciiLowercase(rustTrim(rest.substring(0, eq)))
            rest = rest.substring(eq + 1)
            val parsed: String
            if (rest.startsWith('"')) {
                val quoted = rest.substring(1)
                val out = StringBuilder()
                var end = quoted.length
                var i = 0
                while (i < quoted.length) {
                    val c = quoted[i]
                    if (c == '"') {
                        end = i + 1
                        break
                    }
                    if (c == '\\') {
                        i++
                        if (i < quoted.length) {
                            val escaped = quoted[i]
                            if (escaped == '"') {
                                out.append('"')
                            } else if (param == "filename") {
                                out.append('\\').append(escaped)
                            } else {
                                out.append(escaped)
                            }
                        }
                        i++
                        continue
                    }
                    out.append(c)
                    i++
                }
                parsed = out.toString()
                val after = quoted.substring(minOf(end, quoted.length))
                val next = after.indexOf(';')
                rest = if (next < 0) "" else after.substring(next + 1)
            } else {
                val next = rest.indexOf(';')
                parsed = rustTrim(if (next < 0) rest else rest.substring(0, next))
                rest = if (next < 0) "" else rest.substring(next + 1)
            }
            when (param) {
                "name" -> part.name = parsed
                "filename" -> part.filename = parsed
                "filename*" -> filenameStar = parsed
            }
        }
        if (filenameStar != null) {
            val parts = filenameStar.split('\'', limit = 3)
            part.filename = normalizeFilename(parts.getOrNull(2) ?: "")
        } else if (part.filename != null) {
            part.filename = normalizeFilename(part.filename!!)
        }
        return part
    }

    /**
     * `Rack::Multipart::Parser#normalize_filename`: unescape when every `%` is a valid escape,
     * then keep only the basename (browsers on Windows send full paths).
     */
    internal fun normalizeFilename(filename: String): String {
        val bytes = filename.toByteArray(Charsets.UTF_8)
        var allValid = true
        for (i in bytes.indices) {
            if (bytes[i] == '%'.code.toByte() && !(i + 2 < bytes.size && isHex(bytes[i + 1]) && isHex(bytes[i + 2]))) {
                allValid = false
                break
            }
        }
        val unescaped = if (allValid) String(percentDecode(bytes), Charsets.UTF_8) else filename
        if (unescaped.isEmpty()) return unescaped
        val cut = maxOf(unescaped.lastIndexOf('/'), unescaped.lastIndexOf('\\'))
        return unescaped.substring(cut + 1)
    }

    private fun isHex(b: Byte): Boolean {
        val c = (b.toInt() and 0xff).toChar()
        return c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
    }

    private fun percentDecode(bytes: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(bytes.size)
        var i = 0
        while (i < bytes.size) {
            if (bytes[i] == '%'.code.toByte() && i + 2 < bytes.size && isHex(bytes[i + 1]) && isHex(bytes[i + 2])) {
                out.write(Character.digit(bytes[i + 1].toInt(), 16) * 16 + Character.digit(bytes[i + 2].toInt(), 16))
                i += 3
            } else {
                out.write(bytes[i].toInt())
                i++
            }
        }
        return out.toByteArray()
    }
}
