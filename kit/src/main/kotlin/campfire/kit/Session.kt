package campfire.kit

import campfire.rails.RailsJson
import java.security.SecureRandom
import java.time.Instant

/**
 * The session cookie's settings (`SessionConfig` in `once-campfire-rust/crates/kit/src/session.rs`),
 * as `reference/config/initializers/session_store.rb` configures it: key `_campfire_session`,
 * encrypted, `expire_after: 20.years`, HttpOnly.
 */
data class SessionConfig(
    val key: String = Session.KEY,
    val expireAfterYears: Long? = Session.EXPIRE_AFTER_YEARS,
    val httponly: Boolean = true,
)

/**
 * The Rails cookie-store session (`ActionDispatch::Session::CookieStore` over
 * `Rack::Session::Abstract::PersistedSecure`; `Session` in `session.rs`).
 *
 * Loading is lazy. Unlike Rails, the cookie is only written when the session changed during the
 * request, and deleted when that left it empty: Rails' `commit_session` rewrites it on every
 * request that loads or carries one (`expire_after` forces the update), re-encrypting it with a
 * fresh IV. Without CSRF tokens the session holds only the flash and a return-to URL, so an
 * unchanged session needs no cookie traffic and an empty one no cookie. Cookies Rails wrote are
 * read the same way.
 *
 * Values are JSON values as [RailsJson] reads them. The data keeps its keys in order as Rust's
 * `serde_json::Map` with `preserve_order` does, including [remove]'s `swap_remove` (the last
 * key takes the removed one's place), so the JSON written is the same.
 */
class Session(private val config: SessionConfig = SessionConfig()) {
    private var loaded = false
    private var changed = false
    private val keys = ArrayList<String>(4)
    private val values = ArrayList<Any?>(4)
    private var cookieData: List<Pair<String, Any?>>? = null

    val isLoaded: Boolean get() = loaded

    /** Loads from the cookie if not yet loaded (`load_for_read!`/`load_for_write!`), adding a session id when it has none. */
    fun load(jar: CookieJar): Session {
        if (!loaded) {
            keys.clear()
            values.clear()
            for ((k, v) in cookieData(jar)) put(k, v)
            val sid = indexOf("session_id")
            if (sid < 0 || values[sid] == null) put("session_id", generateSid())
            loaded = true
        }
        return this
    }

    /** The cookie's decoded contents, read once (`action_dispatch.request.unsigned_session_cookie`). */
    private fun cookieData(jar: CookieJar): List<Pair<String, Any?>> {
        cookieData?.let { return it }
        val json = jar.encrypted(config.key)
        val parsed = json?.let { RailsJson.parse(it.toByteArray(Charsets.UTF_8)) }
        val data = if (parsed is Map<*, *>) parsed.entries.map { (k, v) -> k.toString() to v } else emptyList()
        cookieData = data
        return data
    }

    private fun assertLoaded() {
        assert(loaded) { "session used before load(); go through Ctx.session()" }
    }

    private fun indexOf(key: String): Int {
        for (i in keys.indices) if (keys[i] == key) return i
        return -1
    }

    private fun put(key: String, value: Any?) {
        val i = indexOf(key)
        if (i >= 0) values[i] = value else {
            keys.add(key)
            values.add(value)
        }
    }

    /** The session id (`session.id`). */
    val id: String?
        get() {
            assertLoaded()
            return get("session_id") as? String
        }

    /** `session[key]`; a stored JSON null reads as absent. */
    operator fun get(key: String): Any? {
        assertLoaded()
        val i = indexOf(key)
        return if (i < 0) null else values[i]
    }

    fun getStr(key: String): String? = get(key) as? String

    fun containsKey(key: String): Boolean {
        assertLoaded()
        return indexOf(key) >= 0
    }

