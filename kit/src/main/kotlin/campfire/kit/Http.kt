package campfire.kit

import java.net.InetSocketAddress

/**
 * HTTP header fields with `http::HeaderMap`'s ordering, which the Rust port's responses are
 * written in: one entry per name (stored lowercased, compared case-insensitively) in the order
 * names were first inserted, each holding its values in order. [add] appends to an existing
 * entry's values, [set] replaces them in place, and [remove] swap-removes the entry: the last
 * entry takes its position, as `HeaderMap::remove` does.
 */
class Headers : Iterable<Pair<String, String>> {
    private val names = ArrayList<String>(16)
    private val values = ArrayList<ArrayList<String>>(16)

    /** The number of field lines (every value of every name). */
    val size: Int get() = values.sumOf { it.size }

    /** [name]'s entry index, comparing ASCII case-insensitively without allocating. */
    private fun entry(name: String): Int {
        for (i in names.indices) if (sameName(names[i], name)) return i
        return -1
    }

    private fun sameName(stored: String, name: String): Boolean {
        if (stored.length != name.length) return false
        for (k in stored.indices) {
            val c = name[k]
            if (stored[k] != (if (c in 'A'..'Z') c + 32 else c)) return false
        }
        return true
    }

    /** The first value for [name], or null. */
    operator fun get(name: String): String? = entry(name).let { if (it < 0) null else values[it][0] }

    /** Every value for [name], in order. */
    fun all(name: String): List<String> = entry(name).let { if (it < 0) emptyList() else ArrayList(values[it]) }

    operator fun contains(name: String): Boolean = entry(name) >= 0

    /** `HeaderMap::append`: another value for [name], after its existing ones. */
    fun add(name: String, value: String): Headers = apply {
        val i = entry(name)
        if (i >= 0) {
            values[i].add(value)
        } else {
            names.add(name.lowercase())
            values.add(arrayListOf(value))
        }
    }

    /** `HeaderMap::insert`: [name]'s only value, in its entry's place (or a new entry at the end). */
    operator fun set(name: String, value: String) {
        val i = entry(name)
        if (i >= 0) {
            values[i] = arrayListOf(value)
        } else {
            names.add(name.lowercase())
            values.add(arrayListOf(value))
        }
    }

    /** `HeaderMap::remove`: drops [name]'s entry, moving the last entry into its place. */
    fun remove(name: String) {
        val i = entry(name)
        if (i < 0) return
        val last = names.size - 1
        if (i != last) {
            names[i] = names[last]
            values[i] = values[last]
        }
        names.removeAt(last)
        values.removeAt(last)
    }

    override fun iterator(): Iterator<Pair<String, String>> = object : Iterator<Pair<String, String>> {
        private var entry = 0
        private var value = 0

        override fun hasNext(): Boolean {
            while (entry < names.size && value >= values[entry].size) {
                entry++
                value = 0
            }
            return entry < names.size
        }

        override fun next(): Pair<String, String> {
            if (!hasNext()) throw NoSuchElementException()
            return names[entry] to values[entry][value++]
        }
    }

    fun copy(): Headers = Headers().also { copy -> for ((name, value) in this) copy.add(name, value) }

    override fun toString(): String = joinToString(", ") { "${it.first}: ${it.second}" }
}

/** A request as the app sees it: the target split into path and query, and the whole body read. */
class Request(
    val method: String,
    val target: String,
    val headers: Headers,
    val body: ByteArray = EMPTY,
    val remote: InetSocketAddress? = null,
    val version: String = "HTTP/1.1",
) {
    val path: String = target.substringBefore('?')
    val query: String? = target.indexOf('?').let { if (it < 0) null else target.substring(it + 1) }

    /** Values a layer attaches for the ones after it (the Rust port's request extensions). */
    val extensions = HashMap<Class<*>, Any>(4)

    inline fun <reified T : Any> extension(): T? = extensions[T::class.java] as T?

    inline fun <reified T : Any> putExtension(value: T) {
        extensions[T::class.java] = value
    }

    companion object {
        val EMPTY = ByteArray(0)
    }
}

