package campfire.rails

import java.math.BigInteger
import java.time.Instant

/**
 * `Turbo::StreamsChannel.signed_stream_name` / `verified_stream_name` (turbo-rails
 * `app/channels/turbo/streams/stream_name.rb`), after `once-campfire-rust/crates/rails_compat/src/turbo.rs`.
 * The verifier is `MessageVerifier.new(generate_key("turbo/signed_stream_verifier_key"),
 * digest: "SHA256", serializer: JSON)`: strict Base64 of the JSON-dumped stream name, no metadata
 * envelope.
 */
object Turbo {
    const val SALT: String = "turbo/signed_stream_verifier_key"

    /**
     * `streamables` are already-resolved stream name parts, joined with `:` like
     * `stream_name_from`: a record becomes its GID param ([GlobalId.toParam]), a symbol or string
     * itself. So `turbo_stream_from @room, :messages` is `[roomGid.toParam(), "messages"]`.
     */
    fun signedStreamName(secrets: Secrets, streamables: List<String>): String =
        verifier(secrets).generate(streamables.joinToString(":"), null, null)

    /**
     * The stream name, or null if the signature doesn't check out. (A validly signed non-string
     * can't come from [signedStreamName]; numbers are returned as their `to_s` like Rails would.)
     */
    fun verifiedStreamName(secrets: Secrets, signed: String): String? =
        when (val value = verifier(secrets).verify(signed, null, Instant.EPOCH).getOrNull()) {
            is String -> value
            is Long, is BigInteger -> value.toString()
            is Double -> RailsJson.formatFloat(value)
            else -> null
        }

    /** The stream name verifier, built once per [Secrets]. */
    fun verifier(secrets: Secrets): MessageVerifier = secrets.turboVerifier

    internal fun buildVerifier(keyGenerator: KeyGenerator): MessageVerifier = MessageVerifier(
        keyGenerator.generateKey(SALT, 64),
        MessageVerifier.Digest.Sha256,
        MessageVerifier.Encoding.Strict,
        Serializer.Json,
    )
}
