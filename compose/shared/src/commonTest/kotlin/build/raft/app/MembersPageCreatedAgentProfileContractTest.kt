package build.raft.app

import build.raft.app.data.AgentSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * task #399 / Hands 3a56004f. The Members page creates agents through its own
 * `createAgentSubmit` path, not through `ChannelMembersCreateAgentDialog` used by the thread
 * member panel. #3132 and #3140 both fixed only the thread panel, so creating from the Members
 * page still dropped the user back on the list — twice — while CI stayed green.
 *
 * The diagnostic bundle proved it: zero `thread_member_profile_*` lines, `agent_create` 200,
 * and `selectedTab=members` at the create instant. Nothing exercised the Members-page branch.
 *
 * Scope: these pin only the helper contract the navigation depends on — the freshly created
 * profile must carry a usable id and kind, because `openMemberDetail` builds the detail route
 * from them. They do NOT cover the navigation itself: removing `createdAgentToOpen = profile`
 * in MembersPage would leave them green. The post-create navigation timing (stage inside the
 * submit coroutine, consume in `LaunchedEffect`) is NOT COVERED here and is accepted only on a
 * device: Members tab create and thread member panel "+" create must each land on the profile.
 */
class MembersPageCreatedAgentProfileContractTest {
    private fun created(
        id: String,
        machineId: String? = "machine-1",
        machineName: String? = "bedrock-earth"
    ) =
        AgentSummary(
            id = id, name = "qa-399", displayName = null, description = null, serverRole = null,
            status = "inactive", model = null, runtimeModelKind = null, runtimeProvider = null,
            runtime = "builtin", reasoning = null, executionMode = null,
            machineId = machineId, machineName = machineName,
            daemonVersion = null, avatarUrl = null, creatorId = null, creatorType = null, creator = null,
            createdAt = null, envVarNames = emptyList(), envVars = emptyMap(), createdAgents = emptyList(),
            activity = null, activityDetail = null, deletedAt = null
        )

    private fun submit(machineId: String?) =
        SharedCreateAgentDialogSubmit(
            name = "qa-399",
            description = null,
            machineId = machineId
        )

    @Test
    fun aCreatedAgentProfileIsAddressableForProfileNavigation() {
        // openMemberDetail builds MemberDetailIdentity(member.id, member.kind, member.serverId).
        // An empty id or a non-agent kind would make the post-create jump a no-op.
        val profile = createdAgentMemberProfile(created("agent-399"), submit(null), emptyList())

        assertEquals("agent-399", profile.id.value, "the created row must keep the server-assigned id")
        assertEquals("qa-399", profile.displayName)
        assertTrue(
            profile.id.value.isNotBlank(),
            "a blank id would make openMemberDetail hit reason=missing_member_id"
        )
    }

    @Test
    fun createdAgentProfileKeepsItsIdentityFieldsWhenTheMachineIsUnknown() {
        // The Members page creation may race the machine list; the id must survive that.
        val profile =
            createdAgentMemberProfile(
                created("agent-400", machineId = null, machineName = null),
                submit(null),
                emptyList()
            )

        assertNotNull(profile.id.value.takeIf { it.isNotBlank() })
        assertEquals("agent-400", profile.id.value)
    }

    @Test
    fun createdProfileIsDistinctPerCreation() {
        // Two creations in one session must not collapse to the same navigation target.
        val first = createdAgentMemberProfile(created("agent-a"), submit(null), emptyList())
        val second = createdAgentMemberProfile(created("agent-b"), submit(null), emptyList())

        assertTrue(
            first.id != second.id,
            "consecutive creations must target their own profile, not each other's"
        )
    }
}
