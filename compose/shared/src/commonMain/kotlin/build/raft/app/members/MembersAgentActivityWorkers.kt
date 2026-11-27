package build.raft.app

import build.raft.app.resources.*
import build.raft.app.thread.icon
import build.raft.app.thread.key
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.compat.ui.NestedScrollMode
import build.raft.app.compat.ui.nestedScroll
import build.raft.app.components.RaftStorePullToRefreshBox
import build.raft.app.data.AGENT_WORKER_HUMAN_WAITING_AFTER_MS
import build.raft.app.data.AGENT_WORKER_QUIET_AFTER_MS
import build.raft.app.data.AgentActivitySubagentGroup
import build.raft.app.data.AgentActivitySubagentState
import build.raft.app.data.AgentRecentWorkerRow
import build.raft.app.data.AgentWorkerDoing
import build.raft.app.data.AgentWorkerEndReason
import build.raft.app.data.AgentWorkerOpenTimeline
import build.raft.app.data.AgentWorkerRow
import build.raft.app.data.AgentWorkerState
import build.raft.app.data.AgentWorkerStopFeedback
import build.raft.app.data.AgentWorkerTimelineIntent
import build.raft.app.data.AgentWorkerTimelineState
import build.raft.app.data.AgentWorkersIntent
import build.raft.app.data.AgentWorkersLoadStatus
import build.raft.app.data.AgentWorkersState
import build.raft.app.data.ScreenError
import build.raft.app.data.defaultNowMillis
import build.raft.app.data.recentEndedAgentWorkers
import build.raft.app.model.MemberId
import build.raft.app.model.RaftTimeContext
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftEmptyState
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowChevron
import build.raft.designsystem.components.RaftListRowGroup
import build.raft.designsystem.components.RaftPresence
import build.raft.designsystem.components.RaftStatusDot
import build.raft.designsystem.components.RaftTooltip
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.flow.StateFlow

// Worker v1: Agent Detail › Activity › Workers panel
// (botiverse/slock `docs/contracts/worker-snapshot.md`, `docs/contracts/worker-events.md`,
// `AgentWorkerRows.tsx`, `AgentWorkerTimeline.tsx`, `AgentActivityLog.tsx`).
// Live Workers come first, then "Recently ended" (last 7 days), then runtime sub-agents
// tagged "runtime sub-agent".

internal const val MembersAgentWorkersListTag = "members_agent_workers_list"
internal const val MembersAgentWorkersUnknownTag = "members_agent_workers_unknown"
internal const val MembersAgentWorkersStaleTag = "members_agent_workers_stale"
internal const val MembersAgentWorkersRecentGroupTag = "members_agent_workers_recent_group"
internal const val MembersAgentWorkerMarkTag = "members_agent_worker_mark"
internal const val MembersAgentWorkerStopDialogTag = "members_agent_worker_stop_dialog"
internal const val MembersAgentWorkerStopConfirmTag = "members_agent_worker_stop_confirm"

internal fun membersAgentWorkerRowTag(worker: String): String = "members_agent_worker_row_$worker"

internal fun membersAgentWorkerStopTag(worker: String): String = "members_agent_worker_stop_$worker"

internal fun membersAgentRecentWorkerRowTag(launchId: String): String = "members_agent_recent_worker_row_$launchId"

/**
 * Binding for the Activity › Workers view when `worker_v1` is ON for a Computer agent.
 * Null when `worker_v1` is OFF or for external agents, keeping the legacy Subagents view intact.
 */
