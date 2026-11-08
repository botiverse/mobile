package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import build.raft.app.data.AgentActivityDetailKindRuntimeUnavailable
import build.raft.app.data.defaultNowMillis
import build.raft.app.model.AgentSpawnFailure
import build.raft.app.model.AgentStatus
import build.raft.app.model.RaftTimeContext
import build.raft.app.resources.*
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.components.RaftTextLink
import build.raft.designsystem.components.RaftTextLinkVariant
import build.raft.designsystem.theme.RaftTheme

/**
 * The status copy of a failed start, chosen by its typed reason (web `formatSpawnFailureDetail`,
 * botiverse/slock #13460 + #13462), for the reasons that change brought: `session_needs_reset`,
 * `model_unsupported`, `runtime_repeated_failure`, and the paused `runtime_start_timeout` with its
 * next try. Every other reason keeps the text the app showed before (the daemon's detail).
 */
internal sealed interface AgentSpawnFailureCopy {
    /** The context no longer fits the model and shrinking it didn't help (also row C8). */
    data object SessionNeedsReset : AgentSpawnFailureCopy

    /** The runtime's account or plan doesn't include the agent's model; [model] when sent. */
    data class ModelUnsupported(val model: String?) : AgentSpawnFailureCopy

    /** Kept failing after starting; paused, Raft tries again by itself ([retryAtMs] when known and ahead). */
    data class RepeatedFailurePaused(val retryAtMs: Long?) : AgentSpawnFailureCopy

    /** The start timed out and automatic wakes are paused; [detail] is the daemon's text. */
    data class StartTimeoutPaused(val detail: String, val retryAtMs: Long?) : AgentSpawnFailureCopy
}

/**
 * Web `formatSpawnFailureDetail` for the reasons above; null for every other one (and without a
 * start failure). [nowMs] decides whether `pauseRetryAtMs` is still ahead: a time that has passed
 * means the try is due, and the copy says "later" until the server reports it (web
 * `formatPauseRetryTime`).
 */
internal fun agentSpawnFailureCopy(
    spawnFailure: AgentSpawnFailure?,
    daemonDetail: String,
    nowMs: Long,
): AgentSpawnFailureCopy? {
    if (spawnFailure == null) return null
    val retryAtMs = spawnFailure.pauseRetryAtMs?.takeIf { it > nowMs }
    return when (spawnFailure.reason) {
        "session_needs_reset" -> AgentSpawnFailureCopy.SessionNeedsReset
        "model_unsupported" -> AgentSpawnFailureCopy.ModelUnsupported(spawnFailure.model)
        "runtime_repeated_failure" -> AgentSpawnFailureCopy.RepeatedFailurePaused(retryAtMs)
        // The server tries a start-timeout pause again by itself after a cooldown; unpaused, the
        // daemon's detail stands.
        "runtime_start_timeout" ->
            if (spawnFailure.autoRestartPaused) AgentSpawnFailureCopy.StartTimeoutPaused(daemonDetail, retryAtMs) else null
        else -> null
    }
}

/**
 * Web AgentStatusBadge `showChangeModel`: the start failed because the runtime cannot use the
 * agent's model, or the context no longer fits it; another model is a way out.
 */
internal fun agentSpawnFailureOffersChangeModel(spawnFailure: AgentSpawnFailure?): Boolean =
    spawnFailure?.reason == "model_unsupported" || spawnFailure?.reason == "session_needs_reset"

/** The start failure the agent page's status shows, with the daemon's detail beside it. */
internal data class AgentStatusSpawnFailure(val spawnFailure: AgentSpawnFailure, val daemonDetail: String)

/**
 * Web AgentStatusBadge `spawnFailure`: the carrier only while the status's detail kind is
 * `runtime_unavailable`. The live status decides when it has a kind; without one, the loaded
 * profile's (whose carrier is kept only with that kind) while the live status is unknown or
 * offline (a failed start leaves the agent offline).
 */
internal fun membersAgentStatusSpawnFailure(
    indicator: RaftAgentStatusIndicatorState?,
    summaryDetailKind: String?,
    summaryActivityDetail: String?,
    summarySpawnFailure: AgentSpawnFailure?,
): AgentStatusSpawnFailure? {
    val failure = summarySpawnFailure ?: return null
    val liveKind = indicator?.detailKind
    if (liveKind != null) {
        if (liveKind != AgentActivityDetailKindRuntimeUnavailable) return null
        return AgentStatusSpawnFailure(failure, indicator.label.ifBlank { summaryActivityDetail.orEmpty() })
    }
    val liveStatus = indicator?.status
    if (liveStatus != null && liveStatus != AgentStatus.Offline) return null
    if (summaryDetailKind != null && summaryDetailKind != AgentActivityDetailKindRuntimeUnavailable) return null
    return AgentStatusSpawnFailure(failure, summaryActivityDetail.orEmpty())
}

