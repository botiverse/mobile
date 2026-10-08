package build.raft.app

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlockAdaptiveWorkspaceTest {
    @Test
    fun adaptiveRowHighlightFollowsMountedDetailInsteadOfStaleHomeSelection() {
        val selection =
            SlockAdaptiveDetailSelection(
                identity = detailIdentity("thread-b"),
                generation = 3L,
            )

        assertEquals(
            "thread-b",
            resolveSlockHomeSelectedConversationId(
                adaptiveWorkspaceEnabled = true,
                adaptiveSelection = selection,
                shellSelectedConversationId = "thread-a",
                homeSelectedConversationId = "thread-a",
            ),
        )
        assertNull(
            resolveSlockHomeSelectedConversationId(
                adaptiveWorkspaceEnabled = true,
                adaptiveSelection = null,
                shellSelectedConversationId = null,
                homeSelectedConversationId = "thread-a",
            ),
        )
        assertEquals(
            "thread-a",
            resolveSlockHomeSelectedConversationId(
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
                .reduce(SlockAdaptiveWorkspaceIntent.WindowChanged(wide))
                .reduce(SlockAdaptiveWorkspaceIntent.OpenDetail(identity))
        val generation = opened.selection?.generation ?: error("selection missing")

        val compact =
            opened.reduce(
                SlockAdaptiveWorkspaceIntent.WindowChanged(
                    wide.copy(layout = SlockAdaptivePaneLayout.Compact)
                )
            )
        val inactive = compact.reduce(SlockAdaptiveWorkspaceIntent.ParentVisibilityChanged(false))
        val restored =
            inactive
                .reduce(SlockAdaptiveWorkspaceIntent.ParentVisibilityChanged(true))
                .reduce(SlockAdaptiveWorkspaceIntent.WindowChanged(wide))

        assertEquals(SlockAdaptiveWorkspacePresentation.Split, opened.presentation)
        assertEquals(SlockAdaptiveWorkspacePresentation.DetailOnly, compact.presentation)
        assertEquals(SlockAdaptiveWorkspacePresentation.Inactive, inactive.presentation)
        assertEquals(SlockAdaptiveWorkspacePresentation.Split, restored.presentation)
        assertEquals(generation, restored.selection?.generation)
        assertEquals(identity, restored.selection?.identity)
    }

    @Test
    fun compactOpenRemainsStandaloneAndDoesNotInstallPaneSelection() {
        val state =
            initialState().reduce(
                SlockAdaptiveWorkspaceIntent.OpenDetail(detailIdentity("thread-a"))
            )

        assertNull(state.selection)
        assertEquals(SlockAdaptiveWorkspacePresentation.ListOnly, state.presentation)
    }

    @Test
    fun consumerDefensivelyTreatsUnknownDualPaneAsCompact() {
        val inconsistentHostState =
            widePane().copy(posture = SlockAdaptivePanePosture.Unknown)
        val state =
            initialState()
                .reduce(SlockAdaptiveWorkspaceIntent.WindowChanged(inconsistentHostState))
                .reduce(SlockAdaptiveWorkspaceIntent.OpenDetail(detailIdentity("thread-a")))

        assertEquals(SlockAdaptivePaneLayout.Compact, state.pane.layout)
        assertEquals(SlockAdaptiveWorkspacePresentation.ListOnly, state.presentation)
        assertNull(state.selection)
    }

    @Test
    fun closeAndLateCallbacksAreGenerationFencedAcrossRapidReplacement() {
        val first =
            initialState()
                .reduce(SlockAdaptiveWorkspaceIntent.WindowChanged(widePane()))
                .reduce(SlockAdaptiveWorkspaceIntent.OpenDetail(detailIdentity("thread-a")))
        val firstSelection = first.selection ?: error("first selection missing")
        val second =
            first.reduce(SlockAdaptiveWorkspaceIntent.OpenDetail(detailIdentity("thread-b")))
        val secondSelection = second.selection ?: error("second selection missing")
        val lateClose =
            second.reduce(
                SlockAdaptiveWorkspaceIntent.CloseDetail(
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
                .reduce(SlockAdaptiveWorkspaceIntent.WindowChanged(widePane()))
                .reduce(SlockAdaptiveWorkspaceIntent.OpenDetail(detailIdentity("thread-a")))
        val generation = opened.selection?.generation ?: error("selection missing")
        val compact =
            opened.reduce(
                SlockAdaptiveWorkspaceIntent.WindowChanged(
                    widePane().copy(layout = SlockAdaptivePaneLayout.Compact)
                )
            )
        assertEquals(SlockAdaptiveWorkspacePresentation.DetailOnly, compact.presentation)

        val dismissed = compact.reduce(SlockAdaptiveWorkspaceIntent.DismissDetail)

        assertNull(dismissed.selection)
        assertEquals(SlockAdaptiveWorkspacePresentation.ListOnly, dismissed.presentation)
        assertTrue(dismissed.generationCursor > compact.generationCursor)
        // The detail root was dismissed without a CloseDetail envelope; any late callback
        // carrying the old generation must not resurrect or match the slot.
        assertFalse(dismissed.acceptsDetailCallback("thread-a", generation))
        val lateClose =
            dismissed.reduce(
                SlockAdaptiveWorkspaceIntent.CloseDetail(
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
                .reduce(SlockAdaptiveWorkspaceIntent.WindowChanged(widePane()))
                .reduce(SlockAdaptiveWorkspaceIntent.OpenDetail(detailIdentity("thread-a")))
        val parked =
            opened.reduce(
                SlockAdaptiveWorkspaceIntent.ParkSelection(detailIdentity("thread-b"))
            )

        assertEquals("thread-b", parked.selection?.identity?.threadId)
        assertTrue(parked.generationCursor > opened.generationCursor)
        // Parking on a compact pane keeps the pane fact untouched — re-ON restores
        // whichever presentation the current window implies.
        assertEquals(SlockAdaptivePaneLayout.DualPane, parked.pane.layout)
        assertEquals(SlockAdaptiveWorkspacePresentation.Split, parked.presentation)

        // Same identity is a no-op; a foreign server never mutates this workspace.
        val same = parked.reduce(SlockAdaptiveWorkspaceIntent.ParkSelection(detailIdentity("thread-b")))
        assertEquals(parked, same)
        val foreign =
            parked.reduce(
                SlockAdaptiveWorkspaceIntent.ParkSelection(
                    detailIdentity("thread-c").copy(serverId = "server-b")
                )
            )
        assertEquals("thread-b", foreign.selection?.identity?.threadId)
    }

    @Test
    fun dismissDetailWithoutSelectionIsNoOp() {
        val state = initialState().reduce(SlockAdaptiveWorkspaceIntent.DismissDetail)

        assertNull(state.selection)
        assertEquals(0L, state.generationCursor)
    }

    @Test
    fun terminalAndServerTransitionsClearSelectionBeforeSuccessorAuthority() {
        val opened =
            initialState()
                .reduce(SlockAdaptiveWorkspaceIntent.WindowChanged(widePane()))
                .reduce(SlockAdaptiveWorkspaceIntent.OpenDetail(detailIdentity("thread-a")))
        val terminal = opened.reduce(SlockAdaptiveWorkspaceIntent.TerminalReset)
        val successor =
            opened.reduce(SlockAdaptiveWorkspaceIntent.AuthorityChanged("server-b"))

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
                .reduce(SlockAdaptiveWorkspaceIntent.WindowChanged(widePane()))
                .reduce(SlockAdaptiveWorkspaceIntent.OpenDetail(detailIdentity("thread-a")))
        val duplicateWindow =
            opened.reduce(SlockAdaptiveWorkspaceIntent.WindowChanged(widePane()))

        assertEquals(opened.selection, duplicateWindow.selection)
        assertEquals(opened.generationCursor, duplicateWindow.generationCursor)
    }

    @Test
    fun closeEnvelopeRoundTripsExplicitShellIdentityAndGeneration() {
        val request =
            SlockAdaptiveDetailCloseRequest(
                shellInstanceId = "shell-a",
                identityKey = "4:room",
                generation = 7,
            )

        assertEquals(request, request.toJson().toSlockAdaptiveDetailCloseRequestOrNull())
        assertNull(
            JSONObject()
                .put(SLOCK_ADAPTIVE_DETAIL_IDENTITY_KEY, request.identityKey)
                .put(SLOCK_ADAPTIVE_DETAIL_GENERATION, request.generation)
                .toSlockAdaptiveDetailCloseRequestOrNull()
        )
    }

    @Test
    fun childPageDataUpdateRebindsCloseEnvelopeAndLegacyUpdateKeepsCurrent() {
        val first = SlockAdaptiveDetailCloseRequest("shell-a", "first", 1)
        val second = SlockAdaptiveDetailCloseRequest("shell-a", "second", 2)

        val rebound = updatedSlockAdaptiveDetailCloseRequest(first, second.toJson())
        val legacyUpdate = updatedSlockAdaptiveDetailCloseRequest(rebound, JSONObject().put("payload", "{}"))

        assertEquals(second, rebound)
        assertEquals(second, legacyUpdate)
    }

    @Test
    fun standaloneThreadKeepsRoomLeaseWhileCoveredButAdaptiveInactiveReleases() {
        val adaptive = SlockAdaptiveDetailCloseRequest("shell-a", "detail", 1)

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
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                sourceThreadId = "stream-a",
                streamChannelId = "stream-a",
                parentChannelId = "channel-a",
                parentMessageId = "message-a",
                focusedMessageId = "focus-a",
                navigationLabel = "localized presentation",
            )

        val identity = wire.toSlockAdaptiveDetailIdentity("server-a") ?: error("identity missing")

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
                SlockAdaptiveHomeLandingCandidate("channel-all", "all"),
                SlockAdaptiveHomeLandingCandidate("dm-a", "Alice"),
            )

        assertEquals("dm-a", resolveSlockAdaptiveHomeLandingId("dm-a", candidates))
        assertEquals("channel-all", resolveSlockAdaptiveHomeLandingId("missing", candidates))
        assertEquals("channel-all", resolveSlockAdaptiveHomeLandingId(null, candidates))
    }

    private fun initialState(): SlockAdaptiveWorkspaceState =
        SlockAdaptiveWorkspaceState(
            shellInstanceId = "shell-a",
            serverId = "server-a",
        )

    private fun detailIdentity(threadId: String): SlockAdaptiveDetailIdentity =
        SlockAdaptiveDetailIdentity(
            serverId = "server-a",
            threadId = threadId,
            surfaceKind = KuiklyThreadSurfaceKind.Channel,
            channelId = "channel-$threadId",
        )

    private fun widePane(): SlockAdaptivePaneState =
        SlockAdaptivePaneState(
            widthPx = 2200,
            heightPx = 1200,
            density = 2f,
            posture = SlockAdaptivePanePosture.Flat,
            layout = SlockAdaptivePaneLayout.DualPane,
        )

    private fun SlockAdaptiveWorkspaceState.reduce(
        intent: SlockAdaptiveWorkspaceIntent,
    ): SlockAdaptiveWorkspaceState = reduceSlockAdaptiveWorkspace(this, intent)
}
