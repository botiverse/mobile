package build.raft.app

import build.raft.app.settings.AppShellChildTabEntry
import build.raft.app.settings.AppShellChildTabRegistry
import build.raft.app.settings.AppShellChildTabRenderer
import build.raft.app.shell.AppShellServerTabKey
import build.raft.app.shell.RAFT_APP_SHELL_SERVER_PAGE_SHELL_ID
import build.raft.app.shell.RAFT_APP_SHELL_SERVER_PAGE_TAB
import build.raft.app.shell.RaftStackShellTabs
import build.raft.app.shell.appShellServerPagePushes
import build.raft.app.shell.appShellServerStackPageData
import build.raft.app.shell.resolveAppShellServerStackContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AppShellServerStackPageTest {
    @Test
    fun appShellServerPagePushesOnPhoneWhenStackRootOrPlatformSupportsStackPage() {
        assertTrue(appShellServerPagePushes(stackRoot = true, navRailActive = false, platformSupportsServerStackPage = false))
        assertTrue(appShellServerPagePushes(stackRoot = false, navRailActive = false, platformSupportsServerStackPage = true))
        assertFalse(appShellServerPagePushes(stackRoot = false, navRailActive = false, platformSupportsServerStackPage = false))
        assertFalse(appShellServerPagePushes(stackRoot = true, navRailActive = true, platformSupportsServerStackPage = false))
        assertFalse(appShellServerPagePushes(stackRoot = false, navRailActive = true, platformSupportsServerStackPage = true))
    }

    @Test
    fun pageDataOmitsShellIdWhenNullOrBlankAndPreservesTrimmedShellId() {
        val withoutShellId = appShellServerStackPageData(AppShellServerTabKey)
        assertEquals(AppShellServerTabKey, withoutShellId.optString(RAFT_APP_SHELL_SERVER_PAGE_TAB))
        assertFalse(withoutShellId.has(RAFT_APP_SHELL_SERVER_PAGE_SHELL_ID))

        val blankShellId = appShellServerStackPageData("members", shellId = "   ")
        assertEquals("members", blankShellId.optString(RAFT_APP_SHELL_SERVER_PAGE_TAB))
        assertFalse(blankShellId.has(RAFT_APP_SHELL_SERVER_PAGE_SHELL_ID))

        val withShellId = appShellServerStackPageData(AppShellServerTabKey, shellId = " shell-ios-1 ")
        assertEquals(AppShellServerTabKey, withShellId.optString(RAFT_APP_SHELL_SERVER_PAGE_TAB))
        assertEquals("shell-ios-1", withShellId.optString(RAFT_APP_SHELL_SERVER_PAGE_SHELL_ID))
    }

    @Test
    fun resolvesFromLocalStackShellTabsFirstAndFallsBackToChildTabRegistryWithLiveServerStackRenderer() {
        val shellId = "test-ios-server-stack-shell"
        val initialRenderer: AppShellChildTabRenderer = { _, _ -> }
        val updatedRenderer: AppShellChildTabRenderer = { _, _ -> }
        val stackRenderer: AppShellChildTabRenderer = { _, _ -> }
        val entry =
            AppShellChildTabEntry(
                debugId = AppShellChildTabRegistry.createEntryId(),
                initialSelectedTab = "home",
                initialRenderer = initialRenderer,
            )
        val replacementEntry =
            AppShellChildTabEntry(
                debugId = AppShellChildTabRegistry.createEntryId(),
                initialSelectedTab = "home",
                initialRenderer = updatedRenderer,
            )
        val revBeforeRegister = AppShellChildTabRegistry.revision.value
        AppShellChildTabRegistry.register(shellId, entry)
        val revAfterRegister = AppShellChildTabRegistry.revision.value
        assertTrue(revAfterRegister > revBeforeRegister, "register must advance registry revision")
        try {
            // Covered shell (`parentStarted = false`) still updates `serverStackRenderer` for pushed pages
            // without churning hidden tab render slots.
            entry.updateSelection(
                selectedTab = "home",
                retainedTabs = setOf("home"),
                renderer = updatedRenderer,
                parentVisible = false,
                parentStarted = false,
            )
            assertSame(initialRenderer, entry.rendererFor("home"))
            assertSame(updatedRenderer, entry.serverStackRenderer)

            val resolvedFromRegistry = resolveAppShellServerStackContent(shellTabs = null, shellId = shellId)
            assertSame(entry, resolvedFromRegistry?.first)
            assertSame(updatedRenderer, resolvedFromRegistry?.second)

            // When a shared `RaftStackShellTabs` provider exists (Android Nav3), an empty restored stack
            // stays null (waits for the shell) instead of falling through to the registry.
            val emptyStack = RaftStackShellTabs()
            assertNull(resolveAppShellServerStackContent(shellTabs = emptyStack, shellId = shellId))

            // Once populated, `RaftStackShellTabs` takes precedence.
            val shellTabs = RaftStackShellTabs().apply {
                this.entry = entry
                this.renderer = stackRenderer
            }
            val resolvedFromStack = resolveAppShellServerStackContent(shellTabs = shellTabs, shellId = shellId)
            assertSame(entry, resolvedFromStack?.first)
            assertSame(stackRenderer, resolvedFromStack?.second)

            // Re-registering a replacement entry under the same shellId updates resolution, and
            // unregistering the stale entry neither removes the replacement nor bumps revision.
            AppShellChildTabRegistry.register(shellId, replacementEntry)
            val revAfterReplace = AppShellChildTabRegistry.revision.value
            assertTrue(revAfterReplace > revAfterRegister)
            assertFalse(AppShellChildTabRegistry.unregister(shellId, entry))
            assertEquals(revAfterReplace, AppShellChildTabRegistry.revision.value)
            assertSame(replacementEntry, resolveAppShellServerStackContent(shellTabs = null, shellId = shellId)?.first)

            assertNull(resolveAppShellServerStackContent(shellTabs = null, shellId = null))
            assertNull(resolveAppShellServerStackContent(shellTabs = null, shellId = "missing-shell"))

            val revBeforeFinalUnregister = AppShellChildTabRegistry.revision.value
            assertTrue(AppShellChildTabRegistry.unregister(shellId, replacementEntry))
            assertTrue(AppShellChildTabRegistry.revision.value > revBeforeFinalUnregister)
        } finally {
            AppShellChildTabRegistry.unregister(shellId, entry)
            AppShellChildTabRegistry.unregister(shellId, replacementEntry)
        }
        assertNull(resolveAppShellServerStackContent(shellTabs = null, shellId = shellId))
    }
}
