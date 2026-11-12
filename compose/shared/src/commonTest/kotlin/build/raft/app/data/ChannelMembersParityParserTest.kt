package build.raft.app.data

import build.raft.app.model.ChannelId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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

    @Test
    fun jointRosterOriginFactsAreReadAndAbsentOnOlderServers() {
        // botiverse/slock ec13decd3 packages/server/src/routes/channels.ts buildChannelMembersView:
        // computed joint rows carry `servers` (humans), `inViewerServer` and, with joint
        // authority, `canRemoveFromChannel` (people and agents).
        val snapshot = assertNotNull(parseSharedChannelMemberSnapshot("""
            {"humans":[{"id":"human-1","name":"Ada","serverId":"peer","serverName":"Peer","inViewerServer":true,"canRemoveFromChannel":true,
                        "servers":[{"id":"peer","name":"Peer","slug":"peer"},{"id":"acme","name":"Acme","slug":"acme"}]},
                       {"id":"human-2","name":"Bo"}],
             "agents":[{"id":"agent-1","name":"Mira","serverId":"peer","status":"online","inViewerServer":false,"canRemoveFromChannel":false}]}
        """.trimIndent(), ChannelId("channel-1")))
        val (ada, bo, mira) = snapshot.members
        assertEquals(listOf("peer", "acme"), ada.servers?.map { it.id })
        assertEquals("Acme", ada.servers?.get(1)?.name)
        assertEquals(true, ada.inViewerServer)
        assertEquals(true, ada.canRemoveFromChannel)
        assertEquals(false, mira.inViewerServer)
        assertEquals(false, mira.canRemoveFromChannel)
        assertNull(bo.servers)
        assertNull(bo.inViewerServer)
        assertNull(bo.canRemoveFromChannel)
    }
}
