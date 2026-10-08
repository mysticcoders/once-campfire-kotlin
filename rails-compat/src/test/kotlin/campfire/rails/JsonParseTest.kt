package campfire.rails

import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** [RailsJson.parse] follows serde_json (`once-campfire-rust/crates/rails_compat/src/json.rs`, `parse`) where jackson differs. */
class JsonParseTest {
    private fun parse(s: String): Any? = RailsJson.parse(s.toByteArray(Charsets.UTF_8))

    @Test
    fun numbersAreSerdeNumbers() {
        assertEquals(42L, parse("42"))
        assertEquals(-0.0, parse("-0"))
        assertEquals(BigInteger("18446744073709551615"), parse("18446744073709551615"))
        assertEquals(1.8446744073709552e19, parse("18446744073709551616"))
        assertEquals(-9223372036854775807L - 1, parse("-9223372036854775808"))
        assertSame(RailsJson.Invalid, parse("1e400"))
    }

    @Test
    fun keepsKeyOrderAndTheLastDuplicate() {
        val map = parse("""{"b":1,"a":2,"b":3}""") as Map<*, *>
        assertEquals(listOf("b", "a"), map.keys.toList())
        assertEquals(3L, map["b"])
    }

    @Test
    fun refusesWhatSerdeRefuses() {
        for (bad in listOf("", " ", "1 2", "{}x", "\uFEFF1", "\"\\ud800\"", "[" .repeat(128) + "]".repeat(128), "01", "NaN", "'a'")) {
            assertSame(RailsJson.Invalid, parse(bad), bad)
        }
        assertSame(RailsJson.Invalid, RailsJson.parse(byteArrayOf('"'.code.toByte(), 0xC0.toByte(), 0x80.toByte(), '"'.code.toByte())))
        assertEquals(1L, (parse("[".repeat(127) + "1" + "]".repeat(127)) as List<*>).let { var v: Any? = it; while (v is List<*>) v = v[0]; v })
        assertEquals(null, parse(" null "))
    }
}
