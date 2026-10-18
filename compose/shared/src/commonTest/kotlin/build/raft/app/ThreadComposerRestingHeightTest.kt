package build.raft.app

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Channel entry jumped ~76pt on every open: the composer drew nothing until its draft session
 * started, then the editor card appeared and shrank the bottom-pinned message list. The pre-mount
 * placeholder reserves the resting height recorded here.
 */
class ThreadComposerRestingHeightTest {
    @AfterTest
    fun clear() {
        ThreadComposerRestingHeight.clearForTest()
    }

    @Test
    fun firstOpenUsesTheDefaultThenTheMeasuredHeight() {
        val key = ThreadComposerSurface(channelId = "c1").threadComposerRestingHeightKey()
        assertEquals(ThreadComposerRestingHeight.DEFAULT_DP, ThreadComposerRestingHeight.get(key))
        ThreadComposerRestingHeight.record(key, 81)
        assertEquals(81, ThreadComposerRestingHeight.get(key))
    }

    @Test
    fun surfacesWithDifferentChromeKeepSeparateHeights() {
        val channel = ThreadComposerSurface(channelId = "c1").threadComposerRestingHeightKey()
        val noTask = ThreadComposerSurface(channelId = "c1", showTaskButton = false).threadComposerRestingHeightKey()
        ThreadComposerRestingHeight.record(channel, 81)
        assertEquals(ThreadComposerRestingHeight.DEFAULT_DP, ThreadComposerRestingHeight.get(noTask))
    }

    @Test
    fun nonPositiveMeasurementsAreIgnored() {
        val key = ThreadComposerSurface(channelId = "c1").threadComposerRestingHeightKey()
        ThreadComposerRestingHeight.record(key, 0)
        assertEquals(ThreadComposerRestingHeight.DEFAULT_DP, ThreadComposerRestingHeight.get(key))
    }

    @Test
    fun onlyAnEmptyComposerCountsAsAtRest() {
        assertTrue(ThreadComposerUiState().isThreadComposerAtRest())
        assertFalse(ThreadComposerUiState(seedText = "draft").isThreadComposerAtRest())
        assertFalse(ThreadComposerUiState(statusLabel = "Sending").isThreadComposerAtRest())
    }
}
