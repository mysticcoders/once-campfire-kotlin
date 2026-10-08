package campfire.kit

import campfire.rails.RailsJson
import campfire.rails.Secrets
import campfire.rails.TestClock
import campfire.routes.Recognition
import campfire.routes.Route
import campfire.routes.RouteTable
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end behavior of the request lifecycle (`once-campfire-rust/crates/kit/tests/http.rs`):
 * requests go through [RailsMiddleware] and [Adapter.dispatch] to actions, routed by a small
 * [RouteTable] whose captures are the path params, and unknown routes to [Adapter.notFound], as
 * the Rust test's axum router does.
 */
class HttpTest {
    class AppState(val name: String)

    class CurrentUser(val name: String)

    /** One reply, with the body joined. */
    class Reply(val response: Response) {
        val status get() = response.status
        val body: ByteArray = Responses.joined(response.body.chunks())

        fun header(name: String): String? = response.headers[name]

        fun cookies(): List<String> = response.headers.all("set-cookie")

        /** A `Cookie` request header carrying every cookie this response set. */
        fun cookieJar(): String = cookies().joinToString("; ") { it.substringBefore(';') }

        fun json(): Map<*, *> = RailsJson.parse(body) as Map<*, *>

        fun text(): String = String(body, Charsets.UTF_8)
    }

    private fun q(s: String?): String = if (s == null) "null" else RailsJson.generate(s)

    private val show = Action { c -> c.html("<p>${c.state<AppState>().name} room ${c.paramStr("id") ?: ""}</p>") }
    private val form = Action { c -> c.html("<form method=\"post\" action=\"/form\"></form>") }
    private val create = Action { c ->
        c.verifyAuthenticityToken()
        c.json(200, "{\"method\":${q(c.request.method)},\"original_method\":${q(c.request.originalMethod)},\"params\":${c.params.toJson()}}")
    }
    private val echo = Action { c ->
        val raw = String(c.request.rawPost, Charsets.UTF_8)
        c.json(
            200,
            "{\"method\":${q(c.request.method)},\"original_method\":${q(c.request.originalMethod)},\"params\":${c.params.toJson()}," +
                "\"raw\":${q(raw)},\"remote_ip\":${q(c.request.remoteIp())}}",
        )
    }
    private val upload = Action { c ->
        val user = c.params.require("user").asHash()!!
        val file = user["avatar"]?.asFile()
        c.json(
            200,
            "{\"method\":${q(c.request.method)},\"name\":${q(user.str("name"))},\"filename\":${q(file?.originalFilename)}," +
                "\"content\":${q(file?.let { String(it.read()) })}}",
        )
    }
    private val sessionSet = Action { c ->
        c.session()["return_to_after_authenticating"] = c.paramStr("value") ?: ""
        c.head(200)
    }
    private val sessionGet = Action { c ->
        val session = c.session()
        c.json(200, "{\"value\":${q(session.getStr("return_to_after_authenticating"))},\"id\":${q(session.id)}}")
    }
    private val sessionReset = Action { c ->
        c.resetSession()
        c.head(200)
    }
    private val noop = Action { c -> c.head(204) }
    private val notice = Action { c -> c.redirectTo("/flash", Redirect(notice = "✓")) }
    private val showFlash = Action { c -> c.json(200, "{\"notice\":${q(c.flash().notice)}}") }
    private val signIn = Action { c ->
        c.cookies.setSigned("session_token", Cookie("tok123").permanent().httponly())
        c.cookies.set("last_room", Cookie("7").permanent())
        c.head(200)
    }
    private val liveSignIn = Action { c ->
        c.useLiveResponse()
        signIn.call(c)
    }
    private val whoami = Action { c -> c.json(200, "{\"token\":${q(c.cookies.signed("session_token"))},\"last_room\":${q(c.cookies["last_room"])}}") }
    private val signOut = Action { c ->
        c.cookies.delete("session_token")
        c.head(200)
    }

    private fun requireUser(c: Ctx) {
        val user = c.request.header("x-user") ?: halt(c.redirectTo("/session/new"))
        c.setCurrent(CurrentUser(user))
    }

