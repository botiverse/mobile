package build.raft.app.nav

import build.raft.app.settings.RAFT_APP_SHELL_PAGE
import build.raft.app.RAFT_IN_APP_BROWSER_OUTBOUND_KEY
import build.raft.app.RAFT_IN_APP_BROWSER_PAGE
import build.raft.app.RAFT_IN_APP_BROWSER_TAB_ID_KEY
import build.raft.app.RAFT_IN_APP_BROWSER_URL_KEY
import build.raft.app.settings.SETTINGS_NATIVE_DETAIL_PAGE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Navigation 3 open routing: with the stack off every page keeps the host's way (an Android
 * Activity); with it on only the allowlist (Settings detail pages, Saved, Drafts, member detail,
 * Account, Feedback, Settings web pages, Storage, Release notes, Computers, chat & file pages,
 * Artifact viewer, in-app Raft App browser, Search) goes onto the stack.
 */
class RaftStackPageAllowlistTest {
    private fun data(vararg pairs: Pair<String, Any>) =
        JsonObject(pairs.associate { (k, v) -> k to if (v is Boolean) JsonPrimitive(v) else JsonPrimitive(v.toString()) })

    private val about = data("settingsPage" to "about", "slockShellInstanceId" to "shell-1")

    private fun route(
        pageName: String,
        pageData: JsonObject,
        enabled: Boolean = true,
        fromRoot: Boolean = true,
    ) = raftStackOpenRoute(enabled, RaftMobileStackPages, pageName, pageData, fromRoot)

    @Test fun `stack off keeps every page on the host path`() {
        assertEquals(RaftStackOpenRoute.Host, route(SETTINGS_NATIVE_DETAIL_PAGE, about, enabled = false))
        assertEquals(RaftStackOpenRoute.Host, route(SETTINGS_NATIVE_DETAIL_PAGE, about, enabled = false, fromRoot = false))
        assertEquals(RaftStackOpenRoute.Host, route("compose_thread", data("channelId" to "c1"), enabled = false))
    }

    @Test fun `stack on opens allowlisted pages including in-app browser on the stack and the rest on the host`() {
        assertEquals(RaftStackOpenRoute.Stack, route(SETTINGS_NATIVE_DETAIL_PAGE, about))
        assertEquals(RaftStackOpenRoute.Stack, route(SETTINGS_NATIVE_DETAIL_PAGE, about, fromRoot = false))
        assertEquals(
            RaftStackOpenRoute.Stack,
            route(RAFT_IN_APP_BROWSER_PAGE, data(RAFT_IN_APP_BROWSER_URL_KEY to "https://stamp.build/pulls/1", RAFT_IN_APP_BROWSER_TAB_ID_KEY to "1")),
        )
        assertEquals(
            RaftStackOpenRoute.Stack,
            route(RAFT_IN_APP_BROWSER_PAGE, data(RAFT_IN_APP_BROWSER_URL_KEY to "https://example.org/doc", RAFT_IN_APP_BROWSER_OUTBOUND_KEY to true), fromRoot = false),
        )
        assertTrue(RAFT_IN_APP_BROWSER_PAGE in RaftStackCoveredKeepsSizePages)
        assertEquals(RaftStackOpenRoute.Host, route("compose_login", data()))
        assertEquals(RaftStackOpenRoute.Host, route(SETTINGS_NATIVE_DETAIL_PAGE, data("settingsPage" to "billing")))
    }

    @Test fun `in-app browser route identity distinguishes app tabs and cross-origin outbound child pages`() {
        val appTab1 =
            raftPageRouteIdentity(
                RAFT_IN_APP_BROWSER_PAGE,
                data(RAFT_IN_APP_BROWSER_URL_KEY to "https://stamp.build/pulls/1", RAFT_IN_APP_BROWSER_TAB_ID_KEY to "1"),
            )
        val appTab2 =
            raftPageRouteIdentity(
                RAFT_IN_APP_BROWSER_PAGE,
                data(RAFT_IN_APP_BROWSER_URL_KEY to "https://stamp.build/pulls/2", RAFT_IN_APP_BROWSER_TAB_ID_KEY to "2"),
            )
        val outboundChild =
            raftPageRouteIdentity(
                RAFT_IN_APP_BROWSER_PAGE,
                data(RAFT_IN_APP_BROWSER_URL_KEY to "https://docs.example.org/spec", RAFT_IN_APP_BROWSER_OUTBOUND_KEY to true),
            )
        val otherOutboundChild =
            raftPageRouteIdentity(
                RAFT_IN_APP_BROWSER_PAGE,
                data(RAFT_IN_APP_BROWSER_URL_KEY to "https://github.com/botiverse/mobile", RAFT_IN_APP_BROWSER_OUTBOUND_KEY to true),
            )
        assertNotEquals(appTab1, appTab2)
        assertNotEquals(appTab1, outboundChild)
        assertNotEquals(outboundChild, otherOutboundChild)
    }

    @Test fun `the app shell never goes onto the stack`() {
        val allowAll = RaftStackPageAllowlist(listOf(RaftStackPageRule(RAFT_APP_SHELL_PAGE)))
        assertEquals(RaftStackOpenRoute.Host, raftStackOpenRoute(true, allowAll, RAFT_APP_SHELL_PAGE, data(), fromRoot = false))
    }
}