internal class MembersAgentWorkersBinding(
    val state: StateFlow<AgentWorkersState>,
    /** Parent agent's handle (without `@`): Stop confirms "{object} goes back to @{parent}." */
    val parentHandle: String,
    /** Viewer has runtime control authority for this live Computer agent. */
    val canStop: Boolean,
    /** The agent's Computer is currently offline (disables Stop with reason). */
    val computerOffline: Boolean,
    val onIntent: (AgentWorkersIntent) -> Unit,
    /** Worker Timeline Detail store state; null disables in-panel timeline navigation unless [onOpenTimeline] handles it. */
    val timelineState: StateFlow<AgentWorkerTimelineState>? = null,
    val onTimelineIntent: ((AgentWorkerTimelineIntent) -> Unit)? = null,
    /** Opens a message referenced by a timeline `"View message"` link (`GET /messages/context/:id`). */
    val onOpenTimelineMessage: ((messageId: String) -> Unit)? = null,
    /** Optional override hook when a live or recently-ended Worker row opens its timeline. */
    val onOpenTimeline: ((AgentWorkerOpenTimeline) -> Unit)? = null,
)

/** Web `liveMark`: a live Worker row's status mark; null when none or while the snapshot is stale. */
internal sealed interface MembersAgentWorkerMarkKind {
    data class Stalled(val silentMs: Long) : MembersAgentWorkerMarkKind {
        override val warning: Boolean get() = true
    }
    data class Waiting(val person: String?, val waitedMs: Long, override val warning: Boolean) : MembersAgentWorkerMarkKind
    data class InTool(val tool: String?, val inToolMs: Long) : MembersAgentWorkerMarkKind {
        override val warning: Boolean get() = false
    }

    val warning: Boolean
}

/** Pure decision logic matching web `AgentWorkerRows.tsx` `liveMark`. */
internal fun membersAgentWorkerLiveMark(
    row: AgentWorkerRow,
    stale: Boolean,
    nowMillis: Long,
    readAtMillis: Long,
): MembersAgentWorkerMarkKind? {
    if (stale) return null
    if (row.stalled) {
        val lastAt = row.lastEventAtEpochMillis
        val silentMs = maxOf(AGENT_WORKER_QUIET_AFTER_MS, if (lastAt != null) nowMillis - lastAt else 0L)
        return MembersAgentWorkerMarkKind.Stalled(silentMs)
    }
    val waitedMs = row.oldestWaitingAtEpochMillis?.let { maxOf(0L, nowMillis - it) }
    if (waitedMs != null && waitedMs >= AGENT_WORKER_HUMAN_WAITING_AFTER_MS) {
        return MembersAgentWorkerMarkKind.Waiting(person = row.waitingFrom, waitedMs = waitedMs, warning = true)
    }
    val inToolForMs = row.inToolForMs
    if (row.doing == AgentWorkerDoing.Tool && inToolForMs != null) {
        val inToolMs = inToolForMs + maxOf(0L, nowMillis - readAtMillis)
        return MembersAgentWorkerMarkKind.InTool(tool = row.tool, inToolMs = inToolMs)
    }
    if (waitedMs != null) {
        return MembersAgentWorkerMarkKind.Waiting(person = row.waitingFrom, waitedMs = waitedMs, warning = false)
    }
    return null
}

/** Web `formatWorkerSpan` parts ("12m", "2h 5m", "3d 4h"): whole minutes, at least 1. */
internal data class MembersWorkerSpanParts(val days: Int, val hours: Int, val minutes: Int)

internal fun membersWorkerSpanParts(ms: Long): MembersWorkerSpanParts {
    val totalMinutes = maxOf(1L, ms.coerceAtLeast(0L) / 60_000L)
    if (totalMinutes < 60L) return MembersWorkerSpanParts(days = 0, hours = 0, minutes = totalMinutes.toInt())
    val totalHours = totalMinutes / 60L
    if (totalHours < 24L) {
        return MembersWorkerSpanParts(days = 0, hours = totalHours.toInt(), minutes = (totalMinutes % 60L).toInt())
    }
    return MembersWorkerSpanParts(days = (totalHours / 24L).toInt(), hours = (totalHours % 24L).toInt(), minutes = 0)
}

