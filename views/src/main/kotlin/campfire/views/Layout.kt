package campfire.views

import campfire.kit.PageParts

/** A template block (`{% block name %}`): it writes its content into the page. */
fun interface Block {
    fun write(p: PageParts.Builder)

    companion object {
        val EMPTY = Block {}
    }
}

/**
 * `layouts/application.html` (`crates/views/templates/layouts/application.html`): the head with
 * the page's `head` block, the nav, the flash, the page's `content` and `footer` inside `main`,
 * the sidebar, the lightbox and the app logo. [title] is the page title, unescaped.
 */
fun applicationLayout(
    p: PageParts.Builder,
    ctx: LayoutContext,
    title: String?,
    bodyClass: String?,
    head: Block = Block.EMPTY,
    nav: Block = Block.EMPTY,
    content: Block = Block.EMPTY,
    footer: Block = Block.EMPTY,
    sidebar: Block = Block.EMPTY,
) {
    layoutHead(p, ctx, h(title ?: "Campfire"))
    p.append(HEAD_GAP)
    head.write(p)
    p.append(HEAD_END)
    p.append(BODY_OPEN).append(bodyClasses(ctx, bodyClass)).append(BODY_OPEN_END)
    p.append(SKIP_NAVIGATION)
    p.append(NAV_OPEN)
    nav.write(p)
    p.append(NAV_CLOSE)
    flash(p, ctx)
    p.append(MAIN_OPEN)
    content.write(p)
    p.append(FOOTER_OPEN)
    footer.write(p)
    p.append(FOOTER_CLOSE)
    p.append(SIDEBAR_OPEN)
    sidebar.write(p)
    p.append(SIDEBAR_CLOSE)
    lightbox(p)
    p.append(APP_LOGO_OPEN).append(Assets.path("campfire-icon.png")).append(APP_LOGO_CLOSE)
}

/**
 * turbo-rails' `layouts/turbo_rails/frame.html` for a Turbo-Frame request: the page's `head` and
 * `content` blocks only.
 */
fun frameLayout(p: PageParts.Builder, head: Block, content: Block) {
    p.append(FRAME_OPEN)
    head.write(p)
    p.append(FRAME_MIDDLE)
    content.write(p)
    p.append(FRAME_CLOSE)
}

/**
 * The layout's flash (`flash[:notice] || flash[:alert]`): a banner that removes itself once its
 * animation ends, red for an alert.
 */
private fun flash(p: PageParts.Builder, ctx: LayoutContext) {
    val notice = ctx.flashNotice ?: ctx.flashAlert ?: return
    val alert = ctx.flashAlert != null
    p.append(FLASH_OPEN)
    if (alert) p.append("--flash-background: var(--color-negative)")
    p.append(FLASH_STYLE_END)
    val icon = if (alert) "alert.svg" else "check.svg"
    p.append("            ").append(imageTag(icon, listOf("aria-hidden" to "true", "class" to "colorize--white"), "24")).append("</span>\n")
    p.append(FLASH_INNER_CLOSE).append(h(notice)).append(FLASH_CLOSE)
}

/** `body_classes`: `[ @body_class, admin_body_class, account_logo_body_class ].compact.join(" ")`. */
internal fun bodyClasses(ctx: LayoutContext, bodyClass: String?): String {
    val classes = ArrayList<String>(3)
    if (bodyClass != null) classes.add(bodyClass)
    if (ctx.administrator) classes.add("admin")
    if (ctx.hasLogo) classes.add("account-has-logo")
    return classes.joinToString(" ")
}

/** `allow_browser`'s minimum versions (`crates/views/src/sessions.rs` `ALLOW_BROWSER_VERSIONS`). */
private val ALLOW_BROWSER_VERSIONS = listOf("safari" to "17.2", "chrome" to "120", "firefox" to "121", "opera" to "104")

/**
 * `sessions/incompatible_browser.html` in the application layout, or in the frame layout for a
 * Turbo-Frame request: the page a blocked browser gets instead of the one it asked for.
 */
