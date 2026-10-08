package campfire.ruby

import java.math.BigInteger

/**
 * What Rack (3.2) does with request headers, where Ruby's string behavior shows through
 * (`once-campfire-rust/crates/ruby/src/rack.rs`).
 */
object Rack {
    private const val MAX_RANGES = 100

    /**
     * `Rack::Utils.get_byte_ranges(http_range, size)` (`rack.rs`, `byte_ranges`): null means
     * "serve everything" and an empty list means 416. The ranges are inclusive and within [size],
     * which is a byte count and so never negative.
     */
    fun byteRanges(header: String?, size: Long): List<LongRange>? {
        if (size == 0L) return null
        val spec = rangeSpec(header ?: return null) ?: return null
        if (spec.count { it == ',' } >= MAX_RANGES) return null
        val total = BigInteger.valueOf(size)
        val last = total - BigInteger.ONE
        val ranges = ArrayList<Pair<BigInteger, BigInteger>>()
        for (rangeSpec in rubySplit(spec, ::findComma)) {
            if ('-' !in rangeSpec) return null
            val parts = rubySplit(rangeSpec, ::findDash)
            val r0 = parts.getOrNull(0)
            val r1 = parts.getOrNull(1)
            val range = if (r0.isNullOrEmpty()) {
                (total - toI128(r1 ?: return null)).max(BigInteger.ZERO) to last
            } else {
                val first = toI128(r0)
                if (r1 == null) {
                    first to last
                } else {
                    val end = toI128(r1)
                    if (end < first) return null
                    first to end.min(last)
                }
            }
            if (range.first <= range.second) ranges.add(range)
        }
        val covered = ranges.fold(BigInteger.ZERO) { sum, (a, b) -> sum + (b - a + BigInteger.ONE) }
        if (covered > total) return emptyList()
        return ranges.map { (a, b) -> a.toLong()..b.toLong() }
    }

    /**
     * `http_range =~ /bytes=([^;]+)/`: after the first `bytes=` that something other than `;`
     * follows, up to the next `;`.
     */
    private fun rangeSpec(header: String): String? {
        var i = header.indexOf("bytes=")
        while (i >= 0) {
            val start = i + 6
            val end = header.indexOf(';', start).let { if (it < 0) header.length else it }
            if (end > start) return header.substring(start, end)
            i = header.indexOf("bytes=", i + 1)
        }
        return null
    }

    /**
     * Rack's range separator, the regexp `,[ \t]*`, in [s] from [from]: where the separator starts
     * and ends, or null.
     */
    private fun findComma(s: String, from: Int): IntRange? {
        val i = s.indexOf(',', from)
        if (i < 0) return null
        var end = i + 1
        while (end < s.length && (s[end] == ' ' || s[end] == '\t')) end++
        return i until end
    }

    /** `"-"` in [s] from [from]: where the separator starts and ends, or null. */
    private fun findDash(s: String, from: Int): IntRange? {
        val i = s.indexOf('-', from)
        return if (i < 0) null else i..i
    }

    /**
     * `String#split` with a separator finder that returns the separator's span: trailing empty
     * fields are dropped.
     */
    private fun rubySplit(s: String, find: (String, Int) -> IntRange?): List<String> {
        val fields = ArrayList<String>()
        var rest = 0
        while (true) {
            val separator = find(s, rest) ?: break
            fields.add(s.substring(rest, separator.first))
            rest = separator.last + 1
        }
        fields.add(s.substring(rest))
        while (fields.isNotEmpty() && fields.last().isEmpty()) fields.removeAt(fields.size - 1)
        return fields
    }
}
