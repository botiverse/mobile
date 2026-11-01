package build.raft.app.nav

import build.raft.app.RAFT_APP_SHELL_PAGE
import build.raft.app.SETTINGS_NATIVE_DETAIL_PAGE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Navigation 3 step 1 open routing: with the stack off every page keeps the host's way (an Android
 * Activity); with it on only the allowlist (Settings > About) goes onto the stack.
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

    @Test fun `step 1 allowlist is Settings About only`() {
        assertTrue(RaftMobileStackPages.allows(SETTINGS_NATIVE_DETAIL_PAGE, about))
        assertTrue(RaftMobileStackPages.allows(SETTINGS_NATIVE_DETAIL_PAGE, data("settingsPage" to " about ")))
        assertFalse(RaftMobileStackPages.allows(SETTINGS_NATIVE_DETAIL_PAGE, data("settingsPage" to "developer")))
        assertFalse(RaftMobileStackPages.allows(SETTINGS_NATIVE_DETAIL_PAGE, data()))
        assertFalse(RaftMobileStackPages.allows("compose_thread", about))
    }

    @Test fun `stack off keeps every page on the host path`() {
        assertEquals(RaftStackOpenRoute.Host, route(SETTINGS_NATIVE_DETAIL_PAGE, about, enabled = false))
        assertEquals(RaftStackOpenRoute.Host, route(SETTINGS_NATIVE_DETAIL_PAGE, about, enabled = false, fromRoot = false))
        assertEquals(RaftStackOpenRoute.Host, route("compose_thread", data("channelId" to "c1"), enabled = false))
    }

    @Test fun `stack on opens allowlisted pages on the stack and the rest on the host`() {
        assertEquals(RaftStackOpenRoute.Stack, route(SETTINGS_NATIVE_DETAIL_PAGE, about))
        assertEquals(RaftStackOpenRoute.Stack, route(SETTINGS_NATIVE_DETAIL_PAGE, about, fromRoot = false))
        assertEquals(RaftStackOpenRoute.Host, route("compose_thread", data("channelId" to "c1")))
        assertEquals(RaftStackOpenRoute.Host, route(SETTINGS_NATIVE_DETAIL_PAGE, data("settingsPage" to "developer")))
    }

    @Test fun `the app shell never goes onto the stack`() {
        val allowAll = RaftStackPageAllowlist(listOf(RaftStackPageRule(RAFT_APP_SHELL_PAGE)))
        assertEquals(RaftStackOpenRoute.Host, raftStackOpenRoute(true, allowAll, RAFT_APP_SHELL_PAGE, data(), fromRoot = false))
    }

    @Test fun `replace from the root keeps the host path and replace from a stack page stays on the stack`() {
        val replacing = JsonObject(about + ("slockReplaceCurrentPage" to JsonPrimitive(true)))
        assertEquals(RaftStackOpenRoute.Host, route(SETTINGS_NATIVE_DETAIL_PAGE, replacing, fromRoot = true))
        assertEquals(RaftStackOpenRoute.Stack, route(SETTINGS_NATIVE_DETAIL_PAGE, replacing, fromRoot = false))
    }
}
