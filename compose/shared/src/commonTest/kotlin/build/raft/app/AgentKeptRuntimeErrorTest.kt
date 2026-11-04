package build.raft.app

import build.raft.app.data.AgentDetailSnapshot
import build.raft.app.data.AgentDetailSummary
import build.raft.app.data.SharedServerMemberStatus
import build.raft.app.model.AgentSpawnFailure
import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Web classifyRuntimeError.ts and AgentDetailPanel `activityErrorText`: the agent page's error notice
 * for a live error and for the one the server kept (not signed in, sign-in failed, the runtime
 * missing, any other message).
 */
class AgentKeptRuntimeErrorTest {
    @Test
    fun messagePatternsMatchTheWebInItsOrder() {
        assertEquals(AgentRuntimeErrorKind.NotLoggedIn, agentClassifyRuntimeError("Claude Code is not logged in on this machine."))
        assertEquals(AgentRuntimeErrorKind.NotInstalled, agentClassifyRuntimeError("Codex CLI is NOT INSTALLED on this computer."))
        // "is not logged in" wins over "not installed", as the web tries it first.
        assertEquals(AgentRuntimeErrorKind.NotLoggedIn, agentClassifyRuntimeError("Runtime is not logged in and not installed"))
        listOf(
            "Authentication failed for this key",
            "auth error: token expired",
            "Authorization invalid",
            "Invalid API key provided",
            "401 Unauthorized",
        ).forEach { assertEquals(AgentRuntimeErrorKind.AuthFailed, agentClassifyRuntimeError(it), it) }
        assertNull(agentClassifyRuntimeError("The process exited with code 137"))
        // The tool-arguments error comes only from the typed diagnostic, never from the text.
        assertNull(agentClassifyRuntimeError("tool-call arguments in an invalid format"))
    }

    @Test
    fun theTypedDiagnosticNeedsAClassAndAReason() {
        assertEquals(AgentRuntimeErrorKind.AuthFailed, agentTypedRuntimeErrorKind("AuthError", "something_else"))
        assertEquals(AgentRuntimeErrorKind.AuthFailed, agentTypedRuntimeErrorKind("RuntimeError", "auth_failed"))
        assertEquals(AgentRuntimeErrorKind.ToolArgsInvalid, agentTypedRuntimeErrorKind("ToolArgumentParseError", "x"))
        assertEquals(AgentRuntimeErrorKind.ToolArgsInvalid, agentTypedRuntimeErrorKind("RuntimeError", "model_tool_args_invalid"))
        assertNull(agentTypedRuntimeErrorKind("AuthError", null))
        assertNull(agentTypedRuntimeErrorKind(null, "auth_failed"))
        assertNull(agentTypedRuntimeErrorKind("RuntimeError", "crashed"))
    }

    @Test
    fun theTypedDiagnosticWinsOverTheText() {
        assertEquals(
            AgentRuntimeErrorDisplay.Known(AgentRuntimeErrorKind.AuthFailed),
            agentRuntimeErrorDisplay("Codex CLI is not installed", "AuthError", "auth_failed", null, "Codex"),
        )
    }

    @Test
    fun aMissingRuntimeSaysHowTheLookupEnded() {
        assertEquals(
            AgentRuntimeErrorDisplay.Lookup(AgentRuntimeLookupDisplay.ConfirmedMissing, "Codex"),
            agentRuntimeErrorDisplay("Codex CLI is not installed", null, null, AgentSpawnFailure("runtime_not_found", "not_on_path"), "Codex"),
        )
        assertEquals(
            AgentRuntimeErrorDisplay.Lookup(AgentRuntimeLookupDisplay.Unconfirmed, "Codex"),
            agentRuntimeErrorDisplay("Codex CLI is not installed", null, null, null, "Codex"),
        )
        // No runtime to name: the plain "not installed" line.
        assertEquals(
            AgentRuntimeErrorDisplay.Known(AgentRuntimeErrorKind.NotInstalled),
            agentRuntimeErrorDisplay("Codex CLI is not installed", null, null, null, null),
        )
    }

