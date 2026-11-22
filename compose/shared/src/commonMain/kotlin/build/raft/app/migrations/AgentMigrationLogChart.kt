package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftSlotIcon
import build.raft.app.data.AgentMigrationJourney
import build.raft.app.data.AgentMigrationLogEntry
import build.raft.app.data.AgentMigrationLogFailureKind
import build.raft.app.data.AgentMigrationLogFilters
import build.raft.app.data.AgentMigrationLogNode
import build.raft.app.data.AgentMigrationLogOutcome
import build.raft.app.data.agentMigrationJourney
import build.raft.app.data.agentMigrationRouteCells
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftDescriptionList
import build.raft.designsystem.components.RaftDescriptionListDirection
import build.raft.designsystem.components.RaftDropdownMenu
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowGroup
import build.raft.designsystem.components.RaftMenuItem
import build.raft.designsystem.components.RaftMenuLabel
import build.raft.designsystem.components.RaftMenuSeparator
import build.raft.designsystem.components.RaftSeparator
import build.raft.designsystem.components.RaftSheet
import build.raft.designsystem.components.RaftTextLink
import build.raft.designsystem.components.RaftTextLinkVariant
import build.raft.designsystem.components.appendRaftTextLink
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftUiPalette
import org.jetbrains.compose.resources.StringResource

/** An agent of the server as the log shows it: its name and the computer it lives on now. */
internal data class AgentMigrationLogAgent(val id: String, val name: String, val machineId: String? = null)

/** Everything the chart and what is beside it read: the moves that pass the filters, and who and where they name. */
internal class AgentMigrationLogNames(
    val nodeById: Map<String, AgentMigrationLogNode>,
    val agents: Map<String, AgentMigrationLogAgent>,
    private val cloud: String,
    val avatar: @Composable (agentId: String, size: Dp) -> Unit,
    val onOpenAgent: (String) -> Unit,
    val onOpenPerson: (String) -> Unit,
) {
    fun code(id: String): String = nodeById[id]?.code ?: "···"

    fun placeName(node: AgentMigrationLogNode): String = if (node.id == AgentMigrationLogEntry.CLOUD_NODE) cloud else node.name

    /** The short id for an agent no longer on the server. */
    fun agentLabel(id: String): String = agents[id]?.name ?: id.take(8)
}

private const val SideRows = 20
private val ChartWide = 640.dp

/** Web `chartSize`: 640 on a wide window, at most 360 on a phone. */
internal fun agentMigrationChartSize(wide: Boolean, width: Dp): Dp = minOf(if (wide) ChartWide else 360.dp, width)

/**
 * The log's chart view (web `MigrationLogPage`, `view=chart`): the chord diagram, what it is filtered
 * to beside it (under it on a phone), and its legend. A tap opens a computer's or a route's details in
 * a sheet, a press and hold its menu.
 */
