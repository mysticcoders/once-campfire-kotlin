package campfire.assets.build

import java.io.File
import java.security.MessageDigest
import java.util.regex.Matcher
import java.util.regex.Pattern

/** One file on the load path: its logical path, where it came from, and its bytes. */
class Asset(val logicalPath: String, val source: File, val content: ByteArray)

/** Propshaft::Asset#content_type, reduced to the two types that have compilers. */
enum class Kind { CSS, JS, OTHER }

/**
 * A port of Propshaft 1.2.1's load path, digesting and compilers (the gem's lib/propshaft), run
 * at build time so the digested paths and compiled bytes match `assets:precompile`
 * (`once-campfire-rust/crates/assets/build/propshaft.rs`).
 *
 * Propshaft reads assets as ASCII-8BIT and matches bytes; the compilers decode each byte to the
 * char with the same code point (ISO-8859-1) so `java.util.regex` sees exactly those bytes, and
 * run with UNIX_LINES so `.` and `^` treat only `\n` as a line end, as Ruby does.
 */
class LoadPath(paths: List<File>, private val version: String, private val prefix: String) {
    val assets = ArrayList<Asset>()
    private val byLogicalPath = HashMap<String, Int>()
    private val digests = HashMap<Int, String>()

    private val cssAssetUrls = Pattern.compile(quotedUrl("url", "#|%23|data:|http:|https:|//"))
    private val jsAssetUrls = Pattern.compile(quotedUrl("RAILS_ASSET_URL", "#|%23|data|http|//"))
    private val sourceMappingUrls =
        Pattern.compile("(//|/\\*)# sourceMappingURL=(.+\\.map)($WS*?\\*/)?$WS*?(?=\\n?\\z)", Pattern.UNIX_LINES)
    private val urlPrefixInSourceMap = Pattern.compile("^(.+/)?" + Pattern.quote(prefix) + "/", Pattern.UNIX_LINES or Pattern.MULTILINE)
    private val alreadyDigested = Pattern.compile("-([0-9a-zA-Z_-]{7,128})\\.digested")

    init {
        load(paths)
    }

    /**
     * Propshaft::LoadPath#assets_by_path: earlier paths win for the same logical path, and
     * dotfiles are skipped (but not dot-directories). Files are visited in Rust's `PathBuf` order
     * (component-wise), as the Rust build does.
     */
    private fun load(paths: List<File>) {
        for (path in dedup(paths)) {
            if (!path.exists()) continue
            val files = ArrayList<File>()
            allFilesFromTree(path, files)
            for (file in files.sortedWith(PATH_ORDER)) {
                if (file.name.startsWith('.')) continue
                val logicalPath = file.relativeTo(path).invariantSeparatorsPath
                if (logicalPath in byLogicalPath) continue
                byLogicalPath[logicalPath] = assets.size
                assets.add(Asset(logicalPath, file, file.readBytes()))
            }
        }
    }

    /** The index of the asset with [logicalPath], if the load path has one. */
    fun find(logicalPath: String): Int? = byLogicalPath[logicalPath]

    /** Propshaft::Asset#content_type, for the compilers: CSS, JS or anything else. */
    fun kind(index: Int): Kind = when (extname(assets[index].logicalPath).removePrefix(".")) {
        "css" -> Kind.CSS
        "js" -> Kind.JS
        else -> Kind.OTHER
    }

    /**
     * Propshaft::Asset#digest: SHA-1 of the content, the content of everything it references
     * (recursively, in discovery order) and the assets version; the first 8 hex digits.
     * Memoized, since every compiled reference asks for it again.
     */
    fun digest(index: Int): String = digests.getOrPut(index) {
        val sha = MessageDigest.getInstance("SHA-1")
        sha.update(assets[index].content)
        for (referenced in referencedBy(index)) sha.update(assets[referenced].content)
        sha.update(version.toByteArray(Charsets.UTF_8))
        hex(sha.digest()).substring(0, 8)
    }

    /** Propshaft::Asset#digested_path: `name-digest.ext`, or the path itself when already digested. */
    fun digestedPath(index: Int): String {
        val logicalPath = assets[index].logicalPath
        if (alreadyDigested.matcher(logicalPath).find()) return logicalPath
        val dot = digestableExtensionStart(logicalPath) ?: return logicalPath
        return logicalPath.substring(0, dot) + "-" + digest(index) + logicalPath.substring(dot)
    }

