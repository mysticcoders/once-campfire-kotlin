package campfire.richtext.html

/**
 * markup5ever 0.35's `BufferQueue` (`util/buffer_queue.rs`): the tokenizer's input, a queue of
 * strings it reads from the front of and can push characters back onto. Each buffer is a string
 * and a read position, so pushing back and reading on never copies the input.
 */
internal class BufferQueue {
    private class Buffer(val text: String, var pos: Int)

    private val buffers = ArrayDeque<Buffer>()

    /** Whether there is nothing left to read. */
    fun isEmpty(): Boolean = buffers.isEmpty()

    /** Adds [text] to the back of the queue (an empty string is ignored). */
    fun pushBack(text: String) {
        if (text.isNotEmpty()) buffers.addLast(Buffer(text, 0))
    }

    /** Puts [text] back at the front of the queue (an empty string is ignored). */
    fun pushFront(text: CharSequence) {
        if (text.isNotEmpty()) buffers.addFirst(Buffer(text.toString(), 0))
    }

    /** Throws away everything left to read (`replace_with(BufferQueue::default())`). */
    fun clear() {
        buffers.clear()
    }

    /** The next character, without consuming it, or -1. */
    fun peek(): Int {
        val front = buffers.firstOrNull() ?: return -1
        return front.text[front.pos].code
    }

    /** Consumes and returns the next character, or -1. */
    fun next(): Int {
        val front = buffers.firstOrNull() ?: return -1
        val c = front.text[front.pos++]
        if (front.pos == front.text.length) buffers.removeFirst()
        return c.code
    }

    /**
     * `pop_except_from`: the longest run at the front of the first buffer with no character from
     * [set] (returned as a string, with [run] true), or else the single character from the set
     * (with [run] false). Returns false when the queue is empty.
     */
    fun popExceptFrom(set: SmallCharSet, result: SetResult): Boolean {
        val front = buffers.firstOrNull() ?: return false
        val text = front.text
        val start = front.pos
        var end = start
        while (end < text.length && !set.contains(text[end])) end++
        if (end > start) {
            result.run = text.substring(start, end)
            result.char = -1
            front.pos = end
        } else {
            result.run = null
            result.char = text[start].code
            front.pos = start + 1
        }
        if (front.pos == text.length) buffers.removeFirst()
        return true
    }

    /**
     * The data state's fast path (html5ever's SIMD scan): the run at the front of the first
     * buffer up to the next `<`, `&`, `\r` or NUL, newlines included. The caller has checked that
     * the first character isn't one of those or a newline. Returns the run.
     */
    fun popDataRun(): String {
        val front = buffers.first()
        val text = front.text
        val start = front.pos
        var end = start
        while (end < text.length) {
            val c = text[end]
            if (c == '<' || c == '&' || c == '\r' || c == '\u0000') break
            end++
        }
        front.pos = end
        if (end == text.length) buffers.removeFirst()
        return text.substring(start, end)
    }

    /**
     * `BufferQueue::eat`: whether the input starts with [pattern] (ASCII case-insensitively when
     * [ignoreCase]), consuming it if so; null when the input ends before the comparison can be
     * decided.
     */
    fun eat(pattern: String, ignoreCase: Boolean): Boolean? {
        if (buffers.isEmpty()) return null
        var bufferIndex = 0
        var pos = buffers[0].pos
        for (p in pattern) {
            if (bufferIndex >= buffers.size) return null
            val buffer = buffers[bufferIndex]
            val c = buffer.text[pos]
            val equal = if (ignoreCase) asciiLower(c) == asciiLower(p) else c == p
            if (!equal) return false
            pos++
            if (pos >= buffer.text.length) {
                bufferIndex++
                pos = if (bufferIndex < buffers.size) buffers[bufferIndex].pos else 0
            }
        }
        repeat(bufferIndex) { buffers.removeFirst() }
        if (buffers.isNotEmpty()) buffers.first().pos = pos
        return true
    }

    /** Moves everything left to read into [out] and empties the queue. */
    fun drainTo(out: StringBuilder) {
        for (b in buffers) out.append(b.text, b.pos, b.text.length)
        buffers.clear()
    }

    private fun asciiLower(c: Char): Char = if (c in 'A'..'Z') c + 32 else c
}

/** What [BufferQueue.popExceptFrom] found: a run of characters outside the set, or one inside it. */
internal class SetResult {
    var run: String? = null
    var char: Int = -1
}

/** markup5ever's `SmallCharSet`: a set of characters below 64, as a bit mask. */
internal class SmallCharSet(vararg chars: Char) {
    private val bits: Long = chars.fold(0L) { acc, c -> acc or (1L shl c.code) }

    /** Whether [c] is in the set. */
    fun contains(c: Char): Boolean = c.code < 64 && (bits shr c.code) and 1L != 0L
}
