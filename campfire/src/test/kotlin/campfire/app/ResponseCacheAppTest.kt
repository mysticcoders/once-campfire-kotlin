package campfire.app

import campfire.db.SqlConnection
import campfire.db.SqliteBackend
import campfire.kit.Handler
import campfire.kit.Headers
import campfire.kit.Request
import campfire.kit.Response
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The room page through the whole app with the response cache, as
 * `crates/campfire/src/app/tests/response_cache.rs` checks it, on a copy of the parity seed
 * (`tmp/seed.sqlite3`, or `CAMPFIRE_SEED`) with the session in `vectors/campfire_sessions.json`.
 * Without the seed these pass without running, with a note on stderr; `CAMPFIRE_REQUIRE_SEED=1`
 * makes a missing seed fail them instead.
 *
 * The Rust tests of `messages#index`, `users/sidebars#show`, `searches#index`, the page around a
 * message and detached (broadcast) renders have no counterpart: this app doesn't serve them.
 */
class ResponseCacheAppTest {
    private class Reply(val status: Int, val headers: Headers, val body: ByteArray) {
        fun header(name: String): String? = headers[name]

        fun text(): String = String(body, Charsets.UTF_8)

        fun setCookies(prefix: String): List<String> = headers.all("set-cookie").filter { it.startsWith(prefix) }
    }

    private class Fixture(val app: App, val handler: Handler, val cookie: String, val user: Long, val room: Long, val path: String) {
        val foreign: SqlConnection = SqliteBackend.open(app.db.path)

        fun hits(): Long = app.responseCache.hits.get()

        fun send(request: Request): Reply {
            val response: Response = handler.handle(request)
            return Reply(response.status, response.headers, response.body.chunks().fold(ByteArray(0)) { a, b -> a + b })
        }

        fun get(path: String, vararg headers: Pair<String, String>, cookie: String? = this.cookie, method: String = "GET"): Reply {
            val h = Headers().add("host", "campfire.test")
            if (cookie != null) h.add("cookie", cookie)
            for ((name, value) in headers) h[name] = value
            return send(Request(method, path, h))
        }

        /**
         * The first seeded session authentication writes its hourly activity refresh and must not
         * admit that pre-authentication generation; later requests populate, then hit.
         */
        fun warm(path: String = this.path): Reply {
            var reply = get(path)
            repeat(3) {
                assertEquals(200, reply.status, reply.text())
                reply = get(path)
            }
            return reply
        }

        /** Runs [sql] with [binds] on the foreign connection, outside the app's writer. */
        fun execute(sql: String, vararg binds: Any) {
            val stmt = foreign.prepare(sql)
            try {
                binds.forEachIndexed { i, value -> if (value is Long) stmt.bindLong(i + 1, value) else stmt.bindText(i + 1, value.toString()) }
                while (stmt.step()) {
                    continue
                }
            } finally {
                stmt.reset()
            }
        }

        /** The first row of [sql]'s integer columns. */
        fun longs(sql: String, vararg binds: Long): List<Long> {
            val stmt = foreign.prepare(sql)
            try {
                binds.forEachIndexed { i, value -> stmt.bindLong(i + 1, value) }
                check(stmt.step()) { "no row for $sql" }
                return listOf(stmt.integer(1), stmt.integer(2))
            } finally {
                stmt.reset()
            }
        }
    }

    private val mapper = ObjectMapper()

    private fun vectors(name: String) = mapper.readTree(File(System.getProperty("campfire.vectors"), name))

    private val secret: String by lazy { vectors("rails_compat.json")["secret_key_base"].asText() }

    private fun fixture(): Fixture? {
        val seed = File(System.getenv("CAMPFIRE_SEED") ?: "tmp/seed.sqlite3")
        if (!seed.isFile) {
            check(System.getenv("CAMPFIRE_REQUIRE_SEED") != "1") { "no seed at $seed" }
            System.err.println("ResponseCacheAppTest: no seed at $seed; skipped")
            return null
        }
        val dir = Files.createTempDirectory("campfire-app")
        val db = dir.resolve("production.sqlite3")
        Files.copy(seed.toPath(), db)
        val env = mapOf(
            "SECRET_KEY_BASE" to secret,
            "DISABLE_SSL" to "true",
            "APP_VERSION" to "parity",
            "GIT_REVISION" to "parity",
            "CAMPFIRE_DATABASE_PATH" to db.toString(),
        )
        val app = App(Config { env[it] })
        val session = vectors("campfire_sessions.json")["sessions"][0]
        val probe = SqliteBackend.open(db.toString())
        val stmt = probe.prepare(
            "SELECT users.id, rooms.id FROM users JOIN memberships ON memberships.user_id = users.id JOIN rooms ON rooms.id = memberships.room_id WHERE users.name = ? AND rooms.type = 'Rooms::Closed' AND EXISTS (SELECT 1 FROM messages WHERE messages.room_id = rooms.id) ORDER BY rooms.id LIMIT 1",
        )
        stmt.bindText(1, session["user_name"].asText())
        check(stmt.step())
        val user = stmt.integer(1)
        val room = stmt.integer(2)
        stmt.reset()
        return Fixture(app, handler(kit(app)), session["cookie_header"].asText(), user, room, "/rooms/$room")
    }

