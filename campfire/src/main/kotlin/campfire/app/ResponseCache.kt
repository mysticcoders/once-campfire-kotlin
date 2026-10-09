package campfire.app

import campfire.db.DataVersion
import campfire.kit.Action
import campfire.kit.Body
import campfire.kit.ChunkedBody
import campfire.kit.Ctx
import campfire.kit.Headers
import campfire.kit.Middleware
import campfire.kit.Response
import campfire.kit.front.CachedResponse
import campfire.kit.front.MemoryCache
import campfire.kit.sha256
import campfire.views.FragmentCache
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicLong

/**
 * Private, authenticated whole responses (`crates/campfire/src/response_cache.rs`), following the
 * C port's versioned response cache.
 *
 * Authentication and room access run before every lookup. A separate SQLite connection observes
 * commits from every writer ([DataVersion]); its generation is captured before authentication
 * ([Snapshot]) and checked again at lookup and admission, so a commit during authentication or
 * rendering can't populate the new generation with an old authorization snapshot. Cookies, flash,
 * HEAD and conditional GET still go through the kit on a hit. Only the completed, bounded
 * identity or gzip representation is kept ([completed]); `Sec-Fetch-Site` forgery protection
 * means these pages carry no CSRF secrets.
 *
 * The Rust port also caches `messages#index`, `users/sidebars#show` and `searches#index`; of its
 * endpoints, this app serves only `rooms#show`.
 */
object ResponseCache {
    /** The largest body admitted (`MAX_BODY`). */
    const val MAX_BODY = 1024 * 1024

    /** The most key material hashed before a request is left uncached (`MAX_KEY_INPUT`). */
    private const val MAX_KEY_INPUT = 8192

    /** How long an entry lives without a commit: time-dependent links and dates still expire (`TTL`). */
    private const val TTL_NANOS = 15_000_000_000L

    /** The endpoints whose responses are cached, as in Rust; only `rooms#show` has an action here. */
    private val ENDPOINTS = setOf("rooms#show", "messages#index", "users/sidebars#show", "searches#index")

    private val HEX: HexFormat = HexFormat.of()

    /** The response headers each request gets afresh, dropped from what's kept. */
    private val FRESH_HEADERS = listOf("set-cookie", "date", "x-request-id", "x-runtime")

    /**
     * The completed-response store and its database observer (`Store`). The observer's lock also
     * covers lookups and admissions, so that observing the generation and using it are one step.
     */
    class Store private constructor(private val observer: DataVersion, capacity: Long) {
        private val responses = MemoryCache(capacity, MAX_BODY * 2L)
        private var dataVersion = observer.read()
        private var generation = 0L

        /** Whether responses are kept: `CAMPFIRE_RESPONSE_CACHE_MB` isn't zero. */
        val enabled = capacity != 0L

        /** Cache hits served, for tests (Rust counts them only in its test build). */
        internal val hits = AtomicLong()

        /** The current database generation, or null when SQLite can't say (both caches are then bypassed). */
        @Synchronized
        fun version(): Long? = try {
            observe()
        } catch (_: Exception) {
            null
        }

        /** `Observer::version`: the generation, moved on when `data_version` changed since the last look. */
        private fun observe(): Long {
            val current = observer.read()
            if (current != dataVersion) {
                generation++
                dataVersion = current
            }
            return generation
        }

        /** The response kept for [ticket], if its generation is still current. */
        @Synchronized
        internal fun get(ticket: Ticket): CachedResponse? {
            if (version() != ticket.generation) return null
            val response = responses.get(ticket.key, System.nanoTime()) ?: return null
            hits.incrementAndGet()
            return response
        }

        /**
         * Keeps [response] for [ticket] unless a commit has moved the generation on since the
         * ticket's: an old render is never assigned the next generation, and a later commit makes
         * this one unreachable on the next lookup.
         */
        @Synchronized
        internal fun put(ticket: Ticket, response: CachedResponse) {
            if (version() != ticket.generation) return
            val now = System.nanoTime()
            responses.set(ticket.key, response, now + TTL_NANOS, now)
        }

        companion object {
            /** `Store::open`: the observer's connection on [path], and a store of [capacity] bytes. */
            fun open(path: String, capacity: Long): Store = Store(DataVersion(path), capacity)
        }
    }

