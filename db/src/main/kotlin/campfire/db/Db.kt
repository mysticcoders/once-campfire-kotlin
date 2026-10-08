package campfire.db

import java.util.concurrent.ArrayBlockingQueue

/**
 * A UTC timestamp as Active Record stores it (`YYYY-MM-DD HH:MM:SS[.ffffff]`), kept as seconds
 * and microseconds so the room page's formats come straight from the fields.
 */
class Ts(val epochSecond: Long, val micros: Int, private val text: String) {
    fun toInstant(): java.time.Instant = java.time.Instant.ofEpochSecond(epochSecond, micros * 1000L)

    /** `time.iso8601`: seconds precision with a `Z`. */
    fun iso8601(): String = text.substring(0, 10) + "T" + text.substring(11, 19) + "Z"

    /** `time.to_fs(:number)`: `%Y%m%d%H%M%S`. */
    fun number(): String {
        val sb = StringBuilder(14)
        for (i in 0 until 19) {
            val c = text[i]
            if (c in '0'..'9') sb.append(c)
        }
        return sb.toString()
    }

    /** `cache_version`: `to_fs(:usec)`, `%Y%m%d%H%M%S` plus six digits of microseconds. */
    fun usecNumber(): String = number() + micros.toString().padStart(6, '0')

    /**
     * `time.to_fs(:epoch)`, `(to_f * 1000).to_i`: through a double on purpose, since Ruby's float
     * round trip truncates some milliseconds down by one and the client compares these numbers.
     */
    fun epochMs(): Long = ("$epochSecond." + (micros * 1000).toString().padStart(9, '0')).toDouble().times(1000.0).toLong()

    companion object {
        /** Parses Active Record's SQLite datetime text. */
        fun parse(text: String): Ts {
            val y = digits(text, 0, 4)
            val mo = digits(text, 5, 2)
            val d = digits(text, 8, 2)
            val h = digits(text, 11, 2)
            val mi = digits(text, 14, 2)
            val s = digits(text, 17, 2)
            var micros = 0
            if (text.length > 20 && text[19] == '.') {
                for (i in 20 until 26) micros = micros * 10 + (if (i < text.length) text[i] - '0' else 0)
            }
            return Ts(epochDay(y, mo, d) * 86400 + h * 3600 + mi * 60 + s, micros, text)
        }

        private fun digits(text: String, at: Int, count: Int): Int {
            var v = 0
            for (i in at until at + count) v = v * 10 + (text[i] - '0')
            return v
        }

        /** Days since 1970-01-01 in the proleptic Gregorian calendar (Howard Hinnant's algorithm). */
        private fun epochDay(y0: Int, m: Int, d: Int): Long {
            val y = if (m <= 2) y0 - 1 else y0
            val era = (if (y >= 0) y else y - 399) / 400
            val yoe = y - era * 400
            val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return era * 146097L + doe - 719468
        }
    }
}

class Session(val id: Long, val userId: Long, val token: String, val lastActiveAt: Ts) {
    /** `Session#resume`'s check (`crates/db/src/models/session.rs` `needs_resume`): active over an hour ago. */
    fun needsResume(now: java.time.Instant): Boolean = lastActiveAt.toInstant().isBefore(now.minusSeconds(ACTIVITY_REFRESH_SECONDS))

    companion object {
        /** `Session::ACTIVITY_REFRESH_RATE`, an hour. */
        const val ACTIVITY_REFRESH_SECONDS = 3600L
    }
}

class User(
    val id: Long,
    val name: String,
    val role: Int,
    val bio: String?,
    val updatedAt: Ts,
) {
    val administrator get() = role == 1

    /** `[ name, bio ].compact_blank.join(" – ")`. */
    val title: String
        get() = listOfNotNull(name, bio).filter { it.isNotBlank() }.joinToString(" – ")
}

enum class RoomKind(val type: String, val paramKey: String, val modelName: String) {
    Open("Rooms::Open", "rooms_open", "Rooms::Open"),
    Closed("Rooms::Closed", "rooms_closed", "Rooms::Closed"),
    Direct("Rooms::Direct", "rooms_direct", "Rooms::Direct");

    companion object {
        fun of(type: String) = entries.first { it.type == type }
    }
}

class Room(val id: Long, val name: String?, val kind: RoomKind, val creatorId: Long, val createdAt: Ts, val updatedAt: Ts)

class Message(val id: Long, val roomId: Long, val creatorId: Long, val clientMessageId: String, val createdAt: Ts, val updatedAt: Ts)

