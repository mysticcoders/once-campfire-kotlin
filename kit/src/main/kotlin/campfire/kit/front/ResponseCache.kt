package campfire.kit.front

import campfire.kit.Ascii
import campfire.kit.Body
import campfire.kit.Headers
import campfire.kit.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom

/** The longest path and query a cacheable request may have (`MAX_CACHEABLE_URI`, not Thruster's). */
const val MAX_CACHEABLE_URI = 2048

/** What an entry costs beyond its key and response (`ENTRY_OVERHEAD` in `crates/kit/src/front/cache.rs`). */
internal const val ENTRY_OVERHEAD = 256

/**
 * A response as Thruster's cache keeps it (`CacheableResponse`): its status, headers, recorded
 * body, and the request's values of the headers it `Vary`s on when it was stored.
 */
class CachedResponse(val status: Int, val headers: Headers, val body: Body, val variant: List<Pair<String, String>>) {
    /** How much of the cache it takes up stored under [key]: body, headers, variant, key and overhead. */
    fun size(key: String): Long {
        var headerBytes = 0L
        for ((name, value) in headers) headerBytes += name.length + value.length
        var variantBytes = 0L
        for ((name, value) in variant) variantBytes += name.length + value.length
        return body.length + headerBytes + variantBytes + key.length + ENTRY_OVERHEAD
    }
}

/**
 * Thruster's `MemoryCache` (`internal/memory_cache.go`, `crates/kit/src/front/cache.rs`): a
 * size-bounded map that evicts by sampling five random entries and dropping the least recently
 * used (or the first expired one found). Lookups, which run on every cacheable request, are
 * lock-free; stores take a lock for their bookkeeping, as Rust's mutex does. Times are
 * [System.nanoTime] values.
 *
 * As in the Rust port (and unlike Thruster), an entry's size includes its key and bookkeeping, so
 * [capacity] bounds the memory held.
 */
class MemoryCache(private val capacity: Long, private val maxItemSize: Long) {
    private class Entry(val expiresAt: Long, val value: CachedResponse, val size: Long, @Volatile var lastAccessedAt: Long)

    private val items = ConcurrentHashMap<String, Entry>()
    private val keys = ArrayList<String>()
    private var size = 0L

    /** The live entry for [key] at [now], marking it used, or null when absent or expired. */
    fun get(key: String, now: Long): CachedResponse? {
        val item = items[key] ?: return null
        if (item.expiresAt - now < 0) return null
        item.lastAccessedAt = now
        return item.value
    }

    /** Stores [value] under [key] until [expiresAt], evicting until it fits; too large an item isn't stored. */
    @Synchronized
    fun set(key: String, value: CachedResponse, expiresAt: Long, now: Long) {
        val itemSize = value.size(key)
        if (itemSize > maxItemSize || itemSize > capacity) return
        val limit = capacity - itemSize
        while (size > limit && keys.isNotEmpty()) evictOldestItem(now)
        val existing = items[key]
        if (existing != null) size -= existing.size else keys.add(key)
        items[key] = Entry(expiresAt, value, itemSize, now)
        size += itemSize
    }

    /** The bytes the entries take up. */
    @Synchronized
    fun size(): Long = size

    /** The keys held, for tests that check what the entries' keys cost. */
    @Synchronized
    internal fun heldKeys(): List<String> = ArrayList(keys)

    /** `evictOldestItem`: samples 5 random items and evicts the least recently used, or the first expired one. */
    private fun evictOldestItem(now: Long) {
        val random = ThreadLocalRandom.current()
        var oldest = -1
        var oldestAt = 0L
        for (n in 0 until 5) {
            val index = random.nextInt(keys.size)
            val item = items.getValue(keys[index])
            if (item.expiresAt - now < 0) {
                oldest = index
                break
            }
            if (oldest < 0 || item.lastAccessedAt - oldestAt < 0) {
                oldest = index
                oldestAt = item.lastAccessedAt
            }
        }
        val key = keys[oldest]
        val last = keys.removeAt(keys.size - 1)
        if (oldest < keys.size) keys[oldest] = last
        items.remove(key)?.let { size -= it.size }
    }
}

/**
 * `shouldCacheRequest` (`crates/kit/src/front/cache.rs`): GET or HEAD, not an upgrade, not a
 * range, and (unlike Thruster) a path and query of at most [MAX_CACHEABLE_URI] bytes. The upgrade
 * checks compare exactly, as Thruster does.
 */
fun shouldCacheRequest(request: Request): Boolean {
    val headers = request.headers
    val allowedMethod = request.method == "GET" || request.method == "HEAD"
    val isUpgrade = headers["connection"] == "Upgrade" || headers["upgrade"] == "websocket"
    val isRange = !headers["range"].isNullOrEmpty()
    return allowedMethod && !isUpgrade && !isRange && pathAndQueryLength(request) <= MAX_CACHEABLE_URI
}

