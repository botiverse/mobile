package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

class RaftVideoProgressContractTest {
    @Test
    fun replayResetsToZeroAndAdvancesGeneration() {
        val contract = RaftVideoProgressContract()
        contract.progress(900L, 2_000L)
        contract.ended(2_000L)
        val oldGeneration = contract.currentGeneration
        val replay = contract.replay()
        assertEquals(0L, replay.currentMs)
        assertEquals(1L, replay.generation)
        assertEquals(false, replay.terminal)
        assertEquals(replay, contract.ended(2_000L, callbackGeneration = oldGeneration))
        assertEquals(replay, contract.progress(1_500L, 2_000L, callbackGeneration = oldGeneration))
    }

    @Test
    fun seekAndUiProjectionStayWithinDuration() {
        val contract = RaftVideoProgressContract()
        contract.progress(100L, 2_000L)
        assertEquals(2_000L, contract.seek(9_000L).currentMs)
        assertEquals(0L, contract.seek(-20L).currentMs)
        assertEquals(2_000 to 2_000, raftVideoProgressForUi(9_000, 2_000, terminal = true))
    }

    @Test
    fun terminalGateRejectsOldEndedAfterNewReadyUntilPlaybackStarts() {
        val gate = RaftVideoPlaybackAttemptGate()
        gate.begin(1L)

        assertEquals(false, gate.acceptsProgress(0L))
        assertEquals(true, gate.acceptsProgress(1L))
        assertEquals(false, gate.acceptsTerminal(0L))
        assertEquals(false, gate.acceptsTerminal(1L))

        gate.markStarted(1L)
        assertEquals(false, gate.acceptsTerminal(1L))
        gate.markTerminalEligible(1L)
        assertEquals(true, gate.acceptsTerminal(1L))
    }
}
