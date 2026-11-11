package build.raft.app

import build.raft.app.data.AgentDetailSnapshot
import build.raft.app.data.AgentDetailSummary
import build.raft.app.data.AgentRuntimeErrorCode
import build.raft.app.data.SharedServerMemberStatus
import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.resources.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Web #13985 (botiverse/slock agentRuntimeErrorByCode.behavior.test.tsx, AgentDetailPanel
 * `runtimeErrorCode`, utils/activity.ts "error"): the agent page's notice and the status words read
 * the error's code. A member reads the code's sentence (or the generic fallback without one), a
 * manager the managers' sentence with the scrubbed detail under it; a status reads the code's title.
 */
class AgentRuntimeErrorCodeDisplayTest {
    private val sentence =
        "The agent's model provider usage limit has been reached. Wait for the limit to reset, or ask the agent's owner to switch it to another model or account."
    private val scrubbed = "You've hit your usage limit for [REDACTED_EMAIL] at [REDACTED_PATH]"

    @Test
    fun aMemberReadsTheCodesSentenceNotTheText() {
        val member =
            detail(AgentDetailSummary(id = "agent-1", name = "ruby", activityDetail = sentence, activityErrorCode = AgentRuntimeErrorCode.UsageLimit))
        assertEquals(
            AgentRuntimeErrorDisplay.Coded(AgentRuntimeErrorCode.UsageLimit, forManager = false, detail = ""),
            agent.membersAgentRuntimeErrorDisplay(member, state(AgentStatus.Error), canViewDetails = false),
        )
    }

    @Test
    fun aManagerReadsTheManagersSentenceWithTheDetail() {
        val manager =
            detail(AgentDetailSummary(id = "agent-1", name = "ruby", activityDetail = scrubbed, activityErrorCode = AgentRuntimeErrorCode.UsageLimit))
        assertEquals(
            AgentRuntimeErrorDisplay.Coded(AgentRuntimeErrorCode.UsageLimit, forManager = true, detail = scrubbed),
            agent.membersAgentRuntimeErrorDisplay(manager, state(AgentStatus.Error), canViewDetails = true),
        )
        // The live frame's code wins over the loaded profile's.
        assertEquals(
            AgentRuntimeErrorDisplay.Coded(AgentRuntimeErrorCode.AuthRequired, forManager = true, detail = scrubbed),
            agent.membersAgentRuntimeErrorDisplay(manager, state(AgentStatus.Error, AgentRuntimeErrorCode.AuthRequired), canViewDetails = true),
        )
    }

    @Test
    fun anOfflineAgentReadsTheKeptErrorsCode() {
        val kept =
            detail(
                AgentDetailSummary(
                    id = "agent-1",
                    name = "ruby",
                    lastRuntimeErrorMessage = sentence,
                    lastRuntimeErrorCode = AgentRuntimeErrorCode.InputTooLarge,
                )
            )
        assertEquals(
            AgentRuntimeErrorDisplay.Coded(AgentRuntimeErrorCode.InputTooLarge, forManager = false, detail = ""),
            agent.membersAgentRuntimeErrorDisplay(kept, state(AgentStatus.Offline), canViewDetails = false),
        )
    }

    @Test
    fun withoutACodeAManagerKeepsTheDetailAndAMemberGetsNoPatterns() {
        val uncoded = detail(AgentDetailSummary(id = "agent-1", name = "ruby", activityDetail = "Invalid API key provided"))
        assertEquals(
            AgentRuntimeErrorDisplay.Known(AgentRuntimeErrorKind.AuthFailed),
            agent.membersAgentRuntimeErrorDisplay(uncoded, state(AgentStatus.Error), canViewDetails = true),
        )
        // Text patterns only for a viewer the server sends the text to.
        assertEquals(
            AgentRuntimeErrorDisplay.Raw("Invalid API key provided"),
            agent.membersAgentRuntimeErrorDisplay(uncoded, state(AgentStatus.Error), canViewDetails = false),
        )
    }

    @Test
    fun aTypedBillingErrorKeepsTheBillingBanner() {
        val billing =
            detail(
                AgentDetailSummary(
                    id = "agent-1",
                    name = "ruby",
                    lastRuntimeErrorMessage = "402 Payment Required",
                    lastRuntimeErrorClass = "BillingError",
                    lastRuntimeErrorReason = "billing_exhausted",
                    lastRuntimeErrorCode = AgentRuntimeErrorCode.Billing,
                )
            )
        assertEquals(
            AgentRuntimeErrorDisplay.Known(AgentRuntimeErrorKind.BillingExhausted),
            agent.membersAgentRuntimeErrorDisplay(billing, state(AgentStatus.Offline), canViewDetails = true),
        )
    }

    @Test
    fun anErrorStatusReadsTheCodesTitleOrTheCatalogFallback() {
        assertEquals(Res.string.agent_runtime_error_code_usage_limit_title, agentErrorStatusCopy(AgentRuntimeErrorCode.UsageLimit, sentence))
        assertEquals(Res.string.agent_runtime_error_code_network_timeout_title, agentErrorStatusCopy(AgentRuntimeErrorCode.NetworkTimeout, null))
        // The server's generic member line renders as ours.
        assertEquals(Res.string.member_state_agent_error, agentErrorStatusCopy(null, "Agent encountered an error"))
        // A manager's uncoded detail stays the detail.
        assertNull(agentErrorStatusCopy(null, "Runtime crashed"))
    }

    @Test
    fun theCodeReachesTheStatusIndicatorAndSurvivesTheCoarseView() {
        val indicator = state(AgentStatus.Error, AgentRuntimeErrorCode.Billing)
        assertEquals(AgentRuntimeErrorCode.Billing, indicator.errorCode)
        assertEquals(AgentRuntimeErrorCode.Billing, indicator.withoutActivityDetail().errorCode)
    }

    private val agent = MemberProfile(id = MemberId("agent-1"), displayName = "Ruby", handle = "ruby", kind = MemberKind.Agent)

    private fun detail(summary: AgentDetailSummary) =
        AgentDetailSnapshot(
            agentId = MemberId("agent-1"),
            summary = summary,
            directMessages = emptyList(),
            reminders = emptyList(),
            activity = emptyList(),
        )

    private fun state(status: AgentStatus, errorCode: AgentRuntimeErrorCode? = null) =
        SharedServerMemberStatus(status = status, presence = status.presence, runtime = "claude", external = false, errorCode = errorCode)
            .toRaftAgentStatusIndicatorState()
}
