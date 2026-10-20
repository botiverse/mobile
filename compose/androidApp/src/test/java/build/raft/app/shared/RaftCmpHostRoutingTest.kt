package build.raft.app.shared

import org.junit.Assert.assertEquals
import org.junit.Test

/** Every page opens in [RaftCmpActivity]; the app shell keeps its task-owning subclass. */
class RaftCmpHostRoutingTest {
    @Test
    fun everyPageButTheAppShellOpensInRaftCmpActivity() {
        for (pageName in listOf(RaftCmpActivity.PAGE_THREAD, RaftCmpActivity.PAGE_SETTINGS_STORAGE, "compose_cmp_hello", "")) {
            assertEquals(pageName, RaftCmpActivity::class.java, raftHostActivityClass(pageName))
        }
        assertEquals(RaftAppShellActivity::class.java, raftHostActivityClass(RaftCmpActivity.PAGE_APP_SHELL))
    }
}
