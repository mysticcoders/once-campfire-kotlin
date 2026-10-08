package campfire.assets

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The views module's interim `campfire.views.Assets` reads a digest map and three tag files taken
 * from the Rust app's output (assets/src/test/resources/rust-output/); this module's generated values
 * must be identical to every one of them before views switches over.
 */
class ViewsEquivalenceTest {
    private val dir = File("assets/src/test/resources/rust-output")

    @Test
    fun everyDigestedPathMatches() {
        val expected = ObjectMapper().readTree(dir.resolve("assets.json")).properties().associate { (name, path) -> name to path.asText() }
        assertTrue(expected.isNotEmpty())
        val mismatched = expected.filter { (name, path) -> Assets.tryAssetPath(name).getOrNull() != path }
        assertEquals(emptyMap(), mismatched.mapValues { (name, path) -> "$path, ours ${Assets.tryAssetPath(name).getOrNull()}" })
    }

    @Test
    fun stylesheetTagsMatch() {
        assertContentEquals(dir.resolve("stylesheet_tags.html").readBytes(), Assets.stylesheetTags)
    }

    @Test
    fun importmapTagsMatch() {
        assertContentEquals(dir.resolve("importmap_tags.html").readBytes(), Assets.importmapTags)
        assertEquals(dir.resolve("importmap_tags.html").readText(), Assets.importmapTagsHtml)
    }

    @Test
    fun linkHeaderMatches() {
        assertEquals(dir.resolve("link_header.txt").readText().trim(), Assets.linkHeader)
    }
}
