package campfire.views

/** `MemoryStore`'s default `size`, 32 MB (`crates/views/src/fragment_cache.rs` `DEFAULT_MAX_BYTES`). */
const val DEFAULT_FRAGMENT_CACHE_BYTES = 32 * 1024 * 1024

/** What an entry costs beyond its key and payload (`MemoryStore::PER_ENTRY_OVERHEAD`). */
const val PER_ENTRY_OVERHEAD = 240

/**
 * The process's fragment store (`crates/views/src/fragment_cache.rs` `FragmentCache`): bounded by
 * bytes the way `ActiveSupport::Cache::MemoryStore` is. Each entry counts its key, its payload
 * and [PER_ENTRY_OVERHEAD]; when a write takes the total past the limit, least recently used
 * entries go until it's back to three quarters of it, and reads count as uses. An entry over a
 * quarter of the limit is returned but not kept.
 */
class FragmentCache(val maxBytes: Int = DEFAULT_FRAGMENT_CACHE_BYTES) {
    private class Entry(val value: Any, val size: Int)

    private val entries = LinkedHashMap<String, Entry>(1024, 0.75f, true)
    private var bytes = 0L

    /** The value [key] holds, if any, counting as a use. */
    @Synchronized
    fun <T : Any> get(key: String, type: Class<T>): T? = entries[key]?.value?.let { if (type.isInstance(it)) type.cast(it) else null }

    /**
     * `Rails.cache.fetch(key) { compute }`: the stored value, or [compute]'s, stored with
     * [sizeOf]'s payload size. When two renders race, the first one stored is what both return.
     */
    fun <T : Any> fetch(key: String, type: Class<T>, sizeOf: (T) -> Int, compute: () -> T): T {
        get(key, type)?.let { return it }
        val value = compute()
        return store(key, type, value, key.length + sizeOf(value) + PER_ENTRY_OVERHEAD)
    }

    @Synchronized
    private fun <T : Any> store(key: String, type: Class<T>, value: T, size: Int): T {
        val existing = entries[key]
        if (existing != null) {
            if (type.isInstance(existing.value)) return type.cast(existing.value)
            entries.remove(key)
            bytes -= existing.size
        }
        if (size > maxBytes / 4) return value
        entries[key] = Entry(value, size)
        bytes += size
        if (bytes > maxBytes) prune(maxBytes / 4 * 3)
        return value
    }

    private fun prune(target: Int) {
        val oldest = entries.entries.iterator()
        while (bytes > target && oldest.hasNext()) {
            bytes -= oldest.next().value.size
            oldest.remove()
        }
    }

    @get:Synchronized
    val size: Int get() = entries.size

    @Synchronized
    fun bytes(): Long = bytes

    @Synchronized
    fun clear() {
        entries.clear()
        bytes = 0
    }
}

/**
 * Fragment keys as `ActionView::Helpers::CacheHelper#fragment_name_with_digest` writes them:
 * `views/<template>:<digest>/<table>/<id>-<cache_version>`, with the version written digit by digit
 * from the stored `updated_at` (`crates/views/src/fragment_cache.rs` `push_record_fragment_key`).
 */
object FragmentKeys {
    private val buffers = ThreadLocal.withInitial { StringBuilder(128) }

    /** The digest of the view sources, computed when the `views` module was built. */
    val templateDigest: String = FragmentKeys::class.java.getResourceAsStream("/campfire/views/template-digest.txt")
        ?.use { it.readAllBytes().toString(Charsets.UTF_8).trim() } ?: "unknown"

    /**
     * A record fragment's key; [updatedAt] is the column's stored text
     * (`YYYY-MM-DD HH:MM:SS[.ffffff]`), [extra] the key's trailing parts (`presentation-v3`).
     */
    fun record(template: String, table: String, id: Long, updatedAt: String, extra: String? = null): String {
        val key = buffers.get()
        key.setLength(0)
        key.append("views/").append(template).append(':').append(templateDigest).append('/').append(table).append('/').append(id).append('-')
        appendCacheVersion(key, updatedAt)
        if (extra != null) key.append('/').append(extra)
        return key.toString()
    }

    /** `Time#to_fs(:usec)`: `%Y%m%d%H%M%S` and six digits of microseconds, from the stored text. */
    fun appendCacheVersion(key: StringBuilder, updatedAt: String) {
        for (i in 0 until minOf(19, updatedAt.length)) {
            val c = updatedAt[i]
            if (c in '0'..'9') key.append(c)
        }
        var digits = 0
        if (updatedAt.length > 20 && updatedAt[19] == '.') {
            var i = 20
            while (i < updatedAt.length && digits < 6 && updatedAt[i] in '0'..'9') {
                key.append(updatedAt[i])
                i++
                digits++
            }
        }
        while (digits < 6) {
            key.append('0')
            digits++
        }
    }
}
