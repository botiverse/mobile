package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import build.raft.app.data.AgentActivityOverviewStatus
import build.raft.app.data.AgentActivityTurn
import build.raft.app.data.offsetMinutesAt
import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberId
import build.raft.app.model.MillisPerDay
import build.raft.app.model.MillisPerMinute
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.floorDiv
import build.raft.app.model.formatRaftLocalClock
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftCard
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftOtterIndicator
import build.raft.designsystem.components.RaftSectionEyebrow
import build.raft.designsystem.components.RaftSeparator
import build.raft.designsystem.components.RaftStepStatus
import build.raft.designsystem.components.raftClickable
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

/*
 * The agent profile's "Recent activity" (web packages/web/src/features/agent/AgentRecentActivity.tsx
 * and components/agent/AgentNowCard.tsx at botiverse/slock staging, xxchan 2026-11-05): the
 * agent's status as its first line beside Ottie, who the running (else the last) turn answers and
 * what it did, then its last few turns one line each; the heading opens the Activity tab. The
 * turns are the Activity overview's ([build.raft.app.data.SharedAgentActivityOverviewStore]).
 */

/** Finished turns the profile lists under what the agent is doing now (web `RECENT_TURNS`). */
internal const val MembersAgentRecentActivityTurns = 3

/**
 * What the profile needs to show Recent activity; null where it shows none: not
 * [membersAgentShowsRecentActivity], or no overview to read the turns from (the Activity tab is
 * then the raw log alone).
 */
internal class MembersAgentRecentActivityInputs(
    val overview: MembersAgentActivityOverviewBinding,
    val serverId: String?,
    /** The viewer's clock, for the turns' times. */
    val timeContext: RaftTimeContext,
    /** Opens the Activity tab (its Overview). */
    val onOpenActivity: () -> Unit,
)

/** Web AgentDetailPanel `showRecentActivity`: the viewer sees the Activity tab and the agent is not archived. */
internal fun membersAgentShowsRecentActivity(
    visibleTabs: List<MembersAgentDetailTab>,
    deleted: Boolean,
): Boolean = MembersAgentDetailTab.Activity in visibleTabs && !deleted

/**
 * Web AgentNowCard's Ottie: typing while the agent works, puzzled on an error, resting otherwise.
 */
internal fun membersAgentRecentActivityOtter(status: AgentStatus?): RaftStepStatus =
    when (status) {
        AgentStatus.Working, AgentStatus.Thinking -> RaftStepStatus.Process
        AgentStatus.Error -> RaftStepStatus.Error
        else -> RaftStepStatus.Wait
    }

/**
 * The profile's Recent activity for [agentId]: follows the agent's turns as the Activity overview
 * does (the same store and effects) and shows them in [MembersAgentRecentActivityCard]. [status]
 * is the profile's status ([MembersAgentProfileStatus]); it shows before any turn has loaded, and a
 * failed load leaves the card with the status alone (web).
 */
@Composable
internal fun MembersAgentRecentActivity(
    agentId: MemberId,
    liveActivity: String?,
    inputs: MembersAgentRecentActivityInputs,
    otter: RaftStepStatus,
    modifier: Modifier = Modifier,
    status: @Composable () -> Unit,
) {
    MembersAgentActivityOverviewEffects(inputs.overview, agentId, liveActivity, inputs.serverId)
    val state by inputs.overview.state.collectAsState()
    // Another agent's turns never show while this one's load (the store is the detail page's).
    val shown = state.takeIf { it.agentId == agentId && it.status == AgentActivityOverviewStatus.Loaded }
    MembersAgentRecentActivityCard(
        finished = shown?.finishedTurns.orEmpty(),
        running = shown?.running,
        otter = otter,
        timeContext = inputs.timeContext,
        onOpenActivity = inputs.onOpenActivity,
        modifier = modifier,
        status = status,
    )
}

/**
 * Web AgentNowCard with the profile's `status` and `recentRows`: the "Recent activity ›" heading
 * (opens the Activity tab), Ottie beside the [status] and the focus line, then up to
 * [MembersAgentRecentActivityTurns] finished turns. [finished]: newest first; [running]: the turn
 * still running, if any.
 */
