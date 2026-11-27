package build.raft.app

import build.raft.app.members.membersAgentReasoningEffortLabel
import build.raft.app.members.membersAgentReasoningEffortLine
import build.raft.app.members.membersReasoningEffortLabel
import build.raft.app.model.AgentReasoningSetting
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.resources.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MembersReasoningEffortLabelTest {
    @Test
    fun sharedEffortIdsMapToWebLabels() {
        // Web `REASONING_EFFORT_LABEL_ID`: every shared `ReasoningEffort` id, incl. Pi's off/minimal/max.
        assertEquals(Res.string.agent_create_reasoning_off, membersReasoningEffortLabel("off"))
        assertEquals(Res.string.agent_create_reasoning_minimal, membersReasoningEffortLabel("minimal"))
        assertEquals(Res.string.agent_create_reasoning_low, membersReasoningEffortLabel("low"))
        assertEquals(Res.string.agent_create_reasoning_medium, membersReasoningEffortLabel("medium"))
        assertEquals(Res.string.agent_create_reasoning_high, membersReasoningEffortLabel("high"))
        assertEquals(Res.string.agent_create_reasoning_extra_high, membersReasoningEffortLabel("xhigh"))
        assertEquals(Res.string.agent_create_reasoning_max, membersReasoningEffortLabel("max"))
        assertEquals(Res.string.agent_create_reasoning_ultra, membersReasoningEffortLabel("ultra"))
    }

    @Test
    fun unknownEffortIdHasNoCatalogLabel() {
        // The display label then shows the raw id as-is.
        assertNull(membersReasoningEffortLabel("turbo"))
        assertNull(membersReasoningEffortLabel(""))
    }

    /** Web `agentReasoningEffortFact` and the row's description, "Reasoning: High". */
    @Test
    fun membersAgentRowShowsTheProfilesLevel() {
        fun member(reasoning: AgentReasoningSetting?, kind: MemberKind = MemberKind.Agent, appInstallId: String? = null) =
            MemberProfile(
                id = MemberId("agent-1"),
                displayName = "Ada",
                handle = "ada",
                kind = kind,
                agentReasoning = reasoning,
                managedByAppInstallId = appInstallId,
            )

        assertEquals(
            Res.string.agent_create_reasoning_high.desc(),
            member(AgentReasoningSetting("codex", "high")).membersAgentReasoningEffortLabel(),
        )
        assertEquals(
            Res.string.members_agent_reasoning_effort.format(Res.string.member_label_default.desc()),
            member(AgentReasoningSetting("claude", null)).membersAgentReasoningEffortLine(),
        )
        // None for an App's identity, a runtime that takes no effort, a row without the facts, a person.
        val none =
            listOf(
                member(AgentReasoningSetting("claude", "high"), appInstallId = "install-1"),
                member(AgentReasoningSetting("gemini", "high")),
                member(null),
                member(AgentReasoningSetting("claude", "high"), kind = MemberKind.Human),
            )
        for (profile in none) {
            assertNull(profile.membersAgentReasoningEffortLabel(), profile.toString())
            assertNull(profile.membersAgentReasoningEffortLine(), profile.toString())
        }
    }
}
