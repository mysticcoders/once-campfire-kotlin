package campfire.rails

/**
 * The sliver of Ruby's Marshal format needed to read Rails 7-era signed messages, whose payload is
 * a marshaled String (`Marshal.dump("gid://campfire/User/1")`); anything else is refused
 * (`once-campfire-rust/crates/rails_compat/src/marshal.rs`).
 */
internal object Marshal {
    /** `Marshal::MAJOR_VERSION`, `MINOR_VERSION`: every dump starts with `\x04\x08`. */
    val SIGNATURE: ByteArray = byteArrayOf(4, 8)

    /** Whether `bytes` starts with [SIGNATURE] (`SerializerWithFallback`'s Marshal detection). */
    fun isDump(bytes: ByteArray): Boolean = bytes.size >= 2 && bytes[0] == SIGNATURE[0] && bytes[1] == SIGNATURE[1]

    /** Loads a marshaled String, `"\x04\x08" ["I"] '"' <len> <bytes> [<ivars>]`, or null. */
    fun loadString(dumped: ByteArray): ByteArray? {
        if (!isDump(dumped)) return null
        var position = 2
        if (position < dumped.size && dumped[position] == 'I'.code.toByte()) position++
        if (position >= dumped.size || dumped[position] != '"'.code.toByte()) return null
        position++
        val (length, start) = readFixnum(dumped, position) ?: return null
        if (length < 0 || length > dumped.size - start) return null
        return dumped.copyOfRange(start, start + length.toInt())
    }

    /** `r_long`: a Marshal fixnum at `position`, and the position after it. */
    private fun readFixnum(bytes: ByteArray, position: Int): Pair<Long, Int>? {
        if (position >= bytes.size) return null
        val first = bytes[position].toInt()
        val rest = position + 1
        return when (first) {
            0 -> 0L to rest
            in 1..4 -> {
                if (rest + first > bytes.size) return null
                var value = 0L
                for (i in first - 1 downTo 0) value = (value shl 8) or (bytes[rest + i].toLong() and 0xff)
                value to rest + first
            }
            in -4..-1 -> {
                val n = -first
                if (rest + n > bytes.size) return null
                var value = -1L
                for (i in 0 until n) {
                    value = value and (0xffL shl (8 * i)).inv()
                    value = value or ((bytes[rest + i].toLong() and 0xff) shl (8 * i))
                }
                value to rest + n
            }
            in 5..127 -> (first - 5).toLong() to rest
            else -> (first + 5).toLong() to rest
        }
    }
}
