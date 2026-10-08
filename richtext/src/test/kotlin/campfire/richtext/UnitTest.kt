package campfire.richtext

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The remaining `#[cfg(test)]` tests of `once-campfire-rust/crates/richtext/src`: filters.rs,
 * attachables.rs, autolink.rs, ruby.rs and uri.rs.
 */
class UnitTest {
    /** filters.rs: `RemoveSoloUnfurledLinkText#normalize_tweet_url` in the reference. */
    @Test
    fun normalizesTweetUrlsLikeRails() {
        for ((url, normalized) in listOf(
            "HTTPS://x.com/dhh/status/1?s=20" to "https://twitter.com/dhh/status/1",
            "https://x.com/dhh/status/1?s=20" to "https://twitter.com/dhh/status/1",
            "HTTP://twitter.com/a" to "http://twitter.com/a",
            "Https://X.com/dhh/status/1" to "Https://X.com/dhh/status/1",
        )) {
            assertEquals(normalized, Filters.normalizeTweetUrl(url), url)
        }
    }

    /** attachables.rs: `GlobalID.find` over the records the vectors name (users 1 and 2; the default locator ignores the app). */
    private object VectorRecords : AttachableResolver {
        override fun locateSigned(sgid: String): SignedLookup = SignedLookup.Invalid

        override fun findGid(gid: String): GidLookup {
            if (!gid.startsWith("gid://")) return GidLookup.NotFound
            val path = gid.removePrefix("gid://").substringBefore('?')
            val slash = path.indexOf('/')
            if (slash < 0) return GidLookup.NotFound
            val rest = path.substring(slash + 1)
            if (!rest.startsWith("User/")) return GidLookup.OtherModel
            return when (val id = rest.removePrefix("User/")) {
                "1", "2" -> GidLookup.User(MentionUser(id.toLong(), "", "", "", "", ""))
                else -> GidLookup.NotFound
            }
        }
    }

    /**
     * attachables.rs: the reference's answers for SGIDs whose signature doesn't verify
     * (`unverified_sgids` in `vectors/rails_compat.json`, from `reference-tools/rails_compat_vectors.rb`).
     */
    @Test
    fun possiblyExpiredSgidsFindUsersLikeRails() {
        val vectors = ObjectMapper().readTree(File(System.getProperty("campfire.vectors"), "rails_compat.json"))
        val ctx = RenderContext(VectorRecords, null)
        val cases = vectors["unverified_sgids"]
        assertTrue(cases.size() > 0)
        for (case in cases) {
            val label = case["case"].toString()
            val sgid = case["sgid"]?.takeIf { !it.isNull }?.asText()
            val expected = case["expected"]
            when {
                expected == null || expected.isNull -> assertNull(Attachables.attachableFromPossiblyExpiredSgid(sgid, ctx)?.id, label)
                expected.isTextual -> assertEquals(
                    expected.asText().substringAfterLast('/').toLong(),
                    Attachables.attachableFromPossiblyExpiredSgid(sgid, ctx)?.id,
                    label,
                )
                else -> assertFailsWith<RichTextError>("$label should raise like Rails ($expected)") {
                    Attachables.attachableFromPossiblyExpiredSgid(sgid, ctx)
                }
            }
        }
    }

    /**
     * autolink.rs: rails_autolink's `auto_linked?`, as regular expressions over the whole of
     * `left`, in Java's syntax for the regex crate's semantics (`.` and `$` stop only at `\n`,
     * `\b` is Unicode's).
     */
    private fun autoLinkedByRegex(left: String, right: String): Boolean {
        val openTagAtLineEnd = Regex("(?md)<[^>]+$")
        val closesTag = Regex("(?md)^[^>]*>")
        val openAnchor = Regex("(?dU)<[aA]\\b.*?>").toPattern()
        val closeAnchor = Regex("</[aA]>")
        if (openTagAtLineEnd.containsMatchIn(left) && closesTag.containsMatchIn(right)) return true
        val matcher = openAnchor.matcher(left)
        for (i in left.length - 1 downTo 0) {
            matcher.region(i, left.length)
            if (matcher.lookingAt()) return !closeAnchor.containsMatchIn(left.substring(matcher.end()))
        }
        return false
    }

