package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.components.RaftSlotIcon
import build.raft.app.data.ArtifactHome
import build.raft.app.data.ArtifactKind
import build.raft.app.data.ArtifactsHomeActor
import build.raft.app.data.ArtifactsHomeChoiceKind
import build.raft.app.data.ArtifactsHomeChoicesPhase
import build.raft.app.data.ArtifactsHomeConversation
import build.raft.app.data.ArtifactsHomeIntent
import build.raft.app.data.ArtifactsHomeNews
import build.raft.app.data.ArtifactsHomePlace
import build.raft.app.data.ArtifactsHomeRow
import build.raft.app.data.ArtifactsHomeScope
import build.raft.app.data.ArtifactsHomeSort
import build.raft.app.data.ArtifactsHomeState
import build.raft.app.data.ArtifactsHomeStatus
import build.raft.app.data.ArtifactsHomeView
import build.raft.app.data.ARTIFACTS_HOME_SEARCH_MAX_CHARS
import build.raft.app.markdown.lucideIcon
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftComboboxOption
import build.raft.designsystem.components.RaftComboboxSheet
import build.raft.designsystem.components.RaftDropdownMenu
import build.raft.designsystem.components.RaftEmptyState
import build.raft.designsystem.components.RaftIconTile
import build.raft.designsystem.components.RaftIconTileSize
import build.raft.designsystem.components.RaftMenuItem
import build.raft.designsystem.components.RaftPickerTriggerButton
import build.raft.designsystem.components.RaftSegment
import build.raft.designsystem.components.RaftSegmentedControl
import build.raft.designsystem.components.RaftSkeleton
import build.raft.designsystem.components.RaftSkeletonVariant
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.components.raftClickable
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke

/**
 * The Artifacts home page (web `ArtifactsDashboard.tsx` + `ArtifactDashboardList.tsx`, slock staging
 * aae2b7a89): the head (title, "5 of 24 Artifacts"), the filter bar (title search, sort, then the
 * Conversation and Author filters, 和我有关 and Clear all), then every Artifact the reader can see in
 * the server as tiles (the default) or rows, Load more for the next page. The skeleton while the first
 * page loads, a warning with Try again when a read fails (rows stay under it), an empty state by
 * cause, and the off state when workspace_artifacts_v0 is off. A row opens the in-app viewer.
 *
 * Pure: state in, intents out ([ArtifactsHomeRoute] wires the store, the flag and the viewer).
 */
@Composable
internal fun ArtifactsHomeScreen(
    state: ArtifactsHomeState,
    view: ArtifactsHomeView,
    readerId: String?,
    serverName: String?,
    /** The shell's wide layout: tiles in auto-fill columns of at least 248dp. */
    wide: Boolean,
    onIntent: (ArtifactsHomeIntent) -> Unit,
    onView: (ArtifactsHomeView) -> Unit,
    onOpen: (ArtifactsHomeRow) -> Unit,
    onBack: () -> Unit,
    /** The tile's cover: the card's screenshot where one is read (none in tests). */
    cover: @Composable (ArtifactsHomeRow) -> Unit = { ArtifactsHomeTilePlaceholder(it.summary.kind) },
) {
    RaftDesignSystemTheme {
        SettingsDetailShell(
            title = stringResource(Res.string.artifacts_dashboard_title),
            subtitle = "",
            onBack = onBack,
            titleContent = { ArtifactsHomeTitle(state) },
            trailingContent =
                if (state.status != ArtifactsHomeStatus.Off) {
                    { ArtifactsHomeViewToggle(view, onView) }
                } else {
                    null
                },
        ) {
            Column(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas).testTag(ArtifactsHomeTags.Page)) {
                if (state.status == ArtifactsHomeStatus.Off) {
                    ArtifactsHomeEmpty(stringResource(Res.string.artifacts_dashboard_off_title), testTag = ArtifactsHomeTags.Off)
                    return@Column
                }
                ArtifactsHomeToolbar(state, serverName, readerId, onIntent)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    ArtifactsHomeList(state, view, readerId, serverName, wide, onIntent, onOpen, cover)
                }
            }
        }
    }
}

/** Test tags (web `data-testid`s). */
internal object ArtifactsHomeTags {
    const val Page = "artifacts-dashboard"
    const val Off = "artifacts-dashboard-off"
    const val Count = "artifacts-dashboard-count"
    const val Search = "artifacts-all-search"
    const val Sort = "artifacts-all-sort"
    const val Conversation = "artifacts-all-conversation-filter"
    const val Author = "artifacts-all-author-filter"
    const val Relevant = "artifacts-all-relevant"
    const val Clear = "artifacts-all-toolbar-clear"
    const val Skeleton = "artifacts-all-skeleton"
    const val Failed = "artifacts-all-failed"
    const val Empty = "artifacts-all-empty"
    const val EmptyAction = "artifacts-all-empty-action"
    const val Row = "artifacts-all-row"
    const val Tile = "artifacts-dashboard-row"
    const val More = "artifacts-all-more"
    const val ViewGrid = "artifacts-dashboard-view-grid"
    const val ViewList = "artifacts-dashboard-view-list"
}

