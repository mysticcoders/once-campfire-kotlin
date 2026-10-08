package campfire.ruby

import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/** `once-campfire-rust/crates/ruby/src/integer.rs` tests. */
class RubyIntegerTest {
    /** `String#to_i` in the reference. */
    @Test
    fun toILikeRuby() {
        assertEquals(1717243200000, toI("1717243200000"))
        assertEquals(12, toI(" +12abc"))
        assertEquals(7, toI("\t\n\u000b\u000c\r 7"))
        assertEquals(0, toI(" 5"))
        assertEquals(0, toI("abc"))
        assertEquals(-5, toI("-5"))
        assertEquals(0, toI("--5"))
        assertEquals(56, toI("5_6"))
        assertEquals(5, toI("5__6"))
        assertEquals(0, toI("_5"))
        assertEquals(0, toI("0__5"))
        assertEquals(-5, toI("-0d5"))
        assertEquals(0, toI("0d_5"))
        assertEquals(0, toI("0x5"))
        assertEquals(Long.MAX_VALUE, toI("99999999999999999999"))
        assertEquals(Long.MIN_VALUE, toI("-99999999999999999999"))
        assertEquals(null, toIChecked("99999999999999999999"))
        assertEquals(BigInteger("99999999999999999999"), toI128("99999999999999999999"))
    }

    /** `Room.type_for_attribute(:id).serialize(s)` in the reference; a RangeError is null. */
    @Test
    fun castsLikeActiveRecord() {
        val cases = listOf(
            "12" to 12L,
            "12abc" to 12L,
            " -3" to -3L,
            " +12abc" to 12L,
            "\t\n\u000b\u000c\r 7" to 7L,
            "\u000b-0d7" to -7L,
            "0d12" to 12L,
            "0D12" to 12L,
            "0d" to 0L,
            "0d_5" to 0L,
            "0d0_5" to 5L,
            "5_6" to 56L,
            "5__6" to 5L,
            "1_000" to 1000L,
            "0x5" to 0L,
            "-0" to 0L,
            "5\u0000" to 5L,
            "3000000000" to 3000000000L,
            "9223372036854775807" to Long.MAX_VALUE,
            "-9223372036854775808" to Long.MIN_VALUE,
            "9223372036854775808" to null,
            "-9223372036854775809" to null,
            "99999999999999999999" to null,
            " 5" to null,
            "\u0000 5" to null,
            "_5" to null,
            "--5" to null,
            "+-5" to null,
            "+" to null,
            "abc" to null,
            "" to null,
        )
        for ((value, id) in cases) assertEquals(id, integerCast(value), value)
    }
}
