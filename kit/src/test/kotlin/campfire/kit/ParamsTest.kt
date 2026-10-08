package campfire.kit

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The tests of `once-campfire-rust/crates/kit/src/params.rs`, plus its `tests/params_vectors.rs`. */
class ParamsTest {
    private fun parse(qs: String): String = Params.tryParseNested(qs)

    @Test
    fun flatPairs() {
        assertEquals("""{"a":"1","b":"2"}""", parse("a=1&b=2"))
        assertEquals("{}", parse(""))
        assertEquals("""{"a":null}""", parse("a"))
        assertEquals("""{"a":""}""", parse("a="))
        assertEquals("""{"a":"2"}""", parse("a=1&a=2"))
        assertEquals("""{"a":"1"}""", parse("&&a=1&"))
        assertEquals("""{"a":"1","b":"2"}""", parse("a=1&  b=2"))
        assertEquals("{}", parse("=1"))
        assertEquals("""{"a":"b=c"}""", parse("a=b=c"))
        assertEquals("""{"a;b":"1"}""", parse("a;b=1"))
    }

    @Test
    fun decoding() {
        assertEquals("""{"a":"x y z"}""", parse("a=x+y%20z"))
        assertEquals("""{"a":{"b":"1"}}""", parse("a%5Bb%5D=1"))
        assertEquals("""{"café":"✓"}""", parse("caf%C3%A9=%E2%9C%93"))
        assertFailsWith<ParamError.Invalid> { parse("a=%") }
        assertFailsWith<ParamError.Invalid> { parse("a=%zz") }
        assertFailsWith<ParamError.Invalid> { parse("a=%FF") }
        assertEquals("{}", parse("=%FF"))
    }

    @Test
    fun nestedHashes() {
        assertEquals("""{"a":{"b":"1"}}""", parse("a[b]=1"))
        assertEquals("""{"a":{"b":{"c":"1","d":"2"}}}""", parse("a[b][c]=1&a[b][d]=2"))
        assertEquals("""{"a":{"b":{"c":"1"}}}""", parse("a[b]c=1"))
        assertEquals("""{"[a]":"1"}""", parse("[a]=1"))
        assertEquals("""{"a[":"1"}""", parse("a[=1"))
        assertEquals("""{"a":{"[b":"1"}}""", parse("a[b=1"))
        assertEquals("""{"a]":"1"}""", parse("a]=1"))
        assertEquals("""{"a":[{"]":"1"}]}""", parse("a[]]=1"))
        assertEquals("""{"a":{"[":{"]":"1"}}}""", parse("a[[]]=1"))
    }

    @Test
    fun arrays() {
        assertEquals("""{"a":["1","2"]}""", parse("a[]=1&a[]=2"))
        assertEquals("""{"a":[]}""", parse("a[]"))
        assertEquals("""{"a":[""]}""", parse("a[]=&a[]"))
        assertEquals("""{"a":[["1"]]}""", parse("a[][]=1"))
        assertEquals("""{"a":[[]]}""", parse("a[][]"))
        assertEquals("""{"a":{"b":["1","2"]}}""", parse("a[b][]=1&a[b][]=2"))
    }

    @Test
    fun hashesInsideArrays() {
        assertEquals("""{"a":[{"b":"1","c":"2"},{"b":"3"}]}""", parse("a[][b]=1&a[][c]=2&a[][b]=3"))
        assertEquals("""{"a":[{"b":{"c":"1","d":"2"}}]}""", parse("a[][b][c]=1&a[][b][d]=2"))
        assertEquals("""{"a":[{"b":{"c":"1"}},{"b":{"c":"2"}}]}""", parse("a[][b][c]=1&a[][b][c]=2"))
        assertEquals("""{"a":[{"b":["1","2"]}]}""", parse("a[][b][]=1&a[][b][]=2"))
        assertEquals("""{"a":[{"b":"1"}]}""", parse("a[]b=1"))
        assertEquals("""{"a":[{"b":null}]}""", parse("a[][b]"))
    }

