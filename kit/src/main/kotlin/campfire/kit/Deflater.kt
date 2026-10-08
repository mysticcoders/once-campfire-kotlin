package campfire.kit

import java.io.ByteArrayOutputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32

/**
 * A response extension: `ActionDispatch::Static` served it (a public file or an asset), so the
 * middleware below `Static` in the reference never saw it (`StaticFile` in
 * `crates/kit/src/deflater.rs`). With [AppContentLength], it lets an explicit `Content-Length: 0`
 * exempt the response from [RackDeflater].
 */
data object StaticFile

/**
 * A response extension: the app set `Content-Length` itself (`PublicExceptions`,
 * `ShowExceptions#pass_response`), as opposed to one the server adds from the body later.
 */
data object AppContentLength

/**
 * A response extension: the SHA-256 `Rack::ETag` took of the whole body (`BodyDigest`), the body's
 * identity, so the gzip of a body that repeats (the sidebar) is kept instead of deflated again.
 */
class BodyDigest(val sha: ByteArray)

/**
 * `Rack::Deflater` (rack 3.2), which the reference installs around the whole app in `config.ru`,
 * as `crates/kit/src/deflater.rs` ports it: it gzips every response with a body when the client
 * prefers gzip to identity, whatever its size or type, merges `Accept-Encoding` into `Vary`
 * (comma-joined, no space), and answers 406 when no acceptable encoding remains.
 *
 * A gzipped response loses `Content-Length` and is marked [ChunkedBody], so it goes out chunked as
 * in Rust (and the front server's compression leaves it alone). The gzip header carries the
 * `Last-Modified` time (or 0) and OS 3, as `Zlib::GzipWriter` writes it. A [Body.Page] is spliced
 * from its parts' stored pieces ([PageParts.gzipChunks]); a body with a [BodyDigest] is gzipped
 * once while it keeps repeating; anything else is deflated chunk by chunk with a sync flush after
 * each, as `GzipStream` with `sync: true` does.
 */
class RackDeflater : Middleware {
    override fun handle(request: Request, next: Handler): Response {
        val acceptEncoding = request.headers["accept-encoding"] ?: ""
        val response = next.handle(request)
        if (!shouldDeflate(response)) return response
        val encoding = selectBestEncoding(AVAILABLE, parseAcceptEncoding(acceptEncoding))
        mergeVary(response.headers)
        return when (encoding) {
            "gzip" -> gzipResponse(response)
            null -> notAcceptable(request.target)
            else -> response
        }
    }

    private fun gzipResponse(response: Response): Response {
        val mtime = response.headers["last-modified"]?.let(::parseHttpDate) ?: 0
        response.headers["content-encoding"] = "gzip"
        response.headers.remove("content-length")
        val digest = response.extensions.remove(BodyDigest::class.java) as BodyDigest?
        val body = response.body
        response.body = when {
            body is Body.Page -> Body.Chunks(body.parts.gzipChunks(mtime))
            digest != null && body is Body.Bytes -> Body.Bytes(gzipDigested(body.bytes, digest, mtime))
            else -> Body.Chunks(gzipStream(body.chunks(), mtime))
        }
        response.putExtension(ChunkedBody)
        return response
    }

