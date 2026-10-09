package campfire.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The response cache's settings, as `crates/campfire/src/config.rs`'s tests check them. */
class ConfigTest {
    private fun config(vararg env: Pair<String, String>): Config {
        val values = mapOf("SECRET_KEY_BASE" to "abc", *env)
        return Config { values[it] }
    }

    @Test
    fun `the response cache defaults to 64 MB`() {
        assertEquals(64L * 1024 * 1024, config().responseCacheBytes)
    }

    @Test
    fun `response cache limit and off switch`() {
        assertEquals(0L, config("CAMPFIRE_RESPONSE_CACHE_MB" to "0").responseCacheBytes)
        assertEquals(8L * 1024 * 1024, config("CAMPFIRE_RESPONSE_CACHE_MB" to "8").responseCacheBytes)
        assertFailsWith<IllegalArgumentException> { config("CAMPFIRE_RESPONSE_CACHE_MB" to "-1") }
        assertFailsWith<IllegalArgumentException> { config("CAMPFIRE_RESPONSE_CACHE_MB" to "lots") }
    }
}
