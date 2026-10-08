package campfire.kit.front

import campfire.kit.Body
import campfire.kit.Headers
import campfire.kit.Request
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The tests of `crates/kit/src/front/cache.rs`. */
class ResponseCacheTest {
    private val second = 1_000_000_000L

    private fun response(body: String) = CachedResponse(200, Headers(), Body.of(body), emptyList())

    private fun headers(vararg pairs: Pair<String, String>) = Headers().also { h -> for ((n, v) in pairs) h.add(n, v) }

    private fun request(method: String, target: String, vararg pairs: Pair<String, String>) = Request(method, target, headers(*pairs))

    @Test
    fun lifetimeNeedsPublicAndAMaxAge() {
        assertEquals(2592000L, cacheLifetime(200, headers("cache-control" to "public, max-age=2592000")))
        assertEquals(300L, cacheLifetime(200, headers("cache-control" to "max-age=300, public, stale-while-revalidate=604800")))
        assertEquals(10L, cacheLifetime(200, headers("cache-control" to "public, s-max-age=10, max-age=99")))
        assertNull(cacheLifetime(200, headers("cache-control" to "max-age=0, private, must-revalidate")))
        assertNull(cacheLifetime(200, headers("cache-control" to "public")))
        assertNull(cacheLifetime(200, headers("cache-control" to "public, max-age=0")))
        assertNull(cacheLifetime(200, headers("cache-control" to "public, no-cache, max-age=60")))
        assertNull(cacheLifetime(200, headers("cache-control" to "public, max-age=60", "vary" to "*")))
        val public = headers("cache-control" to "public, max-age=60")
        assertNull(cacheLifetime(304, public))
        assertNull(cacheLifetime(404, public))
        assertNotNull(cacheLifetime(301, public))
    }

    @Test
    fun lifetimeMatchesTheRegexesAtTheirEdges() {
        assertNull(cacheLifetime(200, headers("cache-control" to "nonpublic, max-age=60")))
        assertNull(cacheLifetime(200, headers("cache-control" to "public, max-age=60x")))
        assertEquals(60L, cacheLifetime(200, headers("cache-control" to "public, max-age=60x, max-age=60")))
        assertEquals(60L, cacheLifetime(200, headers("cache-control" to "public, s-max-age=1x, max-age=60")))
        assertNull(cacheLifetime(200, headers("cache-control" to "public, max-age=99999999999999999999")))
        assertNull(cacheLifetime(200, headers("cache-control" to "public, xmax-age=60")))
    }

    @Test
    fun requestsThatBypassTheCache() {
        fun get(vararg pairs: Pair<String, String>) = shouldCacheRequest(request("GET", "/x", *pairs))
        assertTrue(get())
        assertFalse(get("upgrade" to "websocket"))
        assertFalse(get("connection" to "Upgrade"))
        assertTrue(get("connection" to "upgrade"), "Thruster compares exactly")
        assertFalse(get("range" to "bytes=0-1"))
        assertFalse(shouldCacheRequest(request("POST", "/x")))
        assertTrue(shouldCacheRequest(request("HEAD", "/x")))
        fun uri(length: Int) = "/qr_code/aGk?pad=" + "x".repeat(length - 17)
        assertTrue(shouldCacheRequest(request("HEAD", uri(MAX_CACHEABLE_URI))))
        assertFalse(shouldCacheRequest(request("HEAD", uri(MAX_CACHEABLE_URI + 1))))
    }

