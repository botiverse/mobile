package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftStorePullToRefreshBox
import build.raft.app.compat.ui.NestedScrollMode
import build.raft.app.compat.ui.nestedScroll
import build.raft.app.data.AgentActivityActionCount
import build.raft.app.data.AgentActivityDetailKindWakeCrashLoopBlocked
import build.raft.app.data.AgentActivityActionKind
import build.raft.app.data.AgentActivityOverviewIntent
import build.raft.app.data.AgentActivityOverviewState
import build.raft.app.data.AgentActivityOverviewStatus
import build.raft.app.data.AgentActivityProblemReason
import build.raft.app.data.AgentActivityReplyState
import build.raft.app.data.AgentActivityTriggerDescription
import build.raft.app.data.AgentActivityTurn
import build.raft.app.data.AgentActivityTurnOutcome
import build.raft.app.data.AgentActivityTurnProblem
import build.raft.app.data.AgentActivityTurnReply
import build.raft.app.data.describe
import build.raft.app.markdown.RaftMarkdownBody
import build.raft.app.model.AgentStatus
import build.raft.app.model.AgentWakeCrashLoop
import build.raft.app.model.MillisPerDay
import build.raft.app.model.MillisPerMinute
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.formatRaftTimestampEpochMillis
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftCard
import build.raft.designsystem.components.RaftPresence
import build.raft.designsystem.components.RaftSegment
import build.raft.designsystem.components.RaftSegmentedControl
import build.raft.designsystem.components.RaftStatusDot
import build.raft.designsystem.components.RaftStatusSize
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import build.raft.app.data.AgentActivityAppEvent
import build.raft.app.data.agentMemoryLimitExit
import build.raft.app.data.isAgentBackgroundOomActivityTitle
import build.raft.app.data.openableMessage
import build.raft.app.markdown.markdownToPreview
import build.raft.app.model.MessageId
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftIconTile
import build.raft.designsystem.components.RaftIconTileSize
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowGroup
import build.raft.designsystem.components.RaftListRowVariant
import build.raft.designsystem.components.RaftTextLink
import build.raft.designsystem.components.RaftTextLinkVariant
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/*
 * Agent Activity tab "Overview" (web packages/web/src/components/agent/AgentActivityOverview.tsx at
 * botiverse/slock 24873fd5be37, behind `activity_overview_web_v0`): one block per finished turn in
 * time order, oldest at the top like the raw log, then the turn still running, then what the agent
 * is doing now at the bottom. Detail stays in the raw log, one segment away.
 */

/**
 * The overview's state and intent sink, owned by [build.raft.app.data.SharedAgentActivityOverviewStore].
 * The flow is collected only where the overview is drawn, so its updates recompose the Activity
 * tab's overview and nothing above it.
 */
internal data class MembersAgentActivityOverviewBinding(
    val state: StateFlow<AgentActivityOverviewState>,
    val onIntent: (AgentActivityOverviewIntent) -> Unit,
)

internal enum class MembersAgentActivityView { Overview, Raw }

/** Web AgentActivityLog's SegmentedControl: Overview / Raw Log. */
@Composable
internal fun MembersAgentActivityViewSwitch(
    view: MembersAgentActivityView,
    onViewChange: (MembersAgentActivityView) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Web: the SegmentedControl's aria-label "Activity view".
    val groupLabel = stringResource(Res.string.member_activity_overview_view_label)
    RaftSegmentedControl(modifier = modifier.semantics { contentDescription = groupLabel }) {
        RaftSegment(
            text = stringResource(Res.string.member_activity_overview_view_overview),
            selected = view == MembersAgentActivityView.Overview,
            onClick = { onViewChange(MembersAgentActivityView.Overview) },
        )
        RaftSegment(
            text = stringResource(Res.string.member_activity_overview_view_raw),
            selected = view == MembersAgentActivityView.Raw,
            onClick = { onViewChange(MembersAgentActivityView.Raw) },
        )
    }
}

/**
 * What the "now" card shows about the agent's live state. [indicator] is the detail page's
 * observed live status (the same one its header dot reads, external tiers included); null
 * means not observed yet, and the card then draws no dot rather than a manufactured one.
 */
internal data class MembersAgentActivityNow(
    val label: String,
    val indicator: RaftAgentStatusIndicatorState?,
    /** Why automatic wakes are paused ([membersAgentActivityWakeCrashLoop]); null when they are not. */
    val wakeCrashLoop: AgentWakeCrashLoop? = null,
)

/**
 * The automatic-wake pause the "now" card explains: the live status's, else the loaded profile's
 * (`GET /agents/:id`) while the live status has no kind of its own and is not past the pause (a
 * paused agent is offline; a start lifts the pause).
 */
internal fun membersAgentActivityWakeCrashLoop(
    indicator: RaftAgentStatusIndicatorState?,
    summaryDetailKind: String?,
    summaryWakeCrashLoop: AgentWakeCrashLoop?,
): AgentWakeCrashLoop? {
    val liveKind = indicator?.detailKind
    if (liveKind != null) return indicator?.wakeCrashLoop.takeIf { liveKind == AgentActivityDetailKindWakeCrashLoopBlocked }
    val liveStatus = indicator?.status
    if (liveStatus != null && liveStatus != AgentStatus.Offline) return null
    return summaryWakeCrashLoop.takeIf { summaryDetailKind == AgentActivityDetailKindWakeCrashLoopBlocked }
}

/** The "now" card's dot colour: the live indicator's, or none. */
internal fun MembersAgentActivityNow.dotColor(): Color? = indicator?.color

