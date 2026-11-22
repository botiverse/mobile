package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.components.RaftSlotIcon
import build.raft.app.data.AgentMigrationLogEntry
import build.raft.app.data.AgentMigrationLogFilters
import build.raft.app.data.AgentMigrationLogLoad
import build.raft.app.data.AgentMigrationLogNode
import build.raft.app.data.AgentMigrationLogOutcome
import build.raft.app.data.agentMigrationLogAgentMoves
import build.raft.app.data.agentMigrationLogNodes
import build.raft.app.data.agentMigrationLogStats
import build.raft.app.data.defaultNowMillis
import build.raft.app.data.searchAgentMigrationLog
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftComboboxOption
import build.raft.designsystem.components.RaftDropdownMenu
import build.raft.designsystem.components.RaftEmptyState
import build.raft.designsystem.components.RaftMenuItem
import build.raft.designsystem.components.RaftMultiComboboxSheet
import build.raft.designsystem.components.RaftPickerTriggerButton
import build.raft.designsystem.components.RaftSeparator
import build.raft.designsystem.components.RaftSkeleton
import build.raft.designsystem.components.RaftTable
import build.raft.designsystem.components.RaftTableAlign
import build.raft.designsystem.components.RaftTableColumn
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.components.RaftTextFieldClearAction
import build.raft.designsystem.components.RaftTextFieldSize
import build.raft.designsystem.components.RaftTextLink
import build.raft.designsystem.components.RaftTextLinkVariant
import build.raft.designsystem.components.raftScrollEdgeFade
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

/** Test tags (web `data-testid`s). */
internal object AgentMigrationLogTags {
    const val Search = "migration-log-search"
    const val AgentsFilter = "migration-log-filter-agents"
    const val ComputersFilter = "migration-log-filter-computers"
    const val OutcomeFilter = "migration-log-filter-outcome"
    const val TimeFilter = "migration-log-filter-time"
    const val ClearAll = "migration-log-clear-all"
    const val Stats = "migration-log-stats"
    const val Truncated = "migration-log-truncated"
    const val NotAvailable = "migration-log-not-available"
    const val Table = "migration-log-table"
    const val TableEmpty = "migration-log-table-empty"
    const val More = "migration-log-table-more"
}

/** Web `min-w-180`: the least width the table's five columns read at; a phone scrolls it sideways. */
private val AgentMigrationLogTableMinWidth = 720.dp

/** Web `compact-row` (avatar `sm`) in a table row, `compact-list` (`xs`) in the agent filter. */
private val AgentMigrationLogRowAvatar = 27.dp
private val AgentMigrationLogFilterAvatar = 19.dp

/**
 * The agent migration log (web `MigrationLogPage`, its table view): the filters (computers, agents,
 * result, time and a search), the counts, and one row per move, the newest first.
 *
 * Pure: state in, intents out ([AgentMigrationLogPage.kt] wires the request, the computers and the
 * agents). [agentName] is null for an agent no longer on the server: a plain short id, no link.
 */
