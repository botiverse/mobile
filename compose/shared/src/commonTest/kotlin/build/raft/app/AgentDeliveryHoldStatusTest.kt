package build.raft.app

import build.raft.app.data.AgentBlockedState
import build.raft.app.data.AgentDetailSummary
import build.raft.app.data.SharedServerMemberStatus
import build.raft.app.data.parseAgentDeliveryHold
import build.raft.app.model.AgentCredentialHoldReason
import build.raft.app.model.AgentDeliveryHold
import build.raft.app.model.AgentDeliveryHoldReason
import build.raft.app.model.AgentProviderLimitHoldKind
import build.raft.app.model.AgentStatus
import build.raft.app.model.sameAgentDeliveryHold
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Shared `parseDeliveryHoldActivityDiagnostic`, `sameDeliveryHold`, web `deliveryHoldOf`, and
 * activity-dot error-light parity (botiverse/slock #16333, commit 88621bab7).
 */
class AgentDeliveryHoldStatusTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun parse(raw: String): AgentDeliveryHold? = parseAgentDeliveryHold(json.parseToJsonElement(raw))

    private val quota =
        AgentDeliveryHold(
            reason = AgentDeliveryHoldReason.ProviderLimit,
            providerLimitDetail = AgentProviderLimitHoldKind.QuotaExhausted,
            untilMs = 10_000L,
            since = 1_000L,
        )
    private val signIn =
        AgentDeliveryHold(
            reason = AgentDeliveryHoldReason.CredentialInvalid,
            credentialDetail = AgentCredentialHoldReason.UserReauth,
            since = 1_000L,
        )

    @Test
    fun parseDeliveryHoldMatchesSharedContract() {
        // Keeps credential_invalid with detail and drops untilMs.
        assertEquals(
            AgentDeliveryHold(
                reason = AgentDeliveryHoldReason.CredentialInvalid,
                credentialDetail = AgentCredentialHoldReason.UserReauth,
                providerLimitDetail = null,
                untilMs = null,
                since = 10L,
            ),
            parse("""{"reason":"credential_invalid","detail":"user_reauth","since":10,"untilMs":99}"""),
        )
        // Keeps provider_limit with kind and reported end.
        assertEquals(
            AgentDeliveryHold(
                reason = AgentDeliveryHoldReason.ProviderLimit,
                providerLimitDetail = AgentProviderLimitHoldKind.QuotaExhausted,
                untilMs = 500L,
                since = 10L,
            ),
            parse("""{"reason":"provider_limit","detail":"quota_exhausted","untilMs":500,"since":10}"""),
        )
        assertEquals(
            AgentDeliveryHold(
                reason = AgentDeliveryHoldReason.ProviderLimit,
                providerLimitDetail = AgentProviderLimitHoldKind.TransientRateLimit,
                untilMs = 65_000L,
                since = 1_000L,
            ),
            parse("""{"reason":"provider_limit","detail":"transient_rate_limit","untilMs":65000,"since":1000}"""),
        )
        // Keeps the hold while dropping an unknown sub-reason from a newer daemon.
        assertEquals(
            AgentDeliveryHold(
                reason = AgentDeliveryHoldReason.ProviderLimit,
                providerLimitDetail = null,
                untilMs = null,
                since = 10L,
            ),
            parse("""{"reason":"provider_limit","detail":"weekly_cap","since":10}"""),
        )
        // Drops negative untilMs while keeping the hold.
        assertEquals(
            AgentDeliveryHold(
                reason = AgentDeliveryHoldReason.ProviderLimit,
                providerLimitDetail = AgentProviderLimitHoldKind.QuotaExhausted,
                untilMs = null,
                since = 10L,
            ),
            parse("""{"reason":"provider_limit","detail":"quota_exhausted","untilMs":-5,"since":10}"""),
        )
        // Invalid shapes or missing/negative since or unknown reason -> null.
        assertNull(parseAgentDeliveryHold(null))
        assertNull(parse("null"))
        assertNull(parse(""""provider_limit""""))
        assertNull(parse("[]"))
        assertNull(parse("""{"reason":"provider_limit"}"""))
        assertNull(parse("""{"reason":"provider_limit","since":-1}"""))
        assertNull(parse("""{"reason":"provider_limit","since":"10"}"""))
        assertNull(parse("""{"reason":"maintenance","since":10}"""))
    }

    @Test
    fun sameDeliveryHoldIgnoresSinceAndComparesMeaning() {
        assertTrue(sameAgentDeliveryHold(null, null))
        assertFalse(sameAgentDeliveryHold(quota, null))
        assertTrue(sameAgentDeliveryHold(quota, quota.copy(since = 9_999L)), "re-recording the same hold moves since only")
        assertFalse(sameAgentDeliveryHold(quota, quota.copy(untilMs = 10_001L)))
        assertFalse(sameAgentDeliveryHold(signIn, signIn.copy(credentialDetail = AgentCredentialHoldReason.Billing)))
        assertTrue(sameAgentDeliveryHold(signIn, signIn.copy(since = 2_000L)))
    }

    @Test
    fun deliveryHoldOfAppliesWhileWorkingOrOnlineAndNotOfflineOrExpired() {
        assertEquals(signIn, agentDeliveryHoldOf(AgentStatus.Working, signIn, nowMs = 5_000L))
        assertEquals(signIn, agentDeliveryHoldOf(AgentStatus.Thinking, signIn, nowMs = 5_000L))
        assertEquals(signIn, agentDeliveryHoldOf(AgentStatus.Idle, signIn, nowMs = 5_000L))
        assertEquals(quota, agentDeliveryHoldOf(AgentStatus.Online, quota, nowMs = 9_999L))
        assertNull(agentDeliveryHoldOf(AgentStatus.Online, quota, nowMs = 10_000L), "wait ended at untilMs")
        assertNull(agentDeliveryHoldOf(AgentStatus.Offline, signIn, nowMs = 5_000L))
        assertNull(agentDeliveryHoldOf(AgentStatus.Disconnected, signIn, nowMs = 5_000L))
        assertNull(agentDeliveryHoldOf(null, signIn, nowMs = 5_000L))
        assertNull(agentDeliveryHoldOf(AgentStatus.Working, signIn, nowMs = 5_000L, external = true))
        assertNull(
            agentDeliveryHoldOf(
                AgentStatus.Working,
                signIn,
                nowMs = 5_000L,
                blocked = AgentBlockedState.ComputerAccessRevoked,
            )
        )
        assertNull(agentDeliveryHoldOf(AgentStatus.Online, signIn, nowMs = 5_000L, likelyOnline = true))
        assertNull(agentDeliveryHoldOf(AgentStatus.Online, signIn, nowMs = 5_000L, appBot = true))
        assertNull(agentDeliveryHoldOf(AgentStatus.Online, null, nowMs = 5_000L), "older daemon: none")
    }

    @Test
    fun membersAgentDeliveryHoldPrefersLiveIndicatorClearOverStaleSummary() {
        val summaryWithHold =
            AgentDetailSummary(
                id = "agent-1",
                name = "held",
                runtime = "claude",
                activity = "working",
                deliveryHold = quota,
            )
        // Before any live indicator exists, summary's hold is shown.
        assertEquals(
            quota,
            membersAgentDeliveryHold(
                indicator = null,
                summary = summaryWithHold,
                external = false,
                blocked = null,
                nowMs = 5_000L,
            ),
        )
        // Once a live frame arrives without deliveryHold, it clears the stale summary hold.
        val liveReleased =
            SharedServerMemberStatus(
                status = AgentStatus.Working,
                presence = AgentStatus.Working.presence,
                external = false,
                deliveryHold = null,
            ).toRaftAgentStatusIndicatorState(nowEpochMillis = 5_000L)
        assertNull(
            membersAgentDeliveryHold(
                indicator = liveReleased,
                summary = summaryWithHold,
                external = false,
                blocked = null,
                nowMs = 5_000L,
            ),
            "live release frame must clear stale REST summary hold",
        )
        // A live working frame carrying a hold shows it even if summary had none.
        val liveHeld =
            SharedServerMemberStatus(
                status = AgentStatus.Working,
                presence = AgentStatus.Working.presence,
                external = false,
                deliveryHold = signIn,
            ).toRaftAgentStatusIndicatorState(nowEpochMillis = 5_000L)
        assertEquals(
            signIn,
            membersAgentDeliveryHold(
                indicator = liveHeld,
                summary = summaryWithHold.copy(deliveryHold = null),
                external = false,
                blocked = null,
                nowMs = 5_000L,
            ),
        )
    }

    @Test
    fun indicatorAndWidgetDotsPaintErrorOrangeWhileHeldAndRestoreWhenExpired() {
        val workingHeld =
            SharedServerMemberStatus(
                status = AgentStatus.Working,
                presence = AgentStatus.Working.presence,
                runtime = "claude",
                model = "claude-sonnet-4-6",
                external = false,
                deliveryHold = quota,
            ).toRaftAgentStatusIndicatorState(nowEpochMillis = 5_000L)
        assertEquals(RaftColor.AccentOrange, workingHeld.color, "held working agent paints error light")
        assertEquals(AgentStatus.Working, workingHeld.status, "real activity status stays Working")
        assertEquals(quota, workingHeld.withoutActivityDetail().deliveryHold, "coarse strip keeps deliveryHold")

        val workingExpired =
            SharedServerMemberStatus(
                status = AgentStatus.Working,
                presence = AgentStatus.Working.presence,
                external = false,
                deliveryHold = quota,
            ).toRaftAgentStatusIndicatorState(nowEpochMillis = 10_000L)
        assertEquals(RaftColor.BrandYellow, workingExpired.color, "expired hold restores busy yellow")

        val offlineHeld =
            SharedServerMemberStatus(
                status = AgentStatus.Offline,
                presence = AgentStatus.Offline.presence,
                external = false,
                deliveryHold = signIn,
            ).toRaftAgentStatusIndicatorState(nowEpochMillis = 5_000L)
        assertEquals(RaftColor.PresenceOffline, offlineHeld.color, "offline agent stays offline gray")

        assertEquals(
            RaftAgentStatusDotKind.Orange,
            raftAgentStatusDotKind("working", external = false, nowEpochMillis = 5_000L, deliveryHold = quota),
        )
        assertEquals(
            RaftAgentStatusDotKind.Yellow,
            raftAgentStatusDotKind("working", external = false, nowEpochMillis = 10_000L, deliveryHold = quota),
        )
        assertEquals(
            RaftAgentStatusDotKind.Gray,
            raftAgentStatusDotKind("offline", external = false, nowEpochMillis = 5_000L, deliveryHold = signIn),
        )
    }
}