@Composable
internal fun MembersAgentRecentActivityCard(
    finished: List<AgentActivityTurn>,
    running: AgentActivityTurn?,
    otter: RaftStepStatus,
    timeContext: RaftTimeContext,
    onOpenActivity: () -> Unit,
    modifier: Modifier = Modifier,
    status: @Composable () -> Unit,
) {
    MembersAgentNowCard(
        finished = finished,
        running = running,
        otter = otter,
        timeContext = timeContext,
        modifier = modifier,
        onOpenActivity = onOpenActivity,
        recentRows = MembersAgentRecentActivityTurns,
    ) {
        // The status's parts 6dp apart, as under the hero (web AgentStatusBadge).
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) { status() }
    }
}

/**
 * Web AgentNowCard (components/agent/AgentNowCard.tsx), the one card of the profile and the
 * Activity overview (botiverse/slock #13108): Ottie ([otter]) beside what the agent is doing now
 * ([status]: the profile's status, or the overview's own headline, the web's `status` prop and
 * its absence), then who the running (else the last) turn answers and what it has done. The
 * profile adds the "Recent activity ›" heading that opens the Activity tab ([onOpenActivity]) and
 * its last [recentRows] finished turns; the overview shows neither. [finished]: newest first;
 * [running]: the turn still running, if any.
 */
@Composable
internal fun MembersAgentNowCard(
    finished: List<AgentActivityTurn>,
    running: AgentActivityTurn?,
    otter: RaftStepStatus,
    timeContext: RaftTimeContext,
    modifier: Modifier = Modifier,
    onOpenActivity: (() -> Unit)? = null,
    recentRows: Int = 0,
    status: @Composable ColumnScope.() -> Unit,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val focus = running ?: finished.firstOrNull()
    // Without a running turn the newest finished one is the focus line's: the rows start after it.
    val rows = if (recentRows > 0) finished.drop(if (running != null) 0 else 1).take(recentRows) else emptyList()
    RaftCard(modifier = modifier.fillMaxWidth().testTag("agent-now-card")) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (onOpenActivity != null) {
                // Web SectionEyebrow as a button, with its chevron.
                Row(
                    modifier = Modifier
                        .raftClickable(onClick = onOpenActivity, shape = RoundedCornerShape(2.dp))
                        .testTag("agent-now-open-activity"),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RaftSectionEyebrow(text = stringResource(Res.string.member_recent_activity_title))
                    RaftGlyphIcon(RaftGlyph.ChevronRight, size = 14.dp, color = colors.foregroundHint)
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RaftOtterIndicator(otter)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    status()
                    focus?.let { turn ->
                        val trigger = membersActivityOverviewCompactTriggerText(turn.trigger)
                            ?: stringResource(Res.string.member_recent_activity_trigger_unknown)
                        val actions = membersActivityOverviewActionsText(turn.actions, turn.messagesSent)
                        RaftText(
                            text = stringResource(
                                if (running != null) Res.string.member_recent_activity_this_turn
                                else Res.string.member_recent_activity_last_turn,
                                trigger,
                                actions,
                            ),
                            style = typography.callout,
                            color = colors.foregroundMuted,
                        )
                    }
                }
            }
            if (rows.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Web `border-t border-line-hairline pt-3`.
                    RaftSeparator()
                    MembersAgentRecentTurnRows(rows, timeContext, Modifier.fillMaxWidth().padding(top = 6.dp))
                }
            }
        }
    }
}

/** The rows' time labels, texts, and badge rows, for tests. */
internal const val MembersAgentRecentTurnTimeTag = "agent-now-row-time"
internal const val MembersAgentRecentTurnTextTag = "agent-now-row"
internal const val MembersAgentRecentTurnBadgesTag = "agent-now-row-badges"

/**
 * Web `AgentNowCard` `useTurnTime` (`packages/web/src/components/agent/AgentNowCard.tsx`):
 * clock only today (`"15:53"` / `"03:53 PM"`), else localized short month/day and clock (web
 * `formatShortDateTime`, e.g. `"Nov 21, 11:23"` / `"11月21日 11:23"`). "Today" and the turn's
 * offset are evaluated in the viewer's [RaftTimeContext] (respecting account `preferredTimezone`
 * and per-instant DST when `timeZoneId` is populated).
 */
@Composable
internal fun membersAgentNowTurnTime(epochMillis: Long, timeContext: RaftTimeContext): String {
    val context = currentRaftEffectiveDateTimeContext(timeContext)
    val today = context.resolvedTodayEpochDay()
    val offsetMinutes = context.offsetMinutesAt(epochMillis)
    val turnDay = floorDiv(epochMillis + offsetMinutes.toLong() * MillisPerMinute, MillisPerDay)
    return if (today == null || turnDay == today) {
        formatRaftLocalClock(epochMillis, offsetMinutes, context.timeFormat)
    } else {
        raftLocalizedShortDateTime(epochMillis, context)
    }
}