@Composable
internal fun AgentMigrationLogScreen(
    state: AgentMigrationLogState,
    /** The server's computers now: id to name. */
    machines: List<Pair<String, String>>,
    agentName: (String) -> String?,
    wide: Boolean,
    onIntent: (AgentMigrationLogIntent) -> Unit,
    onOpenAgent: (String) -> Unit,
    onBack: () -> Unit,
    avatar: @Composable (agentId: String, size: Dp) -> Unit,
    bottomContentPadding: Dp = 0.dp,
    nowMillis: () -> Long = ::defaultNowMillis,
) {
    val log = (state.load as? AgentMigrationLogLoad.Loaded)?.log
    val entries = log?.entries.orEmpty()
    val nodes = remember(entries, machines) { agentMigrationLogNodes(entries, machines) }
    val nodeById = remember(nodes) { nodes.associateBy { it.id } }
    val cloud = stringResource(Res.string.agent_migration_log_group_cloud)
    val nodeName: (AgentMigrationLogNode) -> String = { if (it.id == AgentMigrationLogEntry.CLOUD_NODE) cloud else it.name }
    val label: (String) -> String = { agentName(it) ?: it.take(8) }
    val shown = remember(entries, state.filters) { state.filters.apply(entries, nowMillis()) }
    RaftDesignSystemTheme {
        SettingsDetailShell(title = stringResource(Res.string.agent_migration_log_title), subtitle = "", onBack = onBack) {
            val margin = if (wide) 20.dp else 16.dp
            Column(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) {
                AgentMigrationLogToolbar(state, entries, nodes, nodeName, label, wide, margin, avatar, onIntent)
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = margin)) {
                    AgentMigrationLogStatsRow(shown, filteredOf = entries.size.takeIf { log != null && state.filters.isFiltered }, wide = wide)
                    if (log?.truncated == true) {
                        RaftText(
                            stringResource(Res.string.agent_migration_log_truncated),
                            modifier = Modifier.padding(top = 4.dp).testTag(AgentMigrationLogTags.Truncated),
                            style = RaftTheme.typography.caption,
                            color = RaftTheme.colors.foregroundHint,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    RaftSeparator()
                    Spacer(Modifier.height(16.dp))
                    when {
                        state.load == AgentMigrationLogLoad.Forbidden ->
                            AgentMigrationLogEmpty(
                                stringResource(Res.string.agent_migration_log_owners_and_admins_only),
                                RaftLucideIconName.TriangleAlert,
                                Modifier.testTag(AgentMigrationLogTags.NotAvailable),
                            )
                        state.load == AgentMigrationLogLoad.Failed ->
                            AgentMigrationLogEmpty(
                                stringResource(Res.string.agent_migration_log_load_failed),
                                RaftLucideIconName.TriangleAlert,
                                action = stringResource(Res.string.agent_migration_log_retry) to { onIntent(AgentMigrationLogIntent.Retry) },
                            )
                        log == null -> AgentMigrationLogSkeleton()
                        entries.isEmpty() ->
                            AgentMigrationLogEmpty(
                                stringResource(Res.string.agent_migration_log_empty_none_title),
                                RaftLucideIconName.Repeat,
                                description = stringResource(Res.string.agent_migration_log_empty_none_body),
                            )
                        shown.isEmpty() ->
                            AgentMigrationLogEmpty(
                                stringResource(Res.string.agent_migration_log_empty_filtered_title),
                                RaftLucideIconName.SearchX,
                                description = stringResource(Res.string.agent_migration_log_empty_filtered_body),
                                action =
                                    stringResource(Res.string.agent_migration_log_filter_clear_all) to
                                        { onIntent(AgentMigrationLogIntent.SetFilters(AgentMigrationLogFilters())) },
                            )
                        else -> {
                            val found =
                                remember(shown, state.query, nodeById, cloud, agentName) {
                                    searchAgentMigrationLog(shown, state.query) { entry ->
                                        listOf(label(entry.agentId), entry.sourceMachineName, entry.targetMachineName) +
                                            listOfNotNull(nodeById[entry.sourceMachineId], nodeById[entry.targetNode])
                                                .flatMap { listOf(it.code, nodeName(it)) }
                                    }
                                }
                            AgentMigrationLogTable(found, state, nodeById, nodeName, agentName, avatar, onIntent, onOpenAgent)
                        }
                    }
                    Spacer(Modifier.height(24.dp + bottomContentPadding))
                }
            }
        }
    }
}