    /** Propshaft::Compilers#compile: null when no compiler is registered for the asset's type. */
    fun compiledContent(index: Int): ByteArray? {
        val assetUrls = when (kind(index)) {
            Kind.CSS -> cssAssetUrls
            Kind.JS -> jsAssetUrls
            Kind.OTHER -> return null
        }
        val input = compileAssetUrls(index, assetUrls, latin1Decode(assets[index].content))
        return latin1Encode(compileSourceMappingUrls(index, input))
    }

    /** What [digest] covers besides the asset itself: CssAssetUrls / JsAssetUrls#referenced_by. */
    private fun referencedBy(index: Int): List<Int> {
        val pattern = when (kind(index)) {
            Kind.CSS -> cssAssetUrls
            Kind.JS -> jsAssetUrls
            Kind.OTHER -> return emptyList()
        }
        val references = ArrayList<Int>()
        collectReferences(index, pattern, references)
        return references
    }

    /** Referenced assets are scanned with the same pattern whatever their own type is. */
    private fun collectReferences(index: Int, pattern: Pattern, references: MutableList<Int>) {
        val content = latin1Decode(assets[index].content)
        val directory = dirname(assets[index].logicalPath)
        val matcher = pattern.matcher(content)
        while (matcher.find()) {
            val url = latin1ToUtf8(matcher.group(1))
            val referenced = find(resolvePath(directory, url)) ?: continue
            if (referenced !in references) {
                references.add(referenced)
                collectReferences(referenced, pattern, references)
            }
        }
    }

    private val urlPrefix: String get() = prefix.trimEnd('/')

    /** CssAssetUrls#compile / JsAssetUrls#compile: found references become their digested URLs. */
    private fun compileAssetUrls(index: Int, pattern: Pattern, input: String): String {
        val directory = dirname(assets[index].logicalPath)
        val isCss = kind(index) == Kind.CSS
        return gsub(pattern, input) { m ->
            val url = latin1ToUtf8(m.group(1))
            val fingerprint = m.group(2) ?: ""
            val found = find(resolvePath(directory, url))
            val quoted = if (found != null) {
                "\"" + urlPrefix + "/" + digestedPath(found) + latin1ToUtf8(fingerprint) + "\""
            } else {
                "\"" + url + "\""
            }
            utf8ToLatin1(if (isCss) "url($quoted)" else quoted)
        }
    }

    /** SourceMappingUrls#compile: a known map gets its digested URL, an unknown one is dropped. */
    private fun compileSourceMappingUrls(index: Int, input: String): String {
        val logicalPath = assets[index].logicalPath
        return gsub(sourceMappingUrls, input) { m ->
            val commentStart = m.group(1)
            val commentEnd = m.group(3) ?: ""
            val url = urlPrefixInSourceMap.matcher(latin1ToUtf8(m.group(2))).replaceAll("")
            val directory = dirname(logicalPath)
            val resolved = if (directory == ".") url else plus(directory, url)
            val found = find(resolved)
            if (found != null) {
                commentStart + "# sourceMappingURL=" + urlPrefix + "/" + utf8ToLatin1(digestedPath(found)) + commentEnd
            } else {
                commentStart + commentEnd
            }
        }
    }

    companion object {
        /** Ruby's `\s` is `[ \t\n\v\f\r]`; spelled out so the Latin-1 decoded input can't match more. */
        private const val WS = "[ \\t\\n\\x0B\\x0C\\r]"

        /** CssAssetUrls / JsAssetUrls::ASSET_URL_PATTERN, which differ in the head and the exclusions. */
        private fun quotedUrl(head: String, excluded: String) =
            "$head\\($WS*[\"']?(?!(?:$excluded))([^\"' \\t\\n\\x0B\\x0C\\r?#)]+)([#?][^\"')]+)?$WS*[\"']?\\)"
    }
}

/** Rust's `PathBuf` ordering: component by component, which is byte order with "/" below everything. */
val PATH_ORDER: Comparator<File> = compareBy { it.path.replace('/', '\u0000') }