@Composable
internal fun AgentMigrationLogChartView(
    entries: List<AgentMigrationLogEntry>,
    shown: List<AgentMigrationLogEntry>,
    filters: AgentMigrationLogFilters,
    nodes: List<AgentMigrationLogNode>,
    names: AgentMigrationLogNames,
    wide: Boolean,
    width: Dp,
    onFilters: (AgentMigrationLogFilters) -> Unit,
    onCopy: (String) -> Unit,
) {
    val allCells = remember(entries) { agentMigrationRouteCells(entries) }
    val cells = remember(shown) { agentMigrationRouteCells(shown) }
    val residents = remember(names.agents) { names.agents.values.mapNotNull { it.machineId }.groupingBy { it }.eachCount() }
    // A new filter closes the details: what they showed may no longer be drawn.
    var opened by remember(filters) { mutableStateOf<AgentMigrationChordTarget?>(null) }
    var menu by remember(filters) { mutableStateOf<Pair<AgentMigrationChordTarget, Offset>?>(null) }
    // The one of several selected agents whose routes alone are lit.
    var litAgent by remember(filters) { mutableStateOf<String?>(null) }
    val focus = menu?.first ?: opened
    val highlight =
        remember(focus, litAgent, shown, cells) {
            when {
                litAgent != null ->
                    shown.filter { it.agentId == litAgent }.mapTo(mutableSetOf()) { AgentMigrationChordTarget.Route(it.sourceMachineId, it.targetNode) }
                focus is AgentMigrationChordTarget.Route -> setOf(focus)
                focus is AgentMigrationChordTarget.Computer ->
                    cells.filter { it.src == focus.id || it.dst == focus.id }.mapTo(mutableSetOf()) { AgentMigrationChordTarget.Route(it.src, it.dst) }
                else -> null
            }
        }
    val single = remember(filters.agents, shown) { filters.agents.singleOrNull()?.let { agentMigrationJourney(shown, it) } }
    val numbered =
        remember(single) {
            single?.legs.orEmpty().map { AgentMigrationChordTarget.Route(it.entry.sourceMachineId, it.entry.targetNode) }
        }
    val computerWords = stringResource(Res.string.agent_migration_log_chart_computer, "\u0001", "\u0002", "\u0003", "\u0004")
    val routeWords = stringResource(Res.string.agent_migration_log_chart_route, "\u0001", "\u0002", "\u0003", "\u0004")
    val words =
        AgentMigrationChordWords(
            chart = stringResource(Res.string.agent_migration_log_chart),
            computer = { node, out, into ->
                computerWords.replace("\u0001", node.code).replace("\u0002", names.placeName(node)).replace("\u0003", out.toString()).replace("\u0004", into.toString())
            },
            route = { cell ->
                routeWords.replace("\u0001", names.code(cell.src)).replace("\u0002", names.code(cell.dst)).replace("\u0003", cell.ok.toString()).replace("\u0004", cell.failed.toString())
            },
        )
    val chartSize = agentMigrationChartSize(wide, width)
    val chart: @Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Nothing drawn, though there are moves: canceled ones aren't.
            if (shown.isNotEmpty() && cells.isEmpty()) {
                RaftText(
                    stringResource(Res.string.agent_migration_log_canceled_not_drawn),
                    modifier = Modifier.padding(bottom = 8.dp).testTag(AgentMigrationLogTags.CanceledNote),
                    style = RaftTheme.typography.footnote,
                    color = RaftTheme.colors.foregroundMuted,
                )
            }
            Box {
                AgentMigrationChord(
                    size = chartSize,
                    nodes = nodes,
                    allCells = allCells,
                    cells = cells,
                    filtered = filters.isFiltered,
                    residents = residents,
                    highlight = highlight,
                    numbered = numbered,
                    words = words,
                    onPress = { opened = if (it == opened) null else it },
                    onHold = { target, at -> menu = target to at },
                )
                menu?.let { (target, at) ->
                    Box(Modifier.offset(at.x.dp, at.y.dp).size(1.dp)) {
                        RaftDropdownMenu(expanded = true, onDismissRequest = { menu = null }) {
                            AgentMigrationTargetMenu(
                                target = target,
                                names = names,
                                filters = filters,
                                onFilters = {
                                    menu = null
                                    onFilters(it)
                                },
                                onDetails = {
                                    menu = null
                                    opened = target
                                },
                                onCopy = {
                                    menu = null
                                    onCopy(it)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
    val side: @Composable () -> Unit = {
        AgentMigrationLogSide(filters, shown, single, names, litAgent, onLit = { litAgent = if (it == litAgent) null else it }, onFilters = onFilters)
    }
    if (wide && width >= ChartWide + 24.dp + 240.dp) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            chart()
            Box(Modifier.weight(1f).padding(top = 8.dp)) { side() }
        }
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) { chart() }
            side()
        }
    }
    opened?.let { target ->
        AgentMigrationDetailsSheet(
            target = target,
            shown = shown,
            names = names,
            filters = filters,
            onFilters = {
                opened = null
                onFilters(it)
            },
            onCopy = onCopy,
            onDismiss = { opened = null },
        )
    }
}

private fun withoutPlaces(filters: AgentMigrationLogFilters) = filters.copy(computers = emptyList(), from = emptyList(), to = emptyList())

/** [filters] with only this computer's or this route's moves. */
private fun onlyTarget(filters: AgentMigrationLogFilters, target: AgentMigrationChordTarget): AgentMigrationLogFilters =
    when (target) {
        is AgentMigrationChordTarget.Computer -> withoutPlaces(filters).copy(computers = listOf(target.id))
        is AgentMigrationChordTarget.Route -> withoutPlaces(filters).copy(from = listOf(target.src), to = listOf(target.dst))
    }

/** Web `TargetMenu`: keep only this computer's or route's moves, open its details, copy its name. */
@Composable
private fun ColumnScope.AgentMigrationTargetMenu(
    target: AgentMigrationChordTarget,
    names: AgentMigrationLogNames,
    filters: AgentMigrationLogFilters,
    onFilters: (AgentMigrationLogFilters) -> Unit,
    /** Null in the details themselves. */
    onDetails: (() -> Unit)?,
    onCopy: (String) -> Unit,
) {
    val clear = withoutPlaces(filters)
    when (target) {
        is AgentMigrationChordTarget.Computer -> {
            val node = names.nodeById[target.id] ?: return
            RaftMenuLabel(node.code)
            RaftMenuItem(
                stringResource(Res.string.agent_migration_log_menu_only_computer),
                onClick = { onFilters(onlyTarget(filters, target)) },
                leadingIcon = { RaftSlotIcon(RaftLucideIconName.ListFilter) },
            )
            RaftMenuItem(
                stringResource(Res.string.agent_migration_log_menu_only_out),
                onClick = { onFilters(clear.copy(from = listOf(node.id))) },
                leadingIcon = { RaftSlotIcon(RaftLucideIconName.ArrowRightFromLine) },
            )
            RaftMenuItem(
                stringResource(Res.string.agent_migration_log_menu_only_in),
                onClick = { onFilters(clear.copy(to = listOf(node.id))) },
                leadingIcon = { RaftSlotIcon(RaftLucideIconName.ArrowRightToLine) },
            )
            RaftMenuSeparator()
            if (onDetails != null) {
                RaftMenuItem(
                    stringResource(Res.string.agent_migration_log_menu_details),
                    onClick = onDetails,
                    leadingIcon = { RaftSlotIcon(RaftLucideIconName.Info) },
                )
            }
            if (node.id != AgentMigrationLogEntry.CLOUD_NODE) {
                RaftMenuItem(
                    stringResource(Res.string.agent_migration_log_menu_copy_name),
                    onClick = { onCopy(names.placeName(node)) },
                    leadingIcon = { RaftSlotIcon(RaftLucideIconName.Copy) },
                )
            }
        }
        is AgentMigrationChordTarget.Route -> {
            val from = names.code(target.src)
            val to = names.code(target.dst)
            RaftMenuLabel("$from → $to")
            RaftMenuItem(
                stringResource(Res.string.agent_migration_log_menu_only_route),
                onClick = { onFilters(onlyTarget(filters, target)) },
                leadingIcon = { RaftSlotIcon(RaftLucideIconName.ListFilter) },
            )
            RaftMenuItem(
                stringResource(Res.string.agent_migration_log_menu_only_reverse, "$to → $from"),
                onClick = { onFilters(onlyTarget(filters, AgentMigrationChordTarget.Route(target.dst, target.src))) },
                leadingIcon = { RaftSlotIcon(RaftLucideIconName.Repeat) },
            )
            RaftMenuSeparator()
            if (onDetails != null) {
                RaftMenuItem(
                    stringResource(Res.string.agent_migration_log_menu_details),
                    onClick = onDetails,
                    leadingIcon = { RaftSlotIcon(RaftLucideIconName.Info) },
                )
            }
            RaftMenuItem(
                stringResource(Res.string.agent_migration_log_menu_copy_route),
                onClick = { onCopy("$from → $to") },
                leadingIcon = { RaftSlotIcon(RaftLucideIconName.Copy) },
            )
        }
    }
}

/** A count's own words: [one] for 1, [many] otherwise (Russian's forms are files/plurals/ru.json). */
@Composable
private fun counted(count: Int, one: StringResource, many: StringResource, vararg more: Any): String =
    stringResource(if (count == 1) one else many, count, *more)

@Composable
private fun movesText(count: Int): String =
    counted(count, Res.string.agent_migration_log_side_moves_one, Res.string.agent_migration_log_side_moves_many)

/** A computer's or a route's numbers, in the phone's bottom sheet. */
@Composable
private fun AgentMigrationDetailsSheet(
    target: AgentMigrationChordTarget,
    shown: List<AgentMigrationLogEntry>,
    names: AgentMigrationLogNames,
    filters: AgentMigrationLogFilters,
    onFilters: (AgentMigrationLogFilters) -> Unit,
    onCopy: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val title =
        when (target) {
            is AgentMigrationChordTarget.Computer -> names.nodeById[target.id]?.let { "${it.code} · ${names.placeName(it)}" } ?: return
            is AgentMigrationChordTarget.Route -> "${names.code(target.src)} → ${names.code(target.dst)}"
        }
    var moreOpen by remember { mutableStateOf(false) }
    RaftSheet(
        onDismissRequest = onDismiss,
        title = title,
        // Web's ⋯ in the drawer's head: the same menu as a press and hold, its first item a button.
        rowActions = {
            RaftButton(
                text =
                    stringResource(
                        if (target is AgentMigrationChordTarget.Computer) Res.string.agent_migration_log_menu_only_computer else Res.string.agent_migration_log_menu_only_route,
                    ),
                onClick = { onFilters(onlyTarget(filters, target)) },
                modifier = Modifier.weight(1f),
                size = RaftButtonSize.Lg,
                leadingIcon = { RaftSlotIcon(RaftLucideIconName.ListFilter) },
            )
            Box {
                RaftIconButton(
                    onClick = { moreOpen = true },
                    tooltip = stringResource(Res.string.agent_migration_log_menu_more),
                    modifier = Modifier.testTag(AgentMigrationLogTags.DetailsMore),
                    size = RaftIconButtonSize.Lg,
                ) { RaftSlotIcon(RaftLucideIconName.Ellipsis) }
                RaftDropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }, alignEnd = true) {
                    AgentMigrationTargetMenu(
                        target = target,
                        names = names,
                        filters = filters,
                        onFilters = onFilters,
                        onDetails = null,
                        onCopy = {
                            moreOpen = false
                            onCopy(it)
                        },
                    )
                }
            }
        },
    ) {
        when (target) {
            is AgentMigrationChordTarget.Computer -> AgentMigrationComputerDetails(target.id, shown, names)
            is AgentMigrationChordTarget.Route -> AgentMigrationRouteDetails(target, shown, names)
        }
    }
}

