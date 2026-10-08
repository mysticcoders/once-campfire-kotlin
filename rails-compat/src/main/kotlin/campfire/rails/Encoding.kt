package campfire.rails

import java.util.Base64

/**
 * Ruby's Base64 flavors as Rails uses them (`once-campfire-rust/crates/rails_compat/src/encoding.rs`).
 * Encoding uses the JDK's encoders; decoding is our own, because `java.util.Base64` accepts missing
 * padding and non-zero trailing bits, which Ruby's strict decoding refuses.
 */
object RubyBase64 {
    private val STRICT = Base64.getEncoder()
    private val URL_SAFE_PADDED = Base64.getUrlEncoder()
    private val URL_SAFE_UNPADDED = Base64.getUrlEncoder().withoutPadding()
    private val EMPTY = ByteArray(0)

    /** Sextet values for the standard alphabet, with `-` and `_` at the indexes [URL_SAFE_VALUES] adds. */
    private val STANDARD_VALUES = IntArray(128) { -1 }.also { table ->
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".forEachIndexed { i, c -> table[c.code] = i }
    }

    /** The standard alphabet plus `-` and `_`, which `urlsafe_decode64` translates to `+` and `/`. */
    private val URL_SAFE_VALUES = STANDARD_VALUES.copyOf().also { table ->
        table['-'.code] = 62
        table['_'.code] = 63
    }

    /** `Base64.strict_encode64`. */
    fun strictEncode(data: ByteArray): String = STRICT.encodeToString(data)

    /** `Base64.urlsafe_encode64(data, padding: false)`. */
    fun urlsafeEncodeUnpadded(data: ByteArray): String = URL_SAFE_UNPADDED.encodeToString(data)

    /** `Base64.urlsafe_encode64(data)` (padded). */
    fun urlsafeEncodePadded(data: ByteArray): String = URL_SAFE_PADDED.encodeToString(data)

    /** `Base64.strict_decode64`: standard alphabet, canonical padding, no whitespace; null if invalid. */
    fun strictDecode(encoded: String): ByteArray? = decode(encoded, STANDARD_VALUES, 0)

    /**
     * `Base64.urlsafe_decode64`, which pads a short unpadded string and then translates `-_` to `+/`
     * before a strict decode. So it accepts either alphabet (even mixed) and optional padding, but
     * refuses partial padding (`"ab="`) and non-zero trailing bits, as Ruby does.
     */
    fun urlsafeDecode(encoded: String): ByteArray? {
        val implicitPadding = if (encoded.endsWith('=') || encoded.length % 4 == 0) 0 else 4 - encoded.length % 4
        return decode(encoded, URL_SAFE_VALUES, implicitPadding)
    }

    /**
     * A canonical decode of `encoded` followed by `implicitPadding` virtual `=`: the padded length
     * is a multiple of four, at most two `=` and only at the end, and the bits after the last whole
     * byte are zero.
     */
    private fun decode(encoded: String, values: IntArray, implicitPadding: Int): ByteArray? {
        val total = encoded.length + implicitPadding
        if (total % 4 != 0) return null
        if (total == 0) return EMPTY
        var explicitPadding = 0
        while (explicitPadding < 3 && explicitPadding < encoded.length && encoded[encoded.length - 1 - explicitPadding] == '=') {
            explicitPadding++
        }
        val padding = explicitPadding + implicitPadding
        if (padding > 2) return null
        val dataLength = encoded.length - explicitPadding
        val out = ByteArray(total / 4 * 3 - padding)
        var buffer = 0
        var bits = 0
        var o = 0
        for (i in 0 until dataLength) {
            val c = encoded[i].code
            val value = if (c < 128) values[c] else -1
            if (value < 0) return null
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[o++] = (buffer shr bits).toByte()
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        return if (buffer == 0) out else null
    }
}