@Composable
internal fun MembersAgentActivityOverview(
    binding: MembersAgentActivityOverviewBinding,
    now: MembersAgentActivityNow,
    hasRawActivity: Boolean,
    hasComputer: Boolean,
    timeContext: RaftTimeContext,
    onShowRaw: () -> Unit,
    onOpenComputer: (() -> Unit)?,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Opens a message by its channel and id (web `toMessage`). */
    onOpenMessage: (channelId: String, messageId: MessageId) -> Unit = { _, _ -> },
) {
    val state by binding.state.collectAsState()
    val status = state.status
    if (status is AgentActivityOverviewStatus.Error) {
        RaftEmptyState(
            title = stringResource(Res.string.member_activity_overview_load_error),
            iconName = RaftLucideIconName.Activity,
            actionLabel = stringResource(Res.string.common_retry),
            onAction = { binding.onIntent(AgentActivityOverviewIntent.Refresh) },
            modifier = modifier,
        )
        return
    }
    val loaded = status == AgentActivityOverviewStatus.Loaded
    val finished = state.finishedTurns
    val running = state.running
    // The API pages newest first; the overview reads in time order like the log: earlier turns
    // above, then the running turn, then what the agent is doing now at the bottom (web).
    val chronological = remember(finished) { finished.asReversed() }
    val showTurns = loaded && (finished.isNotEmpty() || running != null)
    val listState = remember(state.agentId) { LazyListState() }
    val lastIndex = (if (state.next != null) 1 else 0) + chronological.size + (if (running != null) 1 else 0)
    val latestTurnKey = running?.key ?: finished.firstOrNull()?.key
    // Parked bottom feed (docs/programmatic-scroll-ownership-contract.md, "Members Agent Activity
    // tail"): it follows new turns while the reader is at the bottom; leaving the tail retires that,
    // and a new turn then shows the "New turn" hint instead of moving what is being read.
    var followingBottom by remember(state.agentId) { mutableStateOf(true) }
    var placed by remember(state.agentId) { mutableStateOf(false) }
    var newTurnBelow by remember(state.agentId) { mutableStateOf(false) }
    if (showTurns) {
        LaunchedEffect(state.agentId, lastIndex) {
            snapshotFlow { listState.isScrollInProgress to listState.isActivityOverviewAtBottom(lastIndex) }
                .distinctUntilChanged()
                .collect { (scrolling, atBottom) ->
                    followingBottom = membersAgentActivityNextBottomAnchor(followingBottom, scrolling, atBottom)
                    if (atBottom) newTurnBelow = false
                }
        }
        LaunchedEffect(state.agentId, latestTurnKey) {
            when (membersActivityOverviewNewTurnAction(placed, followingBottom, listState.isScrollInProgress)) {
                MembersActivityOverviewNewTurnAction.FollowBottom -> listState.scrollToItem(lastIndex)
                MembersActivityOverviewNewTurnAction.ShowHint -> newTurnBelow = true
                MembersActivityOverviewNewTurnAction.None -> Unit
            }
            placed = true
        }
        // Replies and sent messages load after the first paint and grow their cards: keep a
        // reader who follows the bottom there.
        LaunchedEffect(state.agentId, state.replies.size) {
            if (placed && followingBottom && !listState.isScrollInProgress) listState.scrollToItem(lastIndex)
        }
    }
    RaftStorePullToRefreshBox(
        refreshing = state.refreshing,
        onRefresh = { binding.onIntent(AgentActivityOverviewIntent.Refresh) },
        modifier = modifier,
        enabled = loaded,
    ) {
    Box(Modifier.fillMaxSize()) {
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollUp = NestedScrollMode.SELF_FIRST, scrollDown = NestedScrollMode.SELF_FIRST),
        contentPadding = PaddingValues(horizontal = RaftAppSpacing.Md, vertical = RaftAppSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        if (!showTurns) {
            // With no turns the "now" card stays above loading and empty states: it is what shows
            // a live error (e.g. a usage limit).
            item(key = "activity-overview-now") {
                MembersAgentActivityNowCard(now = now, finished = finished, running = running, timeContext = timeContext, onOpenUrl = onOpenUrl)
            }
        }
        when {
            !loaded ->
                item(key = "activity-overview-loading") {
                    RaftCenteredLoading(
                        message = stringResource(Res.string.member_state_loading_activity),
                        modifier = Modifier.fillMaxWidth().padding(vertical = RaftAppSpacing.Lg),
                    )
                }
            !showTurns && !hasRawActivity ->
                item(key = "activity-overview-empty") {
                    MembersAgentActivityOverviewEmpty(
                        title = stringResource(Res.string.member_state_no_activity),
                        detail = stringResource(Res.string.member_state_activity_start_agent),
                    )
                }
            !showTurns ->
                item(key = "activity-overview-empty") {
                    MembersAgentActivityOverviewEmpty(
                        title = stringResource(Res.string.member_activity_overview_empty_title),
                        detail = stringResource(
                            if (hasComputer) Res.string.member_activity_overview_empty_upgrade_desc
                            else Res.string.member_activity_overview_empty_external_desc
                        ),
                    ) {
                        if (hasComputer && onOpenComputer != null) {
                            RaftButton(
                                text = stringResource(Res.string.member_activity_overview_empty_open_computer),
                                onClick = onOpenComputer,
                            )
                        }
                        RaftButton(
                            text = stringResource(Res.string.member_activity_overview_empty_show_raw),
                            onClick = onShowRaw,
                        )
                    }
                }
            else -> {
                if (state.next != null) {
                    item(key = "activity-overview-load-more") {
                        Box(Modifier.fillMaxWidth()) {
                            RaftButton(
                                text = stringResource(Res.string.member_activity_overview_load_more),
                                onClick = { binding.onIntent(AgentActivityOverviewIntent.LoadMore) },
                                loading = state.loadingMore,
                                enabled = !state.loadingMore,
                            )
                        }
                    }
                }
                items(chronological, key = { "activity-overview-turn-${it.key}" }) { turn ->
                    MembersAgentActivityTurnSection(
                        turn = turn,
                        replies = state.replies,
                        timeContext = timeContext,
                        onIntent = binding.onIntent,
                        onOpenUrl = onOpenUrl,
                        onOpenMessage = onOpenMessage,
                    )
                }
                running?.let { turn ->
                    item(key = "activity-overview-turn-${turn.key}") {
                        MembersAgentActivityTurnSection(
                            turn = turn,
                            replies = state.replies,
                            timeContext = timeContext,
                            onIntent = binding.onIntent,
                            onOpenUrl = onOpenUrl,
                            onOpenMessage = onOpenMessage,
                        )
                    }
                }
                item(key = "activity-overview-now") {
                    MembersAgentActivityNowCard(now = now, finished = finished, running = running, timeContext = timeContext, onOpenUrl = onOpenUrl)
                }
            }
        }
    }
    if (newTurnBelow && showTurns) {
        val scope = rememberCoroutineScope()
        RaftButton(
            text = stringResource(Res.string.member_activity_overview_new_turn),
            onClick = {
                // Direct user action: an explicit viewport replacement.
                followingBottom = true
                newTurnBelow = false
                scope.launch { listState.scrollToItem(lastIndex) }
            },
            variant = RaftButtonVariant.Primary,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = RaftAppSpacing.Md),
        )
    }
    }
    }
}

