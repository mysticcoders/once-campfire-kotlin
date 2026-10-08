package campfire.routes

/**
 * One row of the route table: the verb, the Rails path spec (e.g.
 * `/rooms/:room_id/messages(.:format)`), the endpoint as `bin/rails routes` prints it
 * (`controller#action`), and the scope's `defaults:`. Ports `Route` in
 * `once-campfire-rust/crates/campfire/src/controllers.rs`, minus the action, which the app binds
 * to [endpoint].
 */
data class Route(
    val verb: String,
    val pattern: String,
    val endpoint: String,
    val defaults: Map<String, String> = emptyMap(),
) {
    /** The `controller` half of [endpoint] (the whole endpoint when it has no `#`). */
    val controller: String = endpoint.substringBefore('#')

    /** The `action` half of [endpoint] (empty when it has no `#`). */
    val actionName: String = if ('#' in endpoint) endpoint.substringAfter('#') else ""
}

/**
 * The outcome of recognizing a request, as data: `recognize` in
 * `once-campfire-rust/crates/campfire/src/controllers.rs` returns `Ok(Some(..))`, `Ok(None)` (which
 * `dispatch` answers with 404, whatever the verb: there is no 405), or the `BadRequest` that a
 * path parameter that isn't UTF-8 once percent-decoded raises.
 */
sealed interface Recognition {
    /**
     * The first route in table order whose verb and pattern match. [index] is its row in the
     * table; [params] are `request.path_parameters`: the route's defaults, then its captures in
     * pattern order (percent-decoded), then `controller` and `action`.
     */
    data class Matched(val route: Route, val index: Int, val params: LinkedHashMap<String, String>) : Recognition {
        /** `controller#action`, the name the app binds a handler to. */
        val endpoint: String
            get() = route.endpoint
    }

    /** No route matches the verb and path (`ActionController::RoutingError`, a 404). */
    data object NotFound : Recognition

    /**
     * A path parameter isn't valid UTF-8 once percent-decoded (`ActionController::BadRequest`,
     * a 400), as `unescape_uri` in `controllers.rs` reports it.
     */
    data class BadRequest(val message: String) : Recognition
}
