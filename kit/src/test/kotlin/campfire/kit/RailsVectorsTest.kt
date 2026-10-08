package campfire.kit

import campfire.rails.Cookies
import campfire.rails.RailsJson
import campfire.rails.Secrets
import campfire.rails.TestClock
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Old-browser-tab continuity (`once-campfire-rust/crates/kit/tests/rails_vectors.rs`): a session
 * cookie and a signed cookie issued by the reference Rails app (`vectors/rails_compat.json`) are
 * accepted, and what the kit writes back decodes to the same session. Forgery protection is by
 * `Sec-Fetch-Site` rather than Rails' tokens, so a tab opened before an upgrade keeps working
 * without one.
 */
class RailsVectorsTest {
    private val vectors = RailsJson.parse(File(System.getProperty("campfire.vectors"), "rails_compat.json").readBytes()) as Map<*, *>
    private val session = vectors["session"] as Map<*, *>
    private val secrets = Secrets(vectors["secret_key_base"] as String)
    private val now = Instant.parse(vectors["now"] as String)

    private val createSession = Action { c ->
        c.verifyAuthenticityToken()
        val session = c.session()
        val body = "{\"id\":${RailsJson.generate(session.id)},\"csrf\":${RailsJson.generate(session["_csrf_token"])}}"
        session["return_to_after_authenticating"] = "/rooms/1"
        c.json(200, body)
    }

    private val whoami = Action { c -> c.json(200, "{\"token\":${RailsJson.generate(c.cookies.signed("session_token"))}}") }

    private val app: Handler = run {
        val kit = Kit(KitConfig(), secrets, TestClock.frozenAt(now), log = {})
        Adapter.app(kit, Handler { request ->
            when (request.path) {
                "/session" -> Adapter.dispatch(kit, request, createSession)
                "/whoami" -> Adapter.dispatch(kit, request, whoami)
                else -> Adapter.notFound(kit, request)
            }
        })
    }

    private fun postSession(cookie: String, site: String, token: String?, origin: String?): Response {
        val headers = Headers().add("host", "localhost:3000").add("cookie", cookie).add("sec-fetch-site", site)
        origin?.let { headers.add("origin", it) }
        val body = if (token == null) "" else {
            headers.add("content-type", "application/x-www-form-urlencoded")
            "authenticity_token=${CookieJar.escape(token)}"
        }
        return app.handle(Request("POST", "/session", headers, body.toByteArray()))
    }

    @Test
    fun railsSessionsCarryOver() {
        val cookie = "_campfire_session=${CookieJar.escape(session["session_cookie_raw"] as String)}"
        val formToken = session["session_form_token"] as String

        val fromOldTab = postSession(cookie, "same-origin", formToken, null)
        assertEquals(200, fromOldTab.status)
        val setCookie = fromOldTab.headers["set-cookie"]!!
        val body = RailsJson.parse(Responses.joined(fromOldTab.body.chunks())) as Map<*, *>
        val stored = session["session"] as Map<*, *>
        assertEquals(stored["session_id"], body["id"])
        assertEquals(stored["_csrf_token"], body["csrf"], "Rails' token stays in the session, unused")

        val raw = Cookies.unescape(setCookie.removePrefix("_campfire_session=").substringBefore(';'))
        val decoded = Cookies.decrypt(secrets, "_campfire_session", raw, now)
        val expected = LinkedHashMap<Any?, Any?>(stored)
        expected["return_to_after_authenticating"] = "/rooms/1"
        assertEquals(expected, decoded)
        assertTrue(setCookie.endsWith("; path=/; expires=Mon, 01 Jan 2046 12:00:00 GMT; httponly; samesite=lax"), setCookie)

        assertEquals(200, postSession(cookie, "same-origin", null, null).status)
        assertEquals(422, postSession(cookie, "cross-site", formToken, null).status, "a valid Rails token doesn't make a cross-site request acceptable")
        val crossOrigin = postSession(cookie, "same-origin", null, "https://evil.example")
        assertEquals((session["post_with_cross_origin_status"] as Long).toInt(), crossOrigin.status)
    }

    @Test
    fun railsSignedSessionTokenCookieIsRead() {
        val cookie = "session_token=${CookieJar.escape(session["session_token_raw"] as String)}"
        val response = app.handle(Request("GET", "/whoami", Headers().add("cookie", cookie)))
        val body = RailsJson.parse(Responses.joined(response.body.chunks())) as Map<*, *>
        assertEquals(session["session_token_value"], body["token"])
    }
}
