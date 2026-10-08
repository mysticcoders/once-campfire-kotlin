package campfire.app.browser

/** The view-side platform, now `campfire.views.Platform`. */
typealias ViewPlatform = campfire.views.Platform

/**
 * `ApplicationPlatform.new(request.user_agent)` (reference/app/models/application_platform.rb, over
 * platform_agent 1.0.1), and the `allow_browser` check from
 * reference/app/controllers/concerns/allow_browser.rb, ported from
 * `once-campfire-rust/crates/campfire/src/concerns/platform.rs`. Predicates that raise in Ruby (a
 * nil `user_agent.browser`, which Rails turns into a 500) answer false here. A null header is
 * treated like "" (`match?` works on `user_agent_string.to_s`, and `UserAgent.parse` treats nil
 * like ""). Immutable.
 */
class ApplicationPlatform(userAgent: String?) {
    private val userAgentString: String = userAgent ?: ""

    /** The gem's parse of the header. */
    val userAgent: Agent = UserAgent.parse(userAgentString)

    private fun matches(needle: String): Boolean = userAgentString.contains(needle)

    /** `ios?`: the header mentions iPhone or iPad. */
    fun ios(): Boolean = matches("iPhone") || matches("iPad")

    /** `android?`. */
    fun android(): Boolean = matches("Android")

    /** `mac?`. */
    fun mac(): Boolean = matches("Macintosh")

    /** `apple_messages?`: Apple Messages link previews claim to be both the Facebook and Twitter bots. */
    fun appleMessages(): Boolean {
        val lowercased = userAgentString.lowercase()
        return lowercased.contains("facebookexternalhit") && lowercased.contains("twitterbot")
    }

    /** `mobile?`: iOS or Android. */
    fun mobile(): Boolean = ios() || android()

    /** `desktop?`: not mobile. */
    fun desktop(): Boolean = !mobile()

    /** `operating_system`: nil when the gem's `os` is nil; throws [Raised] where Ruby raises. */
    internal fun tryOperatingSystem(): String? {
        val platform = userAgent.tryPlatform() ?: ""
        for ((needle, name) in OPERATING_SYSTEMS) if (platform.contains(needle)) return name
        return userAgent.tryOs()?.let { if (it.contains("Linux")) "Linux" else it }
    }

    /**
     * The platform as the views see it. `chrome?`, `firefox?`, `safari?` and `edge?` all read the
     * gem's `browser`, and `windows?` reads `operating_system`, so each is worked out once here.
     * `browser` (delegated to the gem) and `operating_system` are "" when nil or raised.
     */
    fun toView(): ViewPlatform {
        val browser = attempt { userAgent.tryBrowser() }
        val operatingSystem = attempt { tryOperatingSystem() }
        val mobile = mobile()
        return ViewPlatform(
            ios = ios(),
            android = android(),
            mac = mac(),
            windows = orFalse { isWindows(operatingSystem) },
            chrome = orFalse { browserMatches(browser, CHROME) },
            firefox = orFalse { browserMatches(browser, FIREFOX) },
            safari = orFalse { browserMatches(browser, SAFARI) },
            edge = orFalse { browserMatches(browser, EDGE) },
            mobile = mobile,
            desktop = !mobile,
            appleMessages = appleMessages(),
            browser = browser.getOrNull() ?: "",
            operatingSystem = operatingSystem.getOrNull() ?: "",
        )
    }

    /**
     * `ActionController::AllowBrowser::BrowserBlocker#blocked?` with Campfire's
     * `AllowBrowser::VERSIONS = { safari: 17.2, chrome: 120, firefox: 121, opera: 104, ie: false }`.
     * The blocker parses the header itself; this reads the platform's parse of the same string.
     * Rails raises (a 500) for a versioned agent with a nil browser; that is not blocked here.
     */
    fun browserBlocked(): Boolean = orFalse { tryBrowserBlocked() }

    /** [browserBlocked], throwing [Raised] where Rails raises. */
    internal fun tryBrowserBlocked(): Boolean {
        if (!isPresent(userAgentString)) return false
        val version = userAgent.tryVersion()?.takeIf { it.isPresent() } ?: return false
        val browser = (userAgent.tryBrowser() ?: throw Raised).lowercase()
        val belowMinimum = when (browser) {
            "safari" -> version < SAFARI_MINIMUM
            "chrome" -> version < CHROME_MINIMUM
            "firefox" -> version < FIREFOX_MINIMUM
            "opera" -> version < OPERA_MINIMUM
            "internet explorer" -> true
            else -> return false
        }
        return belowMinimum && !userAgent.isBot()
    }

    internal companion object {
        /** What `chrome?`, `firefox?`, `safari?` and `edge?` look for in the gem's `browser`. */
        val CHROME = arrayOf("Chrome")
        val FIREFOX = arrayOf("Firefox", "FxiOS")
        val SAFARI = arrayOf("Safari")
        val EDGE = arrayOf("Edg")

        private val OPERATING_SYSTEMS = arrayOf(
            "Android" to "Android",
            "iPad" to "iPad",
            "iPhone" to "iPhone",
            "Macintosh" to "macOS",
            "Windows" to "Windows",
            "CrOS" to "ChromeOS",
        )

        private val SAFARI_MINIMUM = Version("17.2")
        private val CHROME_MINIMUM = Version("120")
        private val FIREFOX_MINIMUM = Version("121")
        private val OPERA_MINIMUM = Version("104")

        /** `user_agent.browser.match?(/A|B/)`, which raises for a nil (or raised) browser. */
        fun browserMatches(browser: Result<String?>, names: Array<String>): Boolean {
            val name = browser.getOrElse { throw Raised } ?: throw Raised
            return names.any { name.contains(it) }
        }

        /** `windows?`: `operating_system == "Windows"`. */
        fun isWindows(operatingSystem: Result<String?>): Boolean = operatingSystem.getOrElse { throw Raised } == "Windows"

        /** Runs [block], capturing [Raised] as a failure (the Rust port's `Rb<T>`). */
        inline fun <T> attempt(block: () -> T): Result<T> = try {
            Result.success(block())
        } catch (e: Raised) {
            Result.failure(e)
        }

        /** Runs [block], answering false where it raises. */
        inline fun orFalse(block: () -> Boolean): Boolean = try {
            block()
        } catch (_: Raised) {
            false
        }
    }
}
