package campfire.rails

import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * `Rails.application.key_generator`: an `ActiveSupport::CachingKeyGenerator` over PBKDF2-HMAC
 * (`once-campfire-rust/crates/rails_compat/src/key_generator.rs`). Rails builds it with 1000
 * iterations (railties `Rails::Application#key_generator`), and `load_defaults` 7.0+ sets its digest
 * to SHA256 (`key_generator_hash_digest_class`). Keys are derived once per salt and length.
 */
class KeyGenerator(secretKeyBase: String) {
    private val secret = secretKeyBase.toByteArray(Charsets.UTF_8)
    private val cache = ConcurrentHashMap<Pair<String, Int>, ByteArray>()

    /** `generate_key(salt, length)`, memoized like `CachingKeyGenerator`; returns a copy. */
    fun generateKey(salt: String, length: Int = DEFAULT_KEY_LENGTH): ByteArray =
        cache.computeIfAbsent(salt to length) { pbkdf2HmacSha256(secret, salt.toByteArray(Charsets.UTF_8), ITERATIONS, length) }.copyOf()

    companion object {
        /** `Rails::Application#key_generator`'s iteration count. */
        const val ITERATIONS: Int = 1000

        /** `ActiveSupport::KeyGenerator#generate_key`'s default length (encrypted cookies ask for 32). */
        const val DEFAULT_KEY_LENGTH: Int = 64

        /**
         * PBKDF2 (RFC 8018) with HMAC-SHA256. Written out rather than through `SecretKeyFactory`,
         * whose `PBEKeySpec` refuses an empty salt and takes the password as chars.
         */
        private fun pbkdf2HmacSha256(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(RawKey(password, "HmacSHA256"))
            val out = ByteArray(length)
            var offset = 0
            var block = 1
            while (offset < length) {
                mac.update(salt)
                mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
                var u = mac.doFinal()
                val t = u.copyOf()
                for (iteration in 1 until iterations) {
                    u = mac.doFinal(u)
                    for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
                }
                System.arraycopy(t, 0, out, offset, minOf(t.size, length - offset))
                offset += t.size
                block++
            }
            return out
        }
    }
}

/**
 * A raw secret key that, unlike `SecretKeySpec`, may be empty (an HMAC accepts any key length,
 * as the Rust `hmac` crate does).
 */
internal class RawKey(private val bytes: ByteArray, private val algorithm: String) : SecretKey {
    override fun getAlgorithm(): String = algorithm

    override fun getFormat(): String = "RAW"

    override fun getEncoded(): ByteArray = bytes.clone()
}
