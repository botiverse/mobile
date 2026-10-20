package build.raft.app

import build.raft.app.base.BasePager
import build.raft.app.compat.pager.Page
import build.raft.app.data.SharedComputersLoadStatus

/**
 * Agent-detail profile states that the production `compose_member_detail` route
 * must not learn to parse (Route / PageData MVI contract rule): the seed is built
 * here by typed construction and rendered through the shared detail content.
 * `state` only selects which fixture to show.
 *
 *  - `no-membership` -> screens.members.agent-detail.profile.no-membership:
 *    fixture agent agent-no-membership has no server membership row, so the wire
 *    omits serverRole (role chip hidden on both ends, task #261).
 *  - `loading-state` -> screens.members.agent-detail.profile.loading-state:
 *    fixture agent agent-product-ux while the computers store is still in flight
 *    (Computer row must not render until loaded, task #259).
 *
 * Paired with packages/visual-testing/shared/fixtureData.json and
 * packages/web/visual-testing/VisualTestingCases.tsx AGENT_DETAIL_CASES in slock.
 */
@Page("compose_members_agent_detail_state_visual", supportInLocal = true)
internal class ComposeMembersAgentDetailStateVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        val state = pageData.params.optString("state")
        val variant =
            when (state) {
                "no-membership" -> membersAgentDetailNoMembershipVisualVariant()
                "loading-state" -> null
                else -> error("unknown agent-detail visual state: $state")
            }
        val machineStatusRow: ((String?) -> MembersMachineStatusRow)? =
            if (state == "loading-state") {
                { machineId ->
                    membersMachineStatusRow(
                        machineId = machineId,
                        machines = emptyList(),
                        loadStatus = SharedComputersLoadStatus.InFlight,
                        hasSnapshot = false
                    )
                }
            } else {
                null
            }
        val payload =
            RaftMemberDetailPayload(
                instanceId = "visual-members-agent-profile-$state",
                initialAgentTab = "profile",
                localOffsetMinutes = 480,
                todayEpochDay = 20623,
                nowEpochMillis = 1781872200000,
                timeFormat = VisualFixtureData.Humans.Owner.TIME_FORMAT,
                agentPanelTabOrder = listOf("profile", "activity", "chat", "reminders", "workspace", "integrations")
            )
        setContent {
            // Same timestamp scope the production member-detail pager installs.
            RaftTimestampDisplayScope(state = retainedRaftTimestampDisplayState(pagerId)) {
                MembersAgentDetailVisualVariantContent(
                    payload = payload,
                    variant = variant,
                    pageVisibleTick = raftPageVisibleResumeTick,
                    machineStatusRow = machineStatusRow
                )
            }
        }
    }
}

private fun membersAgentDetailNoMembershipVisualVariant(): MembersAgentDetailVisualVariant {
    val agent = VisualFixtureData.Agents.NoMembership
    val machine = VisualFixtureData.Machines.Primary
    return MembersAgentDetailVisualVariant(
        id = agent.ID,
        name = agent.NAME,
        displayName = agent.DISPLAY_NAME,
        avatarLabel = agent.AVATAR_LABEL,
        description = agent.DESCRIPTION,
        machineId = machine.ID,
        machineName = machine.NAME,
        daemonVersion = machine.DAEMON_VERSION,
        serverRole = null
    )
}
