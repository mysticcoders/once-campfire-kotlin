package campfire.kit

import campfire.rails.RailsJson
import campfire.rails.Secrets
import campfire.rails.TestClock
import campfire.routes.RouteTable
import java.time.Instant
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [Router] over Campfire's route table (`dispatch` and the framework rows in
 * `once-campfire-rust/crates/campfire/src/controllers.rs`), and a page with cached fragments
 * through the router, [RailsMiddleware] and [RackDeflater].
 */
class RouterTest {
    private val kit = Kit(
        KitConfig(errorPages = ErrorPages.PUBLIC),
        Secrets("test-secret"),
        TestClock.frozenAt(Instant.parse("2024-06-01T12:00:00Z")),
        log = {},
    )

    private val fragment = Fragment(("<div class=\"message\">" + "x".repeat(2000) + "</div>").toByteArray())

    private fun page(): PageParts {
        val p = PageParts.Builder()
        p.append("<html><body>")
        p.fragment(fragment)
        p.append("</body></html>")
        return p.build()
    }

    private val actions = mapOf(
        "rooms#show" to Action { c ->
            val route = c.current<MatchedRoute>()!!
            c.json(200, "{\"params\":${c.params.toJson()},\"endpoint\":${RailsJson.generate(route.endpoint)}}")
        },
        "messages#index" to Action { c ->
            c.setHeader("x-version", "42")
            c.renderPage(200, Mime.HTML, page())
        },
    )

    private val app: Handler = Adapter.app(kit, Router(actions, RouteTable.campfire).handler(kit)).layered(RackDeflater())

    private fun send(method: String, target: String, vararg headers: Pair<String, String>): Response {
        val h = Headers().add("host", "chat.example.com")
        for ((k, v) in headers) h.add(k, v)
        return app.handle(Request(method, target, h))
    }

    private fun text(response: Response) = String(Responses.joined(response.body.chunks()), Charsets.UTF_8)

    @Test
    fun recognizedRoutesGetTheirPathParams() {
        val response = send("GET", "/rooms/7?x=1")
        assertEquals(200, response.status)
        val json = RailsJson.parse(text(response).toByteArray()) as Map<*, *>
        assertEquals(RailsJson.parse("""{"x":"1","id":"7","controller":"rooms","action":"show"}""".toByteArray()), json["params"])
        assertEquals("rooms#show", json["endpoint"])
    }

    @Test
    fun unknownRoutesAreThePublic404() {
        val response = send("GET", "/nope")
        assertEquals(404, response.status)
        assertTrue(text(response).contains("<html"))
        assertEquals("text/html; charset=UTF-8", response.headers["content-type"])
        assertEquals(404, send("PUT", "/up").status)
        assertEquals("""{"status":404,"error":"Not Found"}""", text(send("GET", "/nope?format=json")))
    }

    @Test
    fun declaredRoutesWithoutAnActionAre404sAndAMissingControllerIs500() {
        assertEquals(404, send("GET", "/first_run/edit").status)
        assertEquals(404, send("GET", "/rooms/1/edit").status)
        assertEquals(500, send("GET", "/rooms/1/settings").status)
    }

    @Test
    fun malformedPathsAndQueriesAre400sBeforeRouting() {
        assertEquals(400, send("GET", "/rooms/%FF").status)
        assertEquals(400, send("GET", "/nope?a=%").status)
    }

    @Test
    fun theFrameworkRoutes() {
        val up = send("GET", "/up")
        assertEquals(200, up.status)
        assertEquals("<!DOCTYPE html><html><body style=\"background-color: green\"></body></html>", text(up))
        assertEquals("""{"status":"up","timestamp":"2024-06-01T12:00:00Z"}""", text(send("GET", "/up.json")))
        val head = send("HEAD", "/up")
        assertEquals(200, head.status)
        assertEquals(Body.Empty, head.body)
        assertEquals("Going back…", text(send("GET", "/recede_historical_location")))
        assertEquals(404, send("POST", "/rails/action_mailbox/relay/inbound_emails").status)
        assertEquals(403, send("POST", "/rails/conductor/action_mailbox/inbound_emails").status)
    }

    @Test
    fun pagesAreEtaggedByTheirPartsAndGzippedFromThem() {
        val plain = send("GET", "/rooms/1/messages")
        assertEquals(200, plain.status)
        assertEquals(page().etag(), plain.headers["etag"])
        assertEquals("42", plain.headers["x-version"])
        assertEquals("Accept-Encoding", plain.headers["vary"])
        val body = text(plain)
        assertTrue(body.startsWith("<html><body><div class=\"message\">") && body.endsWith("</div></body></html>"))
        assertEquals(body.toByteArray().size.toString(), plain.headers["content-length"])

        val gzipped = send("GET", "/rooms/1/messages", "accept-encoding" to "gzip")
        assertEquals("gzip", gzipped.headers["content-encoding"])
        assertNull(gzipped.headers["content-length"])
        assertEquals(body, String(GZIPInputStream(Responses.joined(gzipped.body.chunks()).inputStream()).readAllBytes()))

        val notModified = send("GET", "/rooms/1/messages", "if-none-match" to page().etag())
        assertEquals(304, notModified.status)
        assertEquals(Body.Empty, notModified.body)
    }

    @Test
    fun aPageWithoutFragmentsIsEtaggedByItsBody() {
        val p = PageParts.Builder()
        p.append("<p>small</p>")
        val response = Ctx.pageBody(p.build())
        assertTrue(response is Body.Bytes)
    }
}