    internal companion object {
        private val AVAILABLE = listOf("gzip", "identity")

        /** A bound on the bytes of kept gzip members (a sidebar's is ~6 KB). */
        private const val MAX_GZIPPED_BYTES = 16L shl 20

        /** What a kept member costs beyond its bytes (`ENTRY_OVERHEAD` in `splice.rs`). */
        private const val ENTRY_OVERHEAD = 128

        /** Gzip members by body digest and gzip mtime. */
        val GZIPPED = Generations<GzipKey, ByteArray>(MAX_GZIPPED_BYTES) { it.size + ENTRY_OVERHEAD.toLong() }

        private val DEFLATERS: ThreadLocal<java.util.zip.Deflater> = ThreadLocal.withInitial { java.util.zip.Deflater(6, true) }

        /**
         * `should_deflate?` (rack 3.2.6; the reference passes no `:include` or `:if`): not for
         * statuses without a body (1xx, 204, 304), `no-transform`, a non-identity
         * `Content-Encoding`, or a `Content-Length: 0` the app set ([StaticFile] or [AppContentLength]).
         */
        fun shouldDeflate(response: Response): Boolean {
            val status = response.status
            if (status in 100..199 || status == 204 || status == 304) return false
            val headers = response.headers
            if (headers["cache-control"]?.let { Ascii.hasWord(it, "no-transform") } == true) return false
            if (headers["content-encoding"]?.let { !Ascii.hasWord(it, "identity") } == true) return false
            val appSetLength = response.extension<StaticFile>() != null || response.extension<AppContentLength>() != null
            return !(appSetLength && headers["content-length"] == "0")
        }

        /** Adds `Accept-Encoding` to `Vary` unless it's there or `*` is, joining the tokens with "," (Rack's `vary.join(',')`). */
        fun mergeVary(headers: Headers) {
            val values = headers.all("vary")
            val tokens = ArrayList<String>()
            for (value in values) for (token in value.split(',')) tokens.add(token.trim())
            if (tokens.any { it == "*" || it.equals("accept-encoding", ignoreCase = true) }) return
            val vary = if (values.isNotEmpty()) tokens else ArrayList()
            vary.add("Accept-Encoding")
            headers["vary"] = vary.joinToString(",")
        }

        /** `Rack::Request#accept_encoding` (`parse_http_accept_header`): each coding with its `q=` (1.0 without). */
        fun parseAcceptEncoding(header: String): List<Pair<String, Double>> {
            val out = ArrayList<Pair<String, Double>>(4)
            for (raw in header.split(',')) {
                val part = raw.trim()
                if (part.isEmpty()) continue
                val semicolon = part.indexOf(';')
                val attribute = if (semicolon < 0) part else part.substring(0, semicolon).trim()
                val parameters = if (semicolon < 0) null else part.substring(semicolon + 1).trim()
                out.add(attribute to (parameters?.let(::quality) ?: 1.0))
            }
            return out
        }

        /** `/\Aq=([\d.]+)/ =~ parameters` then `String#to_f` of the digits, or null when it doesn't match. */
        private fun quality(parameters: String): Double? {
            if (!parameters.startsWith("q=")) return null
            var end = 2
            while (end < parameters.length && (parameters[end] in '0'..'9' || parameters[end] == '.')) end++
            if (end == 2) return null
            return campfire.ruby.toF(parameters.substring(2, end))
        }

        /** `Rack::Utils.select_best_encoding`. */
        fun selectBestEncoding(available: List<String>, accept0: List<Pair<String, Double>>): String? {
            val accept = if (accept0.size > 16) accept0.subList(0, 16) else accept0
            val expanded = ArrayList<Triple<String, Double, Int>>()
            var wildcardSeen = false
            for ((m, q) in accept) {
                val preference = available.indexOf(m).let { if (it < 0) available.size else it }
                if (m == "*") {
                    if (!wildcardSeen) {
                        for (m2 in available) if (accept.none { it.first == m2 }) expanded.add(Triple(m2, q, preference))
                        wildcardSeen = true
                    }
                } else {
                    expanded.add(Triple(m, q, preference))
                }
            }
            val sorted = expanded.sortedWith { a, b -> b.second.compareTo(a.second).let { if (it != 0) it else a.third.compareTo(b.third) } }
            val candidates = sorted.mapTo(ArrayList()) { it.first }
            if ("identity" !in candidates) candidates.add("identity")
            for ((m, q) in expanded) if (q == 0.0) candidates.removeAll { it == m }
            return candidates.firstOrNull { it in available }
        }

        /** The 406 Rack answers when neither gzip nor identity is acceptable. */
        private fun notAcceptable(target: String): Response {
            val message = "An acceptable encoding for the requested resource $target could not be found."
            val bytes = message.toByteArray(Charsets.UTF_8)
            return Response(406, Headers().add("content-type", "text/plain").add("content-length", bytes.size.toString()), Body.Bytes(bytes))
        }

        /** The `Last-Modified` time in epoch seconds (as `u32`, wrapping), or null if it doesn't parse. */
        private fun parseHttpDate(value: String): Int? = try {
            ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond().toInt()
        } catch (_: Exception) {
            null
        }

        /** The gzip header `Zlib::GzipWriter` writes: the mtime and the Unix OS code, no flags. */
        private fun header(mtime: Int): ByteArray =
            byteArrayOf(0x1f, 0x8b.toByte(), 8, 0, mtime.toByte(), (mtime shr 8).toByte(), (mtime shr 16).toByte(), (mtime shr 24).toByte(), 0, 3)

        /**
         * `GzipStream` with `sync: true`: the header, each non-empty chunk deflated and
         * sync-flushed, then the final block and the trailer, as one buffer per step.
         */
        fun gzipStream(chunks: List<ByteArray>, mtime: Int): List<ByteArray> {
            val out = ArrayList<ByteArray>(chunks.size + 2)
            val deflater = DEFLATERS.get()
            deflater.reset()
            val crc = CRC32()
            var length = 0L
            val buf = ByteArray(16 * 1024)
            val first = ByteArrayOutputStream(64)
            first.write(header(mtime))
            var pending: ByteArrayOutputStream? = first
            for (chunk in chunks) {
                if (chunk.isEmpty()) continue
                crc.update(chunk)
                length += chunk.size
                val piece = pending ?: ByteArrayOutputStream(chunk.size / 3 + 64)
                deflater.setInput(chunk)
                while (true) {
                    val n = deflater.deflate(buf, 0, buf.size, java.util.zip.Deflater.SYNC_FLUSH)
                    piece.write(buf, 0, n)
                    if (n < buf.size && deflater.needsInput()) break
                }
                out.add(piece.toByteArray())
                pending = null
            }
            val last = pending ?: ByteArrayOutputStream(32)
            deflater.finish()
            while (!deflater.finished()) last.write(buf, 0, deflater.deflate(buf))
            val v = crc.value.toInt()
            val n = length.toInt()
            last.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte(), n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), (n shr 24).toByte()))
            out.add(last.toByteArray())
            return out
        }

        /** What [gzipStream] sends for a single-buffer body, in one piece. */
        fun gzipMember(body: ByteArray, mtime: Int): ByteArray {
            val chunks = gzipStream(listOf(body), mtime)
            if (chunks.size == 1) return chunks[0]
            val out = ByteArray(chunks.sumOf { it.size })
            var at = 0
            for (chunk in chunks) {
                System.arraycopy(chunk, 0, out, at, chunk.size)
                at += chunk.size
            }
            return out
        }

        /** A body `Rack::ETag` digested, gzipped once while it keeps repeating; one huge body isn't kept. */
        fun gzipDigested(body: ByteArray, digest: BodyDigest, mtime: Int): ByteArray {
            val key = GzipKey(digest.sha, mtime)
            GZIPPED.get(key)?.let { return it }
            val gzipped = gzipMember(body, mtime)
            if (gzipped.size <= MAX_GZIPPED_BYTES / 4) GZIPPED.put(key, gzipped)
            return gzipped
        }
    }
}

