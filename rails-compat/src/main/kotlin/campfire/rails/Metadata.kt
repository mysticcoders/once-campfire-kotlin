package campfire.rails

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/**
 * How a [MessageVerifier] or [MessageEncryptor] dumps and loads values
 * (`once-campfire-rust/crates/rails_compat/src/metadata.rs`, `Serializer`).
 */
sealed interface Serializer {
    /**
     * `ActiveSupport::MessageEncryptor::NullSerializer`: the value is a string of already
     * serialized bytes (what the cookie jars sign and encrypt). Uses the legacy envelope.
     */
    data object Null : Serializer

    /** The `::JSON` module (`JSON.dump`/`JSON.load`), as signed ids and Turbo stream names use. */
    data object Json : Serializer

    /**
     * `SerializerWithFallback[:json]` or `[:json_allow_marshal]` with `ActiveSupport::JSON`: the
     * app's default `message_serializer` under `load_defaults` 7.1+.
     */
    data class JsonWithFallback(val allowMarshal: Boolean) : Serializer
}

/** `dump`: the serialized text of `value` (the null serializer only signs strings). */
internal fun Serializer.dump(value: Any?): String = when (this) {
    Serializer.Null -> value as? String ?: throw IllegalArgumentException("the null serializer only signs strings, got $value")
    Serializer.Json -> RailsJson.generate(value)
    is Serializer.JsonWithFallback -> RailsJson.encode(value)
}

/**
 * `load`: the null serializer wants UTF-8, `JSON.load("")` is nil, and the fallback serializer
 * reads a Marshal-dumped string only when `allowMarshal`.
 */
internal fun Serializer.load(bytes: ByteArray): Verified = when (this) {
    Serializer.Null -> Utf8.decode(bytes)?.let(::Valid) ?: MessageError.InvalidMessage
    Serializer.Json -> if (bytes.isEmpty()) Valid(null) else parsed(RailsJson.parse(bytes))
    is Serializer.JsonWithFallback -> when {
        !Marshal.isDump(bytes) -> parsed(RailsJson.parse(bytes))
        !allowMarshal -> MessageError.InvalidMessage
        else -> Marshal.loadString(bytes)?.let { Valid(String(it, Charsets.UTF_8)) } ?: MessageError.InvalidMessage
    }
}

/** The JSON text of `value` in this serializer's dialect (`encode_json`). */
internal fun Serializer.encodeJson(value: Any?): String =
    if (this == Serializer.Json) RailsJson.generate(value) else RailsJson.encode(value)

private fun parsed(value: Any?): Verified = if (value === RailsJson.Invalid) MessageError.InvalidMessage else Valid(value)

/**
 * `ActiveSupport::Messages::Metadata`: how a value and its purpose and expiry are packed into the
 * bytes that get signed or encrypted (`metadata.rs`).
 *
 * Two envelopes exist. When the serializer is one Rails trusts for metadata (JSON, or the
 * `:json_allow_marshal` fallback serializer) it is `{"_rails":{"data":<value>,"exp":..,"pur":..}}`
 * in that serializer. With the cookie jars' `NullSerializer` (and in Rails 7.0) it's the legacy
 * "dual-serialized" one, `{"_rails":{"message":"<base64 of the dumped value>","exp":..,"pur":..}}`,
 * which always carries `exp` and `pur`, as `null` when unset.
 */
internal object Metadata {
    private val LEGACY_PREFIX = "{\"_rails\":{\"message\":".toByteArray(Charsets.UTF_8)

    /** `Time#iso8601(3)` in UTC: `2046-01-01T12:00:00.000Z` (fraction truncated, not rounded). */
    fun iso8601Millis(time: Instant): String {
        val t = LocalDateTime.ofEpochSecond(time.epochSecond, 0, ZoneOffset.UTC)
        val out = StringBuilder(24)
        pad(out, t.year, 4).append('-')
        pad(out, t.monthValue, 2).append('-')
        pad(out, t.dayOfMonth, 2).append('T')
        pad(out, t.hour, 2).append(':')
        pad(out, t.minute, 2).append(':')
        pad(out, t.second, 2).append('.')
        return pad(out, time.nano / 1_000_000, 3).append('Z').toString()
    }

    private fun pad(out: StringBuilder, value: Int, width: Int): StringBuilder {
        val digits = value.toString()
        repeat(width - digits.length) { out.append('0') }
        return out.append(digits)
    }

