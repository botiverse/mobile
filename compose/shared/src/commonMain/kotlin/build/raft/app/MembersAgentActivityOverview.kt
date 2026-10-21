package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import build.raft.app.compat.ui.NestedScrollMode
import build.raft.app.compat.ui.nestedScroll
import build.raft.app.data.AgentActivityActionCount
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
import build.raft.app.model.MillisPerDay
import build.raft.app.model.MillisPerMinute
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.formatRaftTimestampEpochMillis
import build.raft.app.resources.MR
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
import com.tencent.tmm.kmmresource.compose.stringResource
import kotlinx.coroutines.flow.StateFlow

/*
 * Agent Activity tab "Overview" (web packages/web/src/components/agent/AgentActivityOverview.tsx,
 * behind `activity_overview_web_v0`): what the agent is doing now, then one block per finished
 * turn, newest first. Detail stays in the raw log, one segment away.
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
    val groupLabel = stringResource(MR.strings.member_activity_overview_view_label)
    RaftSegmentedControl(modifier = modifier.semantics { contentDescription = groupLabel }) {
        RaftSegment(
            text = stringResource(MR.strings.member_activity_overview_view_overview),
            selected = view == MembersAgentActivityView.Overview,
            onClick = { onViewChange(MembersAgentActivityView.Overview) },
        )
        RaftSegment(
            text = stringResource(MR.strings.member_activity_overview_view_raw),
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
)

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
) {
    val state by binding.state.collectAsState()
    val status = state.status
    if (status is AgentActivityOverviewStatus.Error) {
        RaftEmptyState(
            title = stringResource(MR.strings.member_activity_overview_load_error),
            iconName = RaftLucideIconName.Activity,
            actionLabel = stringResource(MR.strings.common_retry),
            onAction = { binding.onIntent(AgentActivityOverviewIntent.Refresh) },
            modifier = modifier,
        )
        return
    }
    val loaded = status == AgentActivityOverviewStatus.Loaded
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .nestedScroll(scrollUp = NestedScrollMode.SELF_FIRST, scrollDown = NestedScrollMode.SELF_FIRST),
        contentPadding = PaddingValues(horizontal = RaftAppSpacing.Md, vertical = RaftAppSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        // The "now" card stays above loading and empty states: it is what shows a live error.
        item(key = "activity-overview-now") {
            MembersAgentActivityNowCard(now = now, turns = state.turns, timeContext = timeContext, onOpenUrl = onOpenUrl)
        }
        when {
            !loaded ->
                item(key = "activity-overview-loading") {
                    RaftCenteredLoading(
                        message = stringResource(MR.strings.member_state_loading_activity),
                        modifier = Modifier.fillMaxWidth().padding(vertical = RaftAppSpacing.Lg),
                    )
                }
            state.turns.isEmpty() && !hasRawActivity ->
                item(key = "activity-overview-empty") {
                    MembersAgentActivityOverviewEmpty(
                        title = stringResource(MR.strings.member_state_no_activity),
                        detail = stringResource(MR.strings.member_state_activity_start_agent),
                    )
                }
            state.turns.isEmpty() ->
                item(key = "activity-overview-empty") {
                    MembersAgentActivityOverviewEmpty(
                        title = stringResource(MR.strings.member_activity_overview_empty_title),
                        detail = stringResource(
                            if (hasComputer) MR.strings.member_activity_overview_empty_upgrade_desc
                            else MR.strings.member_activity_overview_empty_external_desc
                        ),
                    ) {
                        if (hasComputer && onOpenComputer != null) {
                            RaftButton(
                                text = stringResource(MR.strings.member_activity_overview_empty_open_computer),
                                onClick = onOpenComputer,
                            )
                        }
                        RaftButton(
                            text = stringResource(MR.strings.member_activity_overview_empty_show_raw),
                            onClick = onShowRaw,
                        )
                    }
                }
            else -> {
                items(state.turns, key = { "activity-overview-turn-${it.key}" }) { turn ->
                    MembersAgentActivityTurnSection(
                        turn = turn,
                        replies = state.replies,
                        timeContext = timeContext,
                        onIntent = binding.onIntent,
                        onOpenUrl = onOpenUrl,
                    )
                }
                if (state.next != null) {
                    item(key = "activity-overview-load-more") {
                        Box(Modifier.fillMaxWidth()) {
                            RaftButton(
                                text = stringResource(MR.strings.member_activity_overview_load_more),
                                onClick = { binding.onIntent(AgentActivityOverviewIntent.LoadMore) },
                                loading = state.loadingMore,
                                enabled = !state.loadingMore,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MembersAgentActivityNowCard(
    now: MembersAgentActivityNow,
    turns: List<AgentActivityTurn>,
    timeContext: RaftTimeContext,
    onOpenUrl: (String) -> Unit,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    RaftCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = RaftAppSpacing.Sm),
            horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            now.dotColor()?.let { RaftStatusDot(color = it, size = RaftStatusSize.Lg) }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                RaftText(
                    // Same localized caption as the profile badge (raw free-text activity passes through).
                    text = now.indicator?.copy(label = now.label)?.localizedMemberStatusLabel() ?: now.label,
                    style = typography.headline.copy(fontWeight = FontWeight.Bold),
                    color = colors.foreground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val last = turns.firstOrNull()
                if (last != null) {
                    val time = membersActivityOverviewClock(last.endedAtEpochMillis, timeContext)
                    val trigger = membersActivityOverviewTriggerText(last.trigger.describe())
                    MembersActivityOverviewRefText(
                        text = if (trigger != null) {
                            stringResource(MR.strings.member_activity_overview_now_last_turn_for, time, trigger)
                        } else {
                            stringResource(MR.strings.member_activity_overview_now_last_turn, time)
                        },
                        style = typography.callout,
                        color = colors.foregroundMuted,
                        onOpenUrl = onOpenUrl,
                    )
                }
                if (turns.isNotEmpty()) {
                    val steps = turns.sumOf { it.toolCalls }
                    val problems = turns.sumOf { it.problemStepCount }
                    val parts = listOf(
                        if (turns.size == 1) stringResource(MR.strings.member_activity_overview_now_turns_one)
                        else stringResource(MR.strings.member_activity_overview_now_turns_other, turns.size),
                        if (steps == 1) stringResource(MR.strings.member_activity_overview_now_steps_one)
                        else stringResource(MR.strings.member_activity_overview_now_steps_other, steps),
                        when (problems) {
                            0 -> stringResource(MR.strings.member_activity_overview_now_problems_none)
                            1 -> stringResource(MR.strings.member_activity_overview_now_problems_one)
                            else -> stringResource(MR.strings.member_activity_overview_now_problems_other, problems)
                        },
                    )
                    RaftText(
                        text = parts.joinToString(" · "),
                        style = typography.caption,
                        color = colors.foregroundHint,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MembersAgentActivityTurnSection(
    turn: AgentActivityTurn,
    replies: Map<build.raft.app.model.MessageId, AgentActivityReplyState>,
    timeContext: RaftTimeContext,
    onIntent: (AgentActivityOverviewIntent) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val range = membersActivityOverviewClockRange(turn.startedAtEpochMillis, turn.endedAtEpochMillis, timeContext)
    val duration = membersActivityOverviewDuration(turn.endedAtEpochMillis - turn.startedAtEpochMillis)
    val trigger = membersActivityOverviewTriggerText(turn.trigger.describe())
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            RaftText(
                text = range.second?.let { end ->
                    stringResource(MR.strings.member_activity_overview_turn_range, range.first, end)
                } ?: range.first,
                style = typography.monoCode.copy(fontWeight = FontWeight.Bold),
                color = colors.foreground,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            // Badges sit right after the time so they stay on the first line (web).
            MembersAgentActivityTurnBadges(turn, Modifier.align(Alignment.CenterVertically))
            MembersActivityOverviewRefText(
                text = if (trigger != null) {
                    stringResource(MR.strings.member_activity_overview_turn_trigger_and_duration, trigger, duration)
                } else {
                    stringResource(MR.strings.member_activity_overview_turn_took, duration)
                },
                style = typography.caption,
                color = colors.foregroundHint,
                onOpenUrl = onOpenUrl,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }
        RaftText(
            text = membersActivityOverviewActionsText(turn.actions),
            style = typography.callout,
            color = colors.foregroundMuted,
        )
        turn.problems.forEach { problem -> MembersAgentActivityProblemLine(problem, timeContext) }
        if (turn.problemCount > turn.problems.size) {
            RaftText(
                text = stringResource(MR.strings.member_activity_overview_problem_more, turn.problemCount - turn.problems.size),
                style = typography.caption,
                color = colors.foregroundHint,
            )
        }
        turn.lastReplyMessageId?.let { messageId ->
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

/**
 * Only a turn that needs a look gets a badge (web TurnBadges): steps that went wrong, an error
 * ending, a stop or an unknown ending; a turn that sent nothing gets a muted one.
 */
