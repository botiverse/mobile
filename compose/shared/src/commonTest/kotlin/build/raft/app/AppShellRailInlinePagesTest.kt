package build.raft.app

import build.raft.app.data.RaftShellStoreScope
import build.raft.app.model.RaftTimeFormat24Hour
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** artin 11-01: the rail's Computers and Apps open beside the rail on the wide layout, not over it. */
class AppShellRailInlinePagesTest {
    private val scope = RaftShellStoreScope.create("shell-rail-inline", "server-1")

    @Test
    fun railPageDestinationsOpenAsInlinePages() {
        assertEquals(AppShellRailComputersKey, appShellRailInlinePageKey(RaftAppDestination.Computers))
        assertEquals(AppShellRailAppsKey, appShellRailInlinePageKey(RaftAppDestination.Apps))
        assertEquals(AppShellRailAssistantKey, appShellRailInlinePageKey(RaftAppDestination.Assistant))
        assertNull(appShellRailInlinePageKey(RaftAppDestination.Members))
        // Every rail page destination has an inline page: none is pushed over the shell.
        RaftNavPresentation.Rail.destinations
            .filter { it.route == RaftNavRoute.Page }
            .forEach { destination -> assertTrue(appShellRailInlinePage(appShellRailInlinePageKey(destination), scope) != null, destination.id) }
    }

    @Test
    fun inlinePagesShowTheirSettingsPanePages() {
        assertEquals(settingsWideDetailPage(SettingsDestinationComputers, scope)?.first, appShellRailInlinePage(AppShellRailComputersKey, scope)?.first)
        assertEquals(SETTINGS_COMPUTERS_PAGE, appShellRailInlinePage(AppShellRailComputersKey, scope)?.first)
        assertEquals(SETTINGS_WEB_VIEW_PAGE, appShellRailInlinePage(AppShellRailAppsKey, scope)?.first)
        // The Artifacts home beside the rail, as the web LeftRail's Artifacts tab.
        assertEquals(ARTIFACTS_HOME_PAGE, appShellRailInlinePage(AppShellRailArtifactsKey, scope)?.first)
        // The assistant's page beside the rail (web's `assistant` rail mode), with the shell's time display for its DM.
        val assistant = appShellRailInlinePage(AppShellRailAssistantKey, scope, AppShellThreadTimeContext(localOffsetMinutes = 480, timeFormat = RaftTimeFormat24Hour))
        assertEquals(ASSISTANT_PAGE, assistant?.first)
        assertEquals(480, assistant?.second?.optInt("localOffsetMinutes"))
        assertNull(appShellRailInlinePage(AppShellRailSearchKey, scope))
        assertNull(appShellRailInlinePage(null, scope))
    }

    @Test
    fun theInlinePageIsTheSelectedRailEntry() {
        val tabs = appShellRailTabs(selectedTab = "home", labelFor = { it }, inlinePageRailId = appShellRailInlinePageRailId(AppShellRailComputersKey))
        assertEquals(listOf("computers"), tabs.filter { it.selected }.map { it.key })
        // Artifacts only behind workspace_artifacts_v0 (web `useArtifactsRailShown`), after Tasks.
        assertFalse(tabs.any { it.key == "artifacts" })
        val withArtifacts = appShellRailTabs(selectedTab = "home", labelFor = { it }, artifacts = true, inlinePageRailId = appShellRailInlinePageRailId(AppShellRailArtifactsKey))
        assertEquals(listOf("home", "activity", "tasks", "artifacts", "members", "computers", "apps"), withArtifacts.map { it.key })
        assertEquals(listOf("artifacts"), withArtifacts.filter { it.selected }.map { it.key })
        // The Assistant only where its entry shows (web `useRememberedAssistantShown`), last.
        assertFalse(tabs.any { it.key == "assistant" })
        val withAssistant = appShellRailTabs(selectedTab = "home", labelFor = { it }, assistant = true, inlinePageRailId = appShellRailInlinePageRailId(AppShellRailAssistantKey))
        assertEquals("assistant", withAssistant.last().key)
        assertEquals(listOf("assistant"), withAssistant.filter { it.selected }.map { it.key })
        val footer = appShellRailFooterTabs(selectedTab = "", labelFor = { it }, helpOpen = false)
        assertFalse(footer.any { it.selected })
        assertNull(appShellRailInlinePageRailId(AppShellRailSearchKey))
    }

    @Test
    fun anInlinePageHostsNoSharedConversationDetail() {
        assertFalse(appShellSharedDetailSurfaceVisible(true, "home", "x", AppShellRailComputersKey))
        assertTrue(appShellSharedDetailSurfaceVisible(true, "home", "x", AppShellRailSearchKey))
        assertFalse(appShellRailSurfaceBackDismissesDetailFirst("members", AppShellRailAppsKey, selectionPresent = true))
    }

    @Test
    fun computerCardsFillTheWidthLikeTheWebGrid() {
        // `repeat(auto-fill, minmax(250px, 1fr))`, gap 12, padding 20.
        assertEquals(1, raftComputerCardColumns(390.dp))
        assertEquals(1, raftComputerCardColumns(551.dp))
        assertEquals(2, raftComputerCardColumns(552.dp))
        assertEquals(4, raftComputerCardColumns(1280.dp))
        assertEquals(6, raftComputerCardColumns(1600.dp))
    }
}
