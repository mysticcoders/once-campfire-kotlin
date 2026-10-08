package campfire.db

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The writer thread's transactions, as `crates/db/src/database.rs`'s write tests check them. */
class WriterTest {
    private fun database(): Pair<Database, String> {
        val path = Files.createTempDirectory("campfire-db").resolve("test.sqlite3").toString()
        val setup = SqliteBackend.open(path)
        setup.execute("CREATE TABLE items (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
        return Database(path, 2) to path
    }

    private fun count(db: Database): Long = db.read { r -> r.countForTest() }

    @Test
    fun `a write commits and its after-commit hooks run in order after it`() {
        val (db, _) = database()
        val seen = ArrayList<String>()
        val value = db.write { tx ->
            tx.execute("INSERT INTO items (name) VALUES (?)", "one")
            tx.afterCommit { seen.add("first") }
            tx.afterCommit { seen.add("second") }
            42
        }
        assertEquals(42, value)
        assertEquals(listOf("first", "second"), seen)
        assertEquals(1, count(db))
    }

    @Test
    fun `a failing write rolls back and skips its hooks`() {
        val (db, _) = database()
        val seen = ArrayList<String>()
        assertFailsWith<IllegalStateException> {
            db.write { tx ->
                tx.execute("INSERT INTO items (name) VALUES (?)", "one")
                tx.afterCommit { seen.add("hook") }
                error("boom")
            }
        }
        assertEquals(emptyList(), seen)
        assertEquals(0, count(db))
        db.write { tx -> tx.execute("INSERT INTO items (name) VALUES (?)", "two") }
        assertEquals(1, count(db))
    }

    @Test
    fun `an after-commit error is returned after the rest of the queue has run`() {
        val (db, _) = database()
        val seen = ArrayList<String>()
        assertFailsWith<IllegalArgumentException> {
            db.write { tx ->
                tx.execute("INSERT INTO items (name) VALUES (?)", "one")
                tx.afterCommit { throw IllegalArgumentException("hook") }
                tx.afterCommit { seen.add("later") }
            }
        }
        assertEquals(listOf("later"), seen)
        assertEquals(1, count(db))
    }
}
