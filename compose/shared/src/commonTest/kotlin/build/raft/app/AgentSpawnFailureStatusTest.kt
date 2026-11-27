package build.raft.app

import build.raft.app.data.AgentDetailSnapshot
import build.raft.app.data.AgentDetailSummary
import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.members.membersAgentCanOpenModelSettings
import build.raft.app.members.membersRuntimeEditorCanOpen
import build.raft.app.members.membersRuntimeEditorOpenRequestActs
import build.raft.app.model.AgentSpawnFailure
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
}
