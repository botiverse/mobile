package build.raft.app

import com.tencent.kuikly.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SlockForwardTargetPickerTest {
    private val session = SlockForwardPickerSessionKey("thread-1", "server-1", 7)

    @Test
    fun externalShareActionPaddingClearsBottomSafeAreaWithoutChangingZeroInsetGeometry() {
        assertEquals(60.dp, slockForwardPickerActionBottomPadding(48.dp))
        assertEquals(12.dp, slockForwardPickerActionBottomPadding(0.dp))
    }

    @Test
    fun productionFlowAdmitsRequestsSynchronouslyAndRestoresNoteSelection() {
        val opened =
            SlockForwardPickerFlowState().reduce(
                SlockForwardPickerFlowEvent.Open("thread-1", "server-1")
            )
        assertEquals(SlockForwardPickerLoadState.Loading, opened.loadState)
        assertFalse(opened.rowsInteractive)
        assertTrue(opened.dismissEnabled)

        val loaded = opened.reduce(SlockForwardPickerFlowEvent.LoadSucceeded(opened.requestGeneration))
        assertTrue(loaded.rowsInteractive)
        val queried = loaded.reduce(SlockForwardPickerFlowEvent.QueryChanged("release"))
        assertEquals(SlockForwardPickerLoadState.Loading, queried.loadState)
        assertFalse(queried.rowsInteractive)
        assertTrue(queried.dismissEnabled)
        assertEquals(loaded.requestGeneration + 1, queried.requestGeneration)
        assertEquals(
            queried,
            queried.reduce(SlockForwardPickerFlowEvent.LoadSucceeded(loaded.requestGeneration)),
            "late success must not reopen rows"
        )

        val failed =
            queried.reduce(
                SlockForwardPickerFlowEvent.LoadFailed(queried.requestGeneration, "offline")
            )
        assertEquals(SlockForwardPickerLoadState.Error, failed.loadState)
        assertFalse(failed.rowsInteractive)
        assertTrue(failed.dismissEnabled)

        val retried = failed.reduce(SlockForwardPickerFlowEvent.Retry(appending = false))
        assertEquals(SlockForwardPickerLoadState.Loading, retried.loadState)
        val ready = retried.reduce(SlockForwardPickerFlowEvent.LoadSucceeded(retried.requestGeneration))
        val appending = ready.reduce(SlockForwardPickerFlowEvent.LoadMore)
        assertEquals(SlockForwardPickerLoadState.Loading, appending.loadState)
        assertTrue(appending.appending)
        assertFalse(appending.rowsInteractive)
        assertTrue(appending.dismissEnabled)
        val appended = appending.reduce(SlockForwardPickerFlowEvent.LoadSucceeded(appending.requestGeneration))
        val selected =
            appended.reduce(
                SlockForwardPickerFlowEvent.TargetSelected(
                    stableKey = "target-a",
                    renderGeneration = appended.requestGeneration
                )
            )
        assertEquals(SlockForwardPickerStep.Targets, selected.step)
        assertEquals("target-a", selected.selectedKey)
        assertEquals(setOf("target-a"), selected.selectedKeys)
        assertEquals(ready.sessionKey, selected.sessionKey)
        assertEquals("release", selected.query)
        assertTrue(selected.rowsInteractive)
        assertTrue(selected.dismissEnabled)

        val note = selected.reduce(SlockForwardPickerFlowEvent.Continue)
        assertEquals(SlockForwardPickerStep.Note, note.step)
        assertEquals("target-a", note.selectedKey)
        assertEquals(setOf("target-a"), note.selectedKeys)
        assertFalse(note.rowsInteractive)
        assertFalse(note.dismissEnabled)

        val restored = note.reduce(SlockForwardPickerFlowEvent.NoteBack)
        assertEquals(SlockForwardPickerStep.Targets, restored.step)
        assertEquals(note.sessionKey, restored.sessionKey)
        assertEquals(note.query, restored.query)
        assertEquals("target-a", restored.selectedKey)
        assertTrue(restored.rowsInteractive)

        val dismissed = restored.reduce(SlockForwardPickerFlowEvent.Dismiss)
        assertEquals(SlockForwardPickerStep.Closed, dismissed.step)
        assertEquals(null, dismissed.sessionKey)
        assertTrue(dismissed.requestGeneration > restored.requestGeneration)
        assertEquals(
            dismissed,
            dismissed.reduce(SlockForwardPickerFlowEvent.LoadSucceeded(restored.requestGeneration))
        )
    }

    @Test
    fun queuedSelectionFromOlderRenderGenerationIsRejected() {
        val opened =
            SlockForwardPickerFlowState().reduce(
                SlockForwardPickerFlowEvent.Open("thread-1", "server-1")
            )
        val loaded = opened.reduce(SlockForwardPickerFlowEvent.LoadSucceeded(opened.requestGeneration))
        val searching = loaded.reduce(SlockForwardPickerFlowEvent.QueryChanged("new query"))
        val newResults =
            searching.reduce(SlockForwardPickerFlowEvent.LoadSucceeded(searching.requestGeneration))

        val afterQueuedTap =
            newResults.reduce(
                SlockForwardPickerFlowEvent.TargetSelected(
                    stableKey = "stale-target",
                    renderGeneration = loaded.requestGeneration
                )
            )

        assertEquals(newResults, afterQueuedTap)
        assertEquals(SlockForwardPickerStep.Targets, afterQueuedTap.step)
        assertTrue(afterQueuedTap.rowsInteractive)

        val selected =
            newResults.reduce(
                SlockForwardPickerFlowEvent.TargetSelected(
                    stableKey = "current-target",
                    renderGeneration = newResults.requestGeneration
                )
            )
        val note = selected.reduce(SlockForwardPickerFlowEvent.Continue)
        assertEquals(
            note,
            note.reduce(
                SlockForwardPickerFlowEvent.TargetSelected(
                    stableKey = "queued-after-note",
                    renderGeneration = newResults.requestGeneration
                )
            )
        )
    }

    @Test
    fun rapidQueryAtoBtoARejectsBothOlderGenerations() {
        val firstA = SlockForwardPickerRequestIdentity("server-1", session, "a", 1)
        val queryB = SlockForwardPickerRequestIdentity("server-1", session, "b", 2)
        val latestA = SlockForwardPickerRequestIdentity("server-1", session, "a", 3)

        assertFalse(firstA.accepts("server-1", session, "a", 3))
        assertFalse(queryB.accepts("server-1", session, "a", 3))
        assertTrue(latestA.accepts("server-1", session, "a", 3))
    }

    @Test
    fun serverOrSessionSwitchRejectsLateResult() {
        val request = SlockForwardPickerRequestIdentity("server-1", session, "", 4)

        assertFalse(request.accepts("server-2", session.copy(serverId = "server-2"), "", 4))
        assertFalse(request.accepts("server-1", session.copy(openGeneration = 8), "", 4))
    }

    @Test
    fun latePagingResultCannotAppendAfterQueryOrServerSwitch() {
        val pagingRequest = SlockForwardPickerRequestIdentity("server-1", session, "", 5)

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
        val public = pickerItem("same-id", SlockForwardTargetVisualKind.PublicChannel)
        val private = pickerItem("same-id", SlockForwardTargetVisualKind.PrivateChannel)
        val joint = pickerItem("same-id", SlockForwardTargetVisualKind.JointChannel)

        assertNotEquals(public.rowKey, private.rowKey)
        assertNotEquals(private.rowKey, joint.rowKey)
        assertEquals(SlockLucideIconName.Hash, public.visualKind.iconName())
        assertEquals(SlockLucideIconName.Lock, private.visualKind.iconName())
        assertEquals(SlockLucideIconName.GitBranch, joint.visualKind.iconName())
    }

    private fun pickerItem(
        id: String,
        visualKind: SlockForwardTargetVisualKind
    ): SlockForwardTargetPickerItem<String> =
        SlockForwardTargetPickerItem(
            stableKey = id,
            title = id,
            subtitle = "",
            visualKind = visualKind,
            payload = id
        )
}
