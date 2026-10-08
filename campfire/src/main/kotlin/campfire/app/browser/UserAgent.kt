package campfire.app.browser

/*
 * A port of the useragent gem (0.16.11), which Rails' `allow_browser` and platform_agent use to
 * read the User-Agent header, following `once-campfire-rust/crates/campfire/src/concerns/user_agent.rs`.
 * `UserAgent.parse` splits the header into products, and the first of `UserAgent::Browsers::ALL`
 * whose `extend?` accepts them decides how `browser`, `version`, `platform`, `os`, `bot?` and
 * `mobile?` are answered.
 *
 * Where the gem raises (a `NoMethodError` on nil, say), the `try*` methods throw [Raised]; the
 * plain methods fall back to nil. Ruby's `\d` and `\s` are ASCII-only and so are the hand-written
 * matchers here (no regexes, so nothing is compiled per call). `^` and `$` are treated as string
 * anchors: a header value cannot contain a newline.
 */

private const val DEFAULT_USER_AGENT = "Mozilla/4.0 (compatible)"

/**
 * The gem raised (NoMethodError/ArgumentError) instead of answering: the Rust port's `Raised`.
 * A single instance without a stack trace, so throwing it costs no more than a return.
 */
object Raised : RuntimeException("the useragent gem raised", null, false, false) {
    private fun readResolve(): Any = Raised
}

/** `UserAgent.parse` (useragent 0.16.11): the entry point of `concerns/user_agent.rs`. */
object UserAgent {
    /**
     * `UserAgent.parse`: blank strings parse as "Mozilla/4.0 (compatible)". The products are
     * scanned in place over index bounds, with Ruby's `strip` of the rest after each product
     * applied by moving both bounds, so no intermediate strings are made.
     */
    @JvmStatic
    fun parse(userAgent: String): Agent {
        val s = if (rubyStrip(userAgent).isEmpty()) DEFAULT_USER_AGENT else userAgent
        val products = ArrayList<Product>(8)
        var from = 0
        var to = s.length
        while (true) {
            val next = matchProduct(s, from, to, products)
            if (next < 0) break
            from = next
            while (from < to && isStripChar(s[from])) from++
            while (to > from && isStripChar(s[to - 1])) to--
        }
        val kind = Kind.ALL.firstOrNull { it.extends(products) } ?: Kind.BASE
        return Agent(kind, products)
    }
}

/** One segment of `UserAgent::Version#to_a`: a run of digits or a trailing word. */
sealed class Segment {
    /** A run of digits, leading zeros stripped ("0" for zero), compared as an unbounded integer. */
    data class Int(val digits: String) : Segment() {
        /** The digits as a number, or null when they don't fit (Rust's `as_u64`). */
        fun toLongOrNull(): Long? = digits.toLongOrNull()

        override fun toString(): String = "i:$digits"
    }

    /** A non-numeric segment, compared by code point (UTF-8 byte order). */
    data class Str(val value: String) : Segment() {
        override fun toString(): String = "s:$value"
    }

    internal companion object {
        val ZERO = Int("0")

        /** `Segment::int`: leading zeros dropped. */
        fun int(digits: String): Int {
            val trimmed = digits.trimStart('0')
            return Int(trimmed.ifEmpty { "0" })
        }
    }
}

/**
 * `UserAgent::Version` (useragent's lib/user_agent/version.rb). Equality is string equality;
 * ordering is the gem's `<=>`, which is not a total order (a non-numeric version sorts below
 * everything it isn't equal to, from either side), so this is not [Comparable], though `<` and
 * friends work through [compareTo]. Segments are split out only when compared.
 */
class Version(private val string: String) {
    private val blank: Boolean = string.all(::isRubySpace)
    private val comparable: Boolean

    init {
        var digits = 0
        while (digits < string.length && isAsciiDigit(string[digits])) digits++
        comparable = !blank && digits > 0 && (digits == string.length || string[digits] == '.')
    }

    /** `Version#nil?`: the string is empty or whitespace. */
    fun isNil(): Boolean = blank

    /** `version.to_s.present?` (ActiveSupport's Unicode-whitespace `blank?`). */
    fun isPresent(): Boolean = isPresent(string)

    /** `Version#to_a`. */
    fun toA(): List<Segment> = when {
        blank -> emptyList()
        comparable -> scanSequences(string)
        else -> listOf(Segment.Str(string))
    }

    /** `Version#<=>` against another version, as -1, 0 or 1: only the first six segments count. */
    fun rubyCompare(other: Version): Int {
        if (!comparable) return if (string == other.string) 0 else -1
        val ours = toA()
        val theirs = other.toA()
        for (i in 0 until 6) {
            val a = ours.getOrNull(i) ?: Segment.ZERO
            val b = theirs.getOrNull(i) ?: Segment.ZERO
            if (a is Segment.Str && b is Segment.Int) return -1
            if (a is Segment.Int && b is Segment.Str) return 1
            if (a == b) continue
            if (a is Segment.Int && b is Segment.Int) {
                val byLength = a.digits.length.compareTo(b.digits.length)
                return Integer.signum(if (byLength != 0) byLength else a.digits.compareTo(b.digits))
            }
            return Integer.signum(compareCodePoints((a as Segment.Str).value, (b as Segment.Str).value))
        }
        return 0
    }