/** `gsub` with a block: each match replaced by [replace]'s result, taken literally. */
private fun gsub(pattern: Pattern, input: String, replace: (Matcher) -> String): String {
    val matcher = pattern.matcher(input)
    val output = StringBuilder(input.length)
    var last = 0
    while (matcher.find()) {
        output.append(input, last, matcher.start())
        output.append(replace(matcher))
        last = matcher.end()
    }
    output.append(input, last, input.length)
    return output.toString()
}

/**
 * Propshaft::LoadPath#dedup: drop paths nested in (string-prefixed by) an earlier sorted path,
 * keeping the original order. Pathname sorts with "/" below every other character.
 */
fun dedup(paths: List<File>): List<File> {
    val deduped = ArrayList<String>()
    for (path in paths.map { it.path }.sortedBy { it.replace('/', '\u0000') }) {
        if (deduped.isEmpty() || !path.startsWith(deduped.last())) deduped.add(path)
    }
    val seen = HashSet<String>()
    return paths.filter { it.path in deduped && seen.add(it.path) }
}

/** Every file under [path], recursively, in directory-listing order. */
private fun allFilesFromTree(path: File, files: MutableList<File>) {
    for (child in path.listFiles()!!) {
        if (child.isDirectory) allFilesFromTree(child, files) else files.add(child)
    }
}

/** The start of the extension that `sub(/\.(\w+(\.map)?)$/)` replaces in Asset#digested_path. */
fun digestableExtensionStart(path: String): Int? {
    fun isWord(s: String) = s.isNotEmpty() && s.all { it.isLetterOrDigit() || it == '_' }
    for ((dot, c) in path.withIndex()) {
        if (c != '.') continue
        val rest = path.substring(dot + 1)
        if (isWord(rest) || (rest.endsWith(".map") && isWord(rest.removeSuffix(".map")))) return dot
    }
    return null
}

/** Ruby's File.extname. */
fun extname(path: String): String {
    val trimmed = path.substringAfterLast('/').trimStart('.')
    val dot = trimmed.lastIndexOf('.')
    return if (dot < 0) "" else trimmed.substring(dot)
}

/** Pathname#dirname for relative logical paths. */
fun dirname(path: String): String {
    val slash = path.lastIndexOf('/')
    return if (slash < 0) "." else path.substring(0, slash)
}

/** CssAssetUrls#resolve_path (JsAssetUrls has the same one). */
fun resolvePath(directory: String, filename: String): String = when {
    filename.startsWith("../") -> cleanpath(plus(directory, filename))
    filename.startsWith("/") -> filename.substring(1)
    else -> plus(directory, filename.removePrefix("./"))
}

/**
 * Pathname#+ for relative paths: leading "." and ".." of the right side are resolved against the
 * left side; anything after the first ordinary component is kept as written.
 */
fun plus(left: String, right: String): String {
    val prefix = left.split('/').filter { it.isNotEmpty() }.toMutableList()
    val suffix = right.split('/').filter { it.isNotEmpty() }.toMutableList()
    val kept = ArrayList<String>()
    while (true) {
        while (suffix.firstOrNull() == ".") suffix.removeAt(0)
        val last = prefix.removeLastOrNull() ?: break
        if (last == ".") continue
        if (last == ".." || suffix.firstOrNull() != "..") {
            kept.add(last)
            break
        }
        suffix.removeAt(0)
    }
    prefix.addAll(kept)
    val joined = prefix + suffix
    return if (joined.isEmpty()) "." else joined.joinToString("/")
}

/** Pathname#cleanpath (non-conservative) for relative paths. */
fun cleanpath(path: String): String {
    val out = ArrayList<String>()
    for (component in path.split('/')) {
        when {
            component.isEmpty() || component == "." -> {}
            component == ".." && out.isNotEmpty() && out.last() != ".." -> out.removeLast()
            else -> out.add(component)
        }
    }
    return if (out.isEmpty()) "." else out.joinToString("/")
}

private fun latin1Decode(bytes: ByteArray) = String(bytes, Charsets.ISO_8859_1)

private fun latin1Encode(s: String) = s.toByteArray(Charsets.ISO_8859_1)

private fun latin1ToUtf8(s: String) = String(latin1Encode(s), Charsets.UTF_8)

private fun utf8ToLatin1(s: String) = latin1Decode(s.toByteArray(Charsets.UTF_8))

/** Lowercase hex of [bytes]. */
fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
