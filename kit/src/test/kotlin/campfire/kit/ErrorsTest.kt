package campfire.kit

import kotlin.test.Test
import kotlin.test.assertEquals

/** The tests of `once-campfire-rust/crates/kit/src/error.rs`. */
class ErrorsTest {
    @Test
    fun withStatusAnswersTheStatusAndLogsTheCause() {
        val cause = RuntimeException("validation failed", IllegalArgumentException("name can't be blank"))
        val error = KitError.WithStatus(422, cause)

        assertEquals(422, error.status)
        assertEquals("422 Unprocessable Entity: validation failed: name can't be blank", error.message)
    }

    @Test
    fun haltsCarryTheirResponse() {
        val error = KitError.Halt(Response(302))
        assertEquals(302, error.status)
        assertEquals("halted with 302 Found", error.message)
    }
}
