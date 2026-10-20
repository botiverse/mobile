package build.raft.app

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RaftForwardTargetPickerTest {
    private val session = RaftForwardPickerSessionKey("thread-1", "server-1", 7)

    @Test
    fun externalShareActionPaddingClearsBottomSafeAreaWithoutChangingZeroInsetGeometry() {
        assertEquals(60.dp, raftForwardPickerActionBottomPadding(48.dp))
        assertEquals(12.dp, raftForwardPickerActionBottomPadding(0.dp))
    }

    @Test
    fun productionFlowAdmitsRequestsSynchronouslyAndRestoresNoteSelection() {
        val opened =
            RaftForwardPickerFlowState().reduce(
                RaftForwardPickerFlowEvent.Open("thread-1", "server-1")
            )
        assertEquals(RaftForwardPickerLoadState.Loading, opened.loadState)
        assertFalse(opened.rowsInteractive)
        assertTrue(opened.dismissEnabled)

        val loaded = opened.reduce(RaftForwardPickerFlowEvent.LoadSucceeded(opened.requestGeneration))
        assertTrue(loaded.rowsInteractive)
        val queried = loaded.reduce(RaftForwardPickerFlowEvent.QueryChanged("release"))
        assertEquals(RaftForwardPickerLoadState.Loading, queried.loadState)
        assertFalse(queried.rowsInteractive)
        assertTrue(queried.dismissEnabled)
        assertEquals(loaded.requestGeneration + 1, queried.requestGeneration)
        assertEquals(
            queried,
            queried.reduce(RaftForwardPickerFlowEvent.LoadSucceeded(loaded.requestGeneration)),
            "late success must not reopen rows"
        )

        val failed =
            queried.reduce(
                RaftForwardPickerFlowEvent.LoadFailed(queried.requestGeneration, "offline")
            )
        assertEquals(RaftForwardPickerLoadState.Error, failed.loadState)
        assertFalse(failed.rowsInteractive)
        assertTrue(failed.dismissEnabled)

        val retried = failed.reduce(RaftForwardPickerFlowEvent.Retry(appending = false))
        assertEquals(RaftForwardPickerLoadState.Loading, retried.loadState)
        val ready = retried.reduce(RaftForwardPickerFlowEvent.LoadSucceeded(retried.requestGeneration))
        val appending = ready.reduce(RaftForwardPickerFlowEvent.LoadMore)
        assertEquals(RaftForwardPickerLoadState.Loading, appending.loadState)
        assertTrue(appending.appending)
        assertFalse(appending.rowsInteractive)
        assertTrue(appending.dismissEnabled)
        val appended = appending.reduce(RaftForwardPickerFlowEvent.LoadSucceeded(appending.requestGeneration))
        val selected =
            appended.reduce(
                RaftForwardPickerFlowEvent.TargetSelected(
                    stableKey = "target-a",
                    renderGeneration = appended.requestGeneration
                )
            )
        assertEquals(RaftForwardPickerStep.Targets, selected.step)
        assertEquals("target-a", selected.selectedKey)
        assertEquals(setOf("target-a"), selected.selectedKeys)
        assertEquals(ready.sessionKey, selected.sessionKey)
        assertEquals("release", selected.query)
        assertTrue(selected.rowsInteractive)
        assertTrue(selected.dismissEnabled)

        val note = selected.reduce(RaftForwardPickerFlowEvent.Continue)
        assertEquals(RaftForwardPickerStep.Note, note.step)
        assertEquals("target-a", note.selectedKey)
        assertEquals(setOf("target-a"), note.selectedKeys)
        assertFalse(note.rowsInteractive)
        assertFalse(note.dismissEnabled)

        val restored = note.reduce(RaftForwardPickerFlowEvent.NoteBack)
        assertEquals(RaftForwardPickerStep.Targets, restored.step)
        assertEquals(note.sessionKey, restored.sessionKey)
        assertEquals(note.query, restored.query)
        assertEquals("target-a", restored.selectedKey)
        assertTrue(restored.rowsInteractive)

        val dismissed = restored.reduce(RaftForwardPickerFlowEvent.Dismiss)
        assertEquals(RaftForwardPickerStep.Closed, dismissed.step)
        assertEquals(null, dismissed.sessionKey)
        assertTrue(dismissed.requestGeneration > restored.requestGeneration)
        assertEquals(
            dismissed,
            dismissed.reduce(RaftForwardPickerFlowEvent.LoadSucceeded(restored.requestGeneration))
        )
    }

    @Test
    fun queuedSelectionFromOlderRenderGenerationIsRejected() {
        val opened =
            RaftForwardPickerFlowState().reduce(
                RaftForwardPickerFlowEvent.Open("thread-1", "server-1")
            )
        val loaded = opened.reduce(RaftForwardPickerFlowEvent.LoadSucceeded(opened.requestGeneration))
        val searching = loaded.reduce(RaftForwardPickerFlowEvent.QueryChanged("new query"))
        val newResults =
            searching.reduce(RaftForwardPickerFlowEvent.LoadSucceeded(searching.requestGeneration))

        val afterQueuedTap =
            newResults.reduce(
                RaftForwardPickerFlowEvent.TargetSelected(
                    stableKey = "stale-target",
                    renderGeneration = loaded.requestGeneration
                )
            )

        assertEquals(newResults, afterQueuedTap)
        assertEquals(RaftForwardPickerStep.Targets, afterQueuedTap.step)
        assertTrue(afterQueuedTap.rowsInteractive)

        val selected =
            newResults.reduce(
                RaftForwardPickerFlowEvent.TargetSelected(
                    stableKey = "current-target",
                    renderGeneration = newResults.requestGeneration
                )
            )
        val note = selected.reduce(RaftForwardPickerFlowEvent.Continue)
        assertEquals(
            note,
            note.reduce(
                RaftForwardPickerFlowEvent.TargetSelected(
                    stableKey = "queued-after-note",
                    renderGeneration = newResults.requestGeneration
                )
            )
        )
    }

    @Test
    fun rapidQueryAtoBtoARejectsBothOlderGenerations() {
        val firstA = RaftForwardPickerRequestIdentity("server-1", session, "a", 1)
        val queryB = RaftForwardPickerRequestIdentity("server-1", session, "b", 2)
        val latestA = RaftForwardPickerRequestIdentity("server-1", session, "a", 3)

        assertFalse(firstA.accepts("server-1", session, "a", 3))
        assertFalse(queryB.accepts("server-1", session, "a", 3))
        assertTrue(latestA.accepts("server-1", session, "a", 3))
    }

    @Test
    fun serverOrSessionSwitchRejectsLateResult() {
        val request = RaftForwardPickerRequestIdentity("server-1", session, "", 4)

        assertFalse(request.accepts("server-2", session.copy(serverId = "server-2"), "", 4))
        assertFalse(request.accepts("server-1", session.copy(openGeneration = 8), "", 4))
    }

    @Test
    fun latePagingResultCannotAppendAfterQueryOrServerSwitch() {
        val pagingRequest = RaftForwardPickerRequestIdentity("server-1", session, "", 5)

        assertFalse(pagingRequest.accepts("server-1", session, "release", 6))
        assertFalse(
            pagingRequest.accepts(
                "server-2",
                session.copy(serverId = "server-2"),
                "",
                6
            )
        )
    }

    @Test
    fun visualKindParticipatesInStableRowKey() {
        val public = pickerItem("same-id", RaftForwardTargetVisualKind.PublicChannel)
        val private = pickerItem("same-id", RaftForwardTargetVisualKind.PrivateChannel)
        val joint = pickerItem("same-id", RaftForwardTargetVisualKind.JointChannel)

        assertNotEquals(public.rowKey, private.rowKey)
        assertNotEquals(private.rowKey, joint.rowKey)
        assertEquals(RaftLucideIconName.Hash, public.visualKind.iconName())
        assertEquals(RaftLucideIconName.Lock, private.visualKind.iconName())
        assertEquals(RaftLucideIconName.GitBranch, joint.visualKind.iconName())
    }

    private fun pickerItem(
        id: String,
        visualKind: RaftForwardTargetVisualKind
    ): RaftForwardTargetPickerItem<String> =
        RaftForwardTargetPickerItem(
            stableKey = id,
            title = id,
            subtitle = "",
            visualKind = visualKind,
            payload = id
        )
}