@Composable
internal fun membersWorkerSpanText(ms: Long): String {
    val parts = membersWorkerSpanParts(ms)
    return when {
        parts.days > 0 -> stringResource(Res.string.member_activity_workers_span_days, parts.days, parts.hours)
        parts.hours > 0 -> stringResource(Res.string.member_activity_workers_span_hours, parts.hours, parts.minutes)
        else -> stringResource(Res.string.member_activity_workers_span_minutes, parts.minutes)
    }
}

/** Web `formatWorkerEndReason` (`workerObservabilityText.ts`): why a Worker ended, in the ended record's words. */
@Composable
internal fun membersWorkerEndReasonText(
    reason: AgentWorkerEndReason,
    parentHandle: String,
    stoppedBy: String? = null,
    waitingOn: String? = null,
): String =
    when (reason) {
        AgentWorkerEndReason.Done -> stringResource(Res.string.member_activity_workers_reason_done)
        AgentWorkerEndReason.Expired -> stringResource(Res.string.member_activity_workers_reason_expired)
        AgentWorkerEndReason.StoppedByPerson,
        AgentWorkerEndReason.StoppedBy ->
            if (!stoppedBy.isNullOrBlank()) {
                stringResource(Res.string.member_activity_workers_reason_stopped_by, stoppedBy)
            } else {
                stringResource(Res.string.member_activity_workers_reason_stopped_by_person)
            }
        AgentWorkerEndReason.Stopped ->
            stringResource(Res.string.member_activity_workers_reason_stopped, parentHandle)
        AgentWorkerEndReason.WaitingOn ->
            if (!waitingOn.isNullOrBlank()) {
                stringResource(Res.string.member_activity_workers_reason_waiting_on, waitingOn)
            } else {
                stringResource(Res.string.member_activity_workers_reason_waiting_on_someone)
            }
        AgentWorkerEndReason.ParentRestarted ->
            stringResource(Res.string.member_activity_workers_reason_parent_restarted, parentHandle)
        AgentWorkerEndReason.DaemonShutdown ->
            stringResource(Res.string.member_activity_workers_reason_daemon_shutdown)
        AgentWorkerEndReason.StartFailed ->
            stringResource(Res.string.member_activity_workers_reason_start_failed)
        AgentWorkerEndReason.Exited,
        AgentWorkerEndReason.TurnFailed ->
            stringResource(Res.string.member_activity_workers_reason_error)
    }

/** Web `agoText`: "1 minute ago", "4 minutes ago", "3 hours ago", "2 days ago", "3 days ago". */
@Composable
internal fun membersWorkerAgoText(ageMs: Long): String {
    val minutes = maxOf(1L, (ageMs.coerceAtLeast(0L) + 30_000L) / 60_000L)
    if (minutes < 60L) {
        return if (minutes == 1L) {
            stringResource(Res.string.relative_time_minute_past_one)
        } else {
            stringResource(Res.string.relative_time_minute_past_many, minutes.toInt())
        }
    }
    val hours = (minutes + 30L) / 60L
    if (hours < 48L) {
        return if (hours == 1L) {
            stringResource(Res.string.relative_time_hour_past_one)
        } else {
            stringResource(Res.string.relative_time_hour_past_many, hours.toInt())
        }
    }
    val days = maxOf(2L, (hours + 12L) / 24L)
    return if (days == 2L) {
        stringResource(Res.string.relative_time_day_before_yesterday)
    } else {
        stringResource(Res.string.relative_time_day_past_many, days.toInt())
    }
}

/** The failed request behind [this] Stop feedback, for its trace ID; null for a 2xx answer. */
internal fun AgentWorkerStopFeedback.serverError(): ScreenError? =
    when (this) {
        is AgentWorkerStopFeedback.ComputerOffline -> error
        is AgentWorkerStopFeedback.SnapshotStale -> error
        is AgentWorkerStopFeedback.UpgradeRequired -> error
        is AgentWorkerStopFeedback.Failed -> error
        is AgentWorkerStopFeedback.Stopped,
        is AgentWorkerStopFeedback.NotRunning -> null
    }