    /**
     * What a request captured before authentication (`Snapshot`): the generation, and the fragment
     * store's view for that generation and the request's origin (opengraph embeds filter links
     * against the request's host, so their fragments are isolated by origin as the finished
     * response is). Without a generation both caches are bypassed, rather than falling back to an
     * old namespace.
     */
    class Snapshot(val generation: Long?, val fragments: FragmentCache) {
        companion object {
            /** `Snapshot::capture`. */
            fun capture(c: Ctx): Snapshot {
                val app = c.state<App>()
                val generation = app.responseCache.version()
                val fragments = if (generation != null) app.fragments.namespace(generation).scoped(origin(c.urlFor(""))) else FragmentCache(0)
                return Snapshot(generation, fragments)
            }
        }
    }

    /** The last origin hashed and its digest, since nearly every request comes from the same one. */
    @Volatile
    private var lastOrigin: Pair<String, String> = "" to ""

    /** The hex SHA-256 of [base], the origin's fragment scope. */
    private fun origin(base: String): String {
        val last = lastOrigin
        if (last.first == base) return last.second
        return HEX.formatHex(sha256(base.toByteArray(Charsets.UTF_8))).also { lastOrigin = base to it }
    }

    /** The request's pre-authentication fragment view (`fragments`), captured now if routing didn't. */
    fun fragments(c: Ctx): FragmentCache = (c.current<Snapshot>() ?: Snapshot.capture(c)).fragments

    /** A request [begin] found cacheable, with the generation its snapshot saw (`Round`). */
    private class Round(val generation: Long, val endpoint: String)

    /** A lookup that missed: where its response may be kept, if it completes (`Ticket`). */
    internal class Ticket(val store: Store, val generation: Long, val key: String)

    /**
     * `dispatch_with_fragment_cache` in `crates/campfire/src/app.rs`: [router] with the request's
     * [Snapshot] captured before routing and authentication.
     */
    fun snapshotting(router: Action): Action = Action { c ->
        c.setCurrent(Snapshot.capture(c))
        router.call(c)
    }

    /**
     * [action] as `controllers::dispatch` runs it for [endpoint]: [begin] before it, and [prepare]
     * on the response it returns (a halt or an error isn't prepared).
     */
    fun around(endpoint: String, action: Action): Action = Action { c ->
        begin(c, endpoint)
        action.call(c).also { prepare(c, it) }
    }

    /** `begin`: before authentication, so that a session refresh or revocation during it also prevents a hit. */
    fun begin(c: Ctx, endpoint: String) {
        if (endpoint !in ENDPOINTS || !eligibleRequest(c)) return
        val generation = c.current<Snapshot>()?.generation ?: return
        if (c.state<App>().responseCache.enabled) c.setCurrent(Round(generation, endpoint))
    }

    /**
     * `lookup`: after the action's fresh authentication, permission checks and request side
     * effects (`remember_last_room_visited`), the kept response for this request, or null. A miss
     * leaves a [Ticket] for [prepare]; even if a concurrent commit made the lookup miss, admission
     * checks the original generation again.
     */
    fun lookup(c: Ctx): Response? {
        val round = c.current<Round>() ?: return null
        if (authenticatedBy(c) != AuthenticatedBy.Session || !c.flash().isEmpty()) return null
        val session = c.current<CurrentSession>()?.session ?: return null
        val user = currentUser(c) ?: return null
        val store = c.state<App>().responseCache
        val key = requestKey(c, round, user.id, session.id) ?: return null
        val ticket = Ticket(store, round.generation, key)
        val cached = store.get(ticket)
        if (cached != null) return Response(cached.status, cached.headers.copy(), cached.body)
        c.setCurrent(ticket)
        return null
    }

    /**
     * `prepare`: marks a GET's 200 response for [completed] to keep, when it has no flash, a body
     * in memory of at most [MAX_BODY], and cacheable headers. Only a request with a [Ticket] reads
     * the flash here.
     */
    fun prepare(c: Ctx, response: Response) {
        val ticket = c.current<Ticket>() ?: return
        if (c.request.method != "GET" || response.status != 200 || !c.flash().isEmpty()) return
        val size = when (val body = response.body) {
            is Body.Bytes -> body.bytes.size
            is Body.Page -> body.parts.length
            else -> return
        }
        if (size <= MAX_BODY && cacheableHeaders(response.headers)) response.putExtension(ticket)
    }

