package campfire.assets

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Golden tests against what the reference app produced (`once-campfire-rust/crates/assets/tests/reference.rs`;
 * the fixtures in src/test/resources/reference were written by the Rust repo's script/revendor from
 * `assets:precompile` and the real Rails helpers). Files in `overrides/` deliberately differ from
 * the reference, so only their digests and bytes are allowed to.
 */
class ReferenceTest {
    private val mapper = ObjectMapper()

    private fun fixture(name: String): String =
        String(ReferenceTest::class.java.getResourceAsStream("/reference/$name")!!.readAllBytes(), Charsets.UTF_8)

    private fun jsonFixture(name: String): JsonNode = mapper.readTree(fixture(name))

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** The logical paths in `overrides/` (the tests run from the repository root). */
    private fun overrideFiles(): List<String> {
        val dir = File("assets/overrides")
        return dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).invariantSeparatorsPath }.toList()
    }

    /** Each overridden logical path, with the reference's digested path and ours. */
    private fun overridden(): Map<String, Pair<String, String>> {
        val reference = jsonFixture("manifest.json")
        val ours = Assets.manifest.toMap()
        return overrideFiles().mapNotNull { logical ->
            val theirs = reference.get(logical)?.get("digested_path")?.asText() ?: return@mapNotNull null
            val mine = ours.getValue(logical)
            assertNotEquals(theirs, mine, "$logical is overridden but digests the same")
            logical to (theirs to mine)
        }.toMap().toSortedMap()
    }

    /** Logical paths the overrides add, which the reference doesn't have at all. */
    private fun added(): List<String> {
        val reference = jsonFixture("manifest.json")
        return overrideFiles().filter { reference.get(it) == null }
    }

    /** [text] with our digested paths for overridden files replaced by the reference's. */
    private fun asReference(text: String): String =
        overridden().values.fold(text) { acc, (theirs, ours) -> acc.replace(ours, theirs) }

    private fun get(path: String): Served = assertNotNull(Assets.serve(StaticRequest("GET", path)), "$path isn't served")

    @Test
    fun manifestMatchesTheReferencePrecompile() {
        val reference = jsonFixture("manifest.json").properties().associate { (logical, entry) -> logical to entry.get("digested_path").asText() }
        val added = added()
        val ours = Assets.manifest.filter { it.first !in added }.associate { (logical, digested) -> logical to asReference(digested) }

        val missing = reference.filter { (logical, digested) -> ours[logical] != digested }
        val extra = ours.keys.filter { it !in reference }
        assertTrue(missing.isEmpty() && extra.isEmpty(), "differs from reference: $missing, extra: $extra")

        val served = mapper.readTree(asReference(Assets.manifestJson)) as ObjectNode
        for (logical in added) served.remove(logical)
        val referenceJson = jsonFixture("manifest.json") as ObjectNode
        for (logical in overridden().keys) {
            (served.get(logical) as ObjectNode).remove("integrity")
            (referenceJson.get(logical) as ObjectNode).remove("integrity")
        }
        assertEquals(referenceJson, served)
    }

    @Test
    fun compiledFilesAreByteIdenticalToTheReferencePrecompile() {
        val reference = jsonFixture("compiled_sha256.json")
        val overridden = overridden().values.map { it.first }
        val mismatched = reference.properties().filter { (digestedPath, expected) ->
            digestedPath !in overridden && sha256(get("/assets/$digestedPath").bodyBytes()) != expected.asText()
        }.map { it.key }
        assertTrue(mismatched.isEmpty(), "compiled output differs for $mismatched")
        assertEquals(Assets.manifest.size, reference.size() + added().size)
    }

    @Test
    fun stylesheetLinkTagAllMatchesTheReference() {
        val tags = Assets.stylesheetLinkTagAll(listOf("data-turbo-track" to "reload"))
        assertEquals(fixture("stylesheet_link_tag_all.html"), tags.html)
        assertEquals(fixture("link_header.txt"), Assets.appendPreloadLinks("", tags.preloadLinks))
    }

    @Test
    fun javascriptImportmapTagsMatchTheReference() {
        assertEquals(fixture("javascript_importmap_tags.html"), asReference(Assets.importmapTagsHtml))
    }

    @Test
    fun publicFilesAreServedLikeActionDispatchStatic() {
        val overridden = overridden()
        for (case in jsonFixture("static_responses.json")) {
            val env = case.get("env")
            val path = case.get("path").asText()
            val overrideOf = overridden.values.firstOrNull { (theirs, _) -> path == "/assets/$theirs" }
            val request = StaticRequest(
                method = case.get("method").asText(),
                path = overrideOf?.let { "/assets/${it.second}" } ?: path,
                acceptEncoding = env.get("HTTP_ACCEPT_ENCODING")?.asText(),
                range = env.get("HTTP_RANGE")?.asText(),
            )
            val label = "${request.method} ${request.path} $env"
            val expectedStatus = case.get("status").asInt()
            val expectedHeaders = case.get("headers")

            if (expectedStatus == 404 && expectedHeaders.get("x-cascade") != null) {
                assertNull(Assets.serve(request), "$label should fall through")
                continue
            }

            val response = assertNotNull(Assets.serve(request), "$label not served")
            assertEquals(expectedStatus, response.status, label)
            val ours = response.headers.filter { it.first != "last-modified" }.toMap().toSortedMap()
            val theirs = expectedHeaders.properties().associate { (name, value) -> name to value.asText() }.toSortedMap()

            if (request.path == "/assets/.manifest.json" || overrideOf != null) {
                assertEquals(theirs["content-type"], ours["content-type"], label)
                continue
            }
            assertEquals(theirs, ours, label)
            if (request.method == "GET") assertEquals(case.get("body_sha256").asText(), sha256(response.bodyBytes()), label)
        }
    }

    @Test
    fun lastModifiedRoundTripsToA304() {
        val lastModified = get("/robots.txt").header("last-modified")!!
        val notModified = Assets.serve(StaticRequest("GET", "/robots.txt", ifModifiedSince = lastModified))!!
        assertEquals(304, notModified.status)
        assertTrue(notModified.headers.isEmpty() && notModified.bodyLength == 0)
    }

    @Test
    fun headRequestsHaveNoBody() {
        val response = Assets.serve(StaticRequest("HEAD", "/robots.txt"))!!
        assertEquals(200, response.status)
        assertEquals(0, response.bodyLength)
        assertEquals("99", response.header("content-length"))
    }

    @Test
    fun multipleRangesAreMultipart() {
        val response = Assets.serve(StaticRequest("GET", Assets.audioPath("56k.mp3"), range = "bytes=0-1, 4-5"))!!
        assertEquals(206, response.status)
        assertEquals("audio/mpeg", response.header("content-type"))
        val body = String(response.bodyBytes(), Charsets.ISO_8859_1)
        assertTrue(body.startsWith("\r\n--AaB03x\r\ncontent-type: audio/mpeg\r\ncontent-range: bytes 0-1/"))
        assertTrue(body.endsWith("\r\n--AaB03x--\r\n"))
    }
}
