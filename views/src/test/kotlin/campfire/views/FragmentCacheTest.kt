package campfire.views

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

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
}
