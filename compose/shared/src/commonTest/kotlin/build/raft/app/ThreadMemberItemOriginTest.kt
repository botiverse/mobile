package build.raft.app

import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.MemberServerRef
import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A joint channel roster row as a member row (#wg-rbac:#46): computed rows carry `inViewerServer`,
 * `servers` and `canRemoveFromChannel` (botiverse/slock ec13decd3 `channels.ts`
 * `buildChannelMembersView`), and `serverId` is only the person's first source.
 */
class ThreadMemberItemOriginTest {
    private val local = ServerId("server-1")
    private val peer = MemberServerRef("server-peer", "Peer Labs", "peer-labs")
    private val acme = MemberServerRef("server-acme", "Acme", "acme")
    private val person =
        MemberProfile(
            id = MemberId("user-2"),
            displayName = "Ada",
            handle = "ada",
            kind = MemberKind.Human,
            serverId = ServerId(peer.id),
            serverName = peer.name,
        )

    @Test
    fun aViewedServerMemberWhoseFirstSourceIsAnotherServerStaysLocal() {
        val row = person.copy(inViewerServer = true, servers = listOf(peer)).toRaftThreadMemberItem(local)

        // Opens the full profile in the viewed server and keeps Remove (older app: remote, no Remove).
        assertEquals(local.value, row.sourceServerId)
        assertEquals(true, row.canRemove)
        // Still names the other server it takes part through, as web's tag does.
        assertEquals("Peer Labs", row.sourceServerLabel)
        // "Already joined here" keeps reading the projection the row came from.
        assertEquals(peer.id, row.rosterServerId)
        assertEquals(setOf<String>(), threadAddMemberJoinedKeys(listOf(row), local.value))
    }

    @Test
    fun anotherServersMemberNamesEveryServerAndRemovesOnlyWhenTheServerSaysSo() {
        val external = person.copy(inViewerServer = false, servers = listOf(peer, acme), effectiveChannelRole = "guest")

        val row = external.toRaftThreadMemberItem(local)
        assertEquals(peer.id, row.sourceServerId)
        assertEquals("Peer Labs +1", row.sourceServerLabel)
        assertEquals(false, row.canRemove)
        // A Guest label is a fact of the viewed server only.
        assertEquals("member", row.effectiveChannelRole)

        // A host admin (joint authority) may remove another server's people.
        assertEquals(true, external.copy(canRemoveFromChannel = true).toRaftThreadMemberItem(local).canRemove)
    }

    @Test
    fun olderServersWithoutTheFieldsKeepTodaysReading() {
        val remote = person.toRaftThreadMemberItem(local)
        assertEquals(peer.id, remote.sourceServerId)
        assertEquals("Peer Labs", remote.sourceServerLabel)
        assertEquals(false, remote.canRemove)

        val own = person.copy(serverId = local, serverName = "Slock", effectiveChannelRole = "guest").toRaftThreadMemberItem(local)
        assertEquals(local.value, own.sourceServerId)
        assertNull(own.sourceServerLabel)
        assertEquals(true, own.canRemove)
        assertEquals("guest", own.effectiveChannelRole)
    }
}
