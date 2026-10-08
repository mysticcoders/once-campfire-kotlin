package campfire.views

import campfire.db.*
import campfire.kit.Fragment
import campfire.kit.PageParts
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonToken

/** `ERB::Util.html_escape`. */
fun h(s: String): String {
    var i = 0
    while (i < s.length) {
        when (s[i]) {
            '&', '<', '>', '"', '\'' -> break
        }
        i++
    }
    if (i == s.length) return s
    val out = StringBuilder(s.length + 16).append(s, 0, i)
    while (i < s.length) {
        when (val c = s[i]) {
            '&' -> out.append("&amp;")
            '<' -> out.append("&lt;")
            '>' -> out.append("&gt;")
            '"' -> out.append("&quot;")
            '\'' -> out.append("&#39;")
            else -> out.append(c)
        }
        i++
    }
    return out.toString()
}

/** The asset pipeline's tags and digested paths (`campfire.assets.Assets`), as the views use them. */
object Assets {
    val stylesheetTags: ByteArray get() = campfire.assets.Assets.stylesheetTags
    val importmapTags: ByteArray get() = campfire.assets.Assets.importmapTags
    val linkHeader: String get() = campfire.assets.Assets.linkHeader

    fun path(name: String): String = campfire.assets.Assets.path(name)
}

/** A user as the views show one: `users/_user`'s name, title and fresh avatar path. */
class UserView(val id: Long, val name: String, val title: String, val avatarUrl: String) {
    val path get() = "/users/$id"
}

/**
 * `ApplicationPlatform` as the views see it (`crates/views/src/lib.rs` `Platform`): the browser
 * and operating system, and the flags the `pwa/` partials branch on.
 */
data class Platform(
    val ios: Boolean,
    val android: Boolean,
    val mac: Boolean,
    val windows: Boolean,
    val chrome: Boolean,
    val firefox: Boolean,
    val safari: Boolean,
    val edge: Boolean,
    val mobile: Boolean,
    val desktop: Boolean,
    val appleMessages: Boolean,
    val browser: String,
    val operatingSystem: String,
)

/** `capitalize` (`crates/views/src/helpers/application.rs`): the first character upcased, the rest downcased. */
fun capitalize(text: String): String =
    if (text.isEmpty()) "" else text.substring(0, text.offsetByCodePoints(0, 1)).uppercase() + text.substring(text.offsetByCodePoints(0, 1)).lowercase()

/**
 * `image_tag(source, options)` (`crates/views/src/helpers/assets.rs`): the options in order, then
 * `src`, then `width` and `height` from `size` ("20" or "20x30").
 */
fun imageTag(source: String, options: List<Pair<String, String>>, size: String?): String {
    val sb = StringBuilder(96).append("<img")
    for ((name, value) in options) sb.append(' ').append(name).append("=\"").append(h(value)).append('"')
    sb.append(" src=\"").append(h(Assets.path(source))).append('"')
    if (size != null) {
        val x = size.indexOf('x')
        val width = if (x < 0) size else size.substring(0, x)
        val height = if (x < 0) size else size.substring(x + 1)
        sb.append(" width=\"").append(width).append("\" height=\"").append(height).append('"')
    }
    return sb.append(" />").toString()
}

/** What the layout needs from the request: `Current.user`, the account and the request's URL base. */
class LayoutContext(
    val user: UserView,
    val administrator: Boolean,
    val baseUrl: String,
    val accountLogoUrl: String,
    val hasLogo: Boolean,
    val customStyles: String?,
    val vapidPublicKey: String?,
    val platform: Platform,
    val flashNotice: String? = null,
    val flashAlert: String? = null,
)

/** `rooms/show`'s data: the room, its last page of messages as fragments, and its stream name. */
class ShowView(
    val roomId: Long,
    val kind: RoomKind,
    val displayName: String,
    val loadedAt: Long,
    val messages: List<Fragment>,
    val invitation: Boolean,
    val joinCode: String,
    val streamName: String,
)

/** A message as `messages/_message` shows it. */
class MessageView(
    val id: Long,
    val clientMessageId: String,
    val roomId: Long,
    val roomName: String,
    val creator: UserView,
    val createdAt: Ts,
    val updatedAt: Ts,
    val allEmoji: Boolean,
    val bodyHtml: String,
    val boosts: List<BoostView>,
    val unrenderable: Boolean = false,
)

class BoostView(val id: Long, val messageId: Long, val content: String, val allEmoji: Boolean, val booster: UserView)

private val REACTIONS = listOf(
    "👍" to "Thumbs up",
    "👏" to "Clapping",
    "👋" to "Waving hand",
    "💪" to "Muscle",
    "❤️" to "Red heart",
    "😂" to "Face with tears of joy",
    "🎉" to "Party popper",
    "🔥" to "Fire",
)

/** `messages/_message` for an unrenderable message: `messages/_unrenderable.html`. */
private const val UNRENDERABLE = "\n  <div class=\"message message--formatted message--failed center\">\n  <div class=\"message__body\">\n    <div class=\"message__body-content txt-align-center\">\n      Failed to load message content\n    </div>\n  </div>\n</div>"

/** `messages/_message` with `_actions`, `_presentation` and `boosts/_boosts` (a cached fragment). */
fun renderMessage(m: MessageView): String {
    if (m.unrenderable) return UNRENDERABLE
    val dom = "message_${m.clientMessageId}"
    val atPath = "/rooms/${m.roomId}/@${m.id}"
    val created = m.createdAt.iso8601()
    val createdMs = m.createdAt.epochMs()
    val sb = StringBuilder(10 * 1024)
    sb.append("\n  <div id=\"").append(dom).append("\" class=\"message ").append(if (m.allEmoji) "message--emoji" else "")
        .append("\" data-controller=\"reply\" data-user-id=\"").append(m.creator.id).append("\" data-message-id=\"").append(m.id)
        .append("\" data-message-timestamp=\"").append(createdMs).append("\" data-message-updated-at=\"").append(m.updatedAt.epochMs())
        .append("\" data-sort-value=\"").append(createdMs)
        .append("\" data-messages-target=\"message\" data-search-results-target=\"message\" data-refresh-room-target=\"message\" data-reply-composer-outlet=\"#composer\">\n")
    sb.append("    <h2 class=\"message__day-separator\"><time datetime=\"").append(created).append("\" data-local-time-target=\"date\"></time></h2>\n\n")
    sb.append("    <figure class=\"avatar message__avatar\">\n")
    sb.append("      <a title=\"").append(h(m.creator.title)).append("\" class=\"btn avatar\" data-turbo-frame=\"_top\" href=\"").append(m.creator.path)
        .append("\"><img aria-hidden=\"true\" src=\"").append(h(m.creator.avatarUrl)).append("\" width=\"48\" height=\"48\" /></a>\n")
    sb.append("    </figure>\n\n")
    sb.append("    <turbo-frame id=\"edit_").append(dom).append("\">\n")
    sb.append("      <div class=\"message__body\">\n")
    sb.append("        <div class=\"message__body-content\">\n")
    sb.append("          <div class=\"message__meta\">\n")
    sb.append("            <h3 class=\"message__heading\">\n")
    sb.append("              <span class=\"message__author\" title=\"").append(h(m.creator.title)).append("\">\n")
    sb.append("                <strong data-reply-target=\"author\">").append(h(m.creator.name)).append("</strong>\n")
    sb.append("              </span>\n")
    sb.append("              <a target=\"_top\" class=\"message__permalink\" href=\"").append(atPath).append("\"><time class=\"message__timestamp\" datetime=\"")
        .append(created).append("\" data-local-time-target=\"time\"></time></a>\n")
    sb.append("              <span class=\"message__room\">\n")
    sb.append("                <a target=\"_top\" data-reply-target=\"link\" href=\"").append(atPath).append("\">").append(h(m.roomName)).append("</a>\n")
    sb.append("              </span>\n")
    sb.append("            </h3>\n")
    sb.append("            ")
    actions(sb, m, dom, atPath)
    sb.append("\n          </div>\n")
    sb.append("          <div id=\"presentation_").append(dom).append("\" dir=\"auto\" data-reply-target=\"body\" data-messages-target=\"body\">\n  ")
    sb.append(m.bodyHtml)
    sb.append("\n</div>\n")
    sb.append("          ")
    boosts(sb, m, dom)
    sb.append("\n        </div>\n      </div>\n    </turbo-frame>\n</div>")
    return sb.toString()
}