    /** The gem's `<=>` behind Kotlin's `<`, `<=`, `>` and `>=` (Rust's `PartialOrd`). */
    operator fun compareTo(other: Version): Int = rubyCompare(other)

    override fun equals(other: Any?): Boolean = other is Version && other.string == string

    override fun hashCode(): Int = string.hashCode()

    override fun toString(): String = string

    companion object {
        /** `Version.new(nil)`. */
        @JvmField
        val EMPTY = Version("")
    }
}

/** `str.scan(/\d+|[A-Za-z][0-9A-Za-z-]*$/)`. */
private fun scanSequences(string: String): List<Segment> {
    val sequences = ArrayList<Segment>(4)
    var i = 0
    val n = string.length
    while (i < n) {
        val c = string[i]
        if (isAsciiDigit(c)) {
            val start = i
            while (i < n && isAsciiDigit(string[i])) i++
            sequences.add(Segment.int(string.substring(start, i)))
        } else if (isAsciiAlpha(c)) {
            var end = i + 1
            while (end < n && (isAsciiAlphanumeric(string[end]) || string[end] == '-')) end++
            if (end == n) {
                sequences.add(Segment.Str(string.substring(i)))
                i = end
            } else {
                i++
            }
        } else {
            i++
        }
    }
    return sequences
}

/** One `UserAgent` of the gem: a product, its version and its `; `-split comment. */
internal class Product(val product: String, val version: Version, val comment: List<String>?) {
    fun commentAt(index: Int): String? = comment?.getOrNull(index)

    fun joinedComment(): String? = comment?.joinToString("; ")
}

/**
 * `UserAgent::MATCHER` applied at [from] of `s[from, to)`:
 * `^['"]*([^/\s]+)/?([^\s,]*)(\s\(([^\)]*)\)|,gzip\(gfe\))?`. Adds the product to [products] and
 * returns the index just past the match, or -1 when it doesn't match. Every delimiter the pattern
 * names is ASCII, so scanning UTF-16 units stops where scanning code points would.
 */
private fun matchProduct(s: String, from: Int, to: Int, products: MutableList<Product>): Int {
    var quotes = from
    while (quotes < to && (s[quotes] == '\'' || s[quotes] == '"')) quotes++
    val start = when {
        quotes < to && isProductChar(s[quotes]) -> quotes
        quotes > from -> quotes - 1
        else -> return -1
    }

    var i = start + 1
    while (i < to && isProductChar(s[i])) i++
    val product = s.substring(start, i)

    if (i < to && s[i] == '/') i++
    val versionStart = i
    while (i < to && !isRubySpace(s[i]) && s[i] != ',') i++
    val version = s.substring(versionStart, i)

    var comment: List<String>? = null
    if (i + 1 < to && isRubySpace(s[i]) && s[i + 1] == '(') {
        val close = s.indexOf(')', i + 2)
        if (close in 0 until to) {
            comment = rubySplit(s.substring(i + 2, close), "; ")
            i = close + 1
        }
    } else if (i + GZIP_GFE.length <= to && s.startsWith(GZIP_GFE, i)) {
        i += GZIP_GFE.length
    }

    products.add(Product(product, Version(version), comment))
    return i
}

private const val GZIP_GFE = ",gzip(gfe)"

/** `String#split(separator)`: trailing empty fields are dropped. */
internal fun rubySplit(string: String, separator: String): List<String> {
    val parts = string.split(separator).toMutableList()
    while (parts.isNotEmpty() && parts.last().isEmpty()) parts.removeAt(parts.size - 1)
    return parts
}

/** `UserAgent::Browsers::ALL` and the `Base` fallback, each with its class's `self.extend?`. */
internal enum class Kind {
    BASE, EDGE, INTERNET_EXPLORER, OPERA, WECHAT_BROWSER, VIVALDI, CHROME, ITUNES, PLAYSTATION,
    PODCAST_ADDICT, WEBKIT, GECKO, WINDOWS_MEDIA_PLAYER, APPLE_CORE_MEDIA, LIBAVFORMAT;

