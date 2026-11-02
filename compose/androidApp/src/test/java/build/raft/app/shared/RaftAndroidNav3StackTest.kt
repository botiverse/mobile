package build.raft.app.shared

import build.raft.app.cmp.host.HostBridgeTransport
import build.raft.app.cmp.host.RaftCmpPageArgs
import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.nav.RaftPageStack
import build.raft.app.nav.RaftPageStackSavedState
import build.raft.app.nav.RaftStackOpenRoute
import build.raft.app.nav.RaftStackPage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Navigation 3 step 1 in the Android shell: the switch off (no stack) keeps every open on the
 * Activity path; on, only the allowlist goes onto the stack; restore takes only allowlisted keys.
 */
class RaftAndroidNav3StackTest {
    private val about =
        JsonObject(mapOf("settingsPage" to JsonPrimitive("about"), "slockShellInstanceId" to JsonPrimitive("shell-1")))
    private val thread = JsonObject(mapOf("channelId" to JsonPrimitive("c1")))

    @Test
    fun switchOffKeepsEveryPageOnTheActivityPath() {
        listOf(SETTINGS_DETAIL to about, RaftPages.THREAD to thread, RaftPages.APP_SHELL to JsonObject(emptyMap()))
            .forEach { (name, data) ->
                assertEquals(name, RaftStackOpenRoute.Host, raftAndroidNav3OpenRoute(stack = null, name, data, from = null))
            }
    }

    @Test
    fun onlyTheAppShellWindowHostsTheStack() {
        // Short-circuits before the process decision: no other page ever draws the stack.
        assertFalse(raftAndroidNav3StackEnabled(RaftPages.THREAD) { InMemoryRaftKeyValueStore() })
        assertFalse(raftAndroidNav3StackEnabled(RaftPages.LOGIN) { InMemoryRaftKeyValueStore() })
    }

    @Test
    fun switchOnRoutesTheAllowlistToTheStackAndTheRestToActivities() {
        val stack = RaftPageStack()
        assertEquals(RaftStackOpenRoute.Stack, raftAndroidNav3OpenRoute(stack, SETTINGS_DETAIL, about, from = null))
        stack.open(SETTINGS_DETAIL, about, from = stack.pages.first())
        val aboutPage = stack.pages.last()
        assertEquals(RaftStackOpenRoute.Stack, raftAndroidNav3OpenRoute(stack, SETTINGS_DETAIL, about, from = aboutPage))
        // Mixing: a page not on the allowlist opened from a stack page still gets an Activity.
        assertEquals(RaftStackOpenRoute.Host, raftAndroidNav3OpenRoute(stack, RaftPages.THREAD, thread, from = aboutPage))
        assertEquals(RaftStackOpenRoute.Host, raftAndroidNav3OpenRoute(stack, RaftPages.APP_SHELL, about, from = aboutPage))
    }

    @Test
    fun restoreTakesOnlyAllowlistedPages() {
        val stack = RaftPageStack().apply { open(SETTINGS_DETAIL, about, from = pages.first()) }
        val text = RaftPageStackSavedState.encode(stack.savedKeys())
        assertEquals(stack.savedKeys(), raftAndroidNav3RestoredKeys(text))

        val dropped = RaftPageStack().apply { open(RaftPages.THREAD, thread, from = pages.first()) }
        assertNull(raftAndroidNav3RestoredKeys(RaftPageStackSavedState.encode(dropped.savedKeys())))
        assertNull(raftAndroidNav3RestoredKeys(null))
    }

    @Test
    fun aStackPageClosePageClosesThePageNotTheWindow() {
        val calls = mutableListOf<String>()
        var closed = 0
        val transport =
            RaftStackPageBridgeTransport(
                delegate = HostBridgeTransport { method, _, _ -> calls += method },
                onClosePage = { closed++ },
            )
        transport.call("closePage", "{}", null)
        transport.call("toast", "{}", null)
        assertEquals(1, closed)
        assertEquals(listOf("toast"), calls)
    }

    // Step 2 batch 1 -------------------------------------------------------------------------------

    private fun settings(page: String, vararg extra: Pair<String, String>) =
        JsonObject(
            mapOf("settingsPage" to JsonPrimitive(page), "slockShellInstanceId" to JsonPrimitive("shell-1")) +
                extra.associate { (k, v) -> k to JsonPrimitive(v) }
        )

    /** Opens [pageName] the way RaftCmpActivity.openPageFrom does; true when it went onto the stack. */
    private fun RaftPageStack.openLikeTheShell(pageName: String, pageData: JsonObject, from: RaftStackPage?): Boolean {
        if (raftAndroidNav3OpenRoute(this, pageName, pageData, from) != RaftStackOpenRoute.Stack) return false
        open(pageName, pageData, from = from ?: pages.first())
        return true
    }