/** Localized toast copy for [feedback], matching web `stopWorkerWithFeedback`. */
@Composable
internal fun membersAgentWorkerStopFeedbackMessage(
    feedback: AgentWorkerStopFeedback,
    parentHandle: String,
): String =
    when (feedback) {
        is AgentWorkerStopFeedback.Stopped ->
            stringResource(Res.string.member_activity_workers_stop_stopped, feedback.worker, feedback.objectRef, parentHandle)
        is AgentWorkerStopFeedback.NotRunning ->
            stringResource(Res.string.member_activity_workers_stop_not_running, feedback.worker)
        is AgentWorkerStopFeedback.ComputerOffline ->
            stringResource(Res.string.agent_stop_turn_computer_offline)
        is AgentWorkerStopFeedback.SnapshotStale ->
            stringResource(Res.string.member_activity_workers_stop_stale)
        is AgentWorkerStopFeedback.UpgradeRequired ->
            stringResource(Res.string.member_activity_workers_stop_upgrade_required)
        is AgentWorkerStopFeedback.Failed ->
            stringResource(Res.string.member_activity_workers_stop_failed, feedback.worker)
    }

/**
 * Web `WorkersList` (`AgentActivityLog.tsx`): live Workers first, then runtime sub-agents with
 * the "runtime sub-agent" label tag. Empty state never flashes while the initial read is out.
 */
