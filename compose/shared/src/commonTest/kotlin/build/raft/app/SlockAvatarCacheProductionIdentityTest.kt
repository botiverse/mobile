package build.raft.app

import build.raft.app.data.SlockAvatarCacheSubject
import build.raft.app.model.MemberKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SlockAvatarCacheProductionIdentityTest {
    @Test
    fun onlyTypedHumanOrAgentIdentityCanEnterPersistentAvatarRendering() {
        assertEquals(
            SlockAvatarCacheSubject.Member(MemberKind.Human, "human-1"),
            slockMemberAvatarCacheSubject(MemberKind.Human, "human-1")
        )
        assertEquals(
            SlockAvatarCacheSubject.Member(MemberKind.Agent, "agent-1"),
            slockMemberAvatarCacheSubject(MemberKind.Agent, "agent-1")
        )
        assertNull(slockMemberAvatarCacheSubject(MemberKind.System, "system-1"))
        assertNull(slockMemberAvatarCacheSubject(MemberKind.Human, " "))
        assertNull(slockMemberAvatarCacheSubject(null, "label-or-url"))
        assertFailsWith<IllegalArgumentException> {
            SlockAvatarCacheSubject.Member(MemberKind.System, "system-1")
        }
    }
}
