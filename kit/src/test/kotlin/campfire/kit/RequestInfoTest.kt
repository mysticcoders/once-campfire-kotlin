package campfire.kit

import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The tests of `once-campfire-rust/crates/kit/src/request.rs`. */
class RequestInfoTest {
    private fun request(headers: List<Pair<String, String>>, peer: String, proxy: ProxyConfig = ProxyConfig()): RequestInfo {
        val map = Headers()
        for ((k, v) in headers) map.add(k, v)
        val remote = InetSocketAddress(InetAddress.getByName(peer), 0)
        return RequestInfo(Request("GET", "/rooms/1?x=1", map, remote = remote), proxy)
    }

    @Test
    fun remoteIpSkipsTrustedProxies() {
        assertEquals("203.0.113.9", request(listOf("x-forwarded-for" to "203.0.113.9, 10.0.0.2"), "127.0.0.1").remoteIp())
        assertEquals("198.51.100.7", request(emptyList(), "198.51.100.7").remoteIp())
        assertEquals("203.0.113.9", request(listOf("x-forwarded-for" to "203.0.113.9"), "198.51.100.7").remoteIp())
        assertEquals("10.0.0.3", request(listOf("x-forwarded-for" to "10.0.0.3"), "127.0.0.1").remoteIp())
        assertEquals("2001:db8::1", request(listOf("forwarded" to "for=\"[2001:db8::1]:4711\""), "::1").remoteIp())
    }

    @Test
    fun remoteIpSpoofingCheck() {
        val spoofed = request(listOf("client-ip" to "1.1.1.1", "x-forwarded-for" to "2.2.2.2"), "127.0.0.1")
        assertFailsWith<KitError.IpSpoofAttack> { spoofed.remoteIp() }
        assertEquals("2.2.2.2", request(listOf("client-ip" to "2.2.2.2", "x-forwarded-for" to "2.2.2.2"), "127.0.0.1").remoteIp())
    }

    @Test
    fun hostAndProtocol() {
        var r = request(listOf("host" to "chat.example.com:3000"), "127.0.0.1")
        assertEquals("chat.example.com", r.host())
        assertEquals(3000, r.port())
        assertEquals("http://chat.example.com:3000", r.baseUrl())
        assertEquals("http://chat.example.com:3000/rooms/1?x=1", r.url())

        r = request(
            listOf("host" to "internal:80", "x-forwarded-host" to "a.example, chat.example.com", "x-forwarded-proto" to "https"),
            "127.0.0.1",
        )
        assertEquals("chat.example.com", r.host())
        assertTrue(r.isSsl)
        assertEquals("https://chat.example.com", r.baseUrl())

        r = request(listOf("host" to "chat.example.com"), "127.0.0.1", ProxyConfig(assumeSsl = true))
        assertEquals("https://chat.example.com", r.baseUrl())
    }

    @Test
    fun ipNets() {
        var net = IpNet.parse("172.16.0.0/12")
        assertTrue(net.contains(IpAddr.parse("172.31.255.1")!!))
        assertTrue(!net.contains(IpAddr.parse("172.32.0.1")!!))
        net = IpNet.parse("fc00::/7")
        assertTrue(net.contains(IpAddr.parse("fd12::1")!!))
    }

    @Test
    fun ipAddressesParseAndPrintLikeRust() {
        for ((text, printed) in listOf(
            "1.2.3.4" to "1.2.3.4",
            "::" to "::",
            "::1" to "::1",
            "2001:DB8:0:0:1:0:0:1" to "2001:db8::1:0:0:1",
            "1:0:0:2:0:0:0:3" to "1:0:0:2::3",
            "::ffff:1.2.3.4" to "::ffff:1.2.3.4",
            "64:ff9b::1.2.3.4" to "64:ff9b::102:304",
            "1:2:3:4:5:6:7:8" to "1:2:3:4:5:6:7:8",
        )) {
            assertEquals(printed, IpAddr.parse(text)?.toString(), text)
        }
        for (bad in listOf("01.2.3.4", "1.2.3", "256.1.1.1", "1:2:3:4:5:6:7:8:9", "1::2::3", ":::", "12345::", "1.2.3.4:80", "", "x")) {
            assertNull(IpAddr.parse(bad), bad)
        }
    }

    @Test
    fun urlPieces() {
        val r = RequestInfo(Request("POST", "/a?", Headers().add("X-Requested-With", "XMLHttpRequest").add("content-type", "Text/HTML; charset=x")))
        assertEquals("/a", r.fullpath())
        assertEquals("http://localhost/a", r.url())
        assertTrue(r.isXhr)
        assertEquals("text/html", r.mediaType)
        assertEquals("", r.remoteIp())
    }
}
