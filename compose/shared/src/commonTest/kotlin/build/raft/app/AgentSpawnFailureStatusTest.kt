package build.raft.app

import build.raft.app.data.AgentDetailSnapshot
import build.raft.app.data.AgentDetailSummary
import build.raft.app.data.SharedServerMemberStatus
import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.AgentSpawnFailure
import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberPresenceUpdated
import build.raft.app.model.MemberProfile
import build.raft.app.model.RaftTimeContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * botiverse/slock #13460 (web `formatSpawnFailureDetail`, AgentStatusBadge Change model) and
 * #13435 (web `classifyTypedRuntimeError` billingExhausted, AgentRuntimeErrorNotice's billing
 * banner): the new carrier fields, the status copy rules, and when the billing banner shows.
 */
class AgentSpawnFailureStatusTest {
    private val now = 1_782_367_200_000L

    private fun frame(spawnFailure: String, detailKind: String = "runtime_unavailable"): MemberPresenceUpdated =
        assertIs(
            RealtimeEventMapper().map(
                RealtimeSocketEvents.AgentActivity,
                """
                {
                  "agentId": "agent-1",
                  "activity": "offline",
                  "activityKind": "offline",
                  "detail": "Runtime failed to start",
                  "detailKind": "$detailKind",
                  "timestamp": $now,
                  "serverSeq": 7,
                  "spawnFailure": $spawnFailure
                }
                """.trimIndent(),
            ),
        )

    @Test
    fun theCarrierReadsModelPauseAndNextTry() {
        assertEquals(
            AgentSpawnFailure(reason = "runtime_repeated_failure", autoRestartPaused = true, pauseRetryAtMs = now + 60_000),
            frame("""{"reason":"runtime_repeated_failure","autoRestartPaused":true,"pauseRetryAtMs":${now + 60_000}}""").spawnFailure,
        )
        assertEquals(
            AgentSpawnFailure(reason = "model_unsupported", model = "gpt-5.5-pro"),
            frame("""{"reason":"model_unsupported","model":" gpt-5.5-pro "}""").spawnFailure,
        )
        // An older server: no time; a non-positive one is no time either; a fractional one still reads.
        assertNull(frame("""{"reason":"runtime_start_timeout","autoRestartPaused":true}""").spawnFailure?.pauseRetryAtMs)
        assertNull(frame("""{"reason":"runtime_start_timeout","pauseRetryAtMs":0}""").spawnFailure?.pauseRetryAtMs)
        assertEquals(now, frame("""{"reason":"runtime_start_timeout","pauseRetryAtMs":$now.4}""").spawnFailure?.pauseRetryAtMs)
        // Only with its kind.
        assertNull(frame("""{"reason":"model_unsupported"}""", detailKind = "stopped").spawnFailure)
    }

    @Test
    fun theNewReasonsGetTheirOwnCopy() {
        assertEquals(
            AgentSpawnFailureCopy.SessionNeedsReset,
            agentSpawnFailureCopy(AgentSpawnFailure("session_needs_reset", autoRestartPaused = true), "daemon text", now),
        )
        assertEquals(
            AgentSpawnFailureCopy.ModelUnsupported("o3"),
            agentSpawnFailureCopy(AgentSpawnFailure("model_unsupported", model = "o3"), "daemon text", now),
        )
        assertEquals(
            AgentSpawnFailureCopy.ModelUnsupported(null),
            agentSpawnFailureCopy(AgentSpawnFailure("model_unsupported"), "daemon text", now),
        )
        // Repeated failure: paused or not, the copy says Raft tries again; with the time while it is ahead.
        assertEquals(
            AgentSpawnFailureCopy.RepeatedFailurePaused(now + 1),
            agentSpawnFailureCopy(AgentSpawnFailure("runtime_repeated_failure", pauseRetryAtMs = now + 1), "", now),
        )
        assertEquals(
            AgentSpawnFailureCopy.RepeatedFailurePaused(null),
            agentSpawnFailureCopy(AgentSpawnFailure("runtime_repeated_failure", pauseRetryAtMs = now), "", now),
        )
        assertEquals(
            AgentSpawnFailureCopy.RepeatedFailurePaused(null),
            agentSpawnFailureCopy(AgentSpawnFailure("runtime_repeated_failure"), "", now),
        )
    }

    @Test
    fun aStartTimeoutGetsTheCopyOnlyWhilePaused() {
        assertEquals(
            AgentSpawnFailureCopy.StartTimeoutPaused("Timed out", now + 5),
            agentSpawnFailureCopy(AgentSpawnFailure("runtime_start_timeout", autoRestartPaused = true, pauseRetryAtMs = now + 5), "Timed out", now),
        )
        assertEquals(
            AgentSpawnFailureCopy.StartTimeoutPaused("Timed out", null),
            agentSpawnFailureCopy(AgentSpawnFailure("runtime_start_timeout", autoRestartPaused = true, pauseRetryAtMs = now - 5), "Timed out", now),
        )
        assertNull(agentSpawnFailureCopy(AgentSpawnFailure("runtime_start_timeout"), "Timed out", now))
        // Reasons #13460 did not change keep the text shown before.
        assertNull(agentSpawnFailureCopy(AgentSpawnFailure("runtime_not_found", autoRestartPaused = true), "x", now))
        assertNull(agentSpawnFailureCopy(null, "x", now))
    }

