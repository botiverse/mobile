package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import build.raft.app.components.SharedHomeSearchEditorOwner
import build.raft.app.data.InboxItem
import build.raft.app.data.SharedHomeSearchPanelState
import build.raft.app.data.SharedHomeSearchSavedIntent
import build.raft.app.data.SharedHomeState
import build.raft.app.components.SharedHomeSearchInput
import build.raft.designsystem.components.RaftSearchPanel
import build.raft.designsystem.components.RaftSearchPanelFooter
import build.raft.designsystem.components.raftSearchPanelKeyHints
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftKbd
import build.raft.designsystem.components.RaftKeyGlyph
import build.raft.designsystem.components.RaftSearchResultDescription
import build.raft.designsystem.components.RaftSearchResultIcon
import build.raft.designsystem.components.RaftSearchResultRow
import build.raft.designsystem.components.RaftSearchResultTitle
import build.raft.designsystem.components.RaftSearchSectionHeader
import build.raft.designsystem.components.RaftSearchShowAll
import build.raft.designsystem.components.raftSearchRowSelected
import build.raft.designsystem.theme.RaftTheme

/**
 * The wide layout's search panel mode of [SharedHomeSearchPanel] (web `SearchOverlay` +
 * `MessageSearchPage overlayChrome activateResultsInChat`): the same search store and field,
 * drawn in a [build.raft.designsystem.components.RaftSearchPanel] that hangs from the top bar's
 * field over the page.
 *
 * - [anchorTop] / [anchorWidth]: the top bar field's top edge and width in the panel window.
 * - [visitedThreadIds]: the conversations the wide layout showed, newest first (its history),
 *   for Recent conversations; [excludeThreadId] is the one behind the panel.
 * - [onDismiss]: close the panel (Escape, a click on the dimmed page, after a row opened).
 * - [onOpenFullResults]: "Search for": the full results page (the list column's Search) with the
 *   query given and the filters the store holds.
 */
@Immutable
internal class HomeSearchOverlayChrome(
    val anchorTop: Dp?,
    val anchorWidth: Dp,
    val visitedThreadIds: List<String>,
    val excludeThreadId: String?,
    val onDismiss: () -> Unit,
    val onOpenFullResults: (query: String) -> Unit,
)

/** What the panel's body shows: its rows by section, already capped. */
internal class HomeSearchOverlayContent(
    val typed: Boolean,
    val recent: List<SearchEntityResult>,
    val exact: SearchEntityResult?,
    /** Every destination but the exact one; the panel shows [HomeSearchOverlayEntityPreviewCount] unless expanded. */
    val entities: List<SearchEntityResult>,
    val entitiesExpanded: Boolean,
    val messages: List<HomeSearchResultGroup>,
    val messageCount: Int?,
    val searched: Boolean,
    val loading: Boolean,
) {
    val shownEntities: List<SearchEntityResult> =
        if (entitiesExpanded) entities else entities.take(HomeSearchOverlayEntityPreviewCount)

    val rows: List<HomeSearchOverlayRow> =
        homeSearchOverlayRows(
            recentKeys = if (typed) emptyList() else recent.map { "recent:${it.key}" },
            exactKey = exact?.key?.takeIf { typed },
            searchFor = typed,
            entityKeys = if (typed) shownEntities.map { it.key } else emptyList(),
            messageKeys = if (typed) messages.map { it.overlayKey } else emptyList(),
        )
}

internal val HomeSearchResultGroup.overlayKey: String
    get() =
        when (this) {
            is HomeSearchResultGroup.Message -> item.key
            is HomeSearchResultGroup.Thread -> key
        }

/** The item a message row opens: the hit itself, or a thread group's first hit. */
internal val HomeSearchResultGroup.firstItem: InboxItem?
    get() =
        when (this) {
            is HomeSearchResultGroup.Message -> item
            is HomeSearchResultGroup.Thread -> items.firstOrNull()
        }

