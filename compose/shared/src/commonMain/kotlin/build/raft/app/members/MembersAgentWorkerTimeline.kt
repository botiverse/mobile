package build.raft.app.members

import build.raft.app.resources.*
import build.raft.app.thread.RaftTimelineItem
import build.raft.app.thread.icon
import build.raft.app.thread.key
import build.raft.app.traceId
import build.raft.app.userFacingMessage
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.RaftContentLucideIcon
import build.raft.app.RaftLucideIcon
import build.raft.app.RaftLucideIconName
import build.raft.app.RaftTraceIdNote
import build.raft.app.compat.ui.NestedScrollMode
import build.raft.app.compat.ui.nestedScroll
import build.raft.app.components.RaftSlotIcon
import build.raft.app.components.RaftStorePullToRefreshBox
import build.raft.app.data.AgentRecentWorkerRow
import build.raft.app.data.AgentWorkerDeliverySender
import build.raft.app.data.AgentWorkerDeliveryVia
import build.raft.app.data.AgentWorkerEndReason
import build.raft.app.data.AgentWorkerHandedToParentReason
import build.raft.app.data.AgentWorkerOpenTimeline
import build.raft.app.data.AgentWorkerQuietDoing
import build.raft.app.data.AgentWorkerReportKind
import build.raft.app.data.AgentWorkerRow
import build.raft.app.data.AgentWorkerSettledBy
import build.raft.app.data.AgentWorkerSettledHow
import build.raft.app.data.AgentWorkerTimelineChipState
import build.raft.app.data.AgentWorkerTimelineDisplayItem
import build.raft.app.data.AgentWorkerTimelineEventData
import build.raft.app.data.AgentWorkerTimelineLoadStatus
import build.raft.app.data.AgentWorkerTimelineState
import build.raft.app.data.AgentWorkerTimelineVariant
import build.raft.app.data.AgentWorkerTurnOutcome
import build.raft.app.data.AgentWorkersIntent
import build.raft.app.data.MobileUiErrorCode
import build.raft.app.data.MobileUiErrorSurface
import build.raft.app.data.WorkerConversationHoldingMode
import build.raft.app.data.agentWorkerTimelineChipState
import build.raft.app.data.defaultNowMillis
import build.raft.app.data.formatAgentUsageTokens
import build.raft.app.data.fullObjectRefs
import build.raft.app.data.projectAgentWorkerTimelineEvents
import build.raft.app.localizedErrorPresentationStrings
import build.raft.app.model.MemberId
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.formatRaftTimestampClockWithSeconds
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.app.raftArgbHex
import build.raft.designsystem.components.RaftBackButton
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftDescriptionList
import build.raft.designsystem.components.RaftDescriptionListDirection
import build.raft.designsystem.components.RaftEmptyState
import build.raft.designsystem.components.RaftPresence
import build.raft.designsystem.components.RaftStatusDot
import build.raft.designsystem.components.RaftStatusVariant
import build.raft.designsystem.components.RaftTextLink
import build.raft.designsystem.components.RaftTextLinkVariant
import build.raft.designsystem.components.RaftTooltip
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

// Worker observability (botiverse/slock `docs/contracts/worker-events.md`, `AgentWorkerTimeline.tsx`):
// One launch's timeline pushed one level into Agent Detail › Activity › Workers, mirroring
// `MembersAgentSubagentDetail`: header (Worker + conversation, state chip, Stop while running),
// horizontal DescriptionList facts, chronological Timeline (turns folded start->end, `needs_parent`
// hidden, warning icons on model stalls / human waiting / 24h expiry, "View message" links), and
// 7-day retention footer.