    /** Each browser class's `self.extend?(agent)`. */
    fun extends(products: List<Product>): Boolean {
        val first = products.firstOrNull()
        val firstVersion = first?.version?.toString()
        return when (this) {
            BASE -> true
            EDGE -> products.lastOrNull()?.product == "Edge"
            INTERNET_EXPLORER -> first != null && first.comment != null &&
                (first.commentAt(1)?.contains("MSIE") == true || first.joinedComment()?.let(::tridentRv) == true)
            OPERA -> first?.product == "Opera" || products.lastOrNull()?.product == "OPR"
            WECHAT_BROWSER -> products.any { containsIgnoringCase(it.product, "micromessenger") }
            VIVALDI -> products.any { it.product == "Vivaldi" }
            CHROME -> products.any { it.product == "Chrome" } || products.any { it.product == "CriOS" }
            ITUNES -> products.any { it.product == "iTunes" }
            PLAYSTATION -> first?.comment?.firstOrNull()?.let {
                it.contains("PLAYSTATION 3") || it.contains("PlayStation Vita") || it.contains("PlayStation 4")
            } == true
            PODCAST_ADDICT -> products.size >= 3 && products[0].product == "Podcast" &&
                products[1].product == "Addict" && products[2].product == "-"
            WEBKIT -> products.any { p ->
                sameIgnoringCase(p.product, "applewebkit") || p.comment.orEmpty().any { webkitCommentVersion(it) != null }
            }
            GECKO -> first?.product == "Mozilla"
            WINDOWS_MEDIA_PLAYER -> products.any {
                it.product in WINDOWS_MEDIA_PLAYER_PRODUCTS && firstVersion !in LIBAVFORMAT_NSPLAYER_VERSIONS
            }
            APPLE_CORE_MEDIA -> products.any { it.product == "AppleCoreMedia" }
            LIBAVFORMAT -> products.any { it.product == "Lavf" || (it.product == "NSPlayer" && firstVersion == "4.1.0.3856") }
        }
    }

    companion object {
        /** `UserAgent::Browsers::ALL`, in detection order. */
        val ALL: Array<Kind> = arrayOf(
            EDGE, INTERNET_EXPLORER, OPERA, WECHAT_BROWSER, VIVALDI, CHROME, ITUNES, PLAYSTATION,
            PODCAST_ADDICT, WEBKIT, GECKO, WINDOWS_MEDIA_PLAYER, APPLE_CORE_MEDIA, LIBAVFORMAT,
        )

        private val WINDOWS_MEDIA_PLAYER_PRODUCTS = setOf("NSPlayer", "Windows-Media-Player", "WMFSDK")
        private val LIBAVFORMAT_NSPLAYER_VERSIONS = setOf("4.1.0.3856", "7.10.0.3059", "7.0.0.1956")
    }
}

/**
 * A parsed User-Agent: `UserAgent::Browsers::Base` extended with the detected browser class
 * (`concerns/user_agent.rs`'s `Agent`). Immutable, so it can be shared across threads.
 */
class Agent internal constructor(internal val kind: Kind, internal val products: List<Product>) {

    /** `browser`; nil (possible for unparseable strings and a bare PlayStation Vita) is "". */
    fun browser(): String = rescue { tryBrowser() } ?: ""

    /** `version`; nil is an empty version. */
    fun version(): Version = rescue { tryVersion() } ?: Version.EMPTY

    /** `platform`, nil where the gem answers nil or raises. */
    fun platform(): String? = rescue { tryPlatform() }

    /** `os`, nil where the gem answers nil or raises. */
    fun os(): String? = rescue { tryOs() }

    /** `bot?`. */
    fun isBot(): Boolean {
        val application = application() ?: return true
        return products.any { p -> p.comment.orEmpty().any { containsIgnoringCase(it, "bot") } } ||
            detectProduct("Chrome-Lighthouse") != null ||
            application.product.contains("bot")
    }

    private inline fun <T> rescue(block: () -> T): T? = try {
        block()
    } catch (_: Raised) {
        null
    }

    private fun first(): Product? = products.firstOrNull()

    private fun last(): Product? = products.lastOrNull()

    /** `detect_product`: case-insensitive product name lookup (also `respond_to?`/`method_missing`). */
    private fun detectProduct(name: String): Product? = products.firstOrNull { sameIgnoringCase(it.product, name) }

    /** `application`: the first product, or for the WebKit-based classes the first with a non-empty comment. */
    private fun application(): Product? = when (kind) {
        Kind.CHROME, Kind.VIVALDI, Kind.WEBKIT, Kind.ITUNES, Kind.APPLE_CORE_MEDIA ->
            products.firstOrNull { !it.comment.isNullOrEmpty() }
        else -> first()
    }

    private fun applicationComment(): List<String>? = application()?.comment

    private fun baseVersion(): Version? = application()?.version

    /** `browser`, throwing [Raised] where the gem raises. */
    internal fun tryBrowser(): String? = when (kind) {
        Kind.BASE -> application()?.product
        Kind.EDGE -> "Edge"
        Kind.INTERNET_EXPLORER -> "Internet Explorer"
        Kind.OPERA -> "Opera"
        Kind.WECHAT_BROWSER -> "Wechat Browser"
        Kind.VIVALDI -> "Vivaldi"
        Kind.CHROME -> if (detectProduct("Iron") != null) "Iron" else "Chrome"
        Kind.ITUNES -> "iTunes"
        Kind.PLAYSTATION -> playstationBrowser()
        Kind.PODCAST_ADDICT -> "Podcast Addict"
        Kind.WEBKIT -> webkitBrowser()
        Kind.GECKO -> geckoBrowser()
        Kind.WINDOWS_MEDIA_PLAYER -> "Windows Media Player"
        Kind.APPLE_CORE_MEDIA -> "AppleCoreMedia"
        Kind.LIBAVFORMAT -> "libavformat"
    }