    private fun ensureAdmin(c: Ctx) {
        if (c.current<CurrentUser>()?.name != "admin") halt(c.head(403))
    }

    private val admin = Action { c ->
        c.setHeader("x-version", "42")
        requireUser(c)
        ensureAdmin(c)
        c.html("hi ${c.current<CurrentUser>()!!.name}")
    }
    private val messages = Action { c ->
        when (c.respondTo(Mime.HTML, Mime.TURBO_STREAM, Mime.JSON)) {
            Mime.TURBO_STREAM -> c.turboStream("<turbo-stream action=\"append\"></turbo-stream>")
            Mime.JSON -> c.json(200, "[]")
            else -> c.render(200, Mime.HTML, "<p>messages</p>")
        }
    }
    private val autocomplete = Action { c -> c.render(200, c.respondTo(Mime.HTML, Mime.JSON), "x") }
    private val redirects = Action { c ->
        when (c.paramStr("to") ?: "") {
            "relative" -> c.redirectTo("rooms/1")
            "other" -> c.redirectTo("https://evil.example/")
            "other_allowed" -> c.redirectTo("https://docs.example/", Redirect(allowOtherHost = true))
            "see_other" -> c.redirectTo("/rooms", Redirect(status = 303))
            "back" -> c.redirectBackOrTo("/fallback")
            else -> c.redirectTo("/rooms/1?x=1")
        }
    }
    private val created = Action { c -> c.headWithLocation(201, "/rooms/1/messages/2") }
    private val file = Action { c -> c.sendFile(java.nio.file.Path.of(c.paramStr("path")!!), SendOptions.inline("image/png")) }
    private val logo = Action { c ->
        if (c.stale(Freshness.etag("accounts/1-20240601"))) {
            c.expiresIn(300, ExpiresIn(public = true, staleWhileRevalidate = 604800))
            c.sendData("PNG".toByteArray(), SendOptions.inline("image/png"))
        } else {
            c.head(304)
        }
    }
    private val indexFresh = Action { c -> c.freshWhen(Freshness.etag("messages/1-2")) ?: c.html("<p>page</p>") }

    private val routes: List<Pair<Route, Action>> = listOf(
        route("GET", "/rooms/:id", show),
        route("GET", "/form", form),
        route("POST", "/form", create),
        route("GET", "/echo/:id", echo),
        route("POST", "/echo/:id", echo),
        route("PATCH", "/echo/:id", echo),
        route("DELETE", "/echo/:id", echo),
        route("PATCH", "/upload", upload),
        route("POST", "/upload", upload),
        route("GET", "/session", sessionGet),
        route("POST", "/session", sessionSet),
        route("DELETE", "/session", sessionReset),
        route("GET", "/noop", noop),
        route("GET", "/notice", notice),
        route("GET", "/flash", showFlash),
        route("GET", "/sign_in", signIn),
        route("GET", "/live_sign_in", liveSignIn),
        route("GET", "/whoami", whoami),
        route("GET", "/sign_out", signOut),
        route("GET", "/admin", admin),
        route("GET", "/messages", messages),
        route("POST", "/messages", messages),
        route("GET", "/messages.:format", messages),
        route("GET", "/autocomplete", autocomplete),
        route("GET", "/redirect", redirects),
        route("GET", "/created", created),
        route("GET", "/file", file),
        route("GET", "/logo", logo),
        route("GET", "/fresh", indexFresh),
    )

    private fun route(verb: String, pattern: String, action: Action) = Route(verb, pattern, "$verb $pattern") to action

    private val secrets = SECRETS

    private fun kitWith(config: KitConfig): Kit =
        Kit(config, secrets, TestClock.frozenAt(Instant.parse("2024-06-01T12:00:00Z")), AppState("Campfire"), log = {})

    /** The test router: a table match runs its action with the captures as path params; anything else is the 404. */
    private fun appWith(config: KitConfig): Handler {
        val kit = kitWith(config)
        val table = RouteTable(routes.map { it.first })
        val actions = routes.associate { it.first.endpoint to it.second }
        val router = Handler { request ->
            when (val recognized = table.recognize(request.method, RequestInfo(request).path)) {
                is Recognition.Matched -> {
                    val captures = PathParams {
                        val params = ParamMap()
                        for ((k, v) in recognized.params) if (k != "controller" && k != "action") params[k] = Param.Str(v)
                        params
                    }
                    Adapter.dispatch(kit, request, actions.getValue(recognized.endpoint), captures)
                }
                else -> Adapter.notFound(kit, request)
            }
        }
        return Adapter.app(kit, router)
    }