@Composable
private fun ArtifactsHomeTitle(state: ArtifactsHomeState) {
    Column(Modifier.padding(vertical = 2.dp)) {
        RaftText(
            stringResource(Res.string.artifacts_dashboard_title),
            style = RaftTheme.typography.headline,
            color = RaftTheme.colors.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val counts = state.counts
        if (counts != null) {
            RaftText(
                artifactsHomeCountLine(counts.total, counts.filtered, state.countsNarrowed),
                modifier = Modifier.testTag(ArtifactsHomeTags.Count),
                style = RaftTheme.typography.footnote,
                color = RaftTheme.colors.foregroundMuted,
                maxLines = 1,
            )
        }
    }
}

/** web `countLine`: "24 Artifacts", and "5 of 24 Artifacts" with any filter on. */
@Composable
internal fun artifactsHomeCountLine(total: Int, filtered: Int, narrowed: Boolean): String =
    if (narrowed) {
        stringResource(if (total == 1) Res.string.artifacts_dashboard_count_filtered_one else Res.string.artifacts_dashboard_count_filtered_many, filtered, total)
    } else {
        stringResource(if (total == 1) Res.string.artifacts_dashboard_count_one else Res.string.artifacts_dashboard_count_many, total)
    }

/** web `ViewToggle`: grid first, icons only. */
@Composable
private fun ArtifactsHomeViewToggle(view: ArtifactsHomeView, onView: (ArtifactsHomeView) -> Unit) {
    val viewLabel = stringResource(Res.string.artifacts_dashboard_view_label)
    RaftSegmentedControl(modifier = Modifier.semantics { contentDescription = viewLabel }, iconsOnlyWhenLabelsDontFit = true) {
        RaftSegment(
            text = stringResource(Res.string.artifacts_dashboard_view_grid),
            selected = view == ArtifactsHomeView.Grid,
            onClick = { onView(ArtifactsHomeView.Grid) },
            modifier = Modifier.testTag(ArtifactsHomeTags.ViewGrid),
            iconsOnlyIcon = { RaftSlotIcon(RaftLucideIconName.LayoutGrid) },
        )
        RaftSegment(
            text = stringResource(Res.string.artifacts_dashboard_view_list),
            selected = view == ArtifactsHomeView.List,
            onClick = { onView(ArtifactsHomeView.List) },
            modifier = Modifier.testTag(ArtifactsHomeTags.ViewList),
            iconsOnlyIcon = { RaftSlotIcon(RaftLucideIconName.LayoutList) },
        )
    }
}

/**
 * web `DashboardToolbar` (phone): the search, the sort; then the filters in a row that scrolls
 * sideways. Clear all only with both filters set (each has its own Clear in its list).
 */
@Composable
private fun ArtifactsHomeToolbar(state: ArtifactsHomeState, serverName: String?, readerId: String?, onIntent: (ArtifactsHomeIntent) -> Unit) {
    val colors = RaftTheme.colors
    var sortOpen by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().background(colors.layerCanvas).padding(horizontal = ArtifactsHomeMargin, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val searchLabel = stringResource(Res.string.artifacts_dashboard_search_label)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RaftTextField(
                value = state.search,
                onValueChange = { onIntent(ArtifactsHomeIntent.Search(it.take(ARTIFACTS_HOME_SEARCH_MAX_CHARS))) },
                modifier = Modifier.weight(1f).testTag(ArtifactsHomeTags.Search).semantics { contentDescription = searchLabel },
                placeholder = stringResource(Res.string.artifacts_dashboard_search_placeholder),
                leading = { RaftSlotIcon(RaftLucideIconName.Search) },
            )
            // 和我有关 fixes the order: no sort then (its value is kept for when it goes off).
            if (!state.query.relevant) {
                Box {
                    val sortLabel = artifactsHomeSortLabel(state.query.sort)
                    // web: the trigger is named "Sort: Recently opened" (on a phone it is the icon alone there).
                    val sortName = stringResource(Res.string.artifacts_dashboard_sort_label, sortLabel)
                    RaftPickerTriggerButton(
                        text = sortLabel,
                        onClick = { sortOpen = true },
                        modifier = Modifier.testTag(ArtifactsHomeTags.Sort).semantics { contentDescription = sortName },
                        hasValue = state.query.sort != ArtifactsHomeSort.Default,
                        leadingIcon = { RaftSlotIcon(RaftLucideIconName.ArrowDownUp) },
                    )
                    RaftDropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }, alignEnd = true, minWidth = 200.dp) {
                        ArtifactsHomeSort.entries.forEach { sort ->
                            RaftMenuItem(
                                text = artifactsHomeSortLabel(sort),
                                checked = sort == state.query.sort,
                                modifier = Modifier.testTag("${ArtifactsHomeTags.Sort}-${sort.wire}"),
                                onClick = {
                                    sortOpen = false
                                    onIntent(ArtifactsHomeIntent.SetSort(sort))
                                },
                            )
                        }
                    }
                }
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "conversation") { ArtifactsHomeConversationFilter(state, serverName, onIntent) }
            item(key = "author") { ArtifactsHomeAuthorFilter(state, readerId, onIntent) }
            item(key = "relevant") {
                val count = state.counts?.relevant?.takeIf { it > 0 }
                val label = stringResource(Res.string.artifacts_dashboard_relevant)
                RaftPickerTriggerButton(
                    text = if (count != null) "$label $count" else label,
                    onClick = { onIntent(ArtifactsHomeIntent.SetRelevant(!state.query.relevant)) },
                    // web `aria-pressed`: a switch, read as on or off (review M3).
                    modifier =
                        Modifier.testTag(ArtifactsHomeTags.Relevant).semantics {
                            toggleableState = ToggleableState(state.query.relevant)
                            selected = state.query.relevant
                        },
                    hasValue = state.query.relevant,
                    showIndicator = false,
                    leadingIcon = { RaftSlotIcon(RaftLucideIconName.Inbox) },
                )
            }
            if (state.query.place != null && state.query.author != null) {
                item(key = "clear") {
                    RaftPickerTriggerButton(
                        text = stringResource(Res.string.artifacts_dashboard_clear_filters),
                        onClick = { onIntent(ArtifactsHomeIntent.ClearFilters) },
                        modifier = Modifier.testTag(ArtifactsHomeTags.Clear),
                        showIndicator = false,
                        leadingIcon = { RaftSlotIcon(RaftLucideIconName.X) },
                    )
                }
            }
        }
    }
}

