package campfire.kit.front

import campfire.kit.Ascii
import campfire.kit.Body
import campfire.kit.ChunkedBody
import campfire.kit.Headers
import campfire.kit.Request
import campfire.kit.Response
import com.github.luben.zstd.ZstdOutputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.CRC32C
import java.util.zip.Deflater

/** `gzhttp.HeaderNoCompression`: set by the guard to veto compression, never sent. */
internal const val NO_COMPRESSION = "no-gzip-compression"
private const val MIN_SIZE = 1024
private const val GZIP_LEVEL = 6

/** `zstd.SpeedFastest` */
private const val ZSTD_LEVEL = 1

/** `RandomJitter(n, 0, false)`: jitter is derived from (at most) the first 64 KB of the body. */
private const val JITTER_BUFFER = 64 shl 10

/** net/http's `bufferBeforeChunkingSize`. */
private const val GO_CHUNKING_BUFFER = 2048

/** The content coding `gzhttp` picked for a request. */
enum class Encoding { NONE, GZIP, ZSTD }

/**
 * How the response's own headers were combined with the `Vary: Accept-Encoding` gzhttp set before
 * calling on: Thruster's cache copies stored or recorded headers over it (a `Vary` of the
 * response's replaces it), while bypassed requests add theirs to it (so `Vary` repeats).
 */
enum class HeaderMerge { REPLACE, APPEND }

/** What `gzhttp` decided about a request before calling on. */
class Negotiation(val encoding: Encoding, val userSpecificRequest: Boolean)

/**
 * Thruster's compression (`internal/compression_handler.go`, `compression_guard_handler.go`), as
 * `crates/kit/src/front/compression.rs` ports it: klauspost/compress's `gzhttp` with a 1 KB minimum
 * size, gzip level 6, zstd (preferred over gzip at equal quality) at its fastest level, and
 * [jitter] bytes of BREACH padding (a gzip comment, or a zstd skippable frame).
 *
 * The app's `Rack::Deflater` ([campfire.kit.RackDeflater]) already gzips nearly every response for
 * clients that accept gzip, and `gzhttp` leaves encoded responses alone; what this adds is
 * `Vary: Accept-Encoding` on every response, and compression of what the app sent unencoded.
 *
 * Bodies here are whole, so the Rust port's buffering decides only how the result is framed: a
 * body at least as long as the 1 KB minimum (64 KB with jitter) that Rust would have streamed, or
 * a compressed one over Go's 2 KB chunking buffer, is marked [ChunkedBody].
 */
class Compression(jitter: Long, private val disableOnAuth: Boolean) {
    /** `strings.Repeat("Padding-", 1+(n/8))[:n+1]`, or empty without jitter. */
    private val jitter: String = if (jitter > 0) "Padding-".repeat(1 + (jitter / 8).toInt()).substring(0, jitter.toInt() + 1) else ""

    /** The encoding [request] gets and whether the auth guard applies, decided before calling on. */
    fun negotiate(request: Request): Negotiation = Negotiation(
        selectEncoding(request.method, request.headers["accept-encoding"]),
        disableOnAuth && hasUserSpecificRequestHeaders(request.headers),
    )

    /** Adds `Vary` and compresses [response] when [negotiation], its headers and its body allow. */
    fun apply(negotiation: Negotiation, response: Response, merge: HeaderMerge): Response {
        val headers = response.headers
        addVary(headers, merge)
        if (response.status in 100..199) return response
        val guarded = negotiation.userSpecificRequest || (disableOnAuth && hasUserSpecificResponseHeaders(headers))
        val vetoed = guarded || NO_COMPRESSION in headers
        headers.remove(NO_COMPRESSION)
        if (negotiation.encoding == Encoding.NONE || vetoed || !mayCompress(headers)) return response

        val body = response.body
        val total = body.length
        val want = if (jitter.isEmpty()) MIN_SIZE else maxOf(JITTER_BUFFER, MIN_SIZE)
        val ended = total < want
        val hadLength = response.extension<ChunkedBody>() == null
        val bodyAllowed = response.status != 204 && response.status != 304
        var contentType = headers["content-type"] ?: ""
        if (contentType.isEmpty() && bodyAllowed && total > 0) {
            contentType = detectContentType(prefix(body, 512))
            if ("content-type" !in headers) headers["content-type"] = contentType
        }
        if (!(total >= MIN_SIZE && contentTypeFilter(contentType))) {
            if (!(ended && hadLength)) response.putExtension(ChunkedBody)
            return response
        }

        val encoding = negotiation.encoding
        headers["content-encoding"] = if (encoding == Encoding.GZIP) "gzip" else "zstd"
        headers.remove("content-length")
        headers.remove("accept-ranges")
        val chunks = body.chunks()
        val padding = jitterFor(chunks)
        val compressed = if (encoding == Encoding.GZIP) gzip(chunks, padding) else zstd(chunks, padding)
        response.body = Body.Bytes(compressed)
        if (ended && compressed.size <= GO_CHUNKING_BUFFER) {
            response.extensions.remove(ChunkedBody::class.java)
        } else {
            response.putExtension(ChunkedBody)
        }
        return response
    }