/** What a newly finished (or newly running) turn does to the view. */
internal enum class MembersActivityOverviewNewTurnAction { None, FollowBottom, ShowHint }

/**
 * Web AgentActivityOverview: the first load places the view at the bottom; later a new latest turn
 * follows the bottom only for a reader parked there and not scrolling; anyone else gets the hint.
 */
internal fun membersActivityOverviewNewTurnAction(
    placed: Boolean,
    followingBottom: Boolean,
    scrolling: Boolean,
): MembersActivityOverviewNewTurnAction =
    when {
        !placed -> MembersActivityOverviewNewTurnAction.FollowBottom
        followingBottom && !scrolling -> MembersActivityOverviewNewTurnAction.FollowBottom
        else -> MembersActivityOverviewNewTurnAction.ShowHint
    }

private fun LazyListState.isActivityOverviewAtBottom(lastIndex: Int): Boolean {
    if (!canScrollForward) return true
    return layoutInfo.visibleItemsInfo.any { item ->
        item.index == lastIndex && item.offset + item.size <= layoutInfo.viewportEndOffset + 4
    }
}

/**
 * The "now" card's headline: the live label, with the daemon's memory-limit texts in this locale
 * (web `getActivityTextDescriptor`, #10860).
 */
@Composable
private fun MembersAgentActivityNow.headline(): String {
    val detailKind = indicator?.detailKind
    // The server paused automatic wakes; a manual start lifts it (web `activity.status.wakeCrashLoopBlocked`).
    if (wakeCrashLoop != null || detailKind == AgentActivityDetailKindWakeCrashLoopBlocked) {
        return stringResource(Res.string.agent_activity_wake_crash_loop_blocked)
    }
    agentMemoryLimitExit(label, detailKind)?.let { exit ->
        return exit.memoryLimit?.let { stringResource(Res.string.agent_activity_memory_limit_stopped_with_limit, it) }
            ?: stringResource(Res.string.agent_activity_memory_limit_stopped)
    }
    if ((detailKind == null || detailKind == "system_message") && isAgentBackgroundOomActivityTitle(label)) {
        return stringResource(Res.string.agent_activity_background_oom)
    }
    // Same localized caption as the profile badge (raw free-text activity passes through).
    return indicator?.copy(label = label)?.localizedMemberStatusLabel() ?: label
}

