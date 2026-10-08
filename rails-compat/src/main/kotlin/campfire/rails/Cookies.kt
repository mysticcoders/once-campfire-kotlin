package campfire.rails

import java.time.Instant
import java.time.ZoneOffset

/**
 * `cookies.signed[...]` / `cookies.encrypted[...]` values (`action_dispatch/middleware/cookies.rb`),
 * plus Rack's escaping of cookie values on the wire (`once-campfire-rust/crates/rails_compat/src/cookies.rs`).
 * Attributes (path, expires, HttpOnly, SameSite) are the HTTP layer's job.
 *
 * The functions here work on the *raw* jar value. On the wire Rack escapes it with
 * `URI.encode_www_form_component` ([escape]) and unescapes incoming cookies ([unescape]).
 *
 * How the jars work, per Rails main:
 * - the value is first dumped with `cookies_serializer` (`:json` here, so `ActiveSupport::JSON`),
 * - then signed (HMAC-**SHA1**, key `generate_key("signed cookie")`) or encrypted (aes-256-gcm,
 *   key `generate_key("authenticated encrypted cookie", 32)`) with the legacy metadata envelope
 *   carrying `pur: "cookie.<name>"` and `exp` (ISO 8601 with milliseconds, or `null`),
 * - reading tries purpose `cookie.<name>` first, then *no purpose*, so a value signed without
 *   metadata (pre-Rails 5.2) is accepted under any cookie name.
 */
object Cookies {
    const val SIGNED_COOKIE_SALT: String = "signed cookie"
    const val AUTHENTICATED_ENCRYPTED_COOKIE_SALT: String = "authenticated encrypted cookie"

    /** `SerializerWithFallback[:json].load`: Marshal payloads aren't allowed for cookies. */
    private val COOKIE_SERIALIZER = Serializer.JsonWithFallback(allowMarshal = false)

    private val HEX = "0123456789ABCDEF".toCharArray()

    /** `cookies.permanent`: expires 20 years from now (calendar years, like `20.years.from_now`). */
    fun permanentExpiresAt(now: Instant): Instant = now.atOffset(ZoneOffset.UTC).plusYears(20).toInstant()

    /**
     * The raw value for `cookies.signed[name] = { value:, expires: expiresAt }`.
     * `cookies.signed.permanent[...]` is `expiresAt = permanentExpiresAt(now)`.
     */
    fun sign(secrets: Secrets, name: String, value: String, expiresAt: Instant?): String =
        signedCookieVerifier(secrets).generate(RailsJson.encode(value), purpose(name), expiresAt)

    /**
     * Reads `cookies.signed[name]`; null wherever Rails returns nil. A value that is valid JSON
     * but not a string (Rails would return it) is also null.
     */
    fun verifySigned(secrets: Secrets, name: String, raw: String, now: Instant): String? =
        verifySignedValue(secrets, name, raw, now) as? String

    /** Reads `cookies.signed[name]` as whatever JSON value it holds (null when it doesn't verify). */
    fun verifySignedValue(secrets: Secrets, name: String, raw: String, now: Instant): Any? {
        val verifier = signedCookieVerifier(secrets)
        val result = verifier.verify(raw, purpose(name), now).takeIf { it is Valid } ?: verifier.verify(raw, null, now)
        return load(result)
    }

    /**
     * The raw value for `cookies.encrypted[name] = { value:, expires: expiresAt }`. The session
     * store writes `_campfire_session` this way with a 20-year `expire_after`.
     */
    fun encrypt(secrets: Secrets, name: String, value: Any?, expiresAt: Instant?): String =
        encryptedCookieEncryptor(secrets).encryptAndSign(RailsJson.encode(value), purpose(name), expiresAt)

