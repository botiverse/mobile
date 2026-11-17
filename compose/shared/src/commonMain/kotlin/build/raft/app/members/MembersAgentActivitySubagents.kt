package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.compat.ui.NestedScrollMode
import build.raft.app.compat.ui.nestedScroll
import build.raft.app.data.AgentActivityEntry
import build.raft.app.data.AgentActivitySubagentGroup
import build.raft.app.data.AgentActivitySubagentState
import build.raft.app.data.agentActivitySubagentKeyResolver
import build.raft.app.data.agentToolLogLabel
import build.raft.app.model.AgentActivitySubagentLineage
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.formatRaftTimestampClockWithSeconds
import build.raft.designsystem.components.RaftBackButton
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftDescriptionList
import build.raft.designsystem.components.RaftDescriptionListDirection
import build.raft.designsystem.components.RaftEmptyState
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowGroup
import build.raft.designsystem.components.RaftPresence
import build.raft.designsystem.components.RaftStatusDot
import build.raft.designsystem.components.RaftStatusVariant
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlin.time.Clock

// The Activity tab's Sub-agents view (web AgentActivityLog `SubagentsList` / `SubagentDetail`,
// botiverse/slock 853c6998bcef45df5c25e63b1035e2388608721d, #proj-runtime:#41, #13029, #13383):
// the agent's running and recent sub-agents, each opening its own log, with Stop on a running one.

/**
 * Web `SubagentLinkContext`: the raw log's way into the Sub-agents view. A spawn row whose call
 * started a sub-agent links to it ([keyByCall]); a sub-agent's own row opens its own run
 * ([keyOfRow]).
 */
internal class MembersAgentActivitySubagentLink(
    private val keyByCall: Map<String, String>,
    private val resolveKey: (AgentActivitySubagentLineage) -> String?,
    private val groupKeys: Set<String>,
    val open: (String) -> Unit,
) {
    fun keyOfCall(callId: String?): String? = callId?.let(keyByCall::get)

    fun keyOfRow(lineage: AgentActivitySubagentLineage?): String? =
        lineage?.let(resolveKey)?.takeIf { it in groupKeys }
}

internal fun membersAgentActivitySubagentLink(
    log: List<AgentActivityEntry>,
    groups: List<AgentActivitySubagentGroup>,
    open: (String) -> Unit,
): MembersAgentActivitySubagentLink =
    MembersAgentActivitySubagentLink(
        // The row that started a sub-agent opens it (the first listed, when one call started several).
        keyByCall = groups.asReversed().mapNotNull { group -> group.parentToolUseId?.let { it to group.key } }.toMap(),
        resolveKey = agentActivitySubagentKeyResolver(log),
        groupKeys = groups.mapTo(HashSet()) { it.key },
        open = open,
    )

/** Stop one running sub-agent: the viewer may (web `canStopSubagentsOf`), and which stop is out. */
internal class MembersAgentSubagentStop(
    val inFlightTaskId: String?,
    val onStop: (taskId: String) -> Unit,
)

internal const val MembersAgentSubagentsListTag = "members_agent_subagents_list"
internal const val MembersAgentSubagentRowTag = "members_agent_subagent_row"
internal const val MembersAgentSubagentDetailTag = "members_agent_subagent_detail"
internal const val MembersAgentSubagentStopTag = "members_agent_subagent_stop"
internal const val MembersAgentActivitySubagentOpenTag = "members_agent_activity_subagent_open"

/**
 * Web `SUBAGENT_STATE_CHIP` (raft-ui Status page): a soft badge with a presence light and its
 * word. A failure or a limit is the error light (warning family), a stop or an end the offline one.
 */
