package campfire.rails

import java.math.BigInteger
import java.time.Instant

/**
 * `ActiveRecord::SignedId` (`signed_id(purpose:, expires_in:)` / `find_signed`), after
 * `once-campfire-rust/crates/rails_compat/src/signed_id.rs`.
 *
 * The verifier is `Rails.application.message_verifiers["active_record/signed_id"]` with the
 * legacy options prepended (`use_legacy_signed_id_verifier` defaults to `:generate_and_verify`):
 * it generates with SHA256, `::JSON`, URL-safe Base64, and falls back when reading to the
 * app-wide default (SHA1, `:json_allow_marshal`, strict Base64). The purpose is
 * `"<base class name underscored>/<purpose>"`, e.g. `user/avatar`, or just `user`.
 */
object SignedId {
    const val SALT: String = "active_record/signed_id"

    /** `record.signed_id(purpose:, expires_at:)`; `modelName` is the *base* class name ("User", not "Rooms::Open"). */
    fun generate(secrets: Secrets, modelName: String, id: Long, purpose: String?, expiresAt: Instant?): String =
        verifier(secrets).generate(id, combinePurposes(modelName, purpose), expiresAt)

    /** `find_signed`'s verification step: the id to look up, or null. `find_by(id: "7")` casts a string id. */
    fun verify(secrets: Secrets, modelName: String, signedId: String, purpose: String?, now: Instant): Long? =
        when (val value = verifier(secrets).verify(signedId, combinePurposes(modelName, purpose), now).getOrNull()) {
            is Long -> value
            is BigInteger -> null
            is String -> value.trim().toLongOrNull()
            else -> null
        }

    /** The signed id verifier with its legacy fallback, built once per [Secrets]. */
    fun verifier(secrets: Secrets): MessageVerifier = secrets.signedIdVerifier

    internal fun buildVerifier(keyGenerator: KeyGenerator): MessageVerifier {
        val secret = keyGenerator.generateKey(SALT, 64)
        val fallback = MessageVerifier(secret, MessageVerifier.Digest.Sha1, MessageVerifier.Encoding.Strict, Serializer.JsonWithFallback(allowMarshal = true))
        return MessageVerifier(secret, MessageVerifier.Digest.Sha256, MessageVerifier.Encoding.UrlSafe, Serializer.Json).fallBackTo(fallback)
    }

    /** `combine_signed_id_purposes`: `[base_class.name.underscore, purpose.to_s].compact_blank.join("/")`. */
    fun combinePurposes(modelName: String, purpose: String?): String =
        listOf(underscore(modelName), purpose ?: "").filter { it.isNotBlank() }.joinToString("/")

    /** `String#underscore` for class names: `Rooms::Open` is `rooms/open`, `WebPush` is `web_push`. */
    internal fun underscore(name: String): String {
        val chars = name.replace("::", "/")
        val out = StringBuilder(chars.length + 4)
        for (i in chars.indices) {
            val c = chars[i]
            if (c in 'A'..'Z') {
                val previous = if (i > 0) chars[i - 1] else null
                val next = if (i + 1 < chars.length) chars[i + 1] else null
                val afterLowerOrDigit = previous != null && (previous in 'a'..'z' || previous in '0'..'9')
                val acronymEnd = previous != null && previous in 'A'..'Z' && next != null && next in 'a'..'z'
                if (afterLowerOrDigit || acronymEnd) out.append('_')
                out.append(c.lowercaseChar())
            } else {
                out.append(if (c == '-') '_' else c)
            }
        }
        return out.toString()
    }
}
