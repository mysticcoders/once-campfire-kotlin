package campfire.ruby

import kotlin.test.Test
import kotlin.test.assertEquals

/** `once-campfire-rust/crates/ruby/src/uri.rs` tests. */
class UriTest {
    @Test
    fun escapesLikeRuby() {
        assertEquals("a%2A~+b-._%2B%C3%A9", cgiEscape("a*~ b-._+é"))
        assertEquals("a%2A~%20b-._%2B%C3%A9", urlEncode("a*~ b-._+é"))
    }
}