@Composable
internal fun MembersAgentWorkersList(
    agentId: MemberId,
    workers: MembersAgentWorkersBinding,
    groups: List<AgentActivitySubagentGroup>,
    timeContext: RaftTimeContext,
    onOpenSubagent: (String) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    nowEpochMillis: (() -> Long)? = null,
) {
    val state by workers.state.collectAsState()
    val effectiveNowMillis = nowEpochMillis?.invoke() ?: state.nowMillis
    val subagentNowMillis: () -> Long = nowEpochMillis ?: { state.nowMillis.takeIf { it > 0L } ?: defaultNowMillis() }
    val recentTimeContext =
        if (timeContext.nowEpochMillis != null) {
            timeContext
        } else {
            timeContext.copy(nowEpochMillis = effectiveNowMillis.takeIf { it > 0L } ?: defaultNowMillis())
        }
    val openTimeline: ((AgentWorkerOpenTimeline) -> Unit)? =
        when {
            workers.onOpenTimeline != null -> workers.onOpenTimeline
            workers.onTimelineIntent != null -> {
                { target -> workers.onTimelineIntent.invoke(AgentWorkerTimelineIntent.Open(agentId, target)) }
            }
            else -> null
        }

    var confirmingRow by remember(agentId) { mutableStateOf<AgentWorkerRow?>(null) }
    var stopDispatchedWorker by remember(agentId) { mutableStateOf<String?>(null) }

    LaunchedEffect(stopDispatchedWorker, state.stoppingWorker) {
        if (stopDispatchedWorker != null && state.stoppingWorker == null) {
            confirmingRow = null
            stopDispatchedWorker = null
        }
    }

    val recentEnded = remember(state.recentWorkers) { recentEndedAgentWorkers(state.recentWorkers) }
    val workersShown =
        state.workers.isNotEmpty() ||
            state.stale ||
            state.status == AgentWorkersLoadStatus.Unknown ||
            recentEnded.isNotEmpty()
    // First read still out and no runtime sub-agents yet: render nothing so the empty state never flashes.
    if (groups.isEmpty() && (state.status == AgentWorkersLoadStatus.Loading || (!state.recentLoaded && state.recentLoading))) {
        Box(modifier = modifier)
        return
    }

    val pullRefreshEnabled = state.status != AgentWorkersLoadStatus.Loading
    RaftStorePullToRefreshBox(
        refreshing = state.refreshing,
        onRefresh = { workers.onIntent(AgentWorkersIntent.Refresh) },
        modifier = modifier,
        enabled = pullRefreshEnabled,
    ) {
        if (groups.isEmpty() && !workersShown) {
            RaftEmptyState(
                title = stringResource(Res.string.member_activity_workers_empty_title),
                icon = { RaftContentLucideIcon(RaftLucideIconName.Bot, size = 36.dp) },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            val runningSubagents = groups.filter { it.state == AgentActivitySubagentState.Running }
            val endedSubagents = groups.filter { it.state != AgentActivitySubagentState.Running }
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(MembersAgentWorkersListTag)
                    .nestedScroll(scrollUp = NestedScrollMode.SELF_FIRST, scrollDown = NestedScrollMode.SELF_FIRST),
                contentPadding = contentPadding,
            ) {
                if (state.status == AgentWorkersLoadStatus.Unknown) {
                    item(key = "workers-heading-unknown") {
                        MembersAgentWorkersGroupHeading(count = 0, live = false)
                    }
                    item(key = "workers-unknown") {
                        RaftText(
                            text = stringResource(Res.string.member_activity_workers_unknown),
                            style = RaftTheme.typography.caption,
                            color = RaftTheme.colors.foregroundHint,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 4.dp, end = 4.dp, bottom = 4.dp)
                                .testTag(MembersAgentWorkersUnknownTag),
                        )
                    }
                } else if (state.workers.isNotEmpty() || state.stale) {
                    item(key = "workers-heading-live") {
                        MembersAgentWorkersGroupHeading(count = state.workers.size, live = !state.stale)
                    }
                    if (state.stale) {
                        item(key = "workers-stale") {
                            val receivedAt = state.receivedAtMillis
                            val ago = if (receivedAt != null) membersWorkerAgoText(effectiveNowMillis - receivedAt) else ""
                            RaftText(
                                text = stringResource(Res.string.member_activity_workers_last_seen, ago),
                                style = RaftTheme.typography.caption,
                                color = RaftTheme.colors.foregroundHint,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 4.dp, end = 4.dp, bottom = 4.dp)
                                    .testTag(MembersAgentWorkersStaleTag),
                            )
                        }
                    }
                    if (state.workers.isNotEmpty()) {
                        item(key = "workers-live-group") {
                            RaftListRowGroup(modifier = Modifier.fillMaxWidth()) {
                                state.workers.forEachIndexed { index, row ->
                                    MembersAgentWorkerRow(
                                        row = row,
                                        stale = state.stale,
                                        nowMillis = effectiveNowMillis,
                                        readAtMillis = state.readAtMillis,
                                        canStop = workers.canStop,
                                        computerOffline = workers.computerOffline,
                                        stopBusy = state.stoppingWorker != null,
                                        divider = index > 0,
                                        onRequestStop = { confirmingRow = row },
                                        onOpenTimeline = openTimeline,
                                    )
                                }
                            }
                        }
                    }
                }

                // Web `RecentWorkersGroup`: Workers that ended in the last 7 days, newest first.
                if (recentEnded.isNotEmpty()) {
                    item(key = "workers-recent-heading") {
                        MembersAgentRecentWorkersGroupHeading(count = recentEnded.size)
                    }
                    item(key = "workers-recent-group") {
                        RaftListRowGroup(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(MembersAgentWorkersRecentGroupTag),
                        ) {
                            recentEnded.forEachIndexed { index, row ->
                                MembersAgentRecentWorkerRow(
                                    row = row,
                                    parentHandle = workers.parentHandle,
                                    timeContext = recentTimeContext,
                                    divider = index > 0,
                                    onOpen = openTimeline,
                                )
                            }
                        }
                    }
                    item(key = "workers-recent-note") {
                        RaftText(
                            text = stringResource(Res.string.member_activity_workers_recent_note),
                            style = RaftTheme.typography.caption,
                            color = RaftTheme.colors.foregroundHint,
                            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp),
                        )
                    }
                }

                // Runtime sub-agents follow with the "runtime sub-agent" tag.
                for ((isRunning, section) in listOf(true to runningSubagents, false to endedSubagents)) {
                    if (section.isEmpty()) continue
                    item(key = "subagents-heading-$isRunning") {
                        MembersAgentSubagentGroupHeading(running = isRunning, count = section.size)
                    }
                    item(key = "subagents-group-$isRunning") {
                        RaftListRowGroup(modifier = Modifier.fillMaxWidth()) {
                            section.forEachIndexed { index, group ->
                                MembersAgentSubagentRow(
                                    group = group,
                                    divider = index > 0,
                                    timeContext = timeContext,
                                    nowEpochMillis = subagentNowMillis,
                                    onOpen = onOpenSubagent,
                                    runtimeTag = true,
                                )
                            }
                        }
                    }
                }
                if (groups.isNotEmpty()) {
                    item(key = "subagents-recent-only") {
                        RaftText(
                            text = stringResource(Res.string.member_activity_subagents_recent_only),
                            style = RaftTheme.typography.caption,
                            color = RaftTheme.colors.foregroundHint,
                            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 12.dp),
                        )
                    }
                }
            }
        }
    }

    val dialogRow = confirmingRow
    if (dialogRow != null) {
        val busy = state.stoppingWorker == dialogRow.worker
        MembersAgentWorkerStopDialog(
            worker = dialogRow.worker,
            objectRef = dialogRow.objectRef,
            parentHandle = workers.parentHandle,
            busy = busy,
            onConfirm = {
                stopDispatchedWorker = dialogRow.worker
                workers.onIntent(
                    AgentWorkersIntent.Stop(
                        agentId = agentId,
                        worker = dialogRow.worker,
                        objectRef = dialogRow.objectRef,
                    )
                )
                // If the intent sink didn't transition into a synchronous in-flight state (e.g. test fake),
                // close the dialog immediately so confirmation never stalls.
                if (workers.state.value.stoppingWorker != dialogRow.worker) {
                    confirmingRow = null
                    stopDispatchedWorker = null
                }
            },
            onDismiss = {
                if (!busy) {
                    confirmingRow = null
                    stopDispatchedWorker = null
                }
            },
        )
    }
}

