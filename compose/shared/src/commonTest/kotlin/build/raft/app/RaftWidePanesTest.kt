package build.raft.app

import build.raft.app.cmp.host.RaftCmpNavigator
import build.raft.app.compat.pager.RaftPageRegistry
import build.raft.app.compat.pager.RaftPaneRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Wide pane roles (RaftWidePanes.kt, plan option C): routing, the pane stack, the profile column's cells and Back order. */
class RaftWidePanesTest {
    private val data = JsonObject(mapOf("serverId" to JsonPrimitive("s1"), "channelId" to JsonPrimitive("c1")))

    private class RecordingNavigator : RaftCmpNavigator {
        val opened = mutableListOf<Pair<String, JsonObject>>()
        var closes = 0

        override fun openPage(pageName: String, pageData: JsonObject) {
            opened += pageName to pageData
        }

        override fun closePage() {
            closes++
        }
    }

    @Test
    fun compactNavigatorPassesEveryCallThroughUnchanged() {
        val platform = RecordingNavigator()
        val navigator = RaftPaneNavigator(platform, wide = { false }, openInPane = { _, _, _ -> fail("pane on compact") })
        RaftPageRegistry.pageNames.forEach { navigator.openPage(it, data) }
        navigator.closePage()
        assertEquals(RaftPageRegistry.pageNames.map { it to data }, platform.opened)
        assertEquals(1, platform.closes)
    }

    @Test
    fun wideNavigatorSendsRoledPagesToThePaneAndTheRestToThePlatform() {
        val platform = RecordingNavigator()
        val panes = mutableListOf<Triple<RaftPaneRole, String, JsonObject>>()
        var paneCloses = 0
        val navigator =
            RaftPaneNavigator(
                platform = platform,
                wide = { true },
                openInPane = { role, name, pageData -> panes += Triple(role, name, pageData) },
                close = { paneCloses++ },
                roleOf = { if (it == "settings") RaftPaneRole.Supporting else RaftPaneRole.Window },
            )
        navigator.openPage("settings", data)
        navigator.openPage("profile", data)
        navigator.closePage()
        assertEquals(listOf(Triple(RaftPaneRole.Supporting, "settings", data)), panes)
        assertEquals(listOf("profile" to data), platform.opened)
        assertEquals(1, paneCloses)
        assertEquals(0, platform.closes)
    }

    // #189 step 4 (web F41): the Artifact viewer opens in a column of the conversation's row on the wide layout.
    @Test
    fun theArtifactViewerOpensInTheArtifactColumnOnTheWideLayoutOnly() {
        assertEquals(RaftPaneRole.Artifact, RaftPageRegistry.paneRole(RAFT_ARTIFACT_VIEWER_PAGE))
        assertEquals(listOf(RAFT_ARTIFACT_VIEWER_PAGE), RaftPageRegistry.pageNames.filter { RaftPageRegistry.paneRole(it) == RaftPaneRole.Artifact })
        // A phone (or any compact layout) opens it as the page it always was.
        assertEquals(RaftPaneRole.Window, raftPaneRoute(wide = false, pageName = RAFT_ARTIFACT_VIEWER_PAGE))
        assertEquals(RaftPaneRole.Artifact, raftPaneRoute(wide = true, pageName = RAFT_ARTIFACT_VIEWER_PAGE))
        // Opened from the profile column it goes to the Artifact column too, not into the profile's stack.
        assertEquals(RaftPaneRole.Artifact, raftProfileColumnRole(RAFT_ARTIFACT_VIEWER_PAGE))
        assertEquals(RaftPaneGridColumnBounds(id = "artifact", defaultWidthDp = 480f, minWidthDp = 480f), RaftPaneGridColumns.Artifact)
        assertEquals(RaftChannelSideSection.Artifact.wire, RAFT_ARTIFACT_COLUMN_CELL, "the cell is the side layout's section")
    }

