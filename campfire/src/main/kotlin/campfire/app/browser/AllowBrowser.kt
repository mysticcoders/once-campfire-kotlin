package campfire.app.browser

/**
 * The outcome of [allowBrowser]: the platform parsed for the check (kept for the layout's
 * `platform`, so a page parses its User-Agent once) and whether the browser is blocked.
 */
class AllowBrowserCheck(val platform: ApplicationPlatform, val blocked: Boolean)

/**
 * The User-Agent work of `allow_browser versions: VERSIONS, block: -> { render template:
 * "sessions/incompatible_browser" }` (reference/app/controllers/concerns/allow_browser.rb), from
 * `allow_browser` in `once-campfire-rust/crates/campfire/src/concerns.rs`. A missing or blank
 * header (ActiveSupport `blank?`) isn't checked and gives null; otherwise the header is parsed
 * once and checked with [ApplicationPlatform.browserBlocked]. A blocked request renders
 * sessions/incompatible_browser (200), which the request plumbing does.
 */
fun allowBrowser(userAgent: String?): AllowBrowserCheck? {
    if (userAgent == null || !isPresent(userAgent)) return null
    val platform = ApplicationPlatform(userAgent)
    return AllowBrowserCheck(platform, platform.browserBlocked())
}

/**
 * `SetPlatform#platform` (`helper_method`): `@platform ||= ApplicationPlatform.new(request.user_agent)`,
 * from `platform` in `once-campfire-rust/crates/campfire/src/concerns.rs`: the platform
 * [allowBrowser] kept, or a new one when it didn't parse the header.
 */
fun platformFor(kept: ApplicationPlatform?, userAgent: String?): ApplicationPlatform = kept ?: ApplicationPlatform(userAgent)
