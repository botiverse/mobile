package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.EmptyReason
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.ScreenError
import build.raft.app.data.SettingsServerActionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MembersAgentServerRoleTest {
    @Test
    fun ownerHumanRoleOptionsMatchWebPolicy() {
        assertEquals(
            listOf(MembersHumanServerRole.Owner, MembersHumanServerRole.Admin),
            editableHumanRoles(actorRole = "owner", targetRole = "member")
        )
        assertEquals(
            listOf(MembersHumanServerRole.Owner, MembersHumanServerRole.Member),
            editableHumanRoles(actorRole = "owner", targetRole = "admin")
        )
        assertEquals(
            listOf(MembersHumanServerRole.Admin, MembersHumanServerRole.Member),
            editableHumanRoles(actorRole = "owner", targetRole = "owner", ownerCount = 2)
        )
        assertEquals(
            emptyList(),
            editableHumanRoles(actorRole = "owner", targetRole = "owner", ownerCount = 1)
        )
        assertEquals(
            emptyList(),
            editableHumanRoles(actorRole = "owner", targetRole = "owner", ownerCount = null)
        )
    }

    @Test
    fun adminCanOnlyPromoteHumanMemberToAdmin() {
        assertEquals(
            listOf(MembersHumanServerRole.Admin),
            editableHumanRoles(actorRole = "admin", targetRole = "member")
        )
        assertEquals(emptyList(), editableHumanRoles(actorRole = "admin", targetRole = "admin"))
        assertEquals(emptyList(), editableHumanRoles(actorRole = "admin", targetRole = "owner"))
    }

    @Test
    fun humanRoleEditorFailsClosedForSelfRemovedRemoteAndUnknownRole() {
        assertEquals(emptyList(), editableHumanRoles(currentUserId = "human-1"))
        assertEquals(emptyList(), editableHumanRoles(targetMembershipStatus = "removed"))
        assertEquals(
            emptyList(),
            editableHumanRoles(accessMode = MemberDetailAccessMode.RemoteProfileOnly)
        )
        assertEquals(emptyList(), editableHumanRoles(targetServerId = "server-2"))
        assertEquals(emptyList(), editableHumanRoles(targetServerId = null))
        assertEquals(emptyList(), editableHumanRoles(targetServerId = ""))
        assertEquals(emptyList(), editableHumanRoles(currentServerId = null))
        assertEquals(emptyList(), editableHumanRoles(targetRole = "mystery"))
        assertEquals(emptyList(), editableHumanRoles(targetRole = ""))
    }

    @Test
    fun humanRoleOptionsNeverIncludeCurrentRole() {
        MembersHumanServerRole.entries.forEach { current ->
            assertFalse(
                editableHumanRoles(targetRole = current.wireValue, ownerCount = 2).contains(current)
            )
        }
    }

    @Test
    fun humanRoleSaveOutcomeOnlyClosesOnSuccessfulContent() {
        assertEquals(
            MembersHumanRoleSaveOutcome.Success,
            DataResult.Content(SettingsServerActionResult(success = true)).membersHumanRoleSaveOutcome()
        )
        assertEquals(
            MembersHumanRoleSaveOutcome.Error,
            DataResult.Empty(EmptyReason.NoSettingsAvailable).membersHumanRoleSaveOutcome()
        )
        assertEquals(
            MembersHumanRoleSaveOutcome.Error,
            DataResult.Content(SettingsServerActionResult(success = false)).membersHumanRoleSaveOutcome()
        )
        assertEquals(
            MembersHumanRoleSaveOutcome.NoPermission,
            DataResult.NoPermission(PermissionBlocker("forbidden")).membersHumanRoleSaveOutcome()
        )
        assertEquals(
            MembersHumanRoleSaveOutcome.Error,
            DataResult.Error(ScreenError("network")).membersHumanRoleSaveOutcome()
        )
    }

    @Test
    fun humanRoleSubmitRequiresFrozenRouteServerToRemainSelected() {
        assertTrue(membersHumanRoleUpdateServerMatches("server-1", "server-1"))
        assertTrue(membersHumanRoleUpdateServerMatches(" server-1 ", "server-1"))
        assertFalse(membersHumanRoleUpdateServerMatches("server-1", "server-2"))
        assertFalse(membersHumanRoleUpdateServerMatches("server-1", null))
        assertFalse(membersHumanRoleUpdateServerMatches(null, "server-1"))
        assertFalse(membersHumanRoleUpdateServerMatches("", "server-1"))
    }

    private fun editableHumanRoles(
        actorRole: String? = "owner",
        targetRole: String? = "member",
        targetMembershipStatus: String? = "active",
        accessMode: MemberDetailAccessMode = MemberDetailAccessMode.LocalFull,
        targetMemberId: String = "human-1",
        currentUserId: String? = "owner-1",
        targetServerId: String? = "server-1",
        currentServerId: String? = "server-1",
        ownerCount: Int? = 2
    ): List<MembersHumanServerRole> =
        membersEditableHumanServerRoles(
            actorRole = actorRole,
            targetRole = targetRole,
            targetMembershipStatus = targetMembershipStatus,
            accessMode = accessMode,
            targetMemberId = targetMemberId,
            currentUserId = currentUserId,
            targetServerId = targetServerId,
            currentServerId = currentServerId,
            ownerCount = ownerCount
        )

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
