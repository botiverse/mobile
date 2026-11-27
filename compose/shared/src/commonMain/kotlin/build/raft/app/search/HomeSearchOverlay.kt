package build.raft.app

import build.raft.app.compat.ui.raftImeBottomPx
import androidx.compose.ui.platform.LocalDensity
import build.raft.app.resources.*
import build.raft.app.thread.icon
import build.raft.app.thread.label
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import build.raft.app.components.SharedHomeSearchEditorOwner
import build.raft.app.data.InboxItem
import build.raft.app.data.SearchArtifactHit
import build.raft.app.data.SearchArtifactsIntent
import build.raft.app.data.SearchArtifactsState
import build.raft.app.data.SearchArtifactsStatus
import build.raft.app.data.SearchCoreGroupsIntent
import build.raft.app.data.ScreenError
import build.raft.app.data.SharedHomeSearchPanelState
import build.raft.app.data.SharedHomeSearchSavedIntent
import build.raft.app.data.SharedHomeState
import build.raft.app.label
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
import androidx.compose.ui.platform.testTag
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
import build.raft.designsystem.components.raftSearchRowSelected

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
 * - [focusRequest]: advances with each ⌘K / Ctrl+K while the panel is open: the field takes
 *   focus again with its text selected (web routeGlobalSearchShortcut "focused").
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
    val focusRequest: Int = 0,
)

