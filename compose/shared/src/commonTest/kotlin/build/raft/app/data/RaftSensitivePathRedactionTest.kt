package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals

class RaftSensitivePathRedactionTest {
    @Test
    fun anInviteTokenNeverReachesTheLogs() {
        assertEquals("external-invites/:redacted", raftRedactSensitivePath("external-invites/tok_ABC123"))
        assertEquals("/external-invites/:redacted/connect-servers", raftRedactSensitivePath("/external-invites/tok_ABC123/connect-servers"))
        assertEquals(
            "https://api.raft.build/api/external-invites/:redacted/accept-as-guest",
            raftRedactSensitivePath("https://api.raft.build/api/external-invites/a%2Fb/accept-as-guest"),
        )
        assertEquals("https://app.raft.build/external-invite/:redacted", raftRedactSensitivePath("https://app.raft.build/external-invite/tok"))
    }

    @Test
    fun otherPathsAreLeftAsTheyAre() {
        listOf("channels/c1/external-invites", "channels/c1/external-invites/i1/resend", "servers/s1", "")
            .forEach { assertEquals(it, raftRedactSensitivePath(it)) }
    }
}
