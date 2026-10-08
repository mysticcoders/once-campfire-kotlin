package campfire.app.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The tests of `once-campfire-rust/crates/campfire/src/concerns/user_agent.rs`. */
class UserAgentTest {
    private val vectors = UserAgentVectors.root

    @Test
    fun matchesTheGem() {
        val failures = mutableListOf<String>()
        for (case in vectors["user_agents"]) {
            val ua = case["ua"]
            val agent = UserAgent.parse(ua.textValue() ?: "")
            UserAgentVectors.check(failures, "$ua browser", case["browser"]) { agent.tryBrowser() }
            UserAgentVectors.check(failures, "$ua version", case["version"]) { agent.tryVersion() }
            UserAgentVectors.check(failures, "$ua platform", case["platform"]) { agent.tryPlatform() }
            UserAgentVectors.check(failures, "$ua os", case["os"]) { agent.tryOs() }
            UserAgentVectors.check(failures, "$ua bot", case["bot"]) { agent.isBot() }
            UserAgentVectors.check(failures, "$ua mobile", case["mobile"]) { agent.tryMobile() }
        }
        assertTrue(failures.isEmpty(), "${failures.size} mismatches:\n${failures.joinToString("\n")}")
    }

    @Test
    fun versionsMatchTheGem() {
        val failures = mutableListOf<String>()
        for (case in vectors["versions"]) {
            val version = Version(case["string"].textValue())
            val toA = version.toA().map { it.toString() }
            val expected = case["to_a"].map { it.textValue() }
            if (version.isNil() != case["nil"].booleanValue() || toA != expected) {
                failures.add("$case: nil=${version.isNil()} to_a=$toA")
            }
        }
        for (case in vectors["comparisons"]) {
            val a = Version(case["a"].textValue())
            val b = Version(case["b"].textValue())
            val cmp = a.rubyCompare(b)
            if (cmp != case["cmp"].intValue() || (a < b) != case["lt"].booleanValue() || (a == b) != case["eq"].booleanValue()) {
                failures.add("$case: cmp=$cmp lt=${a < b} eq=${a == b}")
            }
        }
        assertTrue(failures.isEmpty(), "${failures.size} mismatches:\n${failures.joinToString("\n")}")
    }

    @Test
    fun blankUserAgentsParseAsTheDefault() {
        val agent = UserAgent.parse("  ")
        assertEquals("Mozilla", agent.browser())
        assertEquals("4.0", agent.version().toString())
    }

    /** Each User-Agent, and each of its prefixes (the truncated and unterminated shapes). */
    @Test
    fun scansProductsLikeTheCharByCharMatcher() {
        for (userAgent in UserAgentVectors.userAgents.map { it ?: "" }) {
            for (prefix in codePointBoundaries(userAgent).map { userAgent.substring(0, it) } + userAgent) {
                val fast = UserAgent.parse(prefix).products.map(::fields)
                assertEquals(Straightforward.products(prefix), fast, prefix)
            }
        }
    }

    @Test
    fun comparesIgnoringCaseLikeLowercasingBoth() {
        val names = productNames()
        val lookedFor = listOf(
            "Chrome-Lighthouse", "Iron", "PaleMoon", "Firefox", "Camino", "Iceweasel", "Seamonkey", "MicroMessenger",
            "CriOs", "chrome", "iTunes", "Version", "OPR", "NSPlayer", "Mobile", "applewebkit",
        ) + names
        for (name in names) {
            for (other in lookedFor) {
                assertEquals(Straightforward.sameIgnoringCase(name, other), sameIgnoringCase(name, other), "$name $other")
            }
        }
    }

    @Test
    fun searchesIgnoringCaseLikeLowercasingTheHaystack() {
        for (haystack in texts()) {
            for (needle in listOf("bot", "micromessenger", "facebookexternalhit", "twitterbot", "k")) {
                val expected = Straightforward.containsIgnoringCase(haystack, needle)
                assertEquals(expected, containsIgnoringCase(haystack, needle), "$haystack $needle")
            }
        }
    }

    /** Each comment, and each of its suffixes. */
    @Test
    fun readsWebkitCommentVersionsLikeLowercasingTheName() {
        for (text in texts()) {
            for (start in codePointBoundaries(text)) {
                val comment = text.substring(start)
                assertEquals(Straightforward.webkitCommentVersion(comment), webkitCommentVersion(comment), comment)
            }
        }
    }