    @Test
    fun tagIndexAnswersAsTheRegularExpressionsDo() {
        for (text in listOf(
            "<p>www.a.com</p><p>b</p>",
            "<p title=\"a\nb\">x</p> y <p>z</p>",
            "<p title=\"a\n\">x</p>",
            "<a href=\"x\">in <b>link</b></a> out <A\nhref=\"y\">z</A> <a>q</a>",
            "<ab>x</ab><a\tclass=\"c\">y",
            "x<\ny>z<é>",
            "<",
            "<a>",
            "",
        )) {
            val index = Autolink.TagIndex(text)
            for (start in 0..text.length) {
                for (end in start..text.length) {
                    assertEquals(
                        autoLinkedByRegex(text.substring(0, start), text.substring(end)),
                        index.autoLinked(start, end),
                        "${ObjectMapper().writeValueAsString(text)} at $start..$end",
                    )
                }
            }
        }
    }

    /** ruby.rs. */
    @Test
    fun matchesRuby() {
        assertEquals("a", RubyText.chompNewlines("a\r\n\r\n"))
        assertEquals("a\n\r", RubyText.chompNewlines("a\n\r"))
        val parsed = RubyText.jsonParse("{\"a\": [1.5e16, 0.1]}")
        assertEquals("{\"a\" => [1.5e+16, 0.1]}", RubyText.jsonValueInspect(parsed))
        assertEquals("\"\\#{a} \\#\$b \\#@c # #\"", RubyText.stringInspect("#{a} #\$b #@c # #"))
        assertEquals(1L, (RubyText.jsonParse("{\"a\":1/*c*/}") as Map<*, *>)["a"])
        assertTrue(RubyText.jsonParse("{\"a\":1,}") === RubyText.NOT_JSON)
        val truncated = RubyText.truncate("a".repeat(300), 280, "…")
        assertEquals(280, truncated.codePointCount(0, truncated.length))
    }

    /** uri.rs. */
    @Test
    fun parsesLikeRuby() {
        val uri = assertIs<RubyUri.Parsed.Ok>(RubyUri.parse("https://x.com/dhh/status/1?s=20")).uri
        assertEquals("x.com", uri.host)
        assertEquals("s=20", uri.query)
        assertTrue(RubyUri.parse("http://exa mple.com/ ") !is RubyUri.Parsed.Ok)
        assertNull(assertIs<RubyUri.Parsed.Ok>(RubyUri.parse("https:/rooms/1")).uri.host)
        assertEquals("rooms/1", assertIs<RubyUri.Parsed.Ok>(RubyUri.parse("https:rooms/1")).uri.opaque)
        assertEquals("", assertIs<RubyUri.Parsed.Ok>(RubyUri.parse("https://")).uri.host)
        assertEquals("[::1]", assertIs<RubyUri.Parsed.Ok>(RubyUri.parse("http://[::1]/x")).uri.host)
        assertEquals(RubyUri.Parsed.InvalidComponent, RubyUri.parse("mailto:foo"))
        assertIs<RubyUri.Parsed.Ok>(RubyUri.parse("mailto:a@b.com"))
        assertEquals("https://x.com/a", assertIs<RubyUri.Parsed.Ok>(RubyUri.parse("https://x.com:443/a")).uri.toS())
    }

    /** uri.rs: `URI.parse(s)` in the reference, where `scheme=` downcases. */
    @Test
    fun downcasesTheSchemeLikeRuby() {
        assertEquals("https", assertIs<RubyUri.Parsed.Ok>(RubyUri.parse("HTTPS://x.com/a")).uri.scheme)
        assertEquals("http://X.com/a", assertIs<RubyUri.Parsed.Ok>(RubyUri.parse("HTTP://X.com:80/a")).uri.toS())
        assertEquals("file:///etc", assertIs<RubyUri.Parsed.Ok>(RubyUri.parse("FILE:///etc")).uri.toS())
        assertEquals("mailto:a@b.com", assertIs<RubyUri.Parsed.Ok>(RubyUri.parse("MailTo:a@b.com")).uri.toS())
    }
}
