package build.raft.app.data.realtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageSeqRecoveryPolicyTest {
    @Test
    fun gapFrameAppliesImmediatelyAndRequestsRepair() {
        val initial = MessageSeqRecoveryPolicy.initial<String>(appliedSeq = 1)

        val gap = MessageSeqRecoveryPolicy.onFrame(initial, frame(seq = 4))

        assertTrue(gap.repairRequired)
        assertEquals(4L, gap.state.appliedSeq)
        assertEquals(listOf("event-4"), gap.state.appliedEvents)
        assertEquals(emptyList(), gap.state.deferredFrames)
    }

    @Test
    fun repairBatchAppliesSparseEventsAndAdvancesToCurrentSeq() {
        val initial = MessageSeqRecoveryPolicy.initial<String>(appliedSeq = 1)
        val gap = MessageSeqRecoveryPolicy.onFrame(initial, frame(seq = 4))

        val repaired =
            MessageSeqRecoveryPolicy.onRepairBatch(
                gap.state,
                MessageSeqRepairBatch(
                    events = listOf(frame(seq = 2), frame(seq = 4)),
                    currentSeq = 5,
                    hasMore = false
                )
            )

        assertFalse(repaired.repairRequired)
        assertEquals(5L, repaired.state.appliedSeq)
        assertEquals(listOf("event-4", "event-2", "event-4"), repaired.state.appliedEvents)
        assertEquals(emptyList(), repaired.state.deferredFrames)
    }

    @Test
    fun hasMoreRequestsFollowUpWithoutBlockingLaterFrames() {
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
            MessageSeqRecoveryPolicy.onFrame(
                firstRepair.state,
                frame(seq = 4)
            )

        assertTrue(firstRepair.state.recoveryIncomplete)
        assertTrue(firstRepair.repairRequired)
        assertEquals(emptyList(), firstRepair.state.deferredFrames)
        assertTrue(followUp.state.recoveryIncomplete)
        assertTrue(followUp.repairRequired)
        assertEquals(listOf("event-3", "event-2", "event-4"), followUp.state.appliedEvents)
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
    fun sameEventSetConvergesToMaxSeqAcrossArrivalPermutations() {
        val terminalEvents = listOf(frame(seq = 1), frame(seq = 2), frame(seq = 3), frame(seq = 4))

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
            assertTrue(
                state.appliedEvents.containsAll(arrivalOrder.map { it.event }),
                "arrival=${arrivalOrder.map { it.seq }}"
            )
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
