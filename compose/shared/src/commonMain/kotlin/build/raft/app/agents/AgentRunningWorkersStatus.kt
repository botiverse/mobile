package build.raft.app

import androidx.compose.runtime.Composable
import build.raft.app.data.AgentBlockedState
import build.raft.app.data.AgentDetailSummary
import build.raft.app.data.defaultNowMillis
import build.raft.app.data.normalizeAgentRunningWorkers
import build.raft.app.data.normalizedAgentRuntimeStatus
import build.raft.app.model.AgentCredentialHoldReason
import build.raft.app.model.AgentDeliveryHold
import build.raft.app.model.AgentReadiness
import build.raft.app.model.AgentStatus
import build.raft.app.resources.*
import org.jetbrains.compose.resources.StringResource

internal object AgentRunningWorkersTestTags {
    const val SidebarShort = "sidebar_agent_running_workers"
}

/**
 * Display state for an agent that is online (idle itself) while one or more of its Workers are
 * working on its current Computer (botiverse/slock #16819 `RunningWorkersDisplay` /
 * `isBusyThroughWorkers`).
 *
 * When [presenceOnly] is true (another server's agent in a joint channel), the UI renders
 * `"Working through Workers"` and never exposes a count.
 */
internal data class AgentRunningWorkersDisplay(
    val count: Int,
    val presenceOnly: Boolean = false,
)

/** Pure, resource-backed specification for [AgentRunningWorkersDisplay.localizedText]. */
internal data class AgentRunningWorkersTextSpec(
    val res: StringResource,
    val countArg: Int? = null,
)

internal fun AgentRunningWorkersDisplay.textSpec(): AgentRunningWorkersTextSpec =
    when {
        presenceOnly -> AgentRunningWorkersTextSpec(res = Res.string.agent_status_working_through_workers_presence)
        count == 1 -> AgentRunningWorkersTextSpec(res = Res.string.agent_status_working_through_worker)
        else -> AgentRunningWorkersTextSpec(res = Res.string.agent_status_working_through_workers, countArg = count)
    }

@Composable
internal fun AgentRunningWorkersDisplay.localizedText(): String =
    when {
        presenceOnly -> stringResource(Res.string.agent_status_working_through_workers_presence)
        count == 1 -> stringResource(Res.string.agent_status_working_through_worker)
        else -> stringResource(Res.string.agent_status_working_through_workers, count)
    }

/**
 * Web `displayRunningWorkers` + `isBusyThroughWorkers` (botiverse/slock #16819,
 * `docs/contracts/worker-snapshot.md` Presence):
 *
 * Returns non-null ONLY when the agent's status is [AgentStatus.Online], [runningWorkers] > 0, and
 * no higher-priority state applies:
 * - its own `thinking` / `working` (shows its own state);
 * - `error`, `offline`, `disconnected`, `idle`, or unknown status;
 * - external agent, App Bot, guessed `likelyOnline`, paused personal assistant, or held Computer (`blocked`);
 * - holding server readiness (`effectiveHoldingReadiness`);
 * - active `deliveryHold` (`agentDeliveryHoldOf`) or older daemon idle `credentialHold`.
 */
internal fun agentRunningWorkersDisplayOf(
    status: AgentStatus?,
    runningWorkers: Int?,
    runningWorkersPresenceOnly: Boolean = false,
    nowMs: Long = defaultNowMillis(),
    external: Boolean = false,
    blocked: AgentBlockedState? = null,
    likelyOnline: Boolean = false,
    paused: Boolean = false,
    appBot: Boolean = false,
    readiness: AgentReadiness? = null,
    deliveryHold: AgentDeliveryHold? = null,
    credentialHold: AgentCredentialHoldReason? = null,
    detailKind: String? = null,
): AgentRunningWorkersDisplay? {
    val count = normalizeAgentRunningWorkers(runningWorkers) ?: return null
    if (status != AgentStatus.Online) return null
    if (appBot || external || blocked != null || likelyOnline || paused) return null
    if (effectiveHoldingReadiness(readiness, blocked, external, paused, appBot) != null) return null
    if (agentDeliveryHoldOf(status, deliveryHold, nowMs, external, blocked, likelyOnline, appBot) != null) return null
    // Web displayRunningWorkers (#16819): credentialHold is already gated to `idle` at wire ingest,
    // so checking it unconditionally keeps coarse surfaces (withoutActivityDetail) consistent too.
    if (credentialHold != null) return null
    return if (runningWorkersPresenceOnly) {
        AgentRunningWorkersDisplay(count = 1, presenceOnly = true)
    } else {
        AgentRunningWorkersDisplay(count = count, presenceOnly = false)
    }
}