/** A gzip member's key: the body's SHA-256 and the header's mtime. */
internal class GzipKey(private val sha: ByteArray, private val mtime: Int) {
    private val hash = sha.contentHashCode() * 31 + mtime

    override fun equals(other: Any?) = other is GzipKey && other.mtime == mtime && other.sha.contentEquals(sha)

    override fun hashCode() = hash
}

/**
 * A map in two generations, bounded by what its entries cost (`Generations` in
 * `crates/kit/src/deflater/splice.rs`): reading an old entry promotes it, and when the young
 * generation costs more than half the budget it becomes the old one (dropping the previous old
 * one). Where Rust holds a mutex for each call, lookups and stores here run on concurrent maps and
 * only a rotation takes a lock, so the bound is approximate under contention.
 */
internal class Generations<K : Any, V : Any>(private val budget: Long, private val cost: (V) -> Long) {
    @Volatile private var young = ConcurrentHashMap<K, V>()

    @Volatile private var old = ConcurrentHashMap<K, V>()
    private val youngCost = AtomicLong()

    /** The entry for [key], promoting it if it's old. */
    fun get(key: K): V? {
        young[key]?.let { return it }
        val value = old.remove(key) ?: return null
        put(key, value)
        return value
    }

    /** Stores [value] under [key] in the young generation, rotating when it's full. */
    fun put(key: K, value: V) {
        val previous = young.put(key, value)
        val total = youngCost.addAndGet(cost(value) - (previous?.let(cost) ?: 0L))
        if (total > budget / 2) rotate()
    }

    @Synchronized
    private fun rotate() {
        if (youngCost.get() <= budget / 2) return
        old = young
        young = ConcurrentHashMap()
        youngCost.set(0)
    }
}
