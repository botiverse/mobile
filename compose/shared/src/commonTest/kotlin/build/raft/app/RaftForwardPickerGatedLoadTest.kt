package build.raft.app

import build.raft.app.thread.raftForwardPickerGatedLoad
import kotlin.test.Test
import kotlin.test.assertNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

class RaftForwardPickerGatedLoadTest {
    @Test
    fun resultSupersededWhileLoadingIsDropped() = runTest {
        var current = true
        val result = raftForwardPickerGatedLoad(debounce = true, isCurrent = { current }) {
            delay(50)
            current = false
            "stale"
        }
        assertNull(result)
    }
}
