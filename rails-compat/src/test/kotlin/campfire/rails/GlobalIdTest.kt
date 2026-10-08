package campfire.rails

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `once-campfire-rust/crates/rails_compat/src/global_id.rs` tests. */
class GlobalIdTest {
    @Test
    fun parsesAndFormats() {
        val gid = GlobalId.parse("gid://campfire/Rooms::Open/1?expires_in")!!
        assertEquals(GlobalId("Rooms::Open", 1), gid)
        assertEquals("Z2lkOi8vY2FtcGZpcmUvUm9vbXM6Ok9wZW4vMQ", gid.toParam())
        assertEquals(gid, GlobalId.fromParam(gid.toParam()))
        assertNull(GlobalId.parse("gid://campfire/User"))
    }
}