    // web layout-memory.md "Close and Back": a side pane's head always has Close (the pane's), Back only over a page of
    // the pane's own stack (RaftPaneStack.entries.size > 1 for its top page).
    @Test
    fun aSidePaneHeadHasCloseAndBackOnlyOverAPageOfItsStack() {
        assertEquals(setOf(RaftSidePaneHeadButton.Close), raftSidePaneHeadButtons(depth = 1), "the pane's only page: Close alone")
        assertEquals(
            setOf(RaftSidePaneHeadButton.Back, RaftSidePaneHeadButton.Close),
            raftSidePaneHeadButtons(depth = 2),
            "a page pushed in the pane: Back and Close",
        )
    }

    // PR #4666 review: Back goes in the order things were opened across both columns, not by one flag.
    @Test
    fun backClosesInTheOrderThingsWereOpenedAcrossBothColumns() {
        val p1 = RaftProfileColumnPanes().open(MEMBER_DETAIL_PAGE, data)
        val artifact = RaftArtifactColumnPane().open(RAFT_ARTIFACT_VIEWER_PAGE, data, p1)
        // P1, then the Artifact: the Artifact first.
        assertTrue(artifact.isOnTopOf(p1))
        assertEquals(RaftWideBackStep.PopArtifactColumn, raftWideBackStep(p1, shown = true, artifact = artifact, artifactShown = true))
        // P1, the Artifact, then P2 (the details): P2, then the Artifact, then P1.
        val p2 = p1.open(CHANNEL_SETTINGS_PAGE, data)
        assertFalse(artifact.isOnTopOf(p2))
        assertEquals(RaftWideBackStep.PopProfileColumn, raftWideBackStep(p2, shown = true, artifact = artifact, artifactShown = true))
        val afterP2 = p2.back()
        assertEquals(listOf(RaftProfileColumnCell.Profile), afterP2.cells, "P2 closed, P1 left")
        assertEquals(RaftWideBackStep.PopArtifactColumn, raftWideBackStep(afterP2, shown = true, artifact = artifact, artifactShown = true))
        assertEquals(RaftWideBackStep.PopProfileColumn, raftWideBackStep(afterP2, shown = true, artifact = artifact.cleared(), artifactShown = true))
        // A profile replacing P1 after the Artifact (another avatar) is above it too.
        val replaced = p1.open(MEMBER_DETAIL_PAGE, data)
        assertFalse(artifact.isOnTopOf(replaced))
        // Opened with no profile page: any profile page is above it; with none left, Back is the Artifact's.
        val alone = RaftArtifactColumnPane().open(RAFT_ARTIFACT_VIEWER_PAGE, data)
        assertEquals(RaftWideBackStep.PopProfileColumn, raftWideBackStep(p1, shown = true, artifact = alone, artifactShown = true))
        assertEquals(RaftWideBackStep.PopArtifactColumn, raftWideBackStep(RaftProfileColumnPanes(), shown = true, artifact = alone, artifactShown = true))
        // Out of sight (Focus, under the task overlay) it is not Back's.
        assertEquals(RaftWideBackStep.PopProfileColumn, raftWideBackStep(p1, shown = true, artifact = artifact, artifactShown = false))
        assertEquals(RaftWideBackStep.Existing, raftWideBackStep(p1, paneShown = true, overlayOpen = true, artifact = artifact, artifactShown = true))
        assertEquals(RaftWideBackStep.Existing, raftWideBackStep(RaftProfileColumnPanes(), shown = false))
    }

    @Test
    fun narrowingReopensThePagesInBacksOrder() {
        val p1 = RaftProfileColumnPanes().open(MEMBER_DETAIL_PAGE, data)
        val columns = RaftWideSideColumns()
        columns.profile = p1
        columns.artifact = RaftArtifactColumnPane().open(RAFT_ARTIFACT_VIEWER_PAGE, data, p1)
        columns.profile = p1.open(CHANNEL_SETTINGS_PAGE, data)
        val navigator = RecordingNavigator()
        columns.reopenOn(navigator)
        assertEquals(listOf(MEMBER_DETAIL_PAGE, RAFT_ARTIFACT_VIEWER_PAGE, CHANNEL_SETTINGS_PAGE), navigator.opened.map { it.first }, "the one opened last on top")
        assertFalse(columns.profile.isOpen)
        assertFalse(columns.artifact.isOpen)
    }
}
