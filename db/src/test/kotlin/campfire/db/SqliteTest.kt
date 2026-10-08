package campfire.db

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Both backends run statements the writer prepares (transaction control, writes) as well as queries. */
class SqliteTest {
    private fun roundTrip(backend: String) {
        val path = Files.createTempDirectory("campfire-sqlite").resolve("test.sqlite3").toString()
        val conn = SqliteBackend.open(path, backend)
        conn.execute("CREATE TABLE items (id INTEGER PRIMARY KEY, name TEXT)")
        val begin = conn.prepare("BEGIN IMMEDIATE")
        val insert = conn.prepare("INSERT INTO items (name) VALUES (?)")
        val commit = conn.prepare("COMMIT")
        val select = conn.prepare("SELECT id, name FROM items ORDER BY id")
        for (name in listOf("one", "two")) {
            assertFalse(begin.step())
            begin.reset()
            insert.bindText(1, name)
            assertFalse(insert.step())
            insert.reset()
            assertFalse(commit.step())
            commit.reset()
        }
        val rows = ArrayList<Pair<Long, String?>>()
        while (select.step()) rows.add(select.integer(1) to select.text(2))
        select.reset()
        assertEquals<List<Pair<Long, String?>>>(listOf(1L to "one", 2L to "two"), rows)
        assertTrue(select.step())
        select.reset()
    }

    @Test
    fun `the native backend runs writes and transactions`() = roundTrip("native")

    @Test
    fun `the jdbc backend runs writes and transactions`() = roundTrip("jdbc")
}
