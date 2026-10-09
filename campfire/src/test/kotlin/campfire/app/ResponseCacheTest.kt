package campfire.app

import campfire.db.SqlConnection
import campfire.db.SqliteBackend
import campfire.kit.Body
import campfire.kit.Headers
import campfire.kit.front.CachedResponse
import java.nio.file.Files
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The store's generations and bounds, as `crates/campfire/src/response_cache/tests.rs` checks them. */
class ResponseCacheTest {
    private class Fixture(val writer: SqlConnection, val cache: ResponseCache.Store)

    private fun store(capacity: Long): Fixture {
        val path = Files.createTempDirectory("campfire-response-cache").resolve("cache.sqlite3").toString()
        val writer = SqliteBackend.open(path)
        writer.execute("PRAGMA journal_mode=WAL; CREATE TABLE records (value TEXT); INSERT INTO records VALUES ('before');")
        return Fixture(writer, ResponseCache.Store.open(path, capacity))
    }

    private fun ticket(store: ResponseCache.Store, key: String): ResponseCache.Ticket {
        val generation = store.version()!!
        return ResponseCache.Ticket(store, generation, "$generation/$key")
    }

    private fun response(body: String) = response(body.toByteArray())

    private fun response(body: ByteArray) = CachedResponse(200, Headers(), Body.Bytes(body), emptyList())

    private fun ResponseCache.Store.body(ticket: ResponseCache.Ticket): String? = get(ticket)?.let { String((it.body as Body.Bytes).bytes) }

    @Test
    fun `committed external writes invalidate without timestamp changes`() {
        val (writer, cache) = store(1024).let { it.writer to it.cache }
        val old = ticket(cache, "room")
        cache.put(old, response("before"))
        assertEquals("before", cache.body(old))
        writer.execute("BEGIN; UPDATE records SET value = 'after';")
        assertNotNull(cache.get(old), "an uncommitted transaction is invisible")
        writer.execute("COMMIT;")
        assertNull(cache.get(old))
        assertNull(cache.get(ticket(cache, "room")))
        val current = ticket(cache, "room")
        cache.put(current, response("after"))
        assertEquals("after", cache.body(current))
    }

    @Test
    fun `commit during render cannot admit old authorization or content`() {
        val (writer, cache) = store(1024).let { it.writer to it.cache }
        val beforeAuthentication = ticket(cache, "sidebar")
        writer.execute("UPDATE records SET value = 'revoked'")
        val afterAuthentication = ticket(cache, "sidebar")
        cache.put(beforeAuthentication, response("old private sidebar"))
        assertNull(cache.get(beforeAuthentication))
        assertNull(cache.get(afterAuthentication))
        cache.put(afterAuthentication, response("fresh sidebar"))
        assertEquals("fresh sidebar", cache.body(afterAuthentication))
        cache.put(beforeAuthentication, response("late old private sidebar"))
        assertEquals("fresh sidebar", cache.body(afterAuthentication))
    }

    @Test
    fun `replacement and eviction stay within the budget`() {
        val cache = store(1024).cache
        val room = ticket(cache, "room")
        cache.put(room, response("first"))
        cache.put(room, response("replacement"))
        assertEquals("replacement", cache.body(room))
        val tooLarge = ticket(cache, "too-large")
        cache.put(tooLarge, response(ByteArray(1024)))
        assertNull(cache.get(tooLarge))
        val second = ticket(cache, "second")
        val third = ticket(cache, "third")
        cache.put(second, response(ByteArray(350) { 1 }))
        cache.put(third, response(ByteArray(350) { 2 }))
        assertTrue(listOf(room, second, third).count { cache.get(it) != null } < 3)
    }

    @Test
    fun `disabled response cache keeps zero bytes and still observes fragment changes`() {
        val (writer, cache) = store(0).let { it.writer to it.cache }
        assertFalse(cache.enabled)
        val old = ticket(cache, "room")
        cache.put(old, response("not retained"))
        assertNull(cache.get(old))
        writer.execute("UPDATE records SET value = 'changed'")
        assertNotEquals(old.generation, ticket(cache, "room").generation)
    }

    @Test
    fun `delayed render racing a foreign commit never repopulates the cache`() {
        val (writer, cache) = store(1024).let { it.writer to it.cache }
        val beforeAuthentication = ticket(cache, "private-room")
        val barrier = CyclicBarrier(2)
        val renderer = thread {
            barrier.await()
            barrier.await()
            cache.put(beforeAuthentication, response("private content before revocation"))
        }
        barrier.await()
        writer.execute("UPDATE records SET value = 'revoked'")
        val current = ticket(cache, "private-room")
        cache.put(current, response("current public response"))
        barrier.await()
        renderer.join()
        assertEquals("current public response", cache.body(current))
    }
}
