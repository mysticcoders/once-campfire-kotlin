package campfire.assets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The Rust crate's helper tests (`once-campfire-rust/crates/assets/src/helpers.rs`, `mod tests`). */
class HelpersTest {
    @Test
    fun digestsLogicalPaths() {
        assertEquals("/assets/campfire-icon-3d9986c5.png", Assets.path("campfire-icon.png"))
        assertEquals("/assets/bot-8a69692e.svg", Assets.imagePath("bot.svg"))
        assertEquals("/assets/56k-67359aa6.mp3", Assets.audioPath("56k.mp3"))
        assertEquals("/assets/" + Assets.digestedPath("screenshots/android-chat.png")!!, Assets.path("screenshots/android-chat.png"))
    }

    @Test
    fun keepsTailsAndPassesThroughUrlsAndAbsolutePaths() {
        assertEquals("/assets/bot-8a69692e.svg?v=1#x", Assets.path("bot.svg?v=1#x"))
        assertEquals("https://example.com/a.png", Assets.path("https://example.com/a.png"))
        assertEquals("//cdn.example.com/a.png", Assets.path("//cdn.example.com/a.png"))
        assertEquals("data:image/png;base64,xx", Assets.path("data:image/png;base64,xx"))
        assertEquals("/rooms/1", Assets.path("/rooms/1"))
        assertEquals("", Assets.path(""))
    }

    @Test
    fun appendsTypeExtensions() {
        assertEquals(Assets.stylesheetPath("base.css"), Assets.stylesheetPath("base"))
        assertTrue(Assets.javascriptPath("application").startsWith("/assets/application-"))
    }

    @Test
    fun urlsJoinTheBaseUrl() {
        assertEquals("https://chat.example.com" + Assets.imagePath("add.svg"), Assets.imageUrl("https://chat.example.com", "add.svg"))
    }

    @Test
    fun missingAssetsAreErrors() {
        assertEquals("The asset 'nope.png' was not found in the load path.", Assets.tryAssetPath("nope.png").exceptionOrNull()?.message)
        assertFailsWith<MissingAssetError> { Assets.path("nope.png") }
    }
}
