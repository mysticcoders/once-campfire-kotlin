package campfire.rails

import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * `ActiveSupport::MessageEncryptor` with `aes-256-gcm`, as the encrypted cookie jar builds it
 * (`once-campfire-rust/crates/rails_compat/src/message_encryptor.rs`):
 * `<base64 ciphertext>--<base64 12-byte IV>--<base64 16-byte auth tag>`, strict Base64, empty
 * auth data, no separate signature. `secret` must be 32 bytes
 * (`key_generator.generate_key(salt, 32)`). Each thread keeps its own `Cipher`.
 */
class MessageEncryptor(secret: ByteArray, val serializer: Serializer) {
    init {
        require(secret.size == 32) { "aes-256-gcm needs a 32-byte key" }
    }

    private val key = SecretKeySpec(secret, "AES")
    private val ciphers: ThreadLocal<Cipher> = ThreadLocal.withInitial { Cipher.getInstance("AES/GCM/NoPadding") }

    /** `encrypt_and_sign(value, purpose:, expires_at:)` with a random IV. */
    fun encryptAndSign(value: Any?, purpose: String? = null, expiresAt: Instant? = null): String {
        val iv = ByteArray(IV_LENGTH).also(RANDOM::nextBytes)
        return encryptWithIv(Metadata.serializeWithMetadata(serializer, value, purpose, expiresAt), iv)
    }

    internal fun encryptWithIv(plaintext: ByteArray, iv: ByteArray): String {
        val cipher = ciphers.get()
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(AUTH_TAG_LENGTH * 8, iv))
        val sealed = cipher.doFinal(plaintext)
        val ciphertext = sealed.copyOfRange(0, sealed.size - AUTH_TAG_LENGTH)
        val tag = sealed.copyOfRange(sealed.size - AUTH_TAG_LENGTH, sealed.size)
        return RubyBase64.strictEncode(ciphertext) + "--" + RubyBase64.strictEncode(iv) + "--" + RubyBase64.strictEncode(tag)
    }

    /** `decrypt_and_verify(message, purpose:)`: the value, or why it couldn't be read. */
    fun decryptAndVerify(message: String, purpose: String?, now: Instant): Verified {
        val plaintext = decrypt(message) ?: return MessageError.InvalidSignature
        return Metadata.deserializeWithMetadata(serializer, plaintext, purpose, now, STRICT_DECODE)
    }

    /** The decrypted bytes, before any envelope handling; null if the message isn't authentic. */
    fun decrypt(message: String): ByteArray? {
        val tagStart = message.length - ENCODED_AUTH_TAG_LENGTH
        val ivStart = tagStart - 2 - ENCODED_IV_LENGTH
        val ciphertextEnd = ivStart - 2
        if (ciphertextEnd < 0 || !separatorAt(message, tagStart - 2) || !separatorAt(message, ciphertextEnd)) return null
        val ciphertext = RubyBase64.strictDecode(message.substring(0, ciphertextEnd)) ?: return null
        val iv = RubyBase64.strictDecode(message.substring(ivStart, tagStart - 2)) ?: return null
        val tag = RubyBase64.strictDecode(message.substring(tagStart)) ?: return null
        if (iv.size != IV_LENGTH || tag.size != AUTH_TAG_LENGTH) return null
        return try {
            val cipher = ciphers.get()
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(AUTH_TAG_LENGTH * 8, iv))
            cipher.doFinal(ciphertext + tag)
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    private fun separatorAt(message: String, index: Int): Boolean = message[index] == '-' && message[index + 1] == '-'

    private companion object {
        const val IV_LENGTH = 12
        const val AUTH_TAG_LENGTH = 16

        /** Strict Base64 lengths of the IV and auth tag (with padding). */
        const val ENCODED_IV_LENGTH = 16
        const val ENCODED_AUTH_TAG_LENGTH = 24

        val RANDOM = SecureRandom()
        val STRICT_DECODE: (String) -> ByteArray? = RubyBase64::strictDecode
    }
}
