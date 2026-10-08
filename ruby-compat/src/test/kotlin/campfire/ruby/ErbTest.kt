package campfire.ruby

import kotlin.test.Test
import kotlin.test.assertEquals

/** `once-campfire-rust/crates/ruby/src/erb.rs` tests. */
class ErbTest {
    @Test
    fun escapesLikeErbUtil() {
        assertEquals("&lt;&amp;&gt;&quot;&#39;x é", Erb.htmlEscape("<&>\"'x é"))
        val out = StringBuilder("a")
        Erb.appendHtmlEscaped(out, "<b>")
        assertEquals("a&lt;b&gt;", out.toString())
    }
}
