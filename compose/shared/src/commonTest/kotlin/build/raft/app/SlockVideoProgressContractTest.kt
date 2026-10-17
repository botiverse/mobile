package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

class SlockVideoProgressContractTest {
    @Test
    fun terminalClampsAndRejectsLateOlderProgress() {
        val contract = SlockVideoProgressContract()
        assertEquals(700L, contract.progress(700L, 1_000L).currentMs)
        val ended = contract.ended(1_000L)
        assertEquals(1_000L, ended.currentMs)
        assertEquals(1_000L, contract.progress(8_000L, 1_000L).currentMs)
        assertEquals(true, contract.progress(0L, 1_000L).terminal)
    }

    @Test
    fun replayResetsToZeroAndAdvancesGeneration() {
        val contract = SlockVideoProgressContract()
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
        val contract = SlockVideoProgressContract()
        contract.progress(100L, 2_000L)
        assertEquals(2_000L, contract.seek(9_000L).currentMs)
        assertEquals(0L, contract.seek(-20L).currentMs)
        assertEquals(2_000 to 2_000, slockVideoProgressForUi(9_000, 2_000, terminal = true))
    }

    @Test
    fun seekFencesTerminalCallbackFromPreviousAttempt() {
        val contract = SlockVideoProgressContract()
        contract.progress(600L, 2_000L)
        val oldGeneration = contract.currentGeneration
        val seek = contract.seek(400L)

        assertEquals(400L, seek.currentMs)
        assertEquals(false, seek.terminal)
        assertEquals(seek, contract.ended(2_000L, callbackGeneration = oldGeneration))
        assertEquals(true, contract.ended(2_000L).terminal)
    }

    @Test
    fun terminalGateRejectsOldEndedAfterNewReadyUntilPlaybackStarts() {
        val gate = SlockVideoPlaybackAttemptGate()
        gate.begin(1L)

        assertEquals(false, gate.acceptsTerminal(0L))
        assertEquals(false, gate.acceptsTerminal(1L))

        gate.markStarted(1L)
        assertEquals(false, gate.acceptsTerminal(1L))
        gate.markTerminalEligible(1L)
        assertEquals(true, gate.acceptsTerminal(1L))
    }
}
