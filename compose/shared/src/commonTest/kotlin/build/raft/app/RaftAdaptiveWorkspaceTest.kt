package build.raft.app

import build.raft.app.reduce
import build.raft.app.thread.key
import build.raft.app.thread.reduce
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaftAdaptiveWorkspaceTest {
    @Test
    fun adaptiveRowHighlightFollowsMountedDetailInsteadOfStaleHomeSelection() {
        val selection =
            RaftAdaptiveDetailSelection(
                identity = detailIdentity("thread-b"),
                generation = 3L,
            )

        assertEquals(
            "thread-b",
            resolveRaftHomeSelectedConversationId(
                adaptiveWorkspaceEnabled = true,
                adaptiveSelection = selection,
                shellSelectedConversationId = "thread-a",
                homeSelectedConversationId = "thread-a",
            ),
        )
        assertNull(
            resolveRaftHomeSelectedConversationId(
                adaptiveWorkspaceEnabled = true,
                adaptiveSelection = null,
                shellSelectedConversationId = null,
                homeSelectedConversationId = "thread-a",
            ),
        )
        assertEquals(
            "thread-a",
            resolveRaftHomeSelectedConversationId(
                adaptiveWorkspaceEnabled = false,
                adaptiveSelection = null,
                shellSelectedConversationId = null,
                homeSelectedConversationId = "thread-a",
            ),
        )
    }

    @Test
    fun wideSelectionSurvivesCompactAndInactiveTransitionsWithoutNewGeneration() {
        val wide = widePane()
        val identity = detailIdentity("thread-a")
        val opened =
            initialState()
                .reduce(RaftAdaptiveWorkspaceIntent.WindowChanged(wide))
                .reduce(RaftAdaptiveWorkspaceIntent.OpenDetail(identity))
        val generation = opened.selection?.generation ?: error("selection missing")

        val compact =
            opened.reduce(
                RaftAdaptiveWorkspaceIntent.WindowChanged(
                    wide.copy(layout = RaftAdaptivePaneLayout.Compact)
                )
            )
        val inactive = compact.reduce(RaftAdaptiveWorkspaceIntent.ParentVisibilityChanged(false))
        val restored =
            inactive
                .reduce(RaftAdaptiveWorkspaceIntent.ParentVisibilityChanged(true))
                .reduce(RaftAdaptiveWorkspaceIntent.WindowChanged(wide))

        assertEquals(RaftAdaptiveWorkspacePresentation.Split, opened.presentation)
        assertEquals(RaftAdaptiveWorkspacePresentation.DetailOnly, compact.presentation)
        assertEquals(RaftAdaptiveWorkspacePresentation.Inactive, inactive.presentation)
        assertEquals(RaftAdaptiveWorkspacePresentation.Split, restored.presentation)
        assertEquals(generation, restored.selection?.generation)
        assertEquals(identity, restored.selection?.identity)
    }

    @Test
    fun compactOpenRemainsStandaloneAndDoesNotInstallPaneSelection() {
        val state =
            initialState().reduce(
                RaftAdaptiveWorkspaceIntent.OpenDetail(detailIdentity("thread-a"))
            )

        assertNull(state.selection)
        assertEquals(RaftAdaptiveWorkspacePresentation.ListOnly, state.presentation)
    }

    @Test
    fun parkSelectionTracksStandaloneOpenWhileParkedAndRespectsServerIsolation() {
        // task #45: ON pick A → OFF → user standalone-opens B → ON must restore B, not A.
        val opened =
            initialState()
                .reduce(RaftAdaptiveWorkspaceIntent.WindowChanged(widePane()))
                .reduce(RaftAdaptiveWorkspaceIntent.OpenDetail(detailIdentity("thread-a")))
        val parked =
            opened.reduce(
                RaftAdaptiveWorkspaceIntent.ParkSelection(detailIdentity("thread-b"))
            )

        assertEquals("thread-b", parked.selection?.identity?.threadId)
        assertTrue(parked.generationCursor > opened.generationCursor)
        // Parking on a compact pane keeps the pane fact untouched — re-ON restores
        // whichever presentation the current window implies.
        assertEquals(RaftAdaptivePaneLayout.DualPane, parked.pane.layout)
        assertEquals(RaftAdaptiveWorkspacePresentation.Split, parked.presentation)

        // Same identity is a no-op; a foreign server never mutates this workspace.
        val same = parked.reduce(RaftAdaptiveWorkspaceIntent.ParkSelection(detailIdentity("thread-b")))
        assertEquals(parked, same)
        val foreign =
            parked.reduce(
                RaftAdaptiveWorkspaceIntent.ParkSelection(
                    detailIdentity("thread-c").copy(serverId = "server-b")
                )
            )
        assertEquals("thread-b", foreign.selection?.identity?.threadId)
    }

    @Test
    fun terminalAndServerTransitionsClearSelectionBeforeSuccessorAuthority() {
        val opened =
            initialState()
                .reduce(RaftAdaptiveWorkspaceIntent.WindowChanged(widePane()))
                .reduce(RaftAdaptiveWorkspaceIntent.OpenDetail(detailIdentity("thread-a")))
        val terminal = opened.reduce(RaftAdaptiveWorkspaceIntent.TerminalReset)
        val successor =
            opened.reduce(RaftAdaptiveWorkspaceIntent.AuthorityChanged("server-b"))

        assertNull(terminal.selection)
        assertTrue(terminal.generationCursor > opened.generationCursor)
        assertEquals("server-b", successor.serverId)
        assertNull(successor.selection)
        assertTrue(successor.generationCursor > opened.generationCursor)
    }

    @Test
    fun listReorderAndUnreadUpdatesCannotMutateSelectionBecauseRowsAreNotStateInput() {
        val opened =
            initialState()
                .reduce(RaftAdaptiveWorkspaceIntent.WindowChanged(widePane()))
                .reduce(RaftAdaptiveWorkspaceIntent.OpenDetail(detailIdentity("thread-a")))
        val duplicateWindow =
            opened.reduce(RaftAdaptiveWorkspaceIntent.WindowChanged(widePane()))

        assertEquals(opened.selection, duplicateWindow.selection)
        assertEquals(opened.generationCursor, duplicateWindow.generationCursor)
    }

    @Test
    fun standaloneThreadKeepsRoomLeaseWhileCoveredButAdaptiveInactiveReleases() {
        val adaptive = RaftAdaptiveDetailCloseRequest("shell-a", "detail", 1)

        assertTrue(shouldHoldThreadRoomLease(adaptiveCloseRequest = null, active = false))
        assertTrue(shouldHoldThreadRoomLease(adaptiveCloseRequest = adaptive, active = true))
        assertFalse(shouldHoldThreadRoomLease(adaptiveCloseRequest = adaptive, active = false))
    }

    @Test
    fun lengthPrefixedIdentityKeysDoNotCollideWhenDelimitersAppearInIds() {
        val first =
            detailIdentity("thread|a").copy(
                channelId = "channel",
                parentChannelId = "parent|message",
            )
        val second =
            detailIdentity("thread").copy(
                channelId = "a|channel",
                parentChannelId = "parent|message",
            )

        assertNotEquals(first.key, second.key)
    }

    @Test
    fun defaultLandingRestoresVisibleSelectionThenFallsBackToAll() {
        // Joint channels are ordinary channels; like web, "all" is found by name only.
        val candidates =
            listOf(
                RaftAdaptiveHomeLandingCandidate("channel-all", "all"),
                RaftAdaptiveHomeLandingCandidate("dm-a", "Alice"),
            )

        assertEquals("dm-a", resolveRaftAdaptiveHomeLandingId("dm-a", candidates))
        assertEquals("channel-all", resolveRaftAdaptiveHomeLandingId("missing", candidates))
        assertEquals("channel-all", resolveRaftAdaptiveHomeLandingId(null, candidates))

        // Regression for the removed joint skip: a joint channel named "all" is no longer
        // passed over; the first "all" by list order wins, as on web.
        val withJointFirst =
            listOf(RaftAdaptiveHomeLandingCandidate("joint-all", "all")) + candidates
        assertEquals("joint-all", resolveRaftAdaptiveHomeLandingId(null, withJointFirst))
    }

    // Hands d5ec0533: a transient wide window (phone briefly landscape) while the user was in a
    // pushed channel/thread stack auto-landed #proj-mobile in the wide detail. Back in portrait,
    // backing out of the stack revealed that auto-picked channel as a compact DetailOnly page
    // inside the Home tab instead of the Home list.
    @Test
    fun defaultLandingIsRefusedWhileTheShellIsCoveredByPushedPages() {
        val covered =
            initialState()
                .reduce(RaftAdaptiveWorkspaceIntent.WindowChanged(widePane()))
                .reduce(RaftAdaptiveWorkspaceIntent.ParentVisibilityChanged(false))

        assertFalse(covered.acceptsAdaptiveDefaultLanding())
        val landed = covered.reduce(RaftAdaptiveWorkspaceIntent.LandDefaultDetail(detailIdentity("proj-mobile")))
        assertNull(landed.selection)

        val back = landed.reduce(RaftAdaptiveWorkspaceIntent.ParentVisibilityChanged(true))
        assertEquals(RaftAdaptiveWorkspacePresentation.ListOnly, back.presentation)
    }

    @Test
    fun defaultLandingNeverReplacesAUserSelection() {
        val opened =
            initialState()
                .reduce(RaftAdaptiveWorkspaceIntent.WindowChanged(widePane()))
                .reduce(RaftAdaptiveWorkspaceIntent.OpenDetail(detailIdentity("thread-a")))

        assertEquals(
            opened,
            opened.reduce(RaftAdaptiveWorkspaceIntent.LandDefaultDetail(detailIdentity("all"))),
        )
    }

    @Test
    fun selectionForReadyServerBlocksCrossServerMountOnServerSwitchFrame() {
        val opened =
            initialState()
                .reduce(RaftAdaptiveWorkspaceIntent.WindowChanged(widePane()))
                .reduce(RaftAdaptiveWorkspaceIntent.OpenDetail(detailIdentity("thread-a")))
        val selection = opened.selection ?: error("selection missing")

        assertEquals(
            selection,
            raftAdaptiveSelectionForReadyServer(opened, readyServerId = "server-a", detailAccepted = true),
        )
        assertEquals(
            selection,
            raftAdaptiveSelectionForReadyServer(opened, readyServerId = "  server-a  ", detailAccepted = true),
        )
        assertNull(
            raftAdaptiveSelectionForReadyServer(opened, readyServerId = "server-b", detailAccepted = true),
        )
        assertNull(
            raftAdaptiveSelectionForReadyServer(opened, readyServerId = "server-a", detailAccepted = false),
        )
        assertNull(
            raftAdaptiveSelectionForReadyServer(opened, readyServerId = null, detailAccepted = true),
        )
        assertNull(
            raftAdaptiveSelectionForReadyServer(opened, readyServerId = "   ", detailAccepted = true),
        )
    }

    private fun initialState(): RaftAdaptiveWorkspaceState =
        RaftAdaptiveWorkspaceState(
            shellInstanceId = "shell-a",
            serverId = "server-a",
        )

    private fun detailIdentity(threadId: String): RaftAdaptiveDetailIdentity =
        RaftAdaptiveDetailIdentity(
            serverId = "server-a",
            threadId = threadId,
            surfaceKind = RaftThreadSurfaceKind.Channel,
            channelId = "channel-$threadId",
        )

    private fun widePane(): RaftAdaptivePaneState =
        RaftAdaptivePaneState(
            widthPx = 2200,
            heightPx = 1200,
            density = 2f,
            posture = RaftAdaptivePanePosture.Flat,
            layout = RaftAdaptivePaneLayout.DualPane,
        )

    private fun RaftAdaptiveWorkspaceState.reduce(
        intent: RaftAdaptiveWorkspaceIntent,
    ): RaftAdaptiveWorkspaceState = reduceRaftAdaptiveWorkspace(this, intent)
}
