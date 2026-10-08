package build.raft.app

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

    @Test
    fun middleOfTheTrackIsHalf() {
        assertEquals(0.5f, threadInlineAudioSeekFraction(width / 2, width, half))
    }

    @Test
    fun unmeasuredTrackSeeksNowhere() {
        assertEquals(0f, threadInlineAudioSeekFraction(10f, 0f, half))
        assertEquals(0f, threadInlineAudioSeekFraction(10f, 12f, half))
    }
}
