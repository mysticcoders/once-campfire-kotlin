package campfire.routes

/**
 * Path helpers mirroring `reference/config/routes.rb`, ported from
 * `once-campfire-rust/crates/routes/src/lib.rs`. Names match the Rails `*_path` helpers with the
 * `_path` suffix dropped, in camelCase. Arguments are interpolated with `toString()`, as the Rust
 * helpers interpolate any `Display` value. Shared by controllers (redirects) and views (links).
 */
object Paths {
    /** `root_path` (`root "welcome#show"`). */
    fun root(): String = "/"

    /** `first_run_path` (`resource :first_run`). */
    fun firstRun(): String = "/first_run"

    /** `session_path` (`resource :session`). */
    fun session(): String = "/session"

    /** `new_session_path`. */
    fun newSession(): String = "/session/new"

    /** `session_transfer_path` (`resources :transfers` under `resource :session`). */
    fun sessionTransfer(id: Any): String = "/session/transfers/$id"

    /** `account_path` (`resource :account`). */
    fun account(): String = "/account"

    /** `edit_account_path`. */
    fun editAccount(): String = "/account/edit"

    /** `account_users_path`. */
    fun accountUsers(): String = "/account/users"

    /** `account_user_path`. */
    fun accountUser(id: Any): String = "/account/users/$id"

    /** `edit_account_user_path`. */
    fun editAccountUser(id: Any): String = "/account/users/$id/edit"

    /** `account_bots_path`. */
    fun accountBots(): String = "/account/bots"

    /** `new_account_bot_path`. */
    fun newAccountBot(): String = "/account/bots/new"

    /** `account_bot_path`. */
    fun accountBot(id: Any): String = "/account/bots/$id"

    /** `edit_account_bot_path`. */
    fun editAccountBot(id: Any): String = "/account/bots/$id/edit"

    /** `account_bot_key_path` (`resource :key` under `resources :bots`). */
    fun accountBotKey(botId: Any): String = "/account/bots/$botId/key"

    /** `account_join_code_path`. */
    fun accountJoinCode(): String = "/account/join_code"

    /** `account_logo_path`. */
    fun accountLogo(): String = "/account/logo"

    /** `account_custom_styles_path`. */
    fun accountCustomStyles(): String = "/account/custom_styles"

    /** `edit_account_custom_styles_path`. */
    fun editAccountCustomStyles(): String = "/account/custom_styles/edit"

    /** `join_path` (`get "join/:join_code"`). */
    fun join(joinCode: Any): String = "/join/$joinCode"

    /** `qr_code_path`. */
    fun qrCode(id: Any): String = "/qr_code/$id"

    /** `user_path`. */
    fun user(id: Any): String = "/users/$id"

    /** `user_avatar_path`. */
    fun userAvatar(userId: Any): String = "/users/$userId/avatar"

    /** `user_ban_path`. */
    fun userBan(userId: Any): String = "/users/$userId/ban"

    /** `user_sidebar_path` (`scope defaults: { user_id: "me" }`). */
    fun userSidebar(): String = "/users/me/sidebar"

    /** `user_profile_path`. */
    fun userProfile(): String = "/users/me/profile"

    /** `edit_user_profile_path`. */
    fun editUserProfile(): String = "/users/me/profile/edit"

    /** `user_push_subscriptions_path`. */
    fun userPushSubscriptions(): String = "/users/me/push_subscriptions"

    /** `user_push_subscription_path`. */
    fun userPushSubscription(id: Any): String = "/users/me/push_subscriptions/$id"

    /** `user_push_subscription_test_notifications_path`. */
    fun userPushSubscriptionTestNotifications(pushSubscriptionId: Any): String =
        "/users/me/push_subscriptions/$pushSubscriptionId/test_notifications"

    /** `autocompletable_users_path`. */
    fun autocompletableUsers(): String = "/autocompletable/users"

    /** `rooms_path`. */
    fun rooms(): String = "/rooms"

    /** `new_room_path`. */
    fun newRoom(): String = "/rooms/new"

    /** `room_path`. */
    fun room(id: Any): String = "/rooms/$id"

    /** `edit_room_path`. */
    fun editRoom(id: Any): String = "/rooms/$id/edit"

    /** `room_messages_path`. */
    fun roomMessages(roomId: Any): String = "/rooms/$roomId/messages"

    /** `new_room_message_path`. */
    fun newRoomMessage(roomId: Any): String = "/rooms/$roomId/messages/new"

    /** `room_message_path`. */
    fun roomMessage(roomId: Any, id: Any): String = "/rooms/$roomId/messages/$id"

    /** `edit_room_message_path`. */
    fun editRoomMessage(roomId: Any, id: Any): String = "/rooms/$roomId/messages/$id/edit"