    private fun app(): Handler =
        appWith(KitConfig(errorPages = ErrorPages(mapOf(404 to "<h1>Not found</h1>".toByteArray(), 422 to "<h1>Unprocessable</h1>".toByteArray()))))

    private fun send(app: Handler, request: Request): Reply = Reply(app.handle(request))

    private fun request(method: String, target: String, vararg headers: Pair<String, String>, body: String = "", host: Boolean = true): Request {
        val h = Headers()
        if (host) h.add("host", "chat.example.com")
        for ((k, v) in headers) h.add(k, v)
        return Request(method, target, h, body.toByteArray())
    }

    private fun get(target: String, vararg headers: Pair<String, String>) = request("GET", target, *headers)

    private fun formPost(target: String, body: String, vararg headers: Pair<String, String>) =
        request("POST", target, "content-type" to "application/x-www-form-urlencoded", *headers, body = body)

    private fun json(text: String): Any? = RailsJson.parse(text.toByteArray())

    @Test
    fun rendersHtmlWithRailsHeaders() {
        val reply = send(app(), get("/rooms/5"))
        assertEquals(200, reply.status)
        assertEquals("<p>Campfire room 5</p>", reply.text())
        assertEquals("text/html; charset=utf-8", reply.header("content-type"))
        assertEquals("SAMEORIGIN", reply.header("x-frame-options"))
        assertEquals("nosniff", reply.header("x-content-type-options"))
        assertEquals("strict-origin-when-cross-origin", reply.header("referrer-policy"))
        assertEquals("max-age=0, private, must-revalidate", reply.header("cache-control"))
        assertTrue(reply.header("etag")!!.startsWith("W/\""))
        assertEquals(36, reply.header("x-request-id")?.length)
        assertTrue(reply.cookies().isEmpty(), "no session touched, no cookie")
    }

    @Test
    fun headersGoOutInRustsOrder() {
        val reply = send(app(), get("/rooms/5"))
        assertEquals(
            listOf(
                "content-type", "x-frame-options", "x-xss-protection", "x-content-type-options", "x-permitted-cross-domain-policies",
                "referrer-policy", "etag", "cache-control", "content-length", "x-request-id", "x-runtime",
            ),
            reply.response.headers.map { it.first },
        )
        assertTrue(Regex("""\d+\.\d{6}""").matches(reply.header("x-runtime")!!))
    }

    @Test
    fun conditionalGetOnBodyEtag() {
        val app = app()
        val etag = send(app, get("/rooms/5")).header("etag")!!
        val second = send(app, get("/rooms/5", "if-none-match" to etag))
        assertEquals(304, second.status)
        assertTrue(second.body.isEmpty())
        assertNull(second.header("content-type"))

        for (ifNoneMatch in listOf("\"other\", $etag", "*")) {
            assertEquals(304, send(app, get("/rooms/5", "if-none-match" to ifNoneMatch)).status, ifNoneMatch)
        }
        assertEquals(200, send(app, get("/rooms/5", "if-none-match" to "\"other\"")).status)
    }

    @Test
    fun headRequestsDropTheBody() {
        val reply = send(app(), request("HEAD", "/rooms/5"))
        assertEquals(200, reply.status)
        assertTrue(reply.body.isEmpty())
        assertEquals("22", reply.header("content-length"))
    }

    @Test
    fun unknownRoutesAndMethodsAreRails404s() {
        val app = app()
        val reply = send(app, get("/nope"))
        assertEquals(404, reply.status)
        assertEquals("<h1>Not found</h1>", reply.text())
        assertEquals(404, send(app, request("PUT", "/rooms/1", host = false)).status)
        assertEquals("""{"status":404,"error":"Not Found"}""", send(app, get("/nope.json")).text())
    }

