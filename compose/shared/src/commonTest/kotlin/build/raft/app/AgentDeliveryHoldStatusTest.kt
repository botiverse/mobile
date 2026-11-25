package build.raft.app

import build.raft.app.data.AgentBlockedState
import build.raft.app.data.AgentDetailSummary
import build.raft.app.data.DeliveryHoldModelMaxLength
import build.raft.app.data.SharedServerMemberStatus
import build.raft.app.data.parseAgentDeliveryHold
import build.raft.app.model.AgentCredentialHoldReason
import build.raft.app.model.AgentDeliveryHold
import build.raft.app.model.AgentDeliveryHoldReason
import build.raft.app.model.AgentProviderLimitHoldKind
import build.raft.app.model.AgentProviderLimitWindow
import build.raft.app.model.AgentStatus
import build.raft.app.model.sameAgentDeliveryHold
import build.raft.app.resources.*
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Shared `parseDeliveryHoldActivityDiagnostic`, `sameDeliveryHold`, web `deliveryHoldOf`,
 * scoped model/window/reset parity, and activity-dot error-light parity
 * (botiverse/slock #16333 commit 88621bab7, #16629 commit d97634532).
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
    fun parseDeliveryHoldDecodesModelWindowAndResetsAtMs() {
        // botiverse/slock #16629: trims model, parses all 6 window kinds, keeps resetsAtMs.
        assertEquals(
            AgentDeliveryHold(
                reason = AgentDeliveryHoldReason.ProviderLimit,
                providerLimitDetail = AgentProviderLimitHoldKind.QuotaExhausted,
                untilMs = 500L,
                since = 10L,
                model = "Fable",
                window = AgentProviderLimitWindow.SevenDay,
                resetsAtMs = 490L,
            ),
            parse("""{"reason":"provider_limit","detail":"quota_exhausted","untilMs":500,"since":10,"model":" Fable ","window":"seven_day","resetsAtMs":490}"""),
        )
        // Every known PROVIDER_LIMIT_WINDOWS value decodes cleanly.
        val expectedWindows =
            listOf(
                "five_hour" to AgentProviderLimitWindow.FiveHour,
                "seven_day" to AgentProviderLimitWindow.SevenDay,
                "seven_day_opus" to AgentProviderLimitWindow.SevenDayOpus,
                "seven_day_sonnet" to AgentProviderLimitWindow.SevenDaySonnet,
                "seven_day_overage_included" to AgentProviderLimitWindow.SevenDayOverageIncluded,
                "overage" to AgentProviderLimitWindow.Overage,
            )
        expectedWindows.forEach { (wire, expected) ->
            assertEquals(
                expected,
                parse("""{"reason":"provider_limit","since":10,"window":"$wire"}""")?.window,
                "window $wire",
            )
        }
        // Boundary: 64-char model is kept; 65-char model, blank model, non-string model, unknown window, and invalid resetsAtMs are dropped while keeping the hold.
        val maxModel = "x".repeat(DeliveryHoldModelMaxLength)
        assertEquals(
            AgentDeliveryHold(
                reason = AgentDeliveryHoldReason.ProviderLimit,
                since = 10L,
                model = maxModel,
                resetsAtMs = 0L,
            ),
            parse("""{"reason":"provider_limit","since":10,"model":"$maxModel","resetsAtMs":0}"""),
        )
        assertEquals(
            AgentDeliveryHold(
                reason = AgentDeliveryHoldReason.ProviderLimit,
                since = 10L,
            ),
            parse("""{"reason":"provider_limit","since":10,"model":"${"x".repeat(DeliveryHoldModelMaxLength + 1)}","window":"fortnight","resetsAtMs":-1}"""),
        )
        assertEquals(
            AgentDeliveryHold(
                reason = AgentDeliveryHoldReason.ProviderLimit,
                since = 10L,
            ),
            parse("""{"reason":"provider_limit","since":10,"model":"   ","window":123,"resetsAtMs":"490"}"""),
        )
        assertEquals(
            AgentDeliveryHold(
                reason = AgentDeliveryHoldReason.ProviderLimit,
                since = 10L,
            ),
            parse("""{"reason":"provider_limit","since":10,"model":42}"""),
        )
        // credential_invalid always drops model, window, resetsAtMs, and untilMs.
        assertEquals(
            AgentDeliveryHold(
                reason = AgentDeliveryHoldReason.CredentialInvalid,
                since = 10L,
            ),
            parse("""{"reason":"credential_invalid","since":10,"untilMs":500,"model":"Fable","window":"seven_day","resetsAtMs":490}"""),
        )
    }

    @Test
    fun sameDeliveryHoldIgnoresSinceAndComparesMeaning() {
        assertTrue(sameAgentDeliveryHold(null, null))
        assertFalse(sameAgentDeliveryHold(quota, null))
        assertTrue(sameAgentDeliveryHold(quota, quota.copy(since = 9_999L)), "re-recording the same hold moves since only")
        assertFalse(sameAgentDeliveryHold(quota, quota.copy(untilMs = 10_001L)))
        assertFalse(sameAgentDeliveryHold(signIn, signIn.copy(credentialDetail = AgentCredentialHoldReason.Billing)))
        assertTrue(sameAgentDeliveryHold(signIn, signIn.copy(since = 2_000L)))

        // botiverse/slock #16629: model, window, or resetsAtMs changing is a change; since is ignored.
        val fable =
            AgentDeliveryHold(
                reason = AgentDeliveryHoldReason.ProviderLimit,
                untilMs = 5L,
                since = 1L,
                model = "Fable",
                window = AgentProviderLimitWindow.SevenDay,
                resetsAtMs = 4L,
            )
        assertTrue(sameAgentDeliveryHold(fable, fable.copy(since = 2L)))
        assertFalse(sameAgentDeliveryHold(fable, fable.copy(model = "Opus")))
        assertFalse(sameAgentDeliveryHold(fable, fable.copy(window = AgentProviderLimitWindow.FiveHour)))
        assertFalse(sameAgentDeliveryHold(fable, fable.copy(resetsAtMs = 3L)))
    }

    @Test
    fun deliveryHoldWindowLabelMapsEveryWindowWithAndWithoutModel() {
        // Without model (whole-sign-in or named-tier window)
        assertEquals(Res.string.agent_status_delivery_hold_window_five_hour, deliveryHoldWindowLabelRes(AgentProviderLimitWindow.FiveHour, ofModel = false))
        assertEquals(Res.string.agent_status_delivery_hold_window_weekly, deliveryHoldWindowLabelRes(AgentProviderLimitWindow.SevenDay, ofModel = false))
        assertEquals(Res.string.agent_status_delivery_hold_window_weekly_opus, deliveryHoldWindowLabelRes(AgentProviderLimitWindow.SevenDayOpus, ofModel = false))
        assertEquals(Res.string.agent_status_delivery_hold_window_weekly_sonnet, deliveryHoldWindowLabelRes(AgentProviderLimitWindow.SevenDaySonnet, ofModel = false))
        assertEquals(Res.string.agent_status_delivery_hold_window_weekly, deliveryHoldWindowLabelRes(AgentProviderLimitWindow.SevenDayOverageIncluded, ofModel = false))
        assertEquals(Res.string.agent_status_delivery_hold_window_extra_usage, deliveryHoldWindowLabelRes(AgentProviderLimitWindow.Overage, ofModel = false))
        assertEquals(Res.string.agent_status_delivery_hold_window_weekly, deliveryHoldWindowLabelRes(null, ofModel = false))

        // With model (subject already names the model, so seven_day_opus/sonnet simplify to weekly)
        assertEquals(Res.string.agent_status_delivery_hold_window_five_hour, deliveryHoldWindowLabelRes(AgentProviderLimitWindow.FiveHour, ofModel = true))
        assertEquals(Res.string.agent_status_delivery_hold_window_weekly, deliveryHoldWindowLabelRes(AgentProviderLimitWindow.SevenDay, ofModel = true))
        assertEquals(Res.string.agent_status_delivery_hold_window_weekly, deliveryHoldWindowLabelRes(AgentProviderLimitWindow.SevenDayOpus, ofModel = true))
        assertEquals(Res.string.agent_status_delivery_hold_window_weekly, deliveryHoldWindowLabelRes(AgentProviderLimitWindow.SevenDaySonnet, ofModel = true))
        assertEquals(Res.string.agent_status_delivery_hold_window_weekly, deliveryHoldWindowLabelRes(AgentProviderLimitWindow.SevenDayOverageIncluded, ofModel = true))
        assertEquals(Res.string.agent_status_delivery_hold_window_extra_usage, deliveryHoldWindowLabelRes(AgentProviderLimitWindow.Overage, ofModel = true))
        assertEquals(Res.string.agent_status_delivery_hold_window_weekly, deliveryHoldWindowLabelRes(null, ofModel = true))
    }

    @Test
    fun scopedProviderLimitHoldSchedulesNextChangeForResetsAtMsAndUntilMs() {
        val nowMs = 1_795_424_400_000L // 2026-11-23 09:00 UTC
        val resetsAtMs = nowMs + 60_000L
        val untilMs = nowMs + 90_000L
        val scoped =
            AgentDeliveryHold(
                reason = AgentDeliveryHoldReason.ProviderLimit,
                providerLimitDetail = AgentProviderLimitHoldKind.QuotaExhausted,
                untilMs = untilMs,
                since = nowMs - 1_000L,
                model = "Fable",
                window = AgentProviderLimitWindow.SevenDay,
                resetsAtMs = resetsAtMs,
            )
        assertTrue(isScopedProviderLimitDeliveryHold(scoped))
        assertTrue(isScopedProviderLimitDeliveryHold(scoped.copy(providerLimitDetail = null)))
        assertFalse(isScopedProviderLimitDeliveryHold(scoped.copy(model = null, window = null)))
        assertFalse(isScopedProviderLimitDeliveryHold(scoped.copy(providerLimitDetail = AgentProviderLimitHoldKind.TransientRateLimit)))
        assertFalse(isScopedProviderLimitDeliveryHold(signIn))

        assertEquals(
            60_000L,
            agentDeliveryHoldNextChangeInMs(scoped, nowMs = nowMs, localOffsetMinutes = 0),
            "scoped hold wakes first when resetsAtMs passes so display falls back to untilMs",
        )
        assertEquals(
            30_000L,
            agentDeliveryHoldNextChangeInMs(scoped, nowMs = resetsAtMs, localOffsetMinutes = 0),
            "after resetsAtMs passes, wakes at untilMs to clear the hold",
        )
        assertEquals(
            60_000L,
            agentDeliveryHoldNextChangeInMs(scoped.copy(untilMs = null), nowMs = nowMs, localOffsetMinutes = 0),
            "scoped hold without untilMs still wakes when resetsAtMs passes",
        )
        assertEquals(
            90_000L,
            agentDeliveryHoldNextChangeInMs(
                scoped.copy(providerLimitDetail = AgentProviderLimitHoldKind.TransientRateLimit),
                nowMs = nowMs,
                localOffsetMinutes = 0,
            ),
            "rate limit ignores resetsAtMs and schedules only untilMs",
        )
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