@Composable
internal fun MembersAgentSubagentStateChip(state: AgentActivitySubagentState, modifier: Modifier = Modifier) {
    val (variant, label) =
        when (state) {
            AgentActivitySubagentState.Running -> RaftBadgeVariant.Busy to stringResource(Res.string.member_activity_subagents_state_running)
            AgentActivitySubagentState.Done -> RaftBadgeVariant.Success to stringResource(Res.string.member_activity_subagents_state_done)
            AgentActivitySubagentState.Failed -> RaftBadgeVariant.Warning to stringResource(Res.string.member_activity_subagents_state_failed)
            AgentActivitySubagentState.Ceiling -> RaftBadgeVariant.Warning to stringResource(Res.string.member_activity_subagents_state_ceiling)
            AgentActivitySubagentState.Stopped -> RaftBadgeVariant.Muted to stringResource(Res.string.member_activity_subagents_state_stopped)
            AgentActivitySubagentState.Ended -> RaftBadgeVariant.Muted to stringResource(Res.string.member_activity_subagents_state_ended)
        }
    RaftBadge(
        text = label,
        modifier = modifier,
        appearance = RaftBadgeAppearance.Soft,
        variant = variant,
        icon = {
            when (state) {
                AgentActivitySubagentState.Running -> RaftStatusDot(presence = RaftPresence.Busy)
                AgentActivitySubagentState.Done -> RaftStatusDot(variant = RaftStatusVariant.Success)
                AgentActivitySubagentState.Failed,
                AgentActivitySubagentState.Ceiling -> RaftStatusDot(presence = RaftPresence.Error)
                AgentActivitySubagentState.Stopped,
                AgentActivitySubagentState.Ended -> RaftStatusDot(presence = RaftPresence.Offline)
            }
        },
    )
}

/** Web `useSubagentText`. */
@Composable
private fun membersSubagentTitle(group: AgentActivitySubagentGroup): String =
    group.title ?: group.subagentType ?: stringResource(Res.string.member_activity_subagents_untitled)

@Composable
private fun membersSubagentSteps(group: AgentActivitySubagentGroup): String =
    if (group.steps == 1) stringResource(Res.string.member_activity_subagents_steps_one)
    else stringResource(Res.string.member_activity_subagents_steps_other, group.steps)

@Composable
private fun membersSubagentDuration(group: AgentActivitySubagentGroup, nowEpochMillis: () -> Long): String {
    val duration = membersActivityOverviewDuration((group.endedAtMillis ?: nowEpochMillis()) - group.startedAtMillis)
    return if (group.state == AgentActivitySubagentState.Running) {
        stringResource(Res.string.member_activity_overview_turn_running_for, duration)
    } else {
        stringResource(Res.string.member_activity_overview_turn_took, duration)
    }
}

private val systemNowEpochMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() }

@Composable
private fun MembersAgentSubagentRow(
    group: AgentActivitySubagentGroup,
    divider: Boolean,
    timeContext: RaftTimeContext,
    nowEpochMillis: () -> Long,
    onOpen: (String) -> Unit,
) {
    val running = group.state == AgentActivitySubagentState.Running
    val lastTool = group.lastToolName
    val facts = listOfNotNull(
        if (group.title != null) group.subagentType else null,
        membersSubagentSteps(group),
        if (running && lastTool != null) {
            stringResource(Res.string.member_activity_subagents_now, agentToolLogLabel(lastTool))
        } else {
            membersSubagentDuration(group, nowEpochMillis)
        },
    ).joinToString(" · ")
    RaftListRow(
        title = membersSubagentTitle(group),
        modifier = Modifier.testTag(MembersAgentSubagentRowTag),
        onClick = { onOpen(group.key) },
        divider = divider,
        meta = membersActivityOverviewClock(group.startedAtMillis, timeContext),
        description = facts,
        leading = { RaftContentLucideIcon(RaftLucideIconName.Bot, size = 20.dp) },
        badge = { MembersAgentSubagentStateChip(group.state) },
    )
}

@Composable
private fun MembersAgentSubagentGroupHeading(running: Boolean, count: Int) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RaftStatusDot(presence = if (running) RaftPresence.Busy else RaftPresence.Offline)
        RaftText(
            text = stringResource(
                if (running) Res.string.member_activity_subagents_group_running else Res.string.member_activity_subagents_group_ended
            ),
            style = typography.footnote.copy(fontWeight = FontWeight.Bold),
            color = colors.foregroundMuted,
        )
        RaftText(text = count.toString(), style = typography.monoMeta, color = colors.foregroundMuted)
    }
}