    @Test
    fun batch1PagesOpenOnTheStackFromTheShellAndFromStackPages() {
        val stack = RaftPageStack()
        val member = JsonObject(mapOf("memberId" to JsonPrimitive("m1"), "memberKind" to JsonPrimitive("agent")))
        listOf(
            SETTINGS_DETAIL to settings("notifications"),
            SETTINGS_DETAIL to settings("developer"),
            HOME_SAVED to JsonObject(mapOf("destination" to JsonPrimitive("saved"))),
            HOME_DRAFTS to JsonObject(mapOf("destination" to JsonPrimitive("drafts"))),
            MEMBER_DETAIL to member,
        ).forEach { (name, data) ->
            assertTrue(name, stack.openLikeTheShell(name, data, from = null))
            assertTrue(name, stack.openLikeTheShell(name, data, from = stack.pages.last()))
        }
        // Kept as Activities: Account (avatar picker), the Settings web pages, Feedback.
        listOf(RaftPages.SETTINGS_ACCOUNT, "compose_settings_webview", "compose_settings_hands_feedback").forEach { name ->
            assertFalse(name, stack.openLikeTheShell(name, settings("about"), from = stack.pages.last()))
        }
    }

    @Test
    fun backOrderSettingsSubPageThenSettingsThenShell() {
        // Phone Settings is the shell's tab: the shell (root) shows it, its rows open detail pages.
        val stack = RaftPageStack()
        assertTrue(stack.openLikeTheShell(SETTINGS_DETAIL, settings("developer"), from = null))
        val developer = stack.pages.last()
        // Developer > API environment, a Settings sub-page opened from a stack page.
        assertTrue(stack.openLikeTheShell(SETTINGS_DETAIL, settings("api-environment"), from = developer))
        assertEquals(listOf(RaftPages.APP_SHELL, SETTINGS_DETAIL, SETTINGS_DETAIL), stack.pages.map { it.args.pageName })

        assertTrue(stack.back())
        assertEquals(developer, stack.pages.last())
        assertTrue(stack.back())
        assertEquals("Back lands on the shell (its Settings tab)", listOf(RaftPages.APP_SHELL), stack.pages.map { it.args.pageName })
        assertFalse("shell alone: Back is the shell's own (Settings > Back, then move task to back)", stack.back())
    }

    @Test
    fun processDeathRestoresSettingsTabAndSubPages() {
        val before = RaftPageStack()
        before.openLikeTheShell(SETTINGS_DETAIL, settings("diagnostics"), from = null)
        before.openLikeTheShell(SETTINGS_DETAIL, settings("local-feature-overrides"), from = before.pages.last())
        val savedText = RaftPageStackSavedState.encode(before.savedKeys())
        val savedRootTab = "more" // the phone Settings tab, written by the shell (RaftStackRootTab)

        val after = RaftPageStack()
        val keys = raftAndroidNav3RestoredKeys(savedText)
        assertTrue(keys != null && after.restore(keys))
        assertEquals("both Settings pages come back, in order", before.savedKeys(), after.savedKeys())
        assertEquals(
            listOf("diagnostics", "local-feature-overrides"),
            after.pages.drop(1).map { (it.key.pageData["settingsPage"] as JsonPrimitive).content },
        )
        val shellArgs = raftAndroidNav3ShellArgs(RaftCmpPageArgs(RaftPages.APP_SHELL, JsonObject(emptyMap())), after, savedRootTab)
        assertEquals("the shell comes back on Settings, not Home", JsonPrimitive("more"), shellArgs.pageData["slockInitialTab"])

        // Back after the restore: sub-page, sub-page, then the shell on its Settings tab.
        assertTrue(after.back())
        assertTrue(after.back())
        assertFalse(after.back())
    }

    @Test
    fun shellArgsUnchangedWithoutRestoredPages() {
        val args = RaftCmpPageArgs(RaftPages.APP_SHELL, JsonObject(emptyMap()))
        assertSame(args, raftAndroidNav3ShellArgs(args, stack = null, savedRootTab = "more"))
        assertSame(args, raftAndroidNav3ShellArgs(args, RaftPageStack(), savedRootTab = "more"))
        val withPage = RaftPageStack().apply { openLikeTheShell(SETTINGS_DETAIL, settings("about"), from = null) }
        assertSame(args, raftAndroidNav3ShellArgs(args, withPage, savedRootTab = null))
        assertSame(args, raftAndroidNav3ShellArgs(args, withPage, savedRootTab = "home"))
    }

    @Test
    fun mixingAnActivityPageOverAStackPageLeavesTheStackAsItWas() {
        val stack = RaftPageStack()
        stack.openLikeTheShell(MEMBER_DETAIL, JsonObject(mapOf("memberId" to JsonPrimitive("m1"))), from = null)
        val profile = stack.pages.last()
        val before = stack.savedKeys()
        // The profile's DM, Settings > Server > Administration (web page): Activities over the window.
        assertFalse(stack.openLikeTheShell(RaftPages.THREAD, thread, from = profile))
        assertFalse(stack.openLikeTheShell("compose_settings_webview", settings("about"), from = profile))
        assertEquals("the Activity covers the window; finishing it shows the profile again", before, stack.savedKeys())
        assertEquals(profile, stack.pages.last())
        assertTrue(stack.back())
        assertEquals(1, stack.pages.size)
    }

    private companion object {
        const val SETTINGS_DETAIL = "compose_settings_native_detail"
        const val HOME_SAVED = "compose_home_saved"
        const val HOME_DRAFTS = "compose_home_drafts"
        const val MEMBER_DETAIL = "compose_member_detail"
    }
}
