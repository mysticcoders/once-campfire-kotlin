package campfire.routes

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The helpers in `crates/routes/src/lib.rs` have no tests of their own in Rust; these check a
 * sample against `config/routes.rb`, and that each helper's path is recognized as the route it
 * names.
 */
class PathsTest {
    /** A sample of helpers spell the Rails paths. */
    @Test
    fun spellsRailsPaths() {
        assertEquals("/", Paths.root())
        assertEquals("/session/transfers/abc", Paths.sessionTransfer("abc"))
        assertEquals("/account/bots/7/key", Paths.accountBotKey(7))
        assertEquals("/users/me/push_subscriptions/3/test_notifications", Paths.userPushSubscriptionTestNotifications(3))
        assertEquals("/rooms/1/k3y/messages/2/boosts/4", Paths.roomBotMessageBoost(1, "k3y", 2, 4))
        assertEquals("/rooms/1/@42", Paths.roomAtMessage(1, 42))
        assertEquals("/service-worker", Paths.serviceWorker())
        assertEquals("/up", Paths.railsHealthCheck())
    }

    /** `direct :fresh_user_avatar` and `direct :fresh_account_logo`. */
    @Test
    fun spellsDirectRoutes() {
        assertEquals("/users/tok/avatar?v=20240101120000", Paths.freshUserAvatar("tok", "20240101120000"))
        assertEquals("/account/logo", Paths.freshAccountLogo(null, null))
        assertEquals("/account/logo?v=1", Paths.freshAccountLogo("1", null))
        assertEquals("/account/logo?size=small", Paths.freshAccountLogo(null, "small"))
        assertEquals("/account/logo?size=small&v=1", Paths.freshAccountLogo("1", "small"))
    }

    /** Each helper's path is recognized as the route the helper is named for. */
    @Test
    fun helpersRecognizeAsTheirRoutes() {
        val table = RouteTable.campfire
        val expected = listOf(
            Paths.firstRun() to "first_runs#show",
            Paths.newSession() to "sessions#new",
            Paths.editAccount() to "accounts#edit",
            Paths.editAccountUser(1) to "accounts/users#edit",
            Paths.newAccountBot() to "accounts/bots#new",
            Paths.accountLogo() to "accounts/logos#show",
            Paths.editAccountCustomStyles() to "accounts/custom_styles#edit",
            Paths.join("code") to "users#new",
            Paths.qrCode("x") to "qr_code#show",
            Paths.user(1) to "users#show",
            Paths.userAvatar(1) to "users/avatars#show",
            Paths.userSidebar() to "users/sidebars#show",
            Paths.editUserProfile() to "users/profiles#edit",
            Paths.userPushSubscriptions() to "users/push_subscriptions#index",
            Paths.autocompletableUsers() to "autocompletable/users#index",
            Paths.rooms() to "rooms#index",
            Paths.editRoom(1) to "rooms#edit",
            Paths.roomMessages(1) to "messages#index",
            Paths.editRoomMessage(1, 2) to "messages#edit",
            Paths.roomBotMessages(1, "k") to "messages/by_bots#index",
            Paths.roomRefresh(1) to "rooms/refreshes#show",
            Paths.roomSettings(1) to "rooms/settings#show",
            Paths.roomInvolvement(1) to "rooms/involvements#show",
            Paths.roomAtMessage(1, 2) to "rooms#show",
            Paths.newRoomsOpen() to "rooms/opens#new",
            Paths.editRoomsClosed(1) to "rooms/closeds#edit",
            Paths.roomsDirect(1) to "rooms/directs#show",
            Paths.messages() to "messages#index",
            Paths.editMessage(1) to "messages#edit",
            Paths.messageBoosts(1) to "messages/boosts#index",
            Paths.newMessageBoost(1) to "messages/boosts#new",
            Paths.searches() to "searches#index",
            Paths.webmanifest() to "pwa#manifest",
            Paths.serviceWorker() to "pwa#service_worker",
            Paths.railsHealthCheck() to "rails/health#show",
        )
        for ((path, endpoint) in expected) {
            val recognized = table.recognize("GET", path)
            assertEquals(endpoint, (recognized as? Recognition.Matched)?.endpoint, path)
        }
        assertEquals("searches#clear", (table.recognize("DELETE", Paths.clearSearches()) as Recognition.Matched).endpoint)
        assertEquals("unfurl_links#create", (table.recognize("POST", Paths.unfurlLink()) as Recognition.Matched).endpoint)
        assertEquals("accounts/join_codes#create", (table.recognize("POST", Paths.accountJoinCode()) as Recognition.Matched).endpoint)
        assertEquals("messages/boosts#destroy", (table.recognize("DELETE", Paths.messageBoost(1, 2)) as Recognition.Matched).endpoint)
        assertEquals("messages/boosts/by_bots#destroy", (table.recognize("DELETE", Paths.roomBotMessageBoost(1, "k", 2, 3)) as Recognition.Matched).endpoint)
    }
}
