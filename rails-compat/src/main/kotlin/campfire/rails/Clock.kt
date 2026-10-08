package campfire.rails

import java.time.Duration
import java.time.Instant

/**
 * `Time.current` behind an interface (`once-campfire-rust/crates/rails_compat/src/clock.rs`). The
 * models, cookies and sessions read the time from a clock as they go; what needs it once
 * (verifiers, storage) takes a `now` argument instead. Parity runs freeze the whole app with a
 * [TestClock] (`CAMPFIRE_FROZEN_TIME`), and tests move one.
 */
fun interface Clock {
    /** `Time.current`. */
    fun now(): Instant
}

/** The real time. */
object SystemClock : Clock {
    override fun now(): Instant = Instant.now()
}

/**
 * `ActiveSupport::Testing::TimeHelpers`: real time shifted by [travel], or frozen with [travelTo].
 * Safe to share between threads.
 */
class TestClock : Clock {
    private var offset: Duration = Duration.ZERO
    private var frozen: Instant? = null

    /** `travel_to`: freezes time at `at`. */
    @Synchronized
    fun travelTo(at: Instant) {
        frozen = at
    }

    /** `travel`: moves the clock forward by `by`, keeping it frozen if it was. */
    @Synchronized
    fun travel(by: Duration) {
        val at = frozen
        if (at != null) frozen = at + by else offset += by
    }

    /** `travel_back`: real time again. */
    @Synchronized
    fun travelBack() {
        offset = Duration.ZERO
        frozen = null
    }

    @Synchronized
    override fun now(): Instant = frozen ?: (Instant.now() + offset)

    companion object {
        /** A clock frozen at `at`. */
        fun frozenAt(at: Instant): TestClock = TestClock().also { it.travelTo(at) }
    }
}
