package build.raft.app

import androidx.lifecycle.Lifecycle
import kotlin.test.Test
import kotlin.test.assertEquals

class RaftVideoPlayerLifecycleTest {
    @Test
    fun playbackPausesOnlyWhenTheHostGoesToTheBackground() {
        val pausing = Lifecycle.Event.values().filter(::raftVideoPausesOnLifecycleEvent)
        assertEquals(listOf(Lifecycle.Event.ON_STOP), pausing)
    }
}
