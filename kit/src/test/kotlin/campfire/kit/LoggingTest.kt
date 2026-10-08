package campfire.kit

import campfire.rails.Secrets
import campfire.rails.TestClock
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the request lifecycle logs (`once-campfire-rust/crates/kit/tests/logging.rs`). */
class LoggingTest {
    @Test
    fun errorsAreLoggedAsTheyWereRaised() {
        val lines = ArrayList<String>()
        val kit = Kit(KitConfig(), Secrets("test-secret"), TestClock.frozenAt(Instant.parse("2024-06-01T12:00:00Z")), log = { lines.add(it) })
        val echo = Action { c -> c.head(200) }
        val fail = Action { throw RuntimeException("saving the upload", java.io.IOException("disk full")) }
        val app = Adapter.app(kit, Handler { request -> Adapter.dispatch(kit, request, if (request.path == "/fail") fail else echo) })

        val query = Request("GET", "/echo/1?a=%", Headers())
        val form = Request("POST", "/echo/1", Headers().add("content-type", "application/x-www-form-urlencoded"), "b=%".toByteArray())
        for (request in listOf(query, form)) assertEquals(400, app.handle(request).status)
        assertEquals(500, app.handle(Request("GET", "/fail", Headers())).status)

        val text = lines.joinToString("\n")
        assertEquals(2, lines.count { "INFO campfire_kit::ctx: request rejected error=bad request: invalid %-encoding (%)" in it }, text)
        assertTrue("bad request: bad request" !in text, text)
        assertTrue(lines.any { "ERROR campfire_kit::ctx: request failed error=saving the upload: disk full path=\"/fail\"" in it }, text)
    }
}
