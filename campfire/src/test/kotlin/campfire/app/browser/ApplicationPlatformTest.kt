package campfire.app.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The tests of `once-campfire-rust/crates/campfire/src/concerns/platform.rs`, and of the
 * User-Agent work of `allow_browser` and `platform` in `concerns.rs`.
 */
class ApplicationPlatformTest {
    @Test
    fun matchesApplicationPlatformAndAllowBrowser() {
        val failures = mutableListOf<String>()
        for (case in UserAgentVectors.root["user_agents"]) {
            val label = case["ua"]
            val platform = ApplicationPlatform(label.textValue())
            val browser = ApplicationPlatform.attempt { platform.userAgent.tryBrowser() }
            val operatingSystem = ApplicationPlatform.attempt { platform.tryOperatingSystem() }
            val expected = case["application_platform"]
            fun field(name: String, actual: () -> Any?) = UserAgentVectors.check(failures, "$label $name", expected[name], actual)

            field("ios") { platform.ios() }
            field("android") { platform.android() }
            field("mac") { platform.mac() }
            field("chrome") { ApplicationPlatform.browserMatches(browser, ApplicationPlatform.CHROME) }
            field("firefox") { ApplicationPlatform.browserMatches(browser, ApplicationPlatform.FIREFOX) }
            field("safari") { ApplicationPlatform.browserMatches(browser, ApplicationPlatform.SAFARI) }
            field("edge") { ApplicationPlatform.browserMatches(browser, ApplicationPlatform.EDGE) }
            field("apple_messages") { platform.appleMessages() }
            field("mobile") { platform.mobile() }
            field("desktop") { platform.desktop() }
            field("windows") { ApplicationPlatform.isWindows(operatingSystem) }
            field("operating_system") { operatingSystem.getOrThrow() }
            field("browser") { browser.getOrThrow() }

            fun flag(name: String) = expected[name].takeIf { it.isBoolean }?.booleanValue() ?: false
            fun text(name: String) = expected[name].takeIf { it.isTextual }?.textValue() ?: ""
            val expectedView = ViewPlatform(
                ios = flag("ios"),
                android = flag("android"),
                mac = flag("mac"),
                windows = flag("windows"),
                chrome = flag("chrome"),
                firefox = flag("firefox"),
                safari = flag("safari"),
                edge = flag("edge"),
                mobile = flag("mobile"),
                desktop = flag("desktop"),
                appleMessages = flag("apple_messages"),
                browser = text("browser"),
                operatingSystem = text("operating_system"),
            )
            val view = platform.toView()
            if (view != expectedView) failures.add("$label view: expected $expectedView, got $view")

            UserAgentVectors.check(failures, "$label blocked", case["blocked"]) { platform.tryBrowserBlocked() }
        }
        assertTrue(failures.isEmpty(), "${failures.size} mismatches:\n${failures.joinToString("\n")}")
    }

    @Test
    fun viewPlatformUsesEmptyStringsForNil() {
        val view = ApplicationPlatform("curl/8.4.0").toView()
        assertEquals("", view.operatingSystem)
        assertEquals("curl", view.browser)
        assertTrue(view.desktop)
    }

    @Test
    fun allowBrowserSkipsMissingAndBlankHeaders() {
        assertNull(allowBrowser(null))
        assertNull(allowBrowser(""))
        assertNull(allowBrowser(" \t "))
    }

    @Test
    fun allowBrowserBlocksOldBrowsersAndKeepsThePlatform() {
        val old = allowBrowser("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36")!!
        assertTrue(old.blocked)
        assertEquals("Chrome", old.platform.toView().browser)
        val current = allowBrowser("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")!!
        assertFalse(current.blocked)
        assertSame(current.platform, platformFor(current.platform, "ignored"))
    }

    /** `allow_browser` then the layout's `platform` sees what a fresh parse of the header sees. */
    @Test
    fun pageRequestPlatformMatchesAFreshParse() {
        for (userAgent in UserAgentVectors.userAgents) {
            val check = allowBrowser(userAgent)
            assertEquals(ApplicationPlatform(userAgent).toView(), platformFor(check?.platform, userAgent).toView(), userAgent)
            assertEquals(ApplicationPlatform(userAgent).browserBlocked(), check?.blocked ?: false, userAgent)
        }
    }
}