/** A message's `cache_key_with_version`: its id and its `updated_at` as stored. */
data class MessageKey(val id: Long, val version: String)

/** A message on a page: its cached fragment, or its row to render one from. */
class PageMessage<F : Any>(val key: MessageKey, val fragment: F?, val message: Message?)

class Boost(val id: Long, val messageId: Long, val boosterId: Long, val content: String, val updatedAt: Ts)

/** The account as the layout shows it. */
class LayoutAccount(val id: Long, val customStyles: String?, val updatedAt: Ts)

class Account(val id: Long, val name: String, val joinCode: String, val customStyles: String?, val updatedAt: Ts)

private const val SESSION_COLUMNS =
    """"sessions"."id", "sessions"."user_id", "sessions"."token", "sessions"."ip_address", "sessions"."user_agent", "sessions"."last_active_at", "sessions"."created_at", "sessions"."updated_at""""
private const val USER_COLUMNS =
    """"users"."id", "users"."name", "users"."email_address", "users"."password_digest", "users"."role", "users"."status", "users"."bio", "users"."bot_token", "users"."created_at", "users"."updated_at""""
private const val ROOM_COLUMNS =
    """"rooms"."id", "rooms"."name", "rooms"."type", "rooms"."creator_id", "rooms"."created_at", "rooms"."updated_at""""
private const val MESSAGE_COLUMNS =
    """"messages"."id", "messages"."room_id", "messages"."creator_id", "messages"."client_message_id", "messages"."created_at", "messages"."updated_at""""
private const val ACCOUNT_COLUMNS =
    """"accounts"."id", "accounts"."name", "accounts"."join_code", "accounts"."custom_styles", "accounts"."settings", "accounts"."singleton_guard", "accounts"."created_at", "accounts"."updated_at""""

/**
 * One reader connection and its prepared statements: the same SQL `crates/db` sends for the room
 * page, so both apps make SQLite do the same work.
 */
class Reader(path: String) {
    private val conn: SqlConnection = SqliteBackend.open(path).also { configureConnection(it, reader = true) }

    private val sessionByToken by prepared("SELECT $SESSION_COLUMNS FROM \"sessions\" WHERE \"sessions\".\"token\" = ? LIMIT 1")
    private val userById by prepared("SELECT $USER_COLUMNS FROM \"users\" WHERE \"users\".\"id\" = ? LIMIT 1")
    private val roomForUser by prepared(
        "SELECT $ROOM_COLUMNS FROM \"rooms\" INNER JOIN \"memberships\" ON \"rooms\".\"id\" = \"memberships\".\"room_id\" WHERE \"memberships\".\"user_id\" = ? AND \"rooms\".\"id\" = ? LIMIT 1",
    )
    private val firstRoomForUser by prepared(
        "SELECT $ROOM_COLUMNS FROM \"rooms\" INNER JOIN \"memberships\" ON \"rooms\".\"id\" = \"memberships\".\"room_id\" WHERE \"memberships\".\"user_id\" = ? ORDER BY \"rooms\".\"created_at\" ASC LIMIT 1",
    )
    private val roomById by prepared("SELECT $ROOM_COLUMNS FROM \"rooms\" WHERE \"rooms\".\"id\" = ? LIMIT 1")
    private val originalRoom by prepared("SELECT $ROOM_COLUMNS FROM \"rooms\" ORDER BY \"rooms\".\"created_at\" ASC LIMIT 1")
    private val roomUsers by prepared(
        "SELECT $USER_COLUMNS FROM \"users\" INNER JOIN \"memberships\" ON \"users\".\"id\" = \"memberships\".\"user_id\" WHERE \"memberships\".\"room_id\" = ?",
    )
    private val lastPage by prepared(
        "SELECT $MESSAGE_COLUMNS FROM \"messages\" WHERE \"messages\".\"room_id\" = ? ORDER BY \"messages\".\"created_at\" DESC LIMIT 40",
    )
    private val paged by prepared("SELECT 1 FROM \"messages\" WHERE \"messages\".\"room_id\" = ? LIMIT 1 OFFSET 40")
    private val firstAccount by prepared("SELECT $ACCOUNT_COLUMNS FROM \"accounts\" ORDER BY \"accounts\".\"id\" ASC LIMIT 1")
    private val richTextBody by prepared(
        "SELECT \"action_text_rich_texts\".\"id\", \"action_text_rich_texts\".\"name\", \"action_text_rich_texts\".\"body\", \"action_text_rich_texts\".\"record_type\", \"action_text_rich_texts\".\"record_id\", \"action_text_rich_texts\".\"created_at\", \"action_text_rich_texts\".\"updated_at\" FROM \"action_text_rich_texts\" WHERE \"action_text_rich_texts\".\"record_id\" = ? AND \"action_text_rich_texts\".\"record_type\" = ? AND \"action_text_rich_texts\".\"name\" = ? LIMIT 1",
    )
    private val attachedBlob by prepared(
        "SELECT b.id, b.key, b.filename, b.content_type, b.metadata, b.service_name, b.byte_size, b.checksum, b.created_at FROM active_storage_blobs b JOIN active_storage_attachments a ON a.blob_id = b.id WHERE a.record_type = ?1 AND a.record_id = ?2 AND a.name = ?3 ORDER BY a.id LIMIT 1",
    )
    private val boostsForMessage by prepared(
        "SELECT \"boosts\".\"id\", \"boosts\".\"message_id\", \"boosts\".\"booster_id\", \"boosts\".\"content\", \"boosts\".\"created_at\", \"boosts\".\"updated_at\" FROM \"boosts\" WHERE \"boosts\".\"message_id\" = ? ORDER BY \"boosts\".\"created_at\" ASC",
    )

