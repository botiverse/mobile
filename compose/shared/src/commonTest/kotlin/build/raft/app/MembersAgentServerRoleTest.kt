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
    fun humanRemoveActionFollowsReactServerRoleGate() {
        assertTrue(
            membersCanRemoveHumanMember(
                actorRole = "admin",
                targetRole = "member",
                targetMembershipStatus = "active",
                targetMemberId = "member-1",
                currentUserId = "admin-1",
                targetServerId = "server-1",
                currentServerId = "server-1"
            )
        )
        assertFalse(
            membersCanRemoveHumanMember(
                actorRole = "member",
                targetRole = "admin",
                targetMembershipStatus = "active",
                targetMemberId = "admin-1",
                currentUserId = "member-1",
                targetServerId = "server-1",
                currentServerId = "server-1"
            )
        )
        assertFalse(
            membersCanRemoveHumanMember(
                actorRole = "admin",
                targetRole = "admin",
                targetMembershipStatus = "active",
                targetMemberId = "admin-2",
                currentUserId = "admin-1",
                targetServerId = "server-1",
                currentServerId = "server-1"
            )
        )
        assertFalse(
            membersCanRemoveHumanMember(
                actorRole = "admin",
                targetRole = "owner",
                targetMembershipStatus = "active",
                targetMemberId = "owner-1",
                currentUserId = "admin-1",
                targetServerId = "server-1",
                currentServerId = "server-1"
            )
        )
    }

    @Test
    fun humanRemoveActionBlocksSelfRemovedRemoteAndLastOwner() {
        assertFalse(
            membersCanRemoveHumanMember(
                actorRole = "owner",
                targetRole = "member",
                targetMembershipStatus = "active",
                targetMemberId = "owner-1",
                currentUserId = "owner-1",
                targetServerId = "server-1",
                currentServerId = "server-1"
            )
        )
        assertFalse(
            membersCanRemoveHumanMember(
                actorRole = "owner",
                targetRole = "member",
                targetMembershipStatus = "removed",
                targetMemberId = "member-1",
                currentUserId = "owner-1",
                targetServerId = "server-1",
                currentServerId = "server-1"
            )
        )
        assertFalse(
            membersCanRemoveHumanMember(
                actorRole = "owner",
                targetRole = "member",
                targetMembershipStatus = "active",
                targetMemberId = "member-1",
                currentUserId = "owner-1",
                targetServerId = "remote-server",
                currentServerId = "server-1"
            )
        )
        assertFalse(
            membersCanRemoveHumanMember(
                actorRole = "owner",
                targetRole = "owner",
                targetMembershipStatus = "active",
                targetMemberId = "owner-2",
                currentUserId = "owner-1",
                targetServerId = "server-1",
                currentServerId = "server-1",
                ownerCount = 1
            )
        )
    }

    @Test
    fun ownerCanRemoveNonLastOwnersAdminsAndMembers() {
        assertTrue(
            membersCanRemoveHumanMember(
                actorRole = "owner",
                targetRole = "owner",
                targetMembershipStatus = "active",
                targetMemberId = "owner-2",
                currentUserId = "owner-1",
                targetServerId = "server-1",
                currentServerId = "server-1",
                ownerCount = 2
            )
        )
        assertTrue(
            membersCanRemoveHumanMember(
                actorRole = "owner",
                targetRole = "admin",
                targetMembershipStatus = "active",
                targetMemberId = "admin-1",
                currentUserId = "owner-1",
                targetServerId = "server-1",
                currentServerId = "server-1"
            )
        )
        assertTrue(
            membersCanRemoveHumanMember(
                actorRole = "owner",
                targetRole = "member",
                targetMembershipStatus = "active",
                targetMemberId = "member-1",
                currentUserId = "owner-1",
                targetServerId = "server-1",
                currentServerId = "server-1"
            )
        )
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
