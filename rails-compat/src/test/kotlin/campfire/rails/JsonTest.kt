package campfire.rails

import kotlin.test.Test
import kotlin.test.assertEquals

/** `once-campfire-rust/crates/rails_compat/src/json.rs` tests. */
class JsonTest {
    @Test
    fun escapesHtmlEntitiesButNotSeparatorsOrSlashes() {
        assertEquals(
            "{\"key\":\"\\u003ca href=\\\"/x\\\"\\u003e\\u0026\\u003c/a\\u003e \"}",
            RailsJson.encode(mapOf("key" to "<a href=\"/x\">&</a> ")),
        )
        assertEquals("\"<&>\"", RailsJson.generate("<&>"))
        assertEquals(
            "\"\\u003ca href=\\\"x\\\"\\u003e\\u0026' é\\n\\t\\u0001\u007f/\\u003c/a\\u003e\"",
            RailsJson.encode("<a href=\"x\">&' é\n\t\u0001\u007f/</a>"),
        )
    }

    @Test
    fun escapesControlCharactersLikeTheJsonGem() {
        assertEquals("\"\\u001f\\n\\t\\b\\f\u007fé\"", RailsJson.encode("\u001f\n\t\u0008\u000c\u007fé"))
    }

    @Test
    fun floatsMatchTheJsonGem() {
        val cases = listOf(
            320.0 to "320.0",
            65.84 to "65.84",
            -2.5 to "-2.5",
            0.0 to "0.0",
            -0.0 to "-0.0",
            0.1 to "0.1",
            0.0001 to "0.0001",
            0.00001 to "0.00001",
            1.25e-5 to "0.0000125",
            1.5e-7 to "0.00000015",
            -1.5e-7 to "-0.00000015",
            1e-7 to "0.0000001",
            1.2e-9 to "0.0000000012",
            1.23456789012e-8 to "0.0000000123456789012",
            1e-10 to "1e-10",
            5e-324 to "5e-324",
            1e14 to "100000000000000.0",
            -1e14 to "-100000000000000.0",
            123456789012345.6 to "123456789012345.6",
            1e15 to "1e+15",
            -1e15 to "-1e+15",
            1.5e15 to "1.5e+15",
            1234567890123456.0 to "1.234567890123456e+15",
            9007199254740992.0 to "9.007199254740992e+15",
            1e16 to "1e+16",
            12345678901234567.0 to "1.2345678901234568e+16",
            1e21 to "1e+21",
            1e100 to "1e+100",
            Double.MAX_VALUE to "1.7976931348623157e+308",
        )
        for ((f, json) in cases) {
            assertEquals(json, RailsJson.generate(f), "$f")
            assertEquals("[$json]", RailsJson.encode(listOf(f)), "$f")
        }
        assertEquals("null", RailsJson.encode(Double.NaN))
        assertEquals("null", RailsJson.encode(Double.POSITIVE_INFINITY))
        assertEquals("""{"a":1e+16,"b":[0.00001]}""", RailsJson.encode(mapOf("a" to 1e16, "b" to listOf(1e-5))))
    }
}
