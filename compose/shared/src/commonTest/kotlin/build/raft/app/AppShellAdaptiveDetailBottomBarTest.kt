package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Large screen shrinking to a phone width (tablet to portrait, foldable folded): the list-detail
 * host keeps the open conversation alone with its own Back, so the bottom bar steps aside
 * (navigation rule: Back at the top means no bar).
 */
class AppShellAdaptiveDetailBottomBarTest {
    @Test
    fun detailOnlyOnAListDetailTabHidesTheBar() {
        AppShellAdaptiveListDetailTabs.forEach { tab ->
            assertTrue(
                appShellAdaptiveDetailCoversTabRoot(
                    desktopModeOnLargeScreen = true,
                    selectedTab = tab,
                    presentation = RaftAdaptiveWorkspacePresentation.DetailOnly,
                ),
                "tab=$tab",
            )
        }
    }

    @Test
    fun theListAndTheTwoPaneLayoutKeepTheBarRule() {
        listOf(
            RaftAdaptiveWorkspacePresentation.ListOnly,
            RaftAdaptiveWorkspacePresentation.Split,
            RaftAdaptiveWorkspacePresentation.Inactive,
            null,
        ).forEach { presentation ->
            assertFalse(
                appShellAdaptiveDetailCoversTabRoot(true, "home", presentation),
                "presentation=$presentation",
            )
        }
    }

    @Test
    fun otherTabsAndDesktopModeOffAreUnaffected() {
        // Tasks shows a selection in its own overlay; More/Search never host the shared detail.
        listOf("tasks", "settings", "more", AppShellSearchTabKey).forEach { tab ->
            assertFalse(
                appShellAdaptiveDetailCoversTabRoot(true, tab, RaftAdaptiveWorkspacePresentation.DetailOnly),
                "tab=$tab",
            )
        }
        // Desktop mode off: conversations are pushed pages, the workspace only parks a selection.
        assertFalse(appShellAdaptiveDetailCoversTabRoot(false, "home", RaftAdaptiveWorkspacePresentation.DetailOnly))
    }

    @Test
    fun aTabletTurnedToPortraitShowsTheKeptConversationAlone() {
        // 1280x700dp landscape (Split) -> 700x1280dp portrait: below 768dp, the same selection is
        // kept and presented DetailOnly, which hides the bar.
        val wide = RaftAdaptivePaneState(2560, 1400, 2f, RaftAdaptivePanePosture.Flat, RaftAdaptivePaneLayout.DualPane)
        val portrait = reduceRaftAdaptivePane(wide, RaftAdaptivePaneEvent(1400, 2560, 2f, RaftAdaptivePanePosture.Flat))
        assertEquals(RaftAdaptivePaneLayout.Compact, portrait.layout)
        val identity =
            RaftAdaptiveDetailIdentity(serverId = "s", threadId = "t", surfaceKind = RaftThreadSurfaceKind.Channel)
        val split =
            reduceRaftAdaptiveWorkspace(
                reduceRaftAdaptiveWorkspace(
                    RaftAdaptiveWorkspaceState(shellInstanceId = "shell", serverId = "s"),
                    RaftAdaptiveWorkspaceIntent.WindowChanged(wide),
                ),
                RaftAdaptiveWorkspaceIntent.OpenDetail(identity),
            )
        assertEquals(RaftAdaptiveWorkspacePresentation.Split, split.presentation)
        val turned = reduceRaftAdaptiveWorkspace(split, RaftAdaptiveWorkspaceIntent.WindowChanged(portrait))
        assertEquals(identity, turned.selection?.identity)
        assertEquals(RaftAdaptiveWorkspacePresentation.DetailOnly, turned.presentation)
        assertTrue(appShellAdaptiveDetailCoversTabRoot(true, "home", turned.presentation))
    }
}
