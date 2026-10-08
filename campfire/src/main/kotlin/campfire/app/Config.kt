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

    private fun value(name: String): String? = env(name)?.takeIf { it.isNotEmpty() }
}