/** [finished]: the finished turns shown, newest first; [running]: the turn still running, if any. */
@Composable
private fun MembersAgentActivityNowCard(
    now: MembersAgentActivityNow,
    finished: List<AgentActivityTurn>,
    running: AgentActivityTurn?,
    timeContext: RaftTimeContext,
    onOpenUrl: (String) -> Unit,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val shown = listOfNotNull(running) + finished
    RaftCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = RaftAppSpacing.Sm),
            horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            now.dotColor()?.let { RaftStatusDot(color = it, size = RaftStatusSize.Lg) }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                RaftText(
                    text = now.headline(),
                    style = typography.headline.copy(fontWeight = FontWeight.Bold),
                    color = colors.foreground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // Paused after repeated stops at the Computer's per-agent memory limit: why, and what
                // to do (web AgentStatusBadge, botiverse/slock #10860). Older servers send no reason.
                now.wakeCrashLoop?.takeIf { it.pausedAtMemoryLimit }?.let { pause ->
                    RaftText(
                        text = stringResource(Res.string.agent_activity_wake_crash_loop_memory_limit_detail, pause.earlyExitCount),
                        style = typography.callout,
                        color = colors.foregroundMuted,
                    )
                }
                val last = finished.firstOrNull()
                val line = when {
                    running != null -> {
                        val time = membersActivityOverviewClock(running.startedAtEpochMillis, timeContext)
                        membersActivityOverviewTriggerText(running.trigger.describe())
                            ?.let { stringResource(Res.string.member_activity_overview_now_current_turn_for, time, it) }
                            ?: stringResource(Res.string.member_activity_overview_now_current_turn, time)
                    }
                    last != null -> {
                        val time = membersActivityOverviewClock(last.endedAtEpochMillis, timeContext)
                        membersActivityOverviewTriggerText(last.trigger.describe())
                            ?.let { stringResource(Res.string.member_activity_overview_now_last_turn_for, time, it) }
                            ?: stringResource(Res.string.member_activity_overview_now_last_turn, time)
                    }
                    else -> null
                }
                line?.let {
                    MembersActivityOverviewRefText(text = it, style = typography.callout, color = colors.foregroundMuted, onOpenUrl = onOpenUrl)
                }
                if (shown.isNotEmpty()) {
                    val steps = shown.sumOf { it.toolCalls }
                    val problems = shown.sumOf { it.problemStepCount }
                    val parts = listOf(
                        if (shown.size == 1) stringResource(Res.string.member_activity_overview_now_turns_one)
                        else stringResource(Res.string.member_activity_overview_now_turns_other, shown.size),
                        if (steps == 1) stringResource(Res.string.member_activity_overview_now_steps_one)
                        else stringResource(Res.string.member_activity_overview_now_steps_other, steps),
                        when (problems) {
                            0 -> stringResource(Res.string.member_activity_overview_now_problems_none)
                            1 -> stringResource(Res.string.member_activity_overview_now_problems_one)
                            else -> stringResource(Res.string.member_activity_overview_now_problems_other, problems)
                        },
                    )
                    RaftText(text = parts.joinToString(" · "), style = typography.caption, color = colors.foregroundHint)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MembersAgentActivityTurnSection(
    turn: AgentActivityTurn,
    replies: Map<MessageId, AgentActivityReplyState>,
    timeContext: RaftTimeContext,
    onIntent: (AgentActivityOverviewIntent) -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenMessage: (String, MessageId) -> Unit,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    // A running turn has no end yet: its start time only.
    val range = if (turn.running) {
        membersActivityOverviewClock(turn.startedAtEpochMillis, timeContext) to null
    } else {
        membersActivityOverviewClockRange(turn.startedAtEpochMillis, turn.endedAtEpochMillis, timeContext)
    }
    val duration = membersActivityOverviewDuration(turn.endedAtEpochMillis - turn.startedAtEpochMillis)
    val trigger = membersActivityOverviewTriggerText(turn.trigger.describe())
    val triggerMessage = turn.trigger.openableMessage()
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            RaftText(
                text = range.second?.let { end ->
                    stringResource(Res.string.member_activity_overview_turn_range, range.first, end)
                } ?: range.first,
                style = typography.monoCode.copy(fontWeight = FontWeight.Bold),
                color = colors.foreground,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            // Badges sit right after the time so they stay on the first line (web).
            MembersAgentActivityTurnBadges(turn, Modifier.align(Alignment.CenterVertically))
            MembersActivityOverviewRefText(
                text = when {
                    trigger != null && turn.running ->
                        stringResource(Res.string.member_activity_overview_turn_trigger_and_running_for, trigger, duration)
                    trigger != null -> stringResource(Res.string.member_activity_overview_turn_trigger_and_duration, trigger, duration)
                    turn.running -> stringResource(Res.string.member_activity_overview_turn_running_for, duration)
                    else -> stringResource(Res.string.member_activity_overview_turn_took, duration)
                },
                style = typography.caption,
                color = colors.foregroundHint,
                onOpenUrl = onOpenUrl,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            // The exact waking message, only where the viewer may see it (web TriggerMessageLink).
            if (trigger != null && triggerMessage != null) {
                RaftTextLink(
                    text = stringResource(Res.string.member_activity_overview_trigger_open),
                    onClick = { onOpenMessage(triggerMessage.first, triggerMessage.second) },
                    variant = RaftTextLinkVariant.Strong,
                    style = typography.caption,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
        }
        RaftText(
            text = membersActivityOverviewActionsText(turn.actions, turn.messagesSent),
            style = typography.callout,
            color = colors.foregroundMuted,
        )
        turn.problems.forEach { problem ->
            if (!problem.isRoutineHold) MembersAgentActivityProblemLine(problem, timeContext)
        }
        if (turn.problemCount > turn.problems.size && turn.problems.any { !it.isRoutineHold }) {
            RaftText(
                text = stringResource(Res.string.member_activity_overview_problem_more, turn.problemCount - turn.problems.size),
                style = typography.caption,
                color = colors.foregroundHint,
            )
        }
        // What the turn received, then what it sent: two lists, each in time order (web).
        if (turn.appEvents.isNotEmpty()) {
            MembersAgentActivityAppEvents(turn.appEvents, turn.appEventCount ?: turn.appEvents.size, timeContext)
        }
        val sentIds = turn.sentMessageIds.orEmpty()
        when {
            sentIds.isNotEmpty() || turn.hiddenSentCount > 0 ->
                MembersAgentActivitySentMessages(
                    ids = sentIds,
                    visible = turn.sentMessageCount ?: sentIds.size,
                    hidden = turn.hiddenSentCount,
                    replies = replies,
                    timeContext = timeContext,
                    onIntent = onIntent,
                    onOpenMessage = onOpenMessage,
                )
            turn.lastReplyMessageId != null -> {
                val messageId = turn.lastReplyMessageId
                // Idempotent: the store loads each reply id once (a failed one again on the next view).
                LaunchedEffect(messageId) { onIntent(AgentActivityOverviewIntent.LoadReply(messageId)) }
                MembersAgentActivityLastReply(
                    state = replies[messageId] ?: AgentActivityReplyState.Loading,
                    count = turn.messagesSent,
                    timeContext = timeContext,
                )
            }
        }
    }
}

/**
 * Only a turn that needs a look gets a badge (web TurnBadges): the running turn, steps that went
 * wrong, an error ending, a stop or an unknown ending; a turn that sent nothing gets a muted one.
 */
@Composable
private fun MembersAgentActivityTurnBadges(turn: AgentActivityTurn, modifier: Modifier = Modifier) {
    if (turn.running) {
        RaftBadge(
            text = stringResource(Res.string.member_activity_overview_outcome_running),
            appearance = RaftBadgeAppearance.Soft,
            variant = RaftBadgeVariant.Busy,
            icon = { RaftStatusDot(presence = RaftPresence.Busy, size = RaftStatusSize.Sm) },
            modifier = modifier,
        )
        return
    }
    val problems = turn.problemStepCount
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Xs)) {
        when {
            turn.outcome == AgentActivityTurnOutcome.Completed && problems > 0 ->
                RaftBadge(
                    text = if (problems == 1) stringResource(Res.string.member_activity_overview_outcome_problems_one)
                    else stringResource(Res.string.member_activity_overview_outcome_problems_other, problems),
                    appearance = RaftBadgeAppearance.Soft,
                    variant = RaftBadgeVariant.Warning,
                    icon = { RaftStatusDot(presence = RaftPresence.Error, size = RaftStatusSize.Sm) },
                )
            turn.outcome == AgentActivityTurnOutcome.Failed ->
                RaftBadge(
                    text = stringResource(Res.string.member_activity_overview_outcome_failed),
                    appearance = RaftBadgeAppearance.Soft,
                    variant = RaftBadgeVariant.Warning,
                    icon = { RaftStatusDot(presence = RaftPresence.Error, size = RaftStatusSize.Sm) },
                )
            turn.outcome != AgentActivityTurnOutcome.Completed ->
                RaftBadge(
                    text = stringResource(
                        if (turn.outcome == AgentActivityTurnOutcome.Interrupted) Res.string.member_activity_overview_outcome_interrupted
                        else Res.string.member_activity_overview_outcome_unknown
                    ),
                    appearance = RaftBadgeAppearance.Soft,
                    variant = RaftBadgeVariant.Muted,
                    icon = { RaftStatusDot(presence = RaftPresence.Offline, size = RaftStatusSize.Sm) },
                )
        }
        if (turn.lastReplyMessageId == null) {
            RaftBadge(
                text = stringResource(Res.string.member_activity_overview_outcome_no_message),
                appearance = RaftBadgeAppearance.Soft,
                variant = RaftBadgeVariant.Muted,
            )
        }
    }
}

@Composable
private fun MembersAgentActivityProblemLine(problem: AgentActivityTurnProblem, timeContext: RaftTimeContext) {
    val colors = RaftTheme.colors
    val time = membersActivityOverviewClock(problem.atEpochMillis, timeContext)
    val exitNonzero = problem.reason == AgentActivityProblemReason.ExitNonzero
    // A test run that reports failures is ordinary work, not an agent error: lighter wording.
    val text = when {
        exitNonzero && problem.kind == AgentActivityActionKind.RunChecks ->
            stringResource(Res.string.member_activity_overview_problem_checks_failed, time)
        exitNonzero ->
            stringResource(Res.string.member_activity_overview_problem_exit_nonzero, time, problem.exitCode?.toString().orEmpty())
        else -> stringResource(Res.string.member_activity_overview_problem_call_failed, time)
    }
    val icon = when {
        problem.countsAsProblem -> RaftLucideIconName.TriangleAlert
        problem.kind == AgentActivityActionKind.RunChecks -> RaftLucideIconName.FlaskConical
        else -> RaftLucideIconName.SquareTerminal
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        // A non-zero exit is process information: a hint-ink icon, not a warning (web).
        RaftLucideIcon(
            icon,
            modifier = Modifier.size(14.dp),
            color = raftArgbHex(if (problem.countsAsProblem) colors.warningStrong else colors.foregroundHint),
        )
        RaftText(text = text, style = RaftTheme.typography.caption, color = colors.foregroundMuted)
    }
}

/** A message the agent wrote, as web InlineMarkdownPreview shows it: inline marks, three lines. */
@Composable
private fun MembersActivityOverviewMessageText(content: String) {
    val colors = RaftTheme.colors
    val preview = remember(content) { markdownToPreview(content) }
    RaftText(
        text = preview.toAnnotatedString(colors.foregroundLink),
        style = RaftTheme.typography.callout,
        color = colors.foreground,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun MembersAgentActivityLastReply(
    state: AgentActivityReplyState,
    count: Int,
    timeContext: RaftTimeContext,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val reply = (state as? AgentActivityReplyState.Ready)?.reply
    val loadedReply = reply as? AgentActivityTurnReply.Loaded
    val loading = state == AgentActivityReplyState.Loading
    val label = loadedReply?.createdAtEpochMillis?.let { createdAt ->
        stringResource(Res.string.member_activity_overview_reply_label_at, membersActivityOverviewClock(createdAt, timeContext))
    } ?: stringResource(Res.string.member_activity_overview_reply_label)
    RaftCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                RaftLucideIcon(
                    RaftLucideIconName.MessageSquare,
                    modifier = Modifier.size(14.dp),
                    color = raftArgbHex(colors.foregroundHint),
                )
                RaftText(
                    text = if (count > 1) {
                        "$label ${stringResource(Res.string.member_activity_overview_reply_count, count)}"
                    } else {
                        label
                    },
                    style = typography.caption,
                    color = colors.foregroundHint,
                )
            }
            if (loadedReply != null) {
                MembersActivityOverviewMessageText(loadedReply.content)
            } else {
                RaftText(
                    text = stringResource(
                        if (loading) Res.string.member_activity_overview_reply_loading
                        else Res.string.member_activity_overview_reply_unavailable
                    ),
                    style = typography.callout,
                    color = colors.foreground,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** How many sent messages show before "Show N More" (web SENT_MESSAGES_SHOWN). */
private const val SentMessagesShown = 3

/** Every message the turn sent (web SentMessages): the first few shown, the rest a tap away. */
@Composable
private fun MembersAgentActivitySentMessages(
    ids: List<MessageId>,
    visible: Int,
    hidden: Int,
    replies: Map<MessageId, AgentActivityReplyState>,
    timeContext: RaftTimeContext,
    onIntent: (AgentActivityOverviewIntent) -> Unit,
    onOpenMessage: (String, MessageId) -> Unit,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    var expanded by remember(ids) { mutableStateOf(false) }
    val shown = if (expanded) ids else ids.take(SentMessagesShown)
    Column(verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm)) {
        val total = visible + hidden
        RaftText(
            text = if (total == 1) stringResource(Res.string.member_activity_overview_sent_title_one)
            else stringResource(Res.string.member_activity_overview_sent_title_other, total),
            style = typography.caption,
            color = colors.foregroundHint,
        )
        shown.forEach { id ->
            LaunchedEffect(id) { onIntent(AgentActivityOverviewIntent.LoadReply(id)) }
            MembersAgentActivitySentMessageCard(id, replies[id] ?: AgentActivityReplyState.Loading, timeContext, onOpenMessage)
        }
        if (!expanded && ids.size > SentMessagesShown) {
            RaftTextLink(
                text = stringResource(Res.string.member_activity_overview_sent_more, ids.size - SentMessagesShown),
                onClick = { expanded = true },
                style = typography.caption,
            )
        }
        if (visible > ids.size) {
            RaftText(
                text = stringResource(Res.string.member_activity_overview_sent_unlisted, visible - ids.size),
                style = typography.caption,
                color = colors.foregroundHint,
            )
        }
        if (hidden > 0) {
            RaftText(
                text = if (hidden == 1) stringResource(Res.string.member_activity_overview_sent_hidden_one)
                else stringResource(Res.string.member_activity_overview_sent_hidden_other, hidden),
                style = typography.caption,
                color = colors.foregroundHint,
            )
        }
    }
}

/** One message the turn sent, read by id under its own permissions, with a way to it (web SentMessageCard). */
@Composable
private fun MembersAgentActivitySentMessageCard(
    messageId: MessageId,
    state: AgentActivityReplyState,
    timeContext: RaftTimeContext,
    onOpenMessage: (String, MessageId) -> Unit,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val loaded = ((state as? AgentActivityReplyState.Ready)?.reply) as? AgentActivityTurnReply.Loaded
    RaftCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                RaftLucideIcon(RaftLucideIconName.MessageSquare, modifier = Modifier.size(14.dp), color = raftArgbHex(colors.foregroundHint))
                RaftText(
                    text = loaded?.createdAtEpochMillis?.let { membersActivityOverviewClock(it, timeContext) }
                        ?: stringResource(Res.string.member_activity_overview_reply_label),
                    style = typography.caption,
                    color = colors.foregroundHint,
                    modifier = Modifier.weight(1f),
                )
                val channelId = loaded?.channelId
                if (channelId != null) {
                    RaftTextLink(
                        text = stringResource(Res.string.member_activity_overview_sent_open),
                        onClick = { onOpenMessage(channelId, messageId) },
                        style = typography.caption,
                    )
                }
            }
            if (loaded != null) {
                MembersActivityOverviewMessageText(loaded.content)
            } else {
                RaftText(
                    text = stringResource(
                        if (state == AgentActivityReplyState.Loading) Res.string.member_activity_overview_reply_loading
                        else Res.string.member_activity_overview_reply_unavailable
                    ),
                    style = typography.callout,
                    color = colors.foreground,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** How many app events show before "Show N More" (web APP_EVENTS_SHOWN). */
private const val AppEventsShown = 5

/**
 * The app notifications delivered in the turn (web AppEvents, #10830), oldest first: compact list
 * rows, not message cards, so what the agent received never reads as something it sent. Rows link
 * nowhere: the Apps tab has no way to open one event.
 */
@Composable
private fun MembersAgentActivityAppEvents(events: List<AgentActivityAppEvent>, count: Int, timeContext: RaftTimeContext) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    var expanded by remember(events) { mutableStateOf(false) }
    val shown = if (expanded) events else events.take(AppEventsShown)
    Column(verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm)) {
        RaftText(
            text = if (count == 1) stringResource(Res.string.member_activity_overview_app_events_title_one)
            else stringResource(Res.string.member_activity_overview_app_events_title_other, count),
            style = typography.caption,
            color = colors.foregroundHint,
        )
        RaftListRowGroup(modifier = Modifier.fillMaxWidth()) {
            shown.forEachIndexed { index, event ->
                RaftListRow(
                    title = event.appName,
                    variant = RaftListRowVariant.Plain,
                    divider = index > 0,
                    meta = membersActivityOverviewClock(event.deliveredAtEpochMillis, timeContext),
                    description = event.summary,
                    wrapDescription = true,
                    leading = { MembersAgentActivityAppLogo(event) },
                )
            }
        }
        if (!expanded && events.size > AppEventsShown) {
            RaftTextLink(
                text = stringResource(Res.string.member_activity_overview_app_events_more, events.size - AppEventsShown),
                onClick = { expanded = true },
                style = typography.caption,
            )
        }
        if (count > events.size) {
            RaftText(
                text = stringResource(Res.string.member_activity_overview_app_events_unlisted, count - events.size),
                style = typography.caption,
                color = colors.foregroundHint,
            )
        }
    }
}

/** The app's logo (web ConnectedAppLogo `xs`), or the generic app tile when it has none. */
@Composable
private fun MembersAgentActivityAppLogo(event: AgentActivityAppEvent) {
    val logoUrl = event.logoUrl
    if (logoUrl == null) {
        RaftIconTile(size = RaftIconTileSize.Sm) {
            RaftContentLucideIcon(RaftLucideIconName.AppWindow, size = RaftIconTileSize.Sm.icon)
        }
        return
    }
    RaftConnectedAppLogo(name = event.appName.ifBlank { event.clientKey }, logoUrl = logoUrl)
}

@Composable
private fun MembersAgentActivityOverviewEmpty(
    title: String,
    detail: String,
    actions: (@Composable () -> Unit)? = null,
) {
    val colors = RaftTheme.colors
    Box(Modifier.fillMaxWidth().padding(vertical = RaftAppSpacing.Xl), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 320.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Md),
        ) {
            RaftLucideIcon(
                RaftLucideIconName.Activity,
                modifier = Modifier.size(36.dp),
                color = raftArgbHex(colors.foregroundHint),
            )
            RaftText(
                text = title,
                style = RaftTheme.typography.headline.copy(fontWeight = FontWeight.Bold),
                color = colors.foreground,
                textAlign = TextAlign.Center,
            )
            RaftText(
                text = detail,
                style = RaftTheme.typography.callout,
                color = colors.foregroundMuted,
                textAlign = TextAlign.Center,
            )
            if (actions != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm)) { actions() }
            }
        }
    }
}

/** Trigger sentences carry `#channel` refs: render them as links, as the raw log does (web RefText). */
@Composable
private fun MembersActivityOverviewRefText(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    color: Color,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    RaftMarkdownBody(
        body = text,
        textStyle = style.copy(color = color),
        onOpenUrl = onOpenUrl,
        modifier = modifier,
    )
}

@Composable
private fun membersActivityOverviewTriggerText(description: AgentActivityTriggerDescription?): String? =
    when (description) {
        null -> null
        AgentActivityTriggerDescription.HiddenDm -> stringResource(Res.string.member_activity_overview_trigger_dm)
        AgentActivityTriggerDescription.HiddenPrivateChannel ->
            stringResource(Res.string.member_activity_overview_trigger_private_channel)
        AgentActivityTriggerDescription.Unresolved -> stringResource(Res.string.member_activity_overview_trigger_unresolved)
        AgentActivityTriggerDescription.Dm -> stringResource(Res.string.member_activity_overview_trigger_dm)
        AgentActivityTriggerDescription.AppEvent -> stringResource(Res.string.member_activity_overview_trigger_app_event)
        is AgentActivityTriggerDescription.AppEventFrom ->
            stringResource(Res.string.member_activity_overview_trigger_app_event_from, description.app, description.summary)
        is AgentActivityTriggerDescription.DmFrom ->
            stringResource(Res.string.member_activity_overview_trigger_dm_from, description.peer)
        is AgentActivityTriggerDescription.Target ->
            stringResource(Res.string.member_activity_overview_trigger_target, description.channel)
        is AgentActivityTriggerDescription.Message ->
            stringResource(Res.string.member_activity_overview_trigger_message, description.sender, description.channel)
        is AgentActivityTriggerDescription.Thread ->
            stringResource(Res.string.member_activity_overview_trigger_thread, description.sender, description.channel)
    }

@Composable
private fun membersActivityOverviewActionsText(actions: List<AgentActivityActionCount>, messagesSent: Int): String {
    if (actions.isEmpty()) return stringResource(Res.string.member_activity_overview_actions_none)
    // One send command can post several messages (web): count what was sent, not the commands.
    val parts = actions.map {
        membersActivityOverviewActionText(
            if (it.kind == AgentActivityActionKind.SendMessage && messagesSent > 0) it.copy(count = messagesSent) else it
        )
    }
    val joinTemplate = stringResource(Res.string.member_activity_overview_actions_join, "%1\$s", "%2\$s")
    val lastTemplate = stringResource(Res.string.member_activity_overview_actions_join_last, "%1\$s", "%2\$s")
    val pairTemplate = stringResource(Res.string.member_activity_overview_actions_join_pair, "%1\$s", "%2\$s")
    return membersActivityOverviewJoinList(parts, joinTemplate, lastTemplate, pairTemplate)
}

/** Intl conjunction list from localized templates (`%1$s` / `%2$s`): "a", "a and b", "a, b, and c". */
internal fun membersActivityOverviewJoinList(
    parts: List<String>,
    joinTemplate: String,
    lastTemplate: String,
    pairTemplate: String,
): String {
    fun String.fill(a: String, b: String) = replace("%1\$s", a).replace("%2\$s", b)
    return when (parts.size) {
        0 -> ""
        1 -> parts[0]
        2 -> pairTemplate.fill(parts[0], parts[1])
        else -> {
            val head = parts.dropLast(1).reduce { acc, part -> joinTemplate.fill(acc, part) }
            lastTemplate.fill(head, parts.last())
        }
    }
}

@Composable
private fun membersActivityOverviewActionText(action: AgentActivityActionCount): String {
    val one = action.count == 1
    val count = action.count
    return when (action.kind) {
        AgentActivityActionKind.RunCommand ->
            if (one) stringResource(Res.string.member_activity_overview_action_run_command_one)
            else stringResource(Res.string.member_activity_overview_action_run_command_other, count)
        AgentActivityActionKind.ReadFile ->
            if (one) stringResource(Res.string.member_activity_overview_action_read_file_one)
            else stringResource(Res.string.member_activity_overview_action_read_file_other, count)
        AgentActivityActionKind.EditFile ->
            if (one) stringResource(Res.string.member_activity_overview_action_edit_file_one)
            else stringResource(Res.string.member_activity_overview_action_edit_file_other, count)
        AgentActivityActionKind.Search ->
            if (one) stringResource(Res.string.member_activity_overview_action_search_one)
            else stringResource(Res.string.member_activity_overview_action_search_other, count)
        AgentActivityActionKind.Web ->
            if (one) stringResource(Res.string.member_activity_overview_action_web_one)
            else stringResource(Res.string.member_activity_overview_action_web_other, count)
        AgentActivityActionKind.Fetch ->
            if (one) stringResource(Res.string.member_activity_overview_action_fetch_one)
            else stringResource(Res.string.member_activity_overview_action_fetch_other, count)
        AgentActivityActionKind.Subagent ->
            if (one) stringResource(Res.string.member_activity_overview_action_subagent_one)
            else stringResource(Res.string.member_activity_overview_action_subagent_other, count)
        AgentActivityActionKind.Mcp ->
            if (one) stringResource(Res.string.member_activity_overview_action_mcp_one)
            else stringResource(Res.string.member_activity_overview_action_mcp_other, count)
        AgentActivityActionKind.Wait ->
            if (one) stringResource(Res.string.member_activity_overview_action_wait_one)
            else stringResource(Res.string.member_activity_overview_action_wait_other, count)
        AgentActivityActionKind.Other ->
            if (one) stringResource(Res.string.member_activity_overview_action_other_one)
            else stringResource(Res.string.member_activity_overview_action_other_other, count)
        AgentActivityActionKind.SendMessage ->
            if (one) stringResource(Res.string.member_activity_overview_action_send_message_one)
            else stringResource(Res.string.member_activity_overview_action_send_message_other, count)
        AgentActivityActionKind.ReadMessages ->
            if (one) stringResource(Res.string.member_activity_overview_action_read_messages_one)
            else stringResource(Res.string.member_activity_overview_action_read_messages_other, count)
        AgentActivityActionKind.RaftAction ->
            if (one) stringResource(Res.string.member_activity_overview_action_raft_action_one)
            else stringResource(Res.string.member_activity_overview_action_raft_action_other, count)
        AgentActivityActionKind.VersionControl ->
            if (one) stringResource(Res.string.member_activity_overview_action_version_control_one)
            else stringResource(Res.string.member_activity_overview_action_version_control_other, count)
        AgentActivityActionKind.RunChecks ->
            if (one) stringResource(Res.string.member_activity_overview_action_run_checks_one)
            else stringResource(Res.string.member_activity_overview_action_run_checks_other, count)
    }
}

/** Web `formatTurnDuration`: at least 1s; "41s", "1m 41s", "2h 5m". */
internal data class MembersActivityOverviewDurationParts(val hours: Int, val minutes: Int, val seconds: Int)

internal fun membersActivityOverviewDurationParts(millis: Long): MembersActivityOverviewDurationParts {
    val totalSeconds = maxOf(1L, (millis + 500L).floorDiv(1000L))
    val totalMinutes = totalSeconds / 60
    return MembersActivityOverviewDurationParts(
        hours = (totalMinutes / 60).toInt(),
        minutes = (totalMinutes % 60).toInt(),
        seconds = (totalSeconds % 60).toInt(),
    )
}

@Composable
private fun membersActivityOverviewDuration(millis: Long): String {
    val parts = membersActivityOverviewDurationParts(millis)
    return when {
        parts.hours > 0 -> stringResource(Res.string.member_activity_overview_duration_hours, parts.hours, parts.minutes)
        parts.minutes > 0 -> stringResource(Res.string.member_activity_overview_duration_minutes, parts.minutes, parts.seconds)
        else -> stringResource(Res.string.member_activity_overview_duration_seconds, parts.seconds)
    }
}

private fun RaftTimeContext.localEpochDay(epochMillis: Long): Long =
    (epochMillis + localOffsetMinutes * MillisPerMinute).floorDiv(MillisPerDay)

/** A clock time; turns from another day get their date (`MM-dd HH:mm`). */
internal fun membersActivityOverviewClock(epochMillis: Long, timeContext: RaftTimeContext): String {
    val today = timeContext.todayEpochDay
        ?: timeContext.nowEpochMillis?.let(timeContext::localEpochDay)
        ?: timeContext.localEpochDay(epochMillis)
    return formatRaftTimestampEpochMillis(epochMillis, timeContext.localOffsetMinutes, today, timeContext.timeFormat)
}

/**
 * Web `formatClockRange`: one time when both ends read the same; otherwise start and end, the end
 * without the date when the turn stayed on one day, and a shared " AM"/" PM" written once.
 */
internal fun membersActivityOverviewClockRange(
    startMillis: Long,
    endMillis: Long,
    timeContext: RaftTimeContext,
): Pair<String, String?> {
    val start = membersActivityOverviewClock(startMillis, timeContext)
    val sameDay = timeContext.localEpochDay(startMillis) == timeContext.localEpochDay(endMillis)
    val end = if (sameDay) {
        formatRaftTimestampEpochMillis(
            endMillis,
            timeContext.localOffsetMinutes,
            timeContext.localEpochDay(endMillis),
            timeContext.timeFormat,
        )
    } else {
        membersActivityOverviewClock(endMillis, timeContext)
    }
    if (start == end || (sameDay && start.endsWith(end))) return start to null
    val suffix = Regex("\\D+$").find(start)?.value?.takeIf { it.isNotBlank() && it.trim().isNotEmpty() }
    if (suffix != null && end.endsWith(suffix)) return start.dropLast(suffix.length) to end
    return start to end
}
