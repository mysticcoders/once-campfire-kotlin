package campfire.app

import campfire.db.SqliteBackend
import campfire.kit.Action
import campfire.kit.Adapter
import campfire.kit.ErrorPages
import campfire.kit.Handler
import campfire.kit.Kit
import campfire.kit.KitConfig
import campfire.kit.RackDeflater
import campfire.kit.Router
import campfire.kit.front.FrontConfig
import campfire.kit.front.FrontServer
import campfire.kit.layered
import campfire.rails.SystemClock

/**
 * The app's actions by route endpoint, each run with the response cache's [ResponseCache.begin]
 * and [ResponseCache.prepare] around it as `controllers::dispatch` runs them; endpoints not
 * listed answer as the Rust port does for them (`Router.DEFAULT_ACTIONS`, else 404).
 */
fun routes(): Map<String, Action> = mapOf("rooms#show" to RoomsController.show).mapValues { (endpoint, action) -> ResponseCache.around(endpoint, action) }

/**
 * The app's handler (`router` in `crates/campfire/src/app.rs`): the Rails middleware, `public/`
 * files and the route table with the request's [ResponseCache.Snapshot] captured before routing,
 * inside `Rack::Deflater` (`config.ru`), inside the response cache's [ResponseCache.completed].
 */
fun handler(kit: Kit): Handler {
    val router = ResponseCache.snapshotting(Router(routes()))
    val dispatch = Handler { request -> Adapter.dispatch(kit, request, router, Router.CATCH_ALL) }
    return Adapter.app(kit, dispatch, PublicFiles).layered(ResponseCache.completed, RackDeflater())
}

/** The kit for [app]: Campfire's production settings and the reference's public error pages. */
fun kit(app: App): Kit = Kit(KitConfig.production(app.config.disableSsl).copy(errorPages = ErrorPages.PUBLIC), app.secrets, SystemClock, app)

/**
 * Starts the app the way `crates/campfire/src/app.rs` does: the front server (Thruster's job) around
 * Rack::Deflater, the Rails middleware, `public/` files and the router.
 */
fun main() {
    val config = Config()
    val app = App(config)
    val front = FrontConfig.fromEnv()
    FrontServer(front, handler(kit(app)), config.workers).start().also {
        System.err.println("campfire-kotlin listening on :${front.httpPort} (${config.workers} workers, ${config.readers} ${SqliteBackend.name} readers)")
    }.await()
}