private fun actions(sb: StringBuilder, m: MessageView, dom: String, atPath: String) {
    sb.append("<div class=\"message__actions\" data-controller=\"soft-keyboard\">\n")
    sb.append("  <details class=\"position-relative\" data-controller=\"popup\" data-action=\"keydown.esc-&gt;popup#close toggle-&gt;popup#toggle click@document-&gt;popup#closeOnClickOutside\" data-popup-orientation-top-class=\"popup-orientation-top\">\n")
    sb.append("    <summary class=\"btn message__action-btn message__options-btn\">\n")
    sb.append("      <img class=\"colorize--black\" aria-hidden=\"true\" src=\"").append(Assets.path("menu-dots-horizontal.svg")).append("\" width=\"20\" height=\"20\" />\n")
    sb.append("      <span class=\"for-screen-reader\">Message options</span>\n")
    sb.append("    </summary>\n\n")
    sb.append("    <div class=\"message__actions-menu border shadow\" data-popup-target=\"menu\">\n")
    sb.append("      <div class=\"quick-boosts\">")
    for ((character, title) in REACTIONS) {
        sb.append("\n          <form data-turbo-frame=\"boosting_").append(dom).append("\" data-action=\"popup#close\" action=\"/messages/").append(m.id)
            .append("/boosts\" accept-charset=\"UTF-8\" method=\"post\">\n")
        sb.append("            <input type=\"hidden\" name=\"boost[content]\" id=\"boost_content\" value=\"").append(character).append("\" />\n")
        sb.append("            <button name=\"button\" type=\"submit\" title=\"").append(title).append("\" class=\"btn message__action-btn\" data-emoji=\"")
            .append(character).append("\">\n")
        sb.append("              <figure class=\"margin-none boost-character\">").append(character).append("</figure>\n")
        sb.append("              <span class=\"for-screen-reader\">").append(title).append("</span>\n")
        sb.append("</button></form>")
    }
    sb.append("\n\n        <a class=\"btn message__action-btn message__boost-btn\" data-turbo-frame=\"new_boost_").append(dom)
        .append("\" data-action=\"soft-keyboard#open popup#close\" href=\"/messages/").append(m.id).append("/boosts/new\">\n")
    sb.append("          <img class=\"colorize--black\" aria-hidden=\"true\" src=\"").append(Assets.path("boost.svg")).append("\" width=\"20\" height=\"20\" />\n")
    sb.append("          <span class=\"for-screen-reader\">New boost</span>\n")
    sb.append("</a>      </div>\n\n")
    sb.append("      <div class=\"flex flex-wrap border-top margin-block-start-half pad-block-start-half message__actions-grid\">")
    sb.append("\n          <button class=\"btn message__action-btn center full-width\" data-action=\"reply#reply\" title=\"Reply\" aria-label=\"Reply\">\n")
    sb.append("            <img class=\"colorize--black\" aria-hidden=\"true\" src=\"").append(Assets.path("reply.svg")).append("\" width=\"20\" height=\"20\" />\n")
    sb.append("</button>\n\n")
    sb.append("        <button class=\"btn message__action-btn center full-width\" title=\"Copy link\" aria-label=\"Copy link\" data-controller=\"copy-to-clipboard\" data-action=\"copy-to-clipboard#copy\" data-copy-to-clipboard-success-class=\"btn--success\" data-copy-to-clipboard-url-value=\"")
        .append(atPath).append("\">\n")
    sb.append("          <img class=\"colorize--black\" aria-hidden=\"true\" src=\"").append(Assets.path("link.svg")).append("\" width=\"20\" height=\"20\" />\n")
    sb.append("</button>\n")
    sb.append("        <a class=\"btn message__action-btn center full-width message__edit-btn\" data-turbo-frame=\"edit_").append(dom)
        .append("\" title=\"Edit\" aria-label=\"Edit\" href=\"/rooms/").append(m.roomId).append("/messages/").append(m.id).append("/edit\">\n")
    sb.append("          <img class=\"colorize--black\" aria-hidden=\"true\" src=\"").append(Assets.path("pencil.svg")).append("\" width=\"20\" height=\"20\" />\n")
    sb.append("</a>      </div>\n")
    sb.append("    </div>\n")
    sb.append("</details></div>")
}

private fun boosts(sb: StringBuilder, m: MessageView, dom: String) {
    sb.append("<turbo-frame id=\"boosting_").append(dom).append("\">\n")
    sb.append("  <div class=\"boosts flex flex-wrap align-center gap full-width\" style=\"--column-gap: 0.4ch; --row-gap: 0\"\n")
    sb.append("      data-controller=\"turbo-streaming\" data-action=\"turbo:submit-start->turbo-streaming#unsubscribe\">\n")
    sb.append("    <div class=\"flex-inline flex-wrap gap\" id=\"boosts_").append(dom).append("\" data-turbo-streaming-target=\"container\">")
    for (boost in m.boosts) {
        sb.append("\n      ")
        boost(sb, boost)
    }
    sb.append("\n    </div>\n\n")
    sb.append("    <turbo-frame id=\"new_boost_").append(dom).append("\">\n")
    sb.append("      <div class=\"flex-inline message__boost-inline\" data-controller=\"soft-keyboard\">\n")
    sb.append("        <a class=\"boost__action txt-small btn\" action=\"soft-keyboard#open\" href=\"/messages/").append(m.id).append("/boosts/new\">\n")
    sb.append("          <img aria-hidden=\"true\" src=\"").append(Assets.path("boost.svg")).append("\" width=\"20\" height=\"20\" />\n")
    sb.append("          <span class=\"for-screen-reader\">Add a boost</span>\n")
    sb.append("</a>      </div>\n")
    sb.append("</turbo-frame>  </div>\n")
    sb.append("</turbo-frame>")
}

