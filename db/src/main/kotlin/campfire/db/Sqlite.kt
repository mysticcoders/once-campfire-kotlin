package campfire.db

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.Properties

/** A prepared statement as the queries use it: bind, step through rows, read columns, reset. */
interface Stmt {
    fun bindLong(index: Int, value: Long)

    fun bindText(index: Int, value: String)

    fun bindNull(index: Int)

    /** Advances to the next row (running the statement first, if it hasn't run since a reset). */
    fun step(): Boolean

    fun integer(column: Int): Long

    /** The column's text, or null for SQL NULL. Columns are 1-based, as in JDBC. */
    fun text(column: Int): String?

    fun reset()
}

/** A reader connection that prepares statements. */
interface SqlConnection {
    fun prepare(sql: String): Stmt

    fun execute(sql: String)
}

/**
 * Opens reader connections with the backend `KOTLIN_SQLITE` names: `native` (default; FFM on a JVM,
 * the C interface in a native image) or `jdbc`. A [readOnly] connection is opened with
 * `SQLITE_OPEN_READONLY` instead of read-write and create.
 */
object SqliteBackend {
    val name: String = System.getenv("KOTLIN_SQLITE") ?: "native"

    fun open(path: String, backend: String = name, readOnly: Boolean = false): SqlConnection = when (backend) {
        "jdbc" -> JdbcConnection(path, readOnly)
        "native" -> if (System.getProperty("org.graalvm.nativeimage.imagecode") == "runtime") campfire.db.graal.ImageSqlite(path, readOnly) else NativeConnection(path, readOnly)
        else -> error("KOTLIN_SQLITE must be native or jdbc, not $backend")
    }
}

/** sqlite-jdbc behind [Stmt]: the spike's first backend. */
private class JdbcConnection(path: String, readOnly: Boolean) : SqlConnection {
    private val conn = DriverManager.getConnection("jdbc:sqlite:$path", Properties().apply { setProperty("open_mode", if (readOnly) "1" else "6") })

    override fun prepare(sql: String): Stmt = JdbcStmt(conn.prepareStatement(sql))

    override fun execute(sql: String) {
        conn.createStatement().use { it.execute(sql) }
    }

    /** A statement that has run since its last reset holds its rows in [rs], or has none ([done]). */
    private class JdbcStmt(private val ps: PreparedStatement) : Stmt {
        private var rs: ResultSet? = null
        private var done = false

        override fun bindLong(index: Int, value: Long) = ps.setLong(index, value)

        override fun bindText(index: Int, value: String) = ps.setString(index, value)

        override fun bindNull(index: Int) = ps.setNull(index, java.sql.Types.NULL)

        override fun step(): Boolean {
            if (done) return false
            val rows = rs ?: if (ps.execute()) ps.resultSet.also { rs = it } else null
            if (rows == null) done = true
            return rows?.next() ?: false
        }

        override fun integer(column: Int): Long = rs!!.getLong(column)

        override fun text(column: Int): String? = rs!!.getString(column)

        override fun reset() {
            rs?.close()
            rs = null
            done = false
        }
    }
}

/**
 * SQLite's C API through the Java FFM API, against the same amalgamation and flags as the Rust
 * build (`native/build.sh`). Binds and column reads are `critical` downcalls, which skip the
 * thread-state transition and may read Java arrays in place; they only copy memory. Steps and
 * resets are ordinary downcalls: they can do I/O, wait out the busy timeout or run a long query,
 * and a critical call must finish quickly in every case. Statements are prepared with
 * `SQLITE_PREPARE_PERSISTENT` and live as long as their connection.
 */
private class NativeConnection(path: String, readOnly: Boolean) : SqlConnection {
    private val db: MemorySegment

    init {
        Arena.ofConfined().use { arena ->
            val out = arena.allocate(ADDRESS)
            val rc = Sqlite.open.invokeExact(arena.allocateFrom(path), out, (if (readOnly) OPEN_READONLY else OPEN_READWRITE or OPEN_CREATE) or OPEN_NOMUTEX or OPEN_URI, MemorySegment.NULL) as Int
            db = out.get(ADDRESS, 0)
            check(rc == OK) { "sqlite3_open_v2($path): ${errmsg()}" }
        }
    }

    override fun prepare(sql: String): Stmt = Arena.ofConfined().use { arena ->
        val out = arena.allocate(ADDRESS)
        val bytes = arena.allocateFrom(sql)
        val rc = Sqlite.prepare.invokeExact(db, bytes, -1, PREPARE_PERSISTENT, out, MemorySegment.NULL) as Int
        check(rc == OK) { "sqlite3_prepare_v3: ${errmsg()}: $sql" }
        NativeStmt(out.get(ADDRESS, 0))
    }

    override fun execute(sql: String) {
        Arena.ofConfined().use { arena ->
            val rc = Sqlite.exec.invokeExact(db, arena.allocateFrom(sql), MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL) as Int
            check(rc == OK) { "sqlite3_exec: ${errmsg()}: $sql" }
        }
    }

    private fun errmsg(): String = (Sqlite.errmsg.invokeExact(db) as MemorySegment).reinterpret(Long.MAX_VALUE).getString(0)

