package build.raft.app

import build.raft.app.thread.threadInlineAudioSeekFraction
import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadInlineAudioSeekTest {
    private val width = 212f
    private val half = 6f

    @Test
    fun trackEndsMapToZeroAndOne() {
        assertEquals(0f, threadInlineAudioSeekFraction(half, width, half))
        assertEquals(1f, threadInlineAudioSeekFraction(width - half, width, half))
    }

    @Test
    fun touchesOutsideTheTrackClamp() {
        assertEquals(0f, threadInlineAudioSeekFraction(0f, width, half))
        assertEquals(1f, threadInlineAudioSeekFraction(width, width, half))
        assertEquals(0f, threadInlineAudioSeekFraction(-40f, width, half))
        assertEquals(1f, threadInlineAudioSeekFraction(width + 40f, width, half))
    }
}