    /** `room_bot_messages_path` (`scope path: ":bot_key"`). */
    fun roomBotMessages(roomId: Any, botKey: Any): String = "/rooms/$roomId/$botKey/messages"

    /** `room_bot_message_path`. */
    fun roomBotMessage(roomId: Any, botKey: Any, id: Any): String = "/rooms/$roomId/$botKey/messages/$id"

    /** `room_bot_message_boosts_path`. */
    fun roomBotMessageBoosts(roomId: Any, botKey: Any, messageId: Any): String =
        "/rooms/$roomId/$botKey/messages/$messageId/boosts"

    /** `room_bot_message_boost_path`. */
    fun roomBotMessageBoost(roomId: Any, botKey: Any, messageId: Any, id: Any): String =
        "/rooms/$roomId/$botKey/messages/$messageId/boosts/$id"

    /** `room_refresh_path`. */
    fun roomRefresh(roomId: Any): String = "/rooms/$roomId/refresh"

    /** `room_settings_path`. */
    fun roomSettings(roomId: Any): String = "/rooms/$roomId/settings"

    /** `room_involvement_path`. */
    fun roomInvolvement(roomId: Any): String = "/rooms/$roomId/involvement"

    /** `room_at_message_path` (`get "@:message_id"`). */
    fun roomAtMessage(roomId: Any, messageId: Any): String = "/rooms/$roomId/@$messageId"

    /** `rooms_opens_path` (`namespace :rooms { resources :opens }`). */
    fun roomsOpens(): String = "/rooms/opens"

    /** `new_rooms_open_path`. */
    fun newRoomsOpen(): String = "/rooms/opens/new"

    /** `rooms_open_path`. */
    fun roomsOpen(id: Any): String = "/rooms/opens/$id"

    /** `edit_rooms_open_path`. */
    fun editRoomsOpen(id: Any): String = "/rooms/opens/$id/edit"

    /** `rooms_closeds_path`. */
    fun roomsCloseds(): String = "/rooms/closeds"

    /** `new_rooms_closed_path`. */
    fun newRoomsClosed(): String = "/rooms/closeds/new"

    /** `rooms_closed_path`. */
    fun roomsClosed(id: Any): String = "/rooms/closeds/$id"

    /** `edit_rooms_closed_path`. */
    fun editRoomsClosed(id: Any): String = "/rooms/closeds/$id/edit"

    /** `rooms_directs_path`. */
    fun roomsDirects(): String = "/rooms/directs"

    /** `new_rooms_direct_path`. */
    fun newRoomsDirect(): String = "/rooms/directs/new"

    /** `rooms_direct_path`. */
    fun roomsDirect(id: Any): String = "/rooms/directs/$id"

    /** `edit_rooms_direct_path`. */
    fun editRoomsDirect(id: Any): String = "/rooms/directs/$id/edit"

    /** `messages_path`. */
    fun messages(): String = "/messages"

    /** `message_path`. */
    fun message(id: Any): String = "/messages/$id"

    /** `edit_message_path`. */
    fun editMessage(id: Any): String = "/messages/$id/edit"

    /** `message_boosts_path`. */
    fun messageBoosts(messageId: Any): String = "/messages/$messageId/boosts"

    /** `new_message_boost_path`. */
    fun newMessageBoost(messageId: Any): String = "/messages/$messageId/boosts/new"

    /** `message_boost_path`. */
    fun messageBoost(messageId: Any, id: Any): String = "/messages/$messageId/boosts/$id"

    /** `searches_path`. */
    fun searches(): String = "/searches"

    /** `clear_searches_path`. */
    fun clearSearches(): String = "/searches/clear"

    /** `unfurl_link_path`. */
    fun unfurlLink(): String = "/unfurl_link"

    /** `webmanifest_path` (`get "webmanifest" => "pwa#manifest"`). */
    fun webmanifest(): String = "/webmanifest"

    /** `service_worker_path`. */
    fun serviceWorker(): String = "/service-worker"

    /** `rails_health_check_path` (`get "up" => "rails/health#show"`). */
    fun railsHealthCheck(): String = "/up"

    /**
     * `direct :fresh_user_avatar`: the cache-busting avatar URL keyed by the signed avatar token
     * (`fresh_user_avatar` in `crates/routes/src/lib.rs`).
     */
    fun freshUserAvatar(avatarToken: Any, updatedAtNumber: Any): String =
        "/users/$avatarToken/avatar?v=$updatedAtNumber"

    /**
     * `direct :fresh_account_logo`: `v` is the account's `updated_at.to_fs(:number)`, when present;
     * `size` comes first in the query, as in `crates/routes/src/lib.rs`.
     */
    fun freshAccountLogo(v: String?, size: String?): String {
        val query = listOfNotNull(size?.let { "size=$it" }, v?.let { "v=$it" })
        return if (query.isEmpty()) accountLogo() else accountLogo() + "?" + query.joinToString("&")
    }
}
