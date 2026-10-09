package campfire.app

import campfire.db.Message
import campfire.db.MessageKey
import campfire.db.PageMessage
import campfire.db.Reader
import campfire.db.Room
import campfire.db.RoomKind
import campfire.db.User
import campfire.kit.Action
import campfire.kit.Ctx
import campfire.kit.Fragment
import campfire.kit.Mime
import campfire.kit.Redirect
import campfire.kit.halt
import campfire.rails.globalIdParam
import campfire.views.BoostView
import campfire.views.FragmentCache
import campfire.views.FragmentKeys
import campfire.views.LayoutContext
import campfire.views.MessageView
import campfire.views.ShowView
import campfire.views.Unsupported
import campfire.views.UserView
import campfire.views.renderMessage
import campfire.views.renderRoomShow

/** `RoomsController` (`crates/campfire/src/controllers/rooms.rs`). */
object RoomsController {
    /**
     * `show`, also `GET /rooms/:room_id/@:message_id`. A response the [ResponseCache] kept for
     * this request is served once authentication, the room's permission check and the
     * `last_room` cookie have run.
     */
    val show = Action { c ->
        beforeActions(c)
        val room = setRoom(c)
        rememberLastRoomVisited(c, room.id)
        ResponseCache.lookup(c) ?: renderShow(c, room)
    }

    /** `set_room`: `room_scope.find_by(id: params[:room_id] || params[:id])`, or back to the root with an alert. */
    private fun setRoom(c: Ctx): Room {
        val user = currentUser(c) ?: throw IllegalStateException("set_room without Current.user")
        val id = (c.paramStr("room_id") ?: c.paramStr("id"))?.let { campfire.ruby.integerCast(it) }
        val room = id?.let { roomId -> c.state<App>().db.read { it.roomForUser(user.id, roomId) } }
        return room ?: halt(c.redirectTo(c.urlFor("/"), Redirect(alert = "Room not found or inaccessible")))
    }

    /** `rooms/show` with `find_messages`: the last page (the page around `params[:message_id]` isn't ported yet). */
    private fun renderShow(c: Ctx, room: Room): campfire.kit.Response {
        c.respondTo(Mime.HTML)
        if (c.paramStr("message_id") != null) throw Unsupported("the page around a message")
        val app = c.state<App>()
        val fragments = ResponseCache.fragments(c)
        val user = currentUser(c)!!
        val show = app.db.read { r ->
            val messages = r.lastPage<Fragment>(room.id) { fragments.get(messageKey(it), Fragment::class.java) }
            val presenter = Presenter(r, app, fragments, c.now(), c.request.host())
            val original = r.originalRoomId() == room.id
            ShowView(
                roomId = room.id,
                kind = room.kind,
                displayName = displayName(r, room, user),
                loadedAt = room.updatedAt.epochMs(),
                messages = messages.map { presenter.fragment(it) },
                invitation = original && !r.paged(room.id),
                joinCode = r.accountJoinCode() ?: "",
                streamName = app.secrets.signedStreamName(globalIdParam(room.kind.modelName, room.id), "messages"),
            )
        }
        val frame = c.isTurboFrameRequest
        val parts = renderRoomShow(layout(c), show, frame)
        if (!frame) addPreloadLinks(c)
        return c.renderPage(200, Mime.HTML, parts)
    }
}

/**
 * `Layout::load` (`crates/campfire/src/controllers/presenters/view_context.rs`): the account, its
 * logo, the last room visited, and the flash, as the layout shows them.
 */
fun layout(c: Ctx): LayoutContext {
    val app = c.state<App>()
    val user = currentUser(c)!!
    val flash = c.flash()
    val lastRoom = c.cookies["last_room"]?.let { campfire.ruby.integerCast(it) }
    return app.db.read { r ->
        val account = r.layoutAccount()
        val hasLogo = account != null && r.hasAttachedBlob("Account", account.id, "logo")
        (lastRoom?.let { r.roomIdForUser(user.id, it) }) ?: r.firstRoomIdForUser(user.id)
        LayoutContext(
            user = userView(app, user),
            administrator = user.administrator,
            baseUrl = c.request.protocol + c.request.hostWithPort(),
            accountLogoUrl = "/account/logo?v=" + (account?.updatedAt?.number() ?: ""),
            hasLogo = hasLogo,
            customStyles = account?.customStyles,
            vapidPublicKey = app.config.vapidPublicKey,
            platform = platform(c).toView(),
            flashNotice = flash.getStr("notice"),
            flashAlert = flash.getStr("alert"),
        )
    }
}

/** The `Link` preload header `stylesheet_link_tag` adds for a page in the application layout. */
fun addPreloadLinks(c: Ctx) {
    val existing = c.headers["link"]
    c.setHeader("link", if (existing.isNullOrEmpty()) campfire.assets.Assets.linkHeader else campfire.assets.Assets.appendPreloadLinks(existing, campfire.assets.Assets.stylesheetTagsAll.preloadLinks))
}