    @Test
    fun nilReplacedByStructure() {
        assertEquals("""{"a":["1"]}""", parse("a&a[]=1"))
        assertEquals("""{"a":{"b":"1"}}""", parse("a&a[b]=1"))
    }

    @Test
    fun typeConflicts() {
        assertFailsWith<ParamError.Type> { parse("a=1&a[]=2") }
        assertFailsWith<ParamError.Type> { parse("a=1&a[b]=2") }
        assertFailsWith<ParamError.Type> { parse("a[]=1&a[b]=2") }
        assertFailsWith<ParamError.Type> { parse("a[b]=1&a[]=2") }
        assertFailsWith<ParamError.Type> { parse("a[b]=1&a[b][c]=2") }
        assertEquals("null", Params.parseNested("a=1&a[]=2"))
    }

    @Test
    fun depthLimit() {
        Params.tryParseNested("a" + "[b]".repeat(Params.DEPTH_LIMIT - 1) + "=1")
        assertFailsWith<ParamError.TooDeep> { parse("a" + "[b]".repeat(Params.DEPTH_LIMIT) + "=1") }
    }

    @Test
    fun formBodyLimits() {
        assertEquals("""{"a":"1"}""", Params.fromPairs(Params.formPairs("a=1\u0000".toByteArray())).toJson())
        val many = List(Params.FORM_PARAMS_LIMIT + 1) { "a=1" }.joinToString("&")
        assertFailsWith<ParamError.Limit> { Params.formPairs(many.toByteArray()) }
    }

    @Test
    fun jsonBodiesAreDeepMunged() {
        val params = Params.fromJsonBody("""{"a": [1, null, "x"], "b": {"c": null}, "d": true}""".toByteArray())
        assertEquals("""{"a":[1,"x"],"b":{"c":null},"d":true}""", params.toJson())
        assertEquals("""{"_json":[1,2]}""", Params.fromJsonBody("[1,2]".toByteArray()).toJson())
        assertFailsWith<ParamError.Parse> { Params.fromJsonBody("{bad".toByteArray()) }
    }

    @Test
    fun requireAndPermit() {
        val params = Params.fromQueryString(
            "user[name]=Jo&user[admin]=1&user[tags][]=a&user[settings][x][y]=1&user[bad][]=1&blank=+&user[date(1i)]=2024",
        )
        assertFailsWith<KitError.ParameterMissing> { params.require("blank") }
        assertFailsWith<KitError.ParameterMissing> { params.require("missing") }
        val user = params.require("user")
        val permitted = user.permit(
            listOf(Permit.Key("name"), Permit.Key("date"), Permit.ScalarArray("tags"), Permit.AnyHash("settings"), Permit.Key("bad")),
        )
        assertEquals("""{"name":"Jo","date(1i)":"2024","tags":["a"],"settings":{"x":{"y":"1"}}}""", permitted.toJson())
    }

    @Test
    fun permitNested() {
        val params = Params.fromQueryString("a[b][c]=1&a[b][d]=2&list[][c]=1&list[][d]=2&ff[0][c]=1&ff[1][c]=2")
        val nested = listOf(Permit.Key("c"))
        val permitted = params.permit(
            listOf(
                Permit.Nested("a", listOf(Permit.Nested("b", nested))),
                Permit.Nested("list", nested),
                Permit.Nested("ff", nested),
            ),
        )
        assertEquals("""{"a":{"b":{"c":"1"}},"list":[{"c":"1"}],"ff":{"0":{"c":"1"},"1":{"c":"2"}}}""", permitted.toJson())
    }

    @Test
    fun fetchDefault() {
        val params = Params.fromQueryString("user_ids[]=1&user_ids[]=2")
        assertEquals("""["1","2"]""", params.fetch("user_ids", Param.Arr(ArrayList())).toJson())
        assertEquals("[]", params.fetch("nope", Param.Arr(ArrayList())).toJson())
    }