    @Test
    fun paramsMergeQueryOverBodyAndPathOverBoth() {
        val reply = send(app(), formPost("/echo/9?b=query&id=q", "a[b][]=1&a[b][]=2&b=body&id=body"))
        val json = reply.json()
        assertEquals(json("""{"a":{"b":["1","2"]},"b":"query","id":"9"}"""), json["params"])
        assertEquals("a[b][]=1&a[b][]=2&b=body&id=body", json["raw"])
    }

    @Test
    fun malformedParamsAre400() {
        assertEquals(400, send(app(), get("/echo/1?a=1&a[b]=2")).status)
        assertEquals(400, send(app(), formPost("/echo/1", "a=%")).status)
        assertEquals(400, send(app(), request("POST", "/echo/1", "content-type" to "application/json", body = "{nope")).status)
    }

    @Test
    fun methodOverrideFromFormParamAndHeader() {
        val app = app()
        val reply = send(app, formPost("/echo/1", "_method=patch&x=1"))
        assertEquals("PATCH", reply.json()["method"])
        assertEquals("POST", reply.json()["original_method"])

        assertEquals("DELETE", send(app, formPost("/echo/1", "_method=delete")).json()["method"])

        assertEquals("PATCH", send(app, request("POST", "/echo/1", "x-http-method-override" to "patch")).json()["method"])

        val jsonBody = send(app, request("POST", "/echo/1", "content-type" to "application/json", body = """{"_method":"patch","a":[1,null,2]}"""))
        assertEquals("POST", jsonBody.json()["method"])
        assertEquals(listOf(1L, 2L), (jsonBody.json()["params"] as Map<*, *>)["a"])

        assertEquals("POST", send(app, formPost("/echo/1", "_method=bogus")).json()["method"])
    }

    @Test
    fun multipartUploadsWithMethodOverride() {
        val b = "----campfire"
        val body = "--$b\r\nContent-Disposition: form-data; name=\"_method\"\r\n\r\npatch\r\n" +
            "--$b\r\nContent-Disposition: form-data; name=\"user[name]\"\r\n\r\nJo\r\n" +
            "--$b\r\nContent-Disposition: form-data; name=\"user[avatar]\"; filename=\"me.png\"\r\nContent-Type: image/png\r\n\r\nPNGDATA\r\n" +
            "--$b--\r\n"
        val reply = send(app(), request("POST", "/upload", "content-type" to "multipart/form-data; boundary=$b", body = body))
        assertEquals(json("""{"method":"PATCH","name":"Jo","filename":"me.png","content":"PNGDATA"}"""), reply.json())
    }

    @Test
    fun missingRequiredParamIs400() {
        assertEquals(400, send(app(), formPost("/upload", "other=1")).status)
    }

    /** The test app as Campfire runs it in production: behind TLS (`assume_ssl`) with `force_ssl`. */
    private fun sslApp(): Handler = appWith(
        KitConfig(
            errorPages = ErrorPages(mapOf(422 to "<h1>Unprocessable</h1>".toByteArray())),
            forceSsl = true,
            proxy = ProxyConfig(assumeSsl = true),
        ),
    )

    private fun postFrom(site: String?): Request {
        val headers = if (site == null) arrayOf() else arrayOf("sec-fetch-site" to site)
        return formPost("/form", "x=1", *headers)
    }

    @Test
    fun forgeryProtectionTrustsSameSiteRequestsBySecFetchSite() {
        val app = sslApp()
        for (site in listOf("same-origin", "same-site")) {
            val ok = send(app, postFrom(site))
            assertEquals(200, ok.status, site)
            assertEquals("1", (ok.json()["params"] as Map<*, *>)["x"])
        }
        for (site in listOf("cross-site", "none", "bogus", null)) {
            val forged = send(app, postFrom(site))
            assertEquals(422, forged.status, "$site")
            assertEquals("<h1>Unprocessable</h1>", forged.text())
            assertTrue(forged.cookies().isEmpty(), "errors don't commit cookies")
        }
    }

    @Test
    fun forgeryProtectionAllowsAMissingHeaderOnlyWithoutSsl() {
        val app = app()
        assertEquals(200, send(app, postFrom(null)).status)
        assertEquals(422, send(app, postFrom("cross-site")).status)
    }