@Composable
private fun artifactsHomeSortLabel(sort: ArtifactsHomeSort): String =
    stringResource(
        when (sort) {
            ArtifactsHomeSort.Opened -> Res.string.artifacts_dashboard_sort_opened
            ArtifactsHomeSort.Updated -> Res.string.artifacts_dashboard_sort_updated
        },
    )

private const val HomeValuePrefix = "home:"

/**
 * web `ArtifactConversationFilter`: under "Who can see it" the two homes that are no conversation,
 * then "Channels & DMs" (`dashboardConversations`, the server searching them). One value.
 */
@Composable
private fun ArtifactsHomeConversationFilter(state: ArtifactsHomeState, serverName: String?, onIntent: (ArtifactsHomeIntent) -> Unit) {
    val choices = state.conversationChoices
    val place = state.query.place
    val whoTitle = stringResource(Res.string.artifacts_dashboard_who_title)
    val groupTitle = stringResource(Res.string.artifacts_dashboard_conversation_group)
    val homeLabels = ArtifactsHomeScope.entries.associateWith { artifactsHomeScopeLabel(it, serverName) }
    val valueLabel =
        when (place) {
            is ArtifactsHomePlace.Home -> homeLabels.getValue(place.scope)
            is ArtifactsHomePlace.Conversation -> place.conversation.label
            null -> null
        }
    val conversationName =
        valueLabel?.let { stringResource(Res.string.artifacts_dashboard_conversation_applied, it) }
            ?: stringResource(Res.string.artifacts_dashboard_conversation_label)
    RaftPickerTriggerButton(
        text = valueLabel ?: stringResource(Res.string.artifacts_dashboard_conversation_label),
        onClick = { onIntent(ArtifactsHomeIntent.OpenChoices(ArtifactsHomeChoiceKind.Conversation)) },
        // web: named by the filter and its value ("Conversation: #proj").
        modifier = Modifier.testTag(ArtifactsHomeTags.Conversation).semantics { contentDescription = conversationName },
        hasValue = place != null,
        leadingIcon = { RaftSlotIcon(artifactsHomePlaceIcon(place)) },
    )
    if (!choices.open) return
    val conversations = state.shownConversationChoices
    val options =
        ArtifactsHomeScope.entries.map { scope ->
            RaftComboboxOption(value = HomeValuePrefix + scope.wire, label = homeLabels.getValue(scope), group = whoTitle)
        } +
            conversations.map { conversation ->
                RaftComboboxOption(
                    value = conversation.id,
                    label = conversation.label,
                    group = groupTitle,
                    // The server matched it (a DM's peer by handle too): keep it under the same words here.
                    keywords = listOfNotNull(choices.text, conversation.name, conversation.peerDisplayName),
                    description = if (conversation.peer?.type == "agent") stringResource(Res.string.artifacts_dashboard_agent_tag) else null,
                )
            }
    val failedText = stringResource(Res.string.artifacts_dashboard_conversation_failed)
    val loadingText = stringResource(Res.string.common_loading)
    val noMatch = stringResource(Res.string.artifacts_dashboard_conversation_no_match)
    RaftComboboxSheet(
        value = when (place) {
            is ArtifactsHomePlace.Home -> HomeValuePrefix + place.scope.wire
            is ArtifactsHomePlace.Conversation -> place.conversation.id
            null -> null
        },
        options = options,
        onValueChange = { value ->
            val scope = ArtifactsHomeScope.entries.firstOrNull { HomeValuePrefix + it.wire == value }
            val next =
                if (scope != null) {
                    ArtifactsHomePlace.Home(scope)
                } else {
                    conversations.firstOrNull { it.id == value }?.let { ArtifactsHomePlace.Conversation(it) }
                }
            onIntent(ArtifactsHomeIntent.SetPlace(next))
        },
        onDismissRequest = { onIntent(ArtifactsHomeIntent.CloseChoices(ArtifactsHomeChoiceKind.Conversation)) },
        title = stringResource(Res.string.artifacts_dashboard_conversation_label),
        emptyText = { artifactsHomeChoicesEmptyText(choices.phase, failedText, loadingText, noMatch) },
        query = choices.text,
        onQueryChange = { onIntent(ArtifactsHomeIntent.ChoicesQuery(ArtifactsHomeChoiceKind.Conversation, it)) },
        searchPlaceholder = stringResource(Res.string.artifacts_dashboard_conversation_search_placeholder),
        onClear = { onIntent(ArtifactsHomeIntent.SetPlace(null)) },
        clearLabel = stringResource(Res.string.artifacts_dashboard_conversation_clear),
    )
}