/** One lazy item of the panel's body; [rowKey] is its [HomeSearchOverlayRow] key when the cursor can rest on it. */
internal sealed class HomeSearchOverlayEntry(val itemKey: String, val rowKey: String? = null) {
    object Empty : HomeSearchOverlayEntry("empty")
    object RecentHeading : HomeSearchOverlayEntry("recent-heading")
    class Recent(val result: SearchEntityResult) : HomeSearchOverlayEntry("recent:${result.key}", "recent:${result.key}")
    class Exact(val result: SearchEntityResult) : HomeSearchOverlayEntry("exact:${result.key}", result.key)
    object SearchFor : HomeSearchOverlayEntry(HomeSearchOverlaySearchForKey, HomeSearchOverlaySearchForKey)
    class EntitiesHeading(val count: Int) : HomeSearchOverlayEntry("entities-heading")
    class Entity(val result: SearchEntityResult) : HomeSearchOverlayEntry("entity:${result.key}", result.key)
    class ShowAll(val count: Int, val expanded: Boolean) : HomeSearchOverlayEntry("entities-show-all")
    class MessagesHeading(val count: Int?) : HomeSearchOverlayEntry("messages-heading")
    class Message(val group: HomeSearchResultGroup, val index: Int) : HomeSearchOverlayEntry("message:${group.overlayKey}", group.overlayKey)
    object Loading : HomeSearchOverlayEntry("loading")
    object NoResults : HomeSearchOverlayEntry("no-results")
}

/** The panel body's lazy items, in drawing order. */
internal fun HomeSearchOverlayContent.entries(): List<HomeSearchOverlayEntry> =
    buildList {
        if (!typed) {
            if (recent.isEmpty()) {
                add(HomeSearchOverlayEntry.Empty)
            } else {
                add(HomeSearchOverlayEntry.RecentHeading)
                recent.forEach { add(HomeSearchOverlayEntry.Recent(it)) }
            }
            return@buildList
        }
        exact?.let { add(HomeSearchOverlayEntry.Exact(it)) }
        add(HomeSearchOverlayEntry.SearchFor)
        if (entities.isNotEmpty()) {
            add(HomeSearchOverlayEntry.EntitiesHeading(entities.size))
            shownEntities.forEach { add(HomeSearchOverlayEntry.Entity(it)) }
            if (entities.size > HomeSearchOverlayEntityPreviewCount) {
                add(HomeSearchOverlayEntry.ShowAll(entities.size, entitiesExpanded))
            }
        }
        when {
            messages.isNotEmpty() -> {
                add(HomeSearchOverlayEntry.MessagesHeading(messageCount))
                messages.forEachIndexed { index, group -> add(HomeSearchOverlayEntry.Message(group, index)) }
            }
            loading && entities.isEmpty() && exact == null -> add(HomeSearchOverlayEntry.Loading)
            searched && entities.isEmpty() && exact == null -> add(HomeSearchOverlayEntry.NoResults)
        }
    }

/**
 * The panel's body: Recent conversations (or the "Search everything" state) with nothing typed;
 * once typed, the exact destination, "Search for", the destinations (5, then Show All), and up to
 * five messages. [selectedKey] is the keyboard cursor's row ([HomeSearchOverlayCursor]); the
 * list keeps it in view as it moves. Every row is at least 44dp tall and opens with a tap.
 */
