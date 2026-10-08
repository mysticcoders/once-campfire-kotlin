package campfire.kit

import campfire.rails.Secrets
import campfire.rails.TestClock
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The tests of `once-campfire-rust/crates/kit/src/session.rs`, plus the session's key order. */
class SessionTest {
    @Test
    fun flashRoundTripDiscardsAfterOneRequest() {
        val flash = Flash()
        flash.setNotice("✓")
        val stored = flash.toSessionValue()!!
        assertEquals(mapOf("discard" to emptyList<Any?>(), "flashes" to mapOf("notice" to "✓")), stored)

        val next = Flash.fromSessionValue(stored)
        assertEquals("✓", next.notice)
        assertNull(next.toSessionValue())
    }

    @Test
    fun flashNowIsNotPersisted() {
        val flash = Flash()
        flash.now("alert", "Too many requests or unauthorized.")
        assertEquals("Too many requests or unauthorized.", flash.alert)
        assertNull(flash.toSessionValue())
    }

    @Test
    fun flashKeepAndLegacyDiscardLists() {
        val stored = mapOf("discard" to listOf("alert"), "flashes" to mapOf("notice" to "hi", "alert" to "gone"))
        val flash = Flash.fromSessionValue(stored)
        assertNull(flash.alert)
        flash.keep("notice")
        assertEquals(mapOf("discard" to emptyList<Any?>(), "flashes" to mapOf("notice" to "hi")), flash.toSessionValue())
    }

    @Test
    fun sidsAre32HexChars() {
        val sid = Session.generateSid()
        assertEquals(32, sid.length)
        assertTrue(sid.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun removingAKeyMovesTheLastIntoItsPlaceAsSerdeJsonDoes() {
        val clock = TestClock.frozenAt(Instant.parse("2024-06-01T12:00:00Z"))
        val kit = Kit(KitConfig(), Secrets("test-secret"), clock)
        val jar = CookieJar.fromHeaders(emptyList(), kit.cookieCrypto, kit.javaClock)
        val session = Session().load(jar)
        session["flash"] = "x"
        session["a"] = 1L
        session["b"] = 2L
        session.remove("flash")
        session.commit(jar, clock.now())
        val raw = jar.setCookieHeaders(false, "h").single().substringAfter('=').substringBefore(';')
        val json = kit.cookieCrypto.decrypt(Session.KEY, campfire.rails.Cookies.unescape(raw), clock.now())!!
        val keys = (campfire.rails.RailsJson.parse(json.toByteArray()) as Map<*, *>).keys.toList()
        assertEquals(listOf("session_id", "b", "a"), keys)
    }

    @Test
    fun anUnchangedSessionWritesNothing() {
        val kit = Kit(KitConfig(), Secrets("test-secret"))
        val jar = CookieJar.fromHeaders(emptyList(), kit.cookieCrypto, kit.javaClock)
        val session = Session().load(jar)
        session.commit(jar, Instant.now())
        assertTrue(jar.setCookieHeaders(false, "h").isEmpty())
    }
}