    private inner class NativeStmt(private val stmt: MemorySegment) : Stmt {
        private var scratch = ByteArray(256)

        override fun bindLong(index: Int, value: Long) {
            check(Sqlite.bindInt64.invokeExact(stmt, index, value) as Int == OK) { errmsg() }
        }

        override fun bindText(index: Int, value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            check(Sqlite.bindText.invokeExact(stmt, index, MemorySegment.ofArray(bytes), bytes.size, TRANSIENT) as Int == OK) { errmsg() }
        }

        override fun bindNull(index: Int) {
            check(Sqlite.bindNull.invokeExact(stmt, index) as Int == OK) { errmsg() }
        }

        override fun step(): Boolean = when (val rc = Sqlite.step.invokeExact(stmt) as Int) {
            ROW -> true
            DONE -> false
            else -> error("sqlite3_step: $rc ${errmsg()}")
        }

        override fun integer(column: Int): Long = Sqlite.columnInt64.invokeExact(stmt, column - 1) as Long

        override fun text(column: Int): String? {
            val address = Sqlite.columnText.invokeExact(stmt, column - 1) as Long
            if (address == 0L) return null
            val n = Sqlite.columnBytes.invokeExact(stmt, column - 1) as Int
            if (n > scratch.size) scratch = ByteArray(maxOf(n, scratch.size * 2))
            MemorySegment.copy(ALL_MEMORY, JAVA_BYTE, address, scratch, 0, n)
            return String(scratch, 0, n, Charsets.UTF_8)
        }

        override fun reset() {
            Sqlite.reset.invokeExact(stmt) as Int
            Sqlite.clearBindings.invokeExact(stmt) as Int
        }
    }

    companion object {
        const val OK = 0
        const val ROW = 100
        const val DONE = 101
        const val OPEN_READONLY = 0x1
        const val OPEN_READWRITE = 0x2
        const val OPEN_CREATE = 0x4
        const val OPEN_URI = 0x40
        const val OPEN_NOMUTEX = 0x8000
        const val PREPARE_PERSISTENT = 0x1
        val TRANSIENT: MemorySegment = MemorySegment.ofAddress(-1)

        /** All of memory as one segment, so text is copied from SQLite's pointer without a segment per read. */
        val ALL_MEMORY: MemorySegment = MemorySegment.NULL.reinterpret(Long.MAX_VALUE)
    }
}

/** The downcall handles, bound once against `libcampfire_sqlite3`. */
private object Sqlite {
    private val linker = Linker.nativeLinker()
    private val lookup = SymbolLookup.libraryLookup(libraryPath(), Arena.global())
    private val critical = Linker.Option.critical(true)

    val open = handle("sqlite3_open_v2", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS))
    val prepare = handle("sqlite3_prepare_v3", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS))
    val exec = handle("sqlite3_exec", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS))
    val errmsg = handle("sqlite3_errmsg", FunctionDescriptor.of(ADDRESS, ADDRESS))
    val bindInt64 = handle("sqlite3_bind_int64", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG), critical)
    val bindText = handle("sqlite3_bind_text", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS), critical)
    val bindNull = handle("sqlite3_bind_null", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT), critical)
    val step = handle("sqlite3_step", FunctionDescriptor.of(JAVA_INT, ADDRESS))
    val columnInt64 = handle("sqlite3_column_int64", FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT), critical)
    val columnText = handle("sqlite3_column_text", FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT), critical)
    val columnBytes = handle("sqlite3_column_bytes", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT), critical)
    val reset = handle("sqlite3_reset", FunctionDescriptor.of(JAVA_INT, ADDRESS))
    val clearBindings = handle("sqlite3_clear_bindings", FunctionDescriptor.of(JAVA_INT, ADDRESS), critical)

    private fun handle(name: String, descriptor: FunctionDescriptor, vararg options: Linker.Option): MethodHandle =
        linker.downcallHandle(lookup.find(name).orElseThrow { IllegalStateException("missing $name") }, descriptor, *options)

    /**
     * `CAMPFIRE_SQLITE_LIB`, else the library installed beside the app: `<app>/native/<os>-<arch>/`,
     * where `<app>` is the jars' parent directory, or a native image's own directory.
     */
    private fun libraryPath(): Path {
        System.getenv("CAMPFIRE_SQLITE_LIB")?.let { return Path.of(it) }
        val os = System.getProperty("os.name").lowercase()
        val arch = when (System.getProperty("os.arch")) {
            "amd64", "x86_64" -> "x86_64"
            else -> "aarch64"
        }
        val platform = (if (os.contains("mac")) "macos-" else "linux-") + arch
        val file = if (os.contains("mac")) "libcampfire_sqlite3.dylib" else "libcampfire_sqlite3.so"
        val jar = Sqlite::class.java.protectionDomain?.codeSource?.location?.let { Path.of(it.toURI()) }
        val home = if (jar != null && jar.toString().endsWith(".jar")) jar.parent.parent
        else Path.of(ProcessHandle.current().info().command().orElseThrow()).toRealPath().parent
        val path = home.resolve("native").resolve(platform).resolve(file)
        check(Files.exists(path)) { "no SQLite library at $path; run native/build.sh or set CAMPFIRE_SQLITE_LIB" }
        return path
    }
}
