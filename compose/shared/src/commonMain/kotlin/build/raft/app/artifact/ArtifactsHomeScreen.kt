package build.raft.app

import androidx.compose.foundation.background
import build.raft.designsystem.components.raftSafeAreaHorizontalPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
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
import build.raft.app.data.ArtifactsHomeIntent
import build.raft.app.data.ArtifactsHomeNews
import build.raft.app.data.ArtifactsHomePlace
import build.raft.app.data.ArtifactsHomeRow
import build.raft.app.data.ArtifactsHomeScope
import build.raft.app.data.ArtifactsHomeSort
import build.raft.app.data.ArtifactsHomeState
import build.raft.app.data.ArtifactsHomeStatus
import build.raft.app.data.ArtifactsHomeStatusFilter
import build.raft.app.data.ArtifactsHomeView
import build.raft.app.data.ARTIFACTS_HOME_SEARCH_MAX_CHARS
import build.raft.app.data.artifactCanonicalId
import build.raft.app.data.artifactDateTimestamp
import build.raft.app.data.buildArtifactsHomeGroups
import build.raft.app.data.isArtifactDimmed
import build.raft.app.markdown.lucideIcon
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftComboboxOption
import build.raft.designsystem.components.RaftComboboxPicker
import build.raft.designsystem.components.RaftEmptyState
import build.raft.designsystem.components.RaftFitToolbar
import build.raft.designsystem.components.RaftFitToolbarAction
import build.raft.designsystem.components.RaftFitToolbarChoice
import build.raft.designsystem.components.RaftFitToolbarOption
import build.raft.designsystem.components.RaftFitToolbarSearch
import build.raft.designsystem.components.RaftFitToolbarView
import build.raft.designsystem.components.RaftFitToolbarViewOption
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftScale
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftStroke

/**
 * The Artifacts home page (web `ArtifactsDashboard.tsx` + `ArtifactDashboardList.tsx` +
 * `ArtifactDashboardToolbar.tsx`; botiverse/slock staging `87ab82603` — `#15654`, `#16137`,
 * `#16153`, `#16182`, `#16290`):
 * - Two-row header (`PanelHeader` with title & count line) over `RaftFitToolbar` (search,
 *   Conversation, Author, Status when settled is enabled, Relevant to me, Clear all, Sort, and
 *   Grid/List view switch — one row on wide, 2-row wrapping filter strip on phone).
 * - Inline quiet settled strip, collapsible "For you" section, adaptive date groups (week with
 *   >= 8 adaptive day split in grid; calendar day in list), flat mode while searching.
 * - Compact 16:10 grid tiles (`minmax(196dp, 1fr)` on wide, 2 columns on phone) and 2-column wide /
 *   1-column phone thumbnail list rows built on [RaftListRow].
 * - Pure: state in, intents out ([ArtifactsHomeRoute] wires the store, flags, persistence and viewer).
 */