    /** A statement prepared on first use and kept, as rusqlite's `prepare_cached` does. */
    private fun prepared(sql: String) = lazy(LazyThreadSafetyMode.NONE) { conn.prepare(sql) }

    private val bannedIp by prepared("SELECT 1 AS one FROM \"bans\" WHERE \"bans\".\"ip_address\" = ? LIMIT 1")
    private val botByKey by prepared(
        "SELECT $USER_COLUMNS FROM \"users\" WHERE \"users\".\"status\" = 0 AND \"users\".\"role\" = 2 AND \"users\".\"id\" = ? AND \"users\".\"bot_token\" = ? LIMIT 1",
    )

    /** `Ban.banned?(ip_address)` (`crates/db/src/models/ban.rs`). */
    fun banned(ipAddress: String): Boolean = bannedIp.one({ bindText(1, ipAddress) }) { true } ?: false

    /**
     * `User.authenticate_bot(bot_key)` (`crates/db/src/models/user.rs`): an active bot by
     * `<id>-<token>`; Ruby's `split("-")` drops trailing empty fields, so a key without a token
     * finds nothing.
     */
    fun authenticateBot(botKey: String): User? {
        val parts = botKey.split('-')
        val id = parts.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: return null
        val token = parts.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return null
        return botByKey.one({ bindText(1, id); bindText(2, token) }, ::readUser)
    }

    fun sessionByToken(token: String): Session? = sessionByToken.one({ bindText(1, token) }) {
        Session(integer(1), integer(2), text(3)!!, Ts.parse(text(6)!!))
    }

    fun user(id: Long): User? = userById.one({ bindLong(1, id) }, ::readUser)

    fun roomForUser(userId: Long, roomId: Long): Room? = roomForUser.one({ bindLong(1, userId); bindLong(2, roomId) }, ::readRoom)

    fun firstRoomForUser(userId: Long): Room? = firstRoomForUser.one({ bindLong(1, userId) }, ::readRoom)

    /** The original room's id: `Room.original`, the first created, reading only its id column. */
    fun originalRoomId(): Long? = originalRoom.one({}) { integer(1) }

    /** [roomForUser]'s query, reading only the room's id (the layout only needs to know it's there). */
    fun roomIdForUser(userId: Long, roomId: Long): Long? = roomForUser.one({ bindLong(1, userId); bindLong(2, roomId) }) { integer(1) }

    /** [firstRoomForUser]'s query, reading only the room's id. */
    fun firstRoomIdForUser(userId: Long): Long? = firstRoomForUser.one({ bindLong(1, userId) }) { integer(1) }

    /** [firstAccount]'s query, reading only `join_code`. */
    fun accountJoinCode(): String? = firstAccount.one({}) { text(3) }

    /** [firstAccount]'s query, reading only what the layout shows: the id, `custom_styles` and `updated_at`. */
    fun layoutAccount(): LayoutAccount? = firstAccount.one({}) { LayoutAccount(integer(1), text(4), Ts.parse(text(8)!!)) }

    fun room(id: Long): Room? = roomById.one({ bindLong(1, id) }, ::readRoom)