/**
 * Web AgentNowCard `RecentTurnRow`, one per turn: its time, who woke it, what it did, and its
 * compact badges. artin 2026-11-18 (#wg-mobile-sync:c1493fb5): on a phone the row wraps instead of
 * truncating, so nothing is cut to "…"; the badges sit on their own line below the text, aligned
 * with the text, not the clock (#wg-mobile-sync:#251).
 *
 * The rows share one time column, as wide as the widest label measured here (its font, the font
 * scale and the time format as they are), so every row's text starts at the same x when a turn of
 * another day carries its date ("Nov 20, 23:57" above "00:18"). The web's row sizes each label on
 * its own (AgentNowCard.tsx `RecentTurnRow`, `shrink-0 whitespace-nowrap`); the labels stay left
 * aligned as there.
 */
@Composable
internal fun MembersAgentRecentTurnRows(
    rows: List<AgentActivityTurn>,
    timeContext: RaftTimeContext,
    modifier: Modifier = Modifier,
) {
    Layout(
        // Two children a row, in this order: the time, then the text.
        content = {
            rows.forEach { turn ->
                MembersAgentRecentTurnTime(turn, timeContext)
                MembersAgentRecentTurnText(turn)
            }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val gap = MembersAgentRecentTurnTimeGap.roundToPx()
        val rowGap = MembersAgentRecentTurnRowGap.roundToPx()
        val count = measurables.size / 2
        // A label is one line at its own width; the column is the widest of them.
        val times = List(count) { measurables[it * 2].measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val timeColumn = times.maxOfOrNull { it.width } ?: 0
        val textConstraints =
            if (constraints.hasBoundedWidth) {
                Constraints.fixedWidth((constraints.maxWidth - timeColumn - gap).coerceAtLeast(0))
            } else {
                Constraints()
            }
        val texts = List(count) { measurables[it * 2 + 1].measure(textConstraints) }
        val heights = List(count) { maxOf(times[it].height, texts[it].height) }
        val width =
            if (constraints.hasBoundedWidth) constraints.maxWidth else timeColumn + gap + (texts.maxOfOrNull { it.width } ?: 0)
        val height = heights.sum() + rowGap * (count - 1).coerceAtLeast(0)
        layout(constraints.constrainWidth(width), constraints.constrainHeight(height)) {
            var y = 0
            repeat(count) { index ->
                times[index].placeRelative(0, y)
                texts[index].placeRelative(timeColumn + gap, y)
                y += heights[index] + rowGap
            }
        }
    }
}

/** Web `gap-2` between the time and the text, and `stack-y-1.5` between rows. */
private val MembersAgentRecentTurnTimeGap = 8.dp
private val MembersAgentRecentTurnRowGap = 6.dp

@Composable
private fun MembersAgentRecentTurnTime(turn: AgentActivityTurn, timeContext: RaftTimeContext) {
    RaftText(
        text = membersAgentNowTurnTime(turn.startedAtEpochMillis, timeContext),
        style = RaftTheme.typography.monoMeta,
        color = RaftTheme.colors.foregroundHint,
        maxLines = 1,
        softWrap = false,
        // The clock sits on the first line's text, wrapped lines start under the text (web #15377).
        modifier = Modifier.padding(top = 2.dp).testTag(MembersAgentRecentTurnTimeTag),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MembersAgentRecentTurnText(turn: AgentActivityTurn) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val trigger = membersActivityOverviewCompactTriggerText(turn.trigger)
    Column(
        modifier = Modifier.testTag(MembersAgentRecentTurnTextTag),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            if (trigger != null) {
                RaftText(text = trigger, style = typography.callout, color = colors.foreground)
            }
            RaftText(
                text = membersActivityOverviewActionsText(turn.actions, turn.messagesSent),
                style = typography.callout,
                color = colors.foregroundMuted,
            )
        }
        if (turn.hasFinishedTurnBadges()) {
            FlowRow(
                modifier = Modifier.testTag(MembersAgentRecentTurnBadgesTag),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                MembersAgentActivityTurnBadges(turn, compact = true)
            }
        }
    }
}
