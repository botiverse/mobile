package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.data.AgentActivityOverviewStatus
import build.raft.app.data.AgentActivityTurn
import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberId
import build.raft.app.model.RaftTimeContext
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
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val focus = running ?: finished.firstOrNull()
    // Without a running turn the newest finished one is the focus line's: the rows start after it.
    val rows = finished.drop(if (running != null) 0 else 1).take(MembersAgentRecentActivityTurns)
    RaftCard(modifier = modifier.fillMaxWidth().testTag("agent-now-card")) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RaftOtterIndicator(otter)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // The status's parts 6dp apart, as under the hero (web AgentStatusBadge).
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) { status() }
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
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (rows.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Web `border-t border-line-hairline pt-3`.
                    RaftSeparator()
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        rows.forEach { turn -> MembersAgentRecentTurnRow(turn, timeContext) }
                    }
                }
            }
        }
    }
}

/**
 * Web AgentNowCard `RecentTurnRow`: its time, who woke it, what it did, and its compact badges.
 * artin 2026-11-18 (#wg-mobile-sync:c1493fb5): on a phone the row wraps instead of truncating, so
 * nothing is cut to "…"; the badges move to the next line whole, aligned with the text, not the
 * clock (web slock #15377 does the same).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MembersAgentRecentTurnRow(turn: AgentActivityTurn, timeContext: RaftTimeContext) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val trigger = membersActivityOverviewCompactTriggerText(turn.trigger)
    Row(
        modifier = Modifier.fillMaxWidth().testTag("agent-now-row"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RaftText(
            text = membersActivityOverviewClock(turn.startedAtEpochMillis, timeContext),
            style = typography.monoMeta,
            color = colors.foregroundHint,
            maxLines = 1,
            // The clock sits on the first line's text, wrapped lines start under the text (web #15377).
            modifier = Modifier.padding(top = 2.dp),
        )
        FlowRow(
            modifier = Modifier.weight(1f),
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
            MembersAgentActivityTurnBadges(turn, compact = true)
        }
    }
}
