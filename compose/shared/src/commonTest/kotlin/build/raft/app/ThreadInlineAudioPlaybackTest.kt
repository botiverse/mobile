package build.raft.app

import com.tencent.kuikly.core.views.PlayState
import com.tencent.kuikly.core.views.VideoPlayControl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ThreadInlineAudioPlaybackTest {
    @Test
    fun seekingNeedsDurationAndClampsToMediaBounds() {
        val unknown = ThreadInlineAudioPlayback()
        assertEquals(unknown, unknown.seekTo(3000))
        val ready = unknown.withTime(0, 0, 6000)
        val first = ready.seekTo(-100)
        val last = first.seekTo(9000)
        assertEquals(0, first.seekRequest?.timeMs)
        assertEquals(6000, last.seekRequest?.timeMs)
        assertEquals(first.seekRequest!!.id + 1, last.seekRequest?.id)
        assertEquals(6000, last.currentTimeMs)
    }

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
}