/** [agentRunningWorkersDisplayOf] for a live [RaftAgentStatusIndicatorState]. */
internal fun RaftAgentStatusIndicatorState?.busyThroughWorkers(
    nowMs: Long = defaultNowMillis(),
    appBot: Boolean = false,
): AgentRunningWorkersDisplay? =
    this?.let { state ->
        agentRunningWorkersDisplayOf(
            status = state.status,
            runningWorkers = state.runningWorkers,
            runningWorkersPresenceOnly = state.runningWorkersPresenceOnly,
            nowMs = nowMs,
            external = state.external,
            blocked = state.blocked,
            likelyOnline = state.likelyOnline,
            paused = state.paused,
            appBot = appBot,
            readiness = state.readiness,
            deliveryHold = state.deliveryHold,
            credentialHold = state.credentialHold,
            detailKind = state.detailKind,
        )
    }

/** Whether [this] agent is busy only through its Workers (yellow light while status is Online). */
internal fun RaftAgentStatusIndicatorState?.isBusyThroughWorkers(
    nowMs: Long = defaultNowMillis(),
    appBot: Boolean = false,
): Boolean = busyThroughWorkers(nowMs = nowMs, appBot = appBot) != null

/**
 * Busy-through-Workers state for the agent profile hero (`MembersAgentProfileStatus`): reads the
 * live indicator's `runningWorkers` whenever a live status has been observed (so a clear frame
 * wipes stale summary counts immediately), falling back to `summary.runningWorkers` only when no
 * live status exists yet.
 */
internal fun membersAgentRunningWorkersDisplay(
    indicator: RaftAgentStatusIndicatorState?,
    summary: AgentDetailSummary?,
    external: Boolean,
    blocked: AgentBlockedState?,
    paused: Boolean,
    nowMs: Long,
    appBot: Boolean = false,
    presenceOnly: Boolean = false,
    hasResolvedDeliveryHold: Boolean = false,
    resolvedDeliveryHold: AgentDeliveryHold? = null,
): AgentRunningWorkersDisplay? {
    if (appBot || external || blocked != null || paused || indicator?.likelyOnline == true) return null
    val status =
        indicator?.status
            ?: summary?.let { normalizedAgentRuntimeStatus(activity = it.activity, lifecycleStatus = it.status) }
    val count = if (indicator?.status != null) indicator.runningWorkers else summary?.runningWorkers
    val hold =
        when {
            hasResolvedDeliveryHold -> resolvedDeliveryHold
            indicator?.status != null -> indicator.deliveryHold
            else -> summary?.deliveryHold
        }
    val detailKind =
        membersAgentStatusDetailKind(
            indicator = indicator,
            summaryStatus = summary?.status,
            summaryActivity = summary?.activity,
            summaryDetailKind = summary?.activityDetailKind,
        )
    val credHold = indicator?.credentialHold ?: summary?.credentialHold
    return agentRunningWorkersDisplayOf(
        status = status,
        runningWorkers = count,
        runningWorkersPresenceOnly = indicator?.runningWorkersPresenceOnly == true || presenceOnly,
        nowMs = nowMs,
        external = external,
        blocked = blocked,
        likelyOnline = indicator?.likelyOnline == true,
        paused = paused,
        appBot = appBot,
        readiness = indicator?.readiness,
        deliveryHold = hold,
        credentialHold = credHold,
        detailKind = detailKind,
    )
}
