package campfire.kit

import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32
import java.util.zip.Deflater

private const val MIN_FRAGMENT = 1024
private const val MAX_GLUE = 256
private const val WINDOW = 32 * 1024
private const val PIECES_PER_FRAGMENT = 4
private const val MAX_TEXT_ENTRIES = 4096
private val HEX = HexFormat.of()

/** A part compressed: raw deflate ending on a sync flush, and the part's CRC with its x^(8·len). */
class Piece(val deflated: ByteArray, val crc: Int, val shift: Int)

/**
 * A cached HTML fragment (a message's), with its SHA-256 and the compressed pieces it has been
 * seen with, keyed by what came before it: the same scheme as `crates/kit/src/deflater/splice.rs`.
 */
class Fragment(val bytes: ByteArray) {
    val sha: ByteArray by lazy(LazyThreadSafetyMode.PUBLICATION) { sha256(bytes) }
    private val pieces = arrayOfNulls<FragmentPiece>(PIECES_PER_FRAGMENT)
    private var next = 0

    private class FragmentPiece(val before: Before, val glue: ByteArray, val piece: Piece)

    /** The stored piece for this fragment after [before] with [glue] in between, if any. */
    @Synchronized
    fun pieceAfter(before: Before, glue: ByteArray): Piece? {
        for (p in pieces) if (p != null && p.before == before && p.glue.contentEquals(glue)) return p.piece
        return null
    }

    /** Remembers a piece, replacing the oldest once there are [PIECES_PER_FRAGMENT]. */
    @Synchronized
    fun store(before: Before, glue: ByteArray, piece: Piece) {
        pieces[next] = FragmentPiece(before, glue, piece)
        next = (next + 1) % PIECES_PER_FRAGMENT
    }
}

/** What precedes a part: nothing, or a text or fragment by its SHA-256. */
class Before private constructor(private val kind: Int, private val sha: ByteArray?) {
    private val hash = 31 * kind + (sha?.contentHashCode() ?: 0)

    override fun equals(other: Any?) = other is Before && other.kind == kind && other.sha.contentEquals(sha)

    override fun hashCode() = hash

    companion object {
        val NOTHING = Before(0, null)

        fun text(sha: ByteArray) = Before(1, sha)

        fun fragment(sha: ByteArray) = Before(2, sha)
    }
}

/** A byte array as a map key, by content, with its hash computed once. */
private class BytesKey(val bytes: ByteArray) {
    private val hash = bytes.contentHashCode()

    override fun equals(other: Any?) = other is BytesKey && other.hash == hash && other.bytes.contentEquals(bytes)

    override fun hashCode() = hash
}

private class TextPieceKey(val sha: ByteArray, val before: Before) {
    private val hash = sha.contentHashCode() * 31 + before.hashCode()

    override fun equals(other: Any?) = other is TextPieceKey && other.sha.contentEquals(sha) && other.before == before

    override fun hashCode() = hash
}

private val TEXT_SHAS = ConcurrentHashMap<BytesKey, ByteArray>()
private val TEXT_PIECES = ConcurrentHashMap<TextPieceKey, Piece>()

/**
 * A page body split at its cached fragments: text parts (the layout) and fragment parts (each
 * with the short text before it as glue). A plain response sends the parts as they are; gzip is
 * the parts' stored pieces joined, and the ETag a hash of the parts' digests.
 */
class PageParts private constructor(val parts: List<Part>, val length: Int) {
    sealed class Part {
        class Text(val bytes: ByteArray, val sha: ByteArray) : Part()

        class Frag(val fragment: Fragment, val glue: ByteArray) : Part()
    }

    /**
     * `W/"<32 hex>"`, from the parts as `PageParts::etag` computes it; or, for a page with no
     * fragment big enough for a part of its own (an empty room), `Rack::ETag`'s digest of the
     * whole body, as the Rust port falls back to then.
     */
    fun etag(): String {
        val md = ETAG_SHA256.get()
        if (parts.none { it is Part.Frag }) {
            for (part in parts) md.update((part as Part.Text).bytes)
            return "W/\"" + HEX.formatHex(md.digest(), 0, 16) + "\""
        }
        val len = ByteArray(8)
        for (part in parts) {
            when (part) {
                is Part.Text -> {
                    md.update('T'.code.toByte())
                    md.update(le64(len, part.bytes.size.toLong()))
                    md.update(part.sha)
                }
                is Part.Frag -> {
                    md.update('F'.code.toByte())
                    md.update(le64(len, part.glue.size.toLong()))
                    md.update(part.glue)
                    md.update(le64(len, part.fragment.bytes.size.toLong()))
                    md.update(part.fragment.sha)
                }
            }
        }
        return "W/\"" + HEX.formatHex(md.digest(), 0, 16) + "\""
    }