private fun boost(sb: StringBuilder, b: BoostView) {
    sb.append("<div id=\"boost_").append(b.id).append("\"\n")
    sb.append("      class=\"boost boost-item flex-inline postion--relative max-width align-center fill-white gap\"\n")
    sb.append("      data-controller=\"boost-delete\" data-boost-delete-perform-class=\"boost--deleting\" data-boost-delete-reveal-class=\"expanded\" data-boost-delete-booster-id-value=\"")
        .append(b.booster.id).append("\">\n")
    sb.append("    <figure class=\"avatar boost__avatar flex-item-no-shrink\">\n")
    sb.append("      <a title=\"").append(h(b.booster.title)).append("\" class=\"btn avatar\" data-turbo-frame=\"_top\" href=\"").append(b.booster.path)
        .append("\"><img aria-label=\"").append(h(b.booster.name)).append(" boosted ").append(h(b.content)).append("\" src=\"").append(h(b.booster.avatarUrl))
        .append("\" width=\"48\" height=\"48\" /></a>\n")
    sb.append("    </figure>\n\n")
    sb.append("    <span role=\"button\" class=\"").append(if (b.allEmoji) "txt-small txt-medium" else "txt-small")
        .append("\" data-action=\"click-&gt;boost-delete#reveal keydown.enter-&gt;boost-delete#reveal:prevent\" data-boost-delete-target=\"content\">")
        .append(h(b.content)).append("</span>\n\n")
    sb.append("    <form class=\"button_to\" method=\"post\" action=\"/messages/").append(b.messageId).append("/boosts/").append(b.id)
        .append("\"><input type=\"hidden\" name=\"_method\" value=\"delete\" /><button data-action=\"boost-delete#perform\" data-boost-delete-target=\"button\" class=\"btn btn--negative flex-item-justify-end boost__delete\" type=\"submit\">\n")
    sb.append("      <img aria-hidden=\"true\" src=\"").append(Assets.path("minus.svg")).append("\" width=\"20\" height=\"20\" />\n")
    sb.append("      <span class=\"for-screen-reader\">Delete this boost</span>\n")
    sb.append("</button></form>  </div>\n")
    sb.append("  <span id=\"delete_boost_accessible_label\" class=\"for-screen-reader\">Press enter to delete this boost</span>")
}