    /** `jiff::Timestamp`'s parse of an RFC 3339 time with an offset, or null. */
    fun parseTime(text: String): Instant? = try {
        Instant.parse(text)
    } catch (_: DateTimeParseException) {
        try {
            OffsetDateTime.parse(text).toInstant()
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /** `serialize_with_metadata`: `value` dumped by `serializer`, wrapped in its envelope. */
    fun serializeWithMetadata(serializer: Serializer, value: Any?, purpose: String?, expiresAt: Instant?): ByteArray =
        serializeDumpedWithMetadata(serializer, serializer.dump(value), purpose, expiresAt)

    /**
     * Like [serializeWithMetadata] for a value the caller already dumped with `serializer` (so the
     * caller controls key order and escaping). No purpose and no expiry means no envelope.
     */
    fun serializeDumpedWithMetadata(serializer: Serializer, dumped: String, purpose: String?, expiresAt: Instant?): ByteArray {
        if (purpose == null && expiresAt == null) return dumped.toByteArray(Charsets.UTF_8)
        val expiry = expiresAt?.let(::iso8601Millis)
        val out = StringBuilder(dumped.length + 64)
        if (serializer != Serializer.Null) {
            out.append("{\"_rails\":{\"data\":").append(dumped)
            if (expiry != null) out.append(",\"exp\":").append(serializer.encodeJson(expiry))
            if (purpose != null) out.append(",\"pur\":").append(serializer.encodeJson(purpose))
            out.append("}}")
        } else {
            out.append("{\"_rails\":{\"message\":").append(RailsJson.encode(RubyBase64.strictEncode(dumped.toByteArray(Charsets.UTF_8))))
            out.append(",\"exp\":").append(RailsJson.encode(expiry))
            out.append(",\"pur\":").append(RailsJson.encode(purpose))
            out.append("}}")
        }
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * `deserialize_with_metadata`. `decodeLegacyMessage` decodes the Base64 inside a legacy
     * envelope: the verifier accepts either alphabet, the encryptor only strict Base64.
     */
    fun deserializeWithMetadata(
        serializer: Serializer,
        bytes: ByteArray,
        purpose: String?,
        now: Instant,
        decodeLegacyMessage: (String) -> ByteArray?,
    ): Verified {
        if (startsWith(bytes, LEGACY_PREFIX)) {
            val envelope = RailsJson.parse(bytes)
            if (envelope === RailsJson.Invalid) return MessageError.InvalidSignature
            val rails = (envelope as? Map<*, *>)?.get("_rails") as? Map<*, *> ?: return MessageError.InvalidMessage
            check(rails, purpose, now)?.let { return it }
            val message = rails["message"] as? String ?: return MessageError.InvalidSignature
            val dumped = decodeLegacyMessage(message) ?: return MessageError.InvalidSignature
            return serializer.load(dumped)
        }
        val loaded = serializer.load(bytes)
        if (loaded !is Valid) return loaded
        val value = loaded.value
        return if (value is Map<*, *> && value.containsKey("_rails")) {
            val rails = value["_rails"] as? Map<*, *> ?: return MessageError.InvalidMessage
            check(rails, purpose, now) ?: Valid(rails["data"])
        } else if (purpose == null) {
            loaded
        } else {
            MessageError.PurposeMismatch
        }
    }

    /**
     * `extract_from_metadata_envelope`'s checks: `exp` is expired when `now >= exp`; purposes
     * compare with `to_s`, so a missing `pur` matches no purpose. Null when both pass.
     */
    private fun check(rails: Map<*, *>, purpose: String?, now: Instant): MessageError? {
        when (val exp = rails["exp"]) {
            null -> {}
            is String -> {
                val expiresAt = parseTime(exp) ?: return MessageError.InvalidMessage
                if (now >= expiresAt) return MessageError.Expired
            }
            else -> return MessageError.InvalidMessage
        }
        return if (rubyToS(rails["pur"]) != (purpose ?: "")) MessageError.PurposeMismatch else null
    }

    /**
     * `Object#to_s` for the JSON values a purpose could hold. Arrays and hashes `to_s` as their
     * `#inspect`, which no purpose we compare against looks like, so they get a NUL prefix.
     */
    fun rubyToS(value: Any?): String = when (value) {
        null -> ""
        is String -> value
        is Double -> RailsJson.formatFloat(value)
        is Number, is Boolean -> value.toString()
        else -> "\u0000" + RailsJson.generate(value)
    }

    private fun startsWith(bytes: ByteArray, prefix: ByteArray): Boolean {
        if (bytes.size < prefix.size) return false
        for (i in prefix.indices) if (bytes[i] != prefix[i]) return false
        return true
    }
}
