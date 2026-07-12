package build.raft.app

import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MembersListRowTest {
    @Test
    fun missingDescriptionDoesNotFallBackToHandle() {
        val member = member(description = null, handle = "JingKe")

        assertNull(membersListRowSubtitle(member))
    }

    @Test
    fun blankDescriptionDoesNotFallBackToHandle() {
        val member = member(description = "   ", handle = "starc")

        assertNull(membersListRowSubtitle(member))
    }

    @Test
    fun nonBlankDescriptionIsTrimmedAndShown() {
        val member = member(description = "  Visual testing and CI  ", handle = "XiShi")

        assertEquals("Visual testing and CI", membersListRowSubtitle(member))
    }

    private fun member(description: String?, handle: String): MemberProfile =
        MemberProfile(
            id = MemberId("member-id"),
            displayName = "Member",
            handle = handle,
            kind = MemberKind.Agent,
            description = description
        )
}
