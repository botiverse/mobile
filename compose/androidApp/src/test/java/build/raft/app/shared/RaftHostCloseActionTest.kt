package build.raft.app.shared

import org.junit.Assert.assertEquals
import org.junit.Test

class RaftHostCloseActionTest {

    @Test
    fun appShellCloseMovesTaskToBack() {
        assertEquals(
                RaftHostCloseAction.MoveTaskToBack,
                raftHostCloseAction(RaftPages.APP_SHELL)
        )
    }

    @Test
    fun standalonePagesStillFinish() {
        assertEquals(RaftHostCloseAction.Finish, raftHostCloseAction(RaftPages.THREAD))
        assertEquals(RaftHostCloseAction.Finish, raftHostCloseAction(RaftPages.LOGIN))
        assertEquals(RaftHostCloseAction.Finish, raftHostCloseAction(null))
    }
}