/** The page templates' static text, encoded once (as Askama and JTE precompile it). */
private val T0 = "\n\n\n  <meta name=\"turbo-cache-control\" content=\"no-preview\">\n  <meta name=\"current-room-id\" content=\"".toByteArray(Charsets.UTF_8)
private val T1 = "\">  </head>\n\n".toByteArray(Charsets.UTF_8)
private val T2 = "  <body class=\"".toByteArray(Charsets.UTF_8)
private val T3 = "\" data-controller=\"local-time lightbox\">\n".toByteArray(Charsets.UTF_8)
private val T4 = "    <a href=\"#main-content\" class=\"skip-navigation btn\">Skip to main content</a>\n\n".toByteArray(Charsets.UTF_8)
private val T5 = "    <nav id=\"nav\">\n".toByteArray(Charsets.UTF_8)
private val T6 = "    </nav>\n\n".toByteArray(Charsets.UTF_8)
private val T7 = "\n    <main id=\"main-content\">\n".toByteArray(Charsets.UTF_8)
private val T8 = "\n<div id=\"message-area\" class=\"message-area\" contents=\"true\" data-controller=\"messages presence drop-target\" data-action=\"turbo:before-stream-render@document-&gt;messages#beforeStreamRender keydown.up@document-&gt;messages#editMyLastMessage dragenter-&gt;drop-target#dragenter dragover-&gt;drop-target#dragover drop-&gt;drop-target#drop visibilitychange@document-&gt;presence#visibilityChanged\" data-messages-first-of-day-class=\"message--first-of-day\" data-messages-formatted-class=\"message--formatted\" data-messages-me-class=\"message--me\" data-messages-mentioned-class=\"message--mentioned\" data-messages-threaded-class=\"message--threaded\" data-messages-page-url-value=\"".toByteArray(Charsets.UTF_8)
private val T9 = "/messages\">\n  ".toByteArray(Charsets.UTF_8)
private val T10 = "\n\n  <div id=\"messages_".toByteArray(Charsets.UTF_8)
private val T11 = "\" class=\"messages\" data-controller=\"maintain-scroll refresh-room\" data-action=\"turbo:before-stream-render@document-&gt;maintain-scroll#beforeStreamRender visibilitychange@document-&gt;refresh-room#visibilityChanged online@window-&gt;refresh-room#online\" data-messages-target=\"messages\" data-refresh-room-loaded-at-value=\"".toByteArray(Charsets.UTF_8)
private val T12 = "\" data-refresh-room-url-value=\"".toByteArray(Charsets.UTF_8)
private val T13 = "/refresh\">".toByteArray(Charsets.UTF_8)
private val T14 = "\n  </div>\n\n".toByteArray(Charsets.UTF_8)
private val T15 = "  <turbo-cable-stream-source channel=\"RoomMessagesChannel\" signed-stream-name=\"".toByteArray(Charsets.UTF_8)
private val T16 = "\"></turbo-cable-stream-source>\n".toByteArray(Charsets.UTF_8)
private val T17 = "  <button class=\"message-area__return-to-latest btn\" data-action=\"messages#returnToLatest\" data-messages-target=\"latest\" hidden=\"hidden\"><img aria-hidden=\"true\" src=\"".toByteArray(Charsets.UTF_8)
private val T18 = "\" width=\"20\" height=\"20\" /><span class=\"for-screen-reader\">Jump to newest message</span></button>\n</div>\n".toByteArray(Charsets.UTF_8)
private val T19 = "      <footer id=\"footer\">\n".toByteArray(Charsets.UTF_8)
private val T20 = "      </footer>\n    </main>\n\n".toByteArray(Charsets.UTF_8)
private val T21 = "    <aside id=\"sidebar\" data-controller=\"toggle-class\" data-toggle-class-toggle-class=\"open\">\n".toByteArray(Charsets.UTF_8)
private val T22 = "<turbo-frame data-turbo-permanent=\"true\" data-controller=\"rooms-list read-rooms turbo-frame\" data-rooms-list-unread-class=\"unread\" data-action=\"presence:present@window->rooms-list#read read-rooms:read->rooms-list#read turbo:frame-load->rooms-list#loaded refresh-room:visible@window->turbo-frame#reload\" id=\"user_sidebar\" src=\"/users/me/sidebar\" target=\"_top\"></turbo-frame>".toByteArray(Charsets.UTF_8)
private val T23 = "    </aside>\n\n    ".toByteArray(Charsets.UTF_8)
private val T24 = "\n\n    <a href=\"https://once.com\" id=\"app-logo\" target=\"_blank\" aria-label=\"Once software from 37signals home page\">\n".toByteArray(Charsets.UTF_8)
private val T25 = "      <img alt=\"Campfire logo\" width=\"256\" height=\"216\" src=\"".toByteArray(Charsets.UTF_8)
private val T26 = "    </a>\n  </body>\n</html>\n".toByteArray(Charsets.UTF_8)
private val T27 = "<!DOCTYPE html>\n<html>\n  <head>\n    <title>".toByteArray(Charsets.UTF_8)
private val T28 = "</title>\n\n".toByteArray(Charsets.UTF_8)
private val T29 = "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1, user-scalable=no, interactive-widget=resizes-content\">\n".toByteArray(Charsets.UTF_8)
private val T30 = "    <meta name=\"view-transition\" content=\"same-origin\">\n".toByteArray(Charsets.UTF_8)
private val T31 = "    <meta name=\"color-scheme\" content=\"light dark\">\n".toByteArray(Charsets.UTF_8)
private val T32 = "    <meta name=\"theme-color\" content=\"#ffffff\" media=\"(prefers-color-scheme: light)\">\n".toByteArray(Charsets.UTF_8)
private val T33 = "    <meta name=\"theme-color\" content=\"#000000\" media=\"(prefers-color-scheme: dark)\">\n".toByteArray(Charsets.UTF_8)
private val T34 = "    <meta name=\"apple-mobile-web-app-capable\" content=\"yes\">\n".toByteArray(Charsets.UTF_8)
private val T35 = "    <meta name=\"current-user-id\" content=\"".toByteArray(Charsets.UTF_8)
private val T36 = "\" /><meta name=\"current-user-name\" content=\"".toByteArray(Charsets.UTF_8)
private val T37 = "    <meta name=\"action-cable-url\" content=\"/cable\">\n\n".toByteArray(Charsets.UTF_8)
private val T38 = "    <meta name=\"vapid-public-key\" content=\"".toByteArray(Charsets.UTF_8)
private val T39 = "    <meta name=\"vapid-public-key\">\n".toByteArray(Charsets.UTF_8)
private val T40 = "    <meta name=\"turbo-prefetch\" content=\"true\">\n\n".toByteArray(Charsets.UTF_8)
private val T41 = "    <link rel=\"manifest\" href=\"/webmanifest.json\">\n".toByteArray(Charsets.UTF_8)
private val T42 = "    <link rel=\"icon\" href=\"".toByteArray(Charsets.UTF_8)
private val T43 = "\" type=\"image/png\">\n".toByteArray(Charsets.UTF_8)
private val T44 = "    <link rel=\"apple-touch-icon\" href=\"".toByteArray(Charsets.UTF_8)
private val T45 = "\">\n\n    ".toByteArray(Charsets.UTF_8)
private val T46 = "<style data-turbo-track=\"reload\">".toByteArray(Charsets.UTF_8)
private val T47 = "</style>".toByteArray(Charsets.UTF_8)
private val T48 = "\n\n    ".toByteArray(Charsets.UTF_8)
private val T49 = "\n\n  <span class=\"btn btn--reversed btn--faux room--current\">\n    <h1 class=\"room__contents txt-medium overflow-ellipsis\">".toByteArray(Charsets.UTF_8)
private val T50 = "\n        <span class=\"for-screen-reader\">Ping with</span>".toByteArray(Charsets.UTF_8)
private val T51 = "\n\n      ".toByteArray(Charsets.UTF_8)
private val T52 = "\n    </h1>\n</span>\n\n".toByteArray(Charsets.UTF_8)
private val T53 = "  <a class=\"btn\" style=\"view-transition-name: edit-room-".toByteArray(Charsets.UTF_8)
private val T54 = "\" data-room-id=\"".toByteArray(Charsets.UTF_8)
private val T55 = "\" href=\"/rooms/".toByteArray(Charsets.UTF_8)
private val T56 = "/edit\">\n".toByteArray(Charsets.UTF_8)
private val T57 = "    <img aria-hidden=\"true\" src=\"".toByteArray(Charsets.UTF_8)
private val T58 = "\" width=\"20\" height=\"20\" />\n".toByteArray(Charsets.UTF_8)
private val T59 = "    <span class=\"for-screen-reader\">Settings for this ".toByteArray(Charsets.UTF_8)
private val T60 = "</span>\n</a>\n\n  ".toByteArray(Charsets.UTF_8)
private val T61 = "<span>\n  <span class=\"button_to_change_notifying\"\n".toByteArray(Charsets.UTF_8)
private val T62 = "      data-controller=\"notifications\" data-notifications-subscriptions-url-value=\"/users/me/push_subscriptions\" data-notifications-attention-class=\"btn--pulsing\">\n".toByteArray(Charsets.UTF_8)
private val T63 = "    <turbo-frame data-controller=\"turbo-frame\" data-action=\"notifications:ready@window-&gt;turbo-frame#load\" data-turbo-frame-url-param=\"/rooms/".toByteArray(Charsets.UTF_8)
private val T64 = "/involvement\" id=\"involvement_".toByteArray(Charsets.UTF_8)
private val T65 = "      <button class=\"btn\" data-action=\"click->notifications#attemptToSubscribe\" data-notifications-target=\"bell\">\n".toByteArray(Charsets.UTF_8)
private val T66 = "        <img aria-hidden=\"true\" src=\"".toByteArray(Charsets.UTF_8)
private val T67 = "        <img aria-hidden=\"true\" hidden=\"hidden\" src=\"".toByteArray(Charsets.UTF_8)
private val T68 = "        <span class=\"for-screen-reader\">Notification settings for this ".toByteArray(Charsets.UTF_8)
private val T69 = "</span>\n".toByteArray(Charsets.UTF_8)
private val T70 = "      </button>\n</turbo-frame>\n".toByteArray(Charsets.UTF_8)
private val T71 = "    <dialog data-notifications-target=\"notAllowedNotice\" class=\"dialog pad center center-block border-radius border shadow\" style=\"--inline-space: var(--block-space)\">\n".toByteArray(Charsets.UTF_8)
private val T72 = "      <div class=\"flex flex-column txt-align-center\">\n".toByteArray(Charsets.UTF_8)
private val T73 = "        <span class=\"btn btn--faux center txt-x-large\">\n".toByteArray(Charsets.UTF_8)
private val T74 = "          <img aria-hidden=\"true\" src=\"".toByteArray(Charsets.UTF_8)
private val T75 = "\" width=\"48\" height=\"48\" />\n".toByteArray(Charsets.UTF_8)
private val T76 = "          <span class=\"for-screen-reader\">Notifications alert</span>\n".toByteArray(Charsets.UTF_8)
private val T77 = "        </span>\n\n".toByteArray(Charsets.UTF_8)
private val T78 = "        <section>\n".toByteArray(Charsets.UTF_8)
private val T79 = "          <h1 class=\"txt-large margin-none\">Notifications aren’t allowed</h1>\n".toByteArray(Charsets.UTF_8)
private val T80 = "          <div class=\"txt-align-start margin-block-start\">\n            ".toByteArray(Charsets.UTF_8)
private val T81 = "\n          </div>\n        </section>\n\n".toByteArray(Charsets.UTF_8)
private val T82 = "        <form method=\"dialog\" class=\"flex align-center gap center\">\n".toByteArray(Charsets.UTF_8)
private val T83 = "          <button class=\"btn dialog__close\" autofocus=\"true\">\n".toByteArray(Charsets.UTF_8)
private val T84 = "            <span class=\"for-screen-reader\">Close</span>\n".toByteArray(Charsets.UTF_8)
private val T85 = "            <img aria-hidden=\"true\" src=\"".toByteArray(Charsets.UTF_8)
private val T86 = "          </button>\n        </form>\n      </div>\n    </dialog>\n  </span>\n</span>".toByteArray(Charsets.UTF_8)
private val T87 = "  <details class=\"notifications-help\" data-notifications-target=\"details\">\n".toByteArray(Charsets.UTF_8)
private val T88 = "    <summary class=\"btn\">\n".toByteArray(Charsets.UTF_8)
private val T89 = "      <img aria-hidden=\"true\" src=\"".toByteArray(Charsets.UTF_8)
private val T90 = "      <strong>Check your ".toByteArray(Charsets.UTF_8)
private val T91 = " settings</strong>\n".toByteArray(Charsets.UTF_8)
private val T92 = "      <img aria-hidden=\"true\" class=\"disclosure\" src=\"".toByteArray(Charsets.UTF_8)
private val T93 = "\" width=\"10\" height=\"10\" />\n".toByteArray(Charsets.UTF_8)
private val T94 = "    </summary>\n\n".toByteArray(Charsets.UTF_8)
private val T95 = "        <p>Ensure notifications are enabled for <em>".toByteArray(Charsets.UTF_8)
private val T96 = "</em> in your web browser settings.</p>\n".toByteArray(Charsets.UTF_8)
private val T97 = "  </details>\n\n".toByteArray(Charsets.UTF_8)
private val T98 = "            <details class=\"notifications-help hide-in-browser\" data-notifications-target=\"details\">\n".toByteArray(Charsets.UTF_8)
private val T99 = "  <summary class=\"btn\">\n".toByteArray(Charsets.UTF_8)
private val T100 = "    <strong>Check your ".toByteArray(Charsets.UTF_8)
private val T101 = "    <img aria-hidden=\"true\" class=\"disclosure\" src=\"".toByteArray(Charsets.UTF_8)
private val T102 = "  </summary>\n\n".toByteArray(Charsets.UTF_8)
private val T103 = "      <p>Ensure notifications are allowed for ".toByteArray(Charsets.UTF_8)
private val T104 = " in your system settings.</p>\n".toByteArray(Charsets.UTF_8)
private val T105 = "</details>\n\n".toByteArray(Charsets.UTF_8)
private val T106 = "              <details class=\"notifications-help pwa__instructions hide-in-pwa\" data-controller=\"pwa-install\" data-pwa-install-prompting-class=\"pwa--can-install\" data-notifications-target=\"details\">\n".toByteArray(Charsets.UTF_8)
private val T107 = "      <strong>Install Campfire as a web app.</strong>\n".toByteArray(Charsets.UTF_8)
private val T108 = "        <p>Some platforms require you to install Campfire as a web app to receive push notifications.</p>\n\n".toByteArray(Charsets.UTF_8)
private val T109 = "    <div class=\"margin-block-start txt-align-center pwa__installer\">\n".toByteArray(Charsets.UTF_8)
private val T110 = "      <hr class=\"separator margin-block\">\n".toByteArray(Charsets.UTF_8)
private val T111 = "      <button class=\"btn btn--reversed center\" data-action=\"pwa-install#promptInstall\">\n".toByteArray(Charsets.UTF_8)
private val T112 = "        Install now\n".toByteArray(Charsets.UTF_8)
private val T113 = "      </button>\n".toByteArray(Charsets.UTF_8)
private val T114 = "    </div>\n".toByteArray(Charsets.UTF_8)
private val T115 = "  </details>\n".toByteArray(Charsets.UTF_8)
private val T116 = "<script type=\"text/template\" data-messages-target=\"template\">\n".toByteArray(Charsets.UTF_8)
private val T117 = "  <div class=\"message message--me \$messageClasses\$\"\n".toByteArray(Charsets.UTF_8)
private val T118 = "      id=\"message_\$clientMessageId\$\"\n".toByteArray(Charsets.UTF_8)
private val T119 = "      data-format-message-target=\"message\"\n".toByteArray(Charsets.UTF_8)
private val T120 = "      data-user-id=\"".toByteArray(Charsets.UTF_8)
private val T121 = "      data-message-timestamp=\"\$messageTimestamp\$\"\n".toByteArray(Charsets.UTF_8)
private val T122 = "      data-messages-target=\"message\">\n".toByteArray(Charsets.UTF_8)
private val T123 = "    <div class=\"message__day-separator\"><time class=\"message__timestamp\" datetime=\"\$messageDatetime\$\" data-local-time-target=\"date\"></time></div>\n\n".toByteArray(Charsets.UTF_8)
private val T124 = "    <figure class=\"avatar message__avatar\">\n".toByteArray(Charsets.UTF_8)
private val T125 = "      <a title=\"".toByteArray(Charsets.UTF_8)
private val T126 = "\" class=\"btn avatar\" data-turbo-frame=\"_top\" href=\"".toByteArray(Charsets.UTF_8)
private val T127 = "\"><img aria-hidden=\"true\" src=\"".toByteArray(Charsets.UTF_8)
private val T128 = "\" width=\"48\" height=\"48\" /></a>\n".toByteArray(Charsets.UTF_8)
private val T129 = "    </figure>\n\n".toByteArray(Charsets.UTF_8)
private val T130 = "    <div class=\"message__body\">\n".toByteArray(Charsets.UTF_8)
private val T131 = "      <div class=\"message__body-content\">\n".toByteArray(Charsets.UTF_8)
private val T132 = "        <div class=\"message__meta\">\n".toByteArray(Charsets.UTF_8)
private val T133 = "          <h3 class=\"message__heading\">\n".toByteArray(Charsets.UTF_8)
private val T134 = "            <span class=\"message__author\"><strong>".toByteArray(Charsets.UTF_8)
private val T135 = "</strong></span>\n".toByteArray(Charsets.UTF_8)
private val T136 = "            <span class=\"message__permalink\"><time class=\"message__timestamp\" datetime=\"\$messageDatetime\$\" data-local-time-target=\"time\"></time></span>\n".toByteArray(Charsets.UTF_8)
private val T137 = "          </h3>\n".toByteArray(Charsets.UTF_8)
private val T138 = "          <div class=\"message__actions\">\n".toByteArray(Charsets.UTF_8)
private val T139 = "            <div class=\"position-relative\">\n".toByteArray(Charsets.UTF_8)
private val T140 = "              <span class=\"btn message__action-btn message__options-btn\">\n".toByteArray(Charsets.UTF_8)
private val T141 = "                <img class=\"colorize--black\" aria-hidden=\"true\" src=\"".toByteArray(Charsets.UTF_8)
private val T142 = "                <span class=\"for-screen-reader\">Message options</span>\n".toByteArray(Charsets.UTF_8)
private val T143 = "              </span>\n".toByteArray(Charsets.UTF_8)
private val T144 = "            </div class=\"position-relative\">\n".toByteArray(Charsets.UTF_8)
private val T145 = "          </div>\n".toByteArray(Charsets.UTF_8)
private val T146 = "        </div>\n".toByteArray(Charsets.UTF_8)
private val T147 = "        \$body\$\n".toByteArray(Charsets.UTF_8)
private val T148 = "      </div>\n".toByteArray(Charsets.UTF_8)
private val T149 = "  </div>\n".toByteArray(Charsets.UTF_8)
private val T150 = "</script>".toByteArray(Charsets.UTF_8)
private val T151 = "<div class=\"composer flex align-end gap position-relative\"\n".toByteArray(Charsets.UTF_8)
private val T152 = "      data-controller=\"typing-notifications\" data-typing-notifications-active-class=\"typing-indicator--active\">\n".toByteArray(Charsets.UTF_8)
private val T153 = "    <a class=\"btn flex-item-no-shrink margin-block-end composer__context-btn\" style=\"view-transition-name: input-switcher\" href=\"/searches\">\n".toByteArray(Charsets.UTF_8)
private val T154 = "      <span class=\"for-screen-reader\">Search</span>\n</a>\n".toByteArray(Charsets.UTF_8)
private val T155 = "    <turbo-frame id=\"composer-frame\">\n".toByteArray(Charsets.UTF_8)
private val T156 = "      <form id=\"composer\" class=\"margin-block flex-item-grow contain\" data-controller=\"composer drop-target\" data-action=\"dragenter-&gt;drop-target#dragenter dragover-&gt;drop-target#dragover drop-&gt;drop-target#drop drop-target:drop@window-&gt;composer#dropFiles lexxy:file-accept-&gt;composer#preventAttachment refresh-room:online@window-&gt;composer#online typing-notifications#stop paste-&gt;composer#pasteFiles turbo:submit-end-&gt;composer#submitEnd refresh-room:offline@window-&gt;composer#offline\" data-composer-messages-outlet=\"#message-area\" data-composer-toolbar-class=\"composer--rich-text\" data-composer-room-id-value=\"".toByteArray(Charsets.UTF_8)
private val T157 = "\" action=\"/rooms/".toByteArray(Charsets.UTF_8)
private val T158 = "/messages\" accept-charset=\"UTF-8\" method=\"post\">\n".toByteArray(Charsets.UTF_8)
private val T159 = "        <fieldset data-composer-target=\"fields\" contents>\n".toByteArray(Charsets.UTF_8)
private val T160 = "          <div class=\"flex flex-column\">\n".toByteArray(Charsets.UTF_8)
private val T161 = "            <div class=\"composer__filelist flex flex--align-center gap flex-wrap\" data-composer-target=\"fileList\"></div>\n\n".toByteArray(Charsets.UTF_8)
private val T162 = "            <div class=\"flex composer__input input input--actor fill-white min-width\" style=\"--input-border-radius: 1.3rem\">\n".toByteArray(Charsets.UTF_8)
private val T163 = "              <div class=\"flex align-end gap full-width\">\n".toByteArray(Charsets.UTF_8)
private val T164 = "                <img aria-hidden=\"true\" class=\"composer__input-hint colorize--black\" style=\"view-transition-name: input-btn;\" src=\"".toByteArray(Charsets.UTF_8)
private val T165 = "\" width=\"22\" height=\"22\" />\n\n".toByteArray(Charsets.UTF_8)
private val T166 = "                <div class=\"flex flex-column flex-item-grow min-width gap\">\n".toByteArray(Charsets.UTF_8)
private val T167 = "                  <lexxy-editor rows=\"1\" class=\"input lexxy-content\" style=\"order: -1\" aria-multiline=\"true\" aria-label=\"Write a message\" permitted-attachment-types=\"application/vnd.campfire.mention application/vnd.actiontext.opengraph-embed\" data-controller=\"unfurl\" data-action=\"lexxy:change-&gt;typing-notifications#start keydown-&gt;composer#submitByKeyboard:capture lexxy:change-&gt;composer#saveDraft lexxy:insert-link-&gt;unfurl#unfurl\" data-composer-target=\"text\" data-direct-upload-url=\"".toByteArray(Charsets.UTF_8)
private val T168 = "/rails/active_storage/direct_uploads\" data-blob-url-template=\"".toByteArray(Charsets.UTF_8)
private val T169 = "/rails/active_storage/blobs/redirect/:signed_id/:filename\" id=\"message_body\" input=\"message_body_trix_input_message\" name=\"message[body]\">\n".toByteArray(Charsets.UTF_8)
private val T170 = "                    <lexxy-prompt trigger=\"@\" name=\"mention\" src=\"/autocompletable/users?room_id=".toByteArray(Charsets.UTF_8)
private val T171 = "\" remote-filtering=\"true\" empty-results=\"No matches\"></lexxy-prompt>\n".toByteArray(Charsets.UTF_8)
private val T172 = "</lexxy-editor>                </div>\n\n".toByteArray(Charsets.UTF_8)
private val T173 = "                <label class=\"btn btn--borderless txt-small flex-item-no-shrink composer__attachment-btn input--file\">\n".toByteArray(Charsets.UTF_8)
private val T174 = "                  <img class=\"colorize--black\" aria-hidden=\"true\" src=\"".toByteArray(Charsets.UTF_8)
private val T175 = "\" width=\"22\" height=\"22\" />\n".toByteArray(Charsets.UTF_8)
private val T176 = "                  <input type=\"file\" data-action=\"composer#filePicked\" multiple />\n".toByteArray(Charsets.UTF_8)
private val T177 = "                  <span class=\"for-screen-reader\">Attach a file</span>\n".toByteArray(Charsets.UTF_8)
private val T178 = "                </label>\n\n".toByteArray(Charsets.UTF_8)
private val T179 = "                <button class=\"btn btn--borderless txt-small flex-item-no-shrink composer__rich-text-btn\" type=\"button\" data-action=\"composer#toggleToolbar\">\n".toByteArray(Charsets.UTF_8)
private val T180 = "                  <span class=\"for-screen-reader\">Rich text</span>\n".toByteArray(Charsets.UTF_8)
private val T181 = "                </button>\n\n".toByteArray(Charsets.UTF_8)
private val T182 = "                <button name=\"send\" type=\"submit\" data-action=\"composer#submit\" class=\"btn btn--reversed flex-item-no-shrink txt-small\">\n".toByteArray(Charsets.UTF_8)
private val T183 = "                  <img aria-hidden=\"true\" src=\"".toByteArray(Charsets.UTF_8)
private val T184 = "                  <span class=\"for-screen-reader\">Send Message</span>\n".toByteArray(Charsets.UTF_8)
private val T185 = "</button>              </div>\n".toByteArray(Charsets.UTF_8)
private val T186 = "            </div>\n".toByteArray(Charsets.UTF_8)
private val T187 = "        </fieldset>\n\n".toByteArray(Charsets.UTF_8)
private val T188 = "        <div class=\"typing-indicator gap txt-small align-center flex-inline\" data-typing-notifications-target=\"indicator\">\n".toByteArray(Charsets.UTF_8)
private val T189 = "          <div class=\"typing-indicator__author spinner\" data-typing-notifications-target=\"author\"></div>\n".toByteArray(Charsets.UTF_8)
private val T190 = "        </div>\n\n".toByteArray(Charsets.UTF_8)
private val T191 = "        <input data-composer-target=\"clientid\" type=\"hidden\" name=\"message[client_message_id]\" id=\"message_client_message_id\" />\n".toByteArray(Charsets.UTF_8)
private val T192 = "</form>    </turbo-frame>\n".toByteArray(Charsets.UTF_8)
private val T193 = "  </div>".toByteArray(Charsets.UTF_8)
private val T194 = "<dialog class=\"lightbox\" aria-label=\"Image Viewer (Press escape to close)\" data-lightbox-target=\"dialog\" data-action=\"close->lightbox#reset\">\n".toByteArray(Charsets.UTF_8)
private val T195 = "  <img src=\"\" class=\"lightbox__image\" data-lightbox-target=\"zoomedImage\" />\n\n".toByteArray(Charsets.UTF_8)
private val T196 = "  <form method=\"dialog\" class=\"lightbox__btn\">\n".toByteArray(Charsets.UTF_8)
private val T197 = "    <button class=\"btn\">\n".toByteArray(Charsets.UTF_8)
private val T198 = "      <span class=\"for-screen-reader\">Close image viewer</span>\n".toByteArray(Charsets.UTF_8)
private val T199 = "    </button>\n".toByteArray(Charsets.UTF_8)
private val T200 = "  </form>\n\n".toByteArray(Charsets.UTF_8)
private val T201 = "  <a href=\"\" class=\"lightbox__btn--download btn hide-in-ios-pwa\" data-lightbox-target=\"download\">\n".toByteArray(Charsets.UTF_8)
private val T202 = "    <span class=\"for-screen-reader\">Download file</span>\n".toByteArray(Charsets.UTF_8)
private val T203 = "  </a>\n\n".toByteArray(Charsets.UTF_8)
private val T204 = "  <button class=\"lightbox__btn--share btn\"\n".toByteArray(Charsets.UTF_8)
private val T205 = "      data-controller=\"web-share\"\n".toByteArray(Charsets.UTF_8)
private val T206 = "      data-action=\"web-share#share\"\n".toByteArray(Charsets.UTF_8)
private val T207 = "      data-web-share-files-value=\"\"\n".toByteArray(Charsets.UTF_8)
private val T208 = "      data-lightbox-target=\"share\">\n".toByteArray(Charsets.UTF_8)
private val T209 = "    <span class=\"for-screen-reader\">Share file</span>\n".toByteArray(Charsets.UTF_8)
private val T210 = "  </button>\n".toByteArray(Charsets.UTF_8)
private val T211 = "</dialog>\n".toByteArray(Charsets.UTF_8)