@Composable
private fun MembersAgentActivityTurnBadges(turn: AgentActivityTurn, modifier: Modifier = Modifier) {
    val problems = turn.problemStepCount
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Xs)) {
        when {
            turn.outcome == AgentActivityTurnOutcome.Completed && problems > 0 ->
                RaftBadge(
                    text = if (problems == 1) stringResource(MR.strings.member_activity_overview_outcome_problems_one)
                    else stringResource(MR.strings.member_activity_overview_outcome_problems_other, problems),
                    appearance = RaftBadgeAppearance.Soft,
                    variant = RaftBadgeVariant.Warning,
                    icon = { RaftStatusDot(presence = RaftPresence.Error, size = RaftStatusSize.Sm) },
                )
            turn.outcome == AgentActivityTurnOutcome.Failed ->
                RaftBadge(
                    text = stringResource(MR.strings.member_activity_overview_outcome_failed),
                    appearance = RaftBadgeAppearance.Soft,
                    variant = RaftBadgeVariant.Warning,
                    icon = { RaftStatusDot(presence = RaftPresence.Error, size = RaftStatusSize.Sm) },
                )
            turn.outcome != AgentActivityTurnOutcome.Completed ->
                RaftBadge(
                    text = stringResource(
                        if (turn.outcome == AgentActivityTurnOutcome.Interrupted) MR.strings.member_activity_overview_outcome_interrupted
                        else MR.strings.member_activity_overview_outcome_unknown
                    ),
                    appearance = RaftBadgeAppearance.Soft,
                    variant = RaftBadgeVariant.Muted,
                    icon = { RaftStatusDot(presence = RaftPresence.Offline, size = RaftStatusSize.Sm) },
                )
        }
        if (turn.lastReplyMessageId == null) {
            RaftBadge(
                text = stringResource(MR.strings.member_activity_overview_outcome_no_message),
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
    val text = when {
        problem.reason == AgentActivityProblemReason.SendHeld ->
            stringResource(MR.strings.member_activity_overview_problem_send_held, time)
        !problem.countsAsProblem ->
            stringResource(MR.strings.member_activity_overview_problem_checks_failed, time)
        problem.reason == AgentActivityProblemReason.ExitNonzero ->
            stringResource(MR.strings.member_activity_overview_problem_exit_nonzero, time, problem.exitCode?.toString().orEmpty())
        else -> stringResource(MR.strings.member_activity_overview_problem_call_failed, time)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        // A test run that reports failures is ordinary work: a hint-ink check icon, not a warning.
        RaftLucideIcon(
            if (problem.countsAsProblem) RaftLucideIconName.TriangleAlert else RaftLucideIconName.ClipboardCheck,
            modifier = Modifier.size(14.dp),
            color = raftArgbHex(if (problem.countsAsProblem) colors.warningStrong else colors.foregroundHint),
        )
        RaftText(text = text, style = RaftTheme.typography.caption, color = colors.foregroundMuted)
    }
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
        stringResource(MR.strings.member_activity_overview_reply_label_at, membersActivityOverviewClock(createdAt, timeContext))
    } ?: stringResource(MR.strings.member_activity_overview_reply_label)
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
                        "$label ${stringResource(MR.strings.member_activity_overview_reply_count, count)}"
                    } else {
                        label
                    },
                    style = typography.caption,
                    color = colors.foregroundHint,
                )
            }
            RaftText(
                text = when {
                    loadedReply != null -> loadedReply.content
                    loading -> stringResource(MR.strings.member_activity_overview_reply_loading)
                    else -> stringResource(MR.strings.member_activity_overview_reply_unavailable)
                },
                style = typography.callout,
                color = colors.foreground,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
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
        AgentActivityTriggerDescription.HiddenDm -> stringResource(MR.strings.member_activity_overview_trigger_dm)
        AgentActivityTriggerDescription.HiddenPrivateChannel ->
            stringResource(MR.strings.member_activity_overview_trigger_private_channel)
        AgentActivityTriggerDescription.AppEvent -> stringResource(MR.strings.member_activity_overview_trigger_app_event)
        is AgentActivityTriggerDescription.DmFrom ->
            stringResource(MR.strings.member_activity_overview_trigger_dm_from, description.peer)
        is AgentActivityTriggerDescription.Target ->
            stringResource(MR.strings.member_activity_overview_trigger_target, description.channel)
        is AgentActivityTriggerDescription.Message ->
            stringResource(MR.strings.member_activity_overview_trigger_message, description.sender, description.channel)
        is AgentActivityTriggerDescription.Thread ->
            stringResource(MR.strings.member_activity_overview_trigger_thread, description.sender, description.channel)
    }

