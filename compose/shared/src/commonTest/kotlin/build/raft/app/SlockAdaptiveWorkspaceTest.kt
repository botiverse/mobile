package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlockAdaptiveWorkspaceTest {
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

    private fun initialState(): SlockAdaptiveWorkspaceState =
        SlockAdaptiveWorkspaceState(
            shellInstanceId = "shell-a",
            serverId = "server-a",
        )

    private fun detailIdentity(threadId: String): SlockAdaptiveDetailIdentity =
        SlockAdaptiveDetailIdentity(
            serverId = "server-a",
            threadId = threadId,
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