/** [copy] in this locale; the next try reads as the clock today, else the date and the clock. */
@Composable
internal fun agentSpawnFailureCopyText(copy: AgentSpawnFailureCopy, timeContext: RaftTimeContext?): String {
    fun time(atMs: Long): String =
        membersActivityOverviewClock(atMs, timeContext ?: RaftTimeContext(localOffsetMinutes = 0))
    return when (copy) {
        AgentSpawnFailureCopy.SessionNeedsReset -> stringResource(Res.string.agent_status_session_needs_reset)
        is AgentSpawnFailureCopy.ModelUnsupported ->
            copy.model?.let { stringResource(Res.string.agent_status_model_unsupported, it) }
                ?: stringResource(Res.string.agent_status_model_unsupported_default)
        is AgentSpawnFailureCopy.RepeatedFailurePaused ->
            copy.retryAtMs?.let { stringResource(Res.string.agent_status_repeated_failure_paused_at, time(it)) }
                ?: stringResource(Res.string.agent_status_repeated_failure_paused)
        is AgentSpawnFailureCopy.StartTimeoutPaused ->
            copy.retryAtMs?.let { stringResource(Res.string.agent_status_start_timeout_paused_at, copy.detail, time(it)) }
                ?: stringResource(Res.string.agent_status_start_timeout_paused, copy.detail)
    }
}

internal object AgentSpawnFailureStatusTestTags {
    const val Text = "agent-status-spawn-failure"
    const val ChangeModel = "agent-status-change-model"
}

/**
 * The clock the next try is told against: the live one's now (never the page's frozen one), with
 * the page's own UTC offset and 12h/24h choice; today is worked out from that now, so it stays
 * right past midnight.
 */
internal fun agentSpawnFailureTimeContext(payload: RaftTimeContext?, live: RaftTimeContext?, nowMs: Long): RaftTimeContext =
    (payload ?: live ?: RaftTimeContext(localOffsetMinutes = 0)).copy(nowEpochMillis = nowMs, todayEpochDay = null)

/**
 * How long until the next try's text changes by itself: at the try (it turns into "later") or at
 * the viewer's next local midnight (its date goes); null once there is no time ahead.
 */
internal fun agentSpawnFailureNextChangeInMs(retryAtMs: Long?, nowMs: Long, localOffsetMinutes: Int): Long? {
    if (retryAtMs == null || retryAtMs <= nowMs) return null
    val local = nowMs + localOffsetMinutes * 60_000L
    val nextMidnight = (local.floorDiv(MillisPerDayForRetry) + 1) * MillisPerDayForRetry - localOffsetMinutes * 60_000L
    return minOf(retryAtMs, nextMidnight) - nowMs
}

private const val MillisPerDayForRetry = 86_400_000L

/**
 * The profile's start-failure lines under the status chip (web AgentStatusBadge's chip text and
 * its Change model link): the whole copy, so the chip never cuts it, and Change model for whoever
 * may change the agent's model ([onChangeModel] null otherwise) when another model is a way out.
 * [timeContext] gives the offset and the 12h/24h choice; now comes from the live clock.
 */
@Composable
internal fun AgentSpawnFailureStatusNotice(
    status: AgentStatusSpawnFailure,
    timeContext: RaftTimeContext?,
    onChangeModel: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val live = currentRaftTimeContextProvider?.current
    // Raised to each moment the text changes by itself (the try, midnight), so the copy turns
    // over then even when the live clock ticks only once a minute, or not at all.
    var clockFloorMs by remember { mutableStateOf(Long.MIN_VALUE) }
    val now = maxOf(live?.nowEpochMillis ?: defaultNowMillis(), clockFloorMs)
    val clock = agentSpawnFailureTimeContext(timeContext, live, now)
    val copy = agentSpawnFailureCopy(status.spawnFailure, status.daemonDetail, now) ?: return
    val retryAtMs =
        when (copy) {
            is AgentSpawnFailureCopy.RepeatedFailurePaused -> copy.retryAtMs
            is AgentSpawnFailureCopy.StartTimeoutPaused -> copy.retryAtMs
            else -> null
        }
    val waitMs = agentSpawnFailureNextChangeInMs(retryAtMs, now, clock.localOffsetMinutes)
    LaunchedEffect(retryAtMs, waitMs) {
        if (waitMs == null) return@LaunchedEffect
        delay(waitMs)
        clockFloorMs = maxOf(clockFloorMs, now + waitMs)
    }
    val text = agentSpawnFailureCopyText(copy, clock)
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RaftText(
            text = text,
            style = typography.footnote,
            color = colors.foregroundMuted,
            modifier = Modifier
                .weight(1f, fill = false)
                .testTag(AgentSpawnFailureStatusTestTags.Text)
                .semantics { contentDescription = text },
        )
        if (onChangeModel != null && agentSpawnFailureOffersChangeModel(status.spawnFailure)) {
            RaftTextLink(
                text = stringResource(Res.string.agent_status_change_model),
                onClick = onChangeModel,
                variant = RaftTextLinkVariant.Strong,
                style = typography.footnote,
                modifier = Modifier.testTag(AgentSpawnFailureStatusTestTags.ChangeModel),
            )
        }
    }
}