    private fun playstationBrowser(): String? {
        val firstComment = applicationComment()?.firstOrNull() ?: return null
        return when {
            firstComment.contains("PLAYSTATION 3") -> "PS3 Internet Browser"
            last()?.product == "Silk" -> "Silk"
            firstComment.contains("PlayStation 4") -> "PS4 Internet Browser"
            else -> null
        }
    }

    private fun webkitBrowser(): String = when {
        webkitOs()?.contains("Android") == true -> "Android"
        webkitPlatform() == "BlackBerry" -> "BlackBerry"
        else -> "Safari"
    }

    private fun geckoBrowser(): String =
        GECKO_BROWSERS.firstOrNull { detectProduct(it) != null } ?: first()?.product ?: ""

    /** `version`, throwing [Raised] where the gem raises. */
    internal fun tryVersion(): Version? = when (kind) {
        Kind.BASE, Kind.WINDOWS_MEDIA_PLAYER, Kind.APPLE_CORE_MEDIA -> baseVersion()
        Kind.EDGE, Kind.VIVALDI -> last()?.version
        Kind.INTERNET_EXPLORER -> Version(ieVersion(application()?.joinedComment() ?: "") ?: "")
        Kind.OPERA -> operaVersion()
        Kind.WECHAT_BROWSER -> (detectProduct("MicroMessenger") ?: throw Raised).version
        Kind.CHROME -> (detectProduct("CriOs") ?: detectProduct("chrome") ?: throw Raised).version
        Kind.ITUNES -> (detectProduct("iTunes") ?: throw Raised).version
        Kind.PLAYSTATION -> playstationVersion()
        Kind.PODCAST_ADDICT -> null
        Kind.WEBKIT -> webkitVersion()
        Kind.GECKO -> {
            val version = (detectProduct(geckoBrowser()) ?: throw Raised).version
            if (version.isNil()) baseVersion() else version
        }
        Kind.LIBAVFORMAT -> if (detectProduct("NSPlayer") != null) null else baseVersion()
    }

    /** `/Opera Mini/ === application` matches against `UserAgent#to_str`; only the comment can hold a space. */
    private fun operaMini(): Boolean = first()?.joinedComment()?.contains("Opera Mini") == true

    /** Opera's `version`; `rescue Version.new` covers a comment without an "Opera Mini/<version>". */
    private fun operaVersion(): Version? {
        if (operaMini()) {
            val comment = applicationComment().orEmpty().firstOrNull { it.contains("Opera Mini") }
            return Version(comment?.let { captureAfter(it, "Opera Mini/", ::isVersionChar) } ?: "")
        }
        return detectProduct("Version")?.version ?: detectProduct("OPR")?.version ?: baseVersion()
    }

    private fun playstationVersion(): Version? {
        val os = playstationOs() ?: return null
        if (playstationBrowser() == "Silk") return last()?.version
        val marker = when (playstationPlatform()) {
            "PlayStation 3" -> "PLAYSTATION 3 "
            "PlayStation 4" -> "PlayStation 4 "
            "PlayStation Vita" -> "PlayStation Vita "
            else -> return null
        }
        return Version(rubySplit(os, marker).lastOrNull() ?: "")
    }

    private fun webkitVersion(): Version {
        detectProduct("Version")?.let { return it.version }
        val ios = webkitOs()?.let { captureAfter(it, "iOS ", ::isVersionChar) }
        if (ios != null && webkitBrowser() == "Safari") return Version(ios.replace('_', '.'))
        val build = webkit()?.toString() ?: ""
        return Version(webkitBuildVersion(build) ?: "")
    }

    /** `Webkit#webkit.version`: the AppleWebKit product's version, or one from a comment. */
    private fun webkit(): Version? {
        products.firstOrNull { sameIgnoringCase(it.product, "applewebkit") }?.let { return it.version }
        for (p in products) for (c in p.comment.orEmpty()) webkitCommentVersion(c)?.let { return Version(it) }
        return null
    }

    /** `platform`, throwing [Raised] where the gem raises. */
    internal fun tryPlatform(): String? {
        val comment = applicationComment()
        val first = comment?.firstOrNull()
        fun any(needle: String) = comment?.any { it.contains(needle) } == true
        return when (kind) {
            Kind.BASE, Kind.LIBAVFORMAT -> null
            Kind.EDGE, Kind.INTERNET_EXPLORER, Kind.WINDOWS_MEDIA_PLAYER -> "Windows"
            Kind.OPERA, Kind.APPLE_CORE_MEDIA -> when {
                comment == null -> null
                first?.contains("Windows") == true -> "Windows"
                else -> first
            }
            Kind.WECHAT_BROWSER -> when {
                comment == null -> null
                first?.contains("iPhone") == true -> "iPhone"
                any("Android") -> "Android"
                else -> first
            }
            Kind.CHROME, Kind.VIVALDI -> when {
                comment == null -> null
                first?.contains("Windows") == true -> "Windows"
                any("CrOS") -> "ChromeOS"
                any("Android") -> "Android"
                else -> first
            }
            Kind.WEBKIT, Kind.ITUNES -> webkitPlatform()
            Kind.PLAYSTATION -> playstationPlatform()
            Kind.PODCAST_ADDICT -> {
                val os = podcastAddictOs() ?: throw Raised
                if (os.contains("Android")) "Android" else null
            }
            Kind.GECKO -> when {
                comment == null -> null
                first == "compatible" || first == "Mobile" -> null
                first != null && first.startsWith("Windows ") -> "Windows"
                else -> first
            }
        }
    }