    /** `session[key] = value`, marking the session changed only when the value is new. */
    operator fun set(key: String, value: Any?) {
        assertLoaded()
        val i = indexOf(key)
        if (i >= 0 && values[i] == value) return
        if (i >= 0) values[i] = value else {
            keys.add(key)
            values.add(value)
        }
        changed = true
    }

    /** `session.delete(key)`: the removed value, the last key moving into its place. */
    fun remove(key: String): Any? {
        assertLoaded()
        val i = indexOf(key)
        if (i < 0) return null
        val removed = values[i]
        val last = keys.size - 1
        if (i != last) {
            keys[i] = keys[last]
            values[i] = values[last]
        }
        keys.removeAt(last)
        values.removeAt(last)
        changed = true
        return removed
    }

    /** `reset_session`: drop everything and start a new session id. */
    fun reset() {
        keys.clear()
        values.clear()
        put("session_id", generateSid())
        cookieData = listOf(keys[0] to values[0])
        loaded = true
        changed = true
    }

    /**
     * Writes the cookie into [jar] if the session changed, encrypted with a fresh IV and expiring
     * [SessionConfig.expireAfterYears] after [now]; or deletes it if the change left nothing but
     * the session id. Throws [KitError.CookieOverflow].
     */
    fun commit(jar: CookieJar, now: Instant) {
        if (!changed) return
        val data = LinkedHashMap<String, Any?>(keys.size * 2)
        for (i in keys.indices) if (values[i] != null) data[keys[i]] = values[i]
        if (data.keys.all { it == "session_id" }) {
            jar.delete(config.key)
            return
        }
        var cookie = Cookie("", httponly = config.httponly)
        config.expireAfterYears?.let { cookie = cookie.expires(CookieJar.yearsFrom(now, it)) }
        jar.setEncrypted(config.key, RailsJson.encode(data), cookie)
    }

    override fun toString(): String = "Session(loaded=$loaded, changed=$changed, keys=$keys)"

    companion object {
        const val KEY = "_campfire_session"
        const val EXPIRE_AFTER_YEARS = 20L

        private val RANDOM = SecureRandom()
        private const val HEX = "0123456789abcdef"

        /** `ActionDispatch::Session::Compatibility#generate_sid`: `SecureRandom.hex(16)`. */
        fun generateSid(): String {
            val bytes = ByteArray(16)
            RANDOM.nextBytes(bytes)
            val out = CharArray(32)
            for (i in bytes.indices) {
                val b = bytes[i].toInt() and 0xff
                out[i * 2] = HEX[b shr 4]
                out[i * 2 + 1] = HEX[b and 0xf]
            }
            return String(out)
        }
    }
}

/**
 * `ActionDispatch::Flash::FlashHash` (`Flash` in `session.rs`), stored in the session under
 * `"flash"` as `{ "discard" => [], "flashes" => { ... } }`: what a request sets is shown on the
 * next one, then swept; `flash.now` only on this one.
 */