/** `render template: "sessions/incompatible_browser"` (200, HTML whatever the format), in the frame layout for a Turbo-Frame request. */
fun renderIncompatibleBrowser(c: Ctx): campfire.kit.Response {
    val frame = c.isTurboFrameRequest
    val parts = campfire.views.renderIncompatibleBrowser(layout(c), frame)
    if (!frame) addPreloadLinks(c)
    return c.renderPage(200, Mime.HTML, parts)
}

/** `cache [ message, "presentation-v3" ]`'s key for the message's version (`crates/views/src/messages.rs`). */
private fun messageKey(key: MessageKey) = FragmentKeys.record("messages/_message", "messages", key.id, key.version, "presentation-v3")

/** `String#all_emoji?` (reference/lib/rails_ext/string.rb). */
private val ALL_EMOJI = Regex("\\A(\\p{IsEmoji_Presentation}|\\p{IsExtended_Pictographic}|\\x{FE0F})+\\z")

/** A user as the views show one, with the fresh avatar path (`presenters.rs` `user_view`). */
private fun userView(app: App, user: User) =
    UserView(user.id, user.name, user.title, "/users/${app.secrets.avatarToken(user.id)}/avatar?v=${user.updatedAt.number()}")

/** `room_display_name(room, for_user:)`. */
private fun displayName(r: Reader, room: Room, forUser: User?): String {
    if (room.kind != RoomKind.Direct) return room.name ?: ""
    val names = r.roomUsers(room.id).filter { forUser == null || it.id != forUser.id }.map { it.name }
    val sentence = when (names.size) {
        0 -> ""
        1 -> names[0]
        2 -> "${names[0]} and ${names[1]}"
        else -> names.dropLast(1).joinToString(", ") + ", and " + names.last()
    }
    return if (sentence.isBlank()) forUser?.name ?: "" else sentence
}

/**
 * Looks rows up for a page of messages, remembering the rooms and users it has seen; message
 * fragments come from and go to [fragments], the request's view of the fragment store.
 */
private class Presenter(private val r: Reader, private val app: App, private val fragments: FragmentCache, now: java.time.Instant, requestHost: String?) {
    private val richText = campfire.richtext.RenderContext(DbResolver(r, app.secrets, now), requestHost)
    private val users = HashMap<Long, User?>()
    private val roomNames = HashMap<Long, String>()

    /** The message's cached fragment, or its render, stored for the next request. */
    fun fragment(page: PageMessage<Fragment>): Fragment {
        page.fragment?.let { return it }
        return fragments.fetch(messageKey(page.key), Fragment::class.java, { it.bytes.size }) {
            Fragment(renderMessage(view(page.message!!)).toByteArray(Charsets.UTF_8))
        }
    }

    /**
     * A message as `messages/_message` shows it (`presenters.rs` `message`): unrenderable when its
     * creator is gone, or when its plain text raises, as `message_tag` rescues.
     */
    private fun view(message: Message): MessageView {
        val roomName = roomNames.getOrPut(message.roomId) {
            val room = r.room(message.roomId) ?: error("room ${message.roomId}")
            displayName(r, room, null)
        }
        val creator = user(message.creatorId) ?: return unrenderable(message, roomName)
        val body = r.richTextBody("Message", message.id, "body") ?: ""
        val plainText = try {
            campfire.richtext.RichText.toPlainText(body, richText)
        } catch (e: campfire.richtext.RichTextError.Unrenderable) {
            throw IllegalStateException("message_tag's rescue raised logging $e")
        } catch (_: campfire.richtext.RichTextError) {
            return unrenderable(message, roomName)
        }
        if (r.hasAttachedBlob("Message", message.id, "attachment")) throw Unsupported("attachments")
        if (plainText.startsWith("/play ")) throw Unsupported("sounds")
        val html = when (val presented = campfire.richtext.RichText.presentMessage(body, richText)) {
            is campfire.richtext.Presentation.Html -> presented.html
            campfire.richtext.Presentation.Unrenderable -> return unrenderable(message, roomName)
        }
        val boosts = r.boosts(message.id).map { b ->
            val booster = user(b.boosterId) ?: throw Unsupported("a boost by a deleted user")
            BoostView(b.id, b.messageId, b.content, ALL_EMOJI.matches(b.content), userView(app, booster))
        }
        return MessageView(
            id = message.id,
            clientMessageId = message.clientMessageId,
            roomId = message.roomId,
            roomName = roomName,
            creator = userView(app, creator),
            createdAt = message.createdAt,
            updatedAt = message.updatedAt,
            allEmoji = ALL_EMOJI.matches(plainText),
            bodyHtml = html,
            boosts = boosts,
        )
    }

    private fun unrenderable(message: Message, roomName: String) = MessageView(
        id = message.id,
        clientMessageId = message.clientMessageId,
        roomId = message.roomId,
        roomName = roomName,
        creator = UserView(message.creatorId, "", "", ""),
        createdAt = message.createdAt,
        updatedAt = message.updatedAt,
        allEmoji = false,
        bodyHtml = "",
        boosts = emptyList(),
        unrenderable = true,
    )

    private fun user(id: Long) = users.getOrPut(id) { r.user(id) }
}