/** A response body: nothing, one buffer, buffers sent one after another, or a page's parts. */
sealed interface Body {
    /** The body's length in bytes. */
    val length: Long

    /** The body as buffers in order, sharing the underlying arrays. */
    fun chunks(): List<ByteArray>

    data object Empty : Body {
        override val length = 0L

        override fun chunks(): List<ByteArray> = emptyList()
    }

    class Bytes(val bytes: ByteArray) : Body {
        override val length get() = bytes.size.toLong()

        override fun chunks(): List<ByteArray> = listOf(bytes)
    }

    class Chunks(private val list: List<ByteArray>) : Body {
        override val length = list.sumOf { it.size.toLong() }

        override fun chunks(): List<ByteArray> = list
    }

    /** A page split at its cached fragments, which gzip and ETags reuse (see [PageParts]). */
    class Page(val parts: PageParts) : Body {
        override val length get() = parts.length.toLong()

        override fun chunks(): List<ByteArray> = parts.chunks()
    }

    /**
     * [len] bytes of the file at [path] from [offset] (`FileBody` in
     * `once-campfire-rust/crates/kit/src/response.rs`), read when the body is sent; `send_file`
     * builds one. [chunks] reads it in 64 KB pieces and throws the `IOException` if it can't.
     */
    class File(val path: java.nio.file.Path, val offset: Long, val len: Long) : Body {
        override val length get() = len

        override fun chunks(): List<ByteArray> {
            val out = ArrayList<ByteArray>((len / CHUNK + 1).toInt())
            java.nio.channels.FileChannel.open(path, java.nio.file.StandardOpenOption.READ).use { channel ->
                var position = offset
                var remaining = len
                while (remaining > 0) {
                    val buffer = java.nio.ByteBuffer.allocate(minOf(remaining, CHUNK.toLong()).toInt())
                    while (buffer.hasRemaining()) {
                        if (channel.read(buffer, position + buffer.position()) < 0) break
                    }
                    if (buffer.position() == 0) break
                    out.add(if (buffer.hasRemaining()) buffer.array().copyOf(buffer.position()) else buffer.array())
                    position += buffer.position()
                    remaining -= buffer.position()
                }
            }
            return out
        }

        override fun toString(): String = "File($path, $offset, $len)"

        private companion object {
            const val CHUNK = 64 * 1024
        }
    }

    companion object {
        fun of(text: String): Body = Bytes(text.toByteArray(Charsets.UTF_8))
    }
}

/** A response under construction; layers on the way out adjust its status, headers and body. */
class Response(var status: Int, val headers: Headers = Headers(), var body: Body = Body.Empty) {
    /** Values a layer attaches for the ones before it (the Rust port's response extensions). */
    val extensions = HashMap<Class<*>, Any>(2)

    inline fun <reified T : Any> extension(): T? = extensions[T::class.java] as T?

    inline fun <reified T : Any> putExtension(value: T) {
        extensions[T::class.java] = value
    }

    companion object {
        fun text(status: Int, text: String, contentType: String = "text/plain; charset=utf-8"): Response =
            Response(status, Headers().add("content-type", contentType), Body.of(text))
    }
}

/** Handles a request on a worker thread, blocking as needed (database reads run inline). */
fun interface Handler {
    fun handle(request: Request): Response
}

/** A layer around a handler: it may answer itself, or call [next] and adjust what comes back. */
fun interface Middleware {
    fun handle(request: Request, next: Handler): Response
}

/** This handler inside [layers], the first being the outermost, as a Tower/axum stack is applied. */
fun Handler.layered(vararg layers: Middleware): Handler =
    layers.foldRight(this) { layer, inner -> Handler { request -> layer.handle(request, inner) } }
