package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.app.data.AgentBlockedState
import build.raft.app.data.AgentDetailSummary
import build.raft.app.data.defaultNowMillis
import build.raft.app.data.normalizedAgentRuntimeStatus
import build.raft.app.model.AgentCredentialHoldReason
import build.raft.app.model.AgentDeliveryHold
import build.raft.app.model.AgentDeliveryHoldReason
import build.raft.app.model.AgentProviderLimitHoldKind
import build.raft.app.model.AgentStatus
import build.raft.app.model.RaftTimeContext
import build.raft.app.resources.*
import kotlinx.coroutines.delay

internal object AgentDeliveryHoldTestTags {
    const val DmHeaderStatus = "agent_dm_status_delivery_hold"
    const val MemberRowStatus = "member_row_delivery_hold"
    const val SidebarShort = "sidebar_agent_delivery_hold"
}

/**
 * Web `deliveryHoldOf` (`packages/web/src/utils/activity.ts`, botiverse/slock #16333):
 * whether the daemon holds the agent's deliveries now, so it can't respond whatever its activity
 * (online, working, thinking, idle, error).
 *
 * Returns null when:
 * - there is no `deliveryHold` (not held, or an older daemon — its idle `credentialHold` keeps its own path);
 * - the agent is offline/disconnected/unknown (offline already says it can't respond);
 * - the agent is blocked by its Computer, only guessed online (`likelyOnline`), external, or an App Bot;
 * - a `provider_limit` wait's `untilMs` has already passed (`untilMs <= nowMs`).
 */
internal fun agentDeliveryHoldOf(
    status: AgentStatus?,
    deliveryHold: AgentDeliveryHold?,
    nowMs: Long,
    external: Boolean = false,
    blocked: AgentBlockedState? = null,
    likelyOnline: Boolean = false,
    appBot: Boolean = false,
): AgentDeliveryHold? {
    if (deliveryHold == null || appBot || external || blocked != null || likelyOnline) return null
    if (status == null || status == AgentStatus.Offline || status == AgentStatus.Disconnected) return null
    val holdUntilMs = deliveryHold.untilMs
    if (
        deliveryHold.reason == AgentDeliveryHoldReason.ProviderLimit &&
        holdUntilMs != null &&
        holdUntilMs <= nowMs
    ) {
        return null
    }
    return deliveryHold
}

/** [agentDeliveryHoldOf] for a live [RaftAgentStatusIndicatorState]. */
internal fun RaftAgentStatusIndicatorState?.activeDeliveryHold(
    nowMs: Long,
    appBot: Boolean = false,
): AgentDeliveryHold? =
    this?.let { state ->
        agentDeliveryHoldOf(
            status = state.status,
            deliveryHold = state.deliveryHold,
            nowMs = nowMs,
            external = state.external,
            blocked = state.blocked,
            likelyOnline = state.likelyOnline,
            appBot = appBot,
        )
    }

/**
 * Active delivery hold for the agent profile (`MembersAgentProfileStatus`): reads the live
 * indicator's `deliveryHold` whenever a live status has been observed (so a release frame
 * clears any stale summary hold immediately), falling back to `summary.deliveryHold` only when
 * no live status exists yet.
 */
internal fun membersAgentDeliveryHold(
    indicator: RaftAgentStatusIndicatorState?,
    summary: AgentDetailSummary?,
    external: Boolean,
    blocked: AgentBlockedState?,
    nowMs: Long,
    appBot: Boolean = false,
): AgentDeliveryHold? {
    if (appBot || external || blocked != null || indicator?.likelyOnline == true) return null
    val status =
        indicator?.status
            ?: summary?.let { normalizedAgentRuntimeStatus(activity = it.activity, lifecycleStatus = it.status) }
    val hold = if (indicator?.status != null) indicator.deliveryHold else summary?.deliveryHold
    return agentDeliveryHoldOf(status = status, deliveryHold = hold, nowMs = nowMs)
}

/**
 * How long until a provider-limit hold's displayed state changes by itself: at `untilMs` (the wait
 * ends and the hold clears) or at the viewer's next local midnight (the date drops off the clock);
 * null for `credential_invalid` or when no future `untilMs` is set.
 */
internal fun agentDeliveryHoldNextChangeInMs(
    hold: AgentDeliveryHold?,
    nowMs: Long,
    localOffsetMinutes: Int,
): Long? {
    if (hold == null || hold.reason != AgentDeliveryHoldReason.ProviderLimit) return null
    return agentSpawnFailureNextChangeInMs(hold.untilMs, nowMs, localOffsetMinutes)
}

/** What the status surfaces show while the daemon holds the agent's deliveries (web `AgentDeliveryHoldView`). */
internal data class AgentDeliveryHoldView(
    val hold: AgentDeliveryHold,
    /** Full sentence: "Can't respond: Claude Code usage limit reached, until 14:05". */
    val text: String,
    /** Compact chip / sidebar label: "Can't respond". */
    val shortText: String,
)

/**
 * Web `formatDeliveryHoldText` (`packages/web/src/utils/activity.ts`, botiverse/slock #16333):
 * `"Can't respond: <reason>"`, naming the agent's runtime (and model for a plan without it) and,
 * for a provider limit, when the wait ends.
 */