/** The text between the `pwa/` includes in `rooms/involvements/_bell.html`. */
private val INCLUDE_GAP = "\n            ".toByteArray(Charsets.UTF_8)

/** `rooms/show` in `layouts/application`, as parts: the layout around the room's message fragments. */
fun renderRoomShow(ctx: LayoutContext, show: ShowView, frame: Boolean = false): PageParts {
    val p = PageParts.Builder()
    val roomDom = "${show.kind.paramKey}_${show.roomId}"
    val noun = if (show.kind == RoomKind.Direct) "Ping" else "room"
    val head = Block { b ->
        b.append(ROOM_HEAD).append(show.roomId).append(ROOM_HEAD_END)
    }
    val content = Block { b ->
        b.append(T8).append(h(ctx.baseUrl)).append("/rooms/").append(show.roomId).append(T9)
        messageTemplate(b, ctx.user)
        b.append(T10).append(roomDom).append(T11)
            .append(show.loadedAt).append(T12).append(h(ctx.baseUrl)).append("/rooms/").append(show.roomId).append(T13)
        if (show.invitation) {
            b.append("\n    ")
            invitation(b, ctx, show.joinCode)
        }
        for (fragment in show.messages) {
            b.append("\n    ")
            b.fragment(fragment)
        }
        b.append(T14)
        b.append(T15).append(show.streamName).append(T16)
        b.append(T17).append(Assets.path("arrow-down.svg")).append(RETURN_TO_LATEST_END)
    }
    if (frame) {
        frameLayout(p, head, content)
    } else {
        applicationLayout(
            p, ctx, show.displayName, "sidebar",
            head = head,
            nav = Block { b -> nav(b, ctx, show, roomDom, noun) },
            content = content,
            footer = Block { b -> composer(b, ctx, show.roomId) },
            sidebar = Block { b -> b.append(T22) },
        )
    }
    return p.build()
}

