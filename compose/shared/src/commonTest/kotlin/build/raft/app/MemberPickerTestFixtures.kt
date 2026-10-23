package build.raft.app

import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence

/**
 * Roster shared by the member picker's ranking, selection and screenshot tests. No Working or
 * Thinking agent: their pulsing status dot never lets a Robolectric frame loop go idle.
 */
internal object MemberPickerTestFixtures {
    fun agent(id: String, displayName: String, handle: String, status: AgentStatus = AgentStatus.Idle, description: String? = null) =
        MemberProfile(
            id = MemberId(id),
            displayName = displayName,
            handle = handle,
            kind = MemberKind.Agent,
            description = description,
            presence = if (status == AgentStatus.Offline) Presence.Offline else Presence.Active,
            agentStatus = status,
        )

    fun human(id: String, displayName: String, handle: String, serverName: String? = null) =
        MemberProfile(id = MemberId(id), displayName = displayName, handle = handle, kind = MemberKind.Human, serverName = serverName)

    val roster: List<MemberProfile> =
        listOf(
            agent("agent-cindy", "Cindy", "cindy", AgentStatus.Online, description = "Release notes and QA"),
            agent("agent-visual-qa", "Visual QA", "visual-qa", AgentStatus.Offline),
            agent("agent-lagoon", "Lagoon", "lagoon", AgentStatus.Idle, description = "Mobile DRI"),
            human("user-artin", "Artin", "artin"),
            human("user-designer", "Designer", "designer"),
            human("user-bo", "Bo Rivera", "bo-r", serverName = "Partner Co"),
        )
}