    /**
     * `startCompression`'s jitter: a prefix of the padding whose length (1..n) comes from the
     * CRC-32C of (at most) the first 64 KB of the body.
     */
    internal fun jitterFor(chunks: List<ByteArray>): String? {
        if (jitter.isEmpty()) return null
        val crc = CRC32C()
        var left = JITTER_BUFFER
        for (chunk in chunks) {
            if (left == 0) break
            val n = minOf(left, chunk.size)
            crc.update(chunk, 0, n)
            left -= n
        }
        val rng = Integer.rotateLeft(crc.value.toInt(), 19) xor 0xab0755de.toInt()
        val len = 1 + Integer.remainderUnsigned(rng, jitter.length - 1)
        return jitter.substring(0, len)
    }

    internal companion object {
        private val DEFLATERS: ThreadLocal<Deflater> = ThreadLocal.withInitial { Deflater(GZIP_LEVEL, true) }

        /** `gzhttp` adds `Vary: Accept-Encoding` to every response before the handler runs. */
        fun addVary(headers: Headers, merge: HeaderMerge) {
            val existing = headers.all("vary")
            if (merge == HeaderMerge.REPLACE && existing.isNotEmpty()) return
            headers.remove("vary")
            headers.add("vary", "Accept-Encoding")
            for (value in existing) headers.add("vary", value)
        }

        /**
         * The checks `gzhttp` can make from the headers alone: not already encoded, not a range,
         * and (when the length and type are known) at least 1 KB of a compressible type.
         */
        fun mayCompress(headers: Headers): Boolean {
            if (!headers["content-encoding"].isNullOrEmpty() || !headers["content-range"].isNullOrEmpty()) return false
            val contentLength = headers["content-length"]?.let(::parseUsize) ?: 0L
            val contentType = headers["content-type"] ?: ""
            return contentLength == 0L || (contentLength >= MIN_SIZE && (contentType.isEmpty() || contentTypeFilter(contentType)))
        }

        /** Rust's `usize::from_str`: an optional `+` and decimal digits. */
        private fun parseUsize(value: String): Long? =
            if (value.startsWith("-")) null else value.toLongOrNull()

        /** `selectEncoding`: never for HEAD; zstd when its quality is at least gzip's. */
        fun selectEncoding(method: String, acceptEncoding: String?): Encoding {
            if (method == "HEAD") return Encoding.NONE
            if (acceptEncoding.isNullOrEmpty()) return Encoding.NONE
            val gzip = quality(acceptEncoding, "gzip")
            val zstd = quality(acceptEncoding, "zstd")
            return when {
                !(gzip > 0.0) && !(zstd > 0.0) -> Encoding.NONE
                !(zstd > 0.0) -> Encoding.GZIP
                !(gzip > 0.0) -> Encoding.ZSTD
                gzip > zstd -> Encoding.GZIP
                else -> Encoding.ZSTD
            }
        }

        /** `parseEncodingQValue`: the first listing of [encoding]'s quality (1 without a `q`), else 0. */
        private fun quality(header: String, encoding: String): Double {
            val end = Ascii.trimEnd(header, 0, header.length)
            var start = Ascii.trimStart(header, 0, end)
            while (start <= end) {
                var partEnd = header.indexOf(',', start)
                if (partEnd < 0 || partEnd > end) partEnd = end
                var semicolon = header.indexOf(';', start)
                if (semicolon < 0 || semicolon > partEnd) semicolon = partEnd
                val a = Ascii.trimStart(header, start, semicolon)
                if (Ascii.regionEquals(header, a, Ascii.trimEnd(header, a, semicolon), encoding)) {
                    var q = 1.0
                    var p = semicolon
                    while (p < partEnd) {
                        var next = header.indexOf(';', p + 1)
                        if (next < 0 || next > partEnd) next = partEnd
                        val s = Ascii.trimStart(header, p + 1, next)
                        val e = Ascii.trimEnd(header, s, next)
                        if (header.startsWith("q=", s) && s + 2 <= e) q = (parseFloat(header.substring(s + 2, e)) ?: 0.0).coerceIn(0.0, 1.0)
                        p = next
                    }
                    return q
                }
                start = partEnd + 1
            }
            return 0.0
        }

        /** Rust's `f64::from_str`, closely enough for q-values: decimal and exponent forms, `inf` and `nan`. */
        private fun parseFloat(value: String): Double? {
            if (value.isEmpty()) return null
            if (value.all { it in '0'..'9' || it == '.' || it == '+' || it == '-' || it == 'e' || it == 'E' }) {
                return value.toDoubleOrNull()
            }
            val unsigned = value.removePrefix("+").removePrefix("-").lowercase()
            val negative = value.startsWith("-")
            return when (unsigned) {
                "inf", "infinity" -> if (negative) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
                "nan" -> Double.NaN
                else -> null
            }
        }

        private val EXCLUDE_CONTAINS = arrayOf("compress", "zip", "snappy", "lzma", "xz", "zstd", "brotli", "stuffit")
        private val EXCLUDE_PREFIX = arrayOf(
            "video/", "audio/", "image/jp", "image/jpeg", "image/jpg", "image/png", "image/apng", "image/webp", "image/gif",
            "image/avif", "image/heic", "image/heif", "image/jxl",
        )

        /** Thruster's `contentTypeFilter`: gzhttp's default filter, minus already-compressed images. */
        fun contentTypeFilter(contentType: String): Boolean {
            val type = contentType.trim().lowercase()
            if (type.isEmpty()) return true
            return !(EXCLUDE_CONTAINS.any { it in type } || EXCLUDE_PREFIX.any { type.startsWith(it) })
        }

        /** `hasUserSpecificRequestHeaders` (GZIP_COMPRESSION_DISABLE_ON_AUTH). */
        fun hasUserSpecificRequestHeaders(headers: Headers): Boolean =
            !headers["cookie"].isNullOrEmpty() || !headers["authorization"].isNullOrEmpty() || !headers["x-csrf-token"].isNullOrEmpty()

        /** `hasUserSpecificResponseHeaders`: a cookie set, `private` or `no-store`, or `Vary: Cookie`. */
        fun hasUserSpecificResponseHeaders(headers: Headers): Boolean {
            if (!headers["set-cookie"].isNullOrEmpty()) return true
            val cacheControl = (headers["cache-control"] ?: "").lowercase()
            val private = cacheControl.split(',').any {
                val directive = it.trim().substringBefore('=')
                directive == "private" || directive == "no-store"
            }
            if (private) return true
            return Ascii.listHas(headers["vary"] ?: "", "cookie")
        }

        /** The first [n] bytes of [body] (fewer if it's shorter). */
        private fun prefix(body: Body, n: Int): ByteArray {
            val out = ByteArrayOutputStream(n)
            for (chunk in body.chunks()) {
                val take = minOf(n - out.size(), chunk.size)
                out.write(chunk, 0, take)
                if (out.size() == n) break
            }
            return out.toByteArray()
        }

        /**
         * A gzip member as flate2's `GzBuilder` writes it at level 6: no mtime, extra flags 0,
         * OS 255 (unknown), and [comment] (the jitter) when given.
         */
        fun gzip(chunks: List<ByteArray>, comment: String?): ByteArray {
            val out = ByteArrayOutputStream(chunks.sumOf { it.size } / 3 + 64)
            out.write(byteArrayOf(0x1f, 0x8b.toByte(), 8, if (comment != null) 0x10 else 0, 0, 0, 0, 0, 0, 0xff.toByte()))
            if (comment != null) {
                out.write(comment.toByteArray(Charsets.ISO_8859_1))
                out.write(0)
            }
            val deflater = DEFLATERS.get()
            deflater.reset()
            val crc = CRC32()
            val buf = ByteArray(16 * 1024)
            var length = 0L
            for (chunk in chunks) {
                if (chunk.isEmpty()) continue
                crc.update(chunk)
                length += chunk.size
                deflater.setInput(chunk)
                while (!deflater.needsInput()) out.write(buf, 0, deflater.deflate(buf, 0, buf.size, Deflater.NO_FLUSH))
            }
            deflater.finish()
            while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
            le32(out, crc.value.toInt())
            le32(out, length.toInt())
            return out.toByteArray()
        }

        /** A zstd frame at level 1 (no checksum, no content size), then the jitter in a skippable frame (RFC 8878 3.1.2). */
        fun zstd(chunks: List<ByteArray>, jitter: String?): ByteArray {
            val out = ByteArrayOutputStream(chunks.sumOf { it.size } / 3 + 64)
            ZstdOutputStream(out, ZSTD_LEVEL).use { stream -> for (chunk in chunks) if (chunk.isNotEmpty()) stream.write(chunk) }
            if (jitter != null) {
                le32(out, 0x184D2A50)
                le32(out, jitter.length)
                out.write(jitter.toByteArray(Charsets.ISO_8859_1))
            }
            return out.toByteArray()
        }

        private fun le32(out: ByteArrayOutputStream, v: Int) {
            for (i in 0 until 4) out.write(v ushr (8 * i))
        }

        private val HTML = arrayOf(
            "<!DOCTYPE HTML", "<HTML", "<HEAD", "<SCRIPT", "<IFRAME", "<H1", "<DIV", "<FONT", "<TABLE", "<A", "<STYLE", "<TITLE",
            "<B", "<BODY", "<BR", "<P", "<!--",
        )

        private val EXACT: List<Pair<ByteArray, String>> = listOf(
            "%PDF-".toByteArray() to "application/pdf",
            "%!PS-Adobe-".toByteArray() to "application/postscript",
            byteArrayOf(0xFE.toByte(), 0xFF.toByte()) to "text/plain; charset=utf-16be",
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()) to "text/plain; charset=utf-16le",
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) to "text/plain; charset=utf-8",
            "GIF87a".toByteArray() to "image/gif",
            "GIF89a".toByteArray() to "image/gif",
            byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A) to "image/png",
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) to "image/jpeg",
        )

        /**
         * A subset of Go's `http.DetectContentType`, for bodies sent without a `Content-Type`
         * (the app always sends one, so this is only a fallback).
         */
        fun detectContentType(sample: ByteArray): String {
            val data = if (sample.size > 512) sample.copyOf(512) else sample
            var start = 0
            while (start < data.size && data[start].toInt().let { it == 9 || it == 10 || it == 12 || it == 13 || it == 32 }) start++
            for (signature in HTML) {
                if (data.size - start > signature.length && matchesIgnoreCase(data, start, signature)) {
                    val next = data[start + signature.length].toInt()
                    if (next == ' '.code || next == '>'.code) return "text/html; charset=utf-8"
                }
            }
            if (startsWith(data, start, "<?xml".toByteArray())) return "text/xml; charset=utf-8"
            for ((signature, type) in EXACT) if (startsWith(data, 0, signature)) return type
            if (data.size >= 14 && startsWith(data, 0, "RIFF".toByteArray()) && startsWith(data, 8, "WEBPVP".toByteArray())) return "image/webp"
            if (startsWith(data, 0, byteArrayOf(0x1F, 0x8B.toByte(), 0x08))) return "application/x-gzip"
            if (startsWith(data, 0, byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4))) return "application/zip"
            val binary = data.any { val b = it.toInt() and 0xff; b <= 0x08 || b == 0x0B || b in 0x0E..0x1A || b in 0x1C..0x1F }
            return if (binary) "application/octet-stream" else "text/plain; charset=utf-8"
        }

        private fun matchesIgnoreCase(data: ByteArray, at: Int, signature: String): Boolean {
            for (k in signature.indices) {
                if (Ascii.lower((data[at + k].toInt() and 0xff).toChar()) != Ascii.lower(signature[k])) return false
            }
            return true
        }

        private fun startsWith(data: ByteArray, at: Int, signature: ByteArray): Boolean {
            if (data.size - at < signature.size) return false
            for (k in signature.indices) if (data[at + k] != signature[k]) return false
            return true
        }

        /** CRC-32C (Castagnoli), as `crc32.Update(0, castagnoliTable, b)`. */
        fun crc32c(data: ByteArray): Int = CRC32C().apply { update(data) }.value.toInt()
    }
}