class Flash private constructor(
    private val flashes: ArrayList<Pair<String, Any?>>,
    private val discard: ArrayList<String>,
) {
    constructor() : this(ArrayList(2), ArrayList(2))

    /** `FlashHash#to_session_value`: null when nothing survives. */
    fun toSessionValue(): Map<String, Any?>? {
        val keep = LinkedHashMap<String, Any?>()
        for ((k, v) in flashes) if (k !in discard) keep[k] = v
        if (keep.isEmpty()) return null
        val value = LinkedHashMap<String, Any?>(4)
        value["discard"] = emptyList<Any?>()
        value["flashes"] = keep
        return value
    }

    operator fun get(key: String): Any? = flashes.firstOrNull { it.first == key }?.second

    fun getStr(key: String): String? = get(key) as? String

    /** `flash[key] = value`: shown on the next request. */
    operator fun set(key: String, value: Any?) {
        discard.remove(key)
        val i = flashes.indexOfFirst { it.first == key }
        if (i >= 0) flashes[i] = key to value else flashes.add(key to value)
    }

    /** `flash.now[key] = value`: shown on this request only. */
    fun now(key: String, value: Any?) {
        set(key, value)
        discard(key)
    }

    /** `flash.keep(key)`, or `flash.keep` for every key when [key] is null. */
    fun keep(key: String? = null) {
        if (key == null) discard.clear() else discard.remove(key)
    }

    /** `flash.discard(key)`, or `flash.discard` for every key when [key] is null. */
    fun discard(key: String? = null) {
        if (key != null) {
            if (key !in discard) discard.add(key)
            return
        }
        for ((k, _) in flashes) if (k !in discard) discard.add(k)
    }

    fun delete(key: String) {
        discard.remove(key)
        flashes.removeAll { it.first == key }
    }

    fun isEmpty(): Boolean = flashes.isEmpty()

    val keys: List<String> get() = flashes.map { it.first }

    val notice: String? get() = getStr("notice")
    val alert: String? get() = getStr("alert")

    fun setNotice(message: Any?) = set("notice", message)

    fun setAlert(message: Any?) = set("alert", message)

    override fun equals(other: Any?): Boolean = other is Flash && other.flashes == flashes && other.discard == discard

    override fun hashCode(): Int = flashes.hashCode() * 31 + discard.hashCode()

    override fun toString(): String = "Flash(flashes=$flashes, discard=$discard)"

    companion object {
        /**
         * `FlashHash.from_session_value`: everything loaded is marked for discard at the end of
         * this request, minus what the previous request had already discarded.
         */
        fun fromSessionValue(value: Any?): Flash {
            if (value !is Map<*, *>) return Flash()
            val discarded = (value["discard"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
            val flashes = ArrayList<Pair<String, Any?>>(2)
            (value["flashes"] as? Map<*, *>)?.forEach { (k, v) ->
                val key = k.toString()
                if (key !in discarded) flashes.add(key to v)
            }
            return Flash(flashes, flashes.mapTo(ArrayList(flashes.size)) { it.first })
        }
    }
}

/**
 * Rust's `{:?}` of a `serde_json::Value` (`Option<&Value>` adds `Some(...)`/`None`), which the
 * flash's ETag part is built from (`combine_etags` in `ctx.rs`): `String("✓")`, `Number(1)`,
 * `Bool(true)`, `Null`, `Array [..]`, `Object {..}`.
 */
internal object RustDebug {
    fun option(value: Any?, present: Boolean): String = if (present) "Some(${value(value)})" else "None"

    fun value(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    private fun write(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("Null")
            is Boolean -> out.append("Bool(").append(value).append(')')
            is Double -> out.append("Number(").append(ryuFormat(value)).append(')')
            is Number -> out.append("Number(").append(value.toString()).append(')')
            is String -> out.append("String(").append(str(value)).append(')')
            is List<*> -> {
                out.append("Array [")
                value.forEachIndexed { i, v ->
                    if (i > 0) out.append(", ")
                    write(out, v)
                }
                out.append(']')
            }
            is Map<*, *> -> {
                out.append("Object {")
                var first = true
                for ((k, v) in value) {
                    if (!first) out.append(", ")
                    first = false
                    out.append(str(k.toString())).append(": ")
                    write(out, v)
                }
                out.append('}')
            }
            else -> out.append(str(value.toString()))
        }
    }

    /**
     * `<str as Debug>`: quoted, with `\"`, `\\`, `\n`, `\r`, `\t` and `\0` escaped and other
     * control characters as `\u{..}`. (Rust also escapes unassigned and grapheme-extending
     * characters; those are left as they are here.)
     */
    fun str(s: String): String {
        val out = StringBuilder(s.length + 2).append('"')
        for (c in s) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c == '\u0000' -> out.append("\\0")
                Character.isISOControl(c) -> out.append("\\u{").append(Integer.toHexString(c.code)).append('}')
                else -> out.append(c)
            }
        }
        return out.append('"').toString()
    }
}
