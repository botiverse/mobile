package build.raft.app

import org.junit.Test

/**
 * TEMPORARY scratch test for the task #32 watchdog self-proof. It exists only
 * to make the hang watchdog fire on CI (HANG DETECTED + fork exit 73) and is
 * removed by the follow-up commit. DO NOT MERGE.
 */
class HangWatchdogScratchSelfTest {
    @Test
    fun hangForWatchdogSelfProof() {
        Thread.sleep(10 * 60 * 1000L)
    }
}
