package campfire.kit

/**
 * A registered MIME type (`Mime[:html]` and friends; `once-campfire-rust/crates/kit/src/format.rs`,
 * `Mime`). Equal by symbol; the registered ones are singletons in the companion.
 */
class Mime private constructor(
    val symbol: String,
    val string: String,
    val synonyms: List<String>,
    val extensions: List<String>,
) {
    fun isSymbol(symbol: String): Boolean = this.symbol == symbol

    /** `Mime::Type#match?`: [pattern] appears in the type or one of its synonyms. */
    internal fun matches(pattern: String): Boolean = string.contains(pattern) || synonyms.any { it.contains(pattern) }

    override fun equals(other: Any?): Boolean = other is Mime && other.symbol == symbol

    override fun hashCode(): Int = symbol.hashCode()

    override fun toString(): String = string

    /**
     * `action_dispatch/http/mime_types.rb`, then turbo-rails' `:turbo_stream`, in registration
     * order (the order matters for `text/\*` expansion).
     */
    companion object {
        private fun mime(symbol: String, string: String, synonyms: List<String> = emptyList(), extensions: List<String> = emptyList()) =
            Mime(symbol, string, synonyms, extensions)

        val HTML = mime("html", "text/html", listOf("application/xhtml+xml"), listOf("xhtml"))
        val TEXT = mime("text", "text/plain", extensions = listOf("txt"))
        val JS = mime("js", "text/javascript", listOf("application/javascript", "application/x-javascript"))
        val CSS = mime("css", "text/css")
        val ICS = mime("ics", "text/calendar")
        val CSV = mime("csv", "text/csv")
        val VCF = mime("vcf", "text/vcard")
        val VTT = mime("vtt", "text/vtt", extensions = listOf("vtt"))
        val MD = mime("md", "text/markdown", extensions = listOf("md", "markdown"))
        val PNG = mime("png", "image/png", extensions = listOf("png"))
        val JPEG = mime("jpeg", "image/jpeg", extensions = listOf("jpg", "jpeg", "jpe", "pjpeg"))
        val GIF = mime("gif", "image/gif", extensions = listOf("gif"))
        val BMP = mime("bmp", "image/bmp", extensions = listOf("bmp"))
        val TIFF = mime("tiff", "image/tiff", extensions = listOf("tif", "tiff"))
        val SVG = mime("svg", "image/svg+xml")
        val WEBP = mime("webp", "image/webp", extensions = listOf("webp"))
        val MPEG = mime("mpeg", "video/mpeg", extensions = listOf("mpg", "mpeg", "mpe"))
        val MP3 = mime("mp3", "audio/mpeg", extensions = listOf("mp1", "mp2", "mp3"))
        val OGG = mime("ogg", "audio/ogg", extensions = listOf("oga", "ogg", "spx", "opus"))
        val M4A = mime("m4a", "audio/aac", listOf("audio/mp4"), listOf("m4a", "mpg4", "aac"))
        val WEBM = mime("webm", "video/webm", extensions = listOf("webm"))
        val MP4 = mime("mp4", "video/mp4", extensions = listOf("mp4", "m4v"))
        val OTF = mime("otf", "font/otf", extensions = listOf("otf"))
        val TTF = mime("ttf", "font/ttf", extensions = listOf("ttf"))
        val WOFF = mime("woff", "font/woff", extensions = listOf("woff"))
        val WOFF2 = mime("woff2", "font/woff2", extensions = listOf("woff2"))
        val XML = mime("xml", "application/xml", listOf("text/xml", "application/x-xml"))
        val RSS = mime("rss", "application/rss+xml")
        val ATOM = mime("atom", "application/atom+xml")
        val YAML = mime("yaml", "application/x-yaml", listOf("text/yaml"), listOf("yml", "yaml"))
        val MULTIPART_FORM = mime("multipart_form", "multipart/form-data")
        val URL_ENCODED_FORM = mime("url_encoded_form", "application/x-www-form-urlencoded")
        val JSON = mime("json", "application/json", listOf("text/x-json", "application/jsonrequest", "application/problem+json"))
        val PDF = mime("pdf", "application/pdf", extensions = listOf("pdf"))
        val ZIP = mime("zip", "application/zip", extensions = listOf("zip"))
        val GZIP = mime("gzip", "application/gzip", listOf("application/x-gzip"), listOf("gz"))
        val TURBO_STREAM = mime("turbo_stream", "text/vnd.turbo-stream.html")

        /** `Mime::ALL`: the all-types wildcard (star-slash-star), only meaningful in negotiation. */
        val ALL = mime("*/*", "*/*")

        val REGISTERED: List<Mime> = listOf(
            HTML, TEXT, JS, CSS, ICS, CSV, VCF, VTT, MD, PNG, JPEG, GIF, BMP, TIFF, SVG, WEBP, MPEG, MP3, OGG, M4A, WEBM,
            MP4, OTF, TTF, WOFF, WOFF2, XML, RSS, ATOM, YAML, MULTIPART_FORM, URL_ENCODED_FORM, JSON, PDF, ZIP, GZIP, TURBO_STREAM,
        )
    }
}