fun renderIncompatibleBrowser(ctx: LayoutContext, frame: Boolean): PageParts {
    val p = PageParts.Builder()
    val content = Block { b ->
        b.append(INCOMPATIBLE_OPEN).append(translationButton("incompatible_browser_messsage")).append(INCOMPATIBLE_HEADER_END)
        for ((browser, version) in ALLOW_BROWSER_VERSIONS) {
            b.append("      <div class=\"browser flex flex-column\">\n        ")
            b.append(imageTag("browsers/$browser.svg", listOf("aria-hidden" to "true", "class" to "center"), null))
            b.append("\n        <div class=\"flex flex-column align-center margin-block-start-half\">\n          <strong>")
            b.append(h(capitalize(browser))).append("</strong>\n          <span> ").append(h(version)).append("+</span>\n        </div>\n      </div>\n")
        }
        b.append("  </div>\n</div>\n")
    }
    val title = if (ctx.platform.appleMessages) "Campfire" else "Unsupported browser"
    if (frame) frameLayout(p, Block.EMPTY, content) else applicationLayout(p, ctx, title, null, content = content)
    return p.build()
}

private val HEAD_GAP = "\n\n".toByteArray(Charsets.UTF_8)
private val HEAD_END = "  </head>\n\n".toByteArray(Charsets.UTF_8)
private val BODY_OPEN = "  <body class=\"".toByteArray(Charsets.UTF_8)
private val BODY_OPEN_END = "\" data-controller=\"local-time lightbox\">\n".toByteArray(Charsets.UTF_8)
private val SKIP_NAVIGATION = "    <a href=\"#main-content\" class=\"skip-navigation btn\">Skip to main content</a>\n\n".toByteArray(Charsets.UTF_8)
private val NAV_OPEN = "    <nav id=\"nav\">\n".toByteArray(Charsets.UTF_8)
private val NAV_CLOSE = "    </nav>\n\n".toByteArray(Charsets.UTF_8)
private val MAIN_OPEN = "\n    <main id=\"main-content\">\n".toByteArray(Charsets.UTF_8)
private val FOOTER_OPEN = "\n      <footer id=\"footer\">\n".toByteArray(Charsets.UTF_8)
private val FOOTER_CLOSE = "      </footer>\n    </main>\n\n".toByteArray(Charsets.UTF_8)
private val SIDEBAR_OPEN = "    <aside id=\"sidebar\" data-controller=\"toggle-class\" data-toggle-class-toggle-class=\"open\">\n".toByteArray(Charsets.UTF_8)
private val SIDEBAR_CLOSE = "    </aside>\n\n    ".toByteArray(Charsets.UTF_8)
private val APP_LOGO_OPEN = "\n\n    <a href=\"https://once.com\" id=\"app-logo\" target=\"_blank\" aria-label=\"Once software from 37signals home page\">\n      <img alt=\"Campfire logo\" width=\"256\" height=\"216\" src=\"".toByteArray(Charsets.UTF_8)
private val APP_LOGO_CLOSE = "\" />\n    </a>\n  </body>\n</html>\n".toByteArray(Charsets.UTF_8)
private val FRAME_OPEN = "<html>\n  <head>\n    ".toByteArray(Charsets.UTF_8)
private val FRAME_MIDDLE = "\n  </head>\n  <body>\n    ".toByteArray(Charsets.UTF_8)
private val FRAME_CLOSE = "\n  </body>\n</html>\n".toByteArray(Charsets.UTF_8)
private val FLASH_OPEN = "      <div class=\"flash\" data-controller=\"element-removal\" data-action=\"animationend->element-removal#remove\">\n        <div class=\"flash__inner shadow\" style=\"".toByteArray(Charsets.UTF_8)
private val FLASH_STYLE_END = "\">\n".toByteArray(Charsets.UTF_8)
private val FLASH_INNER_CLOSE = "        </div>\n        <span class=\"for-screen-reader\" role=\"alert\" aria-atomic=\"true\">".toByteArray(Charsets.UTF_8)
private val FLASH_CLOSE = "</span>\n      </div>\n".toByteArray(Charsets.UTF_8)
private val INCOMPATIBLE_OPEN = "<div class=\"panel center\">\n  <header>\n    <h1 class=\"txt-x-large txt-tight-lines txt-align-center margin-none-block-start margin-block-end\">\n      Upgrade to a supported web browser\n    </h1>\n    <div class=\"flex align-start gap\">\n      ".toByteArray(Charsets.UTF_8)
private val INCOMPATIBLE_HEADER_END = "\n      <p class=\"margin-none-block-start\">Campfire requires a modern web browser. Please use one of the browsers listed below and make sure auto-updates are enabled.</p>\n    </div>\n  </header>\n\n  <div class=\"browser-list flex align-center flex-wrap gap justify-center margin-block\">\n".toByteArray(Charsets.UTF_8)