    private fun fields(product: Product) = Triple(product.product, product.version.toString(), product.comment)

    /** The start of each character (Rust's `char_indices`). */
    private fun codePointBoundaries(s: String): List<Int> {
        val boundaries = mutableListOf<Int>()
        var i = 0
        while (i < s.length) {
            boundaries.add(i)
            i += Character.charCount(s.codePointAt(i))
        }
        return boundaries
    }

    /** Every product name the test agents parse to, and the special cases. */
    private fun productNames(): Set<String> {
        val names = sortedSetOf<String>()
        for (userAgent in UserAgentVectors.userAgents) names += UserAgent.parse(userAgent ?: "").products.map { it.product }
        names += SPECIAL_CASES
        return names
    }

    /** Every comment and whole test agent, and the product names. */
    private fun texts(): Set<String> {
        val texts = productNames().toSortedSet()
        for (userAgent in UserAgentVectors.userAgents.filterNotNull()) {
            texts += UserAgent.parse(userAgent).products.flatMap { it.comment.orEmpty() }
            texts += userAgent
        }
        return texts
    }

    /** The straightforward code the fast paths replaced, kept to check them against. */
    private object Straightforward {
        /** `parse`'s product loop over code points, collecting the stripped rest after each product. */
        fun products(userAgent: String): List<Triple<String, String, List<String>?>> {
            var rest = (if (rubyStrip(userAgent).isEmpty()) "Mozilla/4.0 (compatible)" else userAgent).codePoints().toArray()
            val products = mutableListOf<Triple<String, String, List<String>?>>()
            while (true) {
                val (length, product) = matchProduct(rest) ?: break
                products += product
                rest = rubyStrip(String(rest, length, rest.size - length)).codePoints().toArray()
            }
            return products
        }

        private fun space(c: Int) = c < 0x80 && isRubySpace(c.toChar())

        private fun text(s: IntArray, from: Int, to: Int) = String(s, from, to - from)

        private fun matchProduct(s: IntArray): Pair<Int, Triple<String, String, List<String>?>>? {
            val isProductChar = { c: Int -> c != '/'.code && !space(c) }
            var quotes = 0
            while (quotes < s.size && (s[quotes] == '\''.code || s[quotes] == '"'.code)) quotes++
            val start = when {
                quotes < s.size && isProductChar(s[quotes]) -> quotes
                quotes > 0 -> quotes - 1
                else -> return null
            }
            var i = start + 1
            while (i < s.size && isProductChar(s[i])) i++
            val product = text(s, start, i)
            if (i < s.size && s[i] == '/'.code) i++
            val versionStart = i
            while (i < s.size && !space(s[i]) && s[i] != ','.code) i++
            val version = text(s, versionStart, i)
            var comment: String? = null
            if (i + 1 < s.size && space(s[i]) && s[i + 1] == '('.code) {
                var close = i + 2
                while (close < s.size && s[close] != ')'.code) close++
                if (close < s.size) {
                    comment = text(s, i + 2, close)
                    i = close + 1
                }
            } else if (text(s, i, minOf(s.size, i + 10)) == ",gzip(gfe)") {
                i += 10
            }
            return i to Triple(product, version, comment?.let { rubySplit(it, "; ") })
        }

        fun sameIgnoringCase(a: String, b: String) = a.lowercase() == b.lowercase()

        fun containsIgnoringCase(haystack: String, needle: String) = haystack.lowercase().contains(needle)

        fun webkitCommentVersion(comment: String): String? {
            val name = comment.codePoints().limit(11).toArray().let { String(it, 0, it.size) }
            if (name.codePointCount(0, name.length) != 11 || name.lowercase() != "applewebkit") return null
            val tail = comment.substring(name.length)
            if (!tail.startsWith('/')) return null
            val digits = tail.drop(1).takeWhile { it in '0'..'9' || it == '.' }
            return digits.ifEmpty { null }
        }
    }

    private companion object {
        /** Characters whose lowercase is ASCII though they aren't, or is longer than they are. */
        val SPECIAL_CASES = listOf("K", "k", "K", "İ", "i̇", "I", "ß", "SS", "Σ", "σ", "ς", "ÀPPLEWEBKIT")
    }
}
