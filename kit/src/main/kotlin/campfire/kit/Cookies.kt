package campfire.kit

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A cookie's `SameSite` attribute. */
enum class SameSite { LAX, STRICT, NONE }

/**
 * A cookie to set, with Rails' option names (`once-campfire-rust/crates/kit/src/cookies.rs`,
 * `Cookie`). The defaults are Rails': `path=/` and `samesite=lax`
 * (`cookies_same_site_protection = :lax` from `load_defaults 6.1`).
 */
data class Cookie(
    val value: String,
    val path: String = "/",
    val domain: String? = null,
    val expires: Instant? = null,
    val permanent: Boolean = false,
    val secure: Boolean = false,
    val httponly: Boolean = false,
    val sameSite: SameSite? = SameSite.LAX,
    val partitioned: Boolean = false,
) {
    /** `cookies.permanent[...]`: expires 20 calendar years from now. */
    fun permanent(): Cookie = copy(permanent = true)

    fun expires(at: Instant): Cookie = copy(expires = at)

    fun httponly(): Cookie = copy(httponly = true)

    fun secure(): Cookie = copy(secure = true)

    fun sameSite(sameSite: SameSite?): Cookie = copy(sameSite = sameSite)

    fun path(path: String): Cookie = copy(path = path)

    fun domain(domain: String): Cookie = copy(domain = domain)
}

/** Options for `cookies.delete(name, options)` (`cookies.rs`, `DeleteOptions`). */
data class DeleteOptions(
    val path: String = "/",
    val domain: String? = null,
    val sameSite: SameSite? = SameSite.LAX,
)

/**
 * The signed and encrypted cookie contracts the jar delegates to; the Rust port's
 * `rails_compat::cookies::{sign, verify_signed, encrypt, decrypt}` with the app's secrets bound in.
 * Encrypted values cross as JSON text, so the implementation owns the JSON (de)serialization of
 * Rails' envelope.
 */
interface CookieCrypto {
    /** The raw value for `cookies.signed[name] = value` (expiring at [expiresAt] when given). */
    fun sign(name: String, value: String, expiresAt: Instant?): String

    /** `cookies.signed[name]` from the raw cookie: the string, or null wherever Rails returns nil. */
    fun verify(name: String, raw: String, now: Instant): String?

    /** The raw value for `cookies.encrypted[name] = value`, [valueJson] being the value as JSON text. */
    fun encrypt(name: String, valueJson: String, expiresAt: Instant?): String

    /** `cookies.encrypted[name]` from the raw cookie, as JSON text; null wherever Rails returns nil. */
    fun decrypt(name: String, raw: String, now: Instant): String?
}

/**
 * `ActionDispatch::Cookies::CookieJar` with its plain, permanent, signed and encrypted jars
 * (`once-campfire-rust/crates/kit/src/cookies.rs`). As in `action_dispatch/middleware/cookies.rb`:
 * setting a cookie only emits `Set-Cookie` when the value changed or an expiry was given;
 * `permanent` means an `expires` 20 calendar years out, embedded in signed/encrypted metadata;
 * deleting only emits a header when the cookie was present; secure cookies are dropped on
 * plain-HTTP requests; signed/encrypted values over 4096 bytes (name included) raise
 * [KitError.CookieOverflow]. Header formatting is `Rack::Utils.set_cookie_header` (Rack 3.2).
 */
