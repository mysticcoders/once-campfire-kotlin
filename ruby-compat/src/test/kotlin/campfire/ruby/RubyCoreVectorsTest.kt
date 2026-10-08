package campfire.ruby

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every function against what Ruby, Rack, Active Record and Addressable answer in the reference
 * (`vectors/ruby_core.json`, written by `reference-tools/ruby_core.rb`), mirroring
 * `once-campfire-rust/crates/ruby/tests/ruby_core.rs`. Each test lists every input it gets wrong,
 * not just the first. The vectors' `rack_escape` column isn't checked, as in Rust, since nothing
 * here ports `Rack::Utils.escape`.
 */
class RubyCoreVectorsTest {
    private companion object {
        val VECTORS: JsonNode = ObjectMapper().readTree(File(System.getProperty("campfire.vectors"), "ruby_core.json"))
    }

    private fun cases(section: String): List<JsonNode> = VECTORS.get(section).toList()

    /** Each string, with what Ruby's version of [function] made of it. */
    private fun strings(function: String): List<Pair<String, JsonNode>> =
        cases("strings").map { it.get("input").asText() to it.get(function) }

    /** Fails listing every case where the port's answer differs from Ruby's. */
    private fun <T> assertMatchesRuby(function: String, results: List<Triple<String, T, T>>) {
        val mismatches = results
            .filter { (_, port, ruby) -> port != ruby }
            .map { (input, port, ruby) -> "$function($input) is $port, Ruby's $ruby" }
        assertTrue(mismatches.isEmpty(), "${mismatches.size} mismatches:\n${mismatches.joinToString("\n")}")
    }

    /** A string as Rust's `{:?}` would quote it, near enough to find the case. */
    private fun quoted(s: String): String = buildString {
        append('"')
        for (c in s) if (c < ' ' || c.code in 0x7f..0x9f) append("\\u{%x}".format(c.code)) else append(c)
        append('"')
    }

    private fun sameStrings(function: String, port: (String) -> String) {
        assertMatchesRuby(function, strings(function).map { (input, ruby) -> Triple(quoted(input), port(input), ruby.asText()) })
    }

    /** Ruby's answer, and what saturating at the Long bounds makes of it. */
    @Test
    fun toI() {
        assertMatchesRuby(
            "to_i",
            strings("to_i").map { (input, ruby) ->
                val text = ruby.asText()
                val checked = text.toLongOrNull()
                val saturated = checked ?: if (text.startsWith('-')) Long.MIN_VALUE else Long.MAX_VALUE
                Triple(quoted(input), toI(input) to toIChecked(input), saturated to checked)
            },
        )
    }

    /** `SQLite3Integer#serialize`: nil, or a RangeError past 8 bytes. */
    @Test
    fun integerCast() {
        assertMatchesRuby(
            "integer_cast",
            strings("integer_cast").map { (input, ruby) ->
                val expected = if (ruby.isTextual) ruby.asText().toLongOrNull() else null
                Triple(quoted(input), integerCast(input), expected)
            },
        )
    }

    /** Compared bit for bit, so -0.0 isn't 0.0. */
    @Test
    fun toF() {
        assertMatchesRuby(
            "to_f",
            strings("to_f").map { (input, ruby) ->
                val expected = ruby.asText().toDouble()
                Triple(quoted(input), toF(input).toRawBits(), expected.toRawBits())
            },
        )
    }

    @Test
    fun strip() {
        sameStrings("strip", ::strip)
    }

    @Test
    fun htmlEscape() {
        sameStrings("html_escape", Erb::htmlEscape)
    }

    @Test
    fun cgiEscape() {
        sameStrings("cgi_escape", ::cgiEscape)
    }

    /** And Addressable's `encode_component(s, UNRESERVED)`, which pagination links use. */
    @Test
    fun urlEncode() {
        sameStrings("url_encode", ::urlEncode)
        sameStrings("addressable_unreserved", ::urlEncode)
    }

    @Test
    fun floatToS() {
        assertMatchesRuby(
            "float_to_s",
            cases("floats").map { case ->
                val float = java.lang.Double.longBitsToDouble(java.lang.Long.parseUnsignedLong(case.get("bits").asText(), 16))
                Triple(float.toString(), floatToS(float), case.get("to_s").asText())
            },
        )
    }

    @Test
    fun byteRanges() {
        assertMatchesRuby(
            "byte_ranges",
            cases("byte_ranges").map { case ->
                val header = case.get("header").takeIf { it.isTextual }?.asText()
                val size = case.get("size").asLong()
                val ranges = case.get("ranges")
                val expected = if (ranges.isArray) ranges.map { it.get(0).asLong()..it.get(1).asLong() } else null
                Triple("${header?.let(::quoted)}, $size", Rack.byteRanges(header, size), expected)
            },
        )
    }
}
