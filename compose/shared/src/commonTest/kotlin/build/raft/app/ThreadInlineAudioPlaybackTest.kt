package build.raft.app

import build.raft.app.compat.ui.PlayState
import build.raft.app.compat.ui.VideoPlayControl
import build.raft.app.thread.ThreadInlineAudioPlayback
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ThreadInlineAudioPlaybackTest {
    @Test
    fun seekingPreservesPlayingOrPausedIntent() {
        val playing = ThreadInlineAudioPlayback(
            control = VideoPlayControl.PLAY, state = PlayState.PLAYING, totalTimeMs = 6000
        )
        assertEquals(VideoPlayControl.PLAY, playing.seekTo(2000).control)
        assertEquals(PlayState.PLAYING, playing.seekTo(2000).state)
        val paused = playing.toggle().withState(0, PlayState.PAUSED)
        assertEquals(VideoPlayControl.PAUSE, paused.seekTo(4000).control)
        assertEquals(PlayState.PAUSED, paused.seekTo(4000).state)
    }

    @Test
    fun replayCreatesFreshPlayerAndRejectsOldTerminalCallbacks() {
        val ended = ThreadInlineAudioPlayback(
            control = VideoPlayControl.PLAY, state = PlayState.PLAY_END,
            currentTimeMs = 6000, totalTimeMs = 6000
        ).seekTo(6000).withState(0, PlayState.PLAY_END)
        val replay = ended.toggle()
        assertEquals(1, replay.generation)
        assertEquals(0, replay.currentTimeMs)
        assertEquals(VideoPlayControl.PLAY, replay.control)
        assertNull(replay.seekRequest)
        assertEquals(replay, replay.withState(0, PlayState.PLAY_END))
        assertEquals(replay, replay.withTime(0, 6000, 6000))
        assertEquals(PlayState.PLAYING, replay.withState(1, PlayState.PLAYING).state)
        assertEquals(1000, replay.withTime(1, 1000, 6000).currentTimeMs)
    }

    @Test
    fun seekingAfterEndAllowsNextPlayCommand() {
        val ended = ThreadInlineAudioPlayback(
            control = VideoPlayControl.PLAY, state = PlayState.PLAY_END, totalTimeMs = 6000
        )
        val sought = ended.seekTo(2000)
        assertEquals(PlayState.PAUSED, sought.state)
        assertEquals(VideoPlayControl.PAUSE, sought.control)
        assertEquals(0, sought.generation)
        assertEquals(VideoPlayControl.PLAY, sought.toggle().control)
        assertEquals(2000, sought.toggle().seekRequest?.timeMs)
    }

    @Test
    fun detachingOffScreenPausesPreservesTimeAndResumesFromSavedPosition() {
        val playing =
            ThreadInlineAudioPlayback(
                control = VideoPlayControl.PLAY,
                state = PlayState.PLAYING,
                currentTimeMs = 18_500,
                totalTimeMs = 60_000,
            )
        val detached = playing.onPlayerDetached()
        assertEquals(1, detached.generation)
        assertEquals(VideoPlayControl.PAUSE, detached.control)
        assertEquals(PlayState.PAUSED, detached.state)
        assertEquals(18_500, detached.currentTimeMs)
        assertEquals(60_000, detached.totalTimeMs)
        assertNull(detached.seekRequest)
        // Late callbacks from the releasing player are ignored.
        assertEquals(detached, detached.withState(0, PlayState.PLAYING))

        val resumed = detached.toggle()
        assertEquals(VideoPlayControl.PLAY, resumed.control)
        assertEquals(18_500, resumed.seekRequest?.timeMs)
        assertNull(resumed.pendingResumeTimeMs)
        // Fresh player emitting 0 total duration before metadata loads keeps remembered totalTimeMs.
        assertEquals(60_000, resumed.withTime(resumed.generation, 0, 0).totalTimeMs)
    }

    @Test
    fun externalEvictionPauseReopensFreshPlayerOnNextToggle() {
        val playing =
            ThreadInlineAudioPlayback(
                control = VideoPlayControl.PLAY,
                state = PlayState.PLAYING,
                currentTimeMs = 9_000,
                totalTimeMs = 30_000,
            )
        // Player evicted by concurrency budget reports PAUSED while control remained PLAY.
        val evicted = playing.withState(0, PlayState.PAUSED)
        val restarted = evicted.toggle()
        assertEquals(1, restarted.generation)
        assertEquals(VideoPlayControl.PLAY, restarted.control)
        assertEquals(9_000, restarted.seekRequest?.timeMs)
    }
}
