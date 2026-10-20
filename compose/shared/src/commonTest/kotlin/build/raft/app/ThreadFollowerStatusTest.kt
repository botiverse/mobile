package build.raft.app

import build.raft.app.model.AgentStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ThreadFollowerStatusTest {
    @Test fun onlineFollowerKeepsWireStatusWithoutExternalLastSeen() {
        val result = requireNotNull(followerStatusIndicator("online"))
        assertEquals(AgentStatus.Online, result.status)
        assertEquals(RaftColor.AccentLime, result.color)
        assertFalse(result.external)
    }

    @Test fun errorFollowerUsesSharedErrorPresentation() {
        val result = requireNotNull(followerStatusIndicator("error"))
        assertEquals(AgentStatus.Error, result.status)
        assertEquals(RaftColor.AccentOrange, result.color)
        assertEquals(AgentStatus.Error.label, result.label)
    }

    @Test fun unknownStatusDoesNotInventOfflinePresence() {
        assertNull(followerStatusIndicator("new-server-status"))
    }
}