@Composable
private fun membersActivityOverviewActionsText(actions: List<AgentActivityActionCount>): String {
    if (actions.isEmpty()) return stringResource(MR.strings.member_activity_overview_actions_none)
    val parts = actions.map { membersActivityOverviewActionText(it) }
    val joinTemplate = stringResource(MR.strings.member_activity_overview_actions_join, "%1\$s", "%2\$s")
    val lastTemplate = stringResource(MR.strings.member_activity_overview_actions_join_last, "%1\$s", "%2\$s")
    val pairTemplate = stringResource(MR.strings.member_activity_overview_actions_join_pair, "%1\$s", "%2\$s")
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
            if (one) stringResource(MR.strings.member_activity_overview_action_run_command_one)
            else stringResource(MR.strings.member_activity_overview_action_run_command_other, count)
        AgentActivityActionKind.ReadFile ->
            if (one) stringResource(MR.strings.member_activity_overview_action_read_file_one)
            else stringResource(MR.strings.member_activity_overview_action_read_file_other, count)
        AgentActivityActionKind.EditFile ->
            if (one) stringResource(MR.strings.member_activity_overview_action_edit_file_one)
            else stringResource(MR.strings.member_activity_overview_action_edit_file_other, count)
        AgentActivityActionKind.Search ->
            if (one) stringResource(MR.strings.member_activity_overview_action_search_one)
            else stringResource(MR.strings.member_activity_overview_action_search_other, count)
        AgentActivityActionKind.Web ->
            if (one) stringResource(MR.strings.member_activity_overview_action_web_one)
            else stringResource(MR.strings.member_activity_overview_action_web_other, count)
        AgentActivityActionKind.Fetch ->
            if (one) stringResource(MR.strings.member_activity_overview_action_fetch_one)
            else stringResource(MR.strings.member_activity_overview_action_fetch_other, count)
        AgentActivityActionKind.Subagent ->
            if (one) stringResource(MR.strings.member_activity_overview_action_subagent_one)
            else stringResource(MR.strings.member_activity_overview_action_subagent_other, count)
        AgentActivityActionKind.Mcp ->
            if (one) stringResource(MR.strings.member_activity_overview_action_mcp_one)
            else stringResource(MR.strings.member_activity_overview_action_mcp_other, count)
        AgentActivityActionKind.Wait ->
            if (one) stringResource(MR.strings.member_activity_overview_action_wait_one)
            else stringResource(MR.strings.member_activity_overview_action_wait_other, count)
        AgentActivityActionKind.Other ->
            if (one) stringResource(MR.strings.member_activity_overview_action_other_one)
            else stringResource(MR.strings.member_activity_overview_action_other_other, count)
        AgentActivityActionKind.SendMessage ->
            if (one) stringResource(MR.strings.member_activity_overview_action_send_message_one)
            else stringResource(MR.strings.member_activity_overview_action_send_message_other, count)
        AgentActivityActionKind.ReadMessages ->
            if (one) stringResource(MR.strings.member_activity_overview_action_read_messages_one)
            else stringResource(MR.strings.member_activity_overview_action_read_messages_other, count)
        AgentActivityActionKind.RaftAction ->
            if (one) stringResource(MR.strings.member_activity_overview_action_raft_action_one)
            else stringResource(MR.strings.member_activity_overview_action_raft_action_other, count)
        AgentActivityActionKind.VersionControl ->
            if (one) stringResource(MR.strings.member_activity_overview_action_version_control_one)
            else stringResource(MR.strings.member_activity_overview_action_version_control_other, count)
        AgentActivityActionKind.RunChecks ->
            if (one) stringResource(MR.strings.member_activity_overview_action_run_checks_one)
            else stringResource(MR.strings.member_activity_overview_action_run_checks_other, count)
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
        parts.hours > 0 -> stringResource(MR.strings.member_activity_overview_duration_hours, parts.hours, parts.minutes)
        parts.minutes > 0 -> stringResource(MR.strings.member_activity_overview_duration_minutes, parts.minutes, parts.seconds)
        else -> stringResource(MR.strings.member_activity_overview_duration_seconds, parts.seconds)
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
