package build.raft.app.nav

import build.raft.app.HOME_DRAFTS_PAGE
import build.raft.app.HOME_SAVED_PAGE
import build.raft.app.MEMBERS_PAGE
import build.raft.app.MEMBER_DETAIL_PAGE
import build.raft.app.RAFT_APP_SHELL_PAGE
import build.raft.app.RAFT_FILE_PREVIEW_PAGE
import build.raft.app.RELEASE_NOTES_PAGE
import build.raft.app.SETTINGS_ACCOUNT_PAGE
import build.raft.app.SETTINGS_COMPUTERS_PAGE
import build.raft.app.SETTINGS_HANDS_FEEDBACK_PAGE
import build.raft.app.SETTINGS_HOME_PAGE
import build.raft.app.SETTINGS_NATIVE_DETAIL_PAGE
import build.raft.app.SETTINGS_STORAGE_PAGE
import build.raft.app.SETTINGS_WEB_VIEW_PAGE
import build.raft.app.SettingsNativeDetailPage
import build.raft.app.THREAD_PAGE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Navigation 3 open routing: with the stack off every page keeps the host's way (an Android
 * Activity); with it on only the allowlist (step 2 batch 1: Settings detail pages, Saved, Drafts,
 * member detail) goes onto the stack.
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

    @Test fun `batch 1 allowlist has every Settings detail page`() {
        // One rule per settingsPage value (SettingsNativeDetailPage), trimmed like the page reads it.
        val keys =
            listOf(
                "language-region", "appearance", "notifications", "theme", "mobile", "server", "about",
                "developer", "open-source-licenses", "api-environment", "diagnostics",
                "local-feature-overrides", "local-feature-override",
            )
        assertEquals(keys.toSet(), SettingsNativeDetailPage.entries.map { it.key }.toSet(), "a new settingsPage value: decide stack or Activity")
        keys.forEach { key ->
            assertTrue(RaftMobileStackPages.allows(SETTINGS_NATIVE_DETAIL_PAGE, data("settingsPage" to key)), key)
            assertEquals(RaftStackOpenRoute.Stack, route(SETTINGS_NATIVE_DETAIL_PAGE, data("settingsPage" to key)), key)
            assertEquals(RaftStackOpenRoute.Stack, route(SETTINGS_NATIVE_DETAIL_PAGE, data("settingsPage" to key), fromRoot = false), key)
        }
        assertTrue(RaftMobileStackPages.allows(SETTINGS_NATIVE_DETAIL_PAGE, data("settingsPage" to " about ")))
        assertFalse(RaftMobileStackPages.allows(SETTINGS_NATIVE_DETAIL_PAGE, data("settingsPage" to "billing")))
        assertFalse(RaftMobileStackPages.allows(SETTINGS_NATIVE_DETAIL_PAGE, data()))
        assertFalse(RaftMobileStackPages.allows("compose_thread", about))
    }

    @Test fun `batch 1 allowlist has Saved Drafts and member detail`() {
        val member = data("memberId" to "m1", "memberKind" to "agent", "instanceId" to "members-detail-m1")
        assertEquals(RaftStackOpenRoute.Stack, route(HOME_SAVED_PAGE, data("destination" to "saved")))
        assertEquals(RaftStackOpenRoute.Stack, route(HOME_DRAFTS_PAGE, data("destination" to "drafts")))
        assertEquals(RaftStackOpenRoute.Stack, route(MEMBER_DETAIL_PAGE, member))
        // Member detail opened from a stack page (a profile from Saved): onto the stack too.
        assertEquals(RaftStackOpenRoute.Stack, route(MEMBER_DETAIL_PAGE, member, fromRoot = false))
    }

    @Test fun `pages kept as Activities stay on the host path`() {
        listOf(
            SETTINGS_ACCOUNT_PAGE, // avatar picker; sign-out / delete replace the window
            SETTINGS_HANDS_FEEDBACK_PAGE, // attachment picker, camera
            SETTINGS_WEB_VIEW_PAGE, // WebView file chooser (Billing, Administration, IM Bridges, ...)
            SETTINGS_STORAGE_PAGE,
            SETTINGS_COMPUTERS_PAGE,
            RELEASE_NOTES_PAGE,
            SETTINGS_HOME_PAGE,
            MEMBERS_PAGE,
            THREAD_PAGE,
            RAFT_FILE_PREVIEW_PAGE,
        ).forEach { name ->
            assertEquals(RaftStackOpenRoute.Host, route(name, data("channelId" to "c1")), name)
            assertEquals(RaftStackOpenRoute.Host, route(name, data("channelId" to "c1"), fromRoot = false), name)
        }
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
        assertEquals(RaftStackOpenRoute.Host, route(SETTINGS_NATIVE_DETAIL_PAGE, data("settingsPage" to "billing")))
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
