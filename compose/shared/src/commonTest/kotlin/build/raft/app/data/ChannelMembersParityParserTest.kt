package build.raft.app.data

import build.raft.app.model.ChannelId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class ChannelMembersParityParserTest {
    @Test
    fun rolesAndExternalParticipantsSurviveWithoutInventingCapabilities() {
        // Verified against botiverse/slock 1443178ec2c72422256ea05f7d6fd5e480429e0a,
        // packages/server/src/routes/channels.ts GET /:id/members and channelService.ts.
        val snapshot = assertNotNull(parseSharedChannelMemberSnapshot("""
            {"humans":[{"id":"human-1","name":"Alex","role":"member","effectiveChannelRole":"admin","channelAdminBasis":"channel_role","canChangeChannelRole":true}],
             "agents":[{"id":"agent-1","name":"Mira","serverId":"peer","serverName":"Peer","status":"online","effectiveChannelRole":"member"}],
             "externalMembers":[{"id":"projection-1","displayName":"Sam","provider":"slack","handles":["sam","sam.work"],"actorKind":"human","avatarUrl":null}]}
        """.trimIndent(), ChannelId("channel-1")))
        assertEquals("admin", snapshot.members[0].effectiveChannelRole)
        assertEquals("channel_role", snapshot.members[0].channelAdminBasis)
        assertEquals("Peer", snapshot.members[1].serverName)
        assertEquals(listOf("sam", "sam.work"), snapshot.externalMembers.single().handles)
        assertEquals(2, snapshot.members.size)
        assertFalse(snapshot.canAddMembers)
        assertFalse(snapshot.canRemoveMembers)
    }
}
