package campfire.richtext.html

import campfire.richtext.Ns
import campfire.richtext.QualName

/**
 * html5ever 0.35's tag sets (`vendor/html5ever/src/tree_builder/tag_sets.rs`): predicates over an
 * element's name that the tree construction rules test the stack of open elements against.
 */
internal object TagSets {
    /** A set of HTML element names, optionally extending another predicate. */
    class HtmlSet(private val names: Set<String>, private val base: ((QualName) -> Boolean)? = null, private val excluded: Boolean = false) :
        (QualName) -> Boolean {
        override fun invoke(name: QualName): Boolean {
            if (name.ns == Ns.HTML && name.local in names) return !excluded
            return base?.invoke(name) ?: false
        }
    }

    private fun set(vararg names: String) = HtmlSet(names.toHashSet())

    private fun plus(base: (QualName) -> Boolean, vararg names: String) = HtmlSet(names.toHashSet(), base)

    private fun minus(base: (QualName) -> Boolean, vararg names: String) = HtmlSet(names.toHashSet(), base, excluded = true)

    private val htmlDefaultScope = set("applet", "caption", "html", "table", "td", "th", "marquee", "object", "template")

    /** `default_scope`, which includes MathML text and SVG HTML integration points. */
    val defaultScope: (QualName) -> Boolean = { n -> htmlDefaultScope(n) || mathmlTextIntegrationPoint(n) || svgHtmlIntegrationPoint(n) }

    val listItemScope = plus(defaultScope, "ol", "ul")
    val buttonScope = plus(defaultScope, "button")
    val tableScope = set("html", "table", "template")
    val selectScope = minus({ true }, "optgroup", "option")
    val tableBodyContext = set("tbody", "tfoot", "thead", "template", "html")
    val tableRowContext = set("tr", "template", "html")
    val tdTh = set("td", "th")
    val cursoryImpliedEnd = set("dd", "dt", "li", "option", "optgroup", "p", "rb", "rp", "rt", "rtc")
    val thoroughImpliedEnd = plus(cursoryImpliedEnd, "caption", "colgroup", "tbody", "td", "tfoot", "th", "thead", "tr")
    val headingTag = set("h1", "h2", "h3", "h4", "h5", "h6")
    val specialTag = set(
        "address", "applet", "area", "article", "aside", "base", "basefont", "bgsound", "blockquote", "body",
        "br", "button", "caption", "center", "col", "colgroup", "dd", "details", "dir", "div", "dl", "dt", "embed",
        "fieldset", "figcaption", "figure", "footer", "form", "frame", "frameset", "h1", "h2", "h3", "h4", "h5",
        "h6", "head", "header", "hgroup", "hr", "html", "iframe", "img", "input", "isindex", "li", "link",
        "listing", "main", "marquee", "menu", "meta", "nav", "noembed", "noframes", "noscript",
        "object", "ol", "p", "param", "plaintext", "pre", "script", "section", "select", "source", "style",
        "summary", "table", "tbody", "td", "template", "textarea", "tfoot", "th", "thead", "title", "tr", "track",
        "ul", "wbr", "xmp",
    )

    /** `extra_special` in the `<li>`/`<dd>`/`<dt>` rule: special elements but address, div and p. */
    val extraSpecial = minus(specialTag, "address", "div", "p")

    /** `foster_target` in `appropriate_place_for_insertion`. */
    val fosterTarget = set("table", "tbody", "tfoot", "thead", "tr")

    /** `table_outer` in `process_chars_in_table`. */
    val tableOuterChars = set("table", "tbody", "tfoot", "thead", "tr")

    /** `table_outer` in the in-table-body rules. */
    val tableOuterBody = set("table", "tbody", "tfoot")

    /** `implied` in `close_p_element`: the cursory implied end tags but `p`. */
    val impliedButP = minus(cursoryImpliedEnd, "p")

    /** `mathml_text_integration_point`. */
    fun mathmlTextIntegrationPoint(n: QualName): Boolean =
        n.ns == Ns.MATHML && (n.local == "mi" || n.local == "mo" || n.local == "mn" || n.local == "ms" || n.local == "mtext")

    /** `svg_html_integration_point` (annotation-xml is handled where it matters). */
    fun svgHtmlIntegrationPoint(n: QualName): Boolean =
        n.ns == Ns.SVG && (n.local == "foreignObject" || n.local == "desc" || n.local == "title")
}