    @Test
    fun pagesCarryNoForgeryTokenOrSession() {
        val page = send(app(), get("/form"))
        assertTrue("authenticity_token" !in page.text())
        assertTrue(page.cookies().isEmpty(), "rendering a form doesn't start a session")
    }

    @Test
    fun forgeryProtectionChecksTheOrigin() {
        val app = sslApp()
        fun withOrigin(origin: String) = request("POST", "/form", "sec-fetch-site" to "same-origin", "origin" to origin)
        assertEquals(200, send(app, withOrigin("https://chat.example.com")).status)
        assertEquals(422, send(app, withOrigin("https://evil.example")).status)
        assertEquals(422, send(app, withOrigin("null")).status)
    }

    @Test
    fun sessionCookieIsWrittenOnlyWhenTheSessionChanges() {
        val app = app()
        assertTrue(send(app, get("/noop")).cookies().isEmpty())

        val set = send(app, formPost("/session", "value=%2Frooms%2F1"))
        assertEquals(200, set.status)
        val cookie = set.cookieJar()
        assertTrue(cookie.startsWith("_campfire_session="))

        val read = send(app, get("/session", "cookie" to cookie))
        assertEquals("/rooms/1", read.json()["value"])
        assertTrue(read.cookies().isEmpty(), "${read.cookies()}")
        val id = read.json()["id"] as String
        assertEquals(32, id.length)
        assertTrue(send(app, get("/noop", "cookie" to cookie)).cookies().isEmpty())
        val same = send(app, formPost("/session", "value=%2Frooms%2F1", "cookie" to cookie))
        assertTrue(same.cookies().isEmpty(), "writing the value it already holds changes nothing")
        assertEquals(id, send(app, get("/session", "cookie" to cookie)).json()["id"])

        val reset = send(app, request("DELETE", "/session", "cookie" to cookie))
        assertTrue(reset.cookies().any { it.startsWith("_campfire_session=;") }, "${reset.cookies()}")
        val after = send(app, get("/session", "cookie" to reset.cookieJar()))
        assertNull(after.json()["value"])
        assertNotEquals(id, after.json()["id"])

        val bogus = send(app, get("/session", "cookie" to "_campfire_session=garbage"))
        assertNull(bogus.json()["value"])
    }

    @Test
    fun flashSurvivesExactlyOneRedirect() {
        val app = app()
        val redirect = send(app, get("/notice"))
        assertEquals(302, redirect.status)
        assertEquals("http://chat.example.com/flash", redirect.header("location"))
        val cookie = redirect.cookieJar()

        val shown = send(app, get("/flash", "cookie" to cookie))
        assertEquals("✓", shown.json()["notice"])
        assertTrue(shown.cookies().any { it.startsWith("_campfire_session=;") }, "${shown.cookies()}")
        assertNull(send(app, get("/flash", "cookie" to shown.cookieJar())).json()["notice"])
    }

    @Test
    fun signedPermanentCookiesAndDeletion() {
        val app = app()
        val signedIn = send(app, get("/sign_in"))
        val cookies = signedIn.cookies()
        assertTrue(
            cookies.any { it.startsWith("session_token=") && it.endsWith("; path=/; expires=Wed, 01 Jun 2044 12:00:00 GMT; httponly; samesite=lax") },
            "$cookies",
        )
        assertTrue("last_room=7; path=/; expires=Wed, 01 Jun 2044 12:00:00 GMT; samesite=lax" in cookies)

        assertEquals(2, send(app, get("/live_sign_in")).cookies().size, "Rails' Live responses send the action's cookies twice; this sends them once")

        val jar = signedIn.cookieJar()
        assertEquals(json("""{"token":"tok123","last_room":"7"}"""), send(app, get("/whoami", "cookie" to jar)).json())
        assertNull(send(app, get("/whoami", "cookie" to "session_token=tok123")).json()["token"])

        val out = send(app, get("/sign_out", "cookie" to jar))
        assertEquals(listOf("session_token=; path=/; max-age=0; expires=Thu, 01 Jan 1970 00:00:00 GMT; samesite=lax"), out.cookies())
        assertTrue(send(app, get("/sign_out")).cookies().isEmpty())
    }