    @Test
    fun `room cache keeps complete responses`() {
        val test = fixture() ?: return
        val first = test.warm()
        val before = test.hits()
        val hit = test.get(test.path)
        assertEquals(before + 1, test.hits())
        assertEquals(first.status, hit.status)
        assertTrue(first.body.contentEquals(hit.body))
        assertEquals(first.header("etag"), hit.header("etag"))
        assertNotEquals(first.header("x-request-id"), hit.header("x-request-id"))
        assertEquals(hit.body.size.toString(), hit.header("content-length"))
    }

    @Test
    fun `gzip identity head and conditional requests keep their contracts`() {
        val test = fixture() ?: return
        val plain = test.warm()
        val first = test.get(test.path, "accept-encoding" to "gzip")
        val before = test.hits()
        val hit = test.get(test.path, "accept-encoding" to "gzip")
        assertEquals(before + 1, test.hits())
        assertTrue(first.body.contentEquals(hit.body))
        assertEquals("gzip", hit.header("content-encoding"))
        assertEquals(plain.text(), String(GZIPInputStream(hit.body.inputStream()).readAllBytes(), Charsets.UTF_8))
        val head = test.get(test.path, "accept-encoding" to "gzip", method = "HEAD")
        assertEquals(200, head.status)
        assertTrue(head.body.isEmpty())
        assertEquals(hit.header("content-length"), head.header("content-length"))
        val conditional = test.get(test.path, "accept-encoding" to "gzip", "if-none-match" to hit.header("etag")!!)
        assertEquals(304, conditional.status)
        assertTrue(conditional.body.isEmpty())
        val forbidden = test.get(test.path, "accept-encoding" to "gzip;q=0,identity;q=0")
        assertEquals(406, forbidden.status)
        assertTrue(plain.body.contentEquals(test.get(test.path).body))
    }

    @Test
    fun `foreign and local writers change cached room content`() {
        val test = fixture() ?: return
        val initial = test.warm()
        test.execute("UPDATE rooms SET name = 'Changed externally without a timestamp' WHERE id = ?", test.room)
        val after = test.get(test.path)
        assertTrue("Changed externally without a timestamp" in after.text())
        assertNotEquals(initial.header("etag"), after.header("etag"))
        test.app.db.write { tx -> tx.execute("UPDATE rooms SET name = 'Changed through the app writer' WHERE id = ?", test.room) }
        assertTrue("Changed through the app writer" in test.get(test.path).text())
    }

    @Test
    fun `revoked sessions and changed tokens cannot reuse private responses`() {
        for (mutation in listOf("DELETE FROM sessions WHERE user_id = ?", "UPDATE sessions SET token = 'rotated-token-' || id WHERE user_id = ?")) {
            val test = fixture() ?: return
            test.warm()
            val before = test.hits()
            test.execute(mutation, test.user)
            val revoked = test.get(test.path)
            assertEquals(302, revoked.status)
            assertEquals("http://campfire.test/session/new", revoked.header("location"))
            assertEquals(before, test.hits())
            assertTrue(revoked.body.isEmpty())
        }
    }

    @Test
    fun `changed users and room permissions are checked before cache hits`() {
        val test = fixture() ?: return
        test.warm()
        test.execute("UPDATE users SET name = 'Renamed current user' WHERE id = ?", test.user)
        assertTrue("Renamed current user" in test.get(test.path).text())
        test.warm()
        val before = test.hits()
        test.execute("DELETE FROM memberships WHERE user_id = ? AND room_id = ?", test.user, test.room)
        val denied = test.get(test.path)
        assertEquals(302, denied.status)
        assertEquals(before, test.hits())
        assertFalse("Renamed current user" in denied.text())
    }

    @Test
    fun `request origin frame user agent target cookies and bypass are isolated`() {
        val test = fixture() ?: return
        val full = test.warm()
        for ((name, value) in listOf(
            "host" to "other.example",
            "turbo-frame" to "room",
            "user-agent" to "Campfire variant",
            "cookie" to "${test.cookie}; unrelated=variant",
            "cache-control" to "no-cache",
        )) {
            val before = test.hits()
            val reply = test.get(test.path, name to value)
            assertEquals(200, reply.status, "$name: ${reply.text()}")
            assertEquals(before, test.hits(), name)
            if (name == "turbo-frame") assertFalse("<!DOCTYPE html>" in reply.text())
        }
        val before = test.hits()
        assertEquals(200, test.get("${test.path}?variant=1").status)
        assertEquals(before, test.hits())
        assertEquals(302, test.get(test.path, cookie = null).status)
        assertEquals(302, test.get(test.path, cookie = vectors("campfire_sessions.json")["forged"]["cookie_header"].asText()).status)
        assertTrue(full.body.contentEquals(test.get(test.path).body))
    }