@Composable
internal fun ArtifactsHomeScreen(
    state: ArtifactsHomeState,
    view: ArtifactsHomeView,
    readerId: String?,
    serverName: String?,
    /** Wide/tablet/desktop layout: 196dp adaptive tile grid and 2-column thumbnail list. */
    wide: Boolean,
    onIntent: (ArtifactsHomeIntent) -> Unit,
    onView: (ArtifactsHomeView) -> Unit,
    onOpen: (ArtifactsHomeRow) -> Unit,
    onBack: () -> Unit,
    onCopyLink: ((ArtifactsHomeRow) -> Unit)? = null,
    /** The tile's cover: screenshot, excerpt, or placeholder (dimmed flag for grayscale). */
    cover: @Composable (ArtifactsHomeRow, Boolean) -> Unit = { row, _ -> ArtifactsHomeTilePlaceholder(row.summary.kind) },
    /** The list row's 76dp 16:10 thumbnail: screenshot, excerpt, or placeholder. */
    thumbnail: @Composable (ArtifactsHomeRow, Boolean) -> Unit = { row, _ -> ArtifactsHomeRowThumbnailPlaceholder(row.summary.kind) },
    /** Artifact currently open in the wide split viewer column; null when closed or on narrow viewports. */
    currentArtifactId: String? = null,
) {
    RaftDesignSystemTheme {
        SettingsDetailShell(
            title = stringResource(Res.string.artifacts_dashboard_title),
            subtitle = "",
            onBack = onBack,
            titleContent = { ArtifactsHomeTitle(state) },
            trailingContent = null,
        ) {
            Column(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas).testTag(ArtifactsHomeTags.Page)) {
                if (state.status == ArtifactsHomeStatus.Off) {
                    ArtifactsHomeEmpty(stringResource(Res.string.artifacts_dashboard_off_title), testTag = ArtifactsHomeTags.Off)
                    return@Column
                }
                ArtifactsHomeToolbar(state, view, serverName, readerId, wide, onIntent, onView)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    ArtifactsHomeList(
                        state = state,
                        view = view,
                        readerId = readerId,
                        serverName = serverName,
                        wide = wide,
                        currentArtifactId = currentArtifactId,
                        onIntent = onIntent,
                        onOpen = onOpen,
                        onCopyLink = onCopyLink,
                        cover = cover,
                        thumbnail = thumbnail,
                    )
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
    const val Toolbar = "artifacts-all-toolbar"
    const val FilterStrip = "artifacts-all-filter-strip"
    const val Search = "artifacts-all-search"
    const val Sort = "artifacts-all-sort"
    const val Conversation = "artifacts-all-conversation-filter"
    const val Author = "artifacts-all-author-filter"
    const val Status = "artifacts-all-status"
    const val Relevant = "artifacts-all-relevant"
    const val Clear = "artifacts-all-toolbar-clear"
    const val Skeleton = "artifacts-all-skeleton"
    const val Failed = "artifacts-all-failed"
    const val Empty = "artifacts-all-empty"
    const val EmptyAction = "artifacts-all-empty-action"
    const val Row = "artifacts-all-row"
    const val Tile = "artifacts-dashboard-row"
    const val TileTitle = "artifacts-tile-title"
    const val TileMeta = "artifacts-tile-meta"
    const val ListMeta = "artifacts-list-meta"
    const val ListTime = "artifacts-list-time"
    const val ListBadges = "artifacts-list-badges"
    const val More = "artifacts-all-more"
    const val View = "artifacts-dashboard-view"
    const val ViewGrid = "artifacts-dashboard-view-grid"
    const val ViewList = "artifacts-dashboard-view-list"
    const val Groups = "artifacts-groups"
    const val ForYouHead = "artifacts-group-foryou"
    const val ForYouCount = "artifacts-group-foryou-count"
    const val DateGroupHeading = "artifacts-date-group-heading"
    const val SettledStrip = "artifacts-settled-strip"
    const val SettledStripAction = "artifacts-settled-strip-action"
    const val PublicBadge = "artifacts-public-badge"
    const val UnfollowedTag = "artifact-unfollowed-tag"
    const val MentionedPill = "artifact-card-mentioned"
    const val NewVersionPill = "artifact-card-new-version"
    const val CommentBadge = "artifacts-comment-count"
    const val HomeMenu = "artifacts-home-menu"
    const val HomeMenuOpen = "artifacts-home-menu-open"
    const val HomeMenuCopyLink = "artifacts-home-menu-copy-link"
    const val HomeMenuFollow = "artifacts-home-menu-follow"
    const val HomeMenuSettle = "artifacts-home-menu-settle"
}

internal fun artifactsHomeTitleIsInline(scale: RaftScale): Boolean = scale == RaftScale.Wide

@Composable
private fun RowScope.ArtifactsHomeTitle(state: ArtifactsHomeState) {
    val countText = state.counts?.let { counts ->
        artifactsHomeCountLine(counts.total, counts.filtered, state.countsNarrowed)
    }
    if (artifactsHomeTitleIsInline(RaftTheme.scale)) {
        // raft-design desktop-density rule 2: wide heads place the count inline on the title's baseline.
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(RaftPanelHeadInlineMetaGap),
        ) {
            RaftText(
                stringResource(Res.string.artifacts_dashboard_title),
                modifier = Modifier.alignByBaseline(),
                style = RaftTheme.typography.headline,
                color = RaftTheme.colors.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (countText != null) {
                RaftText(
                    countText,
                    modifier = Modifier.weight(1f, fill = false).alignByBaseline().testTag(ArtifactsHomeTags.Count),
                    style = RaftTheme.typography.footnote,
                    color = RaftTheme.colors.foregroundMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    } else {
        Column(Modifier.weight(1f).padding(vertical = 2.dp)) {
            RaftText(
                stringResource(Res.string.artifacts_dashboard_title),
                style = RaftTheme.typography.headline,
                color = RaftTheme.colors.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (countText != null) {
                RaftText(
                    countText,
                    modifier = Modifier.testTag(ArtifactsHomeTags.Count),
                    style = RaftTheme.typography.footnote,
                    color = RaftTheme.colors.foregroundMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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

@Composable
private fun ArtifactsHomeToolbarIcon(name: RaftLucideIconName) {
    RaftLucideIcon(name, Modifier.fillMaxSize(), color = raftArgbHex(LocalRaftContentColor.current))
}

/**
 * The Artifacts home toolbar on [RaftFitToolbar] (web `DashboardToolbar`, slock #16137/#16182/#16290):
 * - Wide: `[Search] [Conversation] [Author] [Status?] [Relevant to me] [Clear all?] [Sort?] [Grid | List]`
 * - Phone: line 1 `[Search] [Sort?] [Grid | List]`, line 2 scrolling `[Conversation] [Author] [Status?] [Relevant to me] [×?]`
 */
@Composable
private fun ArtifactsHomeToolbar(
    state: ArtifactsHomeState,
    view: ArtifactsHomeView,
    serverName: String?,
    readerId: String?,
    wide: Boolean,
    onIntent: (ArtifactsHomeIntent) -> Unit,
    onView: (ArtifactsHomeView) -> Unit,
) {
    var searchOpen by remember { mutableStateOf(false) }
    var searchOverScope by remember { mutableStateOf(false) }
    RaftBackHandler(enabled = searchOverScope, label = "artifacts_home_toolbar_search") {
        if (state.search.isNotEmpty()) onIntent(ArtifactsHomeIntent.Search("")) else searchOpen = false
    }

    val conversationChoice = artifactsHomeConversationChoice(state, serverName, onIntent)
    val authorChoice = artifactsHomeAuthorChoice(state, readerId, onIntent)
    val statusChoice = if (state.settledEnabled) artifactsHomeStatusChoice(state.statusFilter, onIntent) else null
    val relevantChoice = artifactsHomeRelevantChoice(state, onIntent)
    val filters = listOfNotNull(conversationChoice, authorChoice, statusChoice, relevantChoice)
    val display = if (state.query.relevant) emptyList() else listOf(artifactsHomeSortChoice(state.query.sort, onIntent))
    val viewSwitch = artifactsHomeViewSwitch(view, onView)
    val clearLabel = stringResource(Res.string.artifacts_dashboard_clear_filters)

    Box(Modifier.fillMaxWidth().background(RaftTheme.colors.layerCanvas).testTag(ArtifactsHomeTags.Toolbar)) {
        RaftFitToolbar(
            search =
                RaftFitToolbarSearch(
                    value = state.search,
                    onValueChange = { onIntent(ArtifactsHomeIntent.Search(it.take(ARTIFACTS_HOME_SEARCH_MAX_CHARS))) },
                    label = stringResource(Res.string.artifacts_dashboard_search_label),
                    placeholder = stringResource(if (wide) Res.string.artifacts_dashboard_search_placeholder else Res.string.home_nav_search),
                    clearLabel = stringResource(Res.string.artifacts_dashboard_clear_search),
                    testTag = ArtifactsHomeTags.Search,
                    open = searchOpen,
                    onOpenChange = { searchOpen = it },
                    onShownOverScopeChange = { searchOverScope = it },
                ),
            filters = filters,
            filtersEnd =
                if (state.hasToolbarFilter) {
                    RaftFitToolbarAction(
                        text = clearLabel,
                        icon = { ArtifactsHomeToolbarIcon(RaftLucideIconName.X) },
                        onClick = { onIntent(ArtifactsHomeIntent.ClearFilters) },
                        testTag = ArtifactsHomeTags.Clear,
                    )
                } else {
                    null
                },
            display = display,
            view = viewSwitch,
            moreLabel = stringResource(Res.string.list_toolbar_more),
            wrap = true,
            horizontalPadding = ArtifactsHomeMargin,
            filtersTestTag = ArtifactsHomeTags.FilterStrip,
        )
    }
}

@Composable
private fun artifactsHomeConversationChoice(
    state: ArtifactsHomeState,
    serverName: String?,
    onIntent: (ArtifactsHomeIntent) -> Unit,
): RaftFitToolbarChoice {
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
    val label = stringResource(Res.string.artifacts_dashboard_conversation_label)
    val description = valueLabel?.let { stringResource(Res.string.artifacts_dashboard_conversation_applied, it) } ?: label
    return RaftFitToolbarChoice(
        text = valueLabel ?: label,
        icon = { ArtifactsHomeToolbarIcon(artifactsHomePlaceIcon(place)) },
        options = emptyList(),
        selectedKey = null,
        onSelect = {},
        contentDescription = description,
        applied = place != null,
        testTag = ArtifactsHomeTags.Conversation,
        key = ArtifactsHomeTags.Conversation,
        onOpen = { onIntent(ArtifactsHomeIntent.OpenChoices(ArtifactsHomeChoiceKind.Conversation)) },
        popup = {
            if (state.conversationChoices.open) {
                ArtifactsHomeConversationChoices(state, homeLabels, whoTitle, groupTitle, onIntent)
            }
        },
    )
}

@Composable
private fun artifactsHomeAuthorChoice(
    state: ArtifactsHomeState,
    readerId: String?,
    onIntent: (ArtifactsHomeIntent) -> Unit,
): RaftFitToolbarChoice {
    val unknown = stringResource(Res.string.artifacts_dashboard_author_unknown)
    val chosen = state.resolvedAuthorActor
    val hasAuthor = state.query.author != null
    val authorLabel =
        when {
            chosen != null -> artifactsHomeActorName(chosen, unknown)
            hasAuthor -> unknown
            else -> null
        }
    val filterLabel = stringResource(Res.string.artifacts_dashboard_author_label)
    val description = authorLabel?.let { stringResource(Res.string.artifacts_dashboard_author_applied, it) } ?: filterLabel
    return RaftFitToolbarChoice(
        text = authorLabel ?: filterLabel,
        icon = {
            if (chosen != null) {
                RaftAppAvatar(
                    kind =
                        when {
                            chosen.type == "agent" -> RaftAvatarKind.Agent
                            chosen.avatarUrl.isNullOrBlank() -> RaftAvatarKind.Labeled
                            else -> RaftAvatarKind.Human
                        },
                    avatarWireValue = chosen.avatarUrl,
                    label = artifactsHomeActorName(chosen, unknown),
                    avatarSize = 16.dp,
                )
            } else {
                ArtifactsHomeToolbarIcon(RaftLucideIconName.CircleUser)
            }
        },
        options = emptyList(),
        selectedKey = null,
        onSelect = {},
        contentDescription = description,
        applied = hasAuthor,
        testTag = ArtifactsHomeTags.Author,
        key = ArtifactsHomeTags.Author,
        onOpen = { onIntent(ArtifactsHomeIntent.OpenChoices(ArtifactsHomeChoiceKind.Author)) },
        popup = {
            if (state.authorChoices.open) {
                ArtifactsHomeAuthorChoices(state, readerId, unknown, onIntent)
            }
        },
    )
}

/** web `StatusPicker` (`ArtifactDashboardToolbar.tsx:117-154`): Unsettled (default) / All / Settled. */
@Composable
private fun artifactsHomeStatusChoice(
    status: ArtifactsHomeStatusFilter,
    onIntent: (ArtifactsHomeIntent) -> Unit,
): RaftFitToolbarChoice {
    val valueLabel = artifactsHomeStatusLabel(status)
    val fullLabel = stringResource(Res.string.artifacts_dashboard_status_label, valueLabel)
    val options =
        ArtifactsHomeStatusFilter.entries.map { option ->
            RaftFitToolbarOption(key = option.wire, label = artifactsHomeStatusLabel(option))
        }
    return RaftFitToolbarChoice(
        text = fullLabel,
        icon = { ArtifactsHomeToolbarIcon(RaftLucideIconName.CircleCheck) },
        options = options,
        selectedKey = status.wire,
        onSelect = { wire ->
            ArtifactsHomeStatusFilter.entries.firstOrNull { it.wire == wire }?.let { onIntent(ArtifactsHomeIntent.SetStatus(it)) }
        },
        contentDescription = fullLabel,
        // web: `data-has-value={status === "all" ? undefined : ""}` (Unsettled & Settled actively filter).
        applied = status != ArtifactsHomeStatusFilter.All,
        testTag = ArtifactsHomeTags.Status,
        key = ArtifactsHomeTags.Status,
    )
}

@Composable
private fun artifactsHomeStatusLabel(status: ArtifactsHomeStatusFilter): String =
    stringResource(
        when (status) {
            ArtifactsHomeStatusFilter.Unsettled -> Res.string.artifacts_dashboard_status_unsettled
            ArtifactsHomeStatusFilter.All -> Res.string.artifacts_dashboard_status_all
            ArtifactsHomeStatusFilter.Settled -> Res.string.artifacts_dashboard_status_settled
        },
    )

@Composable
private fun artifactsHomeRelevantChoice(
    state: ArtifactsHomeState,
    onIntent: (ArtifactsHomeIntent) -> Unit,
): RaftFitToolbarChoice {
    val count = state.counts?.relevant?.takeIf { it > 0 }
    val label = stringResource(Res.string.artifacts_dashboard_relevant)
    val text = if (count != null) "$label $count" else label
    return RaftFitToolbarChoice(
        text = text,
        icon = { ArtifactsHomeToolbarIcon(RaftLucideIconName.Inbox) },
        options = emptyList(),
        selectedKey = null,
        onSelect = {},
        contentDescription = text,
        applied = state.query.relevant,
        testTag = ArtifactsHomeTags.Relevant,
        key = ArtifactsHomeTags.Relevant,
        onOpen = { onIntent(ArtifactsHomeIntent.SetRelevant(!state.query.relevant)) },
    )
}

@Composable
private fun artifactsHomeSortChoice(
    sort: ArtifactsHomeSort,
    onIntent: (ArtifactsHomeIntent) -> Unit,
): RaftFitToolbarChoice {
    val sortLabel = artifactsHomeSortLabel(sort)
    val sortName = stringResource(Res.string.artifacts_dashboard_sort_label, sortLabel)
    val options =
        ArtifactsHomeSort.entries.map { option ->
            RaftFitToolbarOption(key = option.wire, label = artifactsHomeSortLabel(option))
        }
    return RaftFitToolbarChoice(
        text = sortLabel,
        icon = { ArtifactsHomeToolbarIcon(RaftLucideIconName.ArrowDownUp) },
        options = options,
        selectedKey = sort.wire,
        onSelect = { wire ->
            ArtifactsHomeSort.entries.firstOrNull { it.wire == wire }?.let { onIntent(ArtifactsHomeIntent.SetSort(it)) }
        },
        heading = stringResource(Res.string.list_toolbar_sort),
        contentDescription = sortName,
        applied = sort != ArtifactsHomeSort.Default,
        testTag = ArtifactsHomeTags.Sort,
        key = ArtifactsHomeTags.Sort,
    )
}

@Composable
private fun artifactsHomeViewSwitch(
    view: ArtifactsHomeView,
    onView: (ArtifactsHomeView) -> Unit,
): RaftFitToolbarView {
    val grid = stringResource(Res.string.artifacts_dashboard_view_grid)
    val list = stringResource(Res.string.artifacts_dashboard_view_list)
    return RaftFitToolbarView(
        options =
            listOf(
                RaftFitToolbarViewOption(
                    key = ArtifactsHomeView.Grid.wire,
                    label = grid,
                    icon = { ArtifactsHomeToolbarIcon(RaftLucideIconName.LayoutGrid) },
                ),
                RaftFitToolbarViewOption(
                    key = ArtifactsHomeView.List.wire,
                    label = list,
                    icon = { ArtifactsHomeToolbarIcon(RaftLucideIconName.LayoutList) },
                ),
            ),
        selectedKey = view.wire,
        onSelect = { wire ->
            ArtifactsHomeView.entries.firstOrNull { it.wire == wire }?.let(onView)
        },
        label = stringResource(Res.string.artifacts_dashboard_view_label),
        heading = stringResource(Res.string.list_toolbar_view),
        testTag = ArtifactsHomeTags.View,
    )
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

@Composable
private fun ArtifactsHomeConversationChoices(
    state: ArtifactsHomeState,
    homeLabels: Map<ArtifactsHomeScope, String>,
    whoTitle: String,
    groupTitle: String,
    onIntent: (ArtifactsHomeIntent) -> Unit,
) {
    val choices = state.conversationChoices
    val place = state.query.place
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
    RaftComboboxPicker(
        expanded = true,
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
        width = ArtifactsHomeFilterPopupWidth,
    )
}

/** web ArtifactFilterCombobox `w-[320px]`: the filters' popup on a wide window. */
private val ArtifactsHomeFilterPopupWidth = 320.dp

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

@Composable
private fun ArtifactsHomeAuthorChoices(
    state: ArtifactsHomeState,
    readerId: String?,
    unknown: String,
    onIntent: (ArtifactsHomeIntent) -> Unit,
) {
    val choices = state.authorChoices
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
    RaftComboboxPicker(
        expanded = true,
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
        width = ArtifactsHomeFilterPopupWidth,
    )
}

/** web `artifactActorName`: the display name, else the handle; a gone actor as "Someone". */
internal fun artifactsHomeActorName(actor: ArtifactsHomeActor, unknown: String): String =
    actor.displayName.takeIf { it.isNotBlank() } ?: actor.name.takeIf { it.isNotBlank() } ?: unknown

@Composable
private fun ArtifactsHomeList(
    state: ArtifactsHomeState,
    view: ArtifactsHomeView,
    readerId: String?,
    serverName: String?,
    wide: Boolean,
    currentArtifactId: String?,
    onIntent: (ArtifactsHomeIntent) -> Unit,
    onOpen: (ArtifactsHomeRow) -> Unit,
    onCopyLink: ((ArtifactsHomeRow) -> Unit)?,
    cover: @Composable (ArtifactsHomeRow, Boolean) -> Unit,
    thumbnail: @Composable (ArtifactsHomeRow, Boolean) -> Unit,
) {
    val rows = state.rows
    val timeContext = currentRaftEffectiveDateTimeContext()
    var forYouOpen by remember { mutableStateOf(true) }
    val failed: @Composable () -> Unit = {
        if (state.status == ArtifactsHomeStatus.Failed) {
            Column(Modifier.fillMaxWidth().raftSafeAreaHorizontalPadding().padding(horizontal = ArtifactsHomeMargin, vertical = 8.dp).testTag(ArtifactsHomeTags.Failed)) {
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
        rows == null -> ArtifactsHomeSkeleton(view, wide, grouped = state.search.trim().isEmpty(), horizontalPadding = ArtifactsHomeMargin)
        else -> {
            val searching = state.query.q.trim().isNotEmpty()
            val grouped =
                remember(rows, view, state.statusFilter, searching, state.rowsByOpened, state.settledEnabled, timeContext) {
                    buildArtifactsHomeGroups(
                        rows = rows,
                        view = view,
                        status = state.statusFilter,
                        searching = searching,
                        byOpened = state.rowsByOpened,
                        settledEnabled = state.settledEnabled,
                        timeContext = timeContext,
                    )
                }
            val showSettledStrip = grouped.settledEnabled && (grouped.visible.isNotEmpty() || state.nextCursor != null)
            if (rows.isEmpty()) {
                Column(Modifier.fillMaxSize()) {
                    failed()
                    if (showSettledStrip) {
                        ArtifactSettledStrip(
                            status = grouped.status,
                            hiddenCount = grouped.hiddenSettledCount,
                            dimmedCount = grouped.dimmedSettledCount,
                            horizontalPadding = ArtifactsHomeMargin,
                            onStatusChange = { onIntent(ArtifactsHomeIntent.SetStatus(it)) },
                        )
                    }
                    ArtifactsHomeEmptyByCause(state, onIntent)
                }
                return
            }
            if (grouped.visible.isEmpty() && state.nextCursor == null) {
                Column(Modifier.fillMaxSize()) {
                    failed()
                    ArtifactsHomeSettledEmpty(grouped.status, grouped.hiddenSettledCount, state, onIntent)
                }
                return
            }

            // Responsive to the list container's own width (web `@container` breakpoints, slock #16418):
            // when the split viewer column opens beside the list, the list naturally steps down columns.
            val shownUnfollowedIds = if (state.query.relevant) state.unfollowedIds else emptySet()
            val currentCanonicalId = currentArtifactId?.let(::artifactCanonicalId)
            val isGrid = view == ArtifactsHomeView.Grid
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .then(if (isGrid || wide) Modifier.raftSafeAreaHorizontalPadding() else Modifier),
            ) {
                val containerWidthDp = maxWidth.value
                val wideGrid = containerWidthDp >= ArtifactsHomeWideGridMinWidthDp
                val twoColList = containerWidthDp >= ArtifactsHomeTwoColListMinWidthDp
                val trailingListBadges = containerWidthDp >= ArtifactsHomeTrailingBadgesMinWidthDp
                val horizontalGap =
                    when {
                        isGrid && wideGrid -> 12.dp
                        isGrid -> 8.dp
                        twoColList -> 8.dp
                        else -> 0.dp
                    }
                val verticalGap =
                    when {
                        isGrid && wideGrid -> 14.dp
                        isGrid -> 8.dp
                        else -> 2.dp
                    }
                val gridColumnCount =
                    if (isGrid) {
                        artifactsHomeGridColumnCount(containerWidthDp, horizontalGapDp = horizontalGap.value)
                    } else {
                        1
                    }
                val columns =
                    if (isGrid) {
                        GridCells.Fixed(gridColumnCount)
                    } else {
                        if (twoColList) GridCells.Fixed(2) else GridCells.Fixed(1)
                    }
                val contentPadding =
                    if (isGrid) {
                        PaddingValues(start = ArtifactsHomeMargin, end = ArtifactsHomeMargin, top = 4.dp, bottom = 24.dp + raftBottomSafeAreaPadding())
                    } else {
                        PaddingValues(top = 4.dp, bottom = 24.dp + raftBottomSafeAreaPadding())
                    }
                // Full-span items inside the grid already inherit horizontal padding; in list view they add their own margin.
                val headerPadding = if (isGrid) 0.dp else ArtifactsHomeMargin

                LazyVerticalGrid(
                    columns = columns,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .then(if (!grouped.searching) Modifier.testTag(ArtifactsHomeTags.Groups) else Modifier),
                    contentPadding = contentPadding,
                    horizontalArrangement = Arrangement.spacedBy(horizontalGap),
                    verticalArrangement = Arrangement.spacedBy(verticalGap),
                ) {
                    if (state.status == ArtifactsHomeStatus.Failed) {
                        item(key = "failed", span = { GridItemSpan(maxLineSpan) }) { failed() }
                    }
                    if (showSettledStrip) {
                        item(key = "settled-strip", span = { GridItemSpan(maxLineSpan) }) {
                            ArtifactSettledStrip(
                                status = grouped.status,
                                hiddenCount = grouped.hiddenSettledCount,
                                dimmedCount = grouped.dimmedSettledCount,
                                horizontalPadding = headerPadding,
                                onStatusChange = { onIntent(ArtifactsHomeIntent.SetStatus(it)) },
                            )
                        }
                    }

                    fun addSectionItems(prefix: String, sectionRows: List<ArtifactsHomeRow>) {
                        if (isGrid) {
                            // LazyVerticalGrid never stretches sibling heights across a row; chunk each visual row into
                            // an IntrinsicSize.Max Row so every tile in the row matches the tallest card's height and
                            // pins its author/home footer flush to the bottom (matching web CSS Grid `h-full` + `mt-auto`).
                            val chunkedRows = sectionRows.chunked(gridColumnCount)
                            items(
                                items = chunkedRows,
                                key = { rowItems -> "$prefix:row:${rowItems.joinToString(",") { it.summary.id }}" },
                                span = { GridItemSpan(maxLineSpan) },
                            ) { rowItems ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                                    horizontalArrangement = Arrangement.spacedBy(horizontalGap),
                                ) {
                                    rowItems.forEach { row ->
                                        key(row.summary.id) {
                                            val unfollowed = row.summary.id in shownUnfollowedIds && row.summary.following == false
                                            val dimmed = unfollowed || isArtifactDimmed(row, grouped.status, grouped.settledEnabled)
                                            val current = currentCanonicalId != null && artifactCanonicalId(row.summary.id) == currentCanonicalId
                                            val settling = row.summary.id in state.settlingIds
                                            val followingPending = row.summary.id in state.followingIds
                                            val copyAction = onCopyLink?.let { cb -> { cb(row) } }
                                            val followAction: (Boolean) -> Unit = { next -> onIntent(ArtifactsHomeIntent.SetFollowing(row.summary.id, next)) }
                                            val settleAction: (Boolean) -> Unit = { next -> onIntent(ArtifactsHomeIntent.SetSettled(row.summary.id, next)) }
                                            Box(Modifier.weight(1f).fillMaxHeight()) {
                                                ArtifactsHomeTile(
                                                    row = row,
                                                    readerId = readerId,
                                                    serverName = serverName,
                                                    byOpened = state.rowsByOpened,
                                                    dimmed = dimmed,
                                                    settledEnabled = grouped.settledEnabled,
                                                    settling = settling,
                                                    onOpen = { onOpen(row) },
                                                    onCopyLink = copyAction,
                                                    onSetSettled = settleAction,
                                                    current = current,
                                                    unfollowed = unfollowed,
                                                    followingPending = followingPending,
                                                    onSetFollowing = followAction,
                                                    fillHeight = true,
                                                    modifier = Modifier.fillMaxHeight(),
                                                    cover = cover,
                                                )
                                            }
                                        }
                                    }
                                    repeat(gridColumnCount - rowItems.size) {
                                        Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        } else {
                            items(sectionRows, key = { row -> "$prefix:${row.summary.id}" }) { row ->
                                val unfollowed = row.summary.id in shownUnfollowedIds && row.summary.following == false
                                val dimmed = unfollowed || isArtifactDimmed(row, grouped.status, grouped.settledEnabled)
                                val current = currentCanonicalId != null && artifactCanonicalId(row.summary.id) == currentCanonicalId
                                val settling = row.summary.id in state.settlingIds
                                val followingPending = row.summary.id in state.followingIds
                                val copyAction = onCopyLink?.let { cb -> { cb(row) } }
                                val followAction: (Boolean) -> Unit = { next -> onIntent(ArtifactsHomeIntent.SetFollowing(row.summary.id, next)) }
                                val settleAction: (Boolean) -> Unit = { next -> onIntent(ArtifactsHomeIntent.SetSettled(row.summary.id, next)) }
                                ArtifactsHomeListRow(
                                    row = row,
                                    readerId = readerId,
                                    serverName = serverName,
                                    byOpened = state.rowsByOpened,
                                    wide = trailingListBadges,
                                    dimmed = dimmed,
                                    settledEnabled = grouped.settledEnabled,
                                    settling = settling,
                                    onOpen = { onOpen(row) },
                                    onCopyLink = copyAction,
                                    onSetSettled = settleAction,
                                    current = current,
                                    unfollowed = unfollowed,
                                    followingPending = followingPending,
                                    onSetFollowing = followAction,
                                    thumbnail = thumbnail,
                                )
                            }
                        }
                    }

                    if (grouped.searching) {
                        addSectionItems("search", grouped.visible)
                    } else {
                        if (grouped.forYou.isNotEmpty()) {
                            item(key = "group-head:for-you", span = { GridItemSpan(maxLineSpan) }) {
                                ArtifactForYouHead(
                                    count = grouped.forYou.size,
                                    open = forYouOpen,
                                    onToggle = { forYouOpen = !forYouOpen },
                                    horizontalPadding = headerPadding,
                                )
                            }
                            if (forYouOpen) {
                                addSectionItems("for-you", grouped.forYou)
                            }
                        }
                        grouped.dateGroups.forEach { group ->
                            item(key = "group-head:${group.key}", span = { GridItemSpan(maxLineSpan) }) {
                                ArtifactDateGroupHead(label = group.label, horizontalPadding = headerPadding)
                            }
                            addSectionItems("group:${group.key}", group.rows)
                        }
                    }

                    if (state.nextCursor != null) {
                        item(key = "more", span = { GridItemSpan(maxLineSpan) }) {
                            ArtifactsHomeMore(state, horizontalPadding = headerPadding, onIntent = onIntent)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The grid's column count: two on narrow containers; adaptive `repeat(auto-fill, minmax(196dp, 1fr))`
 * at >= 440dp (`@min-[27.5rem]`, web #16418).
 */
internal fun artifactsHomeGridColumnCount(
    containerWidthDp: Float,
    horizontalPaddingDp: Float = ArtifactsHomeMargin.value * 2f,
    horizontalGapDp: Float = 12f,
): Int {
    if (containerWidthDp < ArtifactsHomeWideGridMinWidthDp) return 2
    val availableWidthDp = (containerWidthDp - horizontalPaddingDp).coerceAtLeast(0f)
    return maxOf(((availableWidthDp + horizontalGapDp) / (ArtifactsHomeTileMinWidth.value + horizontalGapDp)).toInt(), 1)
}

internal val ArtifactsHomeTileMinWidth: Dp = 196.dp
internal const val ArtifactsHomeWideGridMinWidthDp: Float = 440f
internal const val ArtifactsHomeTwoColListMinWidthDp: Float = 672f
internal const val ArtifactsHomeTrailingBadgesMinWidthDp: Float = 560f

/** web `DashboardList` `settledEmpty`: all rows hidden by Unsettled or none matching Settled. */
@Composable
private fun ArtifactsHomeSettledEmpty(
    status: ArtifactsHomeStatusFilter,
    hiddenSettledCount: Int,
    state: ArtifactsHomeState,
    onIntent: (ArtifactsHomeIntent) -> Unit,
) {
    when {
        status == ArtifactsHomeStatusFilter.Unsettled && hiddenSettledCount > 0 ->
            ArtifactsHomeEmpty(stringResource(Res.string.artifacts_dashboard_empty_unsettled_title), testTag = ArtifactsHomeTags.Empty) {
                RaftButton(
                    text = stringResource(Res.string.artifacts_dashboard_show_settled),
                    onClick = { onIntent(ArtifactsHomeIntent.SetStatus(ArtifactsHomeStatusFilter.All)) },
                    modifier = Modifier.testTag(ArtifactsHomeTags.EmptyAction),
                    variant = RaftButtonVariant.Default,
                )
            }
        status == ArtifactsHomeStatusFilter.Settled ->
            ArtifactsHomeEmpty(stringResource(Res.string.artifacts_dashboard_empty_settled_title), testTag = ArtifactsHomeTags.Empty) {
                RaftButton(
                    text = stringResource(Res.string.artifacts_dashboard_see_all),
                    onClick = { onIntent(ArtifactsHomeIntent.SetStatus(ArtifactsHomeStatusFilter.All)) },
                    modifier = Modifier.testTag(ArtifactsHomeTags.EmptyAction),
                    variant = RaftButtonVariant.Default,
                )
            }
        else -> ArtifactsHomeEmptyByCause(state, onIntent)
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
            searched && state.hasNonSearchFilter -> Res.string.artifacts_dashboard_clear_all
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
    Box(Modifier.fillMaxSize().raftSafeAreaHorizontalPadding().testTag(testTag), contentAlignment = Alignment.Center) {
        RaftEmptyState(
            title = title,
            icon = { RaftSlotIcon(RaftLucideIconName.Artifact, size = 36.dp) },
            actions = action,
        )
    }
}

@Composable
private fun ArtifactsHomeMore(state: ArtifactsHomeState, horizontalPadding: Dp, onIntent: (ArtifactsHomeIntent) -> Unit) {
    if (state.nextCursor == null) return
    Box(Modifier.fillMaxWidth().raftSafeAreaHorizontalPadding().padding(horizontal = horizontalPadding, vertical = 4.dp)) {
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

/**
 * A conversation home's label (web `homeConversationLabel`): `#name` for a channel, a shared
 * channel by its bare name, and "Shared channel" for one the reader's server has no copy of; a DM
 * by its peer (the caller says "Direct message with"). Null for a deleted conversation (no name).
 */
@Composable
internal fun artifactHomeConversationLabel(home: ArtifactHome.Conversation): String? =
    when {
        home.sharedChannelWithoutCopy -> stringResource(Res.string.artifacts_scope_shared_channel)
        home.dm -> (home.peerDisplayName ?: home.name).takeIf { it.isNotBlank() }
        home.joint -> home.name
        else -> home.name.takeIf { it.isNotBlank() }?.let { "#$it" }
    }

/** Where it lives, in words (web `artifactHomeWords`). */
@Composable
internal fun artifactsHomePlaceWords(row: ArtifactsHomeRow, serverName: String?): String =
    when (val home = row.summary.home) {
        is ArtifactHome.Conversation ->
            when {
                home.dm -> stringResource(Res.string.artifacts_home_dm, home.peerDisplayName ?: home.name)
                else -> artifactHomeConversationLabel(home) ?: "#"
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

/** The sticker's words, or null when nothing is new. */
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

/** Accessible summary for a tile or list row. */
@Composable
internal fun artifactsHomeOpenLabel(row: ArtifactsHomeRow, readerId: String?, serverName: String?, byOpened: Boolean): String {
    val (name, behalf) = artifactsHomeAuthorText(row.author, readerId)
    val relative = raftRelativeTimestampLabel(artifactDateTimestamp(row, byOpened))
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

@Composable
internal fun ArtifactsHomeAuthorLine(actor: ArtifactsHomeActor, readerId: String?, modifier: Modifier = Modifier) {
    val (name, behalf) = artifactsHomeAuthorText(actor, readerId)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        RaftAppAvatar(
            // web ArtifactActorAvatar: an agent's face (else the robot); a person's picture (else,
            // when it fails, the placeholder); a person without one, their initial.
            kind =
                when {
                    actor.type == "agent" -> RaftAvatarKind.Agent
                    actor.avatarUrl.isNullOrBlank() -> RaftAvatarKind.Labeled
                    else -> RaftAvatarKind.Human
                },
            avatarWireValue = actor.avatarUrl,
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

@Composable
private fun artifactsHomeFacts(row: ArtifactsHomeRow): List<String> =
    listOfNotNull(
        stringResource(Res.string.artifacts_card_version, row.summary.version),
        row.summary.commentCount.takeIf { it > 0 }?.let {
            if (it == 1) stringResource(Res.string.artifacts_card_comments_one) else stringResource(Res.string.artifacts_card_comments_many, it)
        },
    )

/** web `TileCover`'s last resort: the kind's glyph on `fill-muted`, 16:10. */
@Composable
internal fun ArtifactsHomeTilePlaceholder(kind: ArtifactKind) {
    val colors = RaftTheme.colors
    ArtifactsHomeTileCoverFrame {
        RaftLucideIcon(kind.lucideIcon(), Modifier.size(28.dp), color = raftArgbHex(colors.foregroundMuted))
    }
}

/**
 * web `TileCover`'s box: every cover is the tile's width at 16:10 (`aspect-[16/10]`, review L3);
 * what doesn't fit is cut, on `fill-muted`, with a hairline under it.
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

/** raft-ui `--column-margin` aligned across toolbar, headers, grid tiles and list rows. */
internal val ArtifactsHomeMargin: Dp = 16.dp