/** The length of the request target's path and query (an absolute-form target's authority excluded). */
private fun pathAndQueryLength(request: Request): Int {
    val target = request.target
    if (target.startsWith("/")) return target.length
    val scheme = target.indexOf("://")
    if (scheme < 0) return target.length
    val slash = target.indexOf('/', scheme + 3)
    return if (slash < 0) 1 else target.length - slash
}

/**
 * `CacheStatus` before the body's size is known: how many seconds the response may be cached, or
 * null. It needs a 2xx or 3xx status other than 304, no `Vary: *`, and a first `Cache-Control`
 * with the word `public`, without `no-cache`, and a positive `s-max-age` (sic) or else `max-age`.
 * Scans in place of Rust's `(?-u)\b` regexes.
 */
fun cacheLifetime(status: Int, headers: Headers): Long? {
    if (status !in 200..399 || status == 304) return null
    if ('*' in (headers["vary"] ?: "")) return null
    val cacheControl = headers["cache-control"] ?: ""
    if (!Ascii.hasWord(cacheControl, "public") || Ascii.hasWord(cacheControl, "no-cache")) return null
    val digits = directiveDigits(cacheControl, "s-max-age=") ?: directiveDigits(cacheControl, "max-age=") ?: return null
    val seconds = digits.toLongOrNull() ?: return null
    return if (seconds > 0) seconds else null
}

/**
 * The digits of the first `\bname(\d+)\b` match in [value], as the regex finds it: a word boundary
 * before [name], one or more digits after it, and a boundary after the last digit taken.
 */
private fun directiveDigits(value: String, name: String): String? {
    var i = value.indexOf(name)
    while (i >= 0) {
        if (Ascii.boundary(value, i)) {
            val start = i + name.length
            var end = start
            while (end < value.length && value[end] in '0'..'9') end++
            if (end > start && (end == value.length || !Ascii.isWord(value[end]))) return value.substring(start, end)
        }
        i = value.indexOf(name, i + 1)
    }
    return null
}

/**
 * Thruster's `Variant` (`internal/variant.go`, `crates/kit/src/front/cache.rs`): the cache key for
 * a request, given the headers its response varies on. The key is the method, the raw path and
 * query (as the router sees them, not Thruster's decoded and re-encoded forms) and the host,
 * followed by each varying header's value. The request's headers are copied, as Rust clones them,
 * so what the proxy step adds doesn't change the variant.
 */
class Variant(request: Request) {
    private val base: String
    private val requestHeaders: Headers = request.headers.copy()
    private var names: List<String> = emptyList()

    init {
        val host = request.headers["host"] ?: authority(request.target) ?: ""
        base = request.method + "\n" + request.path + "\n" + (request.query ?: "") + "\n" + host
    }

    /** `SetResponseHeader`: vary on the response's first `Vary` names, lowercased and sorted. */
    fun setResponseHeaders(headers: Headers) {
        val vary = headers["vary"] ?: ""
        names = if (vary.isEmpty()) emptyList() else vary.split(',').map { it.trim().lowercase() }.sorted()
    }

    /** The key: the base, then `\nname=value` for each varying header. */
    fun cacheKey(): String {
        if (names.isEmpty()) return base
        val key = StringBuilder(base)
        for (name in names) key.append('\n').append(name).append('=').append(requestValue(name))
        return key.toString()
    }

    /** Whether a stored response's variant headers match this request's. */
    fun matches(variant: List<Pair<String, String>>): Boolean = names.all { name ->
        (variant.firstOrNull { it.first == name }?.second ?: "") == requestValue(name)
    }

    /** The request's value of each varying header, to store with the response. */
    fun variantHeaders(): List<Pair<String, String>> = names.map { it to requestValue(it) }

    private fun requestValue(name: String): String = requestHeaders[name] ?: ""

    private companion object {
        /** The authority of an absolute-form target (`http://host/path`), or null. */
        fun authority(target: String): String? {
            val scheme = target.indexOf("://")
            if (target.startsWith("/") || scheme < 0) return null
            val start = scheme + 3
            val end = target.indexOf('/', start).let { if (it < 0) target.indexOf('?', start) else it }.let { if (it < 0) target.length else it }
            return target.substring(start, end)
        }
    }
}

/** `wasNotModified`: the request's `If-None-Match` lists the cached `ETag` exactly. */
fun wasNotModified(cached: CachedResponse, request: Request): Boolean {
    val etag = cached.headers["etag"] ?: ""
    if (etag.isEmpty()) return false
    val ifNoneMatch = request.headers["if-none-match"] ?: ""
    var start = 0
    while (start <= ifNoneMatch.length) {
        var end = ifNoneMatch.indexOf(',', start)
        if (end < 0) end = ifNoneMatch.length
        val a = Ascii.trimStart(ifNoneMatch, start, end)
        val b = Ascii.trimEnd(ifNoneMatch, a, end)
        if (b - a == etag.length && ifNoneMatch.regionMatches(a, etag, 0, etag.length)) return true
        start = end + 1
    }
    return false
}
