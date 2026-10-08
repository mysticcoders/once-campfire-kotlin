package campfire.assets

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Socket
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Compares [Assets.serve] with the Rust release binary's static file responses, byte for byte,
 * when `CAMPFIRE_RUST_APP` names the Rust app's own listener (its TARGET_PORT, e.g.
 * `127.0.0.1:4791`, which has no front-server cache, compression or `Date`); skipped otherwise.
 *
 * Header names are compared case-insensitively (the Rust server lowercases them on the wire) and
 * in order. `last-modified` holds each build's own time, so it's left out, and If-Modified-Since
 * sends each side its own. What the Rust app's layers outside crates/assets add is taken off its
 * side first: hyper's `connection` and `transfer-encoding`, the trailing `vary: Accept-Encoding`
 * every response gets, and the app's own gzip (Rack::Deflater's place in crates/kit) of a body
 * that wasn't already encoded, which also drops `content-length`; that body is gunzipped.
 */
class RustBinaryComparisonTest {
    private val target = System.getenv("CAMPFIRE_RUST_APP")
    private var deflated = 0

    /** A response as read off the wire: status, headers in order, body. */
    private class Wire(val status: Int, val headers: List<Pair<String, String>>, val body: ByteArray)

    @Test
    fun servedFilesMatchTheRustBinary() {
        if (target.isNullOrEmpty()) return
        val requests = ArrayList<StaticRequest>()
        for (url in Embedded.files.keys) {
            requests.add(StaticRequest("GET", url))
            requests.add(StaticRequest("GET", url, acceptEncoding = "gzip, deflate, br"))
        }
        val css = Assets.stylesheetPath("base")
        val js = Assets.javascriptPath("application")
        val sound = Assets.audioPath("56k.mp3")
        val lexxyGz = "/assets/" + Assets.digestedPath("lexxy.js.gz")!!.removeSuffix(".gz")
        for (path in listOf(css, js, sound, "/robots.txt", "/assets/.manifest.json")) {
            requests.add(StaticRequest("HEAD", path))
            requests.add(StaticRequest("GET", path, range = "bytes=0-9"))
            requests.add(StaticRequest("GET", path, range = "bytes=-5"))
            requests.add(StaticRequest("GET", path, range = "bytes=10-"))
            requests.add(StaticRequest("GET", path, range = "bytes=0-1, 4-5"))
            requests.add(StaticRequest("GET", path, range = "bytes=99999999-"))
            requests.add(StaticRequest("HEAD", path, range = "bytes=0-9"))
            requests.add(StaticRequest("GET", path, acceptEncoding = "gzip", range = "bytes=3-7"))
        }
        for (path in listOf("/404", "/assets/../robots.txt", "/%72obots.txt", "/robots.txt/", lexxyGz)) {
            requests.add(StaticRequest("GET", path))
            requests.add(StaticRequest("GET", path, acceptEncoding = "gzip"))
        }

        val mismatches = ArrayList<String>()
        for (request in requests) {
            val label = "${request.method} ${request.path} ae=${request.acceptEncoding} range=${request.range}"
            val theirs = fetch(request)
            val ours = Assets.serve(request)
            if (ours == null) {
                if (theirs.status != 404) mismatches.add("$label: ours falls through, theirs ${theirs.status}")
                continue
            }
            val diff = compare(ours, theirs)
            if (diff != null) mismatches.add("$label: $diff")
        }

        val notModified = StaticRequest("GET", css)
        val theirLastModified = fetch(notModified).headers.first { it.first == "last-modified" }.second
        val theirs304 = fetch(notModified.copy(ifModifiedSince = theirLastModified))
        val ours304 = Assets.serve(notModified.copy(ifModifiedSince = Embedded.builtAt))!!
        compare(ours304, theirs304)?.let { mismatches.add("If-Modified-Since: $it") }

        println("compared ${requests.size + 1} requests with the Rust binary at $target; ${mismatches.size} mismatches, $deflated gzipped by the Rust app's own layer")
        assertEquals(emptyList(), mismatches)
    }

    /** What differs between our response and theirs, or null when status, headers and body are identical. */
    private fun compare(ours: Served, theirs: Wire): String? {
        var ourHeaders = ours.headers.map { it.first.lowercase() to it.second }.filter { it.first != "last-modified" }
        var theirHeaders = theirs.headers.filter { it.first !in setOf("last-modified", "connection", "transfer-encoding") }
        if (theirHeaders.lastOrNull() == ("vary" to "Accept-Encoding")) theirHeaders = theirHeaders.dropLast(1)
        var theirBody = theirs.body
        val appGzip = theirHeaders.contains("content-encoding" to "gzip") && ours.header("content-encoding") == null
        if (appGzip) {
            deflated++
            theirHeaders = theirHeaders - ("content-encoding" to "gzip")
            ourHeaders = ourHeaders.filter { it.first != "content-length" }
            if (theirBody.isNotEmpty()) theirBody = GZIPInputStream(theirBody.inputStream()).readAllBytes()
        }
        return when {
            ours.status != theirs.status -> "status ${ours.status} vs ${theirs.status}"
            ourHeaders != theirHeaders -> "headers $ourHeaders vs $theirHeaders"
            !ours.bodyBytes().contentEquals(theirBody) -> "body differs (${ours.bodyLength} vs ${theirBody.size} bytes)"
            else -> null
        }
    }

    /** One HTTP/1.1 request on its own connection, read raw so header order and bytes are kept. */
    private fun fetch(request: StaticRequest): Wire {
        val (host, port) = target!!.split(':')
        Socket(host, port.toInt()).use { socket ->
            val head = StringBuilder("${request.method} ${request.path} HTTP/1.1\r\nHost: $target\r\nConnection: close\r\n")
            request.acceptEncoding?.let { head.append("Accept-Encoding: $it\r\n") }
            request.range?.let { head.append("Range: $it\r\n") }
            request.ifModifiedSince?.let { head.append("If-Modified-Since: $it\r\n") }
            socket.getOutputStream().apply { write(head.append("\r\n").toString().toByteArray()); flush() }
            return parse(socket.getInputStream().readAllBytes(), request.method == "HEAD")
        }
    }

    private fun parse(raw: ByteArray, head: Boolean): Wire {
        val text = String(raw, Charsets.ISO_8859_1)
        val end = text.indexOf("\r\n\r\n")
        val lines = text.substring(0, end).split("\r\n")
        val status = lines[0].split(' ')[1].toInt()
        val headers = lines.drop(1).map { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
        val rest = raw.copyOfRange(end + 4, raw.size)
        val chunked = headers.any { it.first == "transfer-encoding" && it.second == "chunked" }
        val body = if (head) ByteArray(0) else if (chunked) dechunk(rest.inputStream()) else rest
        return Wire(status, headers, body)
    }

    private fun dechunk(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val size = generateSequence { input.read().takeIf { it >= 0 && it != '\n'.code } }.map { it.toChar() }.joinToString("").trim().toInt(16)
            if (size == 0) return out.toByteArray()
            out.write(input.readNBytes(size))
            input.readNBytes(2)
        }
    }
}
