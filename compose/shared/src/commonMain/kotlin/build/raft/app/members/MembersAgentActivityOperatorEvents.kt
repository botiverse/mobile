package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import build.raft.app.data.AGENT_OPERATOR_EVENTS_FOLD_AT
import build.raft.app.data.AgentOperatorEvent
import build.raft.app.data.AgentOperatorEventActor
import build.raft.app.data.AgentOperatorEventKind
import build.raft.app.model.RaftTimeContext
import build.raft.app.resources.*
import build.raft.app.thread.ThreadMessageTimelineRowInset
import build.raft.app.thread.ThreadSystemMessageGroupHeader
import build.raft.designsystem.text.RaftText

/*
 * What people did to the agent (start, stop, restart, reset, Stop Current Turn, migration, model
 * change), and Raft's own restarts, as thin system lines between the overview's turns (web
 * `ActivityOperatorEvents`, botiverse/slock #13937, design A). Three or more in a row fold into one
 * line, "3 actions · latest: …", that opens like a system message group. Raft's own restarts are
 * fainter and name no one.
 */

/** Web `operatorEventText`, in the app language. */
internal fun membersActivityOverviewOperatorEventText(event: AgentOperatorEvent): StringDesc {
    val actor: Any = when (val who = event.actor) {
        is AgentOperatorEventActor.User -> who.name ?: Res.string.member_activity_overview_operator_someone.desc()
        // The web passes "" and the line's leading space collapses: callers trim the line.
        AgentOperatorEventActor.System -> ""
    }
    return when (event.kind) {
        AgentOperatorEventKind.Start -> Res.string.member_activity_overview_operator_start.format(actor)
        AgentOperatorEventKind.Stop -> Res.string.member_activity_overview_operator_stop.format(actor)
        AgentOperatorEventKind.Restart -> Res.string.member_activity_overview_operator_restart.format(actor)
        AgentOperatorEventKind.ResetSession -> Res.string.member_activity_overview_operator_reset_session.format(actor)
        AgentOperatorEventKind.ResetFull -> Res.string.member_activity_overview_operator_reset_full.format(actor)
        AgentOperatorEventKind.TurnStopped -> Res.string.member_activity_overview_operator_turn_stopped.format(actor)
        AgentOperatorEventKind.MigrationStarted -> {
            val from = event.fromComputer
            val to = event.toComputer
            if (from != null && to != null) {
                Res.string.member_activity_overview_operator_migration_started_to.format(actor, from, to)
            } else {
                Res.string.member_activity_overview_operator_migration_started.format(actor)
            }
        }
        AgentOperatorEventKind.MigrationCancelled -> Res.string.member_activity_overview_operator_migration_cancelled.format(actor)
        AgentOperatorEventKind.ConfigChanged -> {
            val beforeModel = event.beforeModel
            val afterModel = event.afterModel
            val beforeRuntime = event.beforeRuntime
            val afterRuntime = event.afterRuntime
            when {
                beforeModel != null && afterModel != null ->
                    Res.string.member_activity_overview_operator_model_changed.format(actor, beforeModel, afterModel)
                beforeRuntime != null && afterRuntime != null ->
                    Res.string.member_activity_overview_operator_runtime_changed.format(actor, beforeRuntime, afterRuntime)
                else -> Res.string.member_activity_overview_operator_config_changed.format(actor)
            }
        }
        AgentOperatorEventKind.SystemRestart -> Res.string.member_activity_overview_operator_system_restart.desc()
    }
}

/** Web folded summary: "{count} actions · latest: {latest}", [latest] the newest line as shown. */
internal fun membersActivityOverviewOperatorEventsSummary(count: Int, latest: String): StringDesc =
    Res.string.member_activity_overview_operator_folded.format(count, latest)

/** One run of consecutive events, oldest first. */
@Composable
internal fun MembersAgentActivityOperatorEvents(
    events: List<AgentOperatorEvent>,
    timeContext: RaftTimeContext,
    modifier: Modifier = Modifier,
) {
    if (events.isEmpty()) return
    if (events.size < AGENT_OPERATOR_EVENTS_FOLD_AT) {
        Column(modifier.fillMaxWidth()) {
            events.forEach { MembersAgentActivityOperatorEventLine(it, timeContext) }
        }
        return
    }
    var expanded by rememberSaveable(events.first().id) { mutableStateOf(false) }
    // An actor-less line has no leading space (trimmed here and on the line itself).
    val latest = membersActivityOverviewOperatorEventText(events.last()).localized().trim()
    val summary = membersActivityOverviewOperatorEventsSummary(events.size, latest).localized()
    Column(modifier.fillMaxWidth().testTag("activity-overview-operator-events-folded")) {
        ThreadSystemMessageGroupHeader(summary = summary, expanded = expanded, onToggle = { expanded = !expanded })
        if (expanded) events.forEach { MembersAgentActivityOperatorEventLine(it, timeContext) }
    }
}

/** raft-ui `SystemMessage`: time · text, centered, one line; Raft's own restarts at 60%. */
@Composable
private fun MembersAgentActivityOperatorEventLine(event: AgentOperatorEvent, timeContext: RaftTimeContext) {
    val timeColor = RaftColor.TextBlack.copy(alpha = 0.4f)
    val bodyColor = RaftColor.TextBlack.copy(alpha = 0.5f)
    val system = event.actor == AgentOperatorEventActor.System
    val text = membersActivityOverviewOperatorEventText(event).localized().trim()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("activity-overview-operator-event")
            .then(if (system) Modifier.alpha(0.6f) else Modifier)
            .padding(vertical = ThreadMessageTimelineRowInset),
        horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.Top,
    ) {
        RaftText(
            text = membersActivityOverviewClock(event.atEpochMillis, timeContext),
            style = RaftType.Caption.copy(fontFamily = RaftMonoFamily),
            color = timeColor,
            maxLines = 1,
        )
        RaftText(
            text = text,
            style = RaftType.Caption,
            color = bodyColor,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}