@Composable
internal fun HomeSearchOverlayBody(
    content: HomeSearchOverlayContent,
    query: String,
    selectedKey: String?,
    listState: LazyListState,
    agentStatusResolver: RaftAgentStatusResolver,
    memberAvatarResolver: RaftMemberAvatarResolver,
    cacheServerId: String?,
    timeContext: AppShellThreadTimeContext?,
    onToggleEntities: () -> Unit,
    onOpenEntity: (SearchEntityResult) -> Unit,
    onOpenItem: (InboxItem) -> Unit,
    onOpenFullResults: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries = remember(content) { content.entries() }
    LazyColumn(state = listState, modifier = modifier.fillMaxWidth()) {
        items(entries, key = { it.itemKey }) { entry ->
            val selected = entry.rowKey != null && entry.rowKey == selectedKey
            when (entry) {
                HomeSearchOverlayEntry.Empty ->
                    RaftSearchEmptyState(
                        title = stringResource(Res.string.search_empty_title),
                        detail = stringResource(Res.string.search_empty_detail),
                        typography = RaftTheme.typography,
                        modifier = Modifier.fillParentMaxHeight(),
                    )
                HomeSearchOverlayEntry.RecentHeading ->
                    RaftSearchSectionHeader(stringResource(Res.string.search_overlay_recent_conversations), icon = RaftGlyph.Clock)
                is HomeSearchOverlayEntry.Recent ->
                    OverlayEntityRow(entry.result, "", selected, agentStatusResolver, memberAvatarResolver) { onOpenEntity(entry.result) }
                is HomeSearchOverlayEntry.Exact ->
                    OverlayEntityRow(entry.result, query, selected, agentStatusResolver, memberAvatarResolver) { onOpenEntity(entry.result) }
                HomeSearchOverlayEntry.SearchFor ->
                    RaftSearchResultRow(
                        onClick = onOpenFullResults,
                        selected = selected,
                        leading = { RaftSearchResultIcon(RaftGlyph.Search) },
                        description = { RaftSearchResultDescription(stringResource(Res.string.search_overlay_search_for_hint)) },
                        trailing = { RaftKbd(RaftKeyGlyph.Return) },
                    ) {
                        RaftSearchResultTitle(Res.string.search_overlay_search_for.format(listOf(query.trim())).localized())
                    }
                is HomeSearchOverlayEntry.EntitiesHeading ->
                    RaftSearchSectionHeader(stringResource(Res.string.search_section_entities), count = entry.count)
                is HomeSearchOverlayEntry.Entity ->
                    OverlayEntityRow(entry.result, query, selected, agentStatusResolver, memberAvatarResolver) { onOpenEntity(entry.result) }
                is HomeSearchOverlayEntry.ShowAll ->
                    RaftSearchShowAll(
                        label =
                            if (entry.expanded) {
                                stringResource(Res.string.search_show_fewer_entities)
                            } else {
                                Res.string.search_show_all_entities.format(listOf(entry.count)).localized()
                            },
                        onClick = onToggleEntities,
                    )
                is HomeSearchOverlayEntry.MessagesHeading ->
                    RaftSearchSectionHeader(stringResource(Res.string.search_section_messages), count = entry.count)
                is HomeSearchOverlayEntry.Message ->
                    Box(Modifier.raftSearchRowSelected(selected)) {
                        when (val group = entry.group) {
                            is HomeSearchResultGroup.Message ->
                                SharedHomeSearchMessageResultRow(
                                    item = group.item,
                                    query = query,
                                    showSeparator = false,
                                    cacheServerId = cacheServerId,
                                    memberAvatarResolver = memberAvatarResolver,
                                    timeContext = timeContext,
                                    onOpen = { onOpenItem(group.item) },
                                )
                            is HomeSearchResultGroup.Thread ->
                                SharedHomeThreadResultGroup(
                                    group = group,
                                    query = query,
                                    showSeparator = false,
                                    cacheServerId = cacheServerId,
                                    memberAvatarResolver = memberAvatarResolver,
                                    timeContext = timeContext,
                                    onOpenItem = onOpenItem,
                                )
                        }
                    }
                HomeSearchOverlayEntry.Loading -> RaftCenteredLoading(modifier = Modifier.fillMaxWidth().height(160.dp))
                HomeSearchOverlayEntry.NoResults ->
                    RaftSearchEmptyState(
                        title = Res.string.search_empty_results_query.format(listOf(query.trim())).localized(),
                        detail = stringResource(Res.string.search_empty_query_detail),
                        typography = RaftTheme.typography,
                    )
            }
        }
        item(key = "end") { Spacer(Modifier.height(8.dp)) }
    }
    // The cursor's row in view: ↑↓ through a long list would otherwise select an off-screen row.
    LaunchedEffect(selectedKey, entries) {
        val index = entries.indexOfFirst { it.rowKey != null && it.rowKey == selectedKey }
        if (index < 0) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo.filter { it.offset >= 0 && it.offset + it.size <= listState.layoutInfo.viewportEndOffset }
        if (visible.none { it.index == index }) listState.animateScrollToItem(index)
    }
}