/** `Mime::Type::InvalidMimeType` (a 400 in Rails' rescue responses), raised by [Format.lookup]. */
class InvalidMimeType(val type: String) : RuntimeException("\"$type\" is not a valid MIME type", null, false, false)

/**
 * Everything `MimeNegotiation#formats` looks at (`format.rs`, `NegotiationInput`).
 * [formatParam] is `params[:format]` (a path extension captured by the router, or `?format=`).
 */
data class NegotiationInput(
    val formatParam: String? = null,
    val accept: String? = null,
    val contentType: String? = null,
    val path: String = "/",
    val xhr: Boolean = false,
)

/**
 * MIME types and format negotiation (`once-campfire-rust/crates/kit/src/format.rs`):
 * `Mime::Type.lookup`, `Mime::Type.parse` (the `Accept` header),
 * `ActionDispatch::Http::MimeNegotiation#formats` and `respond_to`'s `negotiate_mime`. Lookups go
 * through tables built once, in registration order, so the first registered match wins as in the
 * Rust port's linear scans.
 */
object Format {
    private val byExtension: Map<String, Mime> = HashMap<String, Mime>().also { map ->
        for (m in Mime.REGISTERED) {
            map.putIfAbsent(m.symbol, m)
            for (ext in m.extensions) map.putIfAbsent(ext, m)
        }
    }

    private val byString: Map<String, Mime> = HashMap<String, Mime>().also { map ->
        for (m in Mime.REGISTERED) {
            map.putIfAbsent(m.string, m)
            for (syn in m.synonyms) map.putIfAbsent(syn, m)
        }
    }

    /** `Mime[ext]` / `Mime::Type.lookup_by_extension`. */
    fun lookupByExtension(extension: String): Mime? = byExtension[extension]

    /**
     * `Mime::Type.lookup`: exact type string or synonym, else the part before `;`. Null is a
     * valid but unregistered type; an invalid one throws [InvalidMimeType].
     */
    fun lookup(string: String): Mime? {
        byString[string]?.let { return it }
        val base = baseType(string)
        byString[base]?.let { return it }
        if (base == "*/*") return Mime.ALL
        if (validMimeType(string)) return null
        throw InvalidMimeType(string)
    }

    /** The part of [string] before the first `;`, trailing whitespace trimmed. */
    private fun baseType(string: String): String {
        val semi = string.indexOf(';')
        return rustTrimEnd(if (semi < 0) string else string.substring(0, semi))
    }

    /** `Mime::Type::MIME_REGEXP`, loosely: `type/subtype` with name characters, optional params. */
    private fun validMimeType(string: String): Boolean {
        val base = baseType(string)
        if (base == "*/*") return true
        val slash = base.indexOf('/')
        if (slash < 0) return false
        val sub = base.substring(slash + 1)
        return isMimeName(base.substring(0, slash)) && (sub == "*" || isMimeName(sub))
    }

    private fun isMimeName(s: String): Boolean {
        if (s.isEmpty() || s.length > 127 || !isAsciiAlphanumeric(s[0])) return false
        for (c in s) if (!isAsciiAlphanumeric(c) && "!#$&-^_.+".indexOf(c) < 0) return false
        return true
    }