class CookieJar private constructor(
    private val cookies: LinkedHashMap<String, String>,
    private val crypto: CookieCrypto,
    private val clock: Clock,
) {
    private val setCookies = LinkedHashMap<String, Cookie>()
    private val deleteCookies = LinkedHashMap<String, DeleteOptions>()

    /** `cookies[name]`. */
    operator fun get(name: String): String? = cookies[name]

    fun contains(name: String): Boolean = cookies.containsKey(name)

    /** `cookies.signed[name]`. */
    fun signed(name: String): String? {
        val raw = cookies[name] ?: return null
        return crypto.verify(name, raw, clock.instant())
    }

    /** `cookies.encrypted[name]`, as JSON text. */
    fun encrypted(name: String): String? {
        val raw = cookies[name] ?: return null
        return crypto.decrypt(name, raw, clock.instant())
    }

    /** `cookies[name] = value` (or `cookies.permanent[name] = ...` with [Cookie.permanent]). */
    fun set(name: String, cookie: Cookie) {
        writeValue(name, resolveExpiry(cookie))
    }

    /** `cookies[name] = value` with Rails' default options. */
    operator fun set(name: String, value: String) = set(name, Cookie(value))

    /** `cookies.signed[name] = value`. */
    fun setSigned(name: String, cookie: Cookie) {
        val resolved = resolveExpiry(cookie)
        val signed = resolved.copy(value = crypto.sign(name, resolved.value, resolved.expires))
        checkForOverflow(name, signed.value)
        writeValue(name, signed)
    }

    /** `cookies.encrypted[name] = value`; [cookie]'s value is ignored in favor of [valueJson]. */
    fun setEncrypted(name: String, valueJson: String, cookie: Cookie = Cookie("")) {
        val resolved = resolveExpiry(cookie)
        val encrypted = resolved.copy(value = crypto.encrypt(name, valueJson, resolved.expires))
        checkForOverflow(name, encrypted.value)
        writeValue(name, encrypted)
    }

    /** `cookies.delete(name, options)`: a no-op unless the cookie is present. */
    fun delete(name: String, options: DeleteOptions = DeleteOptions()) {
        if (cookies.remove(name) == null) return
        deleteCookies[name] = options
    }

    /** `cookies.deleted?(name)`. */
    fun isDeleted(name: String): Boolean = deleteCookies.containsKey(name)

    /** Whether [setCookieHeaders] has anything to write, so a request that set nothing skips working out its host. */
    val hasSetCookies: Boolean get() = setCookies.isNotEmpty() || deleteCookies.isNotEmpty()

    /**
     * The `Set-Cookie` header values Rails would write, in order: sets (secure ones only over SSL
     * or to a `.onion` host), then deletions.
     */
    fun setCookieHeaders(ssl: Boolean, host: String): List<String> {
        if (setCookies.isEmpty() && deleteCookies.isEmpty()) return emptyList()
        val headers = ArrayList<String>(setCookies.size + deleteCookies.size)
        for ((name, cookie) in setCookies) {
            if (ssl || !cookie.secure || host.endsWith(".onion")) headers.add(setCookieHeader(name, cookie))
        }
        for ((name, options) in deleteCookies) headers.add(deleteCookieHeader(name, options))
        return headers
    }

    private fun resolveExpiry(cookie: Cookie): Cookie =
        if (cookie.permanent) cookie.copy(expires = yearsFrom(clock.instant(), PERMANENT_YEARS)) else cookie

    private fun writeValue(name: String, cookie: Cookie) {
        if (cookies[name] != cookie.value || cookie.expires != null) {
            cookies[name] = cookie.value
            setCookies[name] = cookie
            deleteCookies.remove(name)
        }
    }

    override fun toString(): String = "CookieJar(cookies=$cookies, setCookies=$setCookies, deleteCookies=$deleteCookies)"

    companion object {
        const val MAX_COOKIE_SIZE = 4096
        private const val PERMANENT_YEARS = 20L

        private val HTTP_DATE: DateTimeFormatter =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC)

        /** The jar for the request's `Cookie` header(s); the first value of a name wins, across headers too. */
        fun fromHeaders(headers: Iterable<String>, crypto: CookieCrypto, clock: Clock): CookieJar {
            val cookies = LinkedHashMap<String, String>()
            for (header in headers) parseCookieHeader(header, cookies)
            return CookieJar(cookies, crypto, clock)
        }

        /** The jar for [request]'s `Cookie` headers. */
        fun fromRequest(request: Request, crypto: CookieCrypto, clock: Clock): CookieJar =
            fromHeaders(request.headers.all("cookie"), crypto, clock)

        /**
         * `Rack::Utils.parse_cookies_header`: split on `;` and the spaces after it, first occurrence wins, values
         * unescaped (kept raw when unescaping fails or isn't UTF-8).
         */
        fun parseCookieHeader(header: String): List<Pair<String, String>> {
            val cookies = LinkedHashMap<String, String>()
            parseCookieHeader(header, cookies)
            return cookies.map { it.key to it.value }
        }

        /** [parseCookieHeader] into [into], leaving names already there alone. */
        private fun parseCookieHeader(header: String, into: MutableMap<String, String>) {
            var start = 0
            var first = true
            while (start <= header.length) {
                var end = header.indexOf(';', start)
                if (end < 0) end = header.length
                var from = start
                if (!first) while (from < end && header[from] == ' ') from++
                first = false
                if (from < end) {
                    val eq = header.indexOf('=', from).let { if (it < 0 || it >= end) -1 else it }
                    val key = header.substring(from, if (eq < 0) end else eq)
                    if (!into.containsKey(key)) into[key] = if (eq < 0) "" else unescape(header.substring(eq + 1, end))
                }
                start = end + 1
            }
        }

        /** `unescape(value) rescue value`: [Params.decodeWwwFormComponent] when that's valid UTF-8, else the raw value. */
        private fun unescape(value: String): String {
            if (value.indexOf('%') < 0 && value.indexOf('+') < 0) return value
            val bytes = try {
                Params.decodeWwwFormComponent(value)
            } catch (_: ParamError) {
                return value
            }
            return if (isValidUtf8(bytes)) String(bytes, Charsets.UTF_8) else value
        }

        /**
         * `Rack::Utils.escape` (`URI.encode_www_form_component`), which Rack applies to every
         * cookie value it writes: `*-._` and alphanumerics stay, a space becomes `+`, the rest is
         * `%XX` of its UTF-8 bytes.
         */
        fun escape(raw: String): String {
            var plain = true
            for (c in raw) if (!isUnreserved(c)) {
                plain = false
                break
            }
            if (plain) return raw
            val out = StringBuilder(raw.length + 16)
            for (b in raw.toByteArray(Charsets.UTF_8)) {
                val c = (b.toInt() and 0xff).toChar()
                when {
                    isUnreserved(c) -> out.append(c)
                    c == ' ' -> out.append('+')
                    else -> out.append('%').append(HEX_UPPER[c.code shr 4]).append(HEX_UPPER[c.code and 0xf])
                }
            }
            return out.toString()
        }

        private const val HEX_UPPER = "0123456789ABCDEF"

        private fun isUnreserved(c: Char): Boolean =
            c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '*' || c == '-' || c == '.' || c == '_'

        private fun sameSiteAttribute(sameSite: SameSite?): String = when (sameSite) {
            null -> ""
            SameSite.LAX -> "; samesite=lax"
            SameSite.STRICT -> "; samesite=strict"
            SameSite.NONE -> "; samesite=none"
        }

        /** `Rack::Utils.set_cookie_header(key, value_hash)`. */
        fun setCookieHeader(name: String, cookie: Cookie): String {
            val header = StringBuilder(64).append(name).append('=').append(escape(cookie.value))
            if (cookie.domain != null) header.append("; domain=").append(cookie.domain)
            header.append("; path=").append(cookie.path)
            if (cookie.expires != null) header.append("; expires=").append(httpdate(cookie.expires))
            if (cookie.secure) header.append("; secure")
            if (cookie.httponly) header.append("; httponly")
            header.append(sameSiteAttribute(cookie.sameSite))
            if (cookie.partitioned) header.append("; partitioned")
            return header.toString()
        }

        /** `Rack::Utils.delete_set_cookie_header(key, options)`. */
        fun deleteCookieHeader(name: String, options: DeleteOptions): String {
            val header = StringBuilder(96).append(name).append('=')
            if (options.domain != null) header.append("; domain=").append(options.domain)
            header.append("; path=").append(options.path).append("; max-age=0; expires=").append(httpdate(Instant.EPOCH))
            header.append(sameSiteAttribute(options.sameSite))
            return header.toString()
        }

        /** An HTTP date (`Time#httpdate`): `Thu, 01 Jan 1970 00:00:00 GMT` (`clock.rs`, `httpdate`). */
        fun httpdate(at: Instant): String = HTTP_DATE.format(at)

        /**
         * `n.years.from_now` as ActiveSupport computes it: calendar years in UTC, Feb 29 landing
         * on Feb 28 in a common year (`clock.rs`, `years_from`).
         */
        fun yearsFrom(now: Instant, years: Long): Instant = try {
            now.atOffset(ZoneOffset.UTC).plusYears(years).toInstant()
        } catch (_: java.time.DateTimeException) {
            now
        }

        private fun checkForOverflow(name: String, value: String) {
            val total = utf8Length(name) + utf8Length(value)
            if (total > MAX_COOKIE_SIZE) throw KitError.CookieOverflow("$name cookie overflowed with size $total bytes")
        }

        private fun utf8Length(s: String): Int {
            var n = 0
            var i = 0
            while (i < s.length) {
                val c = s[i]
                n += when {
                    c.code < 0x80 -> 1
                    c.code < 0x800 -> 2
                    Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> {
                        i++
                        4
                    }
                    else -> 3
                }
                i++
            }
            return n
        }
    }
}
