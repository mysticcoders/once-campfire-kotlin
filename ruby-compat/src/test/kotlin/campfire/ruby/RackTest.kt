package campfire.ruby

import kotlin.test.Test
import kotlin.test.assertEquals

/** `once-campfire-rust/crates/ruby/src/rack.rs` tests. */
class RackTest {
    private class Case(val header: String, val size: Long, val ranges: List<LongRange>?)

    private fun case(header: String, size: Long, vararg ranges: Pair<Long, Long>) =
        Case(header, size, ranges.map { (a, b) -> a..b })

    private fun whole(header: String, size: Long) = Case(header, size, null)

    /** `Rack::Utils.get_byte_ranges(header, size)` in the reference (rack 3.2.6). */
    private val rackRanges = listOf(
        case("bytes=0-4", 10, 0L to 4L),
        case("bytes=5-", 10, 5L to 9L),
        case("bytes=-3", 10, 7L to 9L),
        case("bytes=-30", 10, 0L to 9L),
        case("bytes=0-99999999999999999999", 10, 0L to 9L),
        case("bytes=99999999999999999999-", 10),
        case("bytes=-99999999999999999999", 10, 0L to 9L),
        case("bytes=0-1,", 10, 0L to 1L),
        case("bytes= -5", 10, 0L to 5L),
        case("bytes=0-1, 3-4", 10, 0L to 1L, 3L to 4L),
        case("bytes=0-1,\t 3-4", 10, 0L to 1L, 3L to 4L),
        case("bytes=3-5,1-2", 10, 3L to 5L, 1L to 2L),
        case("bytes=+1-2", 10, 1L to 2L),
        case("bytes=1-+2", 10, 1L to 2L),
        case("bytes=1_0-2_0", 100, 10L to 20L),
        case("bytes=0d5-0d9", 100, 5L to 9L),
        case("bytes= 1-2", 10, 0L to 2L),
        case("bytes=a-b", 10, 0L to 0L),
        case("bytes=0-0x5", 10, 0L to 0L),
        case("bytes=0-1 ", 10, 0L to 1L),
        case("bytes=0 -1", 10, 0L to 1L),
        case("bytes=1-2-3", 10, 1L to 2L),
        case("bytes=9-9", 10, 9L to 9L),
        case("bytes=10-", 10),
        case("bytes=10-12", 10),
        case("bytes=-0", 10),
        case("bytes=--5", 10),
        case("bytes=0-4,5-9,0-0", 10),
        case("bytes=;bytes=2-3", 10, 2L to 3L),
        case("bytes=0-1;bytes=2-3", 10, 0L to 1L),
        case("xbytes=0-1", 10, 0L to 1L),
        whole("bytes=;0-1", 10),
        whole("bytes=", 10),
        whole("bytes=-", 10),
        whole("bytes=5", 10),
        whole("bytes=1-0", 10),
        whole("bytes=0-1,,2-3", 10),
        whole("items=0-1", 10),
        whole("bytes=0-4", 0),
    )

    /** Every case above, no header, and Rack's `max_ranges`: 99 commas are read, and 100 aren't. */
    @Test
    fun byteRangesMatchRack() {
        for (case in rackRanges) {
            assertEquals(case.ranges, Rack.byteRanges(case.header, case.size), "${case.header} of ${case.size}")
        }
        assertEquals(null, Rack.byteRanges(null, 10))
        assertEquals(emptyList(), Rack.byteRanges("bytes=0-1," + "0-0,".repeat(98), 10))
        assertEquals(null, Rack.byteRanges("bytes=0-1," + "0-0,".repeat(99), 10))
    }
}
