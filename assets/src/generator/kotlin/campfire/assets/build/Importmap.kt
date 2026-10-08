package campfire.assets.build

import java.io.File

/** A resolved pin: the module name, the logical path it points at, and whether it's preloaded. */
class Pin(val name: String, val path: String, val preload: Boolean)

/**
 * A port of importmap-rails 2.2.2's Importmap::Map (lib/importmap/map.rb) for the subset of the
 * config/importmap.rb DSL the reference uses: `pin` and `pin_all_from` with `to:`, `under:` and
 * `preload:` (`once-campfire-rust/crates/assets/build/importmap.rs`).
 */
object Importmap {
    private sealed interface Entry {
        class PinEntry(val name: String, val to: String?, val preload: Boolean) : Entry
        class Dir(val dir: String, val under: String?, val to: String?, val preload: Boolean) : Entry
    }

    private sealed interface Value {
        class Str(val value: String) : Value
        class Bool(val value: Boolean) : Value
    }

    /**
     * Importmap::Map#expanded_packages_and_directories: pins in insertion order, then every
     * directory expanded; a later entry for an existing name keeps its position (a Ruby Hash).
     */
    fun expand(importmapRb: File, railsRoot: File): List<Pin> {
        val packages = ArrayList<Pin>()
        val directories = ArrayList<Entry.Dir>()
        for (entry in importmapRb.readText().lines().mapNotNull(::parseLine)) {
            when (entry) {
                is Entry.PinEntry -> insert(packages, Pin(entry.name, entry.to ?: "${entry.name}.js", entry.preload))
                is Entry.Dir -> {
                    directories.removeAll { it.dir == entry.dir }
                    directories.add(entry)
                }
            }
        }
        for (dir in directories) {
            val root = railsRoot.resolve(dir.dir)
            if (!root.exists()) continue
            val files = ArrayList<File>()
            javascriptFilesInTree(root, files)
            for (file in files.sortedWith(PATH_ORDER)) {
                val filename = file.relativeTo(root).invariantSeparatorsPath
                val name = moduleNameFrom(filename, dir.under)
                val path = listOfNotNull(dir.to ?: dir.under, filename).filter { it.isNotEmpty() }.joinToString("/")
                insert(packages, Pin(name, path, dir.preload))
            }
        }
        return packages
    }

    /** A Ruby Hash assignment: an existing name is replaced in place, a new one goes last. */
    private fun insert(packages: MutableList<Pin>, pin: Pin) {
        val existing = packages.indexOfFirst { it.name == pin.name }
        if (existing >= 0) packages[existing] = pin else packages.add(pin)
    }

    /** `[under, filename.chomp(extname).remove(/(?:\/|^)index$/).presence].compact.join("/")` */
    fun moduleNameFrom(filename: String, under: String?): String {
        val stem = filename.removeSuffix(extname(filename)).let { if (it == "index") "" else it.removeSuffix("/index") }
        return listOfNotNull(under, stem.ifEmpty { null }).joinToString("/")
    }

    /** `Dir[path.join("**" + "/" + "*.js{,m}")]` (every .js or .jsm file below): Dir globs skip dotfiles and dot-directories. */
    private fun javascriptFilesInTree(dir: File, files: MutableList<File>) {
        for (path in dir.listFiles()!!) {
            if (path.name.startsWith('.')) continue
            if (path.isDirectory) {
                javascriptFilesInTree(path, files)
            } else if (path.name.endsWith(".js") || path.name.endsWith(".jsm")) {
                files.add(path)
            }
        }
    }

    /** One statement of importmap.rb, or null for a blank or comment line; anything else fails the build. */
    private fun parseLine(raw: String): Entry? {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith('#')) return null
        val split = line.indexOfFirst { it.isWhitespace() }
        val command = if (split < 0) line else line.substring(0, split)
        val args = Args(if (split < 0) "" else line.substring(split).trimStart())
        val first = args.string() ?: error("importmap.rb: expected a string in \"$line\"")
        var to: String? = null
        var under: String? = null
        var preload = true
        while (true) {
            val (key, value) = args.option() ?: break
            when {
                key == "to" && value is Value.Str -> to = value.value
                key == "under" && value is Value.Str -> under = value.value
                key == "preload" && value is Value.Bool -> preload = value.value
                else -> error("importmap.rb: unsupported option \"$key\" in \"$line\"")
            }
        }
        return when (command) {
            "pin" -> Entry.PinEntry(first, to, preload)
            "pin_all_from" -> Entry.Dir(first, under, to, preload)
            else -> error("importmap.rb: unsupported statement \"$command\"")
        }
    }

    /** The arguments after a statement's name: a quoted string, then `, key: value` options. */
    private class Args(var rest: String) {
        /** A single- or double-quoted string (no escapes), or null when [rest] doesn't start with one. */
        fun string(): String? {
            val quote = rest.firstOrNull()?.takeIf { it == '"' || it == '\'' } ?: return null
            val end = rest.indexOf(quote, 1).takeIf { it >= 0 } ?: return null
            val value = rest.substring(1, end)
            rest = rest.substring(end + 1).trimStart()
            return value
        }

        /** The next `, key: value` option, or null at the end of the line or a trailing comment. */
        fun option(): Pair<String, Value>? {
            if (rest.isEmpty() || rest.startsWith('#')) return null
            check(rest.startsWith(',')) { "importmap.rb: can't parse \"$rest\"" }
            val afterComma = rest.substring(1).trimStart()
            val colon = afterComma.indexOf(':')
            check(colon >= 0) { "importmap.rb: expected key: value" }
            val key = afterComma.substring(0, colon)
            rest = afterComma.substring(colon + 1).trimStart()
            val string = string()
            val value = when {
                string != null -> Value.Str(string)
                rest.startsWith("true") -> Value.Bool(true).also { rest = rest.removePrefix("true").trimStart() }
                rest.startsWith("false") -> Value.Bool(false).also { rest = rest.removePrefix("false").trimStart() }
                else -> error("importmap.rb: unsupported value \"$rest\"")
            }
            return key.trim() to value
        }
    }
}
