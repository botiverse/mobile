package build.raft.app.shared

import com.tencent.kuikly.core.render.android.expand.component.KRVideoPlayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlockKRVideoProgressTest {
    @Test
    fun positionIsClampedToKnownDuration() {
        assertEquals(
            SlockVideoProgressSnapshot(positionMs = 2_000L, durationMs = 2_000L),
            slockVideoProgressSnapshot(positionMs = 8_000L, durationMs = 2_000L)
        )
        assertEquals(
            SlockVideoProgressSnapshot(positionMs = 0L, durationMs = 2_000L),
            slockVideoProgressSnapshot(positionMs = -1L, durationMs = 2_000L)
        )
    }

    @Test
    fun unknownDurationPreservesNonNegativePosition() {
        assertEquals(
            SlockVideoProgressSnapshot(positionMs = 1_250L, durationMs = 0L),
            slockVideoProgressSnapshot(positionMs = 1_250L, durationMs = -1L)
        )
    }

    @Test
    fun terminalClampDoesNotReplaceEndedWithSyntheticPausedState() {
        val terminal = slockVideoProgressSnapshot(positionMs = 8_000L, durationMs = 2_000L)

        assertTrue(
            shouldSuppressTerminalClampState(
                lastReportedState = KRVideoPlayState.KRVideoPlayStatePlayEnd,
                playWhenReady = false,
                progress = terminal
            )
        )
        assertFalse(
            shouldSuppressTerminalClampState(
                lastReportedState = KRVideoPlayState.KRVideoPlayStatePlayEnd,
                playWhenReady = true,
                progress = terminal
            )
        )
        assertFalse(
            shouldSuppressTerminalClampState(
                lastReportedState = KRVideoPlayState.KRVideoPlayStatePlayEnd,
                playWhenReady = false,
                progress = slockVideoProgressSnapshot(positionMs = 1_000L, durationMs = 2_000L)
            )
        )
    }
}