    fun originalRoom(): Room? = originalRoom.one({}, ::readRoom)

    fun roomUsers(roomId: Long): List<User> = roomUsers.all({ bindLong(1, roomId) }, ::readUser)

    /**
     * `room.messages.last_page`: the 40 newest, oldest first, as their id and version, with the
     * rest of a row read only when [cached] has no fragment for that version.
     */
    fun <F : Any> lastPage(roomId: Long, cached: (MessageKey) -> F?): List<PageMessage<F>> = lastPage.all({ bindLong(1, roomId) }) {
        val key = MessageKey(integer(1), text(6)!!)
        val fragment = cached(key)
        val message = if (fragment != null) null else Message(key.id, integer(2), integer(3), text(4)!!, Ts.parse(text(5)!!), Ts.parse(key.version))
        PageMessage(key, fragment, message)
    }.asReversed()

    fun paged(roomId: Long): Boolean = paged.one({ bindLong(1, roomId) }) { true } ?: false

    fun firstAccount(): Account? = firstAccount.one({}) {
        Account(integer(1), text(2)!!, text(3)!!, text(4), Ts.parse(text(8)!!))
    }

    fun richTextBody(recordType: String, recordId: Long, name: String): String? =
        richTextBody.one({ bindLong(1, recordId); bindText(2, recordType); bindText(3, name) }) { text(3) }

    fun hasAttachedBlob(recordType: String, recordId: Long, name: String): Boolean =
        attachedBlob.one({ bindText(1, recordType); bindLong(2, recordId); bindText(3, name) }) { true } ?: false

    fun boosts(messageId: Long): List<Boost> = boostsForMessage.all({ bindLong(1, messageId) }) {
        Boost(integer(1), integer(2), integer(3), text(4)!!, Ts.parse(text(6)!!))
    }

    /** Rows in the test table; only for `WriterTest`. */
    internal fun countForTest(): Long = conn.prepare("SELECT COUNT(*) FROM items").one({}) { integer(1) } ?: 0

    private fun readUser(rs: Stmt) = with(rs) { User(integer(1), text(2)!!, integer(5).toInt(), text(7), Ts.parse(text(10)!!)) }

    private fun readRoom(rs: Stmt) = with(rs) {
        Room(integer(1), text(2), RoomKind.of(text(3)!!), integer(4), Ts.parse(text(5)!!), Ts.parse(text(6)!!))
    }

    private inline fun <T> Stmt.one(bind: Stmt.() -> Unit, read: Stmt.() -> T): T? {
        bind()
        try {
            return if (step()) read() else null
        } finally {
            reset()
        }
    }

    private inline fun <T> Stmt.all(bind: Stmt.() -> Unit, read: Stmt.() -> T): List<T> {
        bind()
        val out = ArrayList<T>()
        try {
            while (step()) out.add(read())
        } finally {
            reset()
        }
        return out
    }
}

/**
 * `RAILS_MAX_THREADS` reader connections shared by the request threads, and the writer thread. A
 * request takes a free reader for each read and gives it back, so at most that many requests are
 * inside SQLite at once, as with `crates/db/src/database.rs`.
 */
class Database(path: String, readers: Int) {
    private val pool = java.util.concurrent.ArrayBlockingQueue<Reader>(readers).apply { repeat(readers) { add(Reader(path)) } }
    private val writer by lazy { Writer(path) }

    /** Runs [f] with a reader connection. */
    inline fun <T> read(f: (Reader) -> T): T {
        val reader = take()
        try {
            return f(reader)
        } finally {
            give(reader)
        }
    }

    /** Runs [f] in a write transaction on the writer thread. */
    fun <T> write(f: (Tx) -> T): T = writer.write(f)

    fun take(): Reader = pool.take()

    fun give(reader: Reader) {
        pool.add(reader)
    }
}

/**
 * `Session#refresh_activity`'s update (`crates/db/src/models/session.rs` `touch`): the request's
 * IP and user agent, and the time as `last_active_at` and `updated_at`.
 */
fun touchSession(tx: Tx, sessionId: Long, ipAddress: String?, userAgent: String?, now: String) {
    tx.execute(
        "UPDATE \"sessions\" SET \"ip_address\" = ?, \"last_active_at\" = ?, \"updated_at\" = ?, \"user_agent\" = ? WHERE \"sessions\".\"id\" = ?",
        ipAddress, now, now, userAgent, sessionId,
    )
}