@Composable
private fun MembersAgentWorkersGroupHeading(count: Int, live: Boolean) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RaftStatusDot(presence = if (live && count > 0) RaftPresence.Busy else RaftPresence.Offline)
        RaftText(
            text = stringResource(Res.string.member_activity_workers_title),
            style = typography.footnote.copy(fontWeight = FontWeight.Bold),
            color = colors.foregroundMuted,
        )
        RaftText(text = count.toString(), style = typography.monoMeta, color = colors.foregroundMuted)
    }
}

@Composable
private fun MembersAgentRecentWorkersGroupHeading(count: Int) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RaftStatusDot(presence = RaftPresence.Offline)
        RaftText(
            text = stringResource(Res.string.member_activity_workers_recent_title),
            style = typography.footnote.copy(fontWeight = FontWeight.Bold),
            color = colors.foregroundMuted,
        )
        RaftText(text = count.toString(), style = typography.monoMeta, color = colors.foregroundMuted)
    }
}

@Composable
private fun MembersAgentRecentWorkerRow(
    row: AgentRecentWorkerRow,
    parentHandle: String,
    timeContext: RaftTimeContext,
    divider: Boolean,
    onOpen: ((AgentWorkerOpenTimeline) -> Unit)?,
) {
    val endedTime = activityRowTime(row.endedAt, timeContext)
    val metaText =
        endedTime?.let { time ->
            if (time is ActivityRowTime.Older) time.words() else time.shownLabel()
        }
    val reasonPart =
        row.reason?.let { reason ->
            membersWorkerEndReasonText(
                reason = reason,
                parentHandle = parentHandle,
                stoppedBy = row.stoppedBy,
                waitingOn = row.waitingOn,
            )
        }
    val startedAtMs = row.startedAtEpochMillis
    val endedAtMs = row.endedAtEpochMillis
    val ranPart =
        if (startedAtMs != null && endedAtMs != null) {
            stringResource(
                Res.string.member_activity_workers_recent_ran,
                membersWorkerSpanText(endedAtMs - startedAtMs),
            )
        } else {
            null
        }
    val handedBackPart =
        row.handedBack?.takeIf { it > 0 }?.let { count ->
            stringResource(Res.string.member_activity_workers_recent_handed_back, count)
        }
    val facts = listOfNotNull(row.objectRef, reasonPart, ranPart, handedBackPart).joinToString(" · ")
    val openClick: (() -> Unit)? =
        onOpen?.let { handler ->
            { handler(AgentWorkerOpenTimeline(launchId = row.launchId, worker = row.worker, objectRef = row.objectRef)) }
        }
    RaftListRow(
        title = row.worker,
        modifier = Modifier.testTag(membersAgentRecentWorkerRowTag(row.launchId)),
        onClick = openClick,
        divider = divider,
        meta = metaText,
        description = facts,
        wrapDescription = true,
        descriptionMaxLines = 3,
        leading = { RaftContentLucideIcon(RaftLucideIconName.Bot, size = 20.dp) },
        trailing = { RaftListRowChevron() },
    )
}

