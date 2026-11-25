package build.raft.app.data.realtime

import build.raft.app.model.AgentCredentialHoldReason
import build.raft.app.model.AgentDeliveryHold
import build.raft.app.model.AgentDeliveryHoldReason
import build.raft.app.model.AgentProviderLimitHoldKind
import build.raft.app.model.AgentProviderLimitWindow
import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberPresenceUpdated
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * `agent:activity` for an agent the daemon holds for an unusable sign-in (botiverse/slock #13276,
 * staging 853c6998b: agentOrchestrator emits `credentialHold` beside an idle status; web
 * credentialHoldProjection.test.ts and agentActivityCompatibility.test.ts).
 */
class RealtimeAgentCredentialHoldTest {
    private val heldDetail = "Codex CLI is not logged in on this computer. (Reported by another agent on the same sign-in.)"

    private fun frame(activity: String = "online", detailKind: String = "idle", carrier: String? = """{ "reason": "user_reauth" }"""): String {
        val hold = if (carrier == null) "" else ""","credentialHold": $carrier"""
        return """
        {
          "agentId": "agent-held",
          "activity": "$activity",
          "detail": "$heldDetail",
          "detailKind": "$detailKind",
          "timestamp": 1782367200000,
          "serverSeq": 7$hold
        }
        """.trimIndent()
    }

    private fun map(payload: String): MemberPresenceUpdated =
        assertIs<MemberPresenceUpdated>(RealtimeEventMapper().map(RealtimeSocketEvents.AgentActivity, payload))

    @Test
    fun theIdleStatusCarriesTheHeldReason() {
        val held = map(frame())
        assertEquals(AgentStatus.Online, held.agentStatus)
        assertEquals("idle", held.detailKind)
        assertEquals(AgentCredentialHoldReason.UserReauth, held.credentialHold)
        assertEquals(AgentCredentialHoldReason.UserModelAccess, map(frame(carrier = """{ "reason": "user_model_access", "model": "not-echoed" }""")).credentialHold)
        assertEquals(AgentCredentialHoldReason.Billing, map(frame(carrier = """{ "reason": "billing" }""")).credentialHold)
    }

    @Test
    fun onlyAnIdleFrameWithAKnownReasonCarriesIt() {
        assertNull(map(frame(activity = "working", detailKind = "starting")).credentialHold, "only an idle frame carries it")
        assertNull(map(frame(carrier = """{ "reason": "quota" }""")).credentialHold, "an unknown reason is no reason")
        assertNull(map(frame(carrier = null)).credentialHold, "an older daemon's held notice: none")
    }

    @Test
    fun deliveryHoldRidesAnyActivityFrameAndClearsWhenAbsent() {
        val workingQuota =
            map(
                """
                {
                  "agentId": "agent-held",
                  "activity": "working",
                  "detail": "Running tests",
                  "detailKind": "running_command",
                  "timestamp": 1782367200000,
                  "serverSeq": 8,
                  "deliveryHold": { "reason": "provider_limit", "detail": "quota_exhausted", "untilMs": 1782370800000, "since": 1782367000000 }
                }
                """.trimIndent()
            )
        assertEquals(AgentStatus.Working, workingQuota.agentStatus)
        assertEquals(
            AgentDeliveryHold(
                reason = AgentDeliveryHoldReason.ProviderLimit,
                providerLimitDetail = AgentProviderLimitHoldKind.QuotaExhausted,
                untilMs = 1782370800000L,
                since = 1782367000000L,
            ),
            workingQuota.deliveryHold,
        )

        val credHold =
            map(
                """
                {
                  "agentId": "agent-held",
                  "activity": "online",
                  "detail": "$heldDetail",
                  "detailKind": "idle",
                  "timestamp": 1782367200000,
                  "serverSeq": 9,
                  "credentialHold": { "reason": "user_reauth" },
                  "deliveryHold": { "reason": "credential_invalid", "detail": "user_reauth", "since": 1782367000000 }
                }
                """.trimIndent()
            )
        assertEquals(AgentCredentialHoldReason.UserReauth, credHold.credentialHold)
        assertEquals(
            AgentDeliveryHold(
                reason = AgentDeliveryHoldReason.CredentialInvalid,
                credentialDetail = AgentCredentialHoldReason.UserReauth,
                since = 1782367000000L,
            ),
            credHold.deliveryHold,
        )

        val released =
            map(
                """
                {
                  "agentId": "agent-held",
                  "activity": "working",
                  "detail": "Running tests",
                  "detailKind": "running_command",
                  "timestamp": 1782367205000,
                  "serverSeq": 10
                }
                """.trimIndent()
            )
        assertNull(released.deliveryHold, "frame without deliveryHold clears it")
    }