private val AgentMigrationLogEntry.counts: Boolean
    get() = outcome == AgentMigrationLogOutcome.Completed || outcome == AgentMigrationLogOutcome.Failed

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AgentMigrationComputerDetails(
    id: String,
    shown: List<AgentMigrationLogEntry>,
    names: AgentMigrationLogNames,
) {
    val residents = names.agents.values.filter { it.machineId == id }
    val moves = shown.filter { it.counts }
    val outs = moves.filter { it.sourceMachineId == id }
    val ins = moves.filter { it.targetNode == id }
    val separator = stringResource(Res.string.agent_migration_log_fmt_list_sep, "\u0001", "\u0002")
    val top: (List<String>) -> String = { places ->
        places.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(2)
            .map { "${names.code(it.key)} ×${it.value}" }
            .reduceOrNull { all, next -> separator.replace("\u0001", all).replace("\u0002", next) } ?: "—"
    }
    val latest = shown.lastOrNull { it.sourceMachineId == id || it.targetNode == id }
    val arrived = ins.count { it.outcome == AgentMigrationLogOutcome.Completed }
    val failed = ins.size - arrived
    val outText =
        counted(outs.size, Res.string.agent_migration_log_card_moves_one, Res.string.agent_migration_log_card_moves_many) + " · " +
            outs.distinctBy { it.targetNode }.size.let { counted(it, Res.string.agent_migration_log_card_targets_one, Res.string.agent_migration_log_card_targets_many) }
    val inText =
        listOf(
            counted(ins.size, Res.string.agent_migration_log_card_moves_one, Res.string.agent_migration_log_card_moves_many),
            counted(arrived, Res.string.agent_migration_log_card_in_ok_one, Res.string.agent_migration_log_card_in_ok_many),
            counted(failed, Res.string.agent_migration_log_card_in_failed_one, Res.string.agent_migration_log_card_in_failed_many),
        ).joinToString(" · ")
    Column(Modifier.testTag(AgentMigrationLogTags.ComputerDetails), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RaftDescriptionList(direction = RaftDescriptionListDirection.Inline) {
            Item(stringResource(Res.string.agent_migration_log_card_out), outText, mono = true)
            Item(stringResource(Res.string.agent_migration_log_card_in), inText, mono = true)
            Item(stringResource(Res.string.agent_migration_log_card_top_to), top(outs.map { it.targetNode }), mono = true)
            Item(stringResource(Res.string.agent_migration_log_card_top_from), top(ins.map { it.sourceMachineId }), mono = true)
            if (latest != null) {
                Item(stringResource(Res.string.agent_migration_log_card_latest)) {
                    val initiated = agentMigrationInitiatedBy(latest, names)
                    val whenText = raftLocalizedShortDateTime(latest.startedAt)
                    RaftText(
                        buildAnnotatedString {
                            if (latest.agentId in names.agents) {
                                appendRaftTextLink(names.agentLabel(latest.agentId), onClick = { names.onOpenAgent(latest.agentId) })
                            } else {
                                append(names.agentLabel(latest.agentId))
                            }
                            append(" · $whenText\n")
                            append(initiated)
                        },
                        style = RaftTheme.typography.callout,
                        color = RaftTheme.colors.foreground,
                    )
                }
            }
        }
        if (residents.isNotEmpty()) {
            RaftSeparator()
            RaftText(
                counted(residents.size, Res.string.agent_migration_log_card_residents_one, Res.string.agent_migration_log_card_residents_many),
                style = RaftTheme.typography.caption,
                color = RaftTheme.colors.foregroundHint,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                residents.forEach { agent ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        names.avatar(agent.id, RowAvatar)
                        AgentMigrationLogAgentLink(agent.id, names, RaftTheme.typography.footnote)
                    }
                }
            }
        }
    }
}

