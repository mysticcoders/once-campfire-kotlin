package campfire.rails

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/** `once-campfire-rust/crates/rails_compat/src/metadata.rs` tests. */
class MetadataTest {
    @Test
    fun iso8601TruncatesToMilliseconds() {
        assertEquals("2026-01-01T12:00:00.123Z", Metadata.iso8601Millis(Instant.parse("2026-01-01T12:00:00.123999Z")))
        assertEquals("2046-01-01T12:00:00.000Z", Metadata.iso8601Millis(Instant.parse("2046-01-01T12:00:00Z")))
    }
}
