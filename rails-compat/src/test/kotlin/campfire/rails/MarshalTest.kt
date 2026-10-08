package campfire.rails

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `once-campfire-rust/crates/rails_compat/src/marshal.rs` tests. */
class MarshalTest {
    @Test
    fun loadsStrings() {
        val dumped = bytes("\u0004\u0008I\"\u001agid://campfire/User/1\u0006:\u0006ET")
        assertContentEquals("gid://campfire/User/1".toByteArray(), Marshal.loadString(dumped))
        val long = bytes("\u0004\u0008I\"\u0002,\u0001") + ByteArray(300) { 'x'.code.toByte() }
        assertEquals(300, Marshal.loadString(long)!!.size)
        assertNull(Marshal.loadString(bytes("\u0004\u0008i\u0006")))
    }

    private fun bytes(s: String): ByteArray = s.toByteArray(Charsets.ISO_8859_1)
}
