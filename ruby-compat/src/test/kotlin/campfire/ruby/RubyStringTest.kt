package campfire.ruby

import kotlin.test.Test
import kotlin.test.assertEquals

/** `once-campfire-rust/crates/ruby/src/string.rs` tests. */
class RubyStringTest {
    @Test
    fun stripsNulAndAsciiWhitespace() {
        assertEquals("x", strip("\u0000 \t\u000b x \u0000\n"))
        assertEquals(" x ", strip("  x  "))
    }
}