private val ROOM_HEAD = "\n  <meta name=\"turbo-cache-control\" content=\"no-preview\">\n  <meta name=\"current-room-id\" content=\"".toByteArray(Charsets.UTF_8)
private val ROOM_HEAD_END = "\">".toByteArray(Charsets.UTF_8)
private val RETURN_TO_LATEST_END = "\" width=\"20\" height=\"20\" /><span class=\"for-screen-reader\">Jump to newest message</span></button>\n</div>".toByteArray(Charsets.UTF_8)

/**
 * `rooms/show/_invitation.html` with `accounts/_invite.html`: the original room's welcome, shown
 * until it has a second page of messages.
 */
private fun invitation(p: PageParts.Builder, ctx: LayoutContext, joinCode: String) {
    val url = ctx.baseUrl + "/join/" + joinCode
    val escaped = h(url)
    val qr = "/qr_code/" + java.util.Base64.getUrlEncoder().encodeToString(url.toByteArray(Charsets.UTF_8))
    p.append("<div id=\"system_welcome\" class=\"message message--formatted txt-align-center center\">\n    <div class=\"message__body center\">\n      <div class=\"message__body-content position-relative\">\n        ")
    p.append(accountLogoTag(ctx, "center margin-block-end txt-large"))
    p.append("\n        <div class=\"flex align-center gap\">\n          <div class=\"system-welcome--translation\">\n            ")
    p.append(translationButton("invite_message"))
    p.append("\n          </div>\n          <p>\n            <strong>Welcome to Campfire</strong><br>\n            To invite people to chat, share the join link below.\n          </p>\n        </div>\n        ")
    p.append("<div class=\"flex flex-column align-center gap\">\n\n  <label class=\"flex flex-column gap full-width\" style=\"--row-gap: 0.5em\">\n    <strong id=\"invite_label\" class=\"invite-label\">Share to invite more people</strong>\n    <span class=\"flex align-center gap input input--actor fill-white\">\n      ")
    p.append(imageTag("person-add.svg", listOf("aria-hidden" to "true", "class" to "colorize--black"), "20"))
    p.append("\n      <input type=\"text\" class=\"input\" id=\"invite_url\" value=\"").append(escaped).append("\" aria-labelledby=\"invite_label\" readonly>\n    </span>\n  </label>\n\n  <div class=\"flex align-center gap\">\n    ")
    p.append("<a class=\"btn\" data-lightbox-target=\"image\" data-action=\"lightbox#open\" data-lightbox-url-value=\"").append(h(qr)).append("\" href=\"").append(h(qr)).append("\">\n      <span class=\"for-screen-reader\">Show join link QR code</span>\n      ")
    p.append(imageTag("qr-code.svg", listOf("aria-hidden" to "true", "class" to "colorize--black"), "20"))
    p.append("\n</a>\n    <button class=\"btn\" data-controller=\"copy-to-clipboard\" data-action=\"copy-to-clipboard#copy\" data-copy-to-clipboard-success-class=\"btn--success\" data-copy-to-clipboard-content-value=\"").append(escaped).append("\">\n      <span class=\"for-screen-reader\">Copy join link</span>\n      ")
    p.append(imageTag("copy-paste.svg", listOf("aria-hidden" to "true", "class" to "colorize--black"), "20"))
    p.append("\n</button>\n    <button class=\"btn\" hidden=\"hidden\" data-controller=\"web-share\" data-action=\"web-share#share\" data-web-share-url-value=\"").append(escaped)
        .append("\" data-web-share-text-value=\"Hit this link to join me in Campfire and start chatting.\" data-web-share-title-value=\"Link to join Campfire\">\n      <span class=\"for-screen-reader\">Share join link</span>\n      ")
    p.append(imageTag("share.svg", listOf("aria-hidden" to "true", "class" to "colorize--black"), "20"))
    p.append("\n</button>\n")
    if (ctx.administrator) {
        p.append("      <form class=\"button_to\" method=\"post\" action=\"/account/join_code\"><button class=\"btn btn--regenerate\" type=\"submit\">\n        ")
        p.append(imageTag("refresh.svg", listOf("aria-hidden" to "true", "class" to "colorize--black"), "20"))
        p.append("\n        <span class=\"for-screen-reader\">Regenerate join link</span>\n</button></form>")
    }
    p.append("  </div>\n</div>\n\n      </div>\n    </div>\n  </div>")
}

