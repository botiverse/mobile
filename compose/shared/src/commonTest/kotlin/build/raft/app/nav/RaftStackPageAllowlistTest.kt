package build.raft.app.nav

import build.raft.app.RAFT_APP_SHELL_PAGE
import build.raft.app.RAFT_IN_APP_BROWSER_PAGE
import build.raft.app.SETTINGS_NATIVE_DETAIL_PAGE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Navigation 3 open routing: with the stack off every page keeps the host's way (an Android
 * Activity); with it on only the allowlist (step 2 batch 1: Settings detail pages, Saved, Drafts,
 * member detail; batch 2: Account, Feedback, five Settings web pages, Storage, Release notes,
 * Computers; batch 3: the chat and its pages, the file pages, Search) goes onto the stack.
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

    @Test fun `stack on opens allowlisted pages on the stack and the rest on the host`() {
        assertEquals(RaftStackOpenRoute.Stack, route(SETTINGS_NATIVE_DETAIL_PAGE, about))
        assertEquals(RaftStackOpenRoute.Stack, route(SETTINGS_NATIVE_DETAIL_PAGE, about, fromRoot = false))
        assertEquals(RaftStackOpenRoute.Host, route(RAFT_IN_APP_BROWSER_PAGE, data("url" to "https://example.com")))
        assertEquals(RaftStackOpenRoute.Host, route(SETTINGS_NATIVE_DETAIL_PAGE, data("settingsPage" to "billing")))
    }

    @Test fun `the app shell never goes onto the stack`() {
        val allowAll = RaftStackPageAllowlist(listOf(RaftStackPageRule(RAFT_APP_SHELL_PAGE)))
        assertEquals(RaftStackOpenRoute.Host, raftStackOpenRoute(true, allowAll, RAFT_APP_SHELL_PAGE, data(), fromRoot = false))
    }
}