    /** Reads `cookies.encrypted[name]`; null wherever Rails returns nil. */
    fun decrypt(secrets: Secrets, name: String, raw: String, now: Instant): Any? {
        val encryptor = encryptedCookieEncryptor(secrets)
        val result = encryptor.decryptAndVerify(raw, purpose(name), now).takeIf { it is Valid }
            ?: encryptor.decryptAndVerify(raw, null, now)
        return load(result)
    }

    /** The signed cookie jar's verifier, built once per [Secrets]. */
    fun signedCookieVerifier(secrets: Secrets): MessageVerifier = secrets.signedCookieVerifier

    /** The encrypted cookie jar's encryptor, built once per [Secrets]. */
    fun encryptedCookieEncryptor(secrets: Secrets): MessageEncryptor = secrets.encryptedCookieEncryptor

    /**
     * `signed_cookie_digest` is unset, so the jar falls back to "SHA1" even though the key itself
     * is derived with PBKDF2-SHA256.
     */
    internal fun buildSignedCookieVerifier(keyGenerator: KeyGenerator): MessageVerifier = MessageVerifier(
        keyGenerator.generateKey(SIGNED_COOKIE_SALT, 64),
        MessageVerifier.Digest.Sha1,
        MessageVerifier.Encoding.Strict,
        Serializer.Null,
    )

    internal fun buildEncryptedCookieEncryptor(keyGenerator: KeyGenerator): MessageEncryptor =
        MessageEncryptor(keyGenerator.generateKey(AUTHENTICATED_ENCRYPTED_COOKIE_SALT, 32), Serializer.Null)

    /**
     * `Rack::Utils.escape` (`URI.encode_www_form_component`), which Rack applies to every cookie
     * value it writes: `*-._` and alphanumerics stay, a space becomes `+`, the rest of the UTF-8
     * bytes are `%XX`. A value with nothing to escape is returned as is.
     */
    fun escape(raw: String): String {
        if (raw.all { it.code < 128 && keptByEscape(it.code) }) return raw
        val bytes = raw.toByteArray(Charsets.UTF_8)
        val out = StringBuilder(bytes.size * 3)
        for (byte in bytes) {
            val b = byte.toInt() and 0xff
            when {
                b == ' '.code -> out.append('+')
                b < 128 && keptByEscape(b) -> out.append(b.toChar())
                else -> out.append('%').append(HEX[b shr 4]).append(HEX[b and 0xf])
            }
        }
        return out.toString()
    }

    private fun keptByEscape(b: Int): Boolean =
        b in 'a'.code..'z'.code || b in 'A'.code..'Z'.code || b in '0'.code..'9'.code ||
            b == '*'.code || b == '-'.code || b == '.'.code || b == '_'.code

    /**
     * `Rack::Utils.parse_cookies_header`'s `unescape(value) rescue value`: `+` is a space, `%XX` is
     * decoded, and a malformed escape leaves the value untouched. Invalid UTF-8 is replaced.
     */
    fun unescape(wire: String): String {
        if (wire.indexOf('+') < 0 && wire.indexOf('%') < 0) return wire
        val bytes = wire.toByteArray(Charsets.UTF_8)
        val out = java.io.ByteArrayOutputStream(bytes.size)
        var i = 0
        while (i < bytes.size) {
            when (val b = bytes[i].toInt()) {
                '+'.code -> out.write(' '.code)
                '%'.code -> {
                    val high = if (i + 2 < bytes.size) Character.digit(bytes[i + 1].toInt(), 16) else -1
                    val low = if (high >= 0) Character.digit(bytes[i + 2].toInt(), 16) else -1
                    if (low < 0) return wire
                    out.write(high * 16 + low)
                    i += 2
                }
                else -> out.write(b)
            }
            i++
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private fun purpose(name: String): String = "cookie.$name"

    private fun load(result: Verified): Any? {
        val dumped = (result as? Valid)?.value as? String ?: return null
        return COOKIE_SERIALIZER.load(dumped.toByteArray(Charsets.UTF_8)).getOrNull()
    }
}
