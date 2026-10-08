package campfire.rails

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The spike-era `Secrets` methods the app calls, checked against the same golden vectors. */
class SecretsTest {
    private val vectors = ObjectMapper().readTree(File(System.getProperty("campfire.vectors"), "rails_compat.json"))
    private val secrets = Secrets(vectors["secret_key_base"].asText())
    private val now = Instant.parse(vectors["now"].asText())

    @Test
    fun avatarTokenIsTheUserAvatarSignedId() {
        val case = vectors["signed_ids"]["generate"].first { it["model"].asText() == "User" && it["purpose"]?.asText() == "avatar" && it["expires_at"].isNull }
        assertEquals(case["signed_id"].asText(), secrets.avatarToken(case["id"].asLong()))
    }

    @Test
    fun signedStreamNameMatchesTurbo() {
        val case = vectors["turbo_stream_names"]["generate"][0]
        val parts = case["parts"].map { it.asText() }.toTypedArray()
        assertEquals(case["signed"].asText(), secrets.signedStreamName(*parts))
        assertEquals(parts[0], globalIdParam("Rooms::Open", 1))
    }

    @Test
    fun verifySignedCookieReadsTheSessionToken() {
        val session = vectors["session"]
        assertEquals(session["session_token_value"].asText(), secrets.verifySignedCookie("session_token", session["session_token_raw"].asText(), now))
        assertNull(secrets.verifySignedCookie("other", session["session_token_raw"].asText(), now))
    }

    @Test
    fun jsonStringIsActiveSupportJson() {
        assertEquals("\"\\u003c\\u0026\\u003e\u2028\"", jsonString("<&>\u2028"))
    }
}
