package campfire.app

import campfire.db.Database
import campfire.db.Session
import campfire.db.User
import campfire.rails.Secrets
import campfire.views.FragmentCache

/** The application state actions reach through `c.state<App>()` (`crates/campfire/src/app.rs` `AppState`). */
class App(val config: Config) {
    val secrets = Secrets(config.secretKeyBase)
    val db = Database(config.databasePath, config.readers)
    val fragments = FragmentCache(config.fragmentCacheBytes)
    val responseCache = ResponseCache.Store.open(db.path, config.responseCacheBytes)
}

/** `Current.user`. */
class CurrentUser(val user: User)

/** `Current.session`. */
class CurrentSession(val session: Session)

/** How the request authenticated (`Authentication#authenticated_by`). */
enum class AuthenticatedBy { None, Session, BotKey }