    /** The body's chunks in order, sharing the parts' arrays. */
    fun chunks(): List<ByteArray> {
        val out = ArrayList<ByteArray>(parts.size * 2)
        for (part in parts) {
            when (part) {
                is Part.Text -> out.add(part.bytes)
                is Part.Frag -> {
                    if (part.glue.isNotEmpty()) out.add(part.glue)
                    out.add(part.fragment.bytes)
                }
            }
        }
        return out
    }

    /** The whole gzip member as chunks: header, each part's piece, an empty final block and the trailer. */
    fun gzipChunks(mtime: Int): List<ByteArray> {
        val out = ArrayList<ByteArray>(parts.size + 2)
        val header = byteArrayOf(0x1f, 0x8b.toByte(), 8, 0, mtime.toByte(), (mtime shr 8).toByte(), (mtime shr 16).toByte(), (mtime shr 24).toByte(), 0, 3)
        out.add(header)
        var before = Before.NOTHING
        var dictionary: ByteArray? = null
        var crc = 0
        for (part in parts) {
            val piece = when (part) {
                is Part.Text -> {
                    val key = TextPieceKey(part.sha, before)
                    TEXT_PIECES[key] ?: compress(dictionary, part.bytes).also {
                        if (TEXT_PIECES.size > MAX_TEXT_ENTRIES) TEXT_PIECES.clear()
                        TEXT_PIECES[key] = it
                    }
                }
                is Part.Frag -> part.fragment.pieceAfter(before, part.glue)
                    ?: compress(dictionary, if (part.glue.isEmpty()) part.fragment.bytes else part.glue + part.fragment.bytes).also {
                        part.fragment.store(before, part.glue, it)
                    }
            }
            out.add(piece.deflated)
            crc = Crc.multiply(crc, piece.shift) xor piece.crc
            when (part) {
                is Part.Text -> {
                    before = Before.text(part.sha)
                    dictionary = part.bytes
                }
                is Part.Frag -> {
                    before = Before.fragment(part.fragment.sha)
                    dictionary = part.fragment.bytes
                }
            }
        }
        val trailer = ByteArray(10)
        trailer[0] = 3
        trailer[1] = 0
        le32(trailer, 2, crc)
        le32(trailer, 6, length)
        out.add(trailer)
        return out
    }

    /**
     * Collects the page in order, writing text straight to UTF-8 bytes and recording where each
     * fragment goes, then splits it into parts.
     */
    class Builder {
        private var buf = ByteArray(64 * 1024)
        private var len = 0
        private val fragments = ArrayList<Fragment>(48)
        private val offsets = ArrayList<Int>(48)

        fun append(bytes: ByteArray): Builder {
            ensure(bytes.size)
            System.arraycopy(bytes, 0, buf, len, bytes.size)
            len += bytes.size
            return this
        }

        fun append(s: String): Builder {
            val n = s.length
            if (n > 32) return append(s.toByteArray(Charsets.UTF_8))
            ensure(n)
            var i = 0
            while (i < n) {
                val c = s[i]
                if (c.code >= 0x80) break
                buf[len++] = c.code.toByte()
                i++
            }
            if (i < n) appendUtf8(s, i)
            return this
        }

        fun append(c: Char): Builder = append(c.toString())

        fun append(n: Long): Builder {
            ensure(20)
            if (n < 0) return append(n.toString())
            var start = len
            var v = n
            do {
                buf[len++] = ('0'.code + (v % 10).toInt()).toByte()
                v /= 10
            } while (v != 0L)
            var end = len - 1
            while (start < end) {
                val t = buf[start]; buf[start] = buf[end]; buf[end] = t
                start++; end--
            }
            return this
        }

        fun fragment(fragment: Fragment) {
            fragments.add(fragment)
            offsets.add(len)
        }

        private fun appendUtf8(s: String, from: Int) {
            val bytes = s.substring(from).toByteArray(Charsets.UTF_8)
            ensure(bytes.size)
            System.arraycopy(bytes, 0, buf, len, bytes.size)
            len += bytes.size
        }

        private fun ensure(extra: Int) {
            if (len + extra > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + extra))
        }

