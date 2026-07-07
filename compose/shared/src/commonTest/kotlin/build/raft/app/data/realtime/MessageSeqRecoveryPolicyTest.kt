package build.raft.app.data.realtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageSeqRecoveryPolicyTest {
    @Test
    fun gapFrameIsDeferredUntilRepairBatchCompletesWaterline() {
        val initial = MessageSeqRecoveryPolicy.initial<String>(appliedSeq = 1)

        val gap = MessageSeqRecoveryPolicy.onFrame(initial, frame(seq = 4))
        val partialRepair =
            MessageSeqRecoveryPolicy.onRepairBatch(
                gap.state,
                MessageSeqRepairBatch(
                    events = listOf(frame(seq = 2)),
                    currentSeq = 4,
                    hasMore = false
                )
            )

        assertTrue(gap.repairRequired)
        assertEquals(listOf("event-2"), partialRepair.state.appliedEvents)
        assertEquals(listOf(4L), partialRepair.state.deferredFrames.map { it.seq })
        assertTrue(partialRepair.repairRequired)
    }

    @Test
    fun repairBatchRestitchesDeferredFramesByRecheckingWatermarkAndDropsDuplicates() {
        val initial = MessageSeqRecoveryPolicy.initial<String>(appliedSeq = 1)
        val gap = MessageSeqRecoveryPolicy.onFrame(initial, frame(seq = 4))

        val repaired =
            MessageSeqRecoveryPolicy.onRepairBatch(
                gap.state,
                MessageSeqRepairBatch(
                    events = listOf(frame(seq = 2), frame(seq = 3), frame(seq = 4)),
                    currentSeq = 4,
                    hasMore = false
                )
            )

        assertFalse(repaired.repairRequired)
        assertEquals(4L, repaired.state.appliedSeq)
        assertEquals(listOf("event-2", "event-3", "event-4"), repaired.state.appliedEvents)
        assertEquals(emptyList(), repaired.state.deferredFrames)
    }

    @Test
    fun hasMoreKeepsRecoveryIncompleteUntilFollowUpBatchClosesGap() {
        val initial = MessageSeqRecoveryPolicy.initial<String>(appliedSeq = 1)
        val gap = MessageSeqRecoveryPolicy.onFrame(initial, frame(seq = 3))

        val firstRepair =
            MessageSeqRecoveryPolicy.onRepairBatch(
                gap.state,
                MessageSeqRepairBatch(
                    events = listOf(frame(seq = 2)),
                    currentSeq = 3,
                    hasMore = true
                )
            )
        val followUp =
            MessageSeqRecoveryPolicy.onRepairBatch(
                firstRepair.state,
                MessageSeqRepairBatch(
                    events = listOf(frame(seq = 3)),
                    currentSeq = 3,
                    hasMore = false
                )
            )

        assertTrue(firstRepair.state.recoveryIncomplete)
        assertTrue(firstRepair.repairRequired)
        assertEquals(listOf(3L), firstRepair.state.deferredFrames.map { it.seq })
        assertFalse(followUp.state.recoveryIncomplete)
        assertFalse(followUp.repairRequired)
        assertEquals(listOf("event-2", "event-3"), followUp.state.appliedEvents)
    }

    @Test
    fun differentEpochFramesDoNotAdvanceCurrentWatermark() {
        val initial = MessageSeqRecoveryPolicy.initial<String>(appliedSeq = 1, epoch = "epoch-a")

        val result =
            MessageSeqRecoveryPolicy.onFrame(
                initial,
                MessageSeqEnvelope(seq = 2, epoch = "epoch-b", event = "other")
            )

        assertEquals(1L, result.state.appliedSeq)
        assertEquals(emptyList(), result.state.appliedEvents)
        assertFalse(result.repairRequired)
    }

    @Test
    fun previousEpochRepairBatchDoesNotApplyAfterRebaseline() {
        val rebaseline = MessageSeqRecoveryPolicy.initial<String>(appliedSeq = 0, epoch = "epoch-b")

        val staleRepair =
            MessageSeqRecoveryPolicy.onRepairBatch(
                rebaseline,
                MessageSeqRepairBatch(
                    events = listOf(MessageSeqEnvelope(seq = 1, epoch = "epoch-a", event = "old")),
                    currentSeq = 1,
                    hasMore = false,
                    epoch = "epoch-a"
                )
            )
        val staleFrame =
            MessageSeqRecoveryPolicy.onFrame(
                staleRepair.state,
                MessageSeqEnvelope(seq = 2, epoch = "epoch-a", event = "old-late")
            )

        assertEquals("epoch-b", staleFrame.state.epoch)
        assertEquals(0L, staleFrame.state.appliedSeq)
        assertEquals(emptyList(), staleFrame.state.appliedEvents)
        assertFalse(staleFrame.repairRequired)
    }

    @Test
    fun sameEventSetConvergesAcrossArrivalPermutationsWhenRepairBatchAnchorsTerminalState() {
        val terminalEvents = listOf(frame(seq = 1), frame(seq = 2), frame(seq = 3), frame(seq = 4))
        val expected = terminalEvents.map { it.event }

        terminalEvents.permutations().forEach { arrivalOrder ->
            var state = MessageSeqRecoveryPolicy.initial<String>()
            arrivalOrder.forEach { frame ->
                val frameResult = MessageSeqRecoveryPolicy.onFrame(state, frame)
                state = frameResult.state
                if (frameResult.repairRequired) {
                    state =
                        MessageSeqRecoveryPolicy.onRepairBatch(
                            state,
                            MessageSeqRepairBatch(
                                events = terminalEvents,
                                currentSeq = 4,
                                hasMore = false
                            )
                        ).state
                }
            }

            assertEquals(4L, state.appliedSeq, "arrival=${arrivalOrder.map { it.seq }}")
            assertEquals(expected, state.appliedEvents, "arrival=${arrivalOrder.map { it.seq }}")
            assertEquals(emptyList(), state.deferredFrames, "arrival=${arrivalOrder.map { it.seq }}")
            assertFalse(state.recoveryIncomplete, "arrival=${arrivalOrder.map { it.seq }}")
        }
    }

    private fun frame(seq: Long): MessageSeqEnvelope<String> =
        MessageSeqEnvelope(seq = seq, event = "event-$seq")

    private fun <T> List<T>.permutations(): List<List<T>> {
        if (size <= 1) return listOf(this)
        return flatMapIndexed { index, value ->
            (take(index) + drop(index + 1)).permutations().map { listOf(value) + it }
        }
    }
}