/** The filters (DS Tasks › Filters): they wrap on a wide window and scroll sideways on a phone. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgentMigrationLogToolbar(
    state: AgentMigrationLogState,
    entries: List<AgentMigrationLogEntry>,
    nodes: List<AgentMigrationLogNode>,
    nodeName: (AgentMigrationLogNode) -> String,
    agentLabel: (String) -> String,
    wide: Boolean,
    margin: Dp,
    avatar: @Composable (agentId: String, size: Dp) -> Unit,
    onIntent: (AgentMigrationLogIntent) -> Unit,
) {
    val filters = state.filters
    val setFilters: (AgentMigrationLogFilters) -> Unit = { onIntent(AgentMigrationLogIntent.SetFilters(it)) }
    val moves = remember(entries) { agentMigrationLogAgentMoves(entries) }
    val controls: @Composable () -> Unit = {
        val searchLabel = stringResource(Res.string.agent_migration_log_table_search)
        RaftTextField(
            value = state.query,
            onValueChange = { onIntent(AgentMigrationLogIntent.Search(it)) },
            modifier =
                Modifier
                    .width(if (wide) 256.dp else 192.dp)
                    .testTag(AgentMigrationLogTags.Search)
                    .semantics { contentDescription = searchLabel },
            placeholder = searchLabel,
            leading = { RaftSlotIcon(RaftLucideIconName.Search, size = 14.dp) },
            trailingAction =
                if (state.query.isNotEmpty()) {
                    {
                        RaftTextFieldClearAction(
                            onClear = { onIntent(AgentMigrationLogIntent.Search("")) },
                            label = stringResource(Res.string.agent_migration_log_table_clear_search),
                        )
                    }
                } else {
                    null
                },
            size = RaftTextFieldSize.Md,
        )
        AgentMigrationLogListFilter(
            name = stringResource(Res.string.agent_migration_log_filter_agents),
            icon = RaftLucideIconName.Bot,
            searchPlaceholder = stringResource(Res.string.agent_migration_log_filter_search_agents),
            options = moves.map { (id, count) -> RaftComboboxOption(id, agentLabel(id), description = count.toString()) },
            chosen = filters.agents,
            chosenLabel = agentLabel,
            onChange = { setFilters(filters.copy(agents = it)) },
            testTag = AgentMigrationLogTags.AgentsFilter,
            optionLeading = { avatar(it, AgentMigrationLogFilterAvatar) },
        )
        AgentMigrationLogListFilter(
            name = stringResource(Res.string.agent_migration_log_filter_computers),
            icon = RaftLucideIconName.Monitor,
            searchPlaceholder = stringResource(Res.string.agent_migration_log_filter_search_computers),
            options = nodes.map { RaftComboboxOption(it.id, "${it.code} · ${nodeName(it)}") },
            chosen = filters.computers,
            // The trigger has room for a code, not a name.
            chosenLabel = { id -> nodes.firstOrNull { it.id == id }?.code ?: id },
            onChange = { setFilters(filters.copy(computers = it)) },
            testTag = AgentMigrationLogTags.ComputersFilter,
        )
        val outcomeLabels = AgentMigrationLogOutcome.entries.associateWith { agentMigrationLogOutcomeLabel(it) }
        AgentMigrationLogListFilter(
            name = stringResource(Res.string.agent_migration_log_filter_outcome),
            icon = RaftLucideIconName.CircleCheck,
            searchPlaceholder = stringResource(Res.string.agent_migration_log_filter_search_outcomes),
            options =
                listOf(AgentMigrationLogOutcome.Completed, AgentMigrationLogOutcome.Failed, AgentMigrationLogOutcome.Canceled)
                    .map { RaftComboboxOption(it, outcomeLabels.getValue(it)) },
            chosen = filters.outcomes,
            chosenLabel = { outcomeLabels.getValue(it) },
            onChange = { setFilters(filters.copy(outcomes = it)) },
            testTag = AgentMigrationLogTags.OutcomeFilter,
        )
        AgentMigrationLogTimeFilter(filters.days) { setFilters(filters.copy(days = it)) }
        if (filters.isFiltered) {
            RaftButton(
                text = stringResource(Res.string.agent_migration_log_filter_clear_all),
                onClick = { setFilters(AgentMigrationLogFilters()) },
                modifier = Modifier.testTag(AgentMigrationLogTags.ClearAll),
                variant = RaftButtonVariant.Ghost,
                size = RaftButtonSize.Md,
            )
        }
    }
    if (wide) {
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(start = margin, end = margin, top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) { controls() }
    } else {
        val scroll = rememberScrollState()
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .raftScrollEdgeFade(scroll)
                    .horizontalScroll(scroll)
                    .padding(horizontal = margin, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { controls() }
    }
}

/**
 * Web `ListFilter`: a trigger that opens a list with search, several values at once. Applied, it
 * fills: one value shows its name, more the filter's name and how many.
 */