    private fun webkitPlatform(): String? {
        val comment = applicationComment() ?: return null
        val first = comment.firstOrNull()
        return when {
            first?.contains("Windows") == true -> "Windows"
            first == "BB10" -> "BlackBerry"
            comment.any { it.contains("Android") } -> "Android"
            else -> first
        }
    }

    private fun playstationPlatform(): String? {
        val os = playstationOs() ?: return null
        return when {
            os.contains("PLAYSTATION 3") -> "PlayStation 3"
            os.contains("PlayStation 4") -> "PlayStation 4"
            os.contains("PlayStation Vita") -> "PlayStation Vita"
            else -> null
        }
    }

    /** `os`, throwing [Raised] where the gem raises. */
    internal fun tryOs(): String? = when (kind) {
        Kind.BASE, Kind.LIBAVFORMAT -> null
        Kind.EDGE -> {
            var matched: String? = null
            outer@ for (p in products) for (c in p.comment.orEmpty()) {
                matched = windowsOs(c)
                if (matched != null) break@outer
            }
            normalizeOs(matched ?: "")
        }
        Kind.INTERNET_EXPLORER -> normalizeOs(windowsOs(application()?.joinedComment() ?: "") ?: "")
        Kind.OPERA -> {
            val comment = applicationComment()
            when {
                comment == null -> null
                comment.firstOrNull()?.contains("Windows") == true -> normalizeOs(comment[0])
                else -> comment.getOrNull(1)
            }
        }
        Kind.WECHAT_BROWSER, Kind.CHROME, Kind.VIVALDI, Kind.APPLE_CORE_MEDIA -> applicationComment()?.let(::chromeOs)
        Kind.WEBKIT -> webkitOs()
        Kind.ITUNES -> itunesOs()
        Kind.PLAYSTATION -> playstationOs()
        Kind.PODCAST_ADDICT -> podcastAddictOs()
        Kind.GECKO -> geckoOs()
        Kind.WINDOWS_MEDIA_PLAYER -> windowsMediaPlayerOs()
    }

    private fun webkitOs(): String? {
        val comment = applicationComment() ?: return null
        val at0 = comment.getOrNull(0)
        val at1 = comment.getOrNull(1)
        val at2 = comment.getOrNull(2)
        return when {
            at0 != null && at0.contains("Windows NT") -> normalizeOs(at0)
            at2 == null || at1?.contains("Android") == true -> at1?.let(::normalizeOs)
            else -> normalizeOs(comment.firstOrNull { iosVersion(it) != null } ?: at2)
        }
    }

    private fun itunesOs(): String? {
        val windows = applicationComment()?.firstOrNull()?.contains("Windows") == true
        if (!windows) return webkitOs()
        val fullOs = itunesFullOs() ?: ""
        return when {
            fullOs.contains("Windows 8.1") -> "Windows 8.1"
            fullOs.contains("Windows 8") -> "Windows 8"
            fullOs.contains("Windows 7") -> "Windows 7"
            fullOs.contains("Windows Vista") -> "Windows Vista"
            fullOs.contains("Windows XP") -> "Windows XP"
            else -> "Windows"
        }
    }

    /** `ITunes#full_os`: the comment was cut at the first ")", so "(Build 7601" gets it back. */
    private fun itunesFullOs(): String? {
        val comment = applicationComment()?.takeIf { it.size > 1 } ?: return null
        val fullOs = comment[1]
        val n = fullOs.length
        val reopened = n >= 11 && fullOs.startsWith("(Build ", n - 11) && (n - 4 until n).all { isAsciiDigit(fullOs[it]) }
        return if (reopened) "$fullOs)" else fullOs
    }

    private fun playstationOs(): String? = applicationComment()?.joinToString(" ")

    /** `PodcastAddict#os`; throws [Raised] for a comment-less Dalvik/Mozilla, as the gem does. */
    private fun podcastAddictOs(): String? {
        val device = products.getOrNull(3) ?: return null
        if (device.product != "Dalvik" && device.product != "Mozilla") return null
        val comment = device.comment ?: throw Raised
        return when {
            comment.size > 3 -> comment[2]
            comment.size == 3 -> "Android"
            else -> null
        }
    }

