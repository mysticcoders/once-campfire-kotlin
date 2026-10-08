package campfire.kit.front

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.file.Path
import java.time.Duration

private const val KB = 1024L
private const val MB = 1024 * KB

/** Let's Encrypt's production directory (`acme.LetsEncryptURL`). */
const val LETS_ENCRYPT_URL = "https://acme-v02.api.letsencrypt.org/directory"

/**
 * Thruster's configuration (`internal/config.go`), as `crates/kit/src/front/config.rs` reads it:
 * every setting comes from `THRUSTER_<NAME>`, falling back to `<NAME>`, and a value that doesn't
 * parse falls back to the default.
 *
 * - [targetPort] (`TARGET_PORT`): where Thruster's upstream listened; the app still listens there.
 * - [targetBind] (`TARGET_BIND`, not Thruster's): the address that listener binds, loopback by default.
 * - [cacheSize] / [maxCacheItemSize]: the response cache's capacity and largest item, in bytes.
 * - [maxRequestBody]: 0 for no limit.
 * - [tlsDomains] (`TLS_DOMAIN`, comma-separated): setting it turns on TLS (not served by this port yet).
 * - [forwardHeaders] (`FORWARD_HEADERS`): trust the client's `X-Forwarded-*`; by default only without TLS.
 */
class FrontConfig(
    val targetPort: Int,
    val targetBind: InetAddress,
    val cacheSize: Long,
    val maxCacheItemSize: Long,
    val gzipCompressionEnabled: Boolean,
    val gzipCompressionDisableOnAuth: Boolean,
    val gzipCompressionJitter: Long,
    val maxRequestBody: Long,
    val tlsDomains: List<String>,
    val acmeDirectoryUrl: String,
    val eabKid: String,
    val eabHmacKey: String,
    val storagePath: Path,
    val httpPort: Int,
    val httpsPort: Int,
    val httpIdleTimeout: Duration,
    val httpReadTimeout: Duration,
    val httpWriteTimeout: Duration,
    val h2cEnabled: Boolean,
    val forwardHeaders: Boolean,
    val debug: Boolean,
    val logRequests: Boolean,
) {
    /** Whether TLS_DOMAIN named any domain. */
    val hasTls: Boolean get() = tlsDomains.isNotEmpty()

    companion object {
        /** The configuration from the process environment (`FrontConfig::from_env`). */
        fun fromEnv(): FrontConfig = fromLookup { System.getenv(it) }

        /** `NewConfig` over any variable lookup (`FrontConfig::from_lookup`; tests pass a map). */
        fun fromLookup(get: (String) -> String?): FrontConfig {
            val find = { key: String -> get("THRUSTER_$key") ?: get(key) }
            val string = { key: String, default: String -> find(key) ?: default }
            val int = { key: String, default: Long -> find(key)?.let(::parseInt) ?: default }
            val port = { key: String, default: Int -> int(key, default.toLong()).let { if (it in 0..65535) it.toInt() else default } }
            val seconds = { key: String, default: Long ->
                Duration.ofSeconds(find(key)?.let(::parseInt)?.coerceAtLeast(0) ?: default)
            }
            val boolean = { key: String, default: Boolean -> find(key)?.let(::parseBool) ?: default }
            val tlsDomains = find("TLS_DOMAIN")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            return FrontConfig(
                targetPort = port("TARGET_PORT", 3000),
                targetBind = find("TARGET_BIND")?.let(::parseIp) ?: LOOPBACK_V4,
                cacheSize = int("CACHE_SIZE", 64 * MB),
                maxCacheItemSize = int("MAX_CACHE_ITEM_SIZE", MB),
                gzipCompressionEnabled = boolean("GZIP_COMPRESSION_ENABLED", true),
                gzipCompressionDisableOnAuth = boolean("GZIP_COMPRESSION_DISABLE_ON_AUTH", false),
                gzipCompressionJitter = int("GZIP_COMPRESSION_JITTER", 32),
                maxRequestBody = int("MAX_REQUEST_BODY", 0),
                tlsDomains = tlsDomains,
                acmeDirectoryUrl = string("ACME_DIRECTORY", LETS_ENCRYPT_URL),
                eabKid = string("EAB_KID", ""),
                eabHmacKey = string("EAB_HMAC_KEY", ""),
                storagePath = Path.of(string("STORAGE_PATH", "./storage/thruster")),
                httpPort = port("HTTP_PORT", 80),
                httpsPort = port("HTTPS_PORT", 443),
                httpIdleTimeout = seconds("HTTP_IDLE_TIMEOUT", 60),
                httpReadTimeout = seconds("HTTP_READ_TIMEOUT", 30),
                httpWriteTimeout = seconds("HTTP_WRITE_TIMEOUT", 30),
                h2cEnabled = boolean("H2C_ENABLED", false),
                forwardHeaders = boolean("FORWARD_HEADERS", tlsDomains.isEmpty()),
                debug = boolean("DEBUG", false),
                logRequests = boolean("LOG_REQUESTS", true),
            )
        }

        private val LOOPBACK_V4: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

        /** Rust's `i64::from_str` (as `strconv.Atoi`): an optional sign and decimal digits, nothing else. */
        internal fun parseInt(value: String): Long? = value.toLongOrNull()

        /** Go's `strconv.ParseBool`. */
        internal fun parseBool(value: String): Boolean? = when (value) {
            "1", "t", "T", "TRUE", "true", "True" -> true
            "0", "f", "F", "FALSE", "false", "False" -> false
            else -> null
        }

        /**
         * Rust's `IpAddr::from_str`: a dotted quad of decimal octets without leading zeros, or an
         * IPv6 literal without brackets or a zone. Never a name lookup.
         */
        internal fun parseIp(value: String): InetAddress? {
            if (value.isEmpty()) return null
            if (':' in value) {
                if (value.any { !(it.isLetterOrDigit() && it.code < 128) && it != ':' && it != '.' }) return null
                return try {
                    InetAddress.getByName(value).takeIf { it is Inet6Address || value.startsWith("::ffff:", ignoreCase = true) }
                } catch (_: Exception) {
                    null
                }
            }
            val parts = value.split('.')
            if (parts.size != 4) return null
            val bytes = ByteArray(4)
            for ((i, part) in parts.withIndex()) {
                if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' }) return null
                if (part.length > 1 && part[0] == '0') return null
                val n = part.toInt()
                if (n > 255) return null
                bytes[i] = n.toByte()
            }
            return Inet4Address.getByAddress(bytes)
        }
    }
}