@Composable
private fun <T> AgentMigrationLogListFilter(
    name: String,
    icon: RaftLucideIconName,
    searchPlaceholder: String,
    options: List<RaftComboboxOption<T>>,
    chosen: List<T>,
    chosenLabel: (T) -> String,
    onChange: (List<T>) -> Unit,
    testTag: String,
    optionLeading: (@Composable (T) -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val one = chosen.singleOrNull()?.let(chosenLabel)
    val spoken = one?.let { stringResource(Res.string.agent_migration_log_filter_value_label, name, it) }
    RaftPickerTriggerButton(
        text = one ?: if (chosen.size > 1) "$name ${chosen.size}" else name,
        onClick = {
            query = ""
            open = true
        },
        modifier = Modifier.testTag(testTag).semantics { if (spoken != null) contentDescription = spoken },
        hasValue = chosen.isNotEmpty(),
        leadingIcon = { RaftSlotIcon(icon) },
    )
    if (!open) return
    val noMatch = stringResource(Res.string.agent_migration_log_filter_no_match)
    RaftMultiComboboxSheet(
        values = chosen,
        options = options,
        onValuesChange = onChange,
        onDismissRequest = { open = false },
        title = name,
        emptyText = { noMatch },
        query = query,
        onQueryChange = { query = it },
        searchPlaceholder = searchPlaceholder,
        doneLabel = stringResource(Res.string.common_done),
        clearLabel = stringResource(Res.string.agent_migration_log_filter_clear),
        optionLeading = optionLeading?.let { leading -> { option -> leading(option.value) } },
    )
}

private val AgentMigrationLogDays = listOf(null, 7, 30)

/** Web `TimeFilter`: one of three ranges; only a range other than all time reads as applied. */
@Composable
private fun AgentMigrationLogTimeFilter(days: Int?, onChange: (Int?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val labels =
        AgentMigrationLogDays.associateWith {
            stringResource(
                when (it) {
                    null -> Res.string.agent_migration_log_filter_all_time
                    7 -> Res.string.agent_migration_log_filter_days7
                    else -> Res.string.agent_migration_log_filter_days30
                },
            )
        }
    val current = days.takeIf { it in AgentMigrationLogDays }
    val spoken = stringResource(Res.string.agent_migration_log_filter_value_label, stringResource(Res.string.agent_migration_log_filter_time), labels.getValue(current))
    Box {
        RaftPickerTriggerButton(
            text = labels.getValue(current),
            onClick = { open = true },
            modifier = Modifier.testTag(AgentMigrationLogTags.TimeFilter).semantics { contentDescription = spoken },
            hasValue = current != null,
            leadingIcon = { RaftSlotIcon(RaftLucideIconName.CalendarRange) },
        )
        RaftDropdownMenu(expanded = open, onDismissRequest = { open = false }, minWidth = 192.dp) {
            AgentMigrationLogDays.forEach { option ->
                RaftMenuItem(
                    text = labels.getValue(option),
                    checked = option == current,
                    onClick = {
                        open = false
                        onChange(option)
                    },
                )
            }
        }
    }
}

/** The counts over the moves that pass the filters: two columns on a phone, one line when wide. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgentMigrationLogStatsRow(shown: List<AgentMigrationLogEntry>, filteredOf: Int?, wide: Boolean) {
    val stats = remember(shown) { agentMigrationLogStats(shown) }
    val facts =
        listOf(
            stringResource(Res.string.agent_migration_log_stats_total) to stats.total.toString(),
            stringResource(Res.string.agent_migration_log_stats_success_rate) to (stats.successRate?.let { "$it%" } ?: "—"),
            stringResource(Res.string.agent_migration_log_stats_agents) to stats.agents.toString(),
            stringResource(Res.string.agent_migration_log_stats_computers) to stats.computers.toString(),
        )
    val filtered = filteredOf?.let { stringResource(Res.string.agent_migration_log_stats_filtered_of, it) }
    if (wide) {
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag(AgentMigrationLogTags.Stats),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            facts.forEach { (name, value) -> AgentMigrationLogStat(name, value, Modifier.alignByBaseline()) }
            if (filtered != null) AgentMigrationLogFilteredOf(filtered, Modifier.alignByBaseline())
        }
    } else {
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag(AgentMigrationLogTags.Stats),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            facts.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    pair.forEach { (name, value) -> AgentMigrationLogStat(name, value, Modifier.weight(1f).alignByBaseline()) }
                }
            }
            if (filtered != null) AgentMigrationLogFilteredOf(filtered)
        }
    }
}

@Composable
private fun AgentMigrationLogStat(name: String, value: String, modifier: Modifier = Modifier) {
    val typography = RaftTheme.typography
    val number =
        SpanStyle(
            color = RaftTheme.colors.foreground,
            fontFamily = typography.monoCode.fontFamily,
            fontSize = typography.callout.fontSize,
            fontWeight = FontWeight.Bold,
        )
    RaftText(
        buildAnnotatedString {
            append("$name ")
            withStyle(number) { append(value) }
        },
        modifier = modifier,
        style = typography.footnote,
        color = RaftTheme.colors.foregroundMuted,
    )
}

@Composable
private fun AgentMigrationLogFilteredOf(text: String, modifier: Modifier = Modifier) {
    RaftText(text, modifier = modifier, style = RaftTheme.typography.footnote, color = RaftTheme.colors.foregroundHint)
}

@Composable
private fun AgentMigrationLogEmpty(
    title: String,
    icon: RaftLucideIconName,
    modifier: Modifier = Modifier,
    description: String? = null,
    action: Pair<String, () -> Unit>? = null,
) {
    RaftEmptyState(
        title = title,
        // Web `py-16` over the component's own 48dp.
        modifier = modifier.padding(vertical = 16.dp),
        description = description,
        icon = { RaftSlotIcon(icon) },
        actions = action?.let { (label, onClick) -> { RaftButton(text = label, onClick = onClick, size = RaftButtonSize.Md) } },
    )
}

@Composable
private fun AgentMigrationLogSkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(6) { RaftSkeleton(Modifier.fillMaxWidth().height(40.dp)) }
    }
}

@Composable
private fun AgentMigrationLogTable(
    found: List<AgentMigrationLogEntry>,
    state: AgentMigrationLogState,
    nodeById: Map<String, AgentMigrationLogNode>,
    nodeName: (AgentMigrationLogNode) -> String,
    agentName: (String) -> String?,
    avatar: @Composable (agentId: String, size: Dp) -> Unit,
    onIntent: (AgentMigrationLogIntent) -> Unit,
    onOpenAgent: (String) -> Unit,
) {
    if (found.isEmpty()) {
        AgentMigrationLogEmpty(
            stringResource(Res.string.agent_migration_log_table_no_match, state.query.trim()),
            RaftLucideIconName.SearchX,
            Modifier.testTag(AgentMigrationLogTags.TableEmpty),
            action = stringResource(Res.string.agent_migration_log_table_clear_search) to { onIntent(AgentMigrationLogIntent.Search("")) },
        )
        return
    }
    val rows = remember(found, state.rowLimit) { found.asReversed().take(state.rowLimit) }
    val typography = RaftTheme.typography
    val colors = RaftTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RaftTable(
            columns =
                listOf(
                    RaftTableColumn(stringResource(Res.string.agent_migration_log_table_time)),
                    RaftTableColumn(stringResource(Res.string.agent_migration_log_table_agent)),
                    RaftTableColumn(stringResource(Res.string.agent_migration_log_table_route)),
                    RaftTableColumn(stringResource(Res.string.agent_migration_log_filter_outcome)),
                    RaftTableColumn(stringResource(Res.string.agent_migration_log_table_duration), RaftTableAlign.End),
                ),
            rowCount = rows.size,
            caption = stringResource(Res.string.agent_migration_log_table_caption),
            modifier = Modifier.testTag(AgentMigrationLogTags.Table),
            minWidth = AgentMigrationLogTableMinWidth,
            rowKey = { rows[it].migrationRef },
        ) { row, column ->
            val entry = rows[row]
            when (column) {
                0 ->
                    RaftText(
                        raftLocalizedShortDateTime(entry.startedAt),
                        style = typography.callout,
                        color = colors.foregroundMuted,
                        maxLines = 1,
                        softWrap = false,
                    )
                1 -> AgentMigrationLogAgent(entry.agentId, agentName(entry.agentId), avatar, onOpenAgent)
                2 -> AgentMigrationLogRoute(entry, nodeById, nodeName)
                3 -> AgentMigrationLogOutcomeBadge(entry.outcome)
                else ->
                    RaftText(
                        agentMigrationLogDuration(entry.durationSeconds),
                        style = typography.callout.copy(fontFamily = typography.monoCode.fontFamily),
                        color = colors.foreground,
                        maxLines = 1,
                        softWrap = false,
                    )
            }
        }
        val more = minOf(AGENT_MIGRATION_LOG_PAGE_SIZE, found.size - rows.size)
        if (more > 0) {
            RaftButton(
                text = stringResource(Res.string.agent_migration_log_table_show_more, more),
                onClick = { onIntent(AgentMigrationLogIntent.ShowMore) },
                modifier = Modifier.testTag(AgentMigrationLogTags.More),
                variant = RaftButtonVariant.Ghost,
                size = RaftButtonSize.Md,
            )
        }
    }
}

@Composable
private fun AgentMigrationLogAgent(
    agentId: String,
    name: String?,
    avatar: @Composable (agentId: String, size: Dp) -> Unit,
    onOpenAgent: (String) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        avatar(agentId, AgentMigrationLogRowAvatar)
        if (name != null) {
            RaftTextLink(name, onClick = { onOpenAgent(agentId) }, variant = RaftTextLinkVariant.Strong, style = RaftTheme.typography.callout)
        } else {
            RaftText(agentId.take(8), style = RaftTheme.typography.callout, color = RaftTheme.colors.foreground)
        }
    }
}

/**
 * Where it left and where it arrived: each place's code, then its name, and the name it had at the
 * time of the move when it was renamed since (what a search by that old name matched).
 */
@Composable
private fun AgentMigrationLogRoute(
    entry: AgentMigrationLogEntry,
    nodeById: Map<String, AgentMigrationLogNode>,
    nodeName: (AgentMigrationLogNode) -> String,
) {
    val typography = RaftTheme.typography
    val colors = RaftTheme.colors
    val source = nodeById[entry.sourceMachineId]
    val target = nodeById[entry.targetNode]
    val thenSource = agentMigrationLogThenName(entry.sourceMachineName, source?.let(nodeName))
    val thenTarget = agentMigrationLogThenName(entry.targetMachineName.takeUnless { entry.toCloud }, target?.let(nodeName))
    val code = SpanStyle(color = colors.foreground, fontFamily = typography.monoCode.fontFamily, fontWeight = FontWeight.Bold)
    val muted = SpanStyle(color = colors.foregroundMuted)
    val hint = SpanStyle(color = colors.foregroundHint, fontSize = typography.caption.fontSize)
    RaftText(
        buildAnnotatedString {
            fun place(node: AgentMigrationLogNode?, then: String?) {
                withStyle(code) { append(node?.code.orEmpty()) }
                append(" · ")
                withStyle(muted) { append(node?.let(nodeName).orEmpty()) }
                if (then != null) withStyle(hint) { append(" $then") }
            }
            place(source, thenSource)
            append(" → ")
            place(target, thenTarget)
        },
        style = typography.callout,
        color = colors.foreground,
    )
}

@Composable
private fun agentMigrationLogThenName(recorded: String?, now: String?): String? =
    recorded?.takeIf { it.isNotBlank() && !now.isNullOrBlank() && it != now }
        ?.let { stringResource(Res.string.agent_migration_log_table_then_name, it) }

@Composable
private fun agentMigrationLogOutcomeLabel(outcome: AgentMigrationLogOutcome): String =
    stringResource(
        when (outcome) {
            AgentMigrationLogOutcome.Completed -> Res.string.agent_migration_log_outcome_completed
            AgentMigrationLogOutcome.Failed -> Res.string.agent_migration_log_outcome_failed
            AgentMigrationLogOutcome.Canceled -> Res.string.agent_migration_log_outcome_canceled
            AgentMigrationLogOutcome.InProgress -> Res.string.agent_migration_log_outcome_in_progress
        },
    )

/** Web `OutcomeBadge` with `all`: every row of the table says its result. */
@Composable
private fun AgentMigrationLogOutcomeBadge(outcome: AgentMigrationLogOutcome) {
    RaftBadge(
        text = agentMigrationLogOutcomeLabel(outcome),
        appearance = RaftBadgeAppearance.Soft,
        variant =
            when (outcome) {
                AgentMigrationLogOutcome.Completed -> RaftBadgeVariant.Success
                AgentMigrationLogOutcome.Failed -> RaftBadgeVariant.Warning
                AgentMigrationLogOutcome.Canceled -> RaftBadgeVariant.Muted
                AgentMigrationLogOutcome.InProgress -> RaftBadgeVariant.Information
            },
        icon = if (outcome == AgentMigrationLogOutcome.Failed) ({ RaftSlotIcon(RaftLucideIconName.TriangleAlert) }) else null,
    )
}

/** Web `formatDuration`: "41s", "3m 12s", a dash while it runs. */
@Composable
private fun agentMigrationLogDuration(seconds: Long?): String =
    when {
        seconds == null -> "—"
        seconds < 60 -> stringResource(Res.string.agent_migration_log_duration_seconds, seconds)
        else -> stringResource(Res.string.agent_migration_log_duration_minutes, seconds / 60, seconds % 60)
    }