    private fun isAsciiAlphanumeric(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'

    /**
     * One `Accept` entry: [q] is `(q.to_f * 100).to_i`, kept in a double so that q-values past
     * `Long` still order as Ruby's Integers do. An infinite one, where Rails raises
     * FloatDomainError, sorts first (or last).
     */
    private class AcceptItem(val index: Int, var name: String, var q: Double)

    /**
     * `Mime::Type.parse(accept_header)`, keeping only registered types and the wildcard (the
     * `formats.select!` that follows in `MimeNegotiation#formats`).
     */
    fun parseAccept(header: String): List<Mime> {
        if (header.indexOf(',') < 0) {
            val sep = findQSeparator(header, 0)
            val single = if (sep != null) rustTrim(header.substring(0, sep.first)) else header
            if (rustTrim(single).isEmpty()) return emptyList()
            trailingStar(single)?.let { return it }
            return listOfNotNull(lookup(single))
        }

        val list = ArrayList<AcceptItem>()
        var index = 0
        for (item in scanAcceptItems(header)) {
            val fields = rubySplitOnQ(item)
            if (fields.isEmpty()) continue
            val q = fields.getOrNull(1)
            val params = rustTrim(fields[0])
            if (params.isEmpty()) continue
            val expanded = trailingStar(params)
            val names = expanded?.map { it.string } ?: listOf(params)
            for (name in names) {
                val value = when {
                    q != null -> campfire.ruby.toF(q)
                    name == "*/*" -> 0.0
                    else -> 1.0
                }
                list.add(AcceptItem(index, name, truncate(value * 100.0)))
                index++
            }
        }
        list.sortWith { a, b ->
            when {
                a.q == b.q -> a.index.compareTo(b.index)
                a.q > b.q -> -1
                else -> 1
            }
        }
        sortXml(list)

        val formats = ArrayList<Mime>(list.size)
        for (item in list) {
            val mime = lookup(item.name)
            if (mime != null && mime !in formats) formats.add(mime)
        }
        return formats
    }

    private fun truncate(d: Double): Double = if (d.isInfinite() || d.isNaN()) d else kotlin.math.truncate(d)

    /**
     * `Mime::Type::AcceptList.sort!`'s XML juggling: `text/xml` folds into `application/xml`,
     * which then yields to any `+xml` type of the same quality listed after it.
     */
    private fun sortXml(list: MutableList<AcceptItem>) {
        val textXml = list.indexOfFirst { it.name == "text/xml" }
        var appXml = list.indexOfFirst { it.name == "application/xml" }
        if (textXml >= 0 && appXml >= 0) {
            var textIdx = textXml
            var appIdx = appXml
            list[appIdx].q = maxOf(list[appIdx].q, list[textIdx].q)
            if (appIdx > textIdx) {
                java.util.Collections.swap(list, appIdx, textIdx)
                val t = appIdx
                appIdx = textIdx
                textIdx = t
            }
            list.removeAt(textIdx)
            appXml = appIdx
        } else if (textXml >= 0) {
            list[textXml].name = "application/xml"
        }
        if (appXml >= 0) {
            var appIdx = appXml
            val appQ = list[appIdx].q
            var idx = appIdx
            while (idx < list.size) {
                if (list[idx].q < appQ) break
                if (list[idx].name.endsWith("+xml")) {
                    java.util.Collections.swap(list, appIdx, idx)
                    appIdx = idx
                }
                idx++
            }
        }
    }

    /** `PARAMETER_SEPARATOR_REGEXP = /;\s*q="?/`: where the first one at or after [from] starts and ends. */
    private fun findQSeparator(s: String, from: Int): Pair<Int, Int>? {
        var i = s.indexOf(';', from)
        while (i >= 0) {
            var j = i + 1
            while (j < s.length && isAsciiWhitespace(s[j])) j++
            if (j + 1 < s.length && s[j] == 'q' && s[j + 1] == '=') {
                return i to (if (j + 2 < s.length && s[j + 2] == '"') j + 3 else j + 2)
            }
            i = s.indexOf(';', i + 1)
        }
        return null
    }

    /**
     * `String#split(PARAMETER_SEPARATOR_REGEXP)`. It drops trailing empty fields, so a `q=` with
     * nothing after it leaves `q` nil (1.0), not "" (0.0).
     */
    private fun rubySplitOnQ(s: String): List<String> {
        val fields = ArrayList<String>(2)
        var rest = 0
        while (true) {
            val sep = findQSeparator(s, rest) ?: break
            fields.add(s.substring(rest, sep.first))
            rest = sep.second
        }
        fields.add(s.substring(rest))
        while (fields.isNotEmpty() && fields.last().isEmpty()) fields.removeAt(fields.size - 1)
        return fields
    }

    /**
     * `ACCEPT_HEADER_REGEXP`: the header's items, each starting at a character other than a comma,
     * whitespace or quote, and running to the next comma outside quotes.
     */
    private fun scanAcceptItems(header: String): List<String> {
        val items = ArrayList<String>()
        var i = 0
        while (i < header.length) {
            val c = header[i]
            if (c == ',' || isAsciiWhitespace(c) || c == '"') {
                i++
                continue
            }
            val start = i
            i++
            while (i < header.length && header[i] != ',') {
                if (header[i] == '"') {
                    val close = header.indexOf('"', i + 1)
                    if (close < 0) break
                    i = close + 1
                } else {
                    i++
                }
            }
            items.add(header.substring(start, i))
        }
        return items
    }

    /**
     * `TRAILING_STAR_REGEXP` (`text/\*` or `application/\*`): every registered type matching `text/`
     * or `application/`, in registration order.
     */
    private fun trailingStar(accept: String): List<Mime>? {
        val prefix = when {
            accept.startsWith("text/*") -> "text/"
            accept.startsWith("application/*") -> "application/"
            else -> return null
        }
        return Mime.REGISTERED.filter { it.matches(prefix) }
    }

    /** `request.formats`. Throws [InvalidMimeType] for an `Accept` or `Content-Type` Rails can't parse. */
    fun formats(input: NegotiationInput): List<Mime> {
        if (input.formatParam != null) return listOfNotNull(lookupByExtension(input.formatParam))
        if (validAcceptHeader(input)) {
            val accept = rustTrim(input.accept ?: "")
            return if (accept.isEmpty()) listOfNotNull(contentMimeType(input.contentType)) else parseAccept(accept)
        }
        formatFromPathExtension(input.path)?.let { return listOf(it) }
        return listOf(if (input.xhr) Mime.JS else Mime.HTML)
    }

    /** `request.content_mime_type`. */
    fun contentMimeType(contentType: String?): Mime? {
        if (contentType == null) return null
        var end = 0
        while (end < contentType.length && contentType[end] != ',' && contentType[end] != ';') end++
        val base = asciiLowercase(rustTrim(contentType.substring(0, end)))
        return if (base.isEmpty()) null else lookup(base)
    }

    /**
     * `request.should_apply_vary_header?`: `!params_readable? && use_accept_header &&
     * valid_accept_header`, i.e. the format came from the `Accept` header, not a format param.
     */
    fun shouldApplyVaryHeader(input: NegotiationInput): Boolean = input.formatParam == null && validAcceptHeader(input)

    private fun validAcceptHeader(input: NegotiationInput): Boolean {
        val accept = input.accept ?: ""
        val present = rustTrim(accept).isNotEmpty()
        return (input.xhr && (present || !input.contentType.isNullOrEmpty())) || (present && !browserLike(accept))
    }

    /**
     * `BROWSER_LIKE_ACCEPTS = /,\s*\*\/\*|\*\/\*\s*,/`, as the Rust port reads it: the header with
     * all whitespace removed contains a comma right before or after the wildcard. Scans without building that string.
     */
    private fun browserLike(accept: String): Boolean {
        var a = '\u0000'
        var b = '\u0000'
        var c = '\u0000'
        var seen = 0
        for (ch in accept) {
            if (isRustWhitespace(ch)) continue
            if (seen >= 3) {
                if (a == ',' && b == '*' && c == '/' && ch == '*') return true
                if (a == '*' && b == '/' && c == '*' && ch == ',') return true
            }
            a = b
            b = c
            c = ch
            seen++
        }
        return false
    }

    private fun formatFromPathExtension(path: String): Mime? {
        val dot = path.lastIndexOf('.')
        if (dot < 0 || dot == path.length - 1) return null
        for (i in dot + 1 until path.length) {
            val c = path[i]
            if (!isAsciiAlphanumeric(c) && c != '_') return null
        }
        return lookupByExtension(path.substring(dot + 1))
    }

    /** `request.negotiate_mime(order)`: the first acceptable format [order] offers. */
    fun negotiate(formats: List<Mime>, order: List<Mime>): Mime? {
        for (priority in formats) {
            if (priority == Mime.ALL) return order.firstOrNull()
            if (priority in order) return priority
        }
        return if (Mime.ALL in order) formats.firstOrNull() else null
    }
}