    @Test
    fun changeModelOnlyWhereAnotherModelIsAWayOut() {
        assertTrue(agentSpawnFailureOffersChangeModel(AgentSpawnFailure("model_unsupported")))
        assertTrue(agentSpawnFailureOffersChangeModel(AgentSpawnFailure("session_needs_reset")))
        assertFalse(agentSpawnFailureOffersChangeModel(AgentSpawnFailure("runtime_repeated_failure")))
        assertFalse(agentSpawnFailureOffersChangeModel(AgentSpawnFailure("runtime_start_timeout", autoRestartPaused = true)))
        assertFalse(agentSpawnFailureOffersChangeModel(null))
        // Web `canOpenModelSettings`: whoever may manage the agent, never for an external one.
        assertTrue(membersAgentCanOpenModelSettings(canManage = true, isExternalAgent = false))
        assertFalse(membersAgentCanOpenModelSettings(canManage = true, isExternalAgent = true))
        assertFalse(membersAgentCanOpenModelSettings(canManage = false, isExternalAgent = false))
    }

    @Test
    fun theStatusReadsTheFailureOnlyWithItsKind() {
        val failure = AgentSpawnFailure("model_unsupported", model = "o3")
        // No live status yet: the loaded profile's.
        assertEquals(
            AgentStatusSpawnFailure(failure, "kept detail"),
            membersAgentStatusSpawnFailure(null, "runtime_unavailable", "kept detail", failure),
        )
        // Live and offline without a kind: still the profile's.
        assertEquals(
            AgentStatusSpawnFailure(failure, "kept detail"),
            membersAgentStatusSpawnFailure(state(AgentStatus.Offline), "runtime_unavailable", "kept detail", failure),
        )
        // Running again: gone.
        assertNull(membersAgentStatusSpawnFailure(state(AgentStatus.Working), "runtime_unavailable", "kept detail", failure))
        // A live frame of another kind: gone; of this kind: the live label is the daemon's detail.
        assertNull(membersAgentStatusSpawnFailure(state(AgentStatus.Offline).copy(detailKind = "stopped"), null, null, failure))
        assertEquals(
            AgentStatusSpawnFailure(failure, "Live detail"),
            membersAgentStatusSpawnFailure(
                state(AgentStatus.Offline).copy(detailKind = "runtime_unavailable", label = "Live detail"),
                "runtime_unavailable",
                "kept detail",
                failure,
            ),
        )
        assertNull(membersAgentStatusSpawnFailure(null, "runtime_unavailable", "x", null))
    }

    @Test
    fun aTypedBillingErrorIsTheBillingBannerWhateverTheText() {
        assertEquals(AgentRuntimeErrorKind.BillingExhausted, agentTypedRuntimeErrorKind("BillingError", "something"))
        assertEquals(AgentRuntimeErrorKind.BillingExhausted, agentTypedRuntimeErrorKind("RuntimeError", "billing_exhausted"))
        // Typed carrier only: never the 402 text.
        assertNull(agentClassifyRuntimeError("HTTP 402: insufficient balance"))
        assertNull(agentTypedRuntimeErrorKind("BillingError", null))
        assertTrue(
            agentRuntimeErrorDisplay("402 Payment Required", "BillingError", "billing_exhausted", null, "Claude").isBillingExhausted,
        )
        assertFalse(agentRuntimeErrorDisplay("Invalid API key", "AuthError", "auth_failed", null, "Claude").isBillingExhausted)
    }

    @Test
    fun aKeptBillingErrorStaysWhileTheAgentStartsAgain() {
        val billing =
            detail(
                AgentDetailSummary(
                    id = "agent-1",
                    name = "ruby",
                    lastRuntimeErrorMessage = "402 Payment Required: insufficient balance",
                    lastRuntimeErrorClass = "BillingError",
                    lastRuntimeErrorReason = "billing_exhausted",
                )
            )
        val expected = AgentRuntimeErrorDisplay.Known(AgentRuntimeErrorKind.BillingExhausted)
        assertEquals(expected, agent.membersAgentRuntimeErrorDisplay(billing, state(AgentStatus.Offline)))
        // Start / Starting does not clear it (web: the banner follows the stored error).
        assertEquals(expected, agent.membersAgentRuntimeErrorDisplay(billing, state(AgentStatus.Working)))
        assertEquals(expected, agent.membersAgentRuntimeErrorDisplay(billing, state(AgentStatus.Online)))
        // Any other kept error still goes while the agent runs.
        val other = detail(AgentDetailSummary(id = "agent-1", name = "ruby", lastRuntimeErrorMessage = "Invalid API key provided"))
        assertNull(agent.membersAgentRuntimeErrorDisplay(other, state(AgentStatus.Working)))
        // A blank kept message is still a billing banner (catalog copy).
        val blank = detail(AgentDetailSummary(id = "agent-1", name = "ruby", lastRuntimeErrorClass = "BillingError", lastRuntimeErrorReason = "billing_exhausted"))
        assertEquals(expected, agent.membersAgentRuntimeErrorDisplay(blank, state(AgentStatus.Offline)))
    }

