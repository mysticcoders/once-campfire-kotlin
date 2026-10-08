package campfire.kit.front

import java.net.InetAddress
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The tests of `crates/kit/src/front/config.rs`. */
class FrontConfigTest {
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    private fun config(vararg vars: Pair<String, String>): FrontConfig {
        val map = vars.toMap()
        return FrontConfig.fromLookup { map[it] }
    }

    @Test
    fun defaults() {
        val c = config()
        assertEquals(Triple(80, 443, 3000), Triple(c.httpPort, c.httpsPort, c.targetPort))
        assertEquals(loopback, c.targetBind)
        assertEquals(64L * 1024 * 1024, c.cacheSize)
        assertEquals(1024L * 1024, c.maxCacheItemSize)
        assertEquals(Duration.ofSeconds(60), c.httpIdleTimeout)
        assertEquals(Duration.ofSeconds(30), c.httpReadTimeout)
        assertEquals(Duration.ofSeconds(30), c.httpWriteTimeout)
        assertEquals(Path.of("./storage/thruster"), c.storagePath)
        assertEquals(LETS_ENCRYPT_URL, c.acmeDirectoryUrl)
        assertEquals(32L, c.gzipCompressionJitter)
        assertEquals(0L, c.maxRequestBody)
        assertEquals("", c.eabKid)
        assertEquals("", c.eabHmacKey)
        assertTrue(c.gzipCompressionEnabled && !c.gzipCompressionDisableOnAuth && !c.h2cEnabled && !c.debug)
        assertTrue(!c.hasTls && c.forwardHeaders && c.logRequests)
    }

    @Test
    fun prefixedVariablesWin() {
        val c = config("HTTP_PORT" to "8080", "THRUSTER_HTTP_PORT" to "9090", "HTTPS_PORT" to "8443")
        assertEquals(9090 to 8443, c.httpPort to c.httpsPort)
    }

    @Test
    fun targetBindCanOpenTheAppListener() {
        assertEquals(InetAddress.getByAddress(ByteArray(4)), config("TARGET_BIND" to "0.0.0.0").targetBind)
        assertEquals("::", Front.ipText(config("TARGET_BIND" to "::").targetBind))
        assertEquals(loopback, config("TARGET_BIND" to "everywhere").targetBind)
        assertEquals(loopback, config("TARGET_BIND" to "localhost").targetBind)
        assertEquals(loopback, config("TARGET_BIND" to "01.2.3.4").targetBind)
    }

    @Test
    fun unparseableValuesFallBackToTheDefault() {
        val c = config("HTTP_PORT" to "eighty", "HTTP_READ_TIMEOUT" to "5s", "LOG_REQUESTS" to "yes", "H2C_ENABLED" to "1")
        assertEquals(80, c.httpPort)
        assertEquals(Duration.ofSeconds(30), c.httpReadTimeout)
        assertTrue(c.logRequests && c.h2cEnabled)
        assertEquals(80, config("HTTP_PORT" to "70000").httpPort)
        assertEquals(Duration.ZERO, config("HTTP_IDLE_TIMEOUT" to "-5").httpIdleTimeout)
    }

    @Test
    fun tlsDomainsTurnOffForwardedHeaders() {
        val c = config("TLS_DOMAIN" to " chat.example.com, ,other.example.com ")
        assertEquals(listOf("chat.example.com", "other.example.com"), c.tlsDomains)
        assertTrue(c.hasTls && !c.forwardHeaders)
        assertTrue(config("TLS_DOMAIN" to "a.example.com", "FORWARD_HEADERS" to "true").forwardHeaders)
        assertFalse(config("SSL_DOMAIN" to "a.example.com").hasTls, "Thruster 0.1.23 doesn't read SSL_DOMAIN")
        assertFalse(config("TLS_DOMAIN" to " , ").hasTls)
    }
}
