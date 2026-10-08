package campfire.rails

import javax.crypto.Mac

/**
 * An HMAC with a fixed key, with one initialized `Mac` per thread (a `Mac` isn't thread-safe), so
 * signing on the request path doesn't look up a provider or set up a key. Backs [MessageVerifier]
 * (`once-campfire-rust/crates/rails_compat/src/message_verifier.rs`, `mac`).
 */
class Hmac(algorithm: String, key: ByteArray) {
    private val macs: ThreadLocal<Mac> = ThreadLocal.withInitial { Mac.getInstance(algorithm).apply { init(RawKey(key, algorithm)) } }

    /** The MAC of `data`'s UTF-8 bytes. */
    fun sign(data: String): ByteArray = sign(data.toByteArray(Charsets.UTF_8))

    /** The MAC of `data`. */
    fun sign(data: ByteArray): ByteArray = macs.get().doFinal(data)
}