    @Test
    fun keysUseTheRawPathAndQueryAndIncludeVaryingHeaders() {
        fun req(uri: String, ae: String) = request("GET", uri, "host" to "chat.test", "accept-encoding" to ae)
        fun key(uri: String) = Variant(req(uri, "gzip")).cacheKey()
        assertNotEquals(key("/a?a=1"), key("/a?a=2"))
        assertNotEquals(key("/a%2Fb"), key("/a/b"))
        assertNotEquals(key("/a?b=2&a=1"), key("/a?a=1&b=2"))
        assertNotEquals(key("/a?q=a+b"), key("/a?q=a%20b"))
        assertNotEquals(key("/a?disposition=attachment;"), key("/a"), "Rack reads the pair Go's query parser dropped")
        assertNotEquals(key("/a?disposition=inline&disposition=x;"), key("/a?disposition=inline"))
        assertEquals(key("/a?"), key("/a"))

        val gzip = Variant(req("/a", "gzip"))
        val plain = Variant(req("/a", ""))
        assertEquals(gzip.cacheKey(), plain.cacheKey())
        val vary = headers("vary" to "Accept-Encoding")
        gzip.setResponseHeaders(vary)
        plain.setResponseHeaders(vary)
        assertNotEquals(gzip.cacheKey(), plain.cacheKey())
        assertTrue(gzip.matches(gzip.variantHeaders()))
        assertFalse(plain.matches(gzip.variantHeaders()))
    }

    @Test
    fun theVariantKeepsTheRequestHeadersItWasMadeWith() {
        val request = request("GET", "/a", "host" to "chat.test", "x-forwarded-host" to "client.test")
        val variant = Variant(request)
        request.headers["x-forwarded-host"] = "proxy.test"
        variant.setResponseHeaders(headers("vary" to "X-Forwarded-Host"))
        assertEquals(listOf("x-forwarded-host" to "client.test"), variant.variantHeaders())
    }

    /** A body that makes an entry under a one-letter key take [size] bytes. */
    private fun responseOfSize(size: Int) = response("x".repeat(size - 1 - ENTRY_OVERHEAD))

    @Test
    fun memoryCacheExpiresAndEvicts() {
        val now = System.nanoTime()
        val item = ENTRY_OVERHEAD + 50L
        val cache = MemoryCache(2 * item, item + 10)
        cache.set("a", responseOfSize(item.toInt()), now + 10 * second, now)
        assertNotNull(cache.get("a", now))
        assertNull(cache.get("a", now + 11 * second))

        cache.set("big", responseOfSize(item.toInt() + 11), now + 10 * second, now)
        assertNull(cache.get("big", now), "larger than the item limit")

        cache.set("b", responseOfSize(item.toInt()), now + 10 * second, now)
        cache.set("c", responseOfSize(item.toInt()), now + 10 * second, now)
        assertEquals(2 * item, cache.size())
        val kept = listOf("a", "b", "c").count { cache.get(it, now) != null }
        assertEquals(2, kept)

        cache.set("c", responseOfSize(item.toInt() - 30), now + 10 * second, now)
        assertTrue(cache.size() <= 2 * item)
    }

    @Test
    fun memoryCacheChargesKeys() {
        val now = System.nanoTime()
        val capacity = 64L * 1024
        val cache = MemoryCache(capacity, 1024 * 1024)
        for (n in 0 until 100) {
            val key = "HEAD\n/qr_code/aGk\npad=$n" + "x".repeat(1000) + "\nchat.test"
            cache.set(key, response(""), now + 60 * second, now)
        }
        assertTrue(cache.size() <= capacity)
        assertTrue(cache.size() > capacity - 2000, "charged the keys: ${cache.size()}")
        val held = cache.heldKeys().sumOf { it.length + ENTRY_OVERHEAD.toLong() }
        assertTrue(held <= capacity)
    }

    @Test
    fun notModifiedComparesEtags() {
        val cached = CachedResponse(200, headers("etag" to "\"abc\""), Body.of("x"), emptyList())
        fun req(inm: String) = request("GET", "/", "if-none-match" to inm)
        assertTrue(wasNotModified(cached, req("\"abc\"")))
        assertTrue(wasNotModified(cached, req("\"x\", \"abc\"")))
        assertFalse(wasNotModified(cached, req("W/\"abc\"")))
        assertFalse(wasNotModified(response("x"), req("")))
    }
}
