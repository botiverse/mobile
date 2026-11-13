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
        assertEquals("https://app.raft.build/join/:redacted", raftRedactSensitivePath("https://app.raft.build/join/tok"))
        assertEquals("auth/invite-info?token=:redacted", raftRedactSensitivePath("auth/invite-info?token=tok_ABC"))
    }

    @Test
    fun otherPathsAreLeftAsTheyAre() {
        listOf("channels/c1/external-invites", "channels/c1/external-invites/i1/resend", "channels/c1/join", "servers/s1", "")
            .forEach { assertEquals(it, raftRedactSensitivePath(it)) }
    }
}