private val RowAvatar = 27.dp
private val ListAvatar = 35.dp

@Composable
internal fun AgentMigrationRouteDetails(target: AgentMigrationChordTarget.Route, shown: List<AgentMigrationLogEntry>, names: AgentMigrationLogNames) {
    val moves = shown.filter { it.sourceMachineId == target.src && it.targetNode == target.dst && it.counts }
    if (moves.isEmpty()) return
    val ok = moves.filter { it.outcome == AgentMigrationLogOutcome.Completed }
    val bad = moves.filter { it.outcome == AgentMigrationLogOutcome.Failed }
    val durations = ok.mapNotNull { it.durationSeconds }.sorted()
    val separator = stringResource(Res.string.agent_migration_log_fmt_list_sep, "\u0001", "\u0002")
    val reasons = AgentMigrationLogFailureKind.entries.associateWith { agentMigrationReason(it) }
    Column(Modifier.testTag(AgentMigrationLogTags.RouteDetails), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RaftBadge(
                counted(ok.size, Res.string.agent_migration_log_card_succeeded_one, Res.string.agent_migration_log_card_succeeded_many),
                appearance = RaftBadgeAppearance.Soft,
                variant = RaftBadgeVariant.Success,
            )
            if (bad.isNotEmpty()) {
                RaftBadge(
                    counted(bad.size, Res.string.agent_migration_log_card_failed_one, Res.string.agent_migration_log_card_failed_many),
                    appearance = RaftBadgeAppearance.Soft,
                    variant = RaftBadgeVariant.Warning,
                    icon = { RaftSlotIcon(RaftLucideIconName.TriangleAlert) },
                )
            }
        }
        RaftDescriptionList(direction = RaftDescriptionListDirection.Inline) {
            Item(stringResource(Res.string.agent_migration_log_card_median_duration), agentMigrationLogDuration(durations.getOrNull(durations.size / 2)), mono = true)
            Item(
                stringResource(Res.string.agent_migration_log_card_first_latest),
                raftLocalizedShortDateTime(moves.first().startedAt) + " / " + raftLocalizedShortDateTime(moves.last().startedAt),
            )
            if (bad.isNotEmpty()) {
                Item(
                    stringResource(Res.string.agent_migration_log_card_reasons),
                    bad.groupingBy { it.failureKind ?: AgentMigrationLogFailureKind.Other }.eachCount()
                        .map { (kind, count) -> "${reasons.getValue(kind)} ×$count" }
                        .reduce { all, next -> separator.replace("\u0001", all).replace("\u0002", next) },
                )
            }
        }
        RaftSeparator()
        RaftText(stringResource(Res.string.agent_migration_log_card_agents_on_route), style = RaftTheme.typography.caption, color = RaftTheme.colors.foregroundHint)
        moves.groupBy { it.agentId }.forEach { (agent, mine) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                names.avatar(agent, RowAvatar)
                Box(Modifier.weight(1f)) { AgentMigrationLogAgentLink(agent, names, RaftTheme.typography.footnote) }
                RaftText(
                    movesText(mine.size),
                    style = RaftTheme.typography.footnote.copy(fontFamily = RaftTheme.typography.monoCode.fontFamily),
                    color = RaftTheme.colors.foregroundHint,
                )
            }
        }
    }
}

