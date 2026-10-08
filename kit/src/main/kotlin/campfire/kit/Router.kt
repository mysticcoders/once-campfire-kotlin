package campfire.kit

import campfire.routes.Recognition
import campfire.routes.Route
import campfire.routes.RouteTable
import campfire.routes.normalizePath

/**
 * The route that matched the current request, available to actions as `c.current<MatchedRoute>()`
 * (`MatchedRoute` in `once-campfire-rust/crates/campfire/src/controllers.rs`).
 */
class MatchedRoute(val endpoint: String, val route: Route)

/**
 * The Rails route table as one action (`dispatch` in `controllers.rs`): it recognizes the
 * normalized path against [table] (the first route in table order whose verb and pattern match;
 * HEAD matches GET routes), installs the route's path parameters (defaults, captures, then
 * `controller` and `action`) over the query and body params, records the [MatchedRoute], and runs
 * the action bound to the route's endpoint (`"rooms#show"`).
 *
 * A path no route takes is a 404 ([KitError.NotFound], `ActionController::RoutingError`), whatever
 * the verb: there is no 405. A path parameter that isn't UTF-8 once percent-decoded is a 400. A
 * declared route whose endpoint has no action is [ACTION_NOT_FOUND] (a 404, like
 * `AbstractController::ActionNotFound`), unless [DEFAULT_ACTIONS] has it: the framework routes
 * the Rust table binds (`/up`, Turbo Native navigation, Action Mailbox) and
 * `rooms/settings#show`, whose controller the reference doesn't define (a 500).
 *
 * Actions are looked up per row once, when the router is built.
 */
class Router(
    actions: Map<String, Action>,
    val table: RouteTable = RouteTable.campfire,
    fallback: Action = ACTION_NOT_FOUND,
) : Action {
    private val byRow: Array<Action> = Array(table.routes.size) { i ->
        val endpoint = table.routes[i].endpoint
        actions[endpoint] ?: DEFAULT_ACTIONS[endpoint] ?: fallback
    }

    override fun call(c: Ctx): Response {
        return when (val recognized = table.recognize(c.request.method, normalizePath(c.request.path))) {
            is Recognition.Matched -> {
                val params = ParamMap()
                for ((k, v) in recognized.params) params[k] = Param.Str(v)
                c.installPathParams(params)
                c.setCurrent(MatchedRoute(recognized.endpoint, recognized.route))
                byRow[recognized.index].call(c)
            }
            is Recognition.BadRequest -> throw KitError.BadRequest(recognized.message)
            Recognition.NotFound -> throw KitError.NotFound()
        }
    }

    /**
     * This router as the app's handler: every request through [Adapter.dispatch], with the
     * catch-all route's path parameter as Rust's `"/{*path}"` route gives it ([CATCH_ALL]).
     */
    fun handler(kit: Kit): Handler = Handler { request -> Adapter.dispatch(kit, request, this, CATCH_ALL) }

    companion object {
        /** A declared route whose action the controller doesn't define (`AbstractController::ActionNotFound`). */
        val ACTION_NOT_FOUND = Action { throw KitError.NotFound() }

        /**
         * A declared route whose controller doesn't exist, e.g. `resource :settings` under rooms:
         * the reference answers 500 (the controller constant fails to load).
         */
        val MISSING_CONTROLLER = Action { c ->
            throw KitError.internal("uninitialized constant for ${c.current<MatchedRoute>()?.endpoint ?: "?"}")
        }

        /** `Rails::HealthController#show`. */
        val HEALTH = Action { c ->
            when (c.respondTo(Mime.HTML, Mime.JSON)) {
                Mime.JSON -> c.json(200, "{\"status\":\"up\",\"timestamp\":\"${java.time.Instant.ofEpochSecond(c.now().epochSecond)}\"}")
                else -> c.html("<!DOCTYPE html><html><body style=\"background-color: green\"></body></html>")
            }
        }

        /** `ActionMailbox::BaseController#ensure_configured`: no ingress is configured. */
        val MAILBOX_INGRESS = Action { c -> c.head(404) }

        /** `Rails::Conductor::BaseController`: CSRF first, then `ensure_development_env`. */
        val MAILBOX_CONDUCTOR = Action { c ->
            c.verifyAuthenticityToken()
            c.head(403)
        }

        /** The framework rows of the Rust table (`controllers.rs`), by endpoint. */
        val DEFAULT_ACTIONS: Map<String, Action> = buildMap {
            put("rooms/settings#show", MISSING_CONTROLLER)
            put("rails/health#show", HEALTH)
            put("turbo/native/navigation#recede", Action { c -> c.html("Going back…") })
            put("turbo/native/navigation#resume", Action { c -> c.html("Staying put…") })
            put("turbo/native/navigation#refresh", Action { c -> c.html("Refreshing…") })
            for (ingress in listOf("postmark", "relay", "sendgrid", "mandrill")) {
                put("action_mailbox/ingresses/$ingress/inbound_emails#create", MAILBOX_INGRESS)
            }
            put("action_mailbox/ingresses/mandrill/inbound_emails#health_check", MAILBOX_INGRESS)
            put("action_mailbox/ingresses/mailgun/inbound_emails#create", MAILBOX_INGRESS)
            for (action in listOf("index", "create", "new", "show")) put("rails/conductor/action_mailbox/inbound_emails#$action", MAILBOX_CONDUCTOR)
            put("rails/conductor/action_mailbox/inbound_emails/sources#new", MAILBOX_CONDUCTOR)
            put("rails/conductor/action_mailbox/inbound_emails/sources#create", MAILBOX_CONDUCTOR)
            put("rails/conductor/action_mailbox/reroutes#create", MAILBOX_CONDUCTOR)
            put("rails/conductor/action_mailbox/incinerates#create", MAILBOX_CONDUCTOR)
        }

        /**
         * The path parameter axum's `"/{*path}"` route captures, which the Rust app dispatches
         * through: the path after its first `/`, percent-decoded, or none for `/`. One that isn't
         * UTF-8 once decoded fails before anything else (axum's `InvalidUtf8InPathParam`): a 400.
         * A path without `%` costs one substring.
         */
        val CATCH_ALL = PathParams { request ->
            val path = request.path
            val params = ParamMap()
            if (path.length > 1) params["path"] = Param.Str(percentDecode(path.substring(1)))
            params
        }

        /** `percent_decode_str(..).decode_utf8()`: `%XX` decoded, a `%` without two hex digits kept. */
        private fun percentDecode(s: String): String {
            if (s.indexOf('%') < 0) return s
            val bytes = s.toByteArray(Charsets.UTF_8)
            val out = java.io.ByteArrayOutputStream(bytes.size)
            var i = 0
            while (i < bytes.size) {
                val b = bytes[i].toInt() and 0xff
                val high = if (b == '%'.code && i + 2 < bytes.size) hex(bytes[i + 1]) else -1
                val low = if (high >= 0) hex(bytes[i + 2]) else -1
                if (low >= 0) {
                    out.write(high * 16 + low)
                    i += 3
                } else {
                    out.write(b)
                    i++
                }
            }
            val decoded = out.toByteArray()
            if (!isValidUtf8(decoded)) throw KitError.BadRequest("Invalid path parameters")
            return String(decoded, Charsets.UTF_8)
        }

        private fun hex(b: Byte): Int = when (val c = b.toInt().toChar()) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> -1
        }
    }
}