        /** The parts, with fragments under [MIN_FRAGMENT] folded into the text around them. */
        fun build(): PageParts {
            val parts = ArrayList<Part>(fragments.size + 2)
            var total = len
            var pending = java.io.ByteArrayOutputStream(0)
            var position = 0
            for (k in fragments.indices) {
                val fragment = fragments[k]
                val offset = offsets[k]
                total += fragment.bytes.size
                if (fragment.bytes.size < MIN_FRAGMENT) {
                    pending.write(buf, position, offset - position)
                    pending.write(fragment.bytes)
                    position = offset
                    continue
                }
                val gap = if (pending.size() == 0) buf.copyOfRange(position, offset) else {
                    pending.write(buf, position, offset - position)
                    pending.toByteArray().also { pending = java.io.ByteArrayOutputStream(0) }
                }
                position = offset
                val followsFragment = parts.lastOrNull() is Part.Frag
                if (followsFragment && gap.size <= MAX_GLUE) {
                    parts.add(Part.Frag(fragment, gap))
                } else {
                    if (gap.isNotEmpty()) parts.add(textPart(gap))
                    parts.add(Part.Frag(fragment, EMPTY))
                }
            }
            pending.write(buf, position, len - position)
            val rest = pending.toByteArray()
            if (rest.isNotEmpty()) parts.add(textPart(rest))
            return PageParts(parts, total)
        }
    }

    companion object {
        private val EMPTY = ByteArray(0)

        private fun textPart(bytes: ByteArray): Part.Text {
            val key = BytesKey(bytes)
            val sha = TEXT_SHAS[key] ?: sha256(bytes).also {
                if (TEXT_SHAS.size > MAX_TEXT_ENTRIES) TEXT_SHAS.clear()
                TEXT_SHAS[key] = it
            }
            return Part.Text(bytes, sha)
        }

        /** Raw deflate at level 6 with the last 32 KB of [dictionary] preset, sync-flushed. */
        private fun compress(dictionary: ByteArray?, input: ByteArray): Piece {
            val deflater = Deflater(6, true)
            try {
                if (dictionary != null && dictionary.isNotEmpty()) {
                    val start = maxOf(0, dictionary.size - WINDOW)
                    deflater.setDictionary(dictionary, start, dictionary.size - start)
                }
                deflater.setInput(input)
                val out = java.io.ByteArrayOutputStream(input.size / 4 + 64)
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = deflater.deflate(buf, 0, buf.size, Deflater.SYNC_FLUSH)
                    out.write(buf, 0, n)
                    if (n < buf.size && deflater.needsInput()) break
                }
                val crc = CRC32().apply { update(input) }.value.toInt()
                return Piece(out.toByteArray(), crc, Crc.xToThe8(input.size.toLong()))
            } finally {
                deflater.end()
            }
        }

        private fun le64(buf: ByteArray, v: Long): ByteArray {
            for (i in 0 until 8) buf[i] = (v ushr (8 * i)).toByte()
            return buf
        }

        private fun le32(buf: ByteArray, at: Int, v: Int) {
            for (i in 0 until 4) buf[at + i] = (v ushr (8 * i)).toByte()
        }
    }
}

/**
 * CRC-32 of concatenated parts from each part's CRC: crc(a‖b) = crc(a)·x^(8|b|) + crc(b) over
 * GF(2) modulo the CRC polynomial (zlib's `crc32_combine_op`).
 */
object Crc {
    private const val POLYNOMIAL = 0xedb88320.toInt()
    private val X_TO_THE_2_TO_THE = IntArray(32).also { table ->
        var power = 1 shl 30
        for (k in 0 until 32) {
            table[k] = power
            power = multiply(power, power)
        }
    }

    /** `a` times `b` modulo the polynomial (zlib's `multmodp`). */
    fun multiply(a: Int, b0: Int): Int {
        var b = b0
        var product = 0
        var bit = 32
        while (bit > 0) {
            bit--
            product = product xor (b and -((a ushr bit) and 1))
            b = (b ushr 1) xor (POLYNOMIAL and -(b and 1))
        }
        return product
    }

    /** x^(8·n) modulo the polynomial (zlib's `x2nmodp(n, 3)`). */
    fun xToThe8(n0: Long): Int {
        var n = n0
        var power = 1 shl 31
        var k = 3
        while (n != 0L) {
            if (n and 1L == 1L) power = multiply(X_TO_THE_2_TO_THE[k % 32], power)
            n = n ushr 1
            k++
        }
        return power
    }
}

private val SHA256: ThreadLocal<MessageDigest> = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }
private val ETAG_SHA256: ThreadLocal<MessageDigest> = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }

fun sha256(bytes: ByteArray): ByteArray = SHA256.get().digest(bytes)