    @Test
    fun liveResponsesSkipDefaultHeadersAndEtags() {
        val live = send(app(), get("/live_sign_in"))
        assertNull(live.header("x-frame-options"))
        assertEquals("no-cache", live.header("cache-control"))
    }

    @Test
    fun beforeActionsHaltTheChain() {
        val app = app()
        val anonymous = send(app, get("/admin"))
        assertEquals(302, anonymous.status)
        assertEquals("http://chat.example.com/session/new", anonymous.header("location"))
        assertEquals("42", anonymous.header("x-version"), "headers set before the halt survive")

        val member = send(app, get("/admin", "x-user" to "jo"))
        assertEquals(403, member.status)
        assertEquals("text/html", member.header("content-type"))
        assertTrue(member.body.isEmpty())

        assertEquals("hi admin", send(app, get("/admin", "x-user" to "admin")).text())
    }

    @Test
    fun respondToNegotiatesLikeRails() {
        val app = app()
        val browser = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        assertEquals("text/html; charset=utf-8", send(app, get("/messages", "accept" to browser)).header("content-type"))

        val turbo = "text/vnd.turbo-stream.html, text/html, application/xhtml+xml"
        val stream = send(app, request("POST", "/messages", "accept" to turbo))
        assertEquals("text/vnd.turbo-stream.html; charset=utf-8", stream.header("content-type"))
        assertEquals("Accept", stream.header("vary"))

        assertEquals("application/json; charset=utf-8", send(app, get("/messages.json")).header("content-type"))
        assertEquals("application/json; charset=utf-8", send(app, get("/messages?format=json")).header("content-type"))

        assertEquals(406, send(app, get("/messages", "accept" to "image/png")).status)
        assertEquals(406, send(app, get("/messages", "accept" to "garbage")).status)

        assertEquals("text/html; charset=utf-8", send(app, get("/autocomplete", "accept" to "*/*")).header("content-type"))
    }

    @Test
    fun redirectsLikeRails() {
        val app = app()
        val plain = send(app, get("/redirect"))
        assertEquals(302, plain.status)
        assertEquals("http://chat.example.com/rooms/1?x=1", plain.header("location"))
        assertEquals("text/html; charset=utf-8", plain.header("content-type"))
        assertEquals("no-cache", plain.header("cache-control"))

        assertEquals(303, send(app, get("/redirect?to=see_other")).status)

        assertEquals(500, send(app, get("/redirect?to=relative")).status)
        assertEquals(500, send(app, get("/redirect?to=other")).status)
        assertEquals("https://docs.example/", send(app, get("/redirect?to=other_allowed")).header("location"))

        val back = send(app, get("/redirect?to=back", "referer" to "http://chat.example.com/rooms/3"))
        assertEquals("http://chat.example.com/rooms/3", back.header("location"))
        val foreign = send(app, get("/redirect?to=back", "referer" to "https://evil.example/x"))
        assertEquals("http://chat.example.com/fallback", foreign.header("location"))
    }

    @Test
    fun headWithLocation() {
        val reply = send(app(), get("/created"))
        assertEquals(201, reply.status)
        assertEquals("http://chat.example.com/rooms/1/messages/2", reply.header("location"))
        assertEquals("text/html", reply.header("content-type"))
    }

    @Test
    fun sendFileWithDispositionAndTheWholeFile() {
        val dir = Files.createTempDirectory("kit")
        val path = dir.resolve("logo.png")
        Files.write(path, "0123456789".toByteArray())
        val encoded = CookieJar.escape(path.toString())
        val app = app()

        val whole = send(app, get("/file?path=$encoded"))
        assertEquals("0123456789", whole.text())
        assertEquals("image/png", whole.header("content-type"))
        assertEquals("inline; filename=\"logo.png\"; filename*=UTF-8''logo.png", whole.header("content-disposition"))
        assertEquals("binary", whole.header("content-transfer-encoding"))
        assertEquals("10", whole.header("content-length"))

        val ignored = send(app, get("/file?path=$encoded", "range" to "bytes=2-4"))
        assertEquals(200, ignored.status, "Rails' send_file ignores Range")
        assertEquals("0123456789", ignored.text())

        assertEquals(500, send(app, get("/file?path=%2Fnope")).status)
    }