    @Test
    fun theBillingBannerHasNoViewLogs() {
        val billing =
            membersAgentRuntimeErrorBannerState(MemberKind.Agent, MembersAgentDetailTab.Profile, "Model service balance too low", billingExhausted = true)
        assertEquals(MembersAgentRuntimeErrorBannerState("Model service balance too low", showViewLogs = false, billingExhausted = true), billing)
        val other = membersAgentRuntimeErrorBannerState(MemberKind.Agent, MembersAgentDetailTab.Profile, "Error: x")
        assertEquals(MembersAgentRuntimeErrorBannerState("Error: x", showViewLogs = true, billingExhausted = false), other)
    }

    @Test
    fun theNextTryIsToldAgainstTheLiveNowWithThePagesOffsetAndFormat() {
        val page = RaftTimeContext(localOffsetMinutes = 480, todayEpochDay = 1, nowEpochMillis = 1L, timeFormat = "12h")
        val live = RaftTimeContext(localOffsetMinutes = 0, todayEpochDay = 20_000, nowEpochMillis = now - 1)
        assertEquals(
            RaftTimeContext(localOffsetMinutes = 480, todayEpochDay = null, nowEpochMillis = now, timeFormat = "12h"),
            agentSpawnFailureTimeContext(page, live, now),
        )
        assertEquals(now, agentSpawnFailureTimeContext(null, live, now).nowEpochMillis)
        assertEquals(0, agentSpawnFailureTimeContext(null, null, now).localOffsetMinutes)
    }

    @Test
    fun theTextChangesByItselfAtTheTryOrAtMidnight() {
        // 06:00 UTC: a try at 06:30 comes before midnight.
        assertEquals(30 * 60_000L, agentSpawnFailureNextChangeInMs(now + 30 * 60_000L, now, 0))
        // A try tomorrow: midnight first (18 h away in UTC; 10 h at UTC+8, where it is 14:00).
        assertEquals(18 * 3_600_000L, agentSpawnFailureNextChangeInMs(now + 30 * 3_600_000L, now, 0))
        assertEquals(10 * 3_600_000L, agentSpawnFailureNextChangeInMs(now + 30 * 3_600_000L, now, 480))
        assertNull(agentSpawnFailureNextChangeInMs(now, now, 0))
        assertNull(agentSpawnFailureNextChangeInMs(null, now, 0))
    }

    @Test
    fun aShownBillingErrorIsReReadOnceTheAgentRunsAgain() {
        listOf(AgentStatus.Online, AgentStatus.Working, AgentStatus.Thinking, AgentStatus.Idle).forEach {
            assertTrue(membersBillingErrorRereadDue(billingErrorShown = true, liveStatus = it), it.name)
            assertFalse(membersBillingErrorRereadDue(billingErrorShown = false, liveStatus = it), it.name)
        }
        listOf(AgentStatus.Offline, AgentStatus.Disconnected, AgentStatus.Error, null).forEach {
            assertFalse(membersBillingErrorRereadDue(billingErrorShown = true, liveStatus = it), it?.name)
        }
    }

    @Test
    fun anOpenEditorRequestWaitsUntilTheEditorCanOpen() {
        assertFalse(membersRuntimeEditorCanOpen(canEdit = true, hasAuthority = true, hasConfig = true, runtimeEditorReady = false, hasFormV2Access = true))
        assertFalse(membersRuntimeEditorCanOpen(canEdit = true, hasAuthority = true, hasConfig = false, runtimeEditorReady = true, hasFormV2Access = true))
        assertFalse(membersRuntimeEditorCanOpen(canEdit = true, hasAuthority = true, hasConfig = true, runtimeEditorReady = true, hasFormV2Access = false))
        assertTrue(membersRuntimeEditorCanOpen(canEdit = true, hasAuthority = true, hasConfig = true, runtimeEditorReady = true, hasFormV2Access = true))
        // Not ready: the request stays (no act, so not consumed); ready: it acts.
        assertFalse(membersRuntimeEditorOpenRequestActs(requested = true, canOpen = false))
        assertTrue(membersRuntimeEditorOpenRequestActs(requested = true, canOpen = true))
        assertFalse(membersRuntimeEditorOpenRequestActs(requested = false, canOpen = true))
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