@Composable
private fun MembersAgentWorkerRow(
    row: AgentWorkerRow,
    stale: Boolean,
    nowMillis: Long,
    readAtMillis: Long,
    canStop: Boolean,
    computerOffline: Boolean,
    stopBusy: Boolean,
    divider: Boolean,
    onRequestStop: () -> Unit,
    onOpenTimeline: ((AgentWorkerOpenTimeline) -> Unit)?,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val mark = membersAgentWorkerLiveMark(row = row, stale = stale, nowMillis = nowMillis, readAtMillis = readAtMillis)
    val markText =
        when (mark) {
            null -> null
            is MembersAgentWorkerMarkKind.Stalled ->
                stringResource(Res.string.member_activity_workers_mark_stalled, membersWorkerSpanText(mark.silentMs))
            is MembersAgentWorkerMarkKind.Waiting ->
                if (mark.person != null) {
                    stringResource(Res.string.member_activity_workers_mark_waiting, mark.person, membersWorkerSpanText(mark.waitedMs))
                } else {
                    stringResource(Res.string.member_activity_workers_mark_someone_waiting, membersWorkerSpanText(mark.waitedMs))
                }
            is MembersAgentWorkerMarkKind.InTool ->
                if (mark.tool != null) {
                    stringResource(Res.string.member_activity_workers_mark_in_tool, mark.tool, membersWorkerSpanText(mark.inToolMs))
                } else {
                    stringResource(Res.string.member_activity_workers_mark_in_any_tool, membersWorkerSpanText(mark.inToolMs))
                }
        }
    val sinceEpochMillis = row.sinceEpochMillis
    val durationPart =
        when {
            row.state == AgentWorkerState.Starting -> stringResource(Res.string.member_activity_workers_starting)
            sinceEpochMillis != null -> membersActivityOverviewDuration(nowMillis - sinceEpochMillis)
            else -> null
        }
    val progress = row.progress
    val progressPart =
        if (progress != null) {
            val progressAgeMs = progress.atEpochMillis?.let { maxOf(0L, nowMillis - it) } ?: 0L
            if (progressAgeMs < 60_000L) {
                stringResource(Res.string.member_activity_workers_progress_just_now, progress.text)
            } else {
                stringResource(
                    Res.string.member_activity_workers_progress,
                    membersWorkerAgoText(progressAgeMs),
                    progress.text,
                )
            }
        } else {
            null
        }
    // Web AgentWorkerRows: object · model · how long · progress (never rule).
    val facts = listOfNotNull(row.objectRef, row.model, durationPart, progressPart).joinToString(" · ")
    val blockedReason =
        when {
            computerOffline -> stringResource(Res.string.agent_stop_turn_computer_offline)
            stale -> stringResource(Res.string.member_activity_workers_stop_stale_reason)
            else -> null
        }
    val stopAria = stringResource(Res.string.member_activity_workers_stop_aria, row.worker)
    val launchId = row.launchId
    val openClick: (() -> Unit)? =
        if (onOpenTimeline != null && launchId != null) {
            { onOpenTimeline(AgentWorkerOpenTimeline(launchId = launchId, worker = row.worker, objectRef = row.objectRef)) }
        } else {
            null
        }

    RaftListRow(
        title = row.worker,
        modifier = Modifier.testTag(membersAgentWorkerRowTag(row.worker)),
        onClick = openClick,
        divider = divider,
        description = facts,
        wrapDescription = true,
        descriptionMaxLines = 3,
        leading = { RaftContentLucideIcon(RaftLucideIconName.Bot, size = 20.dp) },
        subtitleContent =
            if (mark != null && markText != null) {
                {
                    Row(
                        modifier = Modifier.testTag(MembersAgentWorkerMarkTag),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (mark.warning) {
                            RaftLucideIcon(
                                RaftLucideIconName.TriangleAlert,
                                modifier = Modifier.size(14.dp),
                                color = raftArgbHex(colors.warningStrong),
                            )
                        }
                        RaftText(
                            text = markText,
                            style = typography.callout.copy(fontWeight = FontWeight.SemiBold),
                            color = if (mark.warning) colors.warningStrong else colors.foregroundMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            } else {
                null
            },
        trailing =
            if (canStop) {
                {
                    val stopButton: @Composable () -> Unit = {
                        RaftButton(
                            text = stringResource(Res.string.member_activity_workers_stop),
                            onClick = onRequestStop,
                            variant = RaftButtonVariant.Ghost,
                            size = RaftButtonSize.Md,
                            enabled = blockedReason == null && !stopBusy,
                            modifier = Modifier
                                .testTag(membersAgentWorkerStopTag(row.worker))
                                .semantics { contentDescription = stopAria },
                        )
                    }
                    if (blockedReason != null) {
                        RaftTooltip(text = blockedReason, openOnTap = true) {
                            stopButton()
                        }
                    } else {
                        stopButton()
                    }
                }
            } else {
                null
            },
    )
}

/**
 * Web `WorkerStopControl` size-sm confirmation Dialog:
 * title "Stop w1?", body "#eng:#7 goes back to @tenny.", ghost Cancel + warning Stop.
 */
@Composable
internal fun MembersAgentWorkerStopDialog(
    worker: String,
    objectRef: String,
    parentHandle: String,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    RaftUnifiedDialog(
        title = stringResource(Res.string.member_activity_workers_stop_title, worker),
        onDismiss = onDismiss,
        modifier = Modifier.testTag(MembersAgentWorkerStopDialogTag),
        dismissEnabled = !busy,
        dismissOnOutsideTap = false,
        maxWidth = 384.dp,
        contentPadding = 24.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            RaftText(
                text = stringResource(Res.string.member_activity_workers_stop_body, objectRef, parentHandle),
                style = RaftTheme.typography.subhead,
                color = RaftTheme.colors.foregroundMuted,
            )
            RaftDialogFooter {
                RaftButton(
                    text = stringResource(Res.string.common_cancel),
                    onClick = onDismiss,
                    enabled = !busy,
                    variant = RaftButtonVariant.Ghost,
                    size = RaftButtonSize.Lg,
                )
                RaftButton(
                    text = stringResource(Res.string.member_activity_workers_stop),
                    onClick = onConfirm,
                    loading = busy,
                    variant = RaftButtonVariant.Warning,
                    size = RaftButtonSize.Lg,
                    modifier = Modifier.testTag(MembersAgentWorkerStopConfirmTag),
                )
            }
        }
    }
}