private fun artifactsHomeChoicesEmptyText(phase: ArtifactsHomeChoicesPhase, failed: String, loading: String, noMatch: String): String =
    when (phase) {
        ArtifactsHomeChoicesPhase.Failed -> failed
        ArtifactsHomeChoicesPhase.Pending, ArtifactsHomeChoicesPhase.Idle -> loading
        else -> noMatch
    }

private fun artifactsHomePlaceIcon(place: ArtifactsHomePlace?): RaftLucideIconName =
    when (place) {
        null -> RaftLucideIconName.MessageSquare
        is ArtifactsHomePlace.Home -> if (place.scope == ArtifactsHomeScope.Public) RaftLucideIconName.Globe else RaftLucideIconName.Users
        is ArtifactsHomePlace.Conversation ->
            when (place.conversation.type) {
                "private" -> RaftLucideIconName.Lock
                "dm" -> RaftLucideIconName.MessageSquare
                else -> RaftLucideIconName.Hash
            }
    }

/** web `artifacts.scope.*`: "Anyone with the link", "Everyone in Northwind Labs" (or "Whole server" with no name). */
@Composable
internal fun artifactsHomeScopeLabel(scope: ArtifactsHomeScope, serverName: String?): String =
    when (scope) {
        ArtifactsHomeScope.Public -> stringResource(Res.string.artifacts_scope_public)
        ArtifactsHomeScope.Server ->
            serverName?.takeIf { it.isNotBlank() }?.let { stringResource(Res.string.artifacts_dashboard_scope_server_named, it) }
                ?: stringResource(Res.string.artifacts_scope_server)
    }

/**
 * web `ArtifactAuthorFilter`: who wrote what the reader can see (`dashboardAuthors`, most first), an
 * agent saying whose assistant it is. An older server without the route: the authors on screen.
 */
@Composable
private fun ArtifactsHomeAuthorFilter(state: ArtifactsHomeState, readerId: String?, onIntent: (ArtifactsHomeIntent) -> Unit) {
    val choices = state.authorChoices
    val chosen = state.authorActor
    val unknown = stringResource(Res.string.artifacts_dashboard_author_unknown)
    val authorLabel = chosen?.let { artifactsHomeActorName(it, unknown) }
    val authorName =
        authorLabel?.let { stringResource(Res.string.artifacts_dashboard_author_applied, it) }
            ?: stringResource(Res.string.artifacts_dashboard_author_label)
    RaftPickerTriggerButton(
        text = authorLabel ?: stringResource(Res.string.artifacts_dashboard_author_label),
        onClick = { onIntent(ArtifactsHomeIntent.OpenChoices(ArtifactsHomeChoiceKind.Author)) },
        modifier = Modifier.testTag(ArtifactsHomeTags.Author).semantics { contentDescription = authorName },
        hasValue = chosen != null,
        leadingIcon = { RaftSlotIcon(RaftLucideIconName.CircleUser) },
    )
    if (!choices.open) return
    val authors = state.shownAuthorChoices
    val agentTag = stringResource(Res.string.artifacts_dashboard_agent_tag)
    val yourAssistant = stringResource(Res.string.artifacts_dashboard_author_your_assistant)
    val options =
        authors.mapNotNull { actor ->
            val value = actor.filterValue ?: return@mapNotNull null
            val hint =
                if (actor.type != "agent") {
                    null
                } else {
                    val behalf = actor.onBehalfOf
                    when {
                        behalf == null -> agentTag
                        behalf.id == readerId -> yourAssistant
                        else -> stringResource(Res.string.artifacts_dashboard_author_assistant_of, behalf.displayName)
                    }
                }
            RaftComboboxOption(value = value, label = artifactsHomeActorName(actor, unknown), description = hint, keywords = listOfNotNull(choices.text, actor.name, hint))
        }
    val failedText = stringResource(Res.string.artifacts_dashboard_author_failed)
    val loadingText = stringResource(Res.string.common_loading)
    val noMatch = stringResource(Res.string.artifacts_dashboard_author_no_match)
    RaftComboboxSheet(
        value = state.query.author,
        options = options,
        onValueChange = { value -> onIntent(ArtifactsHomeIntent.SetAuthor(authors.firstOrNull { it.filterValue == value })) },
        onDismissRequest = { onIntent(ArtifactsHomeIntent.CloseChoices(ArtifactsHomeChoiceKind.Author)) },
        title = stringResource(Res.string.artifacts_dashboard_author_label),
        emptyText = { artifactsHomeChoicesEmptyText(choices.phase, failedText, loadingText, noMatch) },
        query = choices.text,
        onQueryChange = { onIntent(ArtifactsHomeIntent.ChoicesQuery(ArtifactsHomeChoiceKind.Author, it)) },
        searchPlaceholder = stringResource(Res.string.artifacts_dashboard_author_search_placeholder),
        onClear = { onIntent(ArtifactsHomeIntent.SetAuthor(null)) },
        clearLabel = stringResource(Res.string.artifacts_dashboard_author_clear),
    )
}

