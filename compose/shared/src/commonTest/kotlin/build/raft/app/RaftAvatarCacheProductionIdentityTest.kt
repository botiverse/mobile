package build.raft.app

import build.raft.app.data.RaftAvatarCacheSubject
import build.raft.app.model.MemberKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RaftAvatarCacheProductionIdentityTest {
    @Test
    fun onlyTypedHumanOrAgentIdentityCanEnterPersistentAvatarRendering() {
        assertEquals(
            RaftAvatarCacheSubject.Member(MemberKind.Human, "human-1"),
            raftMemberAvatarCacheSubject(MemberKind.Human, "human-1")
        )
        assertEquals(
            RaftAvatarCacheSubject.Member(MemberKind.Agent, "agent-1"),
            raftMemberAvatarCacheSubject(MemberKind.Agent, "agent-1")
        )
        assertNull(raftMemberAvatarCacheSubject(MemberKind.System, "system-1"))
        assertNull(raftMemberAvatarCacheSubject(MemberKind.Human, " "))
        assertNull(raftMemberAvatarCacheSubject(null, "label-or-url"))
        assertFailsWith<IllegalArgumentException> {
            RaftAvatarCacheSubject.Member(MemberKind.System, "system-1")
        }
    }

    @Test
    fun persistentMemberIdentityRequiresTheSubjectAndOwnerServerAsOnePair() {
        assertEquals(
            RaftMemberAvatarCacheIdentity(
                subject = RaftAvatarCacheSubject.Member(MemberKind.Human, "human-1"),
                serverId = "server-1"
            ),
            raftMemberAvatarCacheIdentity(MemberKind.Human, " human-1 ", " server-1 ")
        )
        assertNull(raftMemberAvatarCacheIdentity(MemberKind.Agent, "agent-1", null))
        assertNull(raftMemberAvatarCacheIdentity(MemberKind.Agent, "agent-1", " "))
        assertNull(raftMemberAvatarCacheIdentity(MemberKind.System, "system-1", "server-1"))
        assertNull(raftMemberAvatarCacheIdentity(MemberKind.Human, null, "server-1"))
    }
}