/** An agent's name, linked to its profile; plain text when it is no longer on the server. */
@Composable
internal fun AgentMigrationLogAgentLink(agentId: String, names: AgentMigrationLogNames, style: TextStyle = RaftTheme.typography.callout) {
    if (agentId in names.agents) {
        RaftTextLink(names.agentLabel(agentId), onClick = { names.onOpenAgent(agentId) }, variant = RaftTextLinkVariant.Strong, style = style)
    } else {
        RaftText(names.agentLabel(agentId), style = style, color = RaftTheme.colors.foreground)
    }
}

@Composable
private fun agentMigrationReason(kind: AgentMigrationLogFailureKind): String =
    stringResource(
        when (kind) {
            AgentMigrationLogFailureKind.TooLarge -> Res.string.agent_migration_log_reason_too_large
            AgentMigrationLogFailureKind.Network -> Res.string.agent_migration_log_reason_network
            AgentMigrationLogFailureKind.Other -> Res.string.agent_migration_log_reason_other
        },
    )

/** Who started a move, linked to their profile; a former member (the server names none) is plain text. */
@Composable
private fun agentMigrationInitiatedBy(entry: AgentMigrationLogEntry, names: AgentMigrationLogNames): AnnotatedString =
    agentMigrationPersonIn(stringResource(Res.string.agent_migration_log_fmt_initiated_by_name, "\u0001"), entry, names)