    @Test
    fun manyKeysBuildInLinearTime() {
        val body = (0 until 100_000).joinToString(",", "{", "}") { "\"k$it\":$it" }.toByteArray()
        val started = System.nanoTime()
        val map = Params.fromJsonBody(body)
        assertEquals(100_000, map.size)
        val elapsed = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsed < 1000, "${elapsed}ms")
    }

    @Test
    fun jsonNumbersReadAndPrintLikeSerde() {
        val params = Params.fromJsonBody("""{"i":-3,"u":18446744073709551615,"big":18446744073709551616,"f":1.5,"e":1e20,"z":-0,"h":100.0}""".toByteArray())
        assertEquals("""{"i":-3,"u":18446744073709551615,"big":1.8446744073709552e19,"f":1.5,"e":1e20,"z":-0.0,"h":100.0}""", params.toJson())
        assertEquals("1e20", params["e"]!!.toS())
        assertFailsWith<ParamError.Parse> { Params.fromJsonBody("""{"x":1e400}""".toByteArray()) }
        assertFailsWith<ParamError.Parse> { Params.fromJsonBody("\"\\ud800\"".toByteArray()) }
        assertFailsWith<ParamError.Parse> { Params.fromJsonBody("{} x".toByteArray()) }
        assertFailsWith<ParamError.Parse> { Params.fromJsonBody(ByteArray(0)) }
        Params.fromJsonBody(("[".repeat(127) + "]".repeat(127)).toByteArray())
        assertFailsWith<ParamError.Parse> { Params.fromJsonBody(("[".repeat(128) + "]".repeat(128)).toByteArray()) }
    }

    @Test
    fun queryStringsParseLikeRails() {
        val text = javaClass.getResourceAsStream("/params_vectors.json")!!.readBytes()
        val failures = ArrayList<String>()
        var count = 0
        JsonFactory().createParser(text).use { parser ->
            check(parser.nextToken() == JsonToken.START_ARRAY)
            while (parser.nextToken() == JsonToken.START_OBJECT) {
                var input: String? = null
                var output: String? = null
                while (parser.nextToken() == JsonToken.FIELD_NAME) {
                    val field = parser.currentName()
                    val token = parser.nextToken()
                    if (field == "input") input = parser.text else output = canonical(parser, token)
                }
                count++
                val actual = Params.parseNested(input!!)
                if (actual != output) failures.add("${input}\n  rails: $output\n  ours:  $actual")
            }
        }
        assertTrue(count > 0)
        assertTrue(failures.isEmpty(), "${failures.size} of $count differ:\n" + failures.joinToString("\n"))
    }

    private fun canonical(parser: JsonParser, token: JsonToken): String {
        val out = StringBuilder()
        writeCanonical(parser, token, out)
        return out.toString()
    }

    private fun writeCanonical(parser: JsonParser, token: JsonToken, out: StringBuilder) {
        when (token) {
            JsonToken.START_OBJECT -> {
                out.append('{')
                var first = true
                while (parser.nextToken() == JsonToken.FIELD_NAME) {
                    if (!first) out.append(',')
                    first = false
                    appendJsonString(out, parser.currentName())
                    out.append(':')
                    writeCanonical(parser, parser.nextToken(), out)
                }
                out.append('}')
            }
            JsonToken.START_ARRAY -> {
                out.append('[')
                var first = true
                while (true) {
                    val next = parser.nextToken()
                    if (next == JsonToken.END_ARRAY) break
                    if (!first) out.append(',')
                    first = false
                    writeCanonical(parser, next, out)
                }
                out.append(']')
            }
            JsonToken.VALUE_STRING -> appendJsonString(out, parser.text)
            JsonToken.VALUE_NULL -> out.append("null")
            else -> out.append(parser.text)
        }
    }
}