    @Test
    fun staleAndExpiresIn() {
        val app = app()
        val first = send(app, get("/logo"))
        assertEquals(200, first.status)
        assertEquals("max-age=300, public, stale-while-revalidate=604800", first.header("cache-control"))
        val etag = first.header("etag")!!
        assertTrue(etag.startsWith("W/\""))
        assertEquals(304, send(app, get("/logo", "if-none-match" to etag)).status)

        val fresh = send(app, get("/fresh"))
        assertEquals("max-age=0, private, must-revalidate", fresh.header("cache-control"))
        val freshEtag = fresh.header("etag")!!
        assertEquals(304, send(app, get("/fresh", "if-none-match" to freshEtag)).status)
        assertEquals(304, send(app, get("/fresh", "if-none-match" to "\"other\", $freshEtag")).status)

        assertNotEquals(freshEtag, send(app, get("/fresh", "turbo-frame" to "x")).header("etag"), "turbo-rails' frame etagger")
    }

    @Test
    fun remoteIpFromPeerAndProxies() {
        val app = app()
        fun from(peer: String?, vararg headers: Pair<String, String>): Request {
            val h = Headers().add("host", "chat.example.com")
            for ((k, v) in headers) h.add(k, v)
            return Request("GET", "/echo/1", h, Request.EMPTY, peer?.let { InetSocketAddress(it, 5000) })
        }
        assertEquals("203.0.113.9", send(app, from("127.0.0.1", "x-forwarded-for" to "203.0.113.9, 10.0.0.1")).json()["remote_ip"])
        assertEquals("198.51.100.4", send(app, from("198.51.100.4")).json()["remote_ip"])
        assertEquals(500, send(app, from(null, "client-ip" to "1.1.1.1", "x-forwarded-for" to "2.2.2.2")).status)
    }

    @Test
    fun forceSslRedirectsAndHardens() {
        val app = appWith(KitConfig(forceSsl = true))
        val plain = send(app, get("/rooms/1?x=1"))
        assertEquals(301, plain.status)
        assertEquals("https://chat.example.com/rooms/1?x=1", plain.header("location"))
        assertEquals(308, send(app, formPost("/echo/1", "")).status)

        val production = appWith(KitConfig.production(false))
        val secure = send(production, get("/sign_in"))
        assertEquals(200, secure.status)
        assertEquals("max-age=63072000; includeSubDomains", secure.header("strict-transport-security"))
        assertTrue(secure.cookies().isNotEmpty() && secure.cookies().all { it.endsWith("; secure") })

        assertEquals("https://chat.example.com/rooms/1?x=1", send(production, get("/redirect")).header("location"))
    }

    @Test
    fun bodyLimitIs413() {
        val app = appWith(KitConfig(maxBodyBytes = 16))
        assertEquals(413, send(app, formPost("/echo/1", "a=1&".repeat(20))).status)
        assertEquals(413, send(app, request("PATCH", "/echo/1", body = "x".repeat(40), host = false)).status)
    }

    @Test
    fun requestIdsAreSanitizedPassthroughs() {
        assertEquals("abc-123script", send(app(), get("/rooms/1", "x-request-id" to "abc-123<script>")).header("x-request-id"))
    }

    @Test
    fun servesWithPeerAddresses() {
        val server = HttpServer(ServerOptions(port = 0, bind = "127.0.0.1", workers = 2), app()).start()
        try {
            Socket("127.0.0.1", server.port).use { socket ->
                socket.getOutputStream().write("GET /echo/1 HTTP/1.1\r\nHost: chat.example.com\r\nConnection: close\r\n\r\n".toByteArray())
                val response = String(socket.getInputStream().readAllBytes())
                assertTrue(response.startsWith("HTTP/1.1 200 OK"), response)
                assertTrue(response.contains("\"remote_ip\":\"127.0.0.1\""), response)
            }
        } finally {
            server.stop()
        }
    }

    companion object {
        val SECRETS = Secrets("test-secret")
    }
}
