package campfire.rails

import java.time.Instant

/**
 * A parsed `gid://app/Model/id` (`GlobalID`, `URI::GID`), after
 * `once-campfire-rust/crates/rails_compat/src/global_id.rs`. Query params (Rails puts
 * `?expires_in` into attachable SGIDs) are dropped: the locator ignores them.
 */
data class GlobalId(val app: String, val modelName: String, val id: String) {
    /** `record.to_global_id` in this app: `gid://campfire/<modelName>/<id>`. */
    constructor(modelName: String, id: Any) : this(APP, modelName, id.toString())

    /** `GlobalID#to_s`. */
    override fun toString(): String = "gid://$app/$modelName/$id"

    /** `GlobalID#to_param`: URL-safe Base64 without padding, as used in Turbo stream names. */
    fun toParam(): String = RubyBase64.urlsafeEncodeUnpadded(toString().toByteArray(Charsets.UTF_8))

    companion object {
        /** `GlobalID.app`, from the application name (`Campfire::Application`). */
        const val APP: String = "campfire"

        /** `GlobalID.parse` for the URI form: `gid://<app>/<Model>/<id>[?params]`, or null. */
        fun parse(gid: String): GlobalId? {
            if (!gid.startsWith("gid://")) return null
            val query = gid.indexOf('?', 6)
            val rest = if (query < 0) gid.substring(6) else gid.substring(6, query)
            val appEnd = rest.indexOf('/')
            if (appEnd < 0) return null
            val modelEnd = rest.indexOf('/', appEnd + 1)
            if (modelEnd < 0) return null
            val app = rest.substring(0, appEnd)
            val modelName = rest.substring(appEnd + 1, modelEnd)
            val id = rest.substring(modelEnd + 1)
            if (app.isEmpty() || modelName.isEmpty() || id.isEmpty()) return null
            return GlobalId(app, modelName, id)
        }

        /** `GlobalID.find`'s param form: the inverse of [toParam], or null. */
        fun fromParam(param: String): GlobalId? {
            val bytes = RubyBase64.urlsafeDecode(param) ?: return null
            return parse(Utf8.decode(bytes) ?: return null)
        }
    }
}

/**
 * `SignedGlobalID` (`user.attachable_sgid`, `GlobalID::Locator.locate_signed`), after
 * `once-campfire-rust/crates/rails_compat/src/global_id.rs`.
 *
 * SGIDs are signed by `GlobalID::Verifier` (key `generate_key("signed_global_ids")`, HMAC-SHA1,
 * URL-safe Base64 *with* padding, the app's `:json_allow_marshal` serializer, and the
 * `{"_rails":{"data":..,"exp":..,"pur":..}}` envelope). Rails 7.0 SGIDs used the legacy envelope
 * with a Marshal-dumped string, which Rails still reads.
 */
object SignedGlobalId {
    const val SALT: String = "signed_global_ids"

    /** `ActionText::Attachable::LOCATOR_NAME`. */
    const val ATTACHABLE_PURPOSE: String = "attachable"

    /** `SignedGlobalID::DEFAULT_PURPOSE`. */
    const val DEFAULT_PURPOSE: String = "default"

    /**
     * `record.attachable_sgid`, i.e. `to_sgid(expires_in: nil, for: "attachable")`. GlobalID turns
     * the leftover `expires_in: nil` option into a query param, so the signed data is
     * `gid://campfire/User/1?expires_in`, with no expiry in the envelope.
     */
    fun attachableSgid(secrets: Secrets, gid: GlobalId): String =
        verifier(secrets).generate("$gid?expires_in", ATTACHABLE_PURPOSE, null)

    /** `SignedGlobalID.new(gid_uri, for: purpose, expires_at:)` for a bare GID URI (no params). */
    fun sgid(secrets: Secrets, gid: GlobalId, purpose: String, expiresAt: Instant?): String =
        verifier(secrets).generate(gid.toString(), purpose, expiresAt)

    /**
     * `SignedGlobalID.parse(sgid, for: purpose)`: the GID if the signature, purpose and expiry
     * check out. Looking the record up (and `only:` restrictions) is the caller's job.
     */
    fun locateSigned(secrets: Secrets, sgid: String, purpose: String, now: Instant): GlobalId? {
        val verifier = verifier(secrets)
        val data = when (val result = verifier.verify(sgid, purpose, now)) {
            is Valid -> result.value
            else -> verifyWithLegacySelfValidatedMetadata(verifier, sgid, purpose, now)
        }
        return if (data is String) GlobalId.parse(data) ?: GlobalId.fromParam(data) else null
    }

    /** globalid < 1.0 signed `{"gid":..,"purpose":..,"expires_at":..}` without a Rails envelope. */
    private fun verifyWithLegacySelfValidatedMetadata(verifier: MessageVerifier, sgid: String, purpose: String, now: Instant): Any? {
        val metadata = verifier.verify(sgid, null, now).getOrNull() as? Map<*, *> ?: return null
        val expiresAt = metadata["expires_at"]
        if (expiresAt != null) {
            val at = Metadata.parseTime(expiresAt as? String ?: return null) ?: return null
            if (now > at) return null
        }
        return if (Metadata.rubyToS(metadata["purpose"]) == purpose) metadata["gid"] else null
    }

    /** `GlobalID::Verifier`, built once per [Secrets]. */
    fun verifier(secrets: Secrets): MessageVerifier = secrets.globalIdVerifier

    internal fun buildVerifier(keyGenerator: KeyGenerator): MessageVerifier = MessageVerifier(
        keyGenerator.generateKey(SALT, 64),
        MessageVerifier.Digest.Sha1,
        MessageVerifier.Encoding.UrlSafePadded,
        Serializer.JsonWithFallback(allowMarshal = true),
    )
}
