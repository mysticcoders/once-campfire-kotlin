package campfire.app.browser

import kotlin.test.Test

/**
 * A rough timing of the User-Agent work, after `time_user_agent_work` in
 * `once-campfire-rust/crates/campfire/src/concerns/platform.rs`: the median of five runs of
 * 100,000 calls per agent, after a warm-up, printed in ns per call. Nothing is asserted.
 */
class UserAgentBenchmarkTest {
    @Test
    fun timeUserAgentWork() {
        println("%-18s %8s %8s %8s %8s %12s".format("agent", "parse", "blocked", "platform", "to_view", "page request"))
        for ((label, userAgent) in AGENTS) {
            val platform = ApplicationPlatform(userAgent)
            val parse = medianNs { UserAgent.parse(userAgent ?: "").products.size }
            val blocked = medianNs { if (allowBrowser(userAgent)?.blocked == true) 1 else 0 }
            val new = medianNs { ApplicationPlatform(userAgent).userAgent.products.size }
            val toView = medianNs { platform.toView().browser.length }
            val request = medianNs { platformFor(allowBrowser(userAgent)?.platform, userAgent).toView().browser.length }
            println("%-18s %8d %8d %8d %8d %12d".format(label, parse, blocked, new, toView, request))
        }
        println("(sink $sink)")
    }

    private var sink = 0L

    private inline fun medianNs(f: () -> Int): Long {
        repeat(ITERATIONS) { sink += f() }
        val runs = LongArray(RUNS) {
            val start = System.nanoTime()
            repeat(ITERATIONS) { sink += f() }
            (System.nanoTime() - start) / ITERATIONS
        }
        runs.sort()
        return runs[RUNS / 2]
    }

    private companion object {
        const val RUNS = 5
        const val ITERATIONS = 100_000

        val AGENTS = listOf(
            "Chrome, macOS" to "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36",
            "Chrome, Windows" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36",
            "Chrome, Android" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36",
            "Safari, macOS" to "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15",
            "Safari, iPhone" to "Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Mobile/15E148 Safari/604.1",
            "Firefox, Windows" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:143.0) Gecko/20100101 Firefox/143.0",
            "Firefox, macOS" to "Mozilla/5.0 (Macintosh; Intel Mac OS X 14.6; rv:143.0) Gecko/20100101 Firefox/143.0",
            "Edge, Windows" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36 Edg/140.0.0.0",
            "Googlebot" to "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)",
            "none" to null,
        )
    }
}
