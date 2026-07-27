package build.raft.app

import com.tencent.kuikly.core.views.PlayState
import kotlin.test.Test
import kotlin.test.assertEquals

class SlockFilePreviewVideoPlaybackTest {
    @Test
    fun completionClampsElapsedToKnownDuration() {
        val completed =
            SlockFilePreviewVideoPlaybackState()
                .withProgress(eventGeneration = 0, currentTimeMs = 8_000, totalTimeMs = 2_000)
                .withPlayState(eventGeneration = 0, playState = PlayState.PLAY_END)

        assertEquals(2_000, completed.currentTimeMs)
        assertEquals(2_000, completed.totalTimeMs)
        assertEquals(PlayState.PLAY_END, completed.playState)
        assertEquals(100, slockFilePreviewVideoProgressPercent(completed.currentTimeMs, completed.totalTimeMs))
    }

    @Test
    fun replayResetsAtomicallyAndRejectsLatePreviousGenerationProgress() {
        val completed =
            SlockFilePreviewVideoPlaybackState(currentTimeMs = 2_000, totalTimeMs = 2_000, playState = PlayState.PLAY_END)
        val replaying = completed.replay()
        val afterLateProgress =
            replaying.withProgress(
                eventGeneration = completed.generation,
                currentTimeMs = 8_000,
                totalTimeMs = 2_000
            )

        assertEquals(completed.generation + 1, replaying.generation)
        assertEquals(0, replaying.currentTimeMs)
        assertEquals(PlayState.PAUSED, replaying.playState)
        assertEquals(replaying, afterLateProgress)
    }

    @Test
    fun nativeControllerEndedToPlayingTransitionStartsNewGeneration() {
        val completed =
            SlockFilePreviewVideoPlaybackState(
                generation = 4,
                currentTimeMs = 2_000,
                totalTimeMs = 2_000,
                playState = PlayState.PLAY_END
            )

        val replaying = completed.withPlayState(eventGeneration = 4, playState = PlayState.PLAYING)
        val lateTerminal = replaying.withProgress(eventGeneration = 4, currentTimeMs = 8_000, totalTimeMs = 2_000)

        assertEquals(5, replaying.generation)
        assertEquals(0, replaying.currentTimeMs)
        assertEquals(PlayState.PLAYING, replaying.playState)
        assertEquals(replaying, lateTerminal)
    }

    @Test
    fun currentGenerationProgressAndSeekStayWithinDuration() {
        val replaying =
            SlockFilePreviewVideoPlaybackState(totalTimeMs = 2_000, playState = PlayState.PAUSED).replay()
        val progressed = replaying.withProgress(replaying.generation, currentTimeMs = 1_250, totalTimeMs = 2_000)
        val seekedPastEnd = progressed.seekTo(9_000)

        assertEquals(1_250, progressed.currentTimeMs)
        assertEquals(2_000, seekedPastEnd.currentTimeMs)
        assertEquals(PlayState.PAUSED, seekedPastEnd.playState)
    }

    @Test
    fun unknownDurationKeepsHonestElapsedAndDisablesSeekProjection() {
        val unknown =
            SlockFilePreviewVideoPlaybackState()
                .withProgress(eventGeneration = 0, currentTimeMs = 1_250, totalTimeMs = 0)

        assertEquals(1_250, unknown.currentTimeMs)
        assertEquals(0, unknown.totalTimeMs)
        assertEquals(null, slockFilePreviewVideoProgressPercent(unknown.currentTimeMs, unknown.totalTimeMs))
        assertEquals(0, unknown.seekTo(1_000).currentTimeMs)
    }
}