/** `account_logo_tag(style:)` (`crates/views/src/helpers/users.rs`). */
private fun accountLogoTag(ctx: LayoutContext, style: String?): String =
    "<figure class=\"" + h("account-logo avatar " + (style ?: "")) + "\">" +
        imageTag(ctx.accountLogoUrl, listOf("alt" to "Account logo"), "300") + "</figure>"

/**
 * `translation_button(key)` (`crates/views/src/helpers/translations.rs`): the language popup,
 * built once per key since it depends on nothing else.
 */
internal fun translationButton(key: String): String = TRANSLATION_BUTTONS.getOrPut(key) {
    val entries = TRANSLATIONS[key] ?: error("unknown translation key $key")
    val list = entries.joinToString("") { (language, text) -> "<dt>" + h(language) + "</dt><dd class=\"margin-none\">" + h(text) + "</dd>" }
    "<details class=\"position-relative\" data-controller=\"popup\" data-action=\"keydown.esc-&gt;popup#close toggle-&gt;popup#toggle click@document-&gt;popup#closeOnClickOutside\" data-popup-orientation-top-class=\"popup-orientation-top\">" +
        "<summary class=\"btn\" tabindex=\"-1\">" + imageTag("globe.svg", listOf("aria-hidden" to "true", "class" to "color-icon"), "20") +
        "<span class=\"for-screen-reader\">Translate</span></summary><div class=\"language-list-menu shadow\" data-popup-target=\"menu\"><dl class=\"language-list\">" +
        list + "</dl></div></details>"
}

