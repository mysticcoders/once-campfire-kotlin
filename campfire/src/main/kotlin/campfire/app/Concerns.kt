package campfire.app

import campfire.app.browser.ApplicationPlatform
import campfire.app.browser.allowBrowser as allowBrowserCheck
import campfire.db.Session
import campfire.db.dbTime
import campfire.db.touchSession
import campfire.kit.Cookie
import campfire.kit.Ctx
import campfire.kit.KitError
import campfire.kit.SameSite
import campfire.kit.halt

/** Whether an action requires a signed-in user (`Authentication` concern options). */
enum class Authentication { Required, Allowed, RequireUnauthenticated }

/** Which of `ApplicationController`'s before-actions apply (`crates/campfire/src/concerns.rs` `Before`). */
data class Before(
    val authentication: Authentication = Authentication.Required,
    val denyBots: Boolean = true,
    val forgeryProtection: Boolean = true,
)

/** `ApplicationController`'s before-actions, in the order the reference runs them. */
fun beforeActions(c: Ctx, before: Before = Before()) {
    setVersionHeaders(c)
    rejectBannedIp(c)
    if (before.authentication == Authentication.Required) requireAuthentication(c)
    if (before.denyBots) denyBots(c)
    if (before.forgeryProtection && authenticatedBy(c) != AuthenticatedBy.BotKey) c.verifyAuthenticityToken()
    allowBrowser(c)
}

/** `set_version_headers`: `X-Version`, and `X-Rev` unless it's nil (`config/initializers/version.rb`). */
fun setVersionHeaders(c: Ctx) {
    val config = c.state<App>().config
    c.setHeader("x-version", config.appVersion)
    config.gitRevision?.let { c.setHeader("x-rev", it) }
}

/** `reject_banned_ip`, unless the request is safe (GET/HEAD): 429 for a banned `remote_ip`. */
fun rejectBannedIp(c: Ctx) {
    if (c.request.isGet || c.request.isHead) return
    val ip = c.request.remoteIp()
    if (c.state<App>().db.read { it.banned(ip) }) halt(c.head(429))
}

/** `require_authentication`: `restore_authentication || bot_authentication || request_authentication`. */
fun requireAuthentication(c: Ctx) {
    if (restoreAuthentication(c) || botAuthentication(c)) return
    requestAuthentication(c)
}

/** `restore_authentication`: resume the session the `session_token` cookie names. */
fun restoreAuthentication(c: Ctx): Boolean {
    val token = c.cookies.signed("session_token") ?: return false
    val session = c.state<App>().db.read { it.sessionByToken(token) } ?: return false
    resumeSession(c, session)
    return true
}

/** `bot_authentication`: `params[:bot_key].present?` and a matching active bot. */
fun botAuthentication(c: Ctx): Boolean {
    val param = c.param("bot_key")?.takeIf { it.isPresent } ?: return false
    val key = param.asStr() ?: throw KitError.Internal(IllegalStateException("undefined method 'strip' for bot_key"))
    val bot = c.state<App>().db.read { it.authenticateBot(campfire.ruby.strip(key)) } ?: return false
    c.setCurrent(CurrentUser(bot))
    c.setCurrent(AuthenticatedBy.BotKey)
    return true
}

/** `request_authentication`: remember where we were, then off to sign in. */
fun requestAuthentication(c: Ctx): Nothing {
    c.session()["return_to_after_authenticating"] = c.request.url()
    halt(c.redirectTo(c.urlFor("/session/new")))
}

/**
 * `resume_session(session)`: refresh its activity at most hourly, then authenticate as it. Only a
 * session due for its refresh goes to the writer, and the `session_token` cookie is re-signed on
 * the same schedule, as the Rust port does (Rails re-signs it on every request).
 */
fun resumeSession(c: Ctx, session: Session) {
    val refresh = session.needsResume(c.now())
    if (refresh) {
        val userAgent = c.request.userAgent
        val ip = c.request.remoteIp()
        val now = dbTime(c.now())
        c.state<App>().db.write { tx -> touchSession(tx, session.id, ip, userAgent, now) }
    }
    val user = c.state<App>().db.read { it.user(session.userId) }
    if (refresh) c.cookies.setSigned("session_token", Cookie(session.token).permanent().httponly().sameSite(SameSite.LAX))
    c.setCurrent(CurrentSession(session))
    if (user != null) c.setCurrent(CurrentUser(user))
    c.setCurrent(AuthenticatedBy.Session)
}

/** `authenticated_by`. */
fun authenticatedBy(c: Ctx): AuthenticatedBy = c.current<AuthenticatedBy>() ?: AuthenticatedBy.None

/** `Current.user`. */
fun currentUser(c: Ctx) = c.current<CurrentUser>()?.user

/** `deny_bots`: 403 for bot-key requests. */
fun denyBots(c: Ctx) {
    if (authenticatedBy(c) == AuthenticatedBy.BotKey) halt(c.head(403))
}

/**
 * `allow_browser versions: VERSIONS, block: -> { render template: "sessions/incompatible_browser" }`.
 * The platform parsed for the check is kept for the layout, so a page parses its User-Agent once;
 * a missing or blank one isn't checked.
 */
fun allowBrowser(c: Ctx) {
    val check = allowBrowserCheck(c.request.userAgent) ?: return
    c.setCurrent(check.platform)
    if (check.blocked) halt(renderIncompatibleBrowser(c))
}

/** `platform`: the one [allowBrowser] kept, or a new one. */
fun platform(c: Ctx): ApplicationPlatform = c.current<ApplicationPlatform>() ?: ApplicationPlatform(c.request.userAgent)

/** `remember_last_room_visited`: `cookies.permanent[:last_room] = @room.id`, only when it changes. */
fun rememberLastRoomVisited(c: Ctx, roomId: Long) {
    val id = roomId.toString()
    if (c.cookies["last_room"] != id) c.cookies.set("last_room", Cookie(id).permanent())
}