internal const val MembersAgentWorkerTimelineTag = "members_agent_worker_timeline"
internal const val MembersAgentWorkerTimelineBackTag = "members_agent_worker_timeline_back"
internal const val MembersAgentWorkerTimelineStateTag = "members_agent_worker_timeline_state"
internal const val MembersAgentWorkerTimelineStatusTag = "members_agent_worker_timeline_status"
internal const val MembersAgentWorkerTimelineEventsTag = "members_agent_worker_timeline_events"
internal const val MembersAgentWorkerTimelineUnavailableTag = "members_agent_worker_timeline_unavailable"
internal const val MembersAgentWorkerTimelineRetryTag = "members_agent_worker_timeline_retry"

internal fun membersAgentWorkerTimelineEventTag(seq: Int): String = "members_agent_worker_timeline_event_$seq"

internal fun membersAgentWorkerTimelineViewMessageTag(messageId: String): String =
    "members_agent_worker_timeline_message_$messageId"

@Composable
internal fun MembersAgentWorkerTimelineStateChip(
    state: AgentWorkerTimelineChipState,
    modifier: Modifier = Modifier,
) {
    val (variant, label) =
        when (state) {
            AgentWorkerTimelineChipState.Working ->
                RaftBadgeVariant.Busy to stringResource(Res.string.member_activity_workers_state_working)
            AgentWorkerTimelineChipState.Finished ->
                RaftBadgeVariant.Success to stringResource(Res.string.member_activity_workers_state_finished)
            AgentWorkerTimelineChipState.Stopped ->
                RaftBadgeVariant.Muted to stringResource(Res.string.member_activity_workers_state_stopped)
            AgentWorkerTimelineChipState.Error ->
                RaftBadgeVariant.Warning to stringResource(Res.string.member_activity_workers_state_error)
            AgentWorkerTimelineChipState.Ended ->
                RaftBadgeVariant.Muted to stringResource(Res.string.member_activity_workers_state_ended)
        }
    RaftBadge(
        text = label,
        modifier = modifier,
        appearance = RaftBadgeAppearance.Soft,
        variant = variant,
        icon = {
            when (state) {
                AgentWorkerTimelineChipState.Working -> RaftStatusDot(presence = RaftPresence.Busy)
                AgentWorkerTimelineChipState.Finished -> RaftStatusDot(variant = RaftStatusVariant.Success)
                AgentWorkerTimelineChipState.Error -> RaftStatusDot(presence = RaftPresence.Error)
                AgentWorkerTimelineChipState.Stopped,
                AgentWorkerTimelineChipState.Ended -> RaftStatusDot(presence = RaftPresence.Offline)
            }
        },
    )
}

/**
 * Web `WorkerTimelineDetail` (`AgentWorkerTimeline.tsx`): one launch's timeline pushed inside
 * the Workers view; Back returns to the Workers list.
 */
