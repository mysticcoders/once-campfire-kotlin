package campfire.app

import kotlin.io.path.Path

/** The environment the app reads (`crates/campfire/src/config.rs`). */
class Config(private val env: (String) -> String? = { System.getenv(it) }) {
    val secretKeyBase: String = value("SECRET_KEY_BASE") ?: error("SECRET_KEY_BASE is required")
    val databasePath: String = value("CAMPFIRE_DATABASE_PATH")
        ?: Path(value("CAMPFIRE_STORAGE_PATH") ?: "storage", "db", "${value("RAILS_ENV") ?: "production"}.sqlite3").toString()
    val readers: Int = (value("RAILS_MAX_THREADS") ?: "5").toInt().coerceAtLeast(1)
    val workers: Int = (value("KOTLIN_WORKERS") ?: Runtime.getRuntime().availableProcessors().toString()).toInt()
    val disableSsl: Boolean = !value("DISABLE_SSL").isNullOrBlank()
    val appVersion: String = value("APP_VERSION") ?: value("GIT_REVISION") ?: "0"
    val gitRevision: String? = value("GIT_REVISION")
    val vapidPublicKey: String? = value("VAPID_PUBLIC_KEY")?.takeIf { it.isNotBlank() && !value("VAPID_PRIVATE_KEY").isNullOrBlank() }
    val fragmentCacheBytes: Int = (value("CAMPFIRE_FRAGMENT_CACHE_MB")?.toIntOrNull() ?: 32) * 1024 * 1024

    /**
     * `CAMPFIRE_RESPONSE_CACHE_MB`: the limit of the store of completed, authenticated room page
     * responses ([ResponseCache]), 64 MB by default; zero disables it. A value that isn't a whole
     * number fails at boot, as Rust's `number` does.
     */
    val responseCacheBytes: Long = megabytes("CAMPFIRE_RESPONSE_CACHE_MB", 64)

    private fun value(name: String): String? = env(name)?.takeIf { it.isNotEmpty() }

    /**
     * `number(name, default)` in `config.rs` (a trimmed `usize`), times a megabyte, saturating as
     * `saturating_mul` does.
     */
    private fun megabytes(name: String, default: Long): Long {
        val raw = value(name) ?: return default shl 20
        val digits = raw.trim().removePrefix("+")
        val number = digits.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }?.toBigInteger()
        if (number == null || number.bitLength() > 64) throw IllegalArgumentException("$name=\"$raw\" is not a number")
        return number.shiftLeft(20).min(Long.MAX_VALUE.toBigInteger()).toLong()
    }
}
