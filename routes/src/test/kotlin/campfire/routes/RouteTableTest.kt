package campfire.routes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The routing tests from `crates/campfire/src/controllers.rs`: the table against
 * `bin/rails routes`, recognition against Rails' `recognize_path`, the indexed recognition
 * against a first-match scan over a large corpus of paths, and path normalization.
 */
class RouteTableTest {
    private val table = RouteTable.campfire

    /** Controllers the reference routes to but doesn't define (`recognize_path` raises for them). */
    private val missingControllers = setOf("rooms/settings")

    /** `the_table_is_rails_routes_in_order` */
    @Test
    fun theTableIsRailsRoutesInOrder() {
        val rails = RouteVectors.routes
        val ours = table.routes
        for ((i, pair) in rails.zip(ours).withIndex()) {
            val (expected, actual) = pair
            assertEquals(
                listOf(expected.verb, expected.path, expected.endpoint, expected.defaults),
                listOf(actual.verb, actual.pattern, actual.endpoint, actual.defaults),
                "route #$i",
            )
        }
        assertEquals(rails.size, ours.size, "every Rails route is in the table, and nothing else")
    }

    /** `recognizes_paths_like_rails` */
    @Test
    fun recognizesPathsLikeRails() {
        for (sample in RouteVectors.recognitions) {
            val where = "${sample.verb} ${sample.path}"
            val recognized = table.recognize(sample.verb, normalizePath(sample.path))
            if (recognized is Recognition.BadRequest) fail("$where raised ${recognized.message}")
            when {
                sample.endpoint != null && recognized is Recognition.Matched -> {
                    assertEquals(sample.endpoint, recognized.endpoint, where)
                    val params = recognized.params.filterKeys { it != "controller" && it != "action" }
                    assertEquals(sample.params, params, where)
                }
                sample.endpoint == null && recognized is Recognition.Matched ->
                    assertTrue(recognized.route.controller in missingControllers, "$where matched ${recognized.endpoint}")
                sample.endpoint != null -> fail("$where should be ${sample.endpoint}")
            }
        }
    }

    /**
     * Recognition the straightforward way: try the verb's routes in table order and take the
     * first whose regex matches. `recognize` must agree with it on every path.
     */
    private fun recognizeByScanning(method: String, path: String): Recognition {
        val verb = if (method == "HEAD") "GET" else method
        for (route in table.compiled) {
            if (route.route.verb != verb) continue
            val spans = IntArray(route.names.size * 2)
            if (route.matchesByRegex(path, spans)) return route.recognition(path, spans)
        }
        return Recognition.NotFound
    }

    /** The matched row's index and params (in order), null, or the error, in a form that compares. */
    private fun outcome(recognized: Recognition): Any? = when (recognized) {
        is Recognition.Matched -> recognized.index to recognized.params.entries.map { it.key to it.value }
        Recognition.NotFound -> null
        is Recognition.BadRequest -> "error: ${recognized.message}"
    }

    /**
     * A route pattern's paths: with and without the format, and with its params filled in with
     * ids, words, percent-escapes and raw UTF-8.
     */
    private fun filledIn(pattern: String): List<String> {
        val param = Regex("""([:*])(\w+)""")
        val fills = listOf("1" to "photo", "opens" to "dir/photo.tar.gz", "caf%C3%A9" to "a%2Fb/c%20d", "café" to "😀/dir/é.tar")
        return listOf("", ".:format").flatMap { format ->
            val withFormat = pattern.replace("(.:format)", format)
            fills.map { (value, glob) ->
                param.replace(withFormat) {
                    when {
                        it.groupValues[1] == ":" && it.groupValues[2] == "format" -> "json"
                        it.groupValues[1] == ":" -> value
                        else -> glob
                    }
                }
            }
        }
    }

    /** [path] with its first [count] slashes doubled (`replacen('/', "//", count)`). */
    private fun doubleSlashes(path: String, count: Int): String {
        val doubled = StringBuilder()
        var remaining = count
        for (c in path) {
            doubled.append(c)
            if (c == '/' && remaining > 0) {
                doubled.append('/')
                remaining--
            }
        }
        return doubled.toString()
    }

    /**
     * [path] and its near misses: other formats, trailing and doubled slashes, a character or a
     * segment more or less, other case, a query, escapes that aren't UTF-8, and raw UTF-8.
     */
    private fun variants(path: String): List<String> {
        val variants = mutableListOf(path, path.uppercase(), doubleSlashes(path, 2), path.trimStart('/'))
        val suffixes = listOf(".json", ".turbo_stream", ".1.2", ".", "/", "//", "x", "/x", "/new", "/edit", "?q=1", "%FF", "%2F", "é", "😀.json")
        suffixes.mapTo(variants) { path + it }
        if (path.isNotEmpty()) variants.add(path.substring(0, path.offsetByCodePoints(path.length, -1)))
        val slash = path.lastIndexOf('/')
        if (slash >= 0) {
            val parent = path.substring(0, slash)
            variants.addAll(listOf(parent, "$parent/%E9", "$parent/@42", "$parent/opens"))
        }
        return variants
    }

    /**
     * Every path the Rails vectors recognize and every route pattern filled in, as given and
     * normalized, with their near misses.
     */
    private fun corpus(): Set<String> {
        val seeds = RouteVectors.recognitions.map { it.path } + RouteVectors.routes.flatMap { filledIn(it.path) }
        return seeds.flatMap { listOf(normalizePath(it), it) }.flatMap(::variants).toSortedSet()
    }

