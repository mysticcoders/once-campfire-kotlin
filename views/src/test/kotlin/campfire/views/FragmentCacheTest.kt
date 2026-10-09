package campfire.views

import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The store's bounds and keys, as `crates/views/src/fragment_cache.rs`'s tests check them. */
class FragmentCacheTest {
    private fun fetch(cache: FragmentCache, key: String, size: Int): ByteArray =
        cache.fetch(key, ByteArray::class.java, { it.size }) { ByteArray(size) }

    @Test
    fun `a hit returns the stored value without computing`() {
        val cache = FragmentCache(1 shl 20)
        val first = fetch(cache, "a", 10)
        val second = cache.fetch("a", ByteArray::class.java, { it.size }) { error("computed again") }
        assertSame(first, second)
    }

    @Test
    fun `writes past the limit evict least recently used entries down to three quarters`() {
        val entry = 1000 - 1 - PER_ENTRY_OVERHEAD
        val cache = FragmentCache(10_000)
        for (i in 0 until 10) fetch(cache, "$i", entry)
        cache.get("0", ByteArray::class.java)
        fetch(cache, "x", entry)
        assertEquals(7, cache.size)
        assertEquals(7_000, cache.bytes())
        assertEquals(true, cache.get("0", ByteArray::class.java) != null)
        assertNull(cache.get("1", ByteArray::class.java))
    }

    @Test
    fun `an entry over a quarter of the limit is returned but not kept`() {
        val cache = FragmentCache(10_000)
        fetch(cache, "big", 3_000)
        assertEquals(0, cache.size)
    }

    @Test
    fun `record keys follow fragment_name_with_digest with microsecond versions`() {
        val key = FragmentKeys.record("messages/_message", "messages", 7, "2026-03-02 15:56:01.1234", "presentation-v3")
        assertEquals("views/messages/_message:${FragmentKeys.templateDigest}/messages/7-20260302155601123400/presentation-v3", key)
        assertEquals("views/t:${FragmentKeys.templateDigest}/boosts/9-20260302155601000000", FragmentKeys.record("t", "boosts", 9, "2026-03-02 15:56:01"))
    }

    /** `namespaces_share_a_budget_but_cannot_repopulate_a_later_generation`. */
    @Test
    fun `namespaces share a budget but cannot repopulate a later generation`() {
        val store = FragmentCache(1 shl 20)
        val old = store.namespace(1)
        val current = store.namespace(2)
        val barrier = CyclicBarrier(2)
        var rendered: String? = null
        val renderer = thread {
            rendered = old.fetch("message", String::class.java, { it.length }) {
                barrier.await()
                barrier.await()
                "old creator/body/boost"
            }
        }
        barrier.await()
        assertEquals("fresh creator/body/boost", current.fetch("message", String::class.java, { it.length }) { "fresh creator/body/boost" })
        barrier.await()
        renderer.join()
        assertEquals("old creator/body/boost", rendered)
        assertEquals("fresh creator/body/boost", current.get("message", String::class.java))
        assertEquals(2, store.size)
        assertEquals(store.bytes(), current.bytes())
        assertTrue(store.bytes() <= store.maxBytes)
    }

    /** `many_namespaces_keep_one_lru_and_memory_limit`. */
    @Test
    fun `many namespaces keep one LRU and memory limit`() {
        val store = FragmentCache(4096)
        for (generation in 1L until 100L) {
            store.namespace(generation).fetch("message", String::class.java, { it.length }) { "x".repeat(100) }
            assertTrue(store.bytes() <= store.maxBytes)
        }
        assertTrue(store.size < 100)
        assertNotNull(store.namespace(99).get("message", String::class.java))
        assertNull(store.namespace(1).get("message", String::class.java))
    }

    @Test
    fun `scopes isolate keys within a namespace`() {
        val store = FragmentCache(1 shl 20)
        val one = store.namespace(3).scoped("one")
        one.fetch("message", String::class.java, { it.length }) { "one" }
        assertNull(store.namespace(3).scoped("two").get("message", String::class.java))
        assertNull(store.namespace(3).get("message", String::class.java))
        assertNull(store.get("message", String::class.java))
        assertEquals("one", store.namespace(3).scoped("one").get("message", String::class.java))
    }
}