/** web `artifactActorName`: the display name, else the handle; a gone actor as "Someone". */
private fun artifactsHomeActorName(actor: ArtifactsHomeActor, unknown: String): String =
    actor.displayName.takeIf { it.isNotBlank() } ?: actor.name.takeIf { it.isNotBlank() } ?: unknown

@Composable
private fun ArtifactsHomeList(
    state: ArtifactsHomeState,
    view: ArtifactsHomeView,
    readerId: String?,
    serverName: String?,
    wide: Boolean,
    onIntent: (ArtifactsHomeIntent) -> Unit,
    onOpen: (ArtifactsHomeRow) -> Unit,
    cover: @Composable (ArtifactsHomeRow) -> Unit,
) {
    val rows = state.rows
    val failed: @Composable () -> Unit = {
        if (state.status == ArtifactsHomeStatus.Failed) {
            Column(Modifier.fillMaxWidth().padding(horizontal = ArtifactsHomeMargin, vertical = 8.dp).testTag(ArtifactsHomeTags.Failed)) {
                RaftBanner(
                    description = stringResource(Res.string.artifacts_dashboard_failed_title),
                    status = RaftBannerStatus.Warning,
                    action = {
                        RaftButton(
                            text = stringResource(Res.string.artifacts_dashboard_retry),
                            onClick = { onIntent(ArtifactsHomeIntent.Retry) },
                            variant = RaftButtonVariant.Default,
                        )
                    },
                )
                RaftTraceIdNote(state.error?.traceId, Modifier.padding(top = 4.dp))
            }
        }
    }
    when {
        rows == null && state.status == ArtifactsHomeStatus.Failed -> failed()
        rows == null -> ArtifactsHomeSkeleton()
        rows.isEmpty() ->
            Column(Modifier.fillMaxSize()) {
                failed()
                ArtifactsHomeEmptyByCause(state, onIntent)
            }
        view == ArtifactsHomeView.Grid ->
            LazyVerticalGrid(
                columns = if (wide) GridCells.Adaptive(248.dp) else GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = ArtifactsHomeMargin, end = ArtifactsHomeMargin, top = 8.dp, bottom = 24.dp + raftBottomSafeAreaPadding()),
                horizontalArrangement = Arrangement.spacedBy(if (wide) 12.dp else 8.dp),
                verticalArrangement = Arrangement.spacedBy(if (wide) 12.dp else 8.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "failed") { failed() }
                itemsIndexed(rows, key = { _, row -> row.summary.id }) { _, row ->
                    ArtifactsHomeTile(row, readerId, serverName, state.rowsByOpened, wide, onOpen = { onOpen(row) }, cover = cover)
                }
                item(span = { GridItemSpan(maxLineSpan) }, key = "more") { ArtifactsHomeMore(state, onIntent) }
            }
        else ->
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp + raftBottomSafeAreaPadding()),
            ) {
                item(key = "failed") { failed() }
                itemsIndexed(rows, key = { _, row -> row.summary.id }) { _, row ->
                    ArtifactsHomeListRow(row, readerId, serverName, state.rowsByOpened, onOpen = { onOpen(row) })
                }
                item(key = "more") { ArtifactsHomeMore(state, onIntent) }
            }
    }
}