    private fun geckoOs(): String? {
        val comment = applicationComment() ?: return null
        val first = comment.firstOrNull()
        val index = when {
            comment.getOrNull(1) == "U" -> 2
            first != null && (first.startsWith("Windows ") || first.startsWith("Android")) -> 0
            first == "Mobile" -> return null
            else -> 1
        }
        return comment.getOrNull(index)?.let(::normalizeOs)
    }

    /**
     * `version.to_a[0]` compared with an Integer: nil raises NoMethodError, a String
     * ArgumentError. Digits too large for a Long compare as the largest Long.
     */
    private fun windowsMediaPlayerMajor(): Long {
        val version = baseVersion() ?: throw Raised
        val first = version.toA().firstOrNull() as? Segment.Int ?: throw Raised
        return first.toLongOrNull() ?: Long.MAX_VALUE
    }

    private fun windowsMediaPlayerOs(): String {
        val major = windowsMediaPlayerMajor()
        val segments = (baseVersion() ?: Version.EMPTY).toA()
        fun part(i: Int): Long? = (segments.getOrNull(i) as? Segment.Int)?.toLongOrNull()
        return when {
            major <= 4 -> when (part(3)) {
                3564L, 3925L -> "Windows 98"
                3857L -> "Windows 9x"
                3936L -> "Windows XP"
                3938L -> "Windows 2000"
                else -> "Windows"
            }
            major == 7L -> if (part(3) == 3055L) "Windows 98" else "Windows"
            major == 8L -> "Windows XP"
            major == 9L || major == 10L -> when (part(3)) {
                2980L -> "Windows 98/2000"
                3268L, 3367L, 3270L -> "Windows 2000"
                3802L, 4503L -> "Windows XP"
                else -> "Windows"
            }
            major == 11L || major == 12L -> when (part(2)) {
                9841L, 9858L, 9860L, 9879L -> "Windows 10"
                9651L -> "Windows Phone 8.1"
                9600L -> "Windows 8.1"
                9200L -> "Windows 8"
                7600L, 7601L -> "Windows 7"
                6000L, 6001L, 6002L -> "Windows Vista"
                5721L -> "Windows XP"
                else -> "Windows"
            }
            else -> "Windows"
        }
    }

    /** `mobile?`: unused by the app (the Rust port keeps it test-only), kept for the gem's vectors. */
    internal fun tryMobile(): Boolean = when (kind) {
        Kind.OPERA -> operaMini()
        Kind.PLAYSTATION -> playstationPlatform() == "PlayStation Vita"
        Kind.PODCAST_ADDICT -> true
        Kind.WINDOWS_MEDIA_PLAYER -> windowsMediaPlayerOs().let { it == "Windows Phone 8" || it == "Windows Phone 8.1" }
        else -> detectProduct("Mobile") != null ||
            products.any { p -> p.comment.orEmpty().any { it == "Mobile" } } ||
            tryOs()?.contains("Android") == true ||
            applicationComment()?.any { it.startsWith("IEMobile") } == true
    }

    private companion object {
        val GECKO_BROWSERS = arrayOf("PaleMoon", "Firefox", "Camino", "Iceweasel", "Seamonkey")
    }
}

/** `os` shared by Chrome, Vivaldi, WechatBrowser and AppleCoreMedia. */
private fun chromeOs(comment: List<String>): String? {
    val at0 = comment.getOrNull(0)
    val at1 = comment.getOrNull(1)
    val at2 = comment.getOrNull(2)
    val pick = when {
        at0 != null && at0.contains("Windows NT") -> at0
        at2 == null || at1?.contains("Android") == true -> at1
        else -> at2
    }
    return pick?.let(::normalizeOs)
}

private val WINDOWS_NAMES = mapOf(
    "Windows NT 10.0" to "Windows 10",
    "Windows NT 6.3" to "Windows 8.1",
    "Windows NT 6.2" to "Windows 8",
    "Windows NT 6.1" to "Windows 7",
    "Windows NT 6.0" to "Windows Vista",
    "Windows NT 5.2" to "Windows XP x64 Edition",
    "Windows NT 5.1" to "Windows XP",
    "Windows NT 5.01" to "Windows 2000, Service Pack 1 (SP1)",
    "Windows NT 5.0" to "Windows 2000",
    "Windows NT 4.0" to "Windows NT 4.0",
    "Windows 98" to "Windows 98",
    "Windows 95" to "Windows 95",
    "Windows CE" to "Windows CE",
)

/** `UserAgent::OperatingSystems.normalize_os`. */
private fun normalizeOs(os: String): String {
    WINDOWS_NAMES[os]?.let { return it }
    macOsXVersion(os)?.let { return if (it.isEmpty()) "OS X" else "OS X ${it.replace('_', '.')}" }
    iosVersion(os)?.let { return "iOS ${it.replace('_', '.')}" }
    chromeOsVersion(os)?.let { return "ChromeOS $it" }
    return os
}

/**
 * `/(?:Intel|PPC) Mac OS X\s*([0-9_\.]+)?/`: null when it doesn't match, else the capture ("" when
 * the optional group is absent).
 */