    /** `recognizes_like_a_first_match_scan` */
    @Test
    fun recognizesLikeAFirstMatchScan() {
        val corpus = corpus()
        val rows = sortedSetOf<Int>()
        var missed = 0
        var failed = 0
        for (verb in listOf("GET", "HEAD", "POST", "PATCH", "PUT", "DELETE", "OPTIONS")) {
            for (path in corpus) {
                val expected = recognizeByScanning(verb, path)
                assertEquals(outcome(expected), outcome(table.recognize(verb, path)), "$verb $path")
                when (expected) {
                    is Recognition.Matched -> rows.add(expected.index)
                    Recognition.NotFound -> missed++
                    is Recognition.BadRequest -> failed++
                }
            }
        }
        val unmatched = table.routes.withIndex().filter { it.index !in rows }.map { "${it.value.verb} ${it.value.pattern}" }
        assertEquals(listOf("GET /rooms/opens(.:format)", "GET /rooms/closeds(.:format)", "GET /rooms/directs(.:format)"), unmatched)
        assertTrue(missed > 10_000 && failed > 1_000, "$missed paths missed, $failed failed")
    }

    /** Every Campfire route is matched by hand, not by regex. */
    @Test
    fun everyRouteMatchesByHand() {
        assertEquals(emptyList(), table.compiled.filter { !it.matchesByHand }.map { it.route.pattern })
    }

    /**
     * Line breaks, which the glob refuses and params accept, recognize the same by hand as by
     * regex (the corpus above has none).
     */
    @Test
    fun recognizesLineBreaksLikeAFirstMatchScan() {
        val paths = listOf(
            "/rails/active_storage/blobs/redirect/abc/a\nb.png", "/rails/active_storage/blobs/redirect/abc/ab.p\ng",
            "/rails/active_storage/blobs/redirect/abc/a\rb.png", "/rails/active_storage/blobs/redirect/a\nc/b",
            "/rails/active_storage/disk/k/\n", "/rooms/1\n", "/rooms/1.js\n", "/rooms/\r/messages", "/up.\n",
        )
        for (path in paths) {
            assertEquals(outcome(recognizeByScanning("GET", path)), outcome(table.recognize("GET", path)), path)
        }
    }

    /** `normalizes_paths` */
    @Test
    fun normalizesPaths() {
        assertEquals("/", normalizePath(""))
        assertEquals("/rooms/1", normalizePath("//rooms//1//"))
        assertEquals("/a%2Fb", normalizePath("/a%2fb"))
    }

    /** Normal paths come back as the same instance; the rest as `normalize_path` makes them. */
    @Test
    fun normalizesOnlyWhatNeedsIt() {
        val normal = "/rooms/1/messages"
        assertTrue(normalizePath(normal) === normal)
        assertEquals("/rooms", normalizePath("rooms"))
        assertEquals("/%2F%AB%%2Fx", normalizePath("/%2f%ab%%2fx/"))
        assertEquals("/%2", normalizePath("/%2"))
    }

    /** HEAD falls back to GET, and an unknown verb or path is not found (a 404; there is no 405). */
    @Test
    fun headMatchesGetAndUnknownsAreNotFound() {
        val head = assertIs<Recognition.Matched>(table.recognize("HEAD", "/rooms/1"))
        assertEquals("rooms#show", head.endpoint)
        assertEquals(linkedMapOf("id" to "1", "controller" to "rooms", "action" to "show"), head.params)
        assertEquals(Recognition.NotFound, table.recognize("OPTIONS", "/rooms/1"))
        assertEquals(Recognition.NotFound, table.recognize("get", "/rooms/1"))
        assertEquals(Recognition.NotFound, table.recognize("PATCH", "/rooms"))
        assertEquals(Recognition.NotFound, table.recognize("GET", "/nope"))
    }

    /** Params are the defaults, then captures in pattern order with the format, then controller and action. */
    @Test
    fun extractsParamsInOrder() {
        val bot = assertIs<Recognition.Matched>(table.recognize("POST", "/rooms/3/abc-123/messages/9/boosts.xml"))
        assertEquals("messages/boosts/by_bots#create", bot.endpoint)
        assertEquals(
            listOf("format" to "xml", "room_id" to "3", "bot_key" to "abc-123", "message_id" to "9", "controller" to "messages/boosts/by_bots", "action" to "create"),
            bot.params.entries.map { it.key to it.value },
        )
        val blob = assertIs<Recognition.Matched>(table.recognize("GET", "/rails/active_storage/blobs/redirect/abc--def/dir/photo.tar.gz"))
        assertEquals(linkedMapOf("signed_id" to "abc--def", "filename" to "dir/photo.tar", "format" to "gz", "controller" to "active_storage/blobs/redirect", "action" to "show"), blob.params)
        val shadowed = assertIs<Recognition.Matched>(table.recognize("GET", "/rooms/opens"))
        assertEquals("rooms#show", shadowed.endpoint)
        assertEquals("opens", shadowed.params["id"])
    }

    /** Captures are percent-decoded, and one that isn't UTF-8 is a bad request. */
    @Test
    fun decodesParams() {
        val decoded = assertIs<Recognition.Matched>(table.recognize("GET", "/users/caf%C3%A9"))
        assertEquals("café", decoded.params["id"])
        assertEquals(Recognition.BadRequest("Invalid path parameters: Invalid encoding"), table.recognize("GET", "/users/%FF"))
        assertEquals("100%", unescapeUri("100%"))
        assertEquals("%zz/", unescapeUri("%zz%2F"))
    }
}
