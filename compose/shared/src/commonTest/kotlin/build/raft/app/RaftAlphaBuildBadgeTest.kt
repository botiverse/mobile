package build.raft.app

import build.raft.app.network.RaftAppIdentity
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RaftAlphaBuildBadgeTest {
    private fun identity(buildType: String) = RaftAppIdentity(name = "Raft", version = "1.0", buildNumber = "1", buildType = buildType)

    @Test
    fun onlyTheAlphaBuildTypeShowsTheAlphaBadge() {
        assertTrue(raftIsAlphaBuild(identity("alpha")))
        for (other in listOf("release", "play", "debug", "connectedTest")) {
            assertFalse(raftIsAlphaBuild(identity(other)), other)
        }
        assertFalse(raftIsAlphaBuild(null))
    }
}