    @Test
    fun coarseActivityStateCarriesDeliveryHoldAndClearsWhenAbsent() {
        val heldCoarse =
            assertIs<MemberPresenceUpdated>(
                RealtimeEventMapper().map(
                    RealtimeSocketEvents.AgentActivityState,
                    """
                    {
                      "agentId": "agent-held",
                      "state": "busy",
                      "activity": "working",
                      "timestamp": 1782367200000,
                      "serverSeq": 11,
                      "deliveryHold": { "reason": "provider_limit", "detail": "transient_rate_limit", "untilMs": 1782367320000, "since": 1782367200000 }
                    }
                    """.trimIndent()
                )
            )
        assertEquals(true, heldCoarse.coarseState)
        assertEquals(AgentStatus.Working, heldCoarse.agentStatus)
        assertEquals(
            AgentDeliveryHold(
                reason = AgentDeliveryHoldReason.ProviderLimit,
                providerLimitDetail = AgentProviderLimitHoldKind.TransientRateLimit,
                untilMs = 1782367320000L,
                since = 1782367200000L,
            ),
            heldCoarse.deliveryHold,
        )

        val clearedCoarse =
            assertIs<MemberPresenceUpdated>(
                RealtimeEventMapper().map(
                    RealtimeSocketEvents.AgentActivityState,
                    """
                    {
                      "agentId": "agent-held",
                      "state": "online",
                      "activity": "online",
                      "timestamp": 1782367325000,
                      "serverSeq": 12
                    }
                    """.trimIndent()
                )
            )
        assertEquals(true, clearedCoarse.coarseState)
        assertNull(clearedCoarse.deliveryHold)
    }

    @Test
    fun activityAndCoarseStatePreserveScopedDeliveryHoldModelWindowAndResetsAtMs() {
        val scopedWire =
            """{ "reason": "provider_limit", "detail": "quota_exhausted", "untilMs": 1782370830000, "since": 1782367000000, "model": "Fable", "window": "seven_day", "resetsAtMs": 1782370800000 }"""
        val expected =
            AgentDeliveryHold(
                reason = AgentDeliveryHoldReason.ProviderLimit,
                providerLimitDetail = AgentProviderLimitHoldKind.QuotaExhausted,
                untilMs = 1782370830000L,
                since = 1782367000000L,
                model = "Fable",
                window = AgentProviderLimitWindow.SevenDay,
                resetsAtMs = 1782370800000L,
            )
        val detailed =
            map(
                """
                {
                  "agentId": "agent-held",
                  "activity": "working",
                  "detail": "Running tests",
                  "detailKind": "running_command",
                  "timestamp": 1782367200000,
                  "serverSeq": 13,
                  "deliveryHold": $scopedWire
                }
                """.trimIndent()
            )
        assertEquals(expected, detailed.deliveryHold)

        val coarse =
            assertIs<MemberPresenceUpdated>(
                RealtimeEventMapper().map(
                    RealtimeSocketEvents.AgentActivityState,
                    """
                    {
                      "agentId": "agent-held",
                      "state": "busy",
                      "activity": "working",
                      "timestamp": 1782367200000,
                      "serverSeq": 14,
                      "deliveryHold": $scopedWire
                    }
                    """.trimIndent()
                )
            )
        assertEquals(expected, coarse.deliveryHold)
    }

    @Test
    fun activityAndCoarseStateParseRunningWorkersAndRejectInvalidValues() {
        val detailed =
            map(
                """
                {
                  "agentId": "agent-workers",
                  "activity": "online",
                  "detail": "",
                  "detailKind": "idle",
                  "timestamp": 1782367200000,
                  "serverSeq": 20,
                  "runningWorkers": 2
                }
                """.trimIndent()
            )
        assertEquals(AgentStatus.Online, detailed.agentStatus)
        assertEquals(2, detailed.runningWorkers)

        val coarse =
            assertIs<MemberPresenceUpdated>(
                RealtimeEventMapper().map(
                    RealtimeSocketEvents.AgentActivityState,
                    """
                    {
                      "agentId": "agent-workers",
                      "state": "online",
                      "activity": "online",
                      "timestamp": 1782367201000,
                      "serverSeq": 21,
                      "runningWorkers": 1
                    }
                    """.trimIndent()
                )
            )
        assertEquals(true, coarse.coarseState)
        assertEquals(1, coarse.runningWorkers)

        val cleared =
            assertIs<MemberPresenceUpdated>(
                RealtimeEventMapper().map(
                    RealtimeSocketEvents.AgentActivityState,
                    """
                    {
                      "agentId": "agent-workers",
                      "state": "online",
                      "activity": "online",
                      "timestamp": 1782367202000,
                      "serverSeq": 22
                    }
                    """.trimIndent()
                )
            )
        assertNull(cleared.runningWorkers)

        val fractional =
            assertIs<MemberPresenceUpdated>(
                RealtimeEventMapper().map(
                    RealtimeSocketEvents.AgentActivityState,
                    """
                    {
                      "agentId": "agent-workers",
                      "state": "online",
                      "activity": "online",
                      "timestamp": 1782367203000,
                      "serverSeq": 23,
                      "runningWorkers": 1.5
                    }
                    """.trimIndent()
                )
            )
        assertNull(fractional.runningWorkers)
    }
}