private fun macOsXVersion(os: String): String? {
    val intel = os.indexOf("Intel Mac OS X")
    val ppc = os.indexOf("PPC Mac OS X")
    val after = when {
        intel >= 0 && (ppc < 0 || intel < ppc) -> intel + 14
        ppc >= 0 -> ppc + 12
        else -> return null
    }
    val start = after + run(os, after, ::isRubySpace)
    val digits = run(os, start) { isAsciiDigit(it) || it == '_' || it == '.' }
    return os.substring(start, start + digits)
}

/** `IOS_VERSION_REGEX = /CPU (?:iPhone |iPod )?OS ([\d_]+) like Mac OS X/`. */
private fun iosVersion(os: String): String? {
    var i = os.indexOf("CPU ")
    while (i >= 0) {
        val rest = i + 4
        for (prefix in IOS_DEVICES) {
            if (!os.startsWith(prefix, rest)) continue
            val at = rest + prefix.length
            if (!os.startsWith("OS ", at)) continue
            val start = at + 3
            val digits = run(os, start) { isAsciiDigit(it) || it == '_' }
            if (digits > 0 && os.startsWith(" like Mac OS X", start + digits)) return os.substring(start, start + digits)
        }
        i = os.indexOf("CPU ", i + 1)
    }
    return null
}

private val IOS_DEVICES = arrayOf("iPhone ", "iPod ", "")

/** `/CrOS\s([^\s]+)\s(\d+(\.\d+)*)/`: the second capture. */
private fun chromeOsVersion(os: String): String? {
    var i = os.indexOf("CrOS")
    while (i >= 0) {
        val rest = i + 4
        val version = chromeOsVersionAt(os, rest)
        if (version != null) return version
        i = os.indexOf("CrOS", i + 1)
    }
    return null
}

/** [chromeOsVersion]'s match after a "CrOS" ending at [rest]. */
private fun chromeOsVersionAt(os: String, rest: Int): String? {
    if (rest >= os.length || !isRubySpace(os[rest])) return null
    val word = run(os, rest + 1) { !isRubySpace(it) }
    if (word == 0) return null
    val at = rest + 1 + word
    if (at >= os.length || !isRubySpace(os[at])) return null
    val start = at + 1
    var end = start + run(os, start, ::isAsciiDigit)
    if (end == start) return null
    while (end + 1 < os.length && os[end] == '.' && isAsciiDigit(os[end + 1])) {
        end += 1 + run(os, end + 1, ::isAsciiDigit)
    }
    return os.substring(start, end)
}

/** `/Windows NT [\d\.]+|Windows Phone (OS )?[\d\.]+/`: the matched text. */
private fun windowsOs(s: String): String? {
    var i = s.indexOf("Windows ")
    while (i >= 0) {
        val tail = when {
            s.startsWith("Windows NT ", i) -> i + 11
            s.startsWith("Windows Phone ", i) -> {
                val after = i + 14
                if (s.startsWith("OS ", after) && run(s, after + 3, ::isVersionChar) > 0) after + 3 else after
            }
            else -> -1
        }
        if (tail >= 0) {
            val digits = run(s, tail, ::isVersionChar)
            if (digits > 0) return s.substring(i, tail + digits)
        }
        i = s.indexOf("Windows ", i + 1)
    }
    return null
}

/** `joined_comment =~ /Trident.+rv:/`. */
private fun tridentRv(s: String): Boolean {
    var i = s.indexOf("Trident")
    while (i >= 0) {
        val after = i + 7
        val newline = s.indexOf('\n', after).let { if (it < 0) s.length else it }
        val rv = s.indexOf("rv:", after + 1)
        if (rv in 0 until newline) return true
        i = s.indexOf("Trident", after)
    }
    return false
}

/** `joined_comment[/(MSIE\s|rv:)([\d\.]+)/, 2]`. */
private fun ieVersion(s: String): String? {
    for (i in s.indices) {
        val tail = when {
            s.startsWith("MSIE", i) && i + 4 < s.length && isRubySpace(s[i + 4]) -> i + 5
            s.startsWith("rv:", i) -> i + 3
            else -> continue
        }
        val digits = run(s, tail, ::isVersionChar)
        if (digits > 0) return s.substring(tail, tail + digits)
    }
    return null
}

/** The capture of `/<prefix>([class]+)/` at its leftmost match. */
private inline fun captureAfter(s: String, prefix: String, crossinline charClass: (Char) -> Boolean): String? {
    var i = s.indexOf(prefix)
    while (i >= 0) {
        val tail = i + prefix.length
        val length = run(s, tail) { charClass(it) }
        if (length > 0) return s.substring(tail, tail + length)
        i = s.indexOf(prefix, tail)
    }
    return null
}

/**
 * `WEBKIT_VERSION_REGEXP = /\A(?<webkit>AppleWebKit)\/(?<version>[\d\.]+)/i`: the version. The
 * first eleven UTF-16 units stand in for the first eleven characters: a surrogate among them can
 * never lowercase to "applewebkit" either way.
 */
