package campfire.richtext

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import java.util.TreeMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Differential test against the Rails pipeline (`once-campfire-rust/crates/richtext/tests/corpus.rs`):
 * `src/test/resources/corpus/expected.json` is a copy of the Rust crate's, produced by
 * reference-tools/richtext/run.sh in the campfire-reference image. Every output is also checked
 * against security properties that don't depend on the oracle. The `campfire.richtext.corpus`
 * system property points at another `expected.json` (the Rust test's `RICHTEXT_CORPUS`).
 */
class CorpusTest {
    private val corpus: JsonNode by lazy {
        val override = System.getProperty("campfire.richtext.corpus")
        val text = if (override != null) {
            File(override).readText()
        } else {
            CorpusTest::class.java.getResourceAsStream("/corpus/expected.json")!!.readBytes().toString(Charsets.UTF_8)
        }
        ObjectMapper().readTree(text)
    }

    private fun users(): List<MentionUser> = corpus["users"].map { u ->
        MentionUser(
            u["id"].asLong(), u["name"].asText(), u["title"].asText(), u["attachable_sgid"].asText(), u["user_path"].asText(),
            u["avatar_path"].asText(),
        )
    }

    /** Stands in for the app: SGIDs Rails minted are "verified" by exact match, and GIDs are looked up by model and id. */
    private inner class TestResolver : AttachableResolver {
        val users = users()
        val rooms = corpus["rooms"].map { it.asLong() }
        val signed = corpus["signed"].map { s -> listOf(s["sgid"].asText(), s["model"].asText(), s["id"].asLong(), s["exists"].asBoolean()) }

        override fun locateSigned(sgid: String): SignedLookup {
            val found = signed.firstOrNull { it[0] == sgid } ?: return SignedLookup.Invalid
            val model = found[1] as String
            return if (found[3] == true && model == "User") {
                SignedLookup.User(users.first { it.id == found[2] })
            } else {
                SignedLookup.MissingRecord(model)
            }
        }

        override fun findGid(gid: String): GidLookup {
            if (!gid.startsWith("gid://")) return GidLookup.NotFound
            if (gid.any { it.code >= 128 }) return GidLookup.NotFound
            val rest = gid.removePrefix("gid://")
            val slash = rest.indexOf('/')
            if (slash < 0) return GidLookup.NotFound
            val path = rest.substring(slash + 1).substringBefore('?')
            val modelSlash = path.indexOf('/')
            if (modelSlash < 0) return GidLookup.NotFound
            val model = path.substring(0, modelSlash)
            val id = path.substring(modelSlash + 1).toLongOrNull() ?: return GidLookup.NotFound
            return when {
                model == "User" -> users.firstOrNull { it.id == id }?.let { GidLookup.User(it) } ?: GidLookup.NotFound
                model == "Room" && id in rooms -> GidLookup.OtherModel
                else -> GidLookup.NotFound
            }
        }
    }

    private class Tally {
        var exact = 0
        var domEqual = 0
        val mismatched = ArrayList<String>()

        fun record(label: String, expected: String, actual: String) {
            if (expected == actual) {
                exact++
            } else if (normalizedDom(expected) == normalizedDom(actual)) {
                domEqual++
                mismatched.add("$label (DOM-equal)\n  expected: ${quote(expected)}\n  actual:   ${quote(actual)}")
            } else {
                mismatched.add("$label\n  expected: ${quote(expected)}\n  actual:   ${quote(actual)}")
            }
        }
    }

    /** The outcome Rails recorded: an error, or a value (null when Rails returned nil). */
    private sealed interface Outcome {
        data class Ok(val value: String?) : Outcome
        data class Err(val message: String) : Outcome
    }

    private fun outcome(v: JsonNode): Outcome {
        val error = v["error"]
        if (error != null) return Outcome.Err(error.asText())
        val ok = v["ok"]
        return Outcome.Ok(if (ok == null || ok.isNull) null else ok.asText())
    }

    private inline fun <T> attempt(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: RichTextError) {
        Result.failure(e)
    }

    @Test
    fun corpusMatchesRails() {
        val resolver = TestResolver()
        val tallies = TreeMap<String, Tally>()
        fun tally(kind: String) = tallies.getOrPut(kind) { Tally() }
        val security = ArrayList<String>()
        val cases = corpus["cases"]

        for (case in cases) {
            val name = case["name"].asText()
            val body = case["body"].asText()
            val host = case["host"]?.takeIf { !it.isNull }?.asText()
            val ctx = RenderContext(resolver, host)

            val expected = when (val o = outcome(case["presentation"])) {
                is Outcome.Ok -> Presentation.Html(withPortDivergences(o.value ?: ""))
                is Outcome.Err -> Presentation.Unrenderable
            }
            val actual = RichText.presentMessage(body, ctx)
            val label = "[presentation] $name"
            val missingPartial = case["presentation_raised_message"]?.asText()?.contains("to_missing_attachable_partial_path") == true
            if (expected is Presentation.Html && actual is Presentation.Html && missingPartial) {
                assertTrue(actual.html.contains('☒'), "$label: ${actual.html}")
                tally("presentation").exact++
            } else if (expected is Presentation.Html && actual is Presentation.Html) {
                tally("presentation").record(label, expected.html, actual.html)
            } else {
                tally("presentation").record(label, expected.toString(), actual.toString())
            }
            if (actual is Presentation.Html) {
                for (v in securityViolations(actual.html, false)) security.add("$name: $v")
            }

            val plain = attempt { RichText.toPlainText(body, ctx) }
            val plainLabel = "[plain_text] $name"
            when (val e = outcome(case["plain_text"])) {
                is Outcome.Ok -> if (plain.isSuccess) {
                    tally("plain_text").record(plainLabel, e.value ?: "", plain.getOrThrow())
                } else {
                    tally("plain_text").record(plainLabel, e.toString(), plain.exceptionOrNull().toString())
                }
                is Outcome.Err -> if (plain.isFailure) tally("plain_text").exact++ else tally("plain_text").record(plainLabel, e.toString(), plain.toString())
            }

            val editable = attempt { RichText.editableValue(body, ctx) }
            val editableLabel = "[editable] $name"
            val missingInEditor = case["editable"]["message"]?.asText()?.contains("MissingAttachable") == true
            when (val e = outcome(case["editable"])) {
                is Outcome.Err -> if (editable.isFailure || missingInEditor) {
                    tally("editable").exact++
                } else {
                    tally("editable").record(editableLabel, e.toString(), editable.toString())
                }
                is Outcome.Ok -> if (editable.isSuccess) {
                    tally("editable").record(editableLabel, debugOption(e.value), debugOption(editable.getOrThrow()))
                } else {
                    tally("editable").record(editableLabel, e.toString(), editable.exceptionOrNull().toString())
                }
            }

            val mentioned = attempt { RichText.mentionedUsers(body, ctx).map { it.id } }
            val expectedIds = case["mentioned"]["ok"]?.map { it.asLong() }
            val mentionedLabel = "[mentioned] $name"
            if (expectedIds != null && mentioned.isSuccess) {
                tally("mentioned").record(mentionedLabel, expectedIds.toString(), mentioned.getOrThrow().toString())
            } else if (expectedIds == null && mentioned.isFailure) {
                tally("mentioned").exact++
            } else {
                tally("mentioned").record(mentionedLabel, expectedIds.toString(), mentioned.toString())
            }
        }

        for (url in corpus["web_urls"]) {
            val value = url["value"].asText()
            val host = url["host"].asText()
            val actual = attempt { Attachables.webUrl(value, host) }
            val label = "[web_url] ${quote(value)}"
            when (val e = outcome(url["result"])) {
                is Outcome.Ok -> if (actual.isSuccess) {
                    tally("web_url").record(label, debugOption(e.value), debugOption(actual.getOrThrow()))
                } else {
                    tally("web_url").record(label, e.toString(), actual.toString())
                }
                is Outcome.Err -> if (actual.isFailure) tally("web_url").exact++ else tally("web_url").record(label, e.toString(), actual.toString())
            }
        }

        var failed = false
        for ((kind, t) in tallies) {
            val total = t.exact + t.mismatched.size
            println("$kind: $total cases, ${t.exact} byte-identical, ${t.domEqual} DOM-equal only, ${t.mismatched.size - t.domEqual} different")
            for (m in t.mismatched) {
                println("  MISMATCH $m")
                failed = true
            }
        }
        println("security: ${cases.size()} outputs checked, ${security.size} violations")
        for (v in security) println("  VIOLATION $v")
        assertTrue(security.isEmpty(), "security assertions failed")
        assertTrue(!failed, "differences from the Rails pipeline")
    }

    /**
     * The oracle's own outputs must pass the security assertions too: two implementations can
     * agree on something unsafe. (They do: rails_autolink breaks out of attribute values, which is
     * why the port diverges there, so the assertions run on the output as the port means to render it.)
     */
    @Test
    fun railsOutputsPassSecurityAssertions() {
        val violations = ArrayList<String>()
        for (case in corpus["cases"]) {
            val o = outcome(case["presentation"])
            if (o is Outcome.Ok && o.value != null) {
                for (v in securityViolations(withPortDivergences(o.value), false)) violations.add("${case["name"].asText()}: $v")
            }
        }
        for (v in violations) println("  VIOLATION $v")
        assertTrue(violations.isEmpty())
    }

    /** The security gate has to catch what it's for, or passing it means nothing. */
    @Test
    fun securityAssertionsCatchPlantedDefects() {
        for ((html, what) in listOf(
            "<script>alert(1)</script>" to "script element",
            "<p onclick=\"x()\">p</p>" to "event handler",
            "<a href=\"java\tscript:alert(1)\">x</a>" to "javascript URL",
            "<a href=\" JAVASCRIPT:alert(1)\">x</a>" to "javascript URL",
            "<img src=\"data:text/html,<script>\">" to "data URL",
            "<span style=\"color: red\">x</span>" to "style attribute",
            "<svg><a xlink:href=\"javascript:1\">x</a></svg>" to "svg",
            "<span data-controller=\"x\">x</span>" to "data attribute",
            "<p title=\"a\" _blank\"=\"\">x</p>" to "attribute outside the allowlist",
            "<details>x</details>" to "element outside the allowlist",
        )) {
            assertTrue(securityViolations(html, false).isNotEmpty(), "missed $what in $html")
        }
        assertTrue(securityViolations("<p><a href=\"https://example.com\">x</a><img src=\"/a.png\"></p>", false).isEmpty())
    }

    @Test
    fun portDivergencesApplyToAttributeValuesOnly() {
        assertEquals(
            "<p title=\"a&gt;b http://x.test/\">c > <a target=\"_blank\" href=\"http://y.test/\">y</a></p>",
            withPortDivergences(
                "<p title=\"a>b <a target=\"_blank\" href=\"http://x.test/\">http://x.test/</a>\">c > <a target=\"_blank\" href=\"http://y.test/\">y</a></p>",
            ),
        )
        assertEquals("<a title=\"name=\">n</a>", withPortDivergences("<a name=\"x y\" title=\"name=\">n</a>"))
    }

    companion object {
        private fun quote(s: String): String = ObjectMapper().writeValueAsString(s)

        private fun debugOption(s: String?): String = if (s == null) "None" else "Some(${quote(s)})"

        /** A DOM normalization for reporting near misses: parse, drop whitespace-only text, sort attributes. */
        fun normalizedDom(html: String): String {
            val dom = Dom()
            val root = try {
                dom.parseFragment(html)
            } catch (_: RichTextError.Parse) {
                return "unparseable: $html"
            }
            val out = StringBuilder()
            normalizeInto(dom, root, out)
            return out.toString()
        }

        private fun normalizeInto(dom: Dom, node: Int, out: StringBuilder) {
            for (child in dom.children(node).toArray()) {
                val text = dom.text(child)
                if (text != null) {
                    out.append(text.split(Regex("[\\s\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\u0085]+")).filter { it.isNotEmpty() }.joinToString(" "))
                } else {
                    val name = dom.localName(child) ?: continue
                    val attrs = dom.attrs(child).sortedWith(compareBy({ it.first }, { it.second }))
                    out.append("<$name $attrs>")
                    normalizeInto(dom, child, out)
                    out.append("</$name>")
                }
            }
        }

        /**
         * Rails' presentation as the port renders it on purpose (see "Known differences" in the
         * Rust README): `<` and `>` are escaped in attribute values, and the links
         * rails_autolink inserted inside an attribute value (its stored XSS) are left as the text
         * they replaced; `name` attributes are dropped.
         */
        fun withPortDivergences(rails: String): String {
            val insertedLink = "<a target=\"_blank\" href=\""
            val nameAttr = " name=\""
            val text = 0
            val tag = 1
            val value = 2
            val out = StringBuilder(rails.length)
            var state = text
            var i = 0
            while (i < rails.length) {
                if (state == value && rails.startsWith(insertedLink, i)) {
                    val textStart = rails.indexOf("\">", i) + 2
                    val textEnd = rails.indexOf("</a>", textStart)
                    out.append(rails.substring(textStart, textEnd).replace(">", "&gt;"))
                    i = textEnd + 4
                    continue
                }
                if (state == tag && rails.startsWith(nameAttr, i)) {
                    val valueEnd = rails.indexOf('"', i + nameAttr.length)
                    i = valueEnd + 1
                    continue
                }
                val c = rails[i]
                when {
                    state == text && c == '<' -> state = tag
                    state == tag && c == '>' -> state = text
                    state == tag && c == '"' -> state = value
                    state == value && c == '"' -> state = tag
                }
                when {
                    state == value && c == '<' -> out.append("&lt;")
                    state == value && c == '>' -> out.append("&gt;")
                    else -> out.append(c)
                }
                i++
            }
            return out.toString()
        }

        private val DANGEROUS_ELEMENTS = setOf(
            "script", "style", "iframe", "frame", "frameset", "object", "embed", "applet", "base", "meta", "link", "form",
            "input", "button", "textarea", "select", "svg", "math", "template", "noscript", "xmp", "plaintext", "noembed",
        )

        private val URL_ATTRIBUTES = setOf("href", "src", "action", "formaction", "poster", "cite", "background", "xlink:href", "srcset", "data")

        private fun dangerousUrl(value: String): Boolean {
            val cleaned = StringBuilder()
            var i = 0
            while (i < value.length) {
                val cp = value.codePointAt(i)
                if (Character.getType(cp) != Character.CONTROL.toInt() && !RubyText.isWhitespace(cp)) cleaned.appendCodePoint(cp)
                i += Character.charCount(cp)
            }
            val s = cleaned.toString().lowercase()
            if (s.startsWith("javascript:") || s.startsWith("vbscript:") || s.startsWith("livescript:")) return true
            if (s.startsWith("data:")) {
                val mediatype = s.substring(5).substringBefore(',').substringBefore(';')
                fun token(t: String) = t.isNotEmpty() && t.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "!#$%&'*+-.^_`|~" }
                val slash = mediatype.indexOf('/')
                val valid = slash >= 0 && token(mediatype.substring(0, slash)) && token(mediatype.substring(slash + 1))
                return valid && listOf("html", "xml", "svg", "script", "ecmascript").any { mediatype.contains(it) }
            }
            return false
        }

        /** Parses rendered output the way a browser would and returns the security violations in it. */
        fun securityViolations(html: String, allowStyle: Boolean): List<String> {
            val dom = Dom()
            val root = try {
                dom.parseFragment(html)
            } catch (_: RichTextError.Parse) {
                return listOf("unparseable output")
            }
            val violations = ArrayList<String>()
            val allowed = SafeList.autoLink
            for (node in dom.descendants(root)) {
                val name = dom.localName(node) ?: continue
                if (name in DANGEROUS_ELEMENTS) violations.add("<$name> element")
                if (!allowed.tags.contains(name)) violations.add("<$name> not in the allowlist")
                for ((attr, value) in dom.attrs(node)) {
                    if (!allowed.attributes.contains(attr) && attr != "target" && !(attr == "style" && allowStyle)) {
                        violations.add("$attr on <$name> not in the allowlist")
                    }
                    val lower = attr.lowercase()
                    if (lower.startsWith("on")) violations.add("$attr attribute on <$name>")
                    if (lower in URL_ATTRIBUTES && dangerousUrl(value)) violations.add("$attr=${quote(value)} on <$name>")
                    if (lower == "style" && !allowStyle) violations.add("style on <$name>")
                    if (lower.startsWith("data-") && lower != "data-language") violations.add("$attr on <$name>")
                }
            }
            return violations
        }
    }
}