    @Test
    fun anyOtherMessageKeepsTheServerText() {
        assertEquals(
            AgentRuntimeErrorDisplay.Raw("Runtime crashed"),
            agentRuntimeErrorDisplay("""{"message":"Runtime crashed"}""", null, null, null, "Codex"),
        )
        assertEquals(AgentRuntimeErrorDisplay.Raw(""), agentRuntimeErrorDisplay("  ", null, null, null, "Codex"))
    }

    @Test
    fun theKeptErrorShowsWhileTheAgentIsNotRunning() {
        val summary =
            AgentDetailSummary(
                id = "agent-1",
                name = "ruby",
                runtime = "claude",
                activityDetail = "live detail",
                lastRuntimeErrorMessage = "Claude Code is not logged in on this machine. Please log in locally.",
            )
        val detail = detail(summary)
        val expected = AgentRuntimeErrorDisplay.Known(AgentRuntimeErrorKind.NotLoggedIn)
        assertEquals(expected, agent.membersAgentRuntimeErrorDisplay(detail, null))
        assertEquals(expected, agent.membersAgentRuntimeErrorDisplay(detail, state(AgentStatus.Offline)))
        assertEquals(expected, agent.membersAgentRuntimeErrorDisplay(detail, state(AgentStatus.Disconnected)))
        // Running again: the kept error goes (the profile is not re-read when it starts).
        assertNull(agent.membersAgentRuntimeErrorDisplay(detail, state(AgentStatus.Working)))
        // In error: the live error, classified the same way.
        assertEquals(AgentRuntimeErrorDisplay.Raw("live detail"), agent.membersAgentRuntimeErrorDisplay(detail, state(AgentStatus.Error)))
    }

    @Test
    fun aKeptSignInFailureAndAnyOtherKeptMessageShowToo() {
        val failedSignIn = detail(AgentDetailSummary(id = "agent-1", name = "ruby", lastRuntimeErrorMessage = "Invalid API key provided"))
        assertEquals(
            AgentRuntimeErrorDisplay.Known(AgentRuntimeErrorKind.AuthFailed),
            agent.membersAgentRuntimeErrorDisplay(failedSignIn, state(AgentStatus.Offline)),
        )
        val typed =
            detail(
                AgentDetailSummary(
                    id = "agent-1",
                    name = "ruby",
                    lastRuntimeErrorMessage = "Codex rejected the tool call",
                    lastRuntimeErrorClass = "ToolArgumentParseError",
                    lastRuntimeErrorReason = "model_tool_args_invalid",
                )
            )
        assertEquals(
            AgentRuntimeErrorDisplay.Known(AgentRuntimeErrorKind.ToolArgsInvalid),
            agent.membersAgentRuntimeErrorDisplay(typed, state(AgentStatus.Offline)),
        )
        val other = detail(AgentDetailSummary(id = "agent-1", name = "ruby", lastRuntimeErrorMessage = "The process exited with code 137"))
        assertEquals(
            AgentRuntimeErrorDisplay.Raw("The process exited with code 137"),
            agent.membersAgentRuntimeErrorDisplay(other, state(AgentStatus.Offline)),
        )
        // Nothing kept: no notice.
        assertNull(agent.membersAgentRuntimeErrorDisplay(detail(AgentDetailSummary(id = "agent-1", name = "ruby")), state(AgentStatus.Offline)))
    }

    @Test
    fun copyInfoCarriesTheKeptErrorAndItsTypedDiagnostic() {
        val typed =
            detail(
                AgentDetailSummary(
                    id = "agent-1",
                    name = "ruby",
                    lastRuntimeErrorMessage = "Codex rejected the tool call",
                    lastRuntimeErrorClass = "ToolArgumentParseError",
                    lastRuntimeErrorReason = "model_tool_args_invalid",
                )
            )
        val info = buildMembersAgentDiagnostics(agent, typed, state(AgentStatus.Offline))
        assertTrue(info.contains("lastRuntimeError=Codex rejected the tool call"))
        assertTrue(info.contains("class=ToolArgumentParseError reason=model_tool_args_invalid"))
        assertFalse(buildMembersAgentDiagnostics(agent, detail(AgentDetailSummary(id = "agent-1", name = "ruby")), null).contains("lastRuntimeError="))
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

    private fun state(status: AgentStatus) =
        SharedServerMemberStatus(status = status, presence = status.presence, runtime = "claude", external = false)
            .toRaftAgentStatusIndicatorState()
}