internal fun webkitCommentVersion(comment: String): String? {
    if (comment.length < 12 || comment[11] != '/') return null
    if (!sameIgnoringCase(comment.substring(0, 11), "applewebkit")) return null
    val digits = run(comment, 12, ::isVersionChar)
    return if (digits > 0) comment.substring(12, 12 + digits) else null
}

private val WEBKIT_BUILD_VERSIONS = mapOf(
    "85.7" to "1.0",
    "85.8.5" to "1.0.3", "85.8.2" to "1.0.3",
    "124" to "1.2",
    "125.2" to "1.2.2",
    "125.4" to "1.2.3",
    "125.5.5" to "1.2.4", "125.5.6" to "1.2.4", "125.5.7" to "1.2.4",
    "312.1.1" to "1.3", "312.1" to "1.3",
    "312.5" to "1.3.1", "312.5.1" to "1.3.1", "312.5.2" to "1.3.1",
    "312.8" to "1.3.2", "312.8.1" to "1.3.2",
    "412" to "2.0", "412.6" to "2.0", "412.6.2" to "2.0",
    "412.7" to "2.0.1",
    "416.11" to "2.0.2", "416.12" to "2.0.2",
    "417.9" to "2.0.3", "418" to "2.0.3",
    "418.8" to "2.0.4", "418.9" to "2.0.4", "418.9.1" to "2.0.4", "419" to "2.0.4",
    "425.13" to "2.2",
    "534.52.7" to "5.1.2",
)

/** `Webkit::BuildVersions`: Safari versions before Safari 3 reported only the WebKit build. */
private fun webkitBuildVersion(build: String): String? = WEBKIT_BUILD_VERSIONS[build]

/** Length of the run of chars from [from] satisfying [f]. */
private inline fun run(s: String, from: Int, f: (Char) -> Boolean): Int {
    var i = from
    while (i < s.length && f(s[i])) i++
    return i - from
}

/** `\s` in a Ruby regexp. */
internal fun isRubySpace(c: Char): Boolean =
    c == ' ' || c == '\t' || c == '\n' || c == '\u000b' || c == '\u000c' || c == '\r'

private fun isStripChar(c: Char): Boolean = c == '\u0000' || isRubySpace(c)

private fun isProductChar(c: Char): Boolean = c != '/' && !isRubySpace(c)

private fun isAsciiDigit(c: Char): Boolean = c in '0'..'9'

private fun isAsciiAlpha(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z'

private fun isAsciiAlphanumeric(c: Char): Boolean = isAsciiDigit(c) || isAsciiAlpha(c)

private fun isVersionChar(c: Char): Boolean = isAsciiDigit(c) || c == '.'

private fun isAscii(s: String): Boolean {
    for (c in s) if (c.code >= 0x80) return false
    return true
}

/** `String#strip` (`ruby_compat::strip`): NUL and ASCII whitespace off both ends. */
internal fun rubyStrip(s: String): String {
    var from = 0
    var to = s.length
    while (from < to && isStripChar(s[from])) from++
    while (to > from && isStripChar(s[to - 1])) to--
    return s.substring(from, to)
}

/**
 * `a.downcase == b.downcase`. Two ASCII strings compare char by char ignoring ASCII case;
 * anything else is lowercased first, because a non-ASCII character can lowercase to an ASCII one
 * (U+212A KELVIN SIGN to "k").
 */
internal fun sameIgnoringCase(a: String, b: String): Boolean =
    if (isAscii(a) && isAscii(b)) a.equals(b, ignoreCase = true) else a.lowercase() == b.lowercase()

/**
 * `haystack.downcase.include?(needle)` for a non-empty, lowercase ASCII [needle], with the same
 * ASCII fast path as [sameIgnoringCase].
 */
internal fun containsIgnoringCase(haystack: String, needle: String): Boolean {
    if (!isAscii(haystack)) return haystack.lowercase().contains(needle)
    val last = haystack.length - needle.length
    for (i in 0..last) if (haystack.regionMatches(i, needle, 0, needle.length, ignoreCase = true)) return true
    return false
}

/**
 * ActiveSupport's `present?` for strings, with Unicode's White_Space set (Rust's
 * `char::is_whitespace`, which differs from Java's `isWhitespace`).
 */
internal fun isPresent(string: String): Boolean {
    for (c in string) if (!isUnicodeWhiteSpace(c)) return true
    return false
}

private fun isUnicodeWhiteSpace(c: Char): Boolean = when (c) {
    in '\u0009'..'\u000d', ' ', '\u0085', ' ', ' ', in ' '..' ',
    ' ', ' ', ' ', ' ', '　' -> true
    else -> false
}

/** Code point order, which is UTF-8 byte order (Rust's `as_bytes().cmp`). */
private fun compareCodePoints(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val x = a.codePointAt(i)
        val y = b.codePointAt(j)
        if (x != y) return x.compareTo(y)
        i += Character.charCount(x)
        j += Character.charCount(y)
    }
    return when {
        i < a.length -> 1
        j < b.length -> -1
        else -> 0
    }
}
