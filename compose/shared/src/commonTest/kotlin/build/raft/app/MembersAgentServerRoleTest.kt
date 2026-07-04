package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MembersAgentServerRoleTest {
    @Test
    fun parsesEditableAgentServerRoles() {
        assertEquals(MembersAgentServerRole.Admin, "admin".toMembersAgentServerRoleOrNull())
        assertEquals(MembersAgentServerRole.Member, "member".toMembersAgentServerRoleOrNull())
    }

    @Test
    fun normalizesAgentServerRoleWireValues() {
        assertEquals(MembersAgentServerRole.Admin, " Admin ".toMembersAgentServerRoleOrNull())
        assertEquals(MembersAgentServerRole.Member, "MEMBER".toMembersAgentServerRoleOrNull())
    }

    @Test
    fun rejectsUnknownAgentServerRoles() {
        assertNull("owner".toMembersAgentServerRoleOrNull())
        assertNull("".toMembersAgentServerRoleOrNull())
        assertNull(null.toMembersAgentServerRoleOrNull())
    }

    @Test
    fun displayRoleDefaultsMissingAgentServerRoleToMember() {
        assertEquals(MembersAgentServerRole.Member, membersAgentDisplayServerRole(null, null))
        assertEquals(MembersAgentServerRole.Member, membersAgentDisplayServerRole("", " "))
    }

    @Test
    fun displayRolePrefersMemberRoleOverSummaryRole() {
        assertEquals(
            MembersAgentServerRole.Admin,
            membersAgentDisplayServerRole("admin", "member")
        )
    }

    @Test
    fun displayRoleRejectsUnknownNonBlankRole() {
        assertNull(membersAgentDisplayServerRole("owner", null))
    }

    @Test
    fun marksSelectedAgentServerRoleOption() {
        val options = membersAgentServerRoleOptions(MembersAgentServerRole.Member)

        assertEquals(listOf("admin", "member"), options.map { it.id })
        assertTrue(options.first { it.id == "member" }.selected)
        assertFalse(options.first { it.id == "admin" }.selected)
    }
}