    /**
     * `completed`, outside the kit and `Rack::Deflater`: keeps exactly the finished representation
     * (identity or gzip) of a marked response. Its body is collected into one buffer and sent
     * with a `Content-Length`, as Rust's collected body is, and the copy kept leaves out the
     * headers each response gets afresh.
     */
    val completed = Middleware { request, next ->
        val response = next.handle(request)
        val ticket = response.extensions.remove(Ticket::class.java) as Ticket?
        if (ticket == null || response.status != 200 || !cacheableHeaders(response.headers)) return@Middleware response
        val body = Body.Bytes(collect(response.body))
        response.headers["content-length"] = body.bytes.size.toString()
        response.body = body
        response.extensions.remove(ChunkedBody::class.java)
        val headers = response.headers.copy()
        for (name in FRESH_HEADERS) headers.remove(name)
        ticket.store.put(ticket, CachedResponse(response.status, headers, body, emptyList()))
        response
    }

    /** The body as one buffer; a single-buffer body is used as it is. */
    private fun collect(body: Body): ByteArray {
        if (body is Body.Bytes) return body.bytes
        val chunks = body.chunks()
        val out = ByteArray(chunks.sumOf { it.size })
        var at = 0
        for (chunk in chunks) {
            System.arraycopy(chunk, 0, out, at, chunk.size)
            at += chunk.size
        }
        return out
    }

    /** `eligible_request`: a plain GET or HEAD, with no body, range, upgrade or cache bypass. */
    private fun eligibleRequest(c: Ctx): Boolean {
        val request = c.request
        return (request.method == "GET" || request.method == "HEAD") &&
            request.originalMethod == request.method &&
            request.rawPost.isEmpty() &&
            "range" !in request.headers &&
            "upgrade" !in request.headers &&
            !directives(request.headers, NO_CACHE_OR_STORE) &&
            request.header("pragma")?.equals("no-cache", ignoreCase = true) != true
    }

    private val NO_CACHE_OR_STORE = listOf("no-cache", "no-store")
    private val NO_STORE_OR_TRANSFORM = listOf("no-store", "no-transform")

    /** `cacheable_headers`: no `no-store` or `no-transform` in the response's `Cache-Control`. */
    private fun cacheableHeaders(headers: Headers): Boolean = !directives(headers, NO_STORE_OR_TRANSFORM)

    /**
     * Whether any `Cache-Control` value (of those that are visible ASCII, as `to_str` takes them)
     * has a directive named one of [rejected], ignoring ASCII case.
     */
    private fun directives(headers: Headers, rejected: List<String>): Boolean = headers.all("cache-control").any { value ->
        visible(value) && value.split(',').any { directive ->
            val name = directive.trim().substringBefore('=')
            rejected.any { name.equals(it, ignoreCase = true) }
        }
    }

    private fun visible(value: String): Boolean = value.all { it == '\t' || it in ' '..'~' }

    /**
     * `request_key`: the SHA-256 of length-prefixed fields: the generation, endpoint, user,
     * session, request target and origin, then every request header (sorted by name, values in
     * order) except validators and request tracing, so every representation and session variant
     * (Cookie, User-Agent, Turbo-Frame, Accept, Accept-Encoding, proxy and origin headers) has its
     * own key. Null when there's more than [MAX_KEY_INPUT] bytes of it. Header values are hashed
     * as the bytes they arrived as (ISO-8859-1).
     */
    private fun requestKey(c: Ctx, round: Round, user: Long, session: Long): String? {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        var length = 0
        val prefix = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        fun field(value: ByteArray) {
            length += value.size + 8
            prefix.clear()
            digest.update(prefix.putLong(value.size.toLong()).array())
            digest.update(value)
        }
        fun number(value: Long) = field(java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(value).array())
        number(round.generation)
        field(round.endpoint.toByteArray(Charsets.UTF_8))
        number(user)
        number(session)
        field(c.request.request.target.toByteArray(Charsets.ISO_8859_1))
        field(c.urlFor("").toByteArray(Charsets.UTF_8))
        val headers = c.request.headers
        val names = headers.map { it.first }.distinct().sorted()
        for (name in names) {
            if (name == "if-none-match" || name == "if-modified-since" || name == "x-request-id" || name == "x-request-start") continue
            field(name.toByteArray(Charsets.ISO_8859_1))
            for (value in headers.all(name)) field(value.toByteArray(Charsets.ISO_8859_1))
            field(EMPTY)
        }
        if (length > MAX_KEY_INPUT) return null
        return HEX.formatHex(digest.digest())
    }

    private val EMPTY = ByteArray(0)
}
