package campfire.kit

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The tests of `once-campfire-rust/crates/kit/src/body.rs`. */
class BodyParserTest {
    @Test
    fun dispositions() {
        var part = BodyParser.parseDisposition("""form-data; name="message[attachment]"; filename="C:\Users\me\cat.png"""")
        assertEquals("message[attachment]", part.name)
        assertEquals("cat.png", part.filename)

        part = BodyParser.parseDisposition("""form-data; name="a"; filename="with \"quotes\".txt"""")
        assertEquals("with \"quotes\".txt", part.filename)

        part = BodyParser.parseDisposition("form-data; name=file; filename*=UTF-8''r%C3%A9sum%C3%A9.pdf")
        assertEquals("file", part.name)
        assertEquals("résumé.pdf", part.filename)

        part = BodyParser.parseDisposition("""form-data; name="a"; filename="100%.txt"""")
        assertEquals("100%.txt", part.filename)
    }

    private fun multipartBody(boundary: String, parts: List<Pair<String, String>>): ByteArray {
        val body = StringBuilder()
        for ((headers, content) in parts) body.append("--$boundary\r\n$headers\r\n\r\n$content\r\n")
        body.append("--$boundary--\r\n")
        return body.toString().toByteArray()
    }

    private fun contentType(value: String) = Headers().add("content-type", value)

    @Test
    fun multipartFieldsAndFiles() {
        val boundary = "XyZ"
        val body = multipartBody(
            boundary,
            listOf(
                """Content-Disposition: form-data; name="_method"""" to "patch",
                """Content-Disposition: form-data; name="user[name]"""" to "Jo",
                "Content-Disposition: form-data; name=\"user[avatar]\"; filename=\"me.png\"\r\nContent-Type: image/png" to "PNGDATA",
                """Content-Disposition: form-data; name="user[empty]"; filename=""""" to "",
                """Content-Disposition: form-data; name="tags[]"""" to "a",
                """Content-Disposition: form-data; name="tags[]"""" to "b",
            ),
        )
        val parsed = BodyParser.parse("POST", contentType("multipart/form-data; boundary=$boundary"), body)
        val params = parsed.paramsOrThrow()
        assertEquals(0, parsed.raw.size)
        assertEquals("patch", params.str("_method"))
        val user = params["user"]!!
        assertEquals("Jo", user["name"]?.asStr())
        assertNull(user["empty"])
        val avatar = user["avatar"]!!.asFile()!!
        assertEquals("me.png", avatar.originalFilename)
        assertEquals("image/png", avatar.contentType)
        assertEquals(7, avatar.size)
        assertContentEquals("PNGDATA".toByteArray(), avatar.read())
        assertEquals("content-disposition: form-data; name=\"user[avatar]\"; filename=\"me.png\"\r\ncontent-type: image/png\r\n", avatar.headers)
        assertEquals("""["a","b"]""", params["tags"]!!.toJson())
    }

    @Test
    fun multipartSizeLimit() {
        val body = multipartBody("B", listOf("""Content-Disposition: form-data; name="f"; filename="x"""" to "x".repeat(1000)))
        assertFailsWith<BodyTooLarge> { BodyParser.parse("POST", contentType("multipart/form-data; boundary=B"), body, 100) }
    }

    @Test
    fun multipartTextFieldsAreCappedTogether() {
        val half = "x".repeat(BodyParser.MULTIPART_TEXT_LIMIT / 2)
        val headers = contentType("multipart/form-data; boundary=B")
        val field = """Content-Disposition: form-data; name="a[]""""
        val file = """Content-Disposition: form-data; name="f"; filename="x""""

        val fits = multipartBody("B", listOf(field to half, field to half, file to half))
        assertTrue(BodyParser.parse("POST", headers, fits).params != null)
        val over = multipartBody("B", listOf(field to half, field to half, field to "x"))
        assertFailsWith<BodyTooLarge> { BodyParser.parse("POST", headers, over) }
    }

    @Test
    fun formsJsonAndRawBodies() {
        var parsed = BodyParser.parse("POST", contentType("application/x-www-form-urlencoded"), "a[b]=1&c=2".toByteArray())
        assertEquals("""{"a":{"b":"1"},"c":"2"}""", parsed.paramsOrThrow().toJson())
        assertContentEquals("a[b]=1&c=2".toByteArray(), parsed.raw)

        parsed = BodyParser.parse("POST", contentType("application/json"), """{"url":"x"}""".toByteArray())
        assertEquals("""{"url":"x"}""", parsed.paramsOrThrow().toJson())

        parsed = BodyParser.parse("POST", contentType("text/plain"), "Hello!".toByteArray())
        assertTrue(parsed.paramsOrThrow().isEmpty())
        assertContentEquals("Hello!".toByteArray(), parsed.raw)

        parsed = BodyParser.parse("POST", Headers(), "Hello!".toByteArray())
        assertEquals("""{"Hello!":null}""", parsed.paramsOrThrow().toJson())
        parsed = BodyParser.parse("PUT", Headers(), "Hello!".toByteArray())
        assertTrue(parsed.paramsOrThrow().isEmpty())

        assertFailsWith<BodyTooLarge> { BodyParser.parse("POST", Headers(), "x".repeat(20).toByteArray(), 10) }
    }

    @Test
    fun malformedMultipartIsAParseError() {
        val headers = contentType("multipart/form-data; boundary=B")
        val truncated = "--B\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\nvalue".toByteArray()
        assertTrue(BodyParser.parse("POST", headers, truncated).error is ParamError.Parse)
        val unterminated = "--B\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\nvalue\r\n--B".toByteArray()
        assertTrue(BodyParser.parse("POST", headers, unterminated).error is ParamError.Parse)
    }

    @Test
    fun boundariesParseLikeTheMimeCrate() {
        assertEquals("XyZ", BodyParser.parseBoundary("multipart/form-data; boundary=XyZ"))
        assertEquals("a b", BodyParser.parseBoundary("Multipart/Form-Data; BOUNDARY=\"a b\""))
        assertEquals("x", BodyParser.parseBoundary("multipart/form-data;charset=utf-8; boundary=x"))
        assertNull(BodyParser.parseBoundary("multipart/mixed; boundary=x"))
        assertNull(BodyParser.parseBoundary("multipart/form-data ; boundary=x"))
        assertNull(BodyParser.parseBoundary("multipart/form-data"))
    }
}
