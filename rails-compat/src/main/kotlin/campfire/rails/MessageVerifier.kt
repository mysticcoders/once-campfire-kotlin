package campfire.rails

import java.time.Instant

/**
 * `ActiveSupport::MessageVerifier`: `<base64 payload>--<hex HMAC of the base64 payload>`
 * (`once-campfire-rust/crates/rails_compat/src/message_verifier.rs`). Each use in Rails configures
 * it differently; [Cookies], [SignedId], [SignedGlobalId], [Turbo] and [Secrets.appVerifier] name
 * them. The HMAC key is set up once per thread, so signing and verifying only hash.
 */
class MessageVerifier private constructor(
    private val secret: ByteArray,
    val digest: Digest,
    val encoding: Encoding,
    val serializer: Serializer,
    private val rotations: List<MessageVerifier>,
) {
    /** A verifier with no rotations (`MessageVerifier.new(secret, digest:, url_safe:, serializer:)`). */
    constructor(secret: ByteArray, digest: Digest, encoding: Encoding, serializer: Serializer) :
        this(secret.copyOf(), digest, encoding, serializer, emptyList())

    private val hmac = Hmac(digest.algorithm, secret)

    /** The HMAC digest (`digest:`), with its hex length. */
    enum class Digest(internal val algorithm: String, internal val hexLength: Int) {
        Sha1("HmacSHA1", 40),
        Sha256("HmacSHA256", 64),
    }

    /**
     * How the payload is Base64-encoded when generating. Reading is lenient in every case:
     * `MessageVerifier#decode` retries with the other alphabet, and `GlobalID::Verifier` uses
     * `urlsafe_decode64`, which accepts both alphabets.
     */
    enum class Encoding {
        /** `Base64.strict_encode64` (the default, `url_safe: false`). */
        Strict,

        /** `url_safe: true`: URL-safe alphabet without padding. */
        UrlSafe,

        /** `GlobalID::Verifier`: URL-safe alphabet *with* padding. */
        UrlSafePadded,
    }

    /** `rotate`/`fall_back_to`: a copy that also tries `rotation`, after any earlier ones. */
    fun fallBackTo(rotation: MessageVerifier): MessageVerifier =
        MessageVerifier(secret, digest, encoding, serializer, rotations + rotation)

    /** `generate(value, purpose:, expires_at:)`. */
    fun generate(value: Any?, purpose: String? = null, expiresAt: Instant? = null): String =
        sign(Metadata.serializeWithMetadata(serializer, value, purpose, expiresAt))

    /**
     * `generate` for data the caller already encoded as JSON (in this verifier's serializer, e.g.
     * `ActiveSupport::JSON` escaping for app verifiers), so key order is under the caller's
     * control: `{"_rails":{"data":<dataJson>,"exp":..,"pur":..}}`.
     */
    fun generateRaw(dataJson: String, purpose: String? = null, expiresAt: Instant? = null): String =
        sign(Metadata.serializeDumpedWithMetadata(serializer, dataJson, purpose, expiresAt))

    /**
     * `verified`, with the data re-encoded as JSON in this verifier's serializer as the [Valid]
     * value. Key order is kept; escapes and number formatting are normalized, which only matters
     * if the caller re-signs the returned string.
     */
    fun verifyRaw(message: String, purpose: String?, now: Instant): Verified =
        when (val result = verify(message, purpose, now)) {
            is Valid -> Valid(serializer.encodeJson(result.value))
            else -> result
        }

    /**
     * `verified`/`verify`: the value, or why it couldn't be read. Rotations are tried only after a
     * format or serialization error ([MessageError.rotates]).
     */
    fun verify(message: String, purpose: String?, now: Instant): Verified {
        val result = readMessage(message, purpose, now)
        if (result is MessageError && result.rotates) {
            for (rotation in rotations) {
                val rotated = rotation.readMessage(message, purpose, now)
                if (rotated is MessageError && rotated.rotates) continue
                return rotated
            }
        }
        return result
    }

    private fun sign(serialized: ByteArray): String {
        val encoded = when (encoding) {
            Encoding.Strict -> RubyBase64.strictEncode(serialized)
            Encoding.UrlSafe -> RubyBase64.urlsafeEncodeUnpadded(serialized)
            Encoding.UrlSafePadded -> RubyBase64.urlsafeEncodePadded(serialized)
        }
        val mac = hmac.sign(encoded)
        val out = StringBuilder(encoded.length + 2 + mac.size * 2).append(encoded).append("--")
        for (b in mac) out.append(HEX[(b.toInt() shr 4) and 0xf]).append(HEX[b.toInt() and 0xf])
        return out.toString()
    }

    private fun readMessage(message: String, purpose: String?, now: Instant): Verified {
        val encoded = extractEncoded(message) ?: return MessageError.InvalidSignature
        val decoded = RubyBase64.urlsafeDecode(encoded) ?: return MessageError.InvalidSignature
        return Metadata.deserializeWithMetadata(serializer, decoded, purpose, now, URLSAFE_DECODE)
    }

    /**
     * `extract_encoded`: the digest is the last `2 * digest_length` characters, preceded by `--`,
     * both parts must be `present?`, and the digest must match.
     */
    private fun extractEncoded(signed: String): String? {
        val index = signed.length - (digest.hexLength + 2)
        if (index < 0 || signed[index] != '-' || signed[index + 1] != '-') return null
        val encoded = signed.substring(0, index)
        if (encoded.isBlank() || isBlankFrom(signed, index + 2)) return null
        return if (digestMatches(encoded, signed, index + 2)) encoded else null
    }

    private fun isBlankFrom(s: String, start: Int): Boolean {
        for (i in start until s.length) if (!s[i].isWhitespace()) return false
        return true
    }

    /**
     * `ActiveSupport::SecurityUtils.secure_compare` of the lowercase hex HMAC of `data` against
     * the `hexLength` characters of `signed` from `start`, without building the hex string.
     */
    private fun digestMatches(data: String, signed: String, start: Int): Boolean {
        val mac = hmac.sign(data)
        var difference = 0
        for (i in mac.indices) {
            val b = mac[i].toInt()
            difference = difference or (signed[start + 2 * i].code xor HEX[(b shr 4) and 0xf].code)
            difference = difference or (signed[start + 2 * i + 1].code xor HEX[b and 0xf].code)
        }
        return difference == 0
    }

    private companion object {
        val HEX = "0123456789abcdef".toCharArray()
        val URLSAFE_DECODE: (String) -> ByteArray? = RubyBase64::urlsafeDecode
    }
}