/** web `InboxItemSkeletonList` count 3, compact, leading. */
@Composable
private fun ArtifactsHomeSkeleton() {
    Column(Modifier.fillMaxWidth().padding(horizontal = ArtifactsHomeMargin, vertical = 8.dp).testTag(ArtifactsHomeTags.Skeleton), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(3) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RaftSkeleton(Modifier.size(RaftIconTileSize.Md.edge))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    RaftSkeleton(Modifier.fillMaxWidth(0.6f), variant = RaftSkeletonVariant.Line)
                    RaftSkeleton(Modifier.fillMaxWidth(0.4f), variant = RaftSkeletonVariant.Line)
                }
            }
        }
    }
}

/** web `DashboardList` `empty`: by cause, the way out a narrowed list has. */
@Composable
private fun ArtifactsHomeEmptyByCause(state: ArtifactsHomeState, onIntent: (ArtifactsHomeIntent) -> Unit) {
    val query = state.query
    val filtered = query.place != null || query.author != null
    val searched = query.q.isNotEmpty()
    val narrowed = searched || filtered
    if (query.relevant && !narrowed) {
        ArtifactsHomeEmpty(stringResource(Res.string.artifacts_dashboard_empty_relevant_title), testTag = ArtifactsHomeTags.Empty) {
            RaftButton(
                text = stringResource(Res.string.artifacts_dashboard_see_all),
                onClick = { onIntent(ArtifactsHomeIntent.SetRelevant(false)) },
                modifier = Modifier.testTag(ArtifactsHomeTags.EmptyAction),
                variant = RaftButtonVariant.Default,
            )
        }
        return
    }
    val title =
        when {
            searched -> Res.string.artifacts_dashboard_no_results
            filtered -> Res.string.artifacts_dashboard_empty_filtered_title
            else -> Res.string.artifacts_dashboard_empty_title
        }
    val clearLabel =
        when {
            searched && filtered -> Res.string.artifacts_dashboard_clear_all
            searched -> Res.string.artifacts_dashboard_clear_search
            else -> Res.string.artifacts_dashboard_clear_filters
        }
    ArtifactsHomeEmpty(
        stringResource(title),
        testTag = ArtifactsHomeTags.Empty,
        action =
            if (narrowed) {
                {
                    RaftButton(
                        text = stringResource(clearLabel),
                        onClick = { onIntent(ArtifactsHomeIntent.ClearSearchAndFilters) },
                        modifier = Modifier.testTag(ArtifactsHomeTags.EmptyAction),
                        variant = RaftButtonVariant.Default,
                    )
                }
            } else {
                null
            },
    )
}

@Composable
private fun ArtifactsHomeEmpty(title: String, testTag: String, action: (@Composable () -> Unit)? = null) {
    Box(Modifier.fillMaxSize().testTag(testTag), contentAlignment = Alignment.Center) {
        RaftEmptyState(
            title = title,
            icon = { RaftSlotIcon(RaftLucideIconName.Artifact, size = 36.dp) },
            actions = action,
        )
    }
}

@Composable
private fun ArtifactsHomeMore(state: ArtifactsHomeState, onIntent: (ArtifactsHomeIntent) -> Unit) {
    if (state.nextCursor == null) return
    Box(Modifier.fillMaxWidth().padding(horizontal = ArtifactsHomeMargin, vertical = 4.dp)) {
        RaftButton(
            text = stringResource(Res.string.artifacts_dashboard_show_more),
            onClick = { onIntent(ArtifactsHomeIntent.LoadMore) },
            modifier = Modifier.testTag(ArtifactsHomeTags.More),
            variant = RaftButtonVariant.Ghost,
            loading = state.paging,
            // Off while the list reads itself again: this cursor continues rows being replaced.
            enabled = !state.refreshing,
        )
    }
}

/** Where it lives, in words (web `artifactHomeWords`). */
@Composable
internal fun artifactsHomePlaceWords(row: ArtifactsHomeRow, serverName: String?): String =
    when (val home = row.summary.home) {
        is ArtifactHome.Conversation ->
            when {
                home.dm -> stringResource(Res.string.artifacts_home_dm, home.peerDisplayName ?: home.name)
                else -> "#${home.name}"
            }
        ArtifactHome.Server -> artifactsHomeScopeLabel(ArtifactsHomeScope.Server, serverName)
        ArtifactHome.Public ->
            if (row.fromAnotherServer) stringResource(Res.string.artifacts_dashboard_public_from_another_server) else stringResource(Res.string.artifacts_scope_public)
    }

/** web `artifactShownTitle`: the title without an old `.md` or `.html`. */
internal fun artifactsHomeShownTitle(title: String): String =
    title.replace(Regex("\\.(md|html)$", RegexOption.IGNORE_CASE), "").ifBlank { title }

