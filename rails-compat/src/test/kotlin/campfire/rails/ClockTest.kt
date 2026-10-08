package campfire.rails

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `once-campfire-rust/crates/rails_compat/src/clock.rs` tests. */
class ClockTest {
    @Test
    fun aFrozenClockMovesOnlyWhenTold() {
        val t = Instant.parse("2024-06-01T12:00:00Z")
        val clock = TestClock.frozenAt(t)
        assertEquals(t, clock.now())
        clock.travel(Duration.ofSeconds(60))
        assertEquals("2024-06-01T12:01:00Z", clock.now().toString())
        clock.travelTo(t)
        assertEquals(t, clock.now())
    }

    @Test
    fun travelShiftsRealTimeUntilTravelBack() {
        val clock = TestClock()
        clock.travel(Duration.ofHours(1))
        val shifted = clock.now().let { Duration.between(Instant.now(), it) }
        assertTrue(shifted > Duration.ofMinutes(59) && shifted <= Duration.ofHours(1), "$shifted")

        clock.travelBack()
        assertTrue(clock.now().let { Duration.between(Instant.now(), it) } <= Duration.ZERO)
    }
}
