package campfire.assets

/**
 * The resources `generateAssets` wrote at build time (`assets/src/generator`), loaded from the
 * classpath once: the Kotlin counterpart of the Rust crate's `embedded` module
 * (`once-campfire-rust/crates/assets/build.rs`, `$OUT_DIR/embedded.rs`).
 */
internal object Embedded {
    private const val ROOT = "/campfire/assets/"

    /** (logical path, digested path), sorted by logical path. */
    val manifest: List<Pair<String, String>> = lines("manifest.tsv").map { it.substringBefore('\t') to it.substringAfter('\t') }

    /** Propshaft::Helper#all_stylesheets_paths: every CSS logical path, sorted. */
    val stylesheets: List<String> = lines("stylesheets.txt")

    /** Every file ActionDispatch::Static can serve, by URL path. */
    val files: Map<String, ByteArray> = lines("files.txt").associateWith { bytes("public$it") }

    /** Propshaft's `.manifest.json`, in load-path order. */
    val manifestJson: String = String(files.getValue("${Assets.PREFIX}/.manifest.json"), Charsets.UTF_8)

    /** `javascript_importmap_tags`, rendered at build time from config/importmap.rb. */
    val importmapTags: String = text("importmap_tags.html")

    /** The build time as Time#httpdate: every file's Last-Modified. */
    val builtAt: String = text("built_at.txt")

    /** A generated resource's bytes; a missing one means the build didn't run `generateAssets`. */
    private fun bytes(name: String): ByteArray =
        (Embedded::class.java.getResourceAsStream(ROOT + name) ?: error("missing generated asset resource $ROOT$name")).use { it.readAllBytes() }

    private fun text(name: String) = String(bytes(name), Charsets.UTF_8)

    private fun lines(name: String) = text(name).split('\n').filter { it.isNotEmpty() }
}
