package campfire.kit

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The tests of `once-campfire-rust/crates/kit/src/cookies.rs`, with a stand-in for the crypto. */
class CookiesTest {
    /** Reversible "signing" that only accepts what it produced for the same name. */
    private object StubCrypto : CookieCrypto {
        override fun sign(name: String, value: String, expiresAt: Instant?) = "S:$name:${expiresAt?.epochSecond}:$value"

        override fun verify(name: String, raw: String, now: Instant): String? {
            if (!raw.startsWith("S:$name:")) return null
            val rest = raw.substring(name.length + 3)
            return rest.substring(rest.indexOf(':') + 1)
        }

        override fun encrypt(name: String, valueJson: String, expiresAt: Instant?) = "E:$name:$valueJson"

        override fun decrypt(name: String, raw: String, now: Instant): String? =
            if (raw.startsWith("E:$name:")) raw.substring(name.length + 3) else null
    }

    private val clock = Clock.fixed(Instant.parse("2024-06-01T12:00:00Z"), ZoneOffset.UTC)

    private fun jar(header: String) = CookieJar.fromHeaders(listOf(header), StubCrypto, clock)

    @Test
    fun parsesRequestCookies() {
        val jar = jar("a=1; b=x%20y+z;c=3; a=2; d")
        assertEquals("1", jar["a"])
        assertEquals("x y z", jar["b"])
        assertEquals("3", jar["c"])
        assertEquals("", jar["d"])
        assertNull(jar["zz"])
    }

    @Test
    fun setsPlainCookiesWithRailsDefaults() {
        val jar = jar("")
        jar["last_room"] = "42"
        assertEquals(listOf("last_room=42; path=/; samesite=lax"), jar.setCookieHeaders(false, "example.com"))
    }

    @Test
    fun unchangedValuesAreNotRewrittenUnlessExpiring() {
        val jar = jar("last_room=42")
        jar["last_room"] = "42"
        assertTrue(jar.setCookieHeaders(false, "h").isEmpty())
        jar.set("last_room", Cookie("42").permanent())
        assertEquals(
            listOf("last_room=42; path=/; expires=Wed, 01 Jun 2044 12:00:00 GMT; samesite=lax"),
            jar.setCookieHeaders(false, "h"),
        )
    }

    @Test
    fun escapesValues() {
        val jar = jar("")
        jar["x"] = "a b+c/="
        assertEquals(listOf("x=a+b%2Bc%2F%3D; path=/; samesite=lax"), jar.setCookieHeaders(false, "h"))
    }

    @Test
    fun signedPermanentHttponlyRoundTrip() {
        val jar = jar("")
        jar.setSigned("session_token", Cookie("tok").permanent().httponly())
        val headers = jar.setCookieHeaders(true, "h")
        assertEquals(1, headers.size)
        assertTrue(headers[0].startsWith("session_token="))
        assertTrue(headers[0].endsWith("; path=/; expires=Wed, 01 Jun 2044 12:00:00 GMT; httponly; samesite=lax"))
        assertEquals("tok", jar.signed("session_token"))

        val raw = jar["session_token"]!!
        val next = CookieJar.fromHeaders(listOf("session_token=${CookieJar.escape(raw)}"), StubCrypto, clock)
        assertEquals("tok", next.signed("session_token"))
    }

    @Test
    fun tamperedSignedCookiesReadAsNil() {
        val jar = jar("session_token=forged")
        assertNull(jar.signed("session_token"))
        assertEquals("forged", jar["session_token"])
    }

    @Test
    fun encryptedRoundTrip() {
        val jar = jar("")
        jar.setEncrypted("secret", """{"a":1}""")
        assertEquals("""{"a":1}""", jar.encrypted("secret"))
    }

    @Test
    fun overflow() {
        val jar = jar("")
        assertFailsWith<KitError.CookieOverflow> { jar.setSigned("big", Cookie("x".repeat(CookieJar.MAX_COOKIE_SIZE))) }
    }

    @Test
    fun deleteOnlyWhenPresent() {
        val jar = jar("session_token=abc")
        jar.delete("missing")
        jar.delete("session_token")
        assertTrue(jar.isDeleted("session_token"))
        assertNull(jar["session_token"])
        assertEquals(
            listOf("session_token=; path=/; max-age=0; expires=Thu, 01 Jan 1970 00:00:00 GMT; samesite=lax"),
            jar.setCookieHeaders(false, "h"),
        )
    }

    @Test
    fun secureCookiesNeedSsl() {
        val jar = jar("")
        jar.set("s", Cookie("1").secure())
        assertTrue(jar.setCookieHeaders(false, "example.com").isEmpty())
        assertEquals(1, jar.setCookieHeaders(false, "x.onion").size)
        assertEquals(listOf("s=1; path=/; secure; samesite=lax"), jar.setCookieHeaders(true, "example.com"))
    }

    @Test
    fun manyCookiesParseInLinearTime() {
        val header = (0 until 100_000).joinToString("; ") { "c$it=1" }
        val started = System.nanoTime()
        assertEquals(100_000, CookieJar.parseCookieHeader(header).size)
        val elapsed = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsed < 1000, "${elapsed}ms")
    }

    @Test
    fun clockHelpers() {
        assertEquals("2044-02-29T00:00:00Z", CookieJar.yearsFrom(Instant.parse("2024-02-29T00:00:00Z"), 20).toString())
        assertEquals("Thu, 01 Jan 1970 00:00:00 GMT", CookieJar.httpdate(Instant.EPOCH))
    }
}