/** web `artifactAuthorText`: "You"; a personal assistant's "for you" / "for Remy". */
@Composable
internal fun artifactsHomeAuthorText(actor: ArtifactsHomeActor, readerId: String?): Pair<String, String?> {
    if (actor.type == "user" && readerId != null && actor.id == readerId && actor.gone == null) {
        return stringResource(Res.string.artifacts_dashboard_author_you) to null
    }
    val name = artifactsHomeActorName(actor, stringResource(Res.string.artifacts_dashboard_author_unknown))
    val behalf = actor.onBehalfOf?.takeIf { actor.type == "agent" && actor.gone == null } ?: return name to null
    return name to
        if (behalf.id == readerId) {
            stringResource(Res.string.artifacts_dashboard_author_for_you)
        } else {
            stringResource(Res.string.artifacts_dashboard_author_for_person, behalf.displayName)
        }
}

/** web `NewSticker`: one pink sticker, "Mentions you · Updated · 2 new comments". */
@Composable
private fun ArtifactsHomeSticker(news: ArtifactsHomeNews) {
    val text = artifactsHomeNewsText(news) ?: return
    RaftBadge(text = text, variant = RaftBadgeVariant.Accent)
}

/** The sticker's words, or null when nothing is new. Counts go through the plural path (ru few/many). */
@Composable
private fun artifactsHomeNewsText(news: ArtifactsHomeNews): String? {
    if (!news.any) return null
    val parts =
        listOfNotNull(
            stringResource(Res.string.artifacts_dashboard_mentioned).takeIf { news.mentioned },
            stringResource(Res.string.artifacts_dashboard_new_version).takeIf { news.newerVersion },
            if (news.newComments > 0) {
                stringResource(
                    if (news.newComments == 1) Res.string.artifacts_dashboard_new_comments_one else Res.string.artifacts_dashboard_new_comments_many,
                    news.newComments,
                )
            } else {
                null
            },
        )
    return parts.joinToString(" · ")
}

/**
 * One name for a row or tile (review M4): title, where it lives, version, comments, who made it,
 * when it was opened or updated, what is new. Its parts are cleared, so it is read once, and the
 * avatar, icons and cover are decoration.
 */
@Composable
private fun artifactsHomeOpenLabel(row: ArtifactsHomeRow, readerId: String?, serverName: String?, byOpened: Boolean): String {
    val (name, behalf) = artifactsHomeAuthorText(row.author, readerId)
    val relative = raftRelativeTimestampLabel(row.openedAt ?: row.summary.updatedAt)
    val time =
        when {
            relative.isBlank() -> null
            byOpened && row.openedAt != null -> stringResource(Res.string.artifacts_dashboard_tile_opened, relative)
            else -> stringResource(Res.string.artifacts_dashboard_tile_updated, relative)
        }
    return (
        listOf(artifactsHomeShownTitle(row.summary.title), artifactsHomePlaceWords(row, serverName)) +
            artifactsHomeFacts(row) +
            listOfNotNull(if (behalf != null) "$name $behalf" else name, time, artifactsHomeNewsText(row.news))
        ).joinToString(", ")
}

private fun Modifier.artifactsHomeOpenSemantics(label: String, onOpen: () -> Unit): Modifier =
    clearAndSetSemantics {
        contentDescription = label
        role = Role.Button
        onClick {
            onOpen()
            true
        }
    }

