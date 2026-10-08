package campfire.kit

import campfire.kit.front.LogSink
import campfire.rails.Cookies
import campfire.rails.RailsJson
import campfire.rails.Secrets
import campfire.rails.Valid
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The HTTP layer's per-process settings (`KitConfig` in
 * `once-campfire-rust/crates/kit/src/app.rs`). The defaults are Rails' with `load_defaults 8.x`;
 * [production] is Campfire's.
 */
data class KitConfig(
    val proxy: ProxyConfig = ProxyConfig(),
    /** `config.force_ssl`: redirect plain HTTP to HTTPS, send HSTS, flag cookies `secure`. */
    val forceSsl: Boolean = false,
    /** The HSTS header value sent with [forceSsl] (`ssl_options = { hsts: { subdomains: true } }`). */
    val hsts: String = "max-age=63072000; includeSubDomains",
    val session: SessionConfig = SessionConfig(),
    /** `forgery_protection_origin_check` (on since `load_defaults 5.0`). */
    val forgeryProtectionOriginCheck: Boolean = true,
    /** `action_dispatch.default_headers` (`load_defaults 7.1`), in the order they're added. */
    val defaultHeaders: List<Pair<String, String>> = RAILS_DEFAULT_HEADERS,
    /** `public/404.html`, `422.html`, `500.html`, ... (`ActionDispatch::PublicExceptions`). */
    val errorPages: ErrorPages = ErrorPages.NONE,
    /** Largest request body accepted; null is unlimited, like Puma. */
    val maxBodyBytes: Int? = null,
) {
    companion object {
        /** `config.action_dispatch.default_headers` from `load_defaults 7.1`. */
        val RAILS_DEFAULT_HEADERS: List<Pair<String, String>> = listOf(
            "x-frame-options" to "SAMEORIGIN",
            "x-xss-protection" to "0",
            "x-content-type-options" to "nosniff",
            "x-permitted-cross-domain-policies" to "none",
            "referrer-policy" to "strict-origin-when-cross-origin",
        )

        /**
         * Campfire's production settings: `assume_ssl` and `force_ssl` unless `DISABLE_SSL` is set
         * (`reference/config/environments/production.rb`). The error pages are the app's to set,
         * as in Rust (usually [ErrorPages.PUBLIC]).
         */
        fun production(disableSsl: Boolean): KitConfig =
            KitConfig(proxy = ProxyConfig(assumeSsl = !disableSsl), forceSsl = !disableSsl)
    }
}

/**
 * Everything a request needs that outlives it (`Kit` in `app.rs`): the configuration, the secrets
 * cookies are signed and encrypted with, the clock (`Time.current`), where errors are logged, and
 * the application's own [state] (database handles etc.), reachable from actions with [Ctx.state].
 */
class Kit(
    val config: KitConfig,
    val secrets: Secrets,
    val clock: campfire.rails.Clock = campfire.rails.SystemClock,
    val state: Any? = null,
    val log: LogSink = LogSink.STDERR,
) {
    /** The cookie jars' crypto, backed by [secrets]. */
    val cookieCrypto: CookieCrypto = SecretsCookieCrypto(secrets)

    /** [clock] as the `java.time.Clock` the cookie jar reads. */
    val javaClock: java.time.Clock = ClockAdapter(clock)

    /** The application state, as the type it was given as. */
    inline fun <reified S> state(): S = state as S

    override fun toString(): String = "Kit(config=$config)"

    /** A [campfire.rails.Clock] seen as a `java.time.Clock` in UTC. */
    private class ClockAdapter(private val clock: campfire.rails.Clock) : java.time.Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): java.time.Clock = this

        override fun instant(): Instant = clock.now()
    }
}

/**
 * [CookieCrypto] over `campfire.rails` ([Cookies]): `cookies.signed` with the HMAC-SHA1 verifier and
 * `cookies.encrypted` with aes-256-gcm, both with purpose `cookie.<name>` and falling back to no
 * purpose on read, as `rails_compat::cookies` does for the Rust port. Encrypted values cross as
 * the JSON text the jar serializes (`cookies_serializer = :json`): written as given, and read back
 * only when it is one JSON document (a Marshal payload, which Rails refuses for cookies, reads as
 * nil).
 */
class SecretsCookieCrypto(private val secrets: Secrets) : CookieCrypto {
    override fun sign(name: String, value: String, expiresAt: Instant?): String = Cookies.sign(secrets, name, value, expiresAt)

    override fun verify(name: String, raw: String, now: Instant): String? = Cookies.verifySigned(secrets, name, raw, now)

    override fun encrypt(name: String, valueJson: String, expiresAt: Instant?): String =
        Cookies.encryptedCookieEncryptor(secrets).encryptAndSign(valueJson, purpose(name), expiresAt)

    override fun decrypt(name: String, raw: String, now: Instant): String? {
        val encryptor = Cookies.encryptedCookieEncryptor(secrets)
        val result = encryptor.decryptAndVerify(raw, purpose(name), now).takeIf { it is Valid }
            ?: encryptor.decryptAndVerify(raw, null, now)
        val dumped = (result as? Valid)?.value as? String ?: return null
        val parsed = RailsJson.parse(dumped.toByteArray(Charsets.UTF_8))
        return if (parsed === RailsJson.Invalid || parsed == null) null else dumped
    }

    private fun purpose(name: String): String = "cookie.$name"
}
