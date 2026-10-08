package campfire.rails

/**
 * What reading a signed or encrypted message produced: [Valid] with the value, or the
 * [MessageError] saying why not. The `Result<Value, Error>` of
 * `once-campfire-rust/crates/rails_compat/src/lib.rs`; the errors are singletons, so a failed read
 * allocates nothing and throws nothing.
 */
sealed interface Verified {
    /** The value, or null when the message didn't verify (or verified and holds JSON null). */
    fun getOrNull(): Any? = (this as? Valid)?.value
}

/** An authentic message's value, in the [RailsJson] value model. */
data class Valid(val value: Any?) : Verified

/**
 * Why a message couldn't be read: the `Error` enum of `once-campfire-rust/crates/rails_compat/src/lib.rs`,
 * after `ActiveSupport::Messages::Codec`'s `catch_and_raise` reasons.
 */
enum class MessageError : Verified {
    /** Malformed message or bad signature (Ruby's `:invalid_message_format`). */
    InvalidSignature,

    /** Authentic, but the payload doesn't deserialize (`:invalid_message_serialization`). */
    InvalidMessage,

    /** The envelope's `exp` is at or before now. */
    Expired,

    /** Wrong purpose, or a purpose was expected and the message carries no metadata. */
    PurposeMismatch;

    /**
     * `ActiveSupport::Messages::Rotator` only falls back to the next rotation on format and
     * serialization errors. An expired or mismatched message stops at the first verifier.
     */
    internal val rotates: Boolean
        get() = this == InvalidSignature || this == InvalidMessage
}
