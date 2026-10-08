package campfire.assets

import campfire.ruby.Erb

/**
 * Propshaft::MissingAssetError, raised by `compute_asset_path` for an unknown logical path
 * (`once-campfire-rust/crates/assets/src/helpers.rs`).
 */
class MissingAssetError(val logicalPath: String) : RuntimeException("The asset '$logicalPath' was not found in the load path.")

/**
 * What `stylesheet_link_tag` renders, plus the preload links Rails adds to the response's `link`
 * header while rendering it (`config.action_view.preload_links_header`, on by default)
 * (`once-campfire-rust/crates/assets/src/tags.rs`).
 */
data class StylesheetTags(val html: String, val preloadLinks: List<String>)

/**
 * The reference's asset pipeline, precomputed at build time and loaded from the classpath
 * (`once-campfire-rust/crates/assets/src/lib.rs`).
 *
 * The build (`generateAssets`) digests and compiles reference/app/assets, app/javascript,
 * vendor/javascript and the gem assets in `assets/vendor` exactly as Propshaft's
 * `assets:precompile` does, with `assets/overrides` shadowing them, renders the import map from
 * config/importmap.rb, and copies reference/public.
 *
 * - [path] and friends: ActionView's asset URL helpers (AssetUrlHelper) over Propshaft's static
 *   resolver, with no asset host or relative_url_root, as the reference is configured.
 * - [stylesheetTags], [importmapTags] and [linkHeader]: the layout's head tags
 *   (`stylesheet_link_tag :all, "data-turbo-track": "reload"` and `javascript_importmap_tags`) and
 *   the preload `link` header Rails sends alongside them.
 * - [serve]: ActionDispatch::Static over public/ and the precompiled public/assets.
 */
object Assets {
    /** The URL prefix digested assets are served under (`config.assets.prefix`). */
    const val PREFIX = "/assets"

    /** Rails' MAX_HEADER_SIZE for the preload `link` header (ActionView::Helpers::AssetTagHelper). */
    private const val MAX_LINK_HEADER_SIZE = 1_000

    private val digested: Map<String, String> = Embedded.manifest.toMap()

    /** Every (logical path, digested path) pair, sorted by logical path (`lib.rs`, `manifest`). */
    val manifest: List<Pair<String, String>> get() = Embedded.manifest

    /** Propshaft's manifest, `{"logical": {"digested_path": ..., "integrity": null}}`, as served from `/assets/.manifest.json`. */
    val manifestJson: String get() = Embedded.manifestJson

    /** Propshaft::Helper#all_stylesheets_paths: every CSS logical path on the load path, sorted (`tags.rs`). */
    val allStylesheetPaths: List<String> get() = Embedded.stylesheets

    /** `javascript_importmap_tags`: the import map, a modulepreload link per pin and the `import "application"` module script (`tags.rs`). */
    val importmapTagsHtml: String get() = Embedded.importmapTags

    /** [importmapTagsHtml] as UTF-8, pre-encoded for the layout. */
    val importmapTags: ByteArray = Embedded.importmapTags.toByteArray(Charsets.UTF_8)

    /** The layout's `stylesheet_link_tag :all, "data-turbo-track": "reload"`. */
    val stylesheetTagsAll: StylesheetTags = stylesheetLinkTagAll(listOf("data-turbo-track" to "reload"))

    /** [stylesheetTagsAll]'s HTML as UTF-8, pre-encoded for the layout. */
    val stylesheetTags: ByteArray = stylesheetTagsAll.html.toByteArray(Charsets.UTF_8)

    /** The `link` header the layout's stylesheets add to an otherwise link-less response. */
    val linkHeader: String = appendPreloadLinks("", stylesheetTagsAll.preloadLinks)

    /** The digested path (relative to `/assets/`) for a logical path, from the manifest (`helpers.rs`, `digested_path`). */
    fun digestedPath(logicalPath: String): String? = digested[logicalPath]

    /**
     * `asset_path(source)`: "/assets/<digested>" for pipeline assets; URLs and absolute paths pass
     * through; a `?query` or `#fragment` tail is kept. Throws [MissingAssetError] for an unknown
     * logical path, the way Rails raises Propshaft::MissingAssetError while rendering
     * (`helpers.rs`, `asset_path`).
     */
    fun path(source: String): String =
        if (source.startsWith('/')) compute(source, null) else PATHS.getOrPut(source) { compute(source, null) }

    /** Logical sources resolved by [path]: the digest map can't change while the process runs. */
    private val PATHS = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** [path], as a [Result] holding the [MissingAssetError] instead of throwing it (`helpers.rs`, `try_asset_path`). */
    fun tryAssetPath(source: String): Result<String> = runCatching { compute(source, null) }

    /** `image_path`: [path]. */
    fun imagePath(source: String): String = path(source)