@Composable
internal fun formatAgentDeliveryHoldText(
    hold: AgentDeliveryHold,
    runtime: String?,
    model: String?,
    timeContext: RaftTimeContext?,
    nowMs: Long,
): String {
    val runtimeName = observeAgentRuntimeDisplayName(runtime?.trim().orEmpty())
    val reason =
        when (hold.reason) {
            AgentDeliveryHoldReason.CredentialInvalid ->
                when (val detail = hold.credentialDetail) {
                    AgentCredentialHoldReason.UserModelAccess,
                    AgentCredentialHoldReason.Billing ->
                        agentCredentialInvalidText(detail, runtime, model)
                    AgentCredentialHoldReason.UserReauth ->
                        stringResource(Res.string.agent_status_delivery_hold_sign_in, runtimeName)
                    null ->
                        stringResource(Res.string.agent_status_delivery_hold_credential, runtimeName)
                }
            AgentDeliveryHoldReason.ProviderLimit -> {
                val until =
                    hold.untilMs?.takeIf { it > nowMs }?.let { atMs ->
                        val clock =
                            (timeContext ?: RaftTimeContext(localOffsetMinutes = 0))
                                .copy(nowEpochMillis = nowMs, todayEpochDay = null)
                        membersActivityOverviewClock(atMs, clock)
                    }
                when (hold.providerLimitDetail) {
                    AgentProviderLimitHoldKind.TransientRateLimit ->
                        if (until != null) {
                            stringResource(Res.string.agent_status_delivery_hold_rate_limit_until, runtimeName, until)
                        } else {
                            stringResource(Res.string.agent_status_delivery_hold_rate_limit, runtimeName)
                        }
                    AgentProviderLimitHoldKind.QuotaExhausted,
                    null ->
                        if (until != null) {
                            stringResource(Res.string.agent_status_delivery_hold_usage_limit_until, runtimeName, until)
                        } else {
                            stringResource(Res.string.agent_status_delivery_hold_usage_limit, runtimeName)
                        }
                }
            }
        }
    return stringResource(Res.string.agent_status_cant_respond, reason)
}

/** Web `useAgentDeliveryHold` for any status surface driven by [RaftAgentStatusIndicatorState]. */
@Composable
internal fun rememberAgentDeliveryHoldView(
    state: RaftAgentStatusIndicatorState?,
    timeContext: RaftTimeContext? = null,
    fallbackRuntime: String? = null,
    fallbackModel: String? = null,
    appBot: Boolean = false,
): AgentDeliveryHoldView? {
    val rawHold = state?.deliveryHold
    if (rawHold == null || appBot) return null
    val live = currentRaftTimeContextProvider?.current
    val pinnedNow = timeContext?.nowEpochMillis
    var clockFloorMs by remember(rawHold) { mutableStateOf(Long.MIN_VALUE) }
    val now = pinnedNow ?: maxOf(live?.nowEpochMillis ?: defaultNowMillis(), clockFloorMs)
    val clock = agentSpawnFailureTimeContext(timeContext, live, now)
    val active = state.activeDeliveryHold(nowMs = now, appBot = appBot) ?: return null
    val waitMs = if (pinnedNow == null) agentDeliveryHoldNextChangeInMs(active, now, clock.localOffsetMinutes) else null
    LaunchedEffect(active, waitMs) {
        if (waitMs == null || waitMs <= 0L) return@LaunchedEffect
        delay(waitMs)
        clockFloorMs = maxOf(clockFloorMs, now + waitMs)
    }
    return AgentDeliveryHoldView(
        hold = active,
        text =
            formatAgentDeliveryHoldText(
                hold = active,
                runtime = state.runtime ?: fallbackRuntime,
                model = state.model ?: fallbackModel,
                timeContext = clock,
                nowMs = now,
            ),
        shortText = stringResource(Res.string.agent_status_cant_respond_short),
    )
}

/** Web `useAgentDeliveryHold` for `MembersAgentProfileStatus` (live indicator + profile summary). */
@Composable
internal fun rememberMembersAgentDeliveryHoldView(
    indicator: RaftAgentStatusIndicatorState?,
    summary: AgentDetailSummary?,
    external: Boolean,
    blocked: AgentBlockedState?,
    timeContext: RaftTimeContext? = null,
    appBot: Boolean = false,
): AgentDeliveryHoldView? {
    val rawHold = if (indicator?.status != null) indicator.deliveryHold else summary?.deliveryHold
    if (rawHold == null || appBot || external || blocked != null || indicator?.likelyOnline == true) return null
    val live = currentRaftTimeContextProvider?.current
    val pinnedNow = timeContext?.nowEpochMillis
    var clockFloorMs by remember(rawHold) { mutableStateOf(Long.MIN_VALUE) }
    val now = pinnedNow ?: maxOf(live?.nowEpochMillis ?: defaultNowMillis(), clockFloorMs)
    val clock = agentSpawnFailureTimeContext(timeContext, live, now)
    val active =
        membersAgentDeliveryHold(
            indicator = indicator,
            summary = summary,
            external = external,
            blocked = blocked,
            nowMs = now,
            appBot = appBot,
        ) ?: return null
    val waitMs = if (pinnedNow == null) agentDeliveryHoldNextChangeInMs(active, now, clock.localOffsetMinutes) else null
    LaunchedEffect(active, waitMs) {
        if (waitMs == null || waitMs <= 0L) return@LaunchedEffect
        delay(waitMs)
        clockFloorMs = maxOf(clockFloorMs, now + waitMs)
    }
    return AgentDeliveryHoldView(
        hold = active,
        text =
            formatAgentDeliveryHoldText(
                hold = active,
                runtime = summary?.runtime ?: indicator?.runtime,
                model = summary?.model ?: indicator?.model,
                timeContext = clock,
                nowMs = now,
            ),
        shortText = stringResource(Res.string.agent_status_cant_respond_short),
    )
}
