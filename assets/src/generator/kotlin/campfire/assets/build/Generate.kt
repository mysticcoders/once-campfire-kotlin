package campfire.assets.build

import campfire.ruby.Erb
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Propshaft's Railtie default (`config.assets.prefix`); the app doesn't change it. */
private const val PREFIX = "/assets"

/** Where the run time finds the generated resources, under the output directory. */
private const val RESOURCE_ROOT = "campfire/assets"

/**
 * Digests and compiles the reference's assets the way `bin/rails assets:precompile` does
 * (Propshaft), renders the import map, and writes the results plus reference/public as classpath
 * resources (`once-campfire-rust/crates/assets/build.rs`, which embeds the same data as
 * `$OUT_DIR/embedded.rs`).
 *
 * Arguments: the module directory (holding vendor/ and overrides/), the Rails root, and the output
 * directory. Writes, under `campfire/assets/`:
 * - `public/<url path>`: every file ActionDispatch::Static serves (reference/public plus the
 *   precompiled public/assets: the digested, compiled files and `.manifest.json`);
 * - `files.txt`: those URL paths, sorted, one per line;
 * - `manifest.tsv`: `logical\tdigested` per asset, sorted by logical path;
 * - `stylesheets.txt`: Propshaft::Helper#all_stylesheets_paths, one per line;
 * - `importmap_tags.html`: `javascript_importmap_tags`;
 * - `built_at.txt`: the build time as Time#httpdate, the files' Last-Modified.
 */
fun main(args: Array<String>) {
    val (moduleDir, railsRoot, outDir) = args.map { File(it).canonicalFile }
    val out = outDir.resolve(RESOURCE_ROOT)
    outDir.deleteRecursively()
    out.mkdirs()

    val loadPath = LoadPath(loadPathDirs(moduleDir, railsRoot), assetsVersion(railsRoot), PREFIX)
    val digested = loadPath.assets.indices.map(loadPath::digestedPath)
    val bodies = loadPath.assets.indices.map { loadPath.compiledContent(it) ?: loadPath.assets[it].content }

    val manifestJson = loadPath.assets.indices.joinToString(",", "{", "}") {
        json(loadPath.assets[it].logicalPath) + ":{\"digested_path\":" + json(digested[it]) + ",\"integrity\":null}"
    }

    val byLogical = loadPath.assets.indices.sortedBy { loadPath.assets[it].logicalPath }
    out.resolve("manifest.tsv").writeText(byLogical.joinToString("") { "${checked(loadPath.assets[it].logicalPath)}\t${checked(digested[it])}\n" })
    out.resolve("stylesheets.txt").writeText(
        byLogical.map { loadPath.assets[it].logicalPath }.filter { extname(it) == ".css" }.joinToString("") { "$it\n" },
    )

    val files = sortedMapOf<String, ByteArray>()
    val public = railsRoot.resolve("public")
    val publicFiles = public.walkTopDown().filter { it.isFile && !it.startsWith(public.resolve("assets")) }
    for (file in publicFiles.sortedWith(PATH_ORDER)) files.putIfAbsent("/" + file.relativeTo(public).invariantSeparatorsPath, file.readBytes())
    for (index in loadPath.assets.indices) files.putIfAbsent("$PREFIX/${digested[index]}", bodies[index])
    files.putIfAbsent("$PREFIX/.manifest.json", manifestJson.toByteArray(Charsets.UTF_8))
    for ((url, body) in files) out.resolve("public$url").apply { parentFile.mkdirs() }.writeBytes(body)
    out.resolve("files.txt").writeText(files.keys.joinToString("") { "${checked(it)}\n" })

    out.resolve("importmap_tags.html").writeText(importmapTags(loadPath, digested, railsRoot))
    out.resolve("built_at.txt").writeText(httpdate(buildTime()))
}

/** A name for a line-based index: one that would split a line fails the build instead. */
private fun checked(name: String): String {
    require('\t' !in name && '\n' !in name && '\r' !in name) { "asset path can't be indexed: \"$name\"" }
    return name
}

