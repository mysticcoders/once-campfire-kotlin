package campfire.rails

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Everything derived from `secret_key_base`, built once at boot and shared
 * (`once-campfire-rust/crates/rails_compat/src/lib.rs`, `Secrets`). The verifiers and encryptor the
 * app uses are derived on first use and kept, so a request only signs or checks.
 */
class Secrets(secretKeyBase: String) {
    /** `Rails.application.key_generator`. */
    val keyGenerator: KeyGenerator = KeyGenerator(secretKeyBase)

    internal val signedCookieVerifier: MessageVerifier by lazy { Cookies.buildSignedCookieVerifier(keyGenerator) }
    internal val encryptedCookieEncryptor: MessageEncryptor by lazy { Cookies.buildEncryptedCookieEncryptor(keyGenerator) }
    internal val signedIdVerifier: MessageVerifier by lazy { SignedId.buildVerifier(keyGenerator) }
    internal val globalIdVerifier: MessageVerifier by lazy { SignedGlobalId.buildVerifier(keyGenerator) }
    internal val turboVerifier: MessageVerifier by lazy { Turbo.buildVerifier(keyGenerator) }
    private val appVerifiers = ConcurrentHashMap<String, MessageVerifier>()

    /**
     * `Rails.application.message_verifier(name)` (`lib.rs`, `app_verifier`): `message_verifiers[name]`
     * with the app defaults (key `generate_key(name, 64)`, HMAC-SHA1, strict Base64,
     * `:json_allow_marshal`, `_rails` envelope). Active Storage uses `appVerifier("ActiveStorage")`
     * for blob signed ids (purpose `"blob_id"`, *not* [SignedId]'s `model/purpose` scheme),
     * variation keys, disk URLs and upload tokens. Use `generateRaw`/`verifyRaw` to control the JSON
     * key order. Memoized per name.
     */
    fun appVerifier(name: String): MessageVerifier = appVerifiers.computeIfAbsent(name) {
        MessageVerifier(
            keyGenerator.generateKey(it, 64),
            MessageVerifier.Digest.Sha1,
            MessageVerifier.Encoding.Strict,
            Serializer.JsonWithFallback(allowMarshal = true),
        )
    }

    /**
     * `cookies.signed[name]` as a string ([Cookies.verifySigned]): null wherever Rails returns nil.
     * Tries purpose `cookie.<name>` first, then no purpose, and rejects expired envelopes.
     */
    fun verifySignedCookie(name: String, raw: String, now: Instant): String? = Cookies.verifySigned(this, name, raw, now)

    /** `user.signed_id(purpose: :avatar)` ([SignedId.generate]): `{"_rails":{"data":id,"pur":"user/avatar"}}`, URL-safe. */
    fun avatarToken(userId: Long): String = SignedId.generate(this, "User", userId, "avatar", null)

    /** `Turbo::StreamsChannel.signed_stream_name(streamables)` ([Turbo.signedStreamName]). */
    fun signedStreamName(vararg streamables: String): String = Turbo.signedStreamName(this, streamables.asList())
}

/** `GlobalID#to_param` ([GlobalId.toParam]): URL-safe unpadded Base64 of `gid://campfire/<class>/<id>`. */
fun globalIdParam(modelName: String, id: Long): String = GlobalId(modelName, id).toParam()

/** `ActiveSupport::JSON.encode` for a string ([RailsJson.encode]). */
fun jsonString(value: String): String = RailsJson.encode(value)
