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
 * A person's profile "From" row (web HumanDetailPanel `sourceServerLabel` /
 * utils/channelLocalMembership.ts `memberServerBadgeLabels`, botiverse/slock@44ce160f1, #14009).
 */
class MembersHumanSourceServerLabelTest {
    private val person =
        MemberProfile(id = MemberId("user-2"), displayName = "Ada", handle = "ada", kind = MemberKind.Human)

    @Test
    fun theServersAnswerIsShownExactly() {
        val both =
            person.copy(
                serverId = ServerId("server-peer"),
                servers = listOf(MemberServerRef("server-1", "Slock", "slock"), MemberServerRef("server-peer", "Peer Labs", "peer-labs")),
            )
        // Every server, the viewer's included, when any is not the viewer's.
        assertEquals("Slock, Peer Labs", membersHumanSourceServerLabel(both, "server-1"))
        // A name falls back to the slug.
        assertEquals("peer", membersHumanSourceServerLabel(person.copy(servers = listOf(MemberServerRef("server-peer", "", "peer"))), "server-1"))
        // Only the viewer's server: a local person, nothing.
        assertNull(membersHumanSourceServerLabel(person.copy(servers = listOf(MemberServerRef("server-1", "Slock", "slock"))), "server-1"))
        // An empty list (the channel lists them through no server) shows nothing, never the single fields.
        assertNull(
            membersHumanSourceServerLabel(
                person.copy(serverId = ServerId("server-peer"), serverName = "Peer Labs", servers = emptyList()),
                "server-1",
            )
        )
    }

    @Test
    fun withoutAServersAnswerTheSingleServerFieldsStand() {
        assertEquals(
            "Peer Labs",
            membersHumanSourceServerLabel(person.copy(serverId = ServerId("server-peer"), serverName = "Peer Labs"), "server-1"),
        )
        assertNull(membersHumanSourceServerLabel(person.copy(serverId = ServerId("server-1"), serverName = "Slock"), "server-1"))
        assertNull(membersHumanSourceServerLabel(person, "server-1"))
        assertNull(membersHumanSourceServerLabel(person.copy(serverId = ServerId("server-peer"), serverName = "Peer Labs"), null))
    }
}
