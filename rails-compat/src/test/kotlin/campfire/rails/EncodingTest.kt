package campfire.rails

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

/** `once-campfire-rust/crates/rails_compat/src/encoding.rs` tests. */
class EncodingTest {
    @Test
    fun urlsafeDecodeIsLenientLikeRuby() {
        assertContentEquals("A".toByteArray(), RubyBase64.urlsafeDecode("QQ"))
        assertContentEquals("A".toByteArray(), RubyBase64.urlsafeDecode("QQ=="))
        assertNull(RubyBase64.urlsafeDecode("QQ="))
        assertNull(RubyBase64.urlsafeDecode("QR"))
        assertContentEquals(RubyBase64.urlsafeDecode("a-b_"), RubyBase64.urlsafeDecode("a+b/"))
    }
}