    /** `audio_path`: [path]. */
    fun audioPath(source: String): String = path(source)

    /** `javascript_path`: appends ".js" unless the source already ends in it. */
    fun javascriptPath(source: String): String = compute(source, ".js")

    /** `stylesheet_path`: appends ".css" unless the source already ends in it. */
    fun stylesheetPath(source: String): String = compute(source, ".css")

    /**
     * `asset_url(source)`: the path joined onto the request's base URL (e.g. "https://host:3000"),
     * which is what Rails uses as the host when no asset_host is configured (`helpers.rs`).
     */
    fun assetUrl(baseUrl: String, source: String): String {
        val path = path(source)
        return if (path.isEmpty() || isUri(path)) path else baseUrl.trimEnd('/') + "/" + path.trimStart('/')
    }

    /** `image_url`: [assetUrl]. */
    fun imageUrl(baseUrl: String, source: String): String = assetUrl(baseUrl, source)

    /**
     * `stylesheet_link_tag *sources, **options` as Propshaft::Helper renders it: one Rails
     * `stylesheet_link_tag` per source, joined by newlines (`tags.rs`). A missing source throws
     * [MissingAssetError], like Rails raises.
     */
    fun stylesheetLinkTag(sources: List<String>, options: List<Pair<String, String>>): StylesheetTags {
        val html = ArrayList<String>(sources.size)
        val preloadLinks = ArrayList<String>(sources.size)
        for (source in sources) {
            val href = stylesheetPath(source)
            if (href.isNotEmpty() && !href.startsWith("data:")) preloadLinks.add("<$href>; rel=preload; as=style; nopush")
            val tag = StringBuilder("<link rel=\"stylesheet\" href=\"").append(Erb.htmlEscape(href)).append('"')
            for ((name, value) in options) tag.append(' ').append(name).append("=\"").append(Erb.htmlEscape(value)).append('"')
            html.add(tag.append(" />").toString())
        }
        return StylesheetTags(html.joinToString("\n"), preloadLinks)
    }

    /** `stylesheet_link_tag :all, **options` (`tags.rs`, `stylesheet_link_tag_all`). */
    fun stylesheetLinkTagAll(options: List<Pair<String, String>>): StylesheetTags = stylesheetLinkTag(allStylesheetPaths, options)

    /**
     * Appends preload links to a response's existing `link` header value the way
     * `send_preload_links_header` does: a link that would push the header past 1,000 bytes is
     * left out (Propshaft renders each stylesheet separately, so later, shorter ones can still
     * fit) (`tags.rs`, `append_preload_links`).
     */
    fun appendPreloadLinks(header: String, preloadLinks: List<String>): String {
        val out = StringBuilder(header)
        for (link in preloadLinks) {
            if (out.length + link.length > MAX_LINK_HEADER_SIZE) continue
            if (out.isNotEmpty()) out.append(',')
            out.append(link)
        }
        return out.toString()
    }

    /**
     * ActionDispatch::Static's FileHandler#attempt: the response when a public file matches a GET
     * or HEAD request, null to hand the request to the app (`serve.rs`, `serve`).
     */
    fun serve(request: StaticRequest): Served? = StaticFiles.serve(request)

    /**
     * AssetUrlHelper#asset_path's core (`helpers.rs`, `compute`): split off a `?`/`#` tail, add
     * [extname] when missing, and resolve relative sources through the manifest.
     */
    private fun compute(source: String, extname: String?): String {
        if (source.isEmpty()) return ""
        if (isUri(source)) return source
        val tailAt = source.indexOfFirst { it == '?' || it == '#' }.takeIf { it >= 0 && it + 1 < source.length }
        var path = if (tailAt == null) source else source.substring(0, tailAt)
        val tail = if (tailAt == null) "" else source.substring(tailAt)
        if (extname != null && fileExtname(path) != extname) path += extname
        if (!path.startsWith('/')) path = PREFIX + "/" + (digested[path] ?: throw MissingAssetError(path))
        return path + tail
    }

    /** ActionView::Helpers::AssetUrlHelper::URI_REGEXP: `%r{^[-a-z]+://|^(?:cid|data):|^//}i`. */
    private fun isUri(source: String): Boolean {
        val lower = String(CharArray(source.length) { source[it].let { c -> if (c in 'A'..'Z') c + 32 else c } })
        if (lower.startsWith("//") || lower.startsWith("cid:") || lower.startsWith("data:")) return true
        val scheme = lower.indexOf("://")
        return scheme > 0 && (0 until scheme).all { lower[it] == '-' || lower[it] in 'a'..'z' }
    }

    /** Ruby's File.extname. */
    private fun fileExtname(path: String): String {
        val trimmed = path.substringAfterLast('/').trimStart('.')
        val dot = trimmed.lastIndexOf('.')
        return if (dot < 0) "" else trimmed.substring(dot)
    }
}
