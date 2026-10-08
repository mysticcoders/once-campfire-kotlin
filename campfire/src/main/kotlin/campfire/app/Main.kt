package campfire.app

import campfire.db.SqliteBackend
import campfire.kit.Adapter
import campfire.kit.ErrorPages
import campfire.kit.Kit
import campfire.kit.KitConfig
import campfire.kit.RackDeflater
import campfire.kit.Router
import campfire.kit.front.FrontConfig
import campfire.kit.front.FrontServer
import campfire.kit.layered
import campfire.rails.SystemClock

/**
 * The app's actions by route endpoint; endpoints not listed answer as the Rust port does for them
 * (`Router.DEFAULT_ACTIONS`, else 404).
 */
fun routes() = mapOf("rooms#show" to RoomsController.show)

/**
 * Starts the app the way `crates/campfire/src/app.rs` does: the front server (Thruster's job) around
 * Rack::Deflater, the Rails middleware, `public/` files and the router.
 */
fun main() {
    val config = Config()
    val app = App(config)
    val kit = Kit(KitConfig.production(config.disableSsl).copy(errorPages = ErrorPages.PUBLIC), app.secrets, SystemClock, app)
    val handler = Adapter.app(kit, Router(routes()).handler(kit), PublicFiles).layered(RackDeflater())
    val front = FrontConfig.fromEnv()
    FrontServer(front, handler, config.workers).start().also {
        System.err.println("campfire-kotlin listening on :${front.httpPort} (${config.workers} workers, ${config.readers} ${SqliteBackend.name} readers)")
    }.await()
}
