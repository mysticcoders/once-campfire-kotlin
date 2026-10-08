package campfire.rails

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `once-campfire-rust/crates/rails_compat/src/content_disposition.rs` tests. */
class ContentDispositionTest {
    /**
     * The filenames in `vectors/storage.json` (`reference-tools/storage/generate.rb`), as the
     * reference formats them after `ActiveStorage::Filename#sanitized`.
     */
    @Test
    fun formatsLikeRails() {
        val vectors = ObjectMapper().readTree(File(System.getProperty("campfire.vectors"), "storage.json"))
        val filenames = vectors["filenames"]
        assertTrue(filenames.size() > 0)
        for (f in filenames) {
            val sanitized = f["sanitized"].asText()
            assertEquals(f["inline"].asText(), ContentDisposition.format("inline", sanitized), sanitized)
            assertEquals(f["attachment"].asText(), ContentDisposition.format("attachment", sanitized), sanitized)
        }
    }

    @Test
    fun transliteratesBeyondLatin1() {
        assertEquals(
            "inline; filename=\"Lodz x.pdf\"; filename*=UTF-8''%C5%81%C3%B3d%C5%BA%20%C3%97.pdf",
            ContentDisposition.format("inline", "Łódź ×.pdf"),
        )
    }
}
