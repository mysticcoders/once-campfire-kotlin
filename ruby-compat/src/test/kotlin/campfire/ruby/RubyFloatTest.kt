package campfire.ruby

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals

/** `once-campfire-rust/crates/ruby/src/float.rs` tests. */
class RubyFloatTest {
    /** `String#to_f` in the reference, compared bit for bit so that -0.0 isn't 0.0. */
    @Test
    fun toFLikeRuby() {
        val cases = listOf(
            "0.5" to 0.5,
            "0.5.1" to 0.5,
            "1.5.5e2" to 1.5,
            "1e2" to 100.0,
            "1E2" to 100.0,
            "1.2e-1" to 0.12,
            "1.5e+2" to 150.0,
            "1.e5" to 100000.0,
            "1e2.5" to 100.0,
            "1e0_1" to 10.0,
            "1e" to 1.0,
            "1e+" to 1.0,
            "0.5e-" to 0.5,
            "1e_2" to 1.0,
            "1_0.5" to 10.5,
            "1_2_3.4_5" to 123.45,
            "0.5_5" to 0.55,
            "0_0.5" to 0.5,
            "1__0" to 1.0,
            "1_e2" to 1.0,
            "1._5" to 1.0,
            "_1" to 0.0,
            ".5" to 0.5,
            "+.5" to 0.5,
            "-.5" to -0.5,
            "5." to 5.0,
            "00.5" to 0.5,
            "  -1.5x" to -1.5,
            "\u000b0.5" to 0.5,
            "1,5" to 1.0,
            "-0" to -0.0,
            "0x1A" to 0.0,
            "-0x1A" to -26.0,
            "-0x1.8p1" to -3.0,
            ".e5" to 0.0,
            "e5" to 0.0,
            "." to 0.0,
            "-" to 0.0,
            "+" to 0.0,
            "" to 0.0,
            "Infinity" to 0.0,
            "1e-400" to 0.0,
            "1e400" to Double.POSITIVE_INFINITY,
            "1\u00002" to 1.0,
        )
        for ((s, f) in cases) assertEquals(f.toRawBits(), toF(s).toRawBits(), s)
    }

    /**
     * Significand characters past 60 are dropped when there's more after the number. Fraction
     * digits past 60 significant ones are too: the last case is halfway between two doubles, but
     * not seen as halfway, so it rounds down rather than to the even one.
     */
    @Test
    fun toFDropsWhatRubyHasNoRoomFor() {
        assertEquals(1e59, toF("1" + "0".repeat(70) + "x"))
        assertEquals(1e70, toF("1" + "0".repeat(70)))
        val halfway = "0.00000000093132257461547882581772970738537807677825952623607008717954158782958984375"
        assertEquals(java.lang.Double.longBitsToDouble(0x3E10_0000_0000_0001), toF(halfway))
    }

    /**
     * `Float#to_s` in the reference (Ruby 3.4.10): from 1e15, the exponent form unless there are
     * digits after the decimal point.
     */
    @Test
    fun floatToSLikeRuby34() {
        val cases = listOf(
            100.0 to "100.0",
            0.1 to "0.1",
            0.0001 to "0.0001",
            0.00012345 to "0.00012345",
            1e-5 to "1.0e-05",
            1.5e-7 to "1.5e-07",
            5e-324 to "5.0e-324",
            1e14 to "100000000000000.0",
            123456789012345.6 to "123456789012345.6",
            999999999999999.0 to "999999999999999.0",
            999999999999999.9 to "999999999999999.9",
            -999999999999999.0 to "-999999999999999.0",
            1e15 to "1.0e+15",
            -1e15 to "-1.0e+15",
            1.5e15 to "1.5e+15",
            1234567890123456.0 to "1.234567890123456e+15",
            9007199254740992.0 to "9.007199254740992e+15",
            1000000000000001.0 to "1.000000000000001e+15",
            1963684456584958.8 to "1963684456584958.8",
            1000000000000000.1 to "1000000000000000.1",
            2251799813685248.5 to "2251799813685248.5",
            -2551800308696183.5 to "-2551800308696183.5",
            1e16 to "1.0e+16",
            1e20 to "1.0e+20",
            Double.MAX_VALUE to "1.7976931348623157e+308",
        )
        for ((f, s) in cases) assertEquals(s, floatToS(f), f.toString())
    }

    /**
     * `Float#to_s` in the reference: of two shortest forms equally close, the even one, as long as
     * it reads back. Doubles are twice as dense just below 2^-24, so its even form
     * (5.960464477539062e-08) reads back as a different double.
     */
    @Test
    fun floatToSBreaksTiesToEvenLikeRuby() {
        val cases = listOf(
            667020902720176.0 + 0.25 to "667020902720176.2",
            667020902720176.0 + 0.75 to "667020902720176.8",
            1000000000000000.2 to "1000000000000000.2",
            1125899906842624.0 + 0.25 to "1125899906842624.2",
            -(2074704973491874.0 + 0.25) to "-2074704973491874.2",
            24603114260468.0 + 0.0625 to "24603114260468.062",
            210745403561986.0 + 0.125 to "210745403561986.12",
            2.0.pow(-24) to "5.960464477539063e-08",
            2.0.pow(-25) to "2.9802322387695312e-08",
            0.1 + 0.2 to "0.30000000000000004",
            1.0 / 3.0 to "0.3333333333333333",
        )
        for ((f, s) in cases) assertEquals(s, floatToS(f), f.toString())
    }
}
