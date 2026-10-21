package build.raft.app

import build.raft.app.compat.json.JSONObject
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
    fun consumerDefensivelyTreatsUnknownDualPaneAsCompact() {
        val inconsistentHostState =
            widePane().copy(posture = RaftAdaptivePanePosture.Unknown)
        val state =
            initialState()
                .reduce(RaftAdaptiveWorkspaceIntent.WindowChanged(inconsistentHostState))
                .reduce(RaftAdaptiveWorkspaceIntent.OpenDetail(detailIdentity("thread-a")))

        assertEquals(RaftAdaptivePaneLayout.Compact, state.pane.layout)
        assertEquals(RaftAdaptiveWorkspacePresentation.ListOnly, state.presentation)
        assertNull(state.selection)
    }

    @Test
    fun halfFoldedWideWindowHostsTheDetailLikeTheShellRail() {
        // The shell turns the rail on from the same DualPane pane state; the workspace must agree,
        // or a half-open foldable shows the rail over a single list that pushes every conversation.
        val pane = widePane().copy(posture = RaftAdaptivePanePosture.HalfFolded)
        val state =
            initialState()
                .reduce(RaftAdaptiveWorkspaceIntent.WindowChanged(pane))
                .reduce(RaftAdaptiveWorkspaceIntent.OpenDetail(detailIdentity("thread-a")))

        assertEquals(RaftAdaptivePaneLayout.DualPane, state.pane.layout)
        assertEquals(RaftAdaptiveWorkspacePresentation.Split, state.presentation)
        assertTrue(raftAdaptiveNavRailActive(desktopModeEnabled = true, paneState = pane))
    }

    @Test
    fun closeAndLateCallbacksAreGenerationFencedAcrossRapidReplacement() {
        val first =
            initialState()
                .reduce(RaftAdaptiveWorkspaceIntent.WindowChanged(widePane()))
                .reduce(RaftAdaptiveWorkspaceIntent.OpenDetail(detailIdentity("thread-a")))
        val firstSelection = first.selection ?: error("first selection missing")
        val second =
            first.reduce(RaftAdaptiveWorkspaceIntent.OpenDetail(detailIdentity("thread-b")))
        val secondSelection = second.selection ?: error("second selection missing")
        val lateClose =
            second.reduce(
                RaftAdaptiveWorkspaceIntent.CloseDetail(
                    identityKey = firstSelection.identity.key,
                    generation = firstSelection.generation,
                )
            )

        assertTrue(first.acceptsDetailCallback(firstSelection.identity.key, firstSelection.generation))
        assertFalse(second.acceptsDetailCallback(firstSelection.identity.key, firstSelection.generation))
        assertTrue(second.acceptsDetailCallback(secondSelection.identity.key, secondSelection.generation))
        assertEquals(second, lateClose)
    }

    @Test
    fun dismissDetailFromSinglePaneBackClearsSelectionAndFencesLateCallbacks() {
        val opened =
            initialState()
                .reduce(RaftAdaptiveWorkspaceIntent.WindowChanged(widePane()))
                .reduce(RaftAdaptiveWorkspaceIntent.OpenDetail(detailIdentity("thread-a")))
        val generation = opened.selection?.generation ?: error("selection missing")
        val compact =
            opened.reduce(
                RaftAdaptiveWorkspaceIntent.WindowChanged(
                    widePane().copy(layout = RaftAdaptivePaneLayout.Compact)
                )
            )
        assertEquals(RaftAdaptiveWorkspacePresentation.DetailOnly, compact.presentation)

        val dismissed = compact.reduce(RaftAdaptiveWorkspaceIntent.DismissDetail)

        assertNull(dismissed.selection)
        assertEquals(RaftAdaptiveWorkspacePresentation.ListOnly, dismissed.presentation)
        assertTrue(dismissed.generationCursor > compact.generationCursor)
        // The detail root was dismissed without a CloseDetail envelope; any late callback
        // carrying the old generation must not resurrect or match the slot.
        assertFalse(dismissed.acceptsDetailCallback("thread-a", generation))
        val lateClose =
            dismissed.reduce(
                RaftAdaptiveWorkspaceIntent.CloseDetail(
                    identityKey = opened.selection?.identity?.key.orEmpty(),
                    generation = generation,
                )
            )
        assertEquals(dismissed, lateClose)
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
    fun dismissDetailWithoutSelectionIsNoOp() {
        val state = initialState().reduce(RaftAdaptiveWorkspaceIntent.DismissDetail)

        assertNull(state.selection)
        assertEquals(0L, state.generationCursor)
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
    fun closeEnvelopeRoundTripsExplicitShellIdentityAndGeneration() {
        val request =
            RaftAdaptiveDetailCloseRequest(
                shellInstanceId = "shell-a",
                identityKey = "4:room",
                generation = 7,
            )

        assertEquals(request, request.toJson().toRaftAdaptiveDetailCloseRequestOrNull())
        assertNull(
            JSONObject()
                .put(RAFT_ADAPTIVE_DETAIL_IDENTITY_KEY, request.identityKey)
                .put(RAFT_ADAPTIVE_DETAIL_GENERATION, request.generation)
                .toRaftAdaptiveDetailCloseRequestOrNull()
        )
    }

    @Test
    fun childPageDataUpdateRebindsCloseEnvelopeAndLegacyUpdateKeepsCurrent() {
        val first = RaftAdaptiveDetailCloseRequest("shell-a", "first", 1)
        val second = RaftAdaptiveDetailCloseRequest("shell-a", "second", 2)

        val rebound = updatedRaftAdaptiveDetailCloseRequest(first, second.toJson())
        val legacyUpdate = updatedRaftAdaptiveDetailCloseRequest(rebound, JSONObject().put("payload", "{}"))

        assertEquals(second, rebound)
        assertEquals(second, legacyUpdate)
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
    fun wireLaunchProjectsIdentityOnlyFieldsForAdaptiveSelection() {
        val wire =
            WireThreadLaunchPayload(
                instanceId = "presentation-only-instance",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                sourceThreadId = "stream-a",
                streamChannelId = "stream-a",
                parentChannelId = "channel-a",
                parentMessageId = "message-a",
                focusedMessageId = "focus-a",
                navigationLabel = "localized presentation",
            )

        val identity = wire.toRaftAdaptiveDetailIdentity("server-a") ?: error("identity missing")

        assertEquals("server-a", identity.serverId)
        assertEquals("stream-a", identity.threadId)
        assertEquals("message-a", identity.parentMessageId)
        assertFalse(identity.key.contains("localized presentation"))
        assertFalse(identity.key.contains("presentation-only-instance"))
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
    fun defaultLandingDoesNotSurviveTheWindowLeavingDualPane() {
        val wide =
            initialState()
                .reduce(RaftAdaptiveWorkspaceIntent.WindowChanged(widePane()))
                .reduce(RaftAdaptiveWorkspaceIntent.LandDefaultDetail(detailIdentity("all")))
        val landed = wide.selection ?: error("landing missing")
        assertEquals(RaftAdaptiveWorkspacePresentation.Split, wide.presentation)
        assertTrue(wide.selectionIsDefaultLanding)

        val compact =
            wide.reduce(
                RaftAdaptiveWorkspaceIntent.WindowChanged(
                    widePane().copy(layout = RaftAdaptivePaneLayout.Compact)
                )
            )

        assertNull(compact.selection)
        assertEquals(RaftAdaptiveWorkspacePresentation.ListOnly, compact.presentation)
        assertFalse(compact.acceptsDetailCallback(landed.identity.key, landed.generation))
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
    fun userOpeningTheLandedConversationKeepsCompactContinuity() {
        val identity = detailIdentity("all")
        val landed =
            initialState()
                .reduce(RaftAdaptiveWorkspaceIntent.WindowChanged(widePane()))
                .reduce(RaftAdaptiveWorkspaceIntent.LandDefaultDetail(identity))
        val generation = landed.selection?.generation ?: error("landing missing")
        val adopted = landed.reduce(RaftAdaptiveWorkspaceIntent.OpenDetail(identity))
        assertFalse(adopted.selectionIsDefaultLanding)
        assertEquals(generation, adopted.selection?.generation)

        val compact =
            adopted.reduce(
                RaftAdaptiveWorkspaceIntent.WindowChanged(
                    widePane().copy(layout = RaftAdaptivePaneLayout.Compact)
                )
            )
        assertEquals(RaftAdaptiveWorkspacePresentation.DetailOnly, compact.presentation)
        assertEquals(identity, compact.selection?.identity)
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
