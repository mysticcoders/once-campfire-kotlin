package campfire.rails

import at.favre.lib.crypto.bcrypt.BCrypt
import at.favre.lib.crypto.bcrypt.LongPasswordStrategies
import java.security.SecureRandom

/**
 * `has_secure_password` with `ActiveModel::SecurePassword::BCryptPassword`: bcrypt-ruby's `$2a$`
 * digests at `BCrypt::Engine.cost` (12), after `once-campfire-rust/crates/rails_compat/src/password.rs`.
 * Like bcrypt-ruby (and OpenBSD), the key is the password's UTF-8 bytes plus a NUL, cut to 72
 * bytes, so only the first 72 bytes of a password count. Hashing is `at.favre.lib:bcrypt`.
 */
object Password {
    /** `BCrypt::Engine.cost` in production. */
    const val COST: Int = 12

    /** `BCrypt::Engine::MIN_COST`, what Rails uses when `ActiveModel::SecurePassword.min_cost` is set (tests). */
    const val MIN_COST: Int = 4

    private val TRUNCATE = LongPasswordStrategies.truncate(BCrypt.Version.VERSION_2A)
    private val HASHER = BCrypt.with(BCrypt.Version.VERSION_2A, SecureRandom(), TRUNCATE)
    private val VERIFIER = BCrypt.verifyer(null, TRUNCATE)

    /**
     * `BCrypt::Password.create(password, cost:)`. Throws [IllegalArgumentException] for a cost
     * outside `MIN_COST..31`.
     */
    fun digestWithCost(password: String, cost: Int): String =
        String(HASHER.hash(cost, password.toByteArray(Charsets.UTF_8)), Charsets.UTF_8)

    /** `BCrypt::Password.new(digest).is_password?(password)`; false for a malformed digest. */
    fun verify(password: String, digest: String): Boolean = try {
        VERIFIER.verify(password.toByteArray(Charsets.UTF_8), digest.toByteArray(Charsets.UTF_8)).verified
    } catch (_: RuntimeException) {
        false
    }
}