/**
 * `overrides/` first, so the port's own frontend changes shadow the reference's files of the same
 * logical path, then vendor/LOAD_PATH (written by the Rust repo's script/revendor from
 * `Rails.application.assets.load_path.paths`).
 */
private fun loadPathDirs(moduleDir: File, railsRoot: File): List<File> {
    val exported = moduleDir.resolve("vendor/LOAD_PATH").readLines().filter { it.isNotBlank() }.map { line ->
        val kind = line.substringBefore(':', "")
        val dir = line.substringAfter(':')
        when (kind) {
            "reference" -> railsRoot.resolve(dir)
            "vendor" -> moduleDir.resolve("vendor").resolve(dir)
            else -> error("vendor/LOAD_PATH: bad line \"$line\"")
        }
    }
    return listOf(moduleDir.resolve("overrides")) + exported
}

/** config/initializers/assets.rb sets `Rails.application.config.assets.version`; "1" otherwise. */
private fun assetsVersion(railsRoot: File): String {
    val initializer = railsRoot.resolve("config/initializers/assets.rb").takeIf { it.exists() }?.readText() ?: ""
    for (line in initializer.lines()) {
        if (line.trimStart().startsWith('#')) continue
        val at = line.indexOf("config.assets.version")
        if (at < 0) continue
        val value = line.substring(at + "config.assets.version".length).trimStart()
        if (!value.startsWith('=')) continue
        return value.substring(1).trim().trim('"', '\'')
    }
    return "1"
}

/**
 * Importmap::ImportmapTagsHelper#javascript_importmap_tags for the "application" entry point, with
 * no CSP nonce (the reference configures no content security policy). Missing assets are skipped
 * (Propshaft::MissingAssetError is a rescuable asset error).
 */
private fun importmapTags(loadPath: LoadPath, digested: List<String>, railsRoot: File): String {
    fun resolve(path: String) = loadPath.find(path)?.let { "$PREFIX/${digested[it]}" }
    val pins = Importmap.expand(railsRoot.resolve("config/importmap.rb"), railsRoot)

    val imports = pins.mapNotNull { pin -> resolve(pin.path)?.let { pin.name to it } }
    val json = if (imports.isEmpty()) {
        "{\n  \"imports\": {}\n}"
    } else {
        "{\n  \"imports\": {\n" + imports.joinToString(",\n") { (name, path) -> "    ${json(name)}: ${json(path)}" } + "\n  }\n}"
    }

    val preloads = LinkedHashSet<String>()
    for (pin in pins.filter { it.preload }) resolve(pin.path)?.let(preloads::add)

    return listOf(
        "<script type=\"importmap\" data-turbo-track=\"reload\">$json</script>",
        preloads.joinToString("\n") { "<link rel=\"modulepreload\" href=\"${Erb.htmlEscape(it)}\">" },
        "<script type=\"module\">import \"application\"</script>",
    ).joinToString("\n")
}

/** JSON.generate's string escaping (no script_safe, non-ASCII passed through). */
fun json(s: String): String {
    val out = StringBuilder(s.length + 2).append('"')
    for (c in s) {
        when {
            c == '"' -> out.append("\\\"")
            c == '\\' -> out.append("\\\\")
            c == '\n' -> out.append("\\n")
            c == '\r' -> out.append("\\r")
            c == '\t' -> out.append("\\t")
            c == '\b' -> out.append("\\b")
            c == '\u000c' -> out.append("\\f")
            c.code < 0x20 -> out.append("\\u%04x".format(c.code))
            else -> out.append(c)
        }
    }
    return out.append('"').toString()
}

/** SOURCE_DATE_EPOCH when set (reproducible builds), otherwise now, in epoch seconds. */
private fun buildTime(): Long = System.getenv("SOURCE_DATE_EPOCH")?.toLongOrNull() ?: Instant.now().epochSecond

/** Time#httpdate: "Sat, 26 Sep 2026 12:23:14 GMT". */
fun httpdate(epochSeconds: Long): String =
    DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).format(Instant.ofEpochSecond(epochSeconds).atOffset(ZoneOffset.UTC))
