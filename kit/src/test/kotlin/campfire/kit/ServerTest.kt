package campfire.kit

import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals

/** [HttpServer] over a raw socket: pipelined requests on one connection, as hyper serves them. */
class ServerTest {
    private val started = CopyOnWriteArrayList<String>()

    /** `/slow` answers after 300 ms, anything else at once; each body is its path. */
    private val handler = Handler { request ->
        started.add(request.path)
        if (request.path == "/slow") Thread.sleep(300)
        Response.text(200, request.path)
    }

    private fun exchange(raw: String): String {
        val server = HttpServer(ServerOptions(port = 0, bind = "127.0.0.1", workers = 4), handler).start()
        try {
            Socket("127.0.0.1", server.port).use { socket ->
                socket.soTimeout = 5000
                socket.getOutputStream().write(raw.toByteArray())
                return String(socket.getInputStream().readAllBytes())
            }
        } finally {
            server.stop()
        }
    }

    private fun bodies(response: String): List<String> =
        Regex("""HTTP/1\.1 200 OK\r\n.*?\r\n\r\n(/[a-z]+)""", RegexOption.DOT_MATCHES_ALL).findAll(response).map { it.groupValues[1] }.toList()

    @Test
    fun `pipelined requests are answered in order, one at a time`() {
        val response = exchange(
            "GET /slow HTTP/1.1\r\nHost: a\r\n\r\n" +
                "GET /fast HTTP/1.1\r\nHost: a\r\n\r\n" +
                "GET /last HTTP/1.1\r\nHost: a\r\nConnection: close\r\n\r\n",
        )
        assertEquals(listOf("/slow", "/fast", "/last"), bodies(response))
        assertEquals(listOf("/slow", "/fast", "/last"), started.toList())
    }

    @Test
    fun `requests pipelined after a closing response are dropped`() {
        val response = exchange(
            "GET /slow HTTP/1.1\r\nHost: a\r\nConnection: close\r\n\r\n" +
                "GET /fast HTTP/1.1\r\nHost: a\r\n\r\n",
        )
        assertEquals(listOf("/slow"), bodies(response))
        assertEquals(listOf("/slow"), started.toList())
    }
}