/** Web `SubagentsList`: running first, then the ended ones, each a row that opens it. */
@Composable
internal fun MembersAgentSubagentsList(
    groups: List<AgentActivitySubagentGroup>,
    timeContext: RaftTimeContext,
    onOpen: (String) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    nowEpochMillis: () -> Long = systemNowEpochMillis,
) {
    if (groups.isEmpty()) {
        RaftEmptyState(
            title = stringResource(Res.string.member_activity_subagents_empty_title),
            description = stringResource(Res.string.member_activity_subagents_recent_only),
            icon = { RaftContentLucideIcon(RaftLucideIconName.Bot, size = 36.dp) },
            modifier = modifier,
        )
        return
    }
    val running = groups.filter { it.state == AgentActivitySubagentState.Running }
    val ended = groups.filter { it.state != AgentActivitySubagentState.Running }
    LazyColumn(
        modifier = modifier
            .testTag(MembersAgentSubagentsListTag)
            .nestedScroll(scrollUp = NestedScrollMode.SELF_FIRST, scrollDown = NestedScrollMode.SELF_FIRST),
        contentPadding = contentPadding,
    ) {
        for ((isRunning, section) in listOf(true to running, false to ended)) {
            if (section.isEmpty()) continue
            item(key = "subagents-heading-$isRunning") { MembersAgentSubagentGroupHeading(running = isRunning, count = section.size) }
            item(key = "subagents-group-$isRunning") {
                RaftListRowGroup(modifier = Modifier.fillMaxWidth()) {
                    section.forEachIndexed { index, group ->
                        MembersAgentSubagentRow(group, divider = index > 0, timeContext, nowEpochMillis, onOpen)
                    }
                }
            }
        }
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

/**
 * Web `SubagentDetail`: its head (Back, what it was asked, its state, and Stop while it runs for
 * a viewer who may stop it), its facts, then its own steps as the raw log shows them.
 */
@Composable
internal fun MembersAgentSubagentDetail(
    group: AgentActivitySubagentGroup,
    timeContext: RaftTimeContext,
    stop: MembersAgentSubagentStop?,
    onBack: () -> Unit,
    horizontalInset: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    nowEpochMillis: () -> Long = systemNowEpochMillis,
    row: @Composable (index: Int, entry: AgentActivityEntry, last: Boolean) -> Unit,
) {
    val colors = RaftTheme.colors
    val title = membersSubagentTitle(group)
    // Only a running sub-agent with its own id, of a runtime that can stop one, offers Stop.
    val stopTaskId = group.taskId.takeIf { stop != null && group.state == AgentActivitySubagentState.Running }
    // Web isVisibleTrajectoryEntry: a freshness hold says itself in its action row.
    val items = group.items.filterNot { it.type == "status" && it.statusDetailKind == "freshness_hold" }
    Column(modifier = modifier.testTag(MembersAgentSubagentDetailTag)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalInset, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            RaftBackButton(onClick = onBack, label = stringResource(Res.string.member_activity_subagents_back))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                RaftText(
                    text = title,
                    style = RaftTheme.typography.headline,
                    color = colors.foreground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                MembersAgentSubagentStateChip(group.state)
            }
            if (stopTaskId != null && stop != null) {
                RaftButton(
                    text = stringResource(Res.string.member_activity_subagents_stop),
                    onClick = { stop.onStop(stopTaskId) },
                    enabled = stop.inFlightTaskId == null,
                    modifier = Modifier.testTag(MembersAgentSubagentStopTag),
                )
            }
        }
        MembersDetailSectionDivider()
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .nestedScroll(scrollUp = NestedScrollMode.SELF_FIRST, scrollDown = NestedScrollMode.SELF_FIRST),
        ) {
            item(key = "subagent-facts") {
                Column {
                    RaftDescriptionList(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalInset, vertical = 12.dp),
                        direction = RaftDescriptionListDirection.Horizontal,
                    ) {
                        group.subagentType?.let { Item(term = stringResource(Res.string.member_activity_subagents_detail_type), value = it) }
                        Item(
                            term = stringResource(Res.string.member_activity_subagents_detail_started),
                            value = formatRaftTimestampClockWithSeconds(group.startedAtMillis, timeContext.localOffsetMinutes, timeContext.timeFormat),
                        )
                        Item(
                            term = stringResource(Res.string.member_activity_subagents_detail_time),
                            value = "${membersSubagentDuration(group, nowEpochMillis)} · ${membersSubagentSteps(group)}",
                        )
                    }
                    MembersDetailSectionDivider()
                }
            }
            itemsIndexed(items, key = { _, entry -> entry.id }) { index, entry ->
                Column(Modifier.padding(horizontal = horizontalInset).padding(top = if (index == 0) 12.dp else 0.dp, bottom = if (index == items.lastIndex) 12.dp else 0.dp)) {
                    row(index, entry, index == items.lastIndex)
                }
            }
        }
    }
}