@Composable
private fun OverlayEntityRow(
    result: SearchEntityResult,
    query: String,
    selected: Boolean,
    agentStatusResolver: RaftAgentStatusResolver,
    memberAvatarResolver: RaftMemberAvatarResolver,
    onOpen: () -> Unit,
) {
    Box(Modifier.raftSearchRowSelected(selected)) {
        SharedHomeSearchEntityRow(
            result = result,
            query = query,
            agentStatusResolver = agentStatusResolver,
            memberAvatarResolver = memberAvatarResolver,
            onOpen = onOpen,
        )
    }
}

/**
 * [SharedHomeSearchPanel] as the wide layout's search panel: the same store, field, filters and
 * rows in a [RaftSearchPanel]. The field (with its Esc hint) heads it, the filters show once
 * something is typed, and the footer lists the keys. ↑ / ↓ move the cursor through the rows,
 * Return opens the cursor's row; a tap opens a row as well, so it works without a keyboard. A
 * row that opens a conversation or page closes the panel; "Search for" hands over to the full
 * results page.
 */
@Composable
internal fun HomeSearchOverlayPanel(
    overlay: HomeSearchOverlayChrome,
    state: SharedHomeSearchPanelState,
    homeState: SharedHomeState,
    entityResults: List<SearchEntityResult>,
    frequentEntityResults: List<SearchEntityResult>,
    groupedResults: List<HomeSearchResultGroup>,
    editor: SharedHomeSearchEditorOwner,
    focusRequester: FocusRequester,
    fieldModifier: Modifier,
    filterMenuOpen: Boolean,
    filters: @Composable () -> Unit,
    popups: @Composable () -> Unit,
    cacheServerId: String?,
    agentStatusResolver: RaftAgentStatusResolver,
    memberAvatarResolver: RaftMemberAvatarResolver,
    timeContext: AppShellThreadTimeContext?,
    listState: LazyListState,
    onIntent: (SharedHomeSearchSavedIntent) -> Unit,
    onOpenItem: (InboxItem) -> Unit,
    onOpenEntity: (SearchEntityResult) -> Unit,
) {
    val typed = state.query.isNotBlank()
    val channelLabel = stringResource(Res.string.search_entity_channel)
    val dmLabel = stringResource(Res.string.search_entity_dm)
    val agentLabel = stringResource(Res.string.search_entity_agent)
    val personLabel = stringResource(Res.string.search_entity_person)
    val membersLabel = stringResource(Res.string.search_entity_members)
    val recent =
        remember(overlay.visitedThreadIds, overlay.excludeThreadId, homeState, frequentEntityResults, channelLabel, dmLabel) {
            homeSearchOverlayRecentConversations(
                visitedThreadIds = overlay.visitedThreadIds,
                candidates =
                    homeSearchEntityCandidates(
                        homeState = homeState,
                        senderFilters = emptyList(),
                        channelLabel = channelLabel,
                        dmLabel = dmLabel,
                        agentLabel = agentLabel,
                        personLabel = personLabel,
                        membersLabel = membersLabel,
                    ).map { it.result },
                fill = frequentEntityResults,
                excludeThreadId = overlay.excludeThreadId,
            )
        }
    // Expanded for this server and query only: a new query starts collapsed (web).
    var entitiesExpanded by remember(state.serverId, state.query.trim()) { mutableStateOf(false) }
    val exactKey = homeSearchOverlayExactKey(state.query, entityResults)
    val exact = exactKey?.let { key -> entityResults.firstOrNull { it.key == key } }
    val content =
        HomeSearchOverlayContent(
            typed = typed,
            recent = recent,
            exact = exact,
            entities = entityResults.filter { it.key != exactKey },
            entitiesExpanded = entitiesExpanded,
            // Flat message rows, as the web panel: a thread hit shows "#channel · thread" on its
            // own row rather than under its thread's heading (the full page groups them).
            messages =
                groupedResults
                    .flatMap { group ->
                        when (group) {
                            is HomeSearchResultGroup.Message -> listOf(group)
                            is HomeSearchResultGroup.Thread -> group.items.map(HomeSearchResultGroup::Message)
                        }
                    }.take(HomeSearchOverlayMessagePreviewCount),
            messageCount = state.totalCount.takeIf { it > 0 && !state.loading },
            searched = state.searched,
            loading = state.loading || state.querySearchPending,
        )
    val rows = content.rows
    var cursor by remember { mutableStateOf(HomeSearchOverlayCursor()) }
    // A palette's cursor returns to the top row whenever the query changes (web task #102).
    LaunchedEffect(state.query, exactKey) { cursor = cursor.reset() }
    val selected = cursor.selected(rows)
    val openEntity: (SearchEntityResult) -> Unit = { result ->
        onOpenEntity(result)
        overlay.onDismiss()
    }
    val openItem: (InboxItem) -> Unit = { item ->
        onOpenItem(item)
        overlay.onDismiss()
    }
    val openRow: (HomeSearchOverlayRow) -> Unit = { row ->
        when (row.section) {
            HomeSearchOverlaySection.Recent ->
                recent.firstOrNull { "recent:${it.key}" == row.key }?.let(openEntity)
            HomeSearchOverlaySection.Exact, HomeSearchOverlaySection.Entities ->
                entityResults.firstOrNull { it.key == row.key }?.let(openEntity)
            HomeSearchOverlaySection.SearchFor -> overlay.onOpenFullResults(state.query)
            HomeSearchOverlaySection.Messages ->
                content.messages.firstOrNull { it.overlayKey == row.key }?.firstItem?.let(openItem)
        }
    }
    val filterSlot: (@Composable ColumnScope.() -> Unit)? = if (typed) { { filters() } } else null
    val select = stringResource(Res.string.search_overlay_footer_select)
    val open = stringResource(Res.string.search_overlay_footer_open)
    Box(Modifier.fillMaxSize()) {
        RaftSearchPanel(
            onDismissRequest = overlay.onDismiss,
            anchorTop = overlay.anchorTop,
            anchorWidth = overlay.anchorWidth,
            modifier =
                Modifier.onPreviewKeyEvent { event ->
                    // A filter menu keeps its own keys.
                    if (filterMenuOpen) return@onPreviewKeyEvent false
                    val action =
                        homeSearchOverlayKeyAction(
                            key = event.key,
                            keyDown = event.type == KeyEventType.KeyDown,
                            shift = event.isShiftPressed,
                        ) ?: return@onPreviewKeyEvent false
                    when (action) {
                        is HomeSearchOverlayKeyAction.Move -> cursor = cursor.move(rows, action.delta)
                        HomeSearchOverlayKeyAction.Open -> selected?.let(openRow) ?: return@onPreviewKeyEvent false
                    }
                    true
                },
            head = {
                Box(Modifier.fillMaxWidth().padding(12.dp)) {
                    SharedHomeSearchInput(
                        query = editor.value,
                        loading = state.loading,
                        interactive = true,
                        placeholder = stringResource(Res.string.search_placeholder),
                        focusRequester = focusRequester,
                        onQueryChange = { next ->
                            editor.acceptEditorValue(next) { onIntent(SharedHomeSearchSavedIntent.SearchQueryChanged(it)) }
                        },
                        // Return is the cursor's (above); the IME's Search action opens the full page.
                        onSubmit = { overlay.onOpenFullResults(state.query) },
                        onClear = { editor.clear { onIntent(SharedHomeSearchSavedIntent.SearchQueryChanged("")) } },
                        raftField = true,
                        trailingHint = RaftKeyGlyph.Escape,
                        modifier = Modifier.fillMaxWidth().then(fieldModifier),
                    )
                }
            },
            filters = filterSlot,
            footer = { RaftSearchPanelFooter(raftSearchPanelKeyHints(select = select, open = open)) },
        ) {
            HomeSearchOverlayBody(
                content = content,
                query = state.query,
                selectedKey = selected?.key,
                listState = listState,
                agentStatusResolver = agentStatusResolver,
                memberAvatarResolver = memberAvatarResolver,
                cacheServerId = cacheServerId,
                timeContext = timeContext,
                onToggleEntities = { entitiesExpanded = !entitiesExpanded },
                onOpenEntity = openEntity,
                onOpenItem = openItem,
                onOpenFullResults = { overlay.onOpenFullResults(state.query) },
                modifier = Modifier.weight(1f),
            )
        }
        popups()
    }
    // The field takes focus as the panel opens, so typing and the arrow keys work at once.
    LaunchedEffect(Unit) {
        delay(50)
        runCatching { focusRequester.requestFocus() }
    }
}