/** [detail], then who started that move. */
@Composable
private fun agentMigrationWithInitiator(detail: String, entry: AgentMigrationLogEntry, names: AgentMigrationLogNames): AnnotatedString =
    agentMigrationPersonIn(stringResource(Res.string.agent_migration_log_fmt_initiated_by, detail, "\u0001"), entry, names)

@Composable
private fun agentMigrationPersonIn(sentence: String, entry: AgentMigrationLogEntry, names: AgentMigrationLogNames): AnnotatedString {
    val person = entry.initiatedBy
    val former = stringResource(Res.string.agent_migration_log_former_member)
    return buildAnnotatedString {
        append(sentence.substringBefore("\u0001"))
        if (person != null) {
            appendRaftTextLink(person.displayName?.takeIf { it.isNotBlank() } ?: person.name, onClick = { names.onOpenPerson(person.id) })
        } else {
            append(former)
        }
        append(sentence.substringAfter("\u0001"))
    }
}

/** What the filters narrow the chart to: one agent's path, several agents, or the moves they leave. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AgentMigrationLogSide(
    filters: AgentMigrationLogFilters,
    shown: List<AgentMigrationLogEntry>,
    single: AgentMigrationJourney?,
    names: AgentMigrationLogNames,
    litAgent: String?,
    onLit: (String) -> Unit,
    onFilters: (AgentMigrationLogFilters) -> Unit,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val agentId = filters.agents.singleOrNull()
    when {
        agentId != null && single != null -> {
            val mine = shown.filter { it.agentId == agentId }
            Column(Modifier.fillMaxWidth().testTag(AgentMigrationLogTags.AgentSummary), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    names.avatar(agentId, ListAvatar)
                    Column {
                        AgentMigrationLogAgentLink(agentId, names, typography.headline)
                        names.agents[agentId]?.machineId?.let { names.nodeById[it] }?.let { now ->
                            RaftText(
                                stringResource(Res.string.agent_migration_log_side_now_on, now.name, now.code),
                                style = typography.caption,
                                color = colors.foregroundHint,
                            )
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val failed = mine.count { it.outcome == AgentMigrationLogOutcome.Failed }
                    val canceled = mine.count { it.outcome == AgentMigrationLogOutcome.Canceled }
                    RaftBadge(
                        counted(single.legs.size, Res.string.agent_migration_log_side_arrived_one, Res.string.agent_migration_log_side_arrived_many),
                        appearance = RaftBadgeAppearance.Soft,
                        variant = RaftBadgeVariant.Success,
                    )
                    RaftBadge(
                        counted(failed, Res.string.agent_migration_log_side_failed_one, Res.string.agent_migration_log_side_failed_many),
                        appearance = RaftBadgeAppearance.Soft,
                        variant = RaftBadgeVariant.Warning,
                    )
                    RaftBadge(
                        counted(canceled, Res.string.agent_migration_log_side_canceled_one, Res.string.agent_migration_log_side_canceled_many),
                        appearance = RaftBadgeAppearance.Soft,
                        variant = RaftBadgeVariant.Muted,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    RaftText(stringResource(Res.string.agent_migration_log_side_path), style = typography.caption, color = colors.foregroundHint)
                    AgentMigrationChain(single, names)
                }
                if (mine.isNotEmpty()) {
                    val visited = mine.flatMapTo(mutableSetOf()) { listOf(it.sourceMachineId, it.targetNode) }.size
                    RaftText(
                        counted(
                            visited,
                            Res.string.agent_migration_log_side_visited_one,
                            Res.string.agent_migration_log_side_visited_many,
                            raftLocalizedShortDateTime(mine.first().startedAt),
                        ),
                        style = typography.footnote,
                        color = colors.foregroundMuted,
                    )
                }
            }
        }
        filters.agents.size > 1 ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RaftText(
                    counted(filters.agents.size, Res.string.agent_migration_log_side_selected_touch_one, Res.string.agent_migration_log_side_selected_touch_many),
                    style = typography.caption,
                    color = colors.foregroundHint,
                )
                RaftListRowGroup(Modifier.fillMaxWidth()) {
                    filters.agents.forEachIndexed { index, id ->
                        AgentMigrationSelectedAgent(id, shown, names, lit = id == litAgent, divider = index > 0, onLit = { onLit(id) }, filters, onFilters)
                    }
                }
            }
        !filters.isFiltered ->
            RaftText(stringResource(Res.string.agent_migration_log_side_hint), style = typography.callout, color = colors.foregroundMuted)
        else -> {
            val failures = shown.asReversed().filter { it.outcome == AgentMigrationLogOutcome.Failed }.take(SideRows)
            val rows = failures.ifEmpty { shown.asReversed().take(SideRows) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (failures.isNotEmpty()) {
                    RaftText(stringResource(Res.string.agent_migration_log_side_failures), style = typography.caption, color = colors.foregroundHint)
                }
                RaftListRowGroup(Modifier.fillMaxWidth()) {
                    rows.forEachIndexed { index, entry ->
                        val route = "${names.code(entry.sourceMachineId)} → ${names.code(entry.targetNode)}"
                        val detail = entry.failureKind?.let { "$route · ${agentMigrationReason(it)}" } ?: route
                        RaftListRow(
                            title = names.agentLabel(entry.agentId),
                            onClick = if (entry.agentId in names.agents) ({ names.onOpenAgent(entry.agentId) }) else null,
                            divider = index > 0,
                            meta = raftLocalizedShortDateTime(entry.startedAt),
                            richDescription = agentMigrationWithInitiator(detail, entry, names),
                            wrapDescription = true,
                            leading = { names.avatar(entry.agentId, ListAvatar) },
                            // Under "Failed moves" every row is one.
                            trailing = if (failures.isEmpty()) ({ AgentMigrationLogOutcomeBadge(entry.outcome) }) else null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentMigrationSelectedAgent(
    id: String,
    shown: List<AgentMigrationLogEntry>,
    names: AgentMigrationLogNames,
    lit: Boolean,
    divider: Boolean,
    onLit: () -> Unit,
    filters: AgentMigrationLogFilters,
    onFilters: (AgentMigrationLogFilters) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val journey = remember(shown, id) { agentMigrationJourney(shown, id) }
    RaftListRow(
        title = names.agentLabel(id),
        onClick = onLit,
        selected = lit,
        divider = divider,
        meta = movesText(shown.count { it.agentId == id }),
        leading = { names.avatar(id, ListAvatar) },
        subtitleContent = { AgentMigrationChain(journey, names) },
        menuOpen = menuOpen,
        trailing = {
            Box {
                RaftIconButton(
                    onClick = { menuOpen = true },
                    tooltip = stringResource(Res.string.agent_migration_log_menu_more),
                    variant = RaftButtonVariant.Ghost,
                ) { RaftSlotIcon(RaftLucideIconName.EllipsisVertical) }
                RaftDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, alignEnd = true) {
                    RaftMenuLabel(names.agentLabel(id))
                    RaftMenuItem(
                        stringResource(Res.string.agent_migration_log_menu_only_agent, names.agentLabel(id)),
                        onClick = {
                            menuOpen = false
                            onFilters(filters.copy(agents = listOf(id)))
                        },
                        leadingIcon = { RaftSlotIcon(RaftLucideIconName.ListFilter) },
                    )
                    RaftMenuItem(
                        stringResource(Res.string.agent_migration_log_menu_remove_agent),
                        onClick = {
                            menuOpen = false
                            onFilters(filters.copy(agents = filters.agents - id))
                        },
                        leadingIcon = { RaftSlotIcon(RaftLucideIconName.UserMinus) },
                    )
                    if (id in names.agents) {
                        RaftMenuSeparator()
                        RaftMenuItem(
                            stringResource(Res.string.agent_migration_log_menu_open_profile),
                            onClick = {
                                menuOpen = false
                                names.onOpenAgent(id)
                            },
                            leadingIcon = { RaftSlotIcon(RaftLucideIconName.User) },
                        )
                    }
                }
            }
        },
    )
}

/** Codes along one agent's path, failed tries marked on the step they belong to. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgentMigrationChain(journey: AgentMigrationJourney, names: AgentMigrationLogNames) {
    val style = RaftTheme.typography.monoCode.copy(fontWeight = FontWeight.Bold)
    val ink = RaftTheme.colors.foreground

    fun failed(tries: List<AgentMigrationLogEntry>) = tries.count { it.outcome == AgentMigrationLogOutcome.Failed }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        journey.start?.let { RaftText(names.code(it), style = style, color = ink) }
        journey.legs.forEach { leg ->
            AgentMigrationChainArrow()
            val tries = failed(leg.tries)
            if (tries > 0) {
                RaftBadge(
                    counted(tries, Res.string.agent_migration_log_chain_failed_tries_one, Res.string.agent_migration_log_chain_failed_tries_many),
                    appearance = RaftBadgeAppearance.Outline,
                    variant = RaftBadgeVariant.Warning,
                )
            }
            RaftText(names.code(leg.entry.targetNode), style = style, color = ink)
        }
        val pending = failed(journey.pending)
        if (pending > 0) {
            AgentMigrationChainArrow()
            RaftBadge(
                counted(pending, Res.string.agent_migration_log_chain_pending_one, Res.string.agent_migration_log_chain_pending_many),
                appearance = RaftBadgeAppearance.Outline,
                variant = RaftBadgeVariant.Warning,
            )
        }
    }
}

@Composable
private fun AgentMigrationChainArrow() {
    RaftSlotIcon(RaftLucideIconName.ArrowRight, size = 12.dp, color = RaftTheme.colors.foregroundMuted)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AgentMigrationLogLegend() {
    val colors = RaftTheme.colors
    val swatch: @Composable (Color) -> Unit = { Box(Modifier.size(14.dp, 8.dp).background(it)) }
    Spacer(Modifier.height(16.dp))
    FlowRow(
        modifier = Modifier.fillMaxWidth().testTag(AgentMigrationLogTags.Legend),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AgentMigrationLegendItem(stringResource(Res.string.agent_migration_log_legend_ribbon)) {
            Canvas(Modifier.size(30.dp, 10.dp)) {
                val x = size.width / 30f
                val y = size.height / 10f
                val ribbon =
                    Path().apply {
                        moveTo(0f, 0f)
                        lineTo(0f, 10 * y)
                        lineTo(22 * x, 6 * y)
                        lineTo(28 * x, 5 * y)
                        lineTo(22 * x, 4 * y)
                        close()
                    }
                drawPath(ribbon, colors.foreground, alpha = 0.8f)
            }
        }
        AgentMigrationLegendItem(stringResource(Res.string.agent_migration_log_legend_ring1)) {
            swatch(colors.foreground)
            RaftText("/", style = RaftTheme.typography.footnote, color = colors.foregroundMuted)
            Box(Modifier.size(14.dp, 8.dp).border(1.5.dp, colors.foreground))
        }
        AgentMigrationLegendItem(stringResource(Res.string.agent_migration_log_legend_ring2)) { swatch(RaftUiPalette.success) }
        AgentMigrationLegendItem(stringResource(Res.string.agent_migration_log_legend_failing, "60%")) { swatch(colors.warningGraphic) }
        AgentMigrationLegendItem(stringResource(Res.string.agent_migration_log_legend_ring3)) { swatch(colors.foreground.copy(alpha = 0.7f)) }
    }
}

@Composable
private fun AgentMigrationLegendItem(text: String, mark: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { mark() }
        RaftText(text, style = RaftTheme.typography.footnote, color = RaftTheme.colors.foregroundMuted)
    }
}
