package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

class RaftAgentStatusDotTest {
    @Test
    fun theWidgetDotFollowsTheAppDot() {
        assertEquals(RaftAgentStatusDotKind.Yellow, raftAgentStatusDotKind("working", external = false))
        assertEquals(RaftAgentStatusDotKind.Lime, raftAgentStatusDotKind("online", external = false))
        assertEquals(RaftAgentStatusDotKind.Lime, raftAgentStatusDotKind("idle", external = false))
        assertEquals(RaftAgentStatusDotKind.Orange, raftAgentStatusDotKind("error", external = false))
        assertEquals(RaftAgentStatusDotKind.Gray, raftAgentStatusDotKind("offline", external = false))
    }

    @Test
    fun anUnknownStatusIsGrayNeverGreen() {
        assertEquals(RaftAgentStatusDotKind.Gray, raftAgentStatusDotKind(null, external = false))
        assertEquals(RaftAgentStatusDotKind.Gray, raftAgentStatusDotKind("something-new", external = false))
    }

    @Test
    fun anExternalAgentIsCyanOutsideItsOnlineWindowAndLimeInside() {
        val now = 1_792_635_000_000L
        assertEquals(
            RaftAgentStatusDotKind.Cyan,
            raftAgentStatusDotKind("working", external = true, lastSeenAt = null, nowEpochMillis = now),
        )
        assertEquals(
            RaftAgentStatusDotKind.Lime,
            raftAgentStatusDotKind("working", external = true, lastSeenAt = "2026-10-22T02:09:30Z", nowEpochMillis = now),
        )
    }
}