@Composable
private fun ArtifactsHomeAuthorLine(actor: ArtifactsHomeActor, readerId: String?, modifier: Modifier = Modifier) {
    val (name, behalf) = artifactsHomeAuthorText(actor, readerId)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        RaftAppAvatar(
            avatarWireValue = actor.avatarUrl.orEmpty(),
            label = name,
            avatarSize = 16.dp,
        )
        RaftText(
            if (behalf != null) "$name $behalf" else name,
            style = RaftTheme.typography.footnote,
            color = RaftTheme.colors.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** web `ArtifactCardFacts` for the row: "v2 · 4 comments" (comments only when there are any). */
@Composable
private fun artifactsHomeFacts(row: ArtifactsHomeRow): List<String> =
    listOfNotNull(
        stringResource(Res.string.artifacts_card_version, row.summary.version),
        row.summary.commentCount.takeIf { it > 0 }?.let {
            if (it == 1) stringResource(Res.string.artifacts_card_comments_one) else stringResource(Res.string.artifacts_card_comments_many, it)
        },
    )

/**
 * web `AllRow` (phone list): the kind's tile, the title on one line, "#place · v2 · 4 comments", the
 * short time it was opened (in that order) or updated, then who made it and the sticker.
 */
@Composable
private fun ArtifactsHomeListRow(row: ArtifactsHomeRow, readerId: String?, serverName: String?, byOpened: Boolean, onOpen: () -> Unit) {
    val colors = RaftTheme.colors
    val label = artifactsHomeOpenLabel(row, readerId, serverName, byOpened)
    val time = raftRelativeTimestampLabel(row.openedAt ?: row.summary.updatedAt)
    val subtitle = (listOf(artifactsHomePlaceWords(row, serverName)) + artifactsHomeFacts(row)).joinToString(" · ")
    Row(
        Modifier
            .fillMaxWidth()
            .testTag(ArtifactsHomeTags.Row)
            .raftClickable(onClick = onOpen)
            .artifactsHomeOpenSemantics(label, onOpen)
            .padding(horizontal = ArtifactsHomeMargin, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RaftIconTile(size = RaftIconTileSize.Md) {
            RaftLucideIcon(row.summary.kind.lucideIcon(), Modifier.size(RaftIconTileSize.Md.icon), color = raftArgbHex(colors.foregroundMuted))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RaftText(
                    artifactsHomeShownTitle(row.summary.title),
                    modifier = Modifier.weight(1f),
                    style = RaftTheme.typography.callout.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (time.isNotBlank()) {
                    RaftText(time, Modifier.padding(start = 8.dp), style = RaftTheme.typography.footnote, color = colors.foregroundHint, maxLines = 1)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (row.summary.home == ArtifactHome.Public) {
                    RaftLucideIcon(RaftLucideIconName.Globe, Modifier.size(12.dp).padding(end = 0.dp), color = raftArgbHex(colors.foregroundMuted))
                    Spacer(Modifier.width(4.dp))
                }
                RaftText(subtitle, style = RaftTheme.typography.footnote, color = colors.foregroundMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ArtifactsHomeAuthorLine(row.author, readerId, Modifier.weight(1f, fill = false))
                ArtifactsHomeSticker(row.news)
            }
        }
    }
}

/**
 * web `ArtifactTile` (grid, the default view): the message card's frame, the cover, the title (2
 * lines) and version, the sticker; who made it, "💬 N" when there are comments; then when it was
 * opened (in that order) or updated, short on a phone.
 */
@Composable
private fun ArtifactsHomeTile(
    row: ArtifactsHomeRow,
    readerId: String?,
    serverName: String?,
    byOpened: Boolean,
    wide: Boolean,
    onOpen: () -> Unit,
    cover: @Composable (ArtifactsHomeRow) -> Unit,
) {
    val colors = RaftTheme.colors
    val label = artifactsHomeOpenLabel(row, readerId, serverName, byOpened)
    val shape = RoundedCornerShape(RaftRadius.Container)
    val relative = raftRelativeTimestampLabel(row.openedAt ?: row.summary.updatedAt)
    val time =
        when {
            relative.isBlank() || !wide -> relative
            byOpened && row.openedAt != null -> stringResource(Res.string.artifacts_dashboard_tile_opened, relative)
            else -> stringResource(Res.string.artifacts_dashboard_tile_updated, relative)
        }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.layerCard, shape)
            .border(RaftStroke.Hairline, colors.lineSeam, shape)
            .testTag(ArtifactsHomeTags.Tile)
            .raftClickable(onClick = onOpen)
            .artifactsHomeOpenSemantics(label, onOpen),
    ) {
        cover(row)
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RaftText(
                    artifactsHomeShownTitle(row.summary.title),
                    modifier = Modifier.weight(1f),
                    style = RaftTheme.typography.subhead.copy(fontWeight = FontWeight.Bold),
                    color = colors.foreground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                RaftBadge(text = stringResource(Res.string.artifacts_card_version, row.summary.version), variant = RaftBadgeVariant.Muted)
            }
            ArtifactsHomeSticker(row.news)
            ArtifactsHomeAuthorLine(row.author, readerId)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RaftText(time, Modifier.weight(1f), style = RaftTheme.typography.footnote, color = colors.foregroundHint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (row.summary.commentCount > 0) {
                    RaftLucideIcon(RaftLucideIconName.MessageSquare, Modifier.size(12.dp), color = raftArgbHex(colors.foregroundMuted))
                    RaftText("${row.summary.commentCount}", style = RaftTheme.typography.footnote, color = colors.foregroundMuted, maxLines = 1)
                }
            }
        }
    }
}

/** web `TileCover`'s last resort: the kind's glyph on `fill-muted`, 16:10. */
@Composable
internal fun ArtifactsHomeTilePlaceholder(kind: ArtifactKind) {
    val colors = RaftTheme.colors
    ArtifactsHomeTileCoverFrame {
        RaftLucideIcon(kind.lucideIcon(), Modifier.size(28.dp), color = raftArgbHex(colors.foregroundMuted))
    }
}

/**
 * web `TileCover`'s box: every cover is the tile's width at 16:10 (`aspect-[16/10]`, review L3), so
 * every tile in a row is as tall; what doesn't fit is cut, on `fill-muted`, with a hairline under it.
 */
@Composable
internal fun ArtifactsHomeTileCoverFrame(alignment: Alignment = Alignment.Center, content: @Composable () -> Unit) {
    val colors = RaftTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 10f).clipToBounds().background(colors.fillMuted),
            contentAlignment = alignment,
        ) { content() }
        Box(Modifier.fillMaxWidth().height(RaftStroke.Hairline).background(colors.lineHairline))
    }
}

/** raft-ui `--column-margin` on a phone list. */
private val ArtifactsHomeMargin: Dp = 16.dp
