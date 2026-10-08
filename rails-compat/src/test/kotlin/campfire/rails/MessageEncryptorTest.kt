package campfire.rails

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `once-campfire-rust/crates/rails_compat/src/message_encryptor.rs` tests. */
class MessageEncryptorTest {
    @Test
    fun roundTripsAndRejectsTampering() {
        val encryptor = MessageEncryptor(ByteArray(32) { 7 }, Serializer.Null)
        val now = Instant.EPOCH
        val message = encryptor.encryptAndSign("hi", "p", null)
        assertEquals(Valid("hi"), encryptor.decryptAndVerify(message, "p", now))
        assertEquals(MessageError.PurposeMismatch, encryptor.decryptAndVerify(message, "q", now))
        val tampered = "A" + message.substring(1)
        assertTrue(encryptor.decryptAndVerify(tampered, "p", now) is MessageError || tampered == message)
        assertEquals(MessageError.InvalidSignature, encryptor.decryptAndVerify("short", null, now))
    }
}