@Composable
internal fun MembersAgentWorkerTimelineDetail(
    agentId: MemberId,
    open: AgentWorkerOpenTimeline,
    parentHandle: String,
    live: AgentWorkerRow?,
    ended: AgentRecentWorkerRow?,
    timelineState: AgentWorkerTimelineState,
    nowMillis: Long,
    stale: Boolean,
    canStop: Boolean,
    computerOffline: Boolean,
    stoppingWorker: String?,
    timeContext: RaftTimeContext,
    horizontalInset: Dp,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onWorkersIntent: (AgentWorkersIntent) -> Unit,
    onOpenMessage: (messageId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val errorStrings = localizedErrorPresentationStrings()

    val events = if (timelineState.status == AgentWorkerTimelineLoadStatus.Loaded) timelineState.events else emptyList()
    val displayItems = remember(events) { projectAgentWorkerTimelineEvents(events) }
    val endEvent = remember(events) { events.firstOrNull { it.data is AgentWorkerTimelineEventData.Ended } }
    val endData = endEvent?.data as? AgentWorkerTimelineEventData.Ended
    val handedOutEvent = remember(events) { events.firstOrNull { it.data is AgentWorkerTimelineEventData.HandedOut } }
    val handedOutData = handedOutEvent?.data as? AgentWorkerTimelineEventData.HandedOut

    val reason = ended?.reason ?: endData?.reason
    val chipState = agentWorkerTimelineChipState(isLive = live != null, reason = reason)
    val model = live?.model ?: handedOutData?.model
    val startedAt = ended?.startedAt ?: live?.since ?: handedOutEvent?.occurredAt
    val endedAt = ended?.endedAt ?: endEvent?.occurredAt
    val turnsCount = remember(events) { events.count { it.data is AgentWorkerTimelineEventData.TurnStarted } }
    val startedAtMillis = parseRaftTimestampEpochMillisOrNull(startedAt)
    val endedAtMillis = parseRaftTimestampEpochMillisOrNull(endedAt)
    val effectiveNowMillis = nowMillis.takeIf { it > 0L } ?: timeContext.nowEpochMillis ?: defaultNowMillis()
    val heldMs = startedAtMillis?.let { start -> maxOf(0L, (endedAtMillis ?: effectiveNowMillis) - start) }
    val stoppedBy = ended?.stoppedBy ?: endData?.stoppedBy
    val waitingOn = ended?.waitingOn ?: endData?.waitingOn
    val conversationObjects =
        remember(live, handedOutData, open.objectRef) {
            if (live != null) {
                live.fullObjectRefs()
            } else {
                handedOutData?.fullObjectRefs()?.takeIf { it.isNotEmpty() }
                    ?: listOfNotNull(open.objectRef?.trim()?.takeIf { it.isNotEmpty() })
            }
        }
    val conversationSummary =
        if (conversationObjects.isNotEmpty()) {
            membersWorkerJoinObjectsLocalized(conversationObjects)
        } else {
            null
        }
    // #22: a Worker that holds no conversation is named alone.
    val title = if (conversationSummary != null) "${open.worker} · $conversationSummary" else open.worker

    var confirmingStop by remember(agentId, open.launchId) { mutableStateOf(false) }
    var stopDispatched by remember(agentId, open.launchId) { mutableStateOf(false) }
    LaunchedEffect(stopDispatched, stoppingWorker) {
        if (stopDispatched && stoppingWorker == null) {
            confirmingStop = false
            stopDispatched = false
        }
    }

    val blockedReason =
        when {
            computerOffline -> stringResource(Res.string.agent_stop_turn_computer_offline)
            stale -> stringResource(Res.string.member_activity_workers_stop_stale_reason)
            else -> null
        }

    Column(modifier = modifier.testTag(MembersAgentWorkerTimelineTag)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalInset, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            RaftBackButton(
                onClick = onBack,
                label = stringResource(Res.string.member_activity_workers_back),
                modifier = Modifier.testTag(MembersAgentWorkerTimelineBackTag),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                RaftText(
                    text = title,
                    style = typography.monoCode.copy(
                        fontSize = typography.headline.fontSize,
                        lineHeight = typography.headline.lineHeight,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = colors.foreground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                MembersAgentWorkerTimelineStateChip(
                    state = chipState,
                    modifier = Modifier.testTag(MembersAgentWorkerTimelineStateTag),
                )
            }
            if (live != null && canStop) {
                val stopAria = stringResource(Res.string.member_activity_workers_stop_aria, live.worker)
                val stopButton: @Composable () -> Unit = {
                    RaftButton(
                        text = stringResource(Res.string.member_activity_workers_stop),
                        onClick = { confirmingStop = true },
                        variant = RaftButtonVariant.Default,
                        size = RaftButtonSize.Md,
                        enabled = blockedReason == null && stoppingWorker == null,
                        modifier = Modifier
                            .testTag(membersAgentWorkerStopTag(live.worker))
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
        }
        MembersDetailSectionDivider()

        val pullRefreshEnabled =
            timelineState.status != AgentWorkerTimelineLoadStatus.Loading &&
                timelineState.status != AgentWorkerTimelineLoadStatus.Idle
        RaftStorePullToRefreshBox(
            refreshing = timelineState.refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxWidth().weight(1f),
            enabled = pullRefreshEnabled,
        ) {
            when (timelineState.status) {
                AgentWorkerTimelineLoadStatus.Unavailable -> {
                    RaftEmptyState(
                        title = stringResource(Res.string.member_activity_workers_timeline_unavailable),
                        icon = { RaftContentLucideIcon(RaftLucideIconName.Bot, size = 36.dp) },
                        modifier = Modifier.fillMaxSize().testTag(MembersAgentWorkerTimelineUnavailableTag),
                    )
                }
                AgentWorkerTimelineLoadStatus.Error -> {
                    val error = timelineState.error
                    RaftEmptyState(
                        title = stringResource(Res.string.member_activity_workers_timeline_unavailable),
                        description = error?.userFacingMessage(errorStrings),
                        icon = { RaftContentLucideIcon(RaftLucideIconName.Bot, size = 36.dp) },
                        modifier = Modifier.fillMaxSize().testTag(MembersAgentWorkerTimelineUnavailableTag),
                        actions = {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                RaftButton(
                                    text = stringResource(Res.string.common_retry),
                                    onClick = onRefresh,
                                    leadingIcon = { RaftSlotIcon(RaftLucideIconName.RefreshCw) },
                                    size = RaftButtonSize.Lg,
                                    modifier = Modifier.testTag(MembersAgentWorkerTimelineRetryTag),
                                )
                                RaftTraceIdNote(
                                    traceId = error?.traceId,
                                    uiErrorSurface = MobileUiErrorSurface.ScreenError,
                                    uiErrorRoute = "agent_worker_timeline",
                                    uiErrorCode = MobileUiErrorCode.Unknown,
                                )
                            }
                        },
                    )
                }
                AgentWorkerTimelineLoadStatus.Idle,
                AgentWorkerTimelineLoadStatus.Loading,
                AgentWorkerTimelineLoadStatus.Loaded -> {
                    val hasFacts =
                        conversationSummary != null ||
                            !model.isNullOrBlank() ||
                            !startedAt.isNullOrBlank() ||
                            heldMs != null ||
                            reason != null
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag(MembersAgentWorkerTimelineEventsTag)
                            .nestedScroll(scrollUp = NestedScrollMode.SELF_FIRST, scrollDown = NestedScrollMode.SELF_FIRST),
                    ) {
                        if (hasFacts) {
                            item(key = "worker-timeline-facts") {
                                Column {
                                    RaftDescriptionList(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = horizontalInset, vertical = 12.dp),
                                        direction = RaftDescriptionListDirection.Horizontal,
                                    ) {
                                        if (conversationSummary != null) {
                                            Item(
                                                term = stringResource(Res.string.member_activity_workers_detail_conversation),
                                                value = conversationSummary,
                                                mono = true,
                                            )
                                        }
                                        if (!model.isNullOrBlank()) {
                                            Item(
                                                term = stringResource(Res.string.member_activity_workers_detail_model),
                                                value = model,
                                                mono = true,
                                            )
                                        }
                                        if (!startedAt.isNullOrBlank()) {
                                            Item(
                                                term = stringResource(Res.string.member_activity_workers_detail_started),
                                                value = formatRaftTimestampClockWithSeconds(startedAt, timeContext).uppercase(),
                                                mono = true,
                                            )
                                        }
                                        if (heldMs != null) {
                                            val timeParts =
                                                listOfNotNull(
                                                    membersWorkerSpanText(heldMs),
                                                    turnsCount.takeIf { it > 0 }?.let { count ->
                                                        if (count == 1) stringResource(Res.string.member_activity_workers_detail_turns_one)
                                                        else stringResource(Res.string.member_activity_workers_detail_turns_other, count)
                                                    },
                                                ).joinToString(" · ")
                                            Item(
                                                term = stringResource(Res.string.member_activity_workers_detail_time),
                                                value = timeParts,
                                            )
                                        }
                                        if (reason != null) {
                                            Item(
                                                term = stringResource(Res.string.member_activity_workers_detail_ended),
                                                value = membersWorkerEndReasonText(
                                                    reason = reason,
                                                    parentHandle = parentHandle,
                                                    stoppedBy = stoppedBy,
                                                    waitingOn = waitingOn,
                                                ),
                                            )
                                        }
                                    }
                                    MembersDetailSectionDivider()
                                }
                            }
                        }

                        if (timelineState.status != AgentWorkerTimelineLoadStatus.Loaded) {
                            item(key = "worker-timeline-loading") {
                                RaftText(
                                    text = stringResource(Res.string.member_activity_workers_timeline_loading),
                                    style = typography.caption,
                                    color = colors.foregroundHint,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = horizontalInset, vertical = 12.dp)
                                        .testTag(MembersAgentWorkerTimelineStatusTag),
                                )
                            }
                        } else {
                            itemsIndexed(displayItems, key = { _, item -> item.seq }) { index, item ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = horizontalInset)
                                        .padding(
                                            top = if (index == 0) 12.dp else 0.dp,
                                            bottom = if (index == displayItems.lastIndex) 12.dp else 0.dp,
                                        ),
                                ) {
                                    MembersAgentWorkerTimelineEventRow(
                                        item = item,
                                        parentHandle = parentHandle,
                                        worker = open.worker,
                                        timeContext = timeContext,
                                        last = index == displayItems.lastIndex,
                                        onOpenMessage = onOpenMessage,
                                    )
                                }
                            }
                            item(key = "worker-timeline-footer") {
                                RaftText(
                                    text = stringResource(Res.string.member_activity_workers_timeline_footer),
                                    style = typography.caption,
                                    color = colors.foregroundHint,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = horizontalInset, end = horizontalInset, top = 4.dp, bottom = 16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmingStop && live != null) {
        val busy = stoppingWorker == live.worker
        val fullObjectRefs = live.fullObjectRefs()
        MembersAgentWorkerStopDialog(
            worker = live.worker,
            objectRefs = fullObjectRefs,
            parentHandle = parentHandle,
            busy = busy,
            onConfirm = {
                stopDispatched = true
                onWorkersIntent(
                    AgentWorkersIntent.Stop(
                        agentId = agentId,
                        worker = live.worker,
                        objectRefs = fullObjectRefs,
                    )
                )
            },
            onDismiss = {
                if (!busy) {
                    confirmingStop = false
                    stopDispatched = false
                }
            },
        )
    }
}

private fun AgentWorkerTimelineVariant.markerColor(): Color =
    when (this) {
        AgentWorkerTimelineVariant.Default -> RaftStatusVariant.Default.color
        AgentWorkerTimelineVariant.Information -> RaftStatusVariant.Information.color
        AgentWorkerTimelineVariant.Success -> RaftStatusVariant.Success.color
        AgentWorkerTimelineVariant.Warning -> RaftStatusVariant.Warning.color
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MembersAgentWorkerTimelineEventRow(
    item: AgentWorkerTimelineDisplayItem,
    parentHandle: String,
    worker: String,
    timeContext: RaftTimeContext,
    last: Boolean,
    onOpenMessage: (messageId: String) -> Unit,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val titleText = membersWorkerTimelineEventTitle(item, parentHandle, worker)
    val detailParts = membersWorkerTimelineEventDetailParts(item)
    val linkMessageId = item.data.viewableMessageIdOrNull()
    val clockText = formatRaftTimestampClockWithSeconds(item.occurredAt, timeContext).uppercase()

    RaftTimelineItem(
        color = item.variant.markerColor(),
        last = last,
        modifier = Modifier.testTag(membersAgentWorkerTimelineEventTag(item.seq)),
        time = clockText.takeIf { it.isNotBlank() },
        title = {
            if (item.warning) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RaftLucideIcon(
                        RaftLucideIconName.TriangleAlert,
                        modifier = Modifier.size(14.dp),
                        color = raftArgbHex(colors.warningStrong),
                    )
                    RaftText(
                        text = titleText,
                        style = typography.callout.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.warningStrong,
                    )
                }
            } else {
                RaftText(
                    text = titleText,
                    style = typography.callout.copy(fontWeight = FontWeight.Medium),
                    color = colors.foreground,
                )
            }
        },
        content =
            if (detailParts.isNotEmpty() || linkMessageId != null) {
                {
                    if (linkMessageId != null) {
                        val prefixText = detailParts.joinToString(" · ")
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            if (prefixText.isNotEmpty()) {
                                RaftText(
                                    text = "$prefixText ·",
                                    style = typography.caption,
                                    color = colors.foregroundHint,
                                    modifier = Modifier.align(Alignment.CenterVertically),
                                )
                            }
                            RaftTextLink(
                                text = stringResource(Res.string.member_activity_workers_timeline_view_message),
                                onClick = { onOpenMessage(linkMessageId) },
                                variant = RaftTextLinkVariant.Strong,
                                style = typography.caption,
                                modifier = Modifier
                                    .align(Alignment.CenterVertically)
                                    .testTag(membersAgentWorkerTimelineViewMessageTag(linkMessageId)),
                            )
                        }
                    } else {
                        RaftText(
                            text = detailParts.joinToString(" · "),
                            style = typography.caption,
                            color = colors.foregroundHint,
                        )
                    }
                }
            } else {
                null
            },
    )
}

private fun AgentWorkerTimelineEventData.viewableMessageIdOrNull(): String? =
    when (this) {
        is AgentWorkerTimelineEventData.Delivered -> messageId
        is AgentWorkerTimelineEventData.HumanWaiting -> messageId
        is AgentWorkerTimelineEventData.HandedToParent -> messageId
        is AgentWorkerTimelineEventData.HumanSettled -> messageId
        is AgentWorkerTimelineEventData.HandedOut,
        is AgentWorkerTimelineEventData.Delegated,
        is AgentWorkerTimelineEventData.Undelegated,
        is AgentWorkerTimelineEventData.TurnStarted,
        is AgentWorkerTimelineEventData.TurnEnded,
        is AgentWorkerTimelineEventData.Reported,
        is AgentWorkerTimelineEventData.Quiet,
        is AgentWorkerTimelineEventData.Active,
        is AgentWorkerTimelineEventData.Ended -> null
    }

@Composable
private fun membersWorkerTimelineEventTitle(
    item: AgentWorkerTimelineDisplayItem,
    parentHandle: String,
    worker: String,
): String =
    when (val data = item.data) {
        is AgentWorkerTimelineEventData.HandedOut ->
            stringResource(Res.string.member_activity_workers_event_handed_out, parentHandle)
        is AgentWorkerTimelineEventData.Delegated ->
            when (data.mode) {
                WorkerConversationHoldingMode.Full ->
                    stringResource(Res.string.member_activity_workers_event_delegated, parentHandle)
                WorkerConversationHoldingMode.Post ->
                    stringResource(Res.string.member_activity_workers_event_delegated_post_only, parentHandle)
            }
        is AgentWorkerTimelineEventData.Undelegated ->
            stringResource(Res.string.member_activity_workers_event_undelegated, parentHandle)
        is AgentWorkerTimelineEventData.Delivered ->
            when (data.sender) {
                AgentWorkerDeliverySender.Human ->
                    stringResource(Res.string.member_activity_workers_event_delivered_human)
                AgentWorkerDeliverySender.Agent ->
                    stringResource(Res.string.member_activity_workers_event_delivered_agent)
                AgentWorkerDeliverySender.System ->
                    stringResource(Res.string.member_activity_workers_event_delivered_system)
            }
        is AgentWorkerTimelineEventData.TurnStarted ->
            stringResource(Res.string.member_activity_workers_event_turn_running, data.turn)
        is AgentWorkerTimelineEventData.TurnEnded -> {
            val span = membersActivityOverviewDuration(data.durationMs)
            if (data.toolCalls == 1) {
                stringResource(Res.string.member_activity_workers_event_turn_one, data.turn, span)
            } else {
                stringResource(Res.string.member_activity_workers_event_turn_other, data.turn, span, data.toolCalls)
            }
        }
        is AgentWorkerTimelineEventData.Reported ->
            when (data.reportKind) {
                AgentWorkerReportKind.Progress -> {
                    val progressText = data.text
                    if (!progressText.isNullOrBlank()) {
                        stringResource(Res.string.member_activity_workers_event_progress, progressText)
                    } else {
                        stringResource(Res.string.member_activity_workers_event_progress_reported)
                    }
                }
                AgentWorkerReportKind.NeedsDecision ->
                    stringResource(Res.string.member_activity_workers_event_needs_decision, parentHandle)
                AgentWorkerReportKind.OutOfScope ->
                    stringResource(Res.string.member_activity_workers_event_out_of_scope, parentHandle)
                AgentWorkerReportKind.NeedsParent ->
                    stringResource(Res.string.member_activity_workers_event_progress_reported)
            }
        is AgentWorkerTimelineEventData.Quiet ->
            stringResource(Res.string.member_activity_workers_event_quiet, membersWorkerSpanText(item.elapsedMs ?: 0L))
        is AgentWorkerTimelineEventData.Active ->
            stringResource(Res.string.member_activity_workers_event_active, membersWorkerSpanText(item.elapsedMs ?: 0L))
        is AgentWorkerTimelineEventData.HumanWaiting ->
            stringResource(
                Res.string.member_activity_workers_event_human_waiting,
                membersWorkerSpanText(item.elapsedMs ?: 0L),
            )
        is AgentWorkerTimelineEventData.HandedToParent ->
            when (data.reason) {
                AgentWorkerHandedToParentReason.Overdue ->
                    stringResource(
                        Res.string.member_activity_workers_event_handed_to_parent,
                        parentHandle,
                        membersWorkerSpanText(data.waitedMs),
                    )
                AgentWorkerHandedToParentReason.Ended ->
                    stringResource(Res.string.member_activity_workers_event_handed_to_parent_ended, parentHandle)
            }
        is AgentWorkerTimelineEventData.HumanSettled -> {
            val who = if (data.by == AgentWorkerSettledBy.Worker) worker else "@$parentHandle"
            val span = membersWorkerSpanText(data.waitedMs)
            when (data.how) {
                AgentWorkerSettledHow.Replied ->
                    stringResource(Res.string.member_activity_workers_event_settled_replied, who, span)
                AgentWorkerSettledHow.Reacted ->
                    stringResource(Res.string.member_activity_workers_event_settled_reacted, who, span)
                AgentWorkerSettledHow.Deleted ->
                    stringResource(Res.string.member_activity_workers_event_settled_deleted, span)
                AgentWorkerSettledHow.AgentLeft ->
                    stringResource(Res.string.member_activity_workers_event_settled_agent_left, parentHandle, span)
                AgentWorkerSettledHow.Expired24h ->
                    stringResource(Res.string.member_activity_workers_event_settled_expired)
            }
        }
        is AgentWorkerTimelineEventData.Ended -> {
            val span = membersWorkerSpanText(data.durationMs)
            when (data.reason) {
                AgentWorkerEndReason.Done ->
                    stringResource(Res.string.member_activity_workers_event_ended_done, span)
                AgentWorkerEndReason.WaitingOn -> {
                    val waitingOn = data.waitingOn
                    if (!waitingOn.isNullOrBlank()) {
                        stringResource(
                            Res.string.member_activity_workers_event_ended_waiting_on,
                            waitingOn,
                            span,
                        )
                    } else {
                        stringResource(Res.string.member_activity_workers_event_ended_waiting_on_someone, span)
                    }
                }
                else ->
                    stringResource(
                        Res.string.member_activity_workers_event_ended,
                        membersWorkerEndReasonText(
                            reason = data.reason,
                            parentHandle = parentHandle,
                            stoppedBy = data.stoppedBy,
                            waitingOn = data.waitingOn,
                        ),
                        span,
                    )
            }
        }
    }

@Composable
private fun membersWorkerTimelineEventDetailParts(
    item: AgentWorkerTimelineDisplayItem,
): List<String> =
    when (val data = item.data) {
        is AgentWorkerTimelineEventData.HandedOut ->
            listOfNotNull(data.objectRef, data.model)
        is AgentWorkerTimelineEventData.Delegated ->
            listOf(data.objectRef)
        is AgentWorkerTimelineEventData.Undelegated ->
            listOf(data.objectRef)
        is AgentWorkerTimelineEventData.Delivered ->
            listOf(
                when (data.via) {
                    AgentWorkerDeliveryVia.NewTurn ->
                        stringResource(Res.string.member_activity_workers_event_via_new_turn)
                    AgentWorkerDeliveryVia.Steered ->
                        stringResource(Res.string.member_activity_workers_event_via_steered)
                    AgentWorkerDeliveryVia.Queued ->
                        stringResource(Res.string.member_activity_workers_event_via_queued)
                }
            )
        is AgentWorkerTimelineEventData.TurnStarted -> emptyList()
        is AgentWorkerTimelineEventData.TurnEnded ->
            buildList {
                add(
                    if (data.sent.size == 1) {
                        stringResource(Res.string.member_activity_workers_event_turn_sent_one)
                    } else {
                        stringResource(Res.string.member_activity_workers_event_turn_sent_other, data.sent.size)
                    }
                )
                data.tokens?.let { tokens ->
                    add(
                        stringResource(
                            Res.string.member_activity_workers_event_turn_tokens,
                            formatAgentUsageTokens(tokens.totalInputOutput),
                        )
                    )
                }
                when (data.outcome) {
                    AgentWorkerTurnOutcome.Completed -> Unit
                    AgentWorkerTurnOutcome.Failed ->
                        add(stringResource(Res.string.member_activity_workers_event_turn_failed))
                    AgentWorkerTurnOutcome.Interrupted ->
                        add(stringResource(Res.string.member_activity_workers_event_turn_interrupted))
                }
            }
        is AgentWorkerTimelineEventData.Reported -> emptyList()
        is AgentWorkerTimelineEventData.Quiet ->
            listOf(
                when (data.doing) {
                    AgentWorkerQuietDoing.Model ->
                        stringResource(Res.string.member_activity_workers_event_quiet_model)
                    AgentWorkerQuietDoing.Tool -> {
                        val toolName = data.tool
                        if (!toolName.isNullOrBlank()) {
                            stringResource(Res.string.member_activity_workers_event_quiet_tool, toolName)
                        } else {
                            stringResource(Res.string.member_activity_workers_event_quiet_any_tool)
                        }
                    }
                }
            )
        is AgentWorkerTimelineEventData.Active -> emptyList()
        is AgentWorkerTimelineEventData.HumanWaiting -> emptyList()
        is AgentWorkerTimelineEventData.HandedToParent -> emptyList()
        is AgentWorkerTimelineEventData.HumanSettled -> emptyList()
        is AgentWorkerTimelineEventData.Ended ->
            buildList {
                add(data.note ?: stringResource(Res.string.member_activity_workers_event_ended_no_note))
                if (data.handedBack > 0) {
                    add(stringResource(Res.string.member_activity_workers_recent_handed_back, data.handedBack))
                }
            }
    }