private val TRANSLATION_BUTTONS = java.util.concurrent.ConcurrentHashMap<String, String>()

/** Something the spike doesn't render, which the full port would. */
class Unsupported(what: String) : RuntimeException("not ported in the spike: $what")


internal fun layoutHead(p: PageParts.Builder, ctx: LayoutContext, title: String) {
    p.append(T27).append(title).append(T28)
    p.append(T29)
    p.append(T30)
    p.append(T31)
    p.append(T32)
    p.append(T33)
    p.append(T34)
    p.append(T35).append(ctx.user.id).append(T36).append(h(ctx.user.name)).append("\" />\n")
    p.append(T37)
    if (ctx.vapidPublicKey != null) {
        p.append(T38).append(h(ctx.vapidPublicKey)).append("\">\n")
    } else {
        p.append(T39)
    }
    p.append(T40)
    p.append(T41)
    p.append(T42).append(h(ctx.accountLogoUrl)).append(T43)
    p.append(T44).append(h(ctx.accountLogoUrl)).append(T45)
    p.append(Assets.stylesheetTags)
    p.append("\n    ")
    if (ctx.customStyles != null) p.append(T46).append(ctx.customStyles).append(T47)
    p.append(T48)
    p.append(Assets.importmapTags)
}

private fun nav(p: PageParts.Builder, ctx: LayoutContext, show: ShowView, roomDom: String, noun: String) {
    if (ctx.hasLogo) throw Unsupported("the account logo")
    p.append(T49)
    if (show.kind == RoomKind.Direct) p.append(T50)
    p.append(T51).append(h(show.displayName)).append(T52)
    p.append(T53).append(show.roomId).append(T54).append(show.roomId)
        .append(T55).append(show.kind.name.lowercase()).append("s/").append(show.roomId).append(T56)
    p.append(T57).append(Assets.path("menu-dots-horizontal.svg")).append(T58)
    p.append(T59).append(noun).append(T60)
    bell(p, ctx, show.roomId, roomDom, noun)
}

private fun bell(p: PageParts.Builder, ctx: LayoutContext, roomId: Long, roomDom: String, noun: String) {
    p.append(T61)
    p.append(T62)
    p.append(T63)
        .append(roomId).append(T64).append(roomDom).append("\">\n")
    p.append(T65)
    p.append(T66).append(Assets.path("notification-bell-loading.svg")).append(T58)
    p.append(T67).append(Assets.path("notification-bell-alert.svg")).append(T58)
    p.append(T68).append(noun).append(T69)
    p.append(T70)
    p.append(T71)
    p.append(T72)
    p.append(T73)
    p.append(T74).append(Assets.path("notification-bell-alert.svg")).append(T75)
    p.append(T76)
    p.append(T77)
    p.append(T78)
    p.append(T79)
    p.append(T80)
    pwaBrowserSettings(p, ctx, ctx.platform)
    p.append(INCLUDE_GAP)
    pwaSystemSettings(p, ctx, ctx.platform)
    p.append(INCLUDE_GAP)
    pwaInstallInstructions(p, ctx, ctx.platform)
    p.append(T81)
    p.append(T82)
    p.append(T83)
    p.append(T84)
    p.append(T85).append(Assets.path("remove.svg")).append(T58)
    p.append(T86)
}

private fun messageTemplate(p: PageParts.Builder, user: UserView) {
    p.append(T116)
    p.append(T117)
    p.append(T118)
    p.append(T119)
    p.append(T120).append(user.id).append("\"\n")
    p.append(T121)
    p.append(T122)
    p.append(T123)
    p.append(T124)
    p.append(T125).append(h(user.title)).append(T126).append(user.path)
        .append(T127).append(h(user.avatarUrl)).append(T128)
    p.append(T129)
    p.append(T130)
    p.append(T131)
    p.append(T132)
    p.append(T133)
    p.append(T134).append(h(user.name)).append(T135)
    p.append(T136)
    p.append(T137)
    p.append(T138)
    p.append(T139)
    p.append(T140)
    p.append(T141).append(Assets.path("menu-dots-horizontal.svg")).append("\" />\n")
    p.append(T142)
    p.append(T143)
    p.append(T144)
    p.append(T145)
    p.append(T146)
    p.append(T147)
    p.append(T148)
    p.append(T114)
    p.append(T149)
    p.append(T150)
}

private fun composer(p: PageParts.Builder, ctx: LayoutContext, roomId: Long) {
    val base = h(ctx.baseUrl)
    p.append(T151)
    p.append(T152)
    p.append(T153)
    p.append(T89).append(Assets.path("search.svg")).append(T58)
    p.append(T154)
    p.append(T155)
    p.append(T156)
        .append(roomId).append(T157).append(roomId).append(T158)
    p.append(T159)
    p.append(T160)
    p.append(T161)
    p.append(T162)
    p.append(T163)
    p.append(T164)
        .append(Assets.path("messages-outlined.svg")).append(T165)
    p.append(T166)
    p.append(T167)
        .append(base).append(T168).append(base)
        .append(T169)
    p.append(T170).append(roomId)
        .append(T171)
    p.append(T172)
    p.append(T173)
    p.append(T174).append(Assets.path("attachment.svg")).append(T175)
    p.append(T176)
    p.append(T177)
    p.append(T178)
    p.append(T179)
    p.append(T174).append(Assets.path("text-options.svg")).append(T58)
    p.append(T180)
    p.append(T181)
    p.append(T182)
    p.append(T183).append(Assets.path("arrow-up.svg")).append(T58)
    p.append(T184)
    p.append(T185)
    p.append(T186)
    p.append(T145)
    p.append(T187)
    p.append(T188)
    p.append(T189)
    p.append(T190)
    p.append(T191)
    p.append(T192)
    p.append(T193)
}

internal fun lightbox(p: PageParts.Builder) {
    p.append(T194)
    p.append(T195)
    p.append(T196)
    p.append(T197)
    p.append(T89).append(Assets.path("remove.svg")).append("\" />\n")
    p.append(T198)
    p.append(T199)
    p.append(T200)
    p.append(T201)
    p.append(T57).append(Assets.path("download.svg")).append("\" />\n")
    p.append(T202)
    p.append(T203)
    p.append(T204)
    p.append(T205)
    p.append(T206)
    p.append(T207)
    p.append(T208)
    p.append(T57).append(Assets.path("share.svg")).append("\" />\n")
    p.append(T209)
    p.append(T210)
    p.append(T211)
}
