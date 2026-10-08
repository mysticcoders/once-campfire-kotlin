package campfire.rails

import kotlin.test.Test
import kotlin.test.assertEquals

/** `once-campfire-rust/crates/rails_compat/src/signed_id.rs` tests. */
class SignedIdTest {
    @Test
    fun combinesPurposes() {
        assertEquals("user/avatar", SignedId.combinePurposes("User", "avatar"))
        assertEquals("user", SignedId.combinePurposes("User", null))
        assertEquals("rooms/open", SignedId.combinePurposes("Rooms::Open", ""))
        assertEquals("http_request/x", SignedId.combinePurposes("HTTPRequest", "x"))
    }
}
