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

    @Test
    fun agentRuntimeEditorUsesSelectedMachineRuntimes() {
        val runtimes =
            membersAgentMachineRuntimeIds(
                machines =
                    listOf(
                        SharedCreateAgentMachineOption(
                            id = "machine-1",
                            name = "MacBook",
                            status = "online",
                            runtimes = listOf("codex", "claude")
                        ),
                        SharedCreateAgentMachineOption(
                            id = "machine-2",
                            name = "Linux Mini",
                            status = "online",
                            runtimes = listOf("pi")
                        )
                    ),
                machineId = "machine-1"
            )

        assertEquals(listOf("codex", "claude"), runtimes)
        val runtimeItems = createAgentRuntimeDropdownItems(runtimes)
        assertTrue(runtimeItems.first { it.id == "codex" }.enabled)
        assertTrue(runtimeItems.first { it.id == "claude" }.enabled)
        assertFalse(runtimeItems.first { it.id == "pi" }.enabled)
        assertEquals("Pi (not installed)", runtimeItems.first { it.id == "pi" }.label)
    }

    @Test
    fun agentRuntimeEditorReturnsNoRuntimesForUnknownMachine() {
        assertEquals(
            emptyList(),
            membersAgentMachineRuntimeIds(
                machines =
                    listOf(
                        SharedCreateAgentMachineOption(
                            id = "machine-1",
                            name = "MacBook",
                            status = "online",
                            runtimes = listOf("codex")
                        )
                    ),
                machineId = "missing"
            )
        )
    }
}