    @Test
    fun `fresh trace headers do not disable hits or replay old request ids`() {
        val test = fixture() ?: return
        test.warm()
        val before = test.hits()
        for (index in 1..3) {
            val reply = test.get(test.path, "x-request-start" to "t=$index", "x-request-id" to "trace-$index")
            assertEquals("trace-$index", reply.header("x-request-id"))
            assertEquals(before + index, test.hits())
        }
    }

    /** `timestamp_free_foreign_edits_invalidate_nested_fragments_and_validators`, on the room's last page. */
    @Test
    fun `timestamp free foreign edits invalidate nested fragments and validators`() {
        val test = fixture() ?: return
        val (message, creator) = test.longs(
            "SELECT messages.id, messages.creator_id FROM messages JOIN boosts ON boosts.message_id = messages.id WHERE messages.id IN (SELECT id FROM messages WHERE room_id = ? ORDER BY created_at DESC LIMIT 40) ORDER BY messages.id LIMIT 1",
            test.room,
        )
        val before = test.warm()
        test.execute("UPDATE users SET name = 'Foreign renamed creator' WHERE id = ?", creator)
        test.execute("UPDATE action_text_rich_texts SET body = '<p>Foreign replacement body</p>' WHERE record_type = 'Message' AND record_id = ?", message)
        test.execute("UPDATE boosts SET content = 'Foreign replacement boost' WHERE message_id = ?", message)
        val after = test.get(test.path)
        for (changed in listOf("Foreign renamed creator", "Foreign replacement body", "Foreign replacement boost")) {
            assertTrue(changed in after.text(), "missing $changed")
        }
        assertNotEquals(before.header("etag"), after.header("etag"))
    }

    @Test
    fun `flash is rendered and consumed fresh instead of reusing or populating a response`() {
        val test = fixture() ?: return
        test.warm()
        val denied = test.get("/rooms/0")
        assertEquals(302, denied.status)
        val flashCookie = denied.setCookies("_campfire_session=").first().substringBefore(';')
        val health = test.get("/up", cookie = "${test.cookie}; $flashCookie")
        assertEquals(200, health.status)
        assertTrue(health.setCookies("_campfire_session=").isEmpty(), "an unrelated health response must not consume pending flash")
        val before = test.hits()
        val flashed = test.get(test.path, cookie = "${test.cookie}; $flashCookie")
        assertEquals(200, flashed.status)
        assertTrue("Room not found or inaccessible" in flashed.text())
        assertEquals(before, test.hits())
        assertTrue(flashed.setCookies("_campfire_session=").isNotEmpty(), "flash consumption updates its cookie")
        val clean = test.get(test.path)
        assertEquals(before + 1, test.hits())
        assertFalse("Room not found or inaccessible" in clean.text())
        assertTrue(clean.setCookies("last_room=").isNotEmpty(), "remember_last_room still runs on a cache hit")
        assertTrue(clean.setCookies("session_token=").isEmpty(), "an old session refresh cookie is not replayed")
    }

    @Test
    fun `cached opengraph fragments preserve actual origin filtering`() {
        val test = fixture() ?: return
        val message = test.longs("SELECT id, id FROM messages WHERE room_id = ? ORDER BY created_at DESC LIMIT 1", test.room)[0]
        val embed = """<action-text-attachment content-type="application/vnd.actiontext.opengraph-embed" url="https://one.example/embed.png" href="https://one.example/host-sensitive" filename="Origin-sensitive embed"></action-text-attachment>"""
        test.execute("UPDATE action_text_rich_texts SET body = ? WHERE record_type = 'Message' AND record_id = ?", embed, message)
        repeat(4) { assertEquals(200, test.get(test.path, "host" to "one.example").status) }
        val sameOrigin = test.get(test.path, "host" to "one.example")
        assertFalse("https://one.example/host-sensitive" in sameOrigin.text())
        val elsewhere = test.get(test.path, "host" to "two.example")
        assertTrue("https://one.example/host-sensitive" in elsewhere.text(), "the second origin must render its own nested fragment")
        val before = test.hits()
        assertTrue(elsewhere.body.contentEquals(test.get(test.path, "host" to "two.example").body))
        assertEquals(before + 1, test.hits())
        assertTrue(sameOrigin.body.contentEquals(test.get(test.path, "host" to "one.example").body))
    }

    @Test
    fun `a disabled cache never hits`() {
        val test = fixture() ?: return
        val env = mapOf("SECRET_KEY_BASE" to secret, "DISABLE_SSL" to "true", "CAMPFIRE_DATABASE_PATH" to test.app.db.path, "CAMPFIRE_RESPONSE_CACHE_MB" to "0")
        val app = App(Config { env[it] })
        val disabled = Fixture(app, handler(kit(app)), test.cookie, test.user, test.room, test.path)
        repeat(5) { assertEquals(200, disabled.get(test.path).status) }
        assertEquals(0, disabled.hits())
    }
}