/** What the panel's body shows: its rows by section, already capped. */
internal class HomeSearchOverlayContent(
    val typed: Boolean,
    val recent: List<SearchEntityResult>,
    val exact: SearchEntityResult?,
    /**
     * The destinations by group (web composeSearchEntityGroups), the exact one left out; each shows
     * [HomeSearchEntityPreviewCount] unless its group is in [expandedGroups].
     */
    val entityGroups: List<HomeSearchEntityGroupView>,
    val expandedGroups: Set<HomeSearchEntityGroupId> = emptySet(),
    /** Core's groups couldn't all be asked: the line under "Search for" (web incomplete note). */
    val coreGroupsNote: HomeSearchCoreGroupsNote? = null,
    val messages: List<HomeSearchResultGroup>,
    val messageCount: Int?,
    val searched: Boolean,
    val loading: Boolean,
    /** The plan's history window in days (web `historyNotice`), or null: no notice. */
    val historyWindowDays: Int? = null,
    /** Where a search the server stopped part way got to (web `incompleteSearchNote`), or null. */
    val incompleteNote: String? = null,
    /** The Artifacts group (web app group, previewed like the messages), or null: off. */
    val artifacts: SearchArtifactsState? = null,
) {
    val shownEntities: List<SearchEntityResult> = entityGroups.flatMap { it.shown(it.id in expandedGroups) }

    val hasEntities: Boolean = entityGroups.isNotEmpty()

    /** The Artifacts the panel previews (the store asks for 5; web `appGroupPreviewLimit`). */
    val shownArtifacts: List<SearchArtifactHit> =
        artifacts?.takeIf { it.status == SearchArtifactsStatus.Ok }?.hits.orEmpty().take(HomeSearchOverlayMessagePreviewCount)

    /** The group's section shows: waiting, failed, or with hits. */
    val artifactsShown: Boolean = artifacts?.shown == true

    val rows: List<HomeSearchOverlayRow> =
        homeSearchOverlayRows(
            recentKeys = if (typed) emptyList() else recent.map { "recent:${it.key}" },
            exactKey = exact?.key?.takeIf { typed },
            searchFor = typed,
            entityKeys = if (typed) shownEntities.map { it.key } else emptyList(),
            artifactKeys = if (typed) shownArtifacts.map(::homeSearchArtifactKey) else emptyList(),
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
    class HistoryNotice(val days: Int) : HomeSearchOverlayEntry("history-notice")
    object RecentHeading : HomeSearchOverlayEntry("recent-heading")
    class Recent(val result: SearchEntityResult) : HomeSearchOverlayEntry("recent:${result.key}", "recent:${result.key}")
    class Exact(val result: SearchEntityResult) : HomeSearchOverlayEntry("exact:${result.key}", result.key)
    object SearchFor : HomeSearchOverlayEntry(HomeSearchOverlaySearchForKey, HomeSearchOverlaySearchForKey)
    class CoreGroupsNote(val note: HomeSearchCoreGroupsNote) : HomeSearchOverlayEntry("core-groups-note")
    class EntitiesHeading(val group: HomeSearchEntityGroupView, val expanded: Boolean) : HomeSearchOverlayEntry("entities-heading:${group.id.testTag}")
    class Entity(val result: SearchEntityResult) : HomeSearchOverlayEntry("entity:${result.key}", result.key)
    class EntitiesMore(val group: HomeSearchEntityGroupView) : HomeSearchOverlayEntry("entities-more:${group.id.testTag}")
    class ArtifactsHeading(val state: SearchArtifactsState) : HomeSearchOverlayEntry("artifacts-heading")
    object ArtifactsLoading : HomeSearchOverlayEntry("artifacts-loading")
    class ArtifactsFailed(val state: SearchArtifactsState) : HomeSearchOverlayEntry("artifacts-failed")
    class Artifact(val hit: SearchArtifactHit) : HomeSearchOverlayEntry("artifact:${hit.id}", homeSearchArtifactKey(hit))
    class MessagesHeading(val count: Int?) : HomeSearchOverlayEntry("messages-heading")
    class Message(val group: HomeSearchResultGroup, val index: Int) : HomeSearchOverlayEntry("message:${group.overlayKey}", group.overlayKey)
    object Loading : HomeSearchOverlayEntry("loading")
    object NoResults : HomeSearchOverlayEntry("no-results")
    class NoMatchesYet(val note: String) : HomeSearchOverlayEntry("no-matches-yet")
}

/** The panel body's lazy items, in drawing order. */
internal fun HomeSearchOverlayContent.entries(): List<HomeSearchOverlayEntry> =
    buildList {
        // web MessageSearchPage `historyNotice`: a Free server says so on every search, typed or not.
        historyWindowDays?.let { add(HomeSearchOverlayEntry.HistoryNotice(it)) }
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
        coreGroupsNote?.let { add(HomeSearchOverlayEntry.CoreGroupsNote(it)) }
        entityGroups.forEach { group ->
            val expanded = group.id in expandedGroups
            add(HomeSearchOverlayEntry.EntitiesHeading(group, expanded))
            group.shown(expanded).forEach { add(HomeSearchOverlayEntry.Entity(it)) }
            if (group.showsMore(expanded)) add(HomeSearchOverlayEntry.EntitiesMore(group))
        }
        // web MessageSearchPage: the app groups between the destinations and the messages.
        artifacts?.takeIf { artifactsShown }?.let { group ->
            add(HomeSearchOverlayEntry.ArtifactsHeading(group))
            when (group.status) {
                SearchArtifactsStatus.Loading -> add(HomeSearchOverlayEntry.ArtifactsLoading)
                SearchArtifactsStatus.Failed -> add(HomeSearchOverlayEntry.ArtifactsFailed(group))
                else -> shownArtifacts.forEach { add(HomeSearchOverlayEntry.Artifact(it)) }
            }
        }
        when {
            messages.isNotEmpty() -> {
                add(HomeSearchOverlayEntry.MessagesHeading(messageCount))
                messages.forEachIndexed { index, group -> add(HomeSearchOverlayEntry.Message(group, index)) }
            }
            loading && !hasEntities && exact == null && shownArtifacts.isEmpty() -> add(HomeSearchOverlayEntry.Loading)
            // web `showNoResults`: not while an app group waits, failed or has hits.
            searched && !hasEntities && exact == null && !artifactsShown ->
                // web SearchNoResultsState `searchIncomplete`: not "no results" while the server
                // has more to read.
                add(incompleteNote?.let(HomeSearchOverlayEntry::NoMatchesYet) ?: HomeSearchOverlayEntry.NoResults)
        }
    }

/**
 * The panel's body: Recent conversations (or the "Search everything" state) with nothing typed;
 * once typed, the exact destination, "Search for", the destinations (5, then Show All), and up to
 * five messages. [selectedKey] is the keyboard cursor's row ([HomeSearchOverlayCursor]); the
 * list keeps it in view as it moves. Every row is at least 44dp tall and opens with a tap. A mouse
 * over a row lays the list cell's hover on it without moving the cursor (web: only ↑ / ↓ move it).
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
    onToggleEntities: (HomeSearchEntityGroupId) -> Unit,
    onOpenEntity: (SearchEntityResult) -> Unit,
    onOpenItem: (InboxItem) -> Unit,
    onOpenFullResults: () -> Unit,
    modifier: Modifier = Modifier,
    loadingMore: Boolean = false,
    onContinueSearching: () -> Unit = {},
    onHistoryUpgrade: (() -> Unit)? = null,
    artifactsContext: HomeSearchArtifactsContext = HomeSearchArtifactsContext(query, null, timeContext),
    onArtifactsIntent: (SearchArtifactsIntent) -> Unit = {},
    onOpenArtifact: (SearchArtifactHit) -> Unit = {},
    onCoreGroupsIntent: (SearchCoreGroupsIntent) -> Unit = {},
    coreGroupsError: ScreenError? = null,
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
                        modifier = Modifier.fillParentMaxHeight(),
                    )
                is HomeSearchOverlayEntry.HistoryNotice ->
                    HomeSearchHistoryWindowBanner(days = entry.days, onUpgrade = onHistoryUpgrade)
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
                is HomeSearchOverlayEntry.CoreGroupsNote ->
                    HomeSearchCoreGroupsNoteLine(entry.note, coreGroupsError, onRetry = { onCoreGroupsIntent(SearchCoreGroupsIntent.Retry) })
                is HomeSearchOverlayEntry.EntitiesHeading ->
                    RaftSearchSectionHeader(
                        entry.group.id.label(),
                        modifier = Modifier.testTag("${HomeSearchEntityGroupTags.Group}-${entry.group.id.testTag}"),
                        count = entry.group.count,
                        trailing =
                            if (entry.group.showsToggle(entry.expanded)) {
                                { HomeSearchEntityShowAllToggle(entry.expanded) { onToggleEntities(entry.group.id) } }
                            } else {
                                null
                            },
                    )
                is HomeSearchOverlayEntry.Entity ->
                    OverlayEntityRow(entry.result, query, selected, agentStatusResolver, memberAvatarResolver) { onOpenEntity(entry.result) }
                is HomeSearchOverlayEntry.EntitiesMore ->
                    HomeSearchEntityGroupMore(entry.group) { entry.group.id.loadMoreIntent()?.let(onCoreGroupsIntent) }
                is HomeSearchOverlayEntry.ArtifactsHeading -> HomeSearchArtifactsHeading(entry.state, overlay = true)
                HomeSearchOverlayEntry.ArtifactsLoading -> HomeSearchArtifactsSkeleton()
                is HomeSearchOverlayEntry.ArtifactsFailed ->
                    HomeSearchArtifactsFailed(entry.state) { onArtifactsIntent(SearchArtifactsIntent.Retry) }
                is HomeSearchOverlayEntry.Artifact ->
                    HomeSearchArtifactHitRow(entry.hit, artifactsContext, selected = selected, onOpen = { onOpenArtifact(entry.hit) })
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
                                    selected = selected,
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
                                    selected = selected,
                                    cacheServerId = cacheServerId,
                                    memberAvatarResolver = memberAvatarResolver,
                                    timeContext = timeContext,
                                    onOpenItem = onOpenItem,
                                )
                        }
                    }
                HomeSearchOverlayEntry.Loading -> RaftCenteredLoading(modifier = Modifier.fillMaxWidth().height(160.dp))
                is HomeSearchOverlayEntry.NoMatchesYet ->
                    HomeSearchIncompleteEmptyState(
                        note = entry.note,
                        loadingMore = loadingMore,
                        onContinueSearching = onContinueSearching,
                    )
                HomeSearchOverlayEntry.NoResults ->
                    RaftSearchEmptyState(
                        // web SearchNoResultsState `onlyLoadedEntities`.
                        title =
                            if (content.coreGroupsNote != null) {
                                stringResource(Res.string.search_core_groups_no_loaded_matches)
                            } else {
                                Res.string.search_empty_results_query.format(listOf(query.trim())).localized()
                            },
                        detail = stringResource(Res.string.search_empty_query_detail),
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
            selected = selected,
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
    entityGroups: HomeSearchEntityGroups,
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
    historyWindowDays: Int? = null,
    incompleteNote: String? = null,
    onHistoryUpgrade: (() -> Unit)? = null,
    artifacts: SearchArtifactsState? = null,
    artifactsContext: HomeSearchArtifactsContext = HomeSearchArtifactsContext(state.query, null, timeContext),
    onArtifactsIntent: (SearchArtifactsIntent) -> Unit = {},
    onOpenArtifact: (SearchArtifactHit) -> Unit = {},
    coreGroupsNote: HomeSearchCoreGroupsNote? = null,
    onCoreGroupsIntent: (SearchCoreGroupsIntent) -> Unit = {},
) {
    val typed = state.query.isNotBlank()
    val entityResults = entityGroups.results
    val channelLabel = stringResource(Res.string.search_entity_channel)
    val dmLabel = stringResource(Res.string.search_entity_dm)
    val agentLabel = stringResource(Res.string.search_entity_agent)
    val personLabel = stringResource(Res.string.search_entity_person)
    val membersLabel = stringResource(Res.string.search_entity_members)
    val recent =
        remember(overlay.visitedThreadIds, overlay.excludeThreadId, homeState, frequentEntityResults, channelLabel, dmLabel, agentLabel, personLabel, membersLabel) {
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
    var expandedGroups by remember(state.serverId, state.query.trim()) { mutableStateOf(emptySet<HomeSearchEntityGroupId>()) }
    val exactKey = homeSearchOverlayExactKey(state.query, entityResults)
    val exact = exactKey?.let { key -> entityResults.firstOrNull { it.key == key } }
    val content =
        HomeSearchOverlayContent(
            typed = typed,
            recent = recent,
            exact = exact,
            entityGroups =
                entityGroups.groups.mapNotNull { group ->
                    group.copy(results = group.results.filter { it.key != exactKey }).takeIf { it.results.isNotEmpty() }
                },
            expandedGroups = expandedGroups,
            coreGroupsNote = coreGroupsNote,
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
            loading = state.loading || state.querySearchPending || entityGroups.pending,
            historyWindowDays = historyWindowDays,
            incompleteNote = incompleteNote,
            artifacts = artifacts,
        )
    val rows = content.rows
    var cursor by remember { mutableStateOf(HomeSearchOverlayCursor()) }
    var fieldFocused by remember { mutableStateOf(false) }
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
    // web #12764: the panel has no detail area, so an Artifact opens on the full search page (the
    // panel hands its words over, as "Search for" does), its viewer over it.
    val openArtifact: (SearchArtifactHit) -> Unit = { hit ->
        overlay.onOpenFullResults(state.query)
        onOpenArtifact(hit)
    }
    val openRow: (HomeSearchOverlayRow) -> Unit = { row ->
        when (row.section) {
            HomeSearchOverlaySection.Recent ->
                recent.firstOrNull { "recent:${it.key}" == row.key }?.let(openEntity)
            HomeSearchOverlaySection.Exact, HomeSearchOverlaySection.Entities ->
                entityResults.firstOrNull { it.key == row.key }?.let(openEntity)
            HomeSearchOverlaySection.SearchFor -> overlay.onOpenFullResults(state.query)
            HomeSearchOverlaySection.Artifacts ->
                content.shownArtifacts.firstOrNull { homeSearchArtifactKey(it) == row.key }?.let(openArtifact)
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
            // The host's keyboard where Compose's IME inset doesn't carry it (iOS).
            keyboardHeight = with(LocalDensity.current) { raftImeBottomPx(this).toDp() },
            modifier =
                Modifier.onPreviewKeyEvent { event ->
                    // A filter menu keeps its own keys, and a focused chip or button its Return.
                    if (filterMenuOpen || !fieldFocused) return@onPreviewKeyEvent false
                    val action =
                        homeSearchOverlayKeyAction(
                            key = event.key,
                            keyDown = event.type == KeyEventType.KeyDown,
                            shift = event.isShiftPressed,
                            // Return confirming an input method's word is the field's (web isComposing).
                            composing = editor.value.composition != null,
                        ) ?: return@onPreviewKeyEvent false
                    when (action) {
                        is HomeSearchOverlayKeyAction.Move -> cursor = cursor.move(rows, action.delta)
                        HomeSearchOverlayKeyAction.Open -> selected?.let(openRow) ?: return@onPreviewKeyEvent false
                    }
                    true
                },
            head = {
                Box(Modifier.fillMaxWidth().padding(12.dp).onFocusChanged { fieldFocused = it.hasFocus }) {
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
                onToggleEntities = { id -> expandedGroups = if (id in expandedGroups) expandedGroups - id else expandedGroups + id },
                onOpenEntity = openEntity,
                onOpenItem = openItem,
                onOpenFullResults = { overlay.onOpenFullResults(state.query) },
                modifier = Modifier.weight(1f),
                loadingMore = state.loadingMore,
                onContinueSearching = { onIntent(SharedHomeSearchSavedIntent.LoadMoreSearch) },
                onHistoryUpgrade =
                    onHistoryUpgrade?.let { upgrade ->
                        {
                            overlay.onDismiss()
                            upgrade()
                        }
                    },
                artifactsContext = artifactsContext,
                onArtifactsIntent = onArtifactsIntent,
                onOpenArtifact = openArtifact,
                onCoreGroupsIntent = onCoreGroupsIntent,
                coreGroupsError = entityGroups.error,
            )
        }
        popups()
    }
    // The field takes focus as the panel opens, so typing and the arrow keys work at once, and
    // again (its text selected) with each ⌘K while it is open.
    LaunchedEffect(Unit) {
        delay(50)
        runCatching { focusRequester.requestFocus() }
    }
    LaunchedEffect(overlay.focusRequest) {
        if (overlay.focusRequest == 0) return@LaunchedEffect
        runCatching { focusRequester.requestFocus() }
        editor.selectAll()
    }
}
