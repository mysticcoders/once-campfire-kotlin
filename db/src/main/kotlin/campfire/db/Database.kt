package campfire.db

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

/**
 * The text Active Record writes for a `datetime(6)` (`crates/db/src/time.rs` `to_db`):
 * `YYYY-MM-DD HH:MM:SS`, with `.ffffff` only when there are microseconds.
 */
fun dbTime(instant: java.time.Instant): String {
    val t = java.time.LocalDateTime.ofInstant(instant, java.time.ZoneOffset.UTC)
    val base = "%04d-%02d-%02d %02d:%02d:%02d".format(t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute, t.second)
    val micros = instant.nano / 1000
    return if (micros == 0) base else base + "." + micros.toString().padStart(6, '0')
}

/** `timeout: 5000` in `reference/config/database.yml` (`crates/db/src/schema.rs` `BUSY_TIMEOUT_MS`). */
const val BUSY_TIMEOUT_MS = 5000

/**
 * The per-connection settings Rails applies (`SQLite3Adapter#configure_connection`), as
 * `crates/db/src/schema.rs` `configure_connection` does, plus `query_only` for readers.
 */
fun configureConnection(conn: SqlConnection, reader: Boolean) {
    conn.execute("PRAGMA busy_timeout = $BUSY_TIMEOUT_MS")
    conn.execute("PRAGMA foreign_keys = ON")
    conn.execute("PRAGMA journal_mode = wal")
    conn.execute("PRAGMA synchronous = normal")
    conn.execute("PRAGMA journal_size_limit = 67108864")
    conn.execute("PRAGMA cache_size = 2000")
    if (reader) conn.execute("PRAGMA query_only = ON")
}

/**
 * A write transaction on the writer connection: statements prepared once per SQL text and kept,
 * and work queued to run after the commit, as Active Record runs `after_commit` callbacks
 * (`crates/db/src/database.rs` `Tx`).
 */
class Tx internal constructor(private val conn: SqlConnection, private val statements: HashMap<String, Stmt>) {
    internal val afterCommit = ArrayList<() -> Unit>()

    /** The prepared statement for [sql], reset and ready to bind. */
    fun prepare(sql: String): Stmt = statements.getOrPut(sql) { conn.prepare(sql) }

    /** Runs [sql] with [binds] to completion (an UPDATE, INSERT or DELETE). */
    fun execute(sql: String, vararg binds: Any?) {
        val stmt = prepare(sql)
        try {
            binds.forEachIndexed { i, value ->
                when (value) {
                    null -> stmt.bindNull(i + 1)
                    is Long -> stmt.bindLong(i + 1, value)
                    is Int -> stmt.bindLong(i + 1, value.toLong())
                    is String -> stmt.bindText(i + 1, value)
                    else -> error("unsupported bind ${value::class}")
                }
            }
            while (stmt.step()) Unit
        } finally {
            stmt.reset()
        }
    }

    /** Queues [hook] to run once this transaction has committed. */
    fun afterCommit(hook: () -> Unit) {
        afterCommit.add(hook)
    }
}

/**
 * The writer thread: it owns the write connection and takes a bounded queue of writes, each run
 * in `BEGIN IMMEDIATE` then committed, with its after-commit work run in order outside the
 * transaction (`crates/db/src/database.rs` `run_write`). Callers block until their write is done.
 *
 * Unlike the Rust port, checkpoints are SQLite's own auto-checkpoint (every 1,000 WAL pages, on
 * the committing write, as in Rails) rather than a checkpointer thread fed by a WAL hook; the
 * room page never writes, and the message write path will need the Rust arrangement.
 */
class Writer(path: String, queueSize: Int = 256) {
    private class Task<T>(val work: (Tx) -> T, val result: CompletableFuture<T>)

    private val queue = ArrayBlockingQueue<Task<*>>(queueSize)
    private val conn = SqliteBackend.open(path).also { configureConnection(it, reader = false) }
    private val statements = HashMap<String, Stmt>()
    private val begin = conn.prepare("BEGIN IMMEDIATE")
    private val commit = conn.prepare("COMMIT")
    private val rollback = conn.prepare("ROLLBACK")

    init {
        Thread.ofPlatform().name("campfire-db-writer").daemon(true).start { loop() }
    }

    /** Runs [work] in a write transaction on the writer thread and returns its result. */
    fun <T> write(work: (Tx) -> T): T {
        val task = Task(work, CompletableFuture())
        queue.put(task)
        try {
            return task.result.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    private fun loop() {
        while (true) run(queue.take())
    }

    private fun <T> run(task: Task<T>) {
        val tx = Tx(conn, statements)
        val value = try {
            once(begin)
            val value = task.work(tx)
            once(commit)
            value
        } catch (e: Throwable) {
            runCatching { once(rollback) }
            task.result.completeExceptionally(e)
            return
        }
        var firstError: Throwable? = null
        for (hook in tx.afterCommit) {
            try {
                hook()
            } catch (e: Throwable) {
                System.err.println("after_commit hook failed: $e")
                if (firstError == null) firstError = e
            }
        }
        if (firstError != null) task.result.completeExceptionally(firstError) else task.result.complete(value)
    }

    private fun once(stmt: Stmt) {
        try {
            while (stmt.step()) Unit
        } finally {
            stmt.reset()
        }
    }
}

/**
 * `PRAGMA data_version` on a read-only connection of its own, as the response cache's `Observer`
 * in `crates/campfire/src/response_cache.rs` reads it: SQLite changes the value after every commit
 * by another connection (the app's writer, its readers never write, or another process) and never
 * for reads, so it tells whether anything committed since it was last read. The connection waits
 * at most 50 ms for a lock, as Rust's does. Not thread-safe: the caller serializes [read].
 */
class DataVersion(path: String) {
    private val conn = SqliteBackend.open(path, readOnly = true).also { it.execute("PRAGMA busy_timeout = 50") }
    private val pragma = conn.prepare("PRAGMA data_version")

    /** The current value; throws when SQLite can't answer (a lock held past the timeout, say). */
    fun read(): Long {
        try {
            check(pragma.step()) { "PRAGMA data_version returned no row" }
            return pragma.integer(1)
        } finally {
            pragma.reset()
        }
    }
}
