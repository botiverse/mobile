package build.raft.app.settings

import build.raft.designsystem.components.raftSafeAreaHorizontalPadding
import build.raft.app.resources.*
import build.raft.app.settings.iconName
import build.raft.app.settings.toHomeNotificationItem
import build.raft.app.settings.toHomeSearchEntityResult
import build.raft.app.sharedHomeSearchErrorAction
import build.raft.app.shown
import build.raft.app.thread.icon
import build.raft.app.thread.label
import build.raft.app.toHomeNotificationItem
import build.raft.app.toHomeSearchEntityResult
import build.raft.app.toVisibleCopy
import build.raft.app.userFacingErrorCodeMessage
import build.raft.app.withComposerDraftProjection
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftSearchResultDescription
import build.raft.designsystem.components.RaftSearchResultIcon
import build.raft.designsystem.components.RaftSearchResultRow
import build.raft.designsystem.components.RaftSearchResultTitle
import build.raft.designsystem.components.RaftBackButton
import build.raft.designsystem.components.RaftBackButtonDefaults
import androidx.compose.ui.input.key.key
import build.raft.app.components.SharedHomeSearchEditorOwner
import build.raft.app.components.SharedHomeSearchInput
import build.raft.app.composeHomeSearchEntityGroups
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedHomeState
import build.raft.app.data.SharedHomeNotification
import build.raft.app.data.SharedHomeNotificationIcon
import build.raft.app.data.SharedHomeNotificationKind
import build.raft.app.data.APP_UPDATE_HOME_NOTIFICATION_ID
import build.raft.app.data.SearchScope
import build.raft.app.data.SearchSort
import build.raft.app.data.SearchTimeRange
import build.raft.app.data.SettingsServerSidebarPinnedItem
import build.raft.app.data.SharedHomeSearchPanelState
import build.raft.app.data.SearchArtifactHit
import build.raft.app.data.SearchArtifactsIntent
import build.raft.app.data.SearchArtifactsState
import build.raft.app.data.SearchCoreGroupsIntent
import build.raft.app.data.SearchCoreGroupsState
import build.raft.app.data.SharedHomeSearchSavedIntent
import build.raft.app.data.hasMessageSearchIntent
import build.raft.app.data.InboxItem
import build.raft.app.model.InboxItemKind
import build.raft.app.model.Message
import build.raft.app.note
import build.raft.app.shell.opening
import build.raft.app.raftArgbHex
import build.raft.app.shell.raftInlineListSelected
import build.raft.app.shell.raftSideColumnBackAction
import build.raft.app.shell.raftSideColumnControls
import build.raft.app.rememberHomeSearchReturnHighlight
import build.raft.app.shell.rememberRaftAppShellScope
import build.raft.app.rememberSharedHomeSearchInputFocus
import build.raft.app.resolve
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.EmptyRaftAgentStatusResolver
import build.raft.app.members.EmptyRaftMemberAppBotResolver
import build.raft.app.members.EmptyRaftMemberAvatarResolver
import build.raft.app.HomeSearchArtifactsContext
import build.raft.app.HomeSearchContinueFooter
import build.raft.app.HomeSearchCoreGroupsNoteLine
import build.raft.app.HomeSearchEntityGroupId
import build.raft.app.HomeSearchEntityGroupMore
import build.raft.app.HomeSearchEntityGroupTags
import build.raft.app.HomeSearchEntityShowAllToggle
import build.raft.app.HomeSearchHistoryWindowBanner
import build.raft.app.HomeSearchIncompleteEmptyState
import build.raft.app.HomeSearchOverlayChrome
import build.raft.app.HomeSearchOverlayPanel
import build.raft.app.HomeSearchReturnHighlight
import build.raft.app.LocalRaftAppShellNetworkOffline
import build.raft.app.members.LocalRaftMemberAppBotResolver
import build.raft.app.RaftAgentStatusResolver
import build.raft.app.RaftAppRadius
import build.raft.app.shell.RaftAppShellBodyTopBanner
import build.raft.app.shell.RaftAppShellScope
import build.raft.app.RaftAppStroke
import build.raft.app.RaftBackHandler
import build.raft.app.RaftBaseMenuItem
import build.raft.app.RaftBaseMenuOverlay
import build.raft.app.RaftCenteredLoading
import build.raft.app.RaftColor
import build.raft.app.RaftFilterComboboxPopover
import build.raft.app.RaftFilterOption
import build.raft.app.RaftFilterPopover
import build.raft.app.RaftHomeInlineError
import build.raft.app.RaftHomeNotificationIcon
import build.raft.app.RaftHomeNotificationItem
import build.raft.app.RaftHomeNotificationKind
import build.raft.app.shell.RaftInlineListSelection
import build.raft.app.RaftLucideIcon
import build.raft.app.RaftLucideIconName
import build.raft.app.members.RaftMemberAppBotResolver
import build.raft.app.members.RaftMemberAvatarResolver
import build.raft.app.members.iconName
import build.raft.app.members.localized
import build.raft.app.RaftPopoverAnchor
import build.raft.app.RaftPopoverAnchorResolver
import build.raft.app.RaftSearchEmptyState
import build.raft.app.RaftSearchListEnd
import build.raft.app.RaftSearchSummary
import build.raft.app.RaftSearchTabQueryRequest
import build.raft.app.RaftTopPageHeaderFrame
import build.raft.app.SharedThreadComposerDraftProjection
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import build.raft.app.data.map
import build.raft.app.fill
import build.raft.app.get
import build.raft.app.homeSearchArtifactKey
import build.raft.app.homeSearchArtifactsItems
import build.raft.app.homeSearchHistoryWindowDays
import build.raft.app.homeSearchIncompleteNote
import build.raft.app.homeSearchReturnHighlight
import build.raft.app.iconName
import build.raft.app.label
import build.raft.app.loadMoreIntent
import build.raft.app.localized
import build.raft.app.localizedErrorPresentationStrings
import build.raft.app.components.RaftSlotIcon
import build.raft.designsystem.components.raftClickable

// raft-ui SearchShellRoot sets its column's margin (`--column-margin`) to 20dp, the content edge of
// its list-cell rows (8dp inset + 12dp padding): the filters, summary, headings, rows and notices
// all start there.
internal val SearchControlsHorizontalPadding = 20.dp
internal val SearchMessagesHorizontalPadding = 20.dp
// The search head (the Search tab's frame bar, or the Back + field row) keeps the page head's 16dp
// (nav spec §2/§7.1).
private val SearchHeadHorizontalPadding = 16.dp
// web SearchFilterBar in the ⌘K overlay: `px-3`.
private val SearchOverlayFiltersHorizontalPadding = 12.dp
private val SearchPopoverBorderColor: Color get() = RaftColor.BorderBlack
private val SearchPopoverShadowColor: Color get() = RaftColor.ShadowBlack

@Composable
private fun localizedSharedHomeSearchErrorDisplay(
    code: String,
    action: SharedHomeSearchSavedIntent? = SharedHomeSearchSavedIntent.RefreshSearch
): RaftHomeInlineError =
    RaftHomeInlineError(
        title =
            when {
                code == "QUERY_TOO_BROAD" -> stringResource(Res.string.search_error_too_broad)
                code.contains("permission", ignoreCase = true) -> stringResource(Res.string.search_error_unavailable)
                code.contains("timeout", ignoreCase = true) -> stringResource(Res.string.search_error_timeout)
                code.contains("offline", ignoreCase = true) -> stringResource(Res.string.home_error_offline)
                code.contains("network", ignoreCase = true) -> stringResource(Res.string.search_error_refresh)
                else -> stringResource(Res.string.search_error_load)
            },
        detail =
            when {
                code == "QUERY_TOO_BROAD" -> stringResource(Res.string.search_error_too_broad_detail)
                code.contains("permission", ignoreCase = true) -> stringResource(Res.string.search_error_permission_detail)
                code.contains("timeout", ignoreCase = true) -> stringResource(Res.string.search_error_timeout_detail)
                code.contains("offline", ignoreCase = true) -> stringResource(Res.string.search_error_offline_detail)
                code.contains("network", ignoreCase = true) -> stringResource(Res.string.search_error_network_detail)
                else -> code.userFacingErrorCodeMessage(localizedErrorPresentationStrings())
            },
        code = code.ifBlank { "unknown_error" },
        actionLabel = when (action) {
            is SharedHomeSearchSavedIntent.SetSearchSort -> stringResource(Res.string.search_error_sort_recent)
            null -> ""
            else -> stringResource(Res.string.common_retry)
        }
    )

@Composable
internal fun SharedHomeNotification.toHomeNotificationItem(): RaftHomeNotificationItem =
    RaftHomeNotificationItem(
        id = id,
        title = titleDesc?.localized() ?: title,
        body = bodyDesc?.localized() ?: body,
        actionLabel = actionLabelDesc?.localized() ?: actionLabel,
        primaryActionActive =
            id == APP_UPDATE_HOME_NOTIFICATION_ID &&
                actionLabel.trim().lowercase() in setOf("install", "restart to update"),
        targetId = targetId,
        kind =
            when (kind) {
                SharedHomeNotificationKind.Warning -> RaftHomeNotificationKind.Warning
                SharedHomeNotificationKind.Info -> RaftHomeNotificationKind.Info
                SharedHomeNotificationKind.Error -> RaftHomeNotificationKind.Error
            },
        icon =
            when (icon) {
                SharedHomeNotificationIcon.Default -> RaftHomeNotificationIcon.Default
                SharedHomeNotificationIcon.WifiOff -> RaftHomeNotificationIcon.WifiOff
                SharedHomeNotificationIcon.CircleArrowUp -> RaftHomeNotificationIcon.CircleArrowUp
                SharedHomeNotificationIcon.Info -> RaftHomeNotificationIcon.Info
            }
    )

@Composable
internal fun SharedHomeSearchPanel(
    state: SharedHomeSearchPanelState,
    homeState: SharedHomeState,
    cacheServerId: String? = null,
    agentStatusResolver: RaftAgentStatusResolver = EmptyRaftAgentStatusResolver,
    memberAvatarResolver: RaftMemberAvatarResolver = EmptyRaftMemberAvatarResolver,
    // botiverse/slock #11273: which agents are App Bots (the Bot badge replaces the kind tag).
    memberAppBotResolver: RaftMemberAppBotResolver = EmptyRaftMemberAppBotResolver,
    computerDirectory: HomeSearchComputerDirectory = HomeSearchComputerDirectory(),
    draftProjection: SharedThreadComposerDraftProjection = SharedThreadComposerDraftProjection.Empty,
    onIntent: (SharedHomeSearchSavedIntent) -> Unit,
    onClose: (() -> Unit)? = null,
    // False for the wide layout's inline Search: [onClose] still reaches a system side column, but
    // the head draws no Back button (Esc / system Back close it through the shell).
    showCloseButton: Boolean = true,
    interactiveSearchField: Boolean = true,
    includeTopSafeArea: Boolean = true,
    showSenderFilter: Boolean = true,
    initialOpenFilterId: String? = null,
    initialFocusInput: Boolean = false,
    // False while the page is kept alive but not shown (the phone Search tab when another tab is
    // selected or a page is pushed over the shell): the field must not hold focus or the IME.
    inputActive: Boolean = true,
    // Each increment focuses the field (re-tapping the selected Search tab).
    focusInputRequest: Int = 0,
    // Advances when the hosting page reappears (a pushed result closed).
    visibleTick: Int = 0,
    // The opened-row mark, hoisted like [listState] where the page content is rebuilt on return.
    returnHighlightState: HomeSearchReturnHighlight? = null,
    // The rail's inline Search (two-pane): the row whose conversation is open beside the list is
    // selected while it stays open. Null on the phone, where a result covers the list.
    inlineListSelection: RaftInlineListSelection? = null,
    // The phone Search tab root: the field sits in the tab head (the 56dp frame bar).
    tabHead: Boolean = false,
    // A conversation-scoped pushed search: shows the "In #channel" scope and a "Search all"
    // action that hands the query to the Search tab.
    onExpandToSearchTab: ((String) -> Unit)? = null,
    // A query handed over from a conversation search (Search tab only); applied once.
    externalQueryRequest: RaftSearchTabQueryRequest? = null,
    onExternalQueryApplied: () -> Unit = {},
    initialHistoryEditing: Boolean = false,
    searchHomeVisualTextScale: Float = 1f,
    onOpenItem: (InboxItem) -> Unit,
    onOpenEntity: (SearchEntityResult) -> Unit,
    onRefreshComputers: () -> Unit = {},
    pinnedItems: List<SettingsServerSidebarPinnedItem> = emptyList(),
    onTogglePin: ((SearchEntityPinTarget) -> Unit)? = null,
    // The history notice's Upgrade to Pro / View your plan (web HistoryWindowBanner); null: no link.
    onHistoryUpgrade: (() -> Unit)? = null,
    bottomContentPadding: Dp,
    timeContext: AppShellThreadTimeContext? = null,
    shellScope: RaftAppShellScope = rememberRaftAppShellScope(),
    listState: LazyListState = rememberLazyListState(),
    modifier: Modifier = Modifier.fillMaxSize(),
    // The wide layout's search panel over the page (web SearchOverlay); null: the page or column.
    overlay: HomeSearchOverlayChrome? = null,
    // The Artifacts group of core's search (web AppSearchGroupSection); null: off (the flag, a
    // conversation's own search).
    artifacts: SearchArtifactsState? = null,
    onArtifactsIntent: (SearchArtifactsIntent) -> Unit = {},
    onOpenArtifact: (SearchArtifactHit) -> Unit = {},
    // Core's channels and people groups (web useCoreSearchGroups): when a group answers it replaces
    // that group's local rows. Null: off (a conversation's own search), the local rows only.
    coreGroups: SearchCoreGroupsState? = null,
    onCoreGroupsIntent: (SearchCoreGroupsIntent) -> Unit = {},
) {
    val channelFilters = remember(homeState) { homeSearchChannelFilters(homeState) }
    val artifactsContext =
        HomeSearchArtifactsContext(
            query = artifacts?.query.orEmpty(),
            serverName = (homeState.content as? SharedHomeContentState.Content)?.snapshot?.server?.name,
            timeContext = timeContext,
        )
    val artifactHits = artifacts?.hits.orEmpty()
    val artifactsShown = artifacts?.shown == true
    val channelEntityLabel = stringResource(Res.string.search_entity_channel)
    val dmEntityLabel = stringResource(Res.string.search_entity_dm)
    val agentEntityLabel = stringResource(Res.string.search_entity_agent)
    val personEntityLabel = stringResource(Res.string.search_entity_person)
    val membersEntityLabel = stringResource(Res.string.search_entity_members)
    val computerEntityLabel = stringResource(Res.string.search_entity_computer)
    val threadDiscussionLabel = stringResource(Res.string.search_thread_discussion)
    val entityResults = remember(
        state.query,
        homeState,
        state.senderFilters,
        channelEntityLabel,
        dmEntityLabel,
        agentEntityLabel,
        personEntityLabel,
        membersEntityLabel,
        computerEntityLabel,
        computerDirectory
    ) {
        homeSearchEntityResults(
            query = state.query,
            homeState = homeState,
            senderFilters = state.senderFilters,
            channelLabel = channelEntityLabel,
            dmLabel = dmEntityLabel,
            agentLabel = agentEntityLabel,
            personLabel = personEntityLabel,
            membersLabel = membersEntityLabel,
            computerLabel = computerEntityLabel,
            computerDirectory = computerDirectory
        )
    }
    // The local rows by key (every loaded destination, matched or not): a server row reuses the
    // loaded channel's or DM's own identity, and its key, when the app holds it.
    val localEntityByKey = remember(
        homeState,
        state.senderFilters,
        channelEntityLabel,
        dmEntityLabel,
        agentEntityLabel,
        personEntityLabel,
        membersEntityLabel,
    ) {
        homeSearchEntityCandidates(
            homeState = homeState,
            senderFilters = state.senderFilters,
            channelLabel = channelEntityLabel,
            dmLabel = dmEntityLabel,
            agentLabel = agentEntityLabel,
            personLabel = personEntityLabel,
            membersLabel = membersEntityLabel,
        ).associate { it.result.key to it.result }
    }
    val entityServerId = (homeState.content as? SharedHomeContentState.Content)?.snapshot?.server?.id?.value
    // web composeSearchEntityGroups: Channels, People, Computers; a group's server answer for these
    // words replaces its local rows.
    val entityGroups = remember(state.query, entityResults, coreGroups, localEntityByKey, entityServerId, channelEntityLabel, agentEntityLabel, personEntityLabel) {
        composeHomeSearchEntityGroups(
            query = state.query,
            local = entityResults,
            core = coreGroups,
            toChannel = { hit -> hit.toHomeSearchEntityResult(localEntityByKey, channelEntityLabel) },
            toPerson = { hit -> entityServerId?.let { hit.toHomeSearchEntityResult(localEntityByKey, it, agentEntityLabel, personEntityLabel) } },
        )
    }
    val allEntityResults = entityGroups.results
    val coreGroupsNote = entityGroups.note(offline = LocalRaftAppShellNetworkOffline.current)
    val retryCoreGroups = { onCoreGroupsIntent(SearchCoreGroupsIntent.Retry) }
    // web MessageSearchPage `historyWindowed`: a Free server's search reaches back only 30 days.
    val historyWindowDays = remember(homeState) { homeState.homeSearchHistoryWindowDays() }
    // web `searchIncomplete` / `incompleteSearchNote`: the server stopped at its scan budget.
    val incompleteNote = if (state.searchIncomplete) homeSearchIncompleteNote(state.searchedThrough, timeContext) else null
    val continueSearching = { onIntent(SharedHomeSearchSavedIntent.LoadMoreSearch) }
    // web: each group's Show all, collapsed again for each server and query.
    var expandedEntityGroups by remember(state.serverId, state.query.trim()) { mutableStateOf(emptySet<HomeSearchEntityGroupId>()) }
    val computerSearchEligible = state.query.trim().firstOrNull() !in setOf('#', '@') && state.query.trim().isNotBlank()
    val frequentEntityResults = remember(
        state.frequentEntityRanks,
        homeState,
        state.senderFilters,
        channelEntityLabel,
        dmEntityLabel,
        agentEntityLabel,
        personEntityLabel,
        membersEntityLabel,
    ) {
        homeSearchFrequentEntityResults(
            homeState = homeState,
            senderFilters = state.senderFilters,
            ranks = state.frequentEntityRanks,
            channelLabel = channelEntityLabel,
            dmLabel = dmEntityLabel,
            agentLabel = agentEntityLabel,
            personLabel = personEntityLabel,
            membersLabel = membersEntityLabel,
        )
    }
    val showSearchHome = !state.hasMessageSearchIntent()
    val hasSearchHomeContent = state.history.isNotEmpty() || frequentEntityResults.isNotEmpty()
    val projectedItems =
        remember(state.items, draftProjection) {
            state.items.map { item -> item.withComposerDraftProjection(draftProjection) }
        }
    val groupedResults = remember(projectedItems, threadDiscussionLabel) {
        groupHomeSearchResults(projectedItems, threadDiscussionLabel)
    }
    var openFilterId by remember(initialOpenFilterId) { mutableStateOf<String?>(null) }
    var pendingInitialOpenFilterId by remember(initialOpenFilterId) { mutableStateOf(initialOpenFilterId) }
    var scopeAnchor by remember { mutableStateOf<RaftPopoverAnchor?>(null) }
    var senderAnchor by remember { mutableStateOf<RaftPopoverAnchor?>(null) }
    var channelAnchor by remember { mutableStateOf<RaftPopoverAnchor?>(null) }
    var timeAnchor by remember { mutableStateOf<RaftPopoverAnchor?>(null) }
    var sortAnchor by remember { mutableStateOf<RaftPopoverAnchor?>(null) }
    var scopeAnchorResolver by remember { mutableStateOf<RaftPopoverAnchorResolver?>(null) }
    var senderAnchorResolver by remember { mutableStateOf<RaftPopoverAnchorResolver?>(null) }
    var channelAnchorResolver by remember { mutableStateOf<RaftPopoverAnchorResolver?>(null) }
    var timeAnchorResolver by remember { mutableStateOf<RaftPopoverAnchorResolver?>(null) }
    var sortAnchorResolver by remember { mutableStateOf<RaftPopoverAnchorResolver?>(null) }
    var activePinMenu by remember { mutableStateOf<SharedHomeSearchPinMenuState?>(null) }
    var historyEditing by remember(initialHistoryEditing) { mutableStateOf(initialHistoryEditing) }
    val searchEditor = remember { SharedHomeSearchEditorOwner(state.query) }
    // The field owns its text, so a store-side reset has to be pushed into it: switching server
    // empties the query in the store (search is per server), and the box must visibly empty too.
    var searchEditorServerId by remember { mutableStateOf(state.serverId) }
    LaunchedEffect(state.serverId) {
        if (state.serverId != searchEditorServerId) {
            searchEditorServerId = state.serverId
            searchEditor.adoptStoreReset(state.query)
        }
    }
    val searchFocusRequester = remember { FocusRequester() }
    val searchInputFocus =
        rememberSharedHomeSearchInputFocus(
            focusRequester = searchFocusRequester,
            inputActive = inputActive,
            focusInputRequest = focusInputRequest,
            onFocusInputRequest = searchEditor::selectAll
        )
    val returnHighlight = rememberHomeSearchReturnHighlight(pageVisible = inputActive, visibleTick = visibleTick, hoisted = returnHighlightState)
    // The initial autofocus (pushed search pages) runs once, the first time the page shows;
    // coming back from a result never refocuses the field.
    var pendingInitialFocus by remember { mutableStateOf(initialFocusInput) }
    LaunchedEffect(inputActive) {
        if (inputActive && pendingInitialFocus) {
            pendingInitialFocus = false
            delay(100)
            searchFocusRequester.requestFocus()
        }
    }
    LaunchedEffect(externalQueryRequest?.nonce) {
        val request = externalQueryRequest ?: return@LaunchedEffect
        searchEditor.replaceText(request.query) { next ->
            onIntent(SharedHomeSearchSavedIntent.SearchQueryChanged(next))
        }
        if (request.query.isNotBlank()) onIntent(SharedHomeSearchSavedIntent.SubmitSearch)
        onExternalQueryApplied()
        // A conversation search's "Search all" moved the query here: keep typing in this box, as
        // the user was in the one they left (artin 11-20: the Search tab did not take focus).
        delay(100)
        // Not attached when the tab is not showing yet: then the next visit's autofocus applies.
        runCatching { searchFocusRequester.requestFocus() }
    }
    var senderSearch by remember { mutableStateOf("") }
    var channelSearch by remember { mutableStateOf("") }
    val pinLabel = stringResource(Res.string.home_action_pin)
    val unpinLabel = stringResource(Res.string.home_action_unpin)
    LaunchedEffect(allEntityResults) {
        val activeKey = activePinMenu?.resultKey
        if (activeKey != null && allEntityResults.none { it.key == activeKey }) {
            activePinMenu = null
        }
    }
    LaunchedEffect(state.history) {
        if (state.history.isEmpty()) historyEditing = false
    }
    LaunchedEffect(showSearchHome) {
        if (!showSearchHome) historyEditing = false
    }
    LaunchedEffect(Unit) {
        onIntent(SharedHomeSearchSavedIntent.OpenSearch)
    }
    LaunchedEffect(
        initialOpenFilterId,
        scopeAnchorResolver,
        senderAnchorResolver,
        channelAnchorResolver,
        timeAnchorResolver,
        sortAnchorResolver
    ) {
        val pending = pendingInitialOpenFilterId ?: return@LaunchedEffect
        val resolved =
            when (pending) {
                "scope" -> scopeAnchorResolver?.resolve()?.also { scopeAnchor = it }
                "sender" -> senderAnchorResolver?.resolve()?.also { senderAnchor = it }
                "channel" -> channelAnchorResolver?.resolve()?.also { channelAnchor = it }
                "time" -> timeAnchorResolver?.resolve()?.also { timeAnchor = it }
                "sort" -> sortAnchorResolver?.resolve()?.also { sortAnchor = it }
                else -> null
            }
        if (resolved != null) {
            openFilterId = pending
            pendingInitialOpenFilterId = null
        }
    }
    RaftBackHandler(enabled = openFilterId != null || activePinMenu != null || historyEditing) {
        when {
            openFilterId != null || activePinMenu != null -> {
                openFilterId = null
                pendingInitialOpenFilterId = null
                activePinMenu = null
            }
            historyEditing -> historyEditing = false
        }
    }
    // Nav spec §7.2 / web MessageSearchPage `scopeConversation`: a conversation's own search
    // ("In #general" in the filter strip, "Search everywhere" atop the results).
    val scopeConversation: String? =
        if (onExpandToSearchTab != null) {
            (channelFilters.firstOrNull { it.id == state.selectedChannelId }
                ?: agentLoungeSearchScopeOrNull(state.selectedChannelId))?.searchScopeLabel()
        } else {
            null
        }
    val searchFilters: @Composable () -> Unit = {
                SharedHomeSearchFilters(
                    state = state,
                    channelFilters = channelFilters,
                    scopeConversation = scopeConversation,
                    scopeAnchorChanged = { scopeAnchorResolver = it },
                    senderAnchorChanged = { senderAnchorResolver = it },
                    channelAnchorChanged = { channelAnchorResolver = it },
                    timeAnchorChanged = { timeAnchorResolver = it },
                    sortAnchorChanged = { sortAnchorResolver = it },
                    onOpenFilter = { id, anchor ->
                        when (id) {
                            "scope" -> scopeAnchor = anchor
                            "sender" -> senderAnchor = anchor
                            "channel" -> channelAnchor = anchor
                            "time" -> timeAnchor = anchor
                            "sort" -> sortAnchor = anchor
                        }
                        openFilterId = id
                    },
                    onIntent = onIntent,
                    showSenderFilter = showSenderFilter,
                    horizontalPadding = if (overlay != null) SearchOverlayFiltersHorizontalPadding else SearchControlsHorizontalPadding,
                    // From and Channel open the web's Combobox popup under the chip, with search,
                    // at any number of options (artin 2026-11-02), never a sheet.
                    // Composed only while open, so a closed chip builds no option list.
                    chipPopover = { id ->
                        if (openFilterId == id) when (id) {
                            "sender" ->
                                RaftFilterComboboxPopover(
                                    expanded = true,
                                    title = stringResource(Res.string.search_filter_from),
                                    searchPlaceholder = stringResource(Res.string.search_filter_search),
                                    searchText = senderSearch,
                                    onSearchChange = { senderSearch = it },
                                    options = state.senderFilterOptions(),
                                    // One sender, like the web: a pick replaces the current sender.
                                    selectedIds = setOfNotNull(state.selectedSenderId),
                                    onToggle = {
                                        onIntent(SharedHomeSearchSavedIntent.SetSearchSender(it))
                                    },
                                    onClear = { onIntent(SharedHomeSearchSavedIntent.SetSearchSender(null)) },
                                    onDismiss = { openFilterId = null },
                                    multiSelect = false,
                                    showClear = true,
                                    // An empty list while senders load (or fail) says so.
                                    status =
                                        when {
                                            state.senderFiltersLoading -> stringResource(Res.string.search_filter_loading_senders)
                                            else -> state.senderFiltersErrorCode
                                        }
                                )
                            "channel" ->
                                RaftFilterComboboxPopover(
                                    expanded = true,
                                    title = stringResource(Res.string.search_filter_channels),
                                    searchPlaceholder = stringResource(Res.string.search_filter_search),
                                    searchText = channelSearch,
                                    onSearchChange = { channelSearch = it },
                                    options = channelFilters.map { RaftFilterOption(it.id, it.label, it.icon) },
                                    selectedIds = state.selectedChannelId?.let { setOf(it) } ?: emptySet(),
                                    onToggle = {
                                        onIntent(SharedHomeSearchSavedIntent.SetSearchChannel(it))
                                    },
                                    onClear = { onIntent(SharedHomeSearchSavedIntent.SetSearchChannel(null)) },
                                    onDismiss = { openFilterId = null },
                                    multiSelect = false,
                                    showClear = true
                                )
                            else -> Unit
                        }
                    }
                )
    }
    val searchPopups: @Composable () -> Unit = {
            when (openFilterId) {
                "scope" ->
                    RaftFilterPopover(
                        anchor = scopeAnchor,
                        title = stringResource(Res.string.search_filter_scope),
                        searchPlaceholder = "",
                        searchText = "",
                        onSearchChange = {},
                        options = SearchScope.entries.map { RaftFilterOption(it.key, it.localizedSearchLabel()) },
                        selectedIds = state.selectedScopes.mapTo(linkedSetOf()) { it.key },
                        onToggle = { key ->
                            SearchScope.entries.firstOrNull { it.key == key }?.let {
                                onIntent(SharedHomeSearchSavedIntent.ToggleSearchScope(it))
                            }
                        },
                        onClear = { onIntent(SharedHomeSearchSavedIntent.ClearSearchScopes) },
                        onDismiss = { openFilterId = null },
                        widthDp = 180f,
                        multiSelect = true,
                        searchable = false,
                        surfaceBorderColor = SearchPopoverBorderColor,
                        surfaceShadowColor = SearchPopoverShadowColor,
                        selectedRowColor = RaftColor.SurfaceWhite,
                        showClearWhenSelected = true
                    )
                "time" ->
                    RaftFilterPopover(
                        anchor = timeAnchor,
                        title = stringResource(Res.string.search_filter_time),
                        searchPlaceholder = "",
                        searchText = "",
                        onSearchChange = {},
                        options = SearchTimeRange.entries.map { RaftFilterOption(it.key, it.localizedSearchLabel()) },
                        selectedIds = setOf(state.timeRange.key),
                        onToggle = { key ->
                            SearchTimeRange.entries.firstOrNull { it.key == key }?.let {
                                onIntent(SharedHomeSearchSavedIntent.SetSearchTimeRange(it))
                            }
                        },
                        onClear = {},
                        onDismiss = { openFilterId = null },
                        widthDp = 168f,
                        multiSelect = false,
                        searchable = false,
                        surfaceBorderColor = SearchPopoverBorderColor,
                        surfaceShadowColor = SearchPopoverShadowColor,
                        selectedRowColor = RaftColor.SurfaceWhite
                    )
                "sort" ->
                    RaftFilterPopover(
                        anchor = sortAnchor,
                        title = stringResource(Res.string.search_filter_sort),
                        searchPlaceholder = "",
                        searchText = "",
                        onSearchChange = {},
                        options =
                            listOf(
                                RaftFilterOption("relevance", stringResource(Res.string.search_sort_relevant)),
                                RaftFilterOption("recent", stringResource(Res.string.search_sort_recent))
                            ),
                        selectedIds = setOf(state.sort.searchKey),
                        onToggle = { key ->
                            when (key) {
                                "recent" -> onIntent(SharedHomeSearchSavedIntent.SetSearchSort(SearchSort.Recent))
                                else -> onIntent(SharedHomeSearchSavedIntent.SetSearchSort(SearchSort.Relevance))
                            }
                        },
                        onClear = {},
                        onDismiss = { openFilterId = null },
                        widthDp = 148f,
                        multiSelect = false,
                        searchable = false,
                        surfaceBorderColor = SearchPopoverBorderColor,
                        surfaceShadowColor = SearchPopoverShadowColor,
                        selectedRowColor = RaftColor.SurfaceWhite
                    )
                else -> Unit
            }
            activePinMenu?.let { menu ->
                RaftBaseMenuOverlay(
                    anchor = menu.anchor,
                    items =
                        listOf(
                            RaftBaseMenuItem(
                                id = SharedHomeSearchPinMenuActionToggle,
                                label = if (menu.isPinned) unpinLabel else pinLabel,
                                icon = if (menu.isPinned) RaftLucideIconName.PinOff else RaftLucideIconName.Pin
                            )
                        ),
                    selectedId = "",
                    uppercaseLabels = false,
                    onDismiss = { activePinMenu = null },
                    onSelect = { actionId ->
                        activePinMenu = null
                        dispatchSearchEntityPinMenuSelection(
                            actionId = actionId,
                            target = menu.target,
                            onTogglePin = onTogglePin
                        )
                    }
                )
            }
    }
    if (overlay != null) {
        CompositionLocalProvider(LocalRaftMemberAppBotResolver provides memberAppBotResolver) {
            RaftDesignSystemTheme {
                HomeSearchOverlayPanel(
                    overlay = overlay,
                    state = state,
                    homeState = homeState,
                    entityGroups = entityGroups,
                    coreGroupsNote = coreGroupsNote,
                    onCoreGroupsIntent = onCoreGroupsIntent,
                    frequentEntityResults = frequentEntityResults,
                    groupedResults = groupedResults,
                    editor = searchEditor,
                    focusRequester = searchFocusRequester,
                    fieldModifier = searchInputFocus.fieldModifier,
                    filterMenuOpen = openFilterId != null || activePinMenu != null,
                    filters = searchFilters,
                    popups = searchPopups,
                    cacheServerId = cacheServerId,
                    agentStatusResolver = agentStatusResolver,
                    memberAvatarResolver = memberAvatarResolver,
                    timeContext = timeContext,
                    listState = listState,
                    onIntent = onIntent,
                    onOpenItem = onOpenItem,
                    onOpenEntity = onOpenEntity,
                    historyWindowDays = historyWindowDays,
                    incompleteNote = incompleteNote,
                    onHistoryUpgrade = onHistoryUpgrade,
                    artifacts = artifacts,
                    artifactsContext = artifactsContext,
                    onArtifactsIntent = onArtifactsIntent,
                    onOpenArtifact = onOpenArtifact,
                )
            }
        }
        return
    }
    // raft-ui search panel: designsystem type tokens and badges, on the canvas layer.
    CompositionLocalProvider(LocalRaftMemberAppBotResolver provides memberAppBotResolver) {
    RaftDesignSystemTheme {
        Box(
            modifier =
                modifier
                    .background(RaftColor.Canvas)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                SharedHomeSearchToolbar(
                    editor = searchEditor,
                    focusRequester = searchFocusRequester,
                    fieldModifier = searchInputFocus.fieldModifier,
                    query = state.query,
                    loading = state.loading,
                    onQueryChange = { onIntent(SharedHomeSearchSavedIntent.SearchQueryChanged(it)) },
                    onSubmit = { onIntent(SharedHomeSearchSavedIntent.SubmitSearch) },
                    onClear = { onIntent(SharedHomeSearchSavedIntent.SearchQueryChanged("")) },
                    onClose = onClose,
                    showCloseButton = showCloseButton,
                    interactive = interactiveSearchField,
                    tabHead = tabHead,
                    topSafeAreaPadding = shellScope.topInset(includeTopSafeArea),
                    placeholder = stringResource(Res.string.search_placeholder)
                )
                // The tab head draws its own 1dp seam.
                if (!tabHead) SharedHomeSearchDivider()
                RaftAppShellBodyTopBanner()
                searchFilters()
                SharedHomeSearchDivider()
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    if (scopeConversation != null && onExpandToSearchTab != null) {
                        item(key = "search-everywhere") {
                            SharedHomeSearchEverywhereRow(
                                query = state.query,
                                onClick = {
                                    searchInputFocus.release()
                                    onExpandToSearchTab(state.query)
                                }
                            )
                        }
                    }
                    item {
                        // One column: the history notice stacks above what the item shows.
                        Column(Modifier.fillMaxWidth()) {
                        // web `totalResults`: destinations, app hits and messages.
                        val hasSearchRows = projectedItems.isNotEmpty() || !entityGroups.isEmpty || artifactHits.isNotEmpty()
                        // web `historyNotice`: on every search, before anything is typed too; not beside
                        // a failed search's own error.
                        val searchFailed =
                            !showSearchHome &&
                                (
                                    state.noPermissionCode != null ||
                                        (state.errorCode != null && !hasSearchRows) ||
                                        (computerSearchEligible && computerDirectory.initialLoadError != null && !hasSearchRows)
                                    )
                        val historyNotice: @Composable () -> Unit = {
                            if (historyWindowDays != null && !searchFailed) {
                                HomeSearchHistoryWindowBanner(days = historyWindowDays, onUpgrade = onHistoryUpgrade)
                            }
                        }
                        if (showSearchHome && (!state.personalizationLoaded || !hasSearchHomeContent)) {
                            historyNotice()
                            SharedHomeSearchEmptyState(
                                title = stringResource(Res.string.search_empty_title),
                                detail = stringResource(Res.string.search_empty_detail)
                            )
                        } else if (showSearchHome) {
                            historyNotice()
                        } else if ((state.loading || entityGroups.pending || (computerSearchEligible && computerDirectory.initialLoadInFlight)) && !hasSearchRows) {
                            Column(Modifier.fillParentMaxSize()) {
                                historyNotice()
                                RaftCenteredLoading(modifier = Modifier.fillMaxWidth().weight(1f))
                            }
                        } else if (
                            state.noPermissionCode != null ||
                                (state.errorCode != null && !hasSearchRows) ||
                                (computerSearchEligible && computerDirectory.initialLoadError != null && !hasSearchRows)
                        ) {
                            val errorCode = state.noPermissionCode ?: state.errorCode ?: computerDirectory.initialLoadError?.code.orEmpty()
                            val recoveryAction = sharedHomeSearchErrorAction(errorCode, state.sort)
                            SharedHomeSearchErrorState(
                                error = localizedSharedHomeSearchErrorDisplay(errorCode, recoveryAction),
                                showAction = recoveryAction != null,
                                onAction = {
                                    recoveryAction?.let(onIntent)
                                    if (recoveryAction == SharedHomeSearchSavedIntent.RefreshSearch) {
                                        onRefreshComputers()
                                    }
                                }
                            )
                        } else if (!state.searched && projectedItems.isEmpty() && entityGroups.isEmpty && !artifactsShown) {
                            historyNotice()
                            SharedHomeSearchEmptyState(
                                title = stringResource(Res.string.search_empty_title),
                                detail = stringResource(Res.string.search_empty_detail)
                            )
                        } else if (
                            (state.empty || state.searched) &&
                                projectedItems.isEmpty() &&
                                entityGroups.isEmpty &&
                                !artifactsShown &&
                                incompleteNote != null
                        ) {
                            // web SearchNoResultsState `searchIncomplete`: not "no results" yet.
                            historyNotice()
                            HomeSearchIncompleteEmptyState(
                                note = incompleteNote,
                                loadingMore = state.loadingMore,
                                onContinueSearching = continueSearching,
                            )
                        } else if (
                            (state.empty || state.searched) &&
                                projectedItems.isEmpty() &&
                                entityGroups.isEmpty &&
                                // web `showNoResults`: not while an app group waits, failed or has hits.
                                !artifactsShown
                        ) {
                            val trimmedQuery = state.query.trim()
                            historyNotice()
                            // web SearchNoResultsState `onlyLoadedEntities`: core's channels and
                            // people couldn't be searched, so the line says why and the title says
                            // only the loaded ones were.
                            coreGroupsNote?.let { HomeSearchCoreGroupsNoteLine(it, entityGroups.error, retryCoreGroups) }
                            SharedHomeSearchEmptyState(
                                title =
                                    if (coreGroupsNote != null) {
                                        stringResource(Res.string.search_core_groups_no_loaded_matches)
                                    } else if (trimmedQuery.isNotBlank()) {
                                        Res.string.search_empty_results_query.format(listOf(trimmedQuery)).localized()
                                    } else {
                                        stringResource(Res.string.search_empty_results)
                                    },
                                detail =
                                    if (state.tooBroad) {
                                        // web SearchNoResultsState: relaxedSkipped=too_broad is not "nothing matches".
                                        stringResource(Res.string.search_too_broad_hint)
                                    } else if (trimmedQuery.isNotBlank()) {
                                        stringResource(Res.string.search_empty_query_detail)
                                    } else {
                                        stringResource(Res.string.search_empty_filters_detail)
                                    }
                            )
                        } else {
                            SharedHomeSearchResultsCount(
                                totalResults = state.totalCount + entityGroups.total + artifactHits.size,
                                // web: "Searching…" until every core group has answered too.
                                searching = state.querySearchPending || state.loading || state.refreshing || entityGroups.pending
                            )
                            // web SearchEntityResultsIncompleteNote: the summary's second line.
                            coreGroupsNote?.let { HomeSearchCoreGroupsNoteLine(it, entityGroups.error, retryCoreGroups) }
                            historyNotice()
                        }
                        }
                    }
                    if (showSearchHome && state.personalizationLoaded && state.history.isNotEmpty()) {
                        item(key = "search-home-history") {
                            SharedHomeSearchHistorySection(
                                history = state.history,
                                editing = historyEditing,
                                visualTextScale = searchHomeVisualTextScale,
                                onEditingChange = { historyEditing = it },
                                onSelect = { query ->
                                    historyEditing = false
                                    searchEditor.replaceText(query) { next ->
                                        onIntent(SharedHomeSearchSavedIntent.SearchQueryChanged(next))
                                    }
                                    searchFocusRequester.requestFocus()
                                },
                                onRemove = { query ->
                                    onIntent(SharedHomeSearchSavedIntent.RemoveSearchHistoryEntry(query))
                                },
                                onClear = {
                                    historyEditing = false
                                    onIntent(SharedHomeSearchSavedIntent.ClearSearchHistory)
                                },
                            )
                        }
                    }
                    if (showSearchHome && state.personalizationLoaded && hasSearchHomeContent) {
                        item(key = "search-home-frequent-heading") {
                            SharedHomeSearchSectionLabel(
                                label = stringResource(Res.string.search_home_frequent),
                                visualTextScale = searchHomeVisualTextScale,
                            )
                        }
                        if (frequentEntityResults.isEmpty()) {
                            item(key = "search-home-frequent-empty") {
                                Box(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            // web `px-(--column-margin) py-2`; the heading's 8dp is the top.
                                            .padding(start = SearchMessagesHorizontalPadding, end = SearchMessagesHorizontalPadding, bottom = 8.dp),
                                ) {
                                    RaftText(
                                        text = stringResource(Res.string.search_home_frequent_empty),
                                        style = RaftTheme.typography.callout.withHomeSearchVisualTextScale(searchHomeVisualTextScale),
                                        color = RaftColor.ForegroundHint,
                                    )
                                }
                            }
                        } else {
                            items(
                                frequentEntityResults.size,
                                key = { index -> "frequent:${frequentEntityResults[index].key}" },
                            ) { index ->
                                val frequentKey = "frequent:${frequentEntityResults[index].key}"
                                val selected = inlineListSelection?.selectedRowKey == frequentKey
                                Box(
                                    Modifier
                                        .raftInlineListSelected(selected)
                                        .homeSearchReturnHighlight(returnHighlight, frequentKey)
                                ) {
                                SharedHomeSearchEntityRow(
                                    result = frequentEntityResults[index],
                                    query = "",
                                    showSeparator = index > 0,
                                    selected = selected,
                                    agentStatusResolver = agentStatusResolver,
                                    memberAvatarResolver = memberAvatarResolver,
                                    isPinned = frequentEntityResults[index].isPinnedBy(pinnedItems),
                                    visualTextScale = searchHomeVisualTextScale,
                                    onOpen = {
                                        searchInputFocus.release()
                                        returnHighlight.markOpened(frequentKey)
                                        inlineListSelection.opening(frequentKey) { onOpenEntity(frequentEntityResults[index]) }
                                    },
                                    onContextMenu = null,
                                )
                                }
                            }
                            item(key = "search-home-frequent-end") { RaftSearchListEnd() }
                        }
                    }
                    // web SearchEntityResultsSection, one per group: the heading with its count
                    // (the server's total; none while it is asked) and Show all at its end; the
                    // first five rows, every loaded one when expanded, then Load more.
                    entityGroups.groups.forEach { group ->
                        val expanded = group.id in expandedEntityGroups
                        val shownEntityResults = group.shown(expanded)
                        item(key = "search-entity-group-${group.id.testTag}") {
                            Box(Modifier.testTag("${HomeSearchEntityGroupTags.Group}-${group.id.testTag}")) {
                                SharedHomeSearchSectionLabel(
                                    group.id.label(),
                                    count = group.count,
                                    trailing =
                                        if (group.showsToggle(expanded)) {
                                            {
                                                HomeSearchEntityShowAllToggle(expanded) {
                                                    expandedEntityGroups =
                                                        if (expanded) expandedEntityGroups - group.id else expandedEntityGroups + group.id
                                                }
                                            }
                                        } else {
                                            null
                                        },
                                )
                            }
                        }
                        items(shownEntityResults.size, key = { index -> shownEntityResults[index].key }) { index ->
                            val selected = inlineListSelection?.selectedRowKey == shownEntityResults[index].key
                            Box(
                                Modifier
                                    .raftInlineListSelected(selected)
                                    .homeSearchReturnHighlight(returnHighlight, shownEntityResults[index].key)
                            ) {
                            SharedHomeSearchEntityRow(
                                result = shownEntityResults[index],
                                query = state.query,
                                showSeparator = index > 0,
                                selected = selected,
                                agentStatusResolver = agentStatusResolver,
                                memberAvatarResolver = memberAvatarResolver,
                                isPinned = shownEntityResults[index].isPinnedBy(pinnedItems),
                                onOpen = {
                                    searchInputFocus.release()
                                    returnHighlight.markOpened(shownEntityResults[index].key)
                                    inlineListSelection.opening(shownEntityResults[index].key) { onOpenEntity(shownEntityResults[index]) }
                                },
                                onContextMenu =
                                    searchEntityPinTarget(shownEntityResults[index])
                                        ?.takeIf { onTogglePin != null }
                                        ?.let { target ->
                                            { anchor ->
                                                activePinMenu =
                                                    SharedHomeSearchPinMenuState(
                                                        resultKey = shownEntityResults[index].key,
                                                        target = target,
                                                        isPinned = shownEntityResults[index].isPinnedBy(pinnedItems),
                                                        anchor = anchor
                                                    )
                                            }
                                        }
                            )
                            }
                        }
                        if (group.showsMore(expanded)) {
                            item(key = "search-entity-more-${group.id.testTag}") {
                                HomeSearchEntityGroupMore(group) { group.id.loadMoreIntent()?.let(onCoreGroupsIntent) }
                            }
                        }
                        item(key = "search-entity-end-${group.id.testTag}") { RaftSearchListEnd() }
                    }
                    // web MessageSearchPage: the app groups between the destinations and the messages.
                    if (!showSearchHome) {
                        homeSearchArtifactsItems(
                            state = artifacts,
                            context = artifactsContext,
                            // The viewer opens over the list: no row stays selected beside it.
                            selectedKey = null,
                            onIntent = onArtifactsIntent,
                            onOpen = { hit ->
                                searchInputFocus.release()
                                val key = homeSearchArtifactKey(hit)
                                returnHighlight.markOpened(key)
                                onOpenArtifact(hit)
                            },
                            rowModifier = { key -> Modifier.homeSearchReturnHighlight(returnHighlight, key) },
                        )
                    }
                    if (groupedResults.isNotEmpty()) {
                        item {
                            // web SearchMessageResultsSection: the message count once loaded.
                            SharedHomeSearchSectionLabel(
                                stringResource(Res.string.search_section_messages),
                                count = state.totalCount.takeIf { it > 0 && !state.loading },
                            )
                        }
                        if (state.relevanceFallback && state.sort != SearchSort.Recent && !state.loading) {
                            item(key = "search-relevance-fallback") {
                                // web SearchMessageResultsSection `search-relevance-fallback`: too many
                                // matches to rank, so the server sorted this page newest first.
                                RaftText(
                                    text = stringResource(Res.string.search_relevance_fallback_note),
                                    style = RaftTheme.typography.caption,
                                    color = RaftColor.ForegroundHint,
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(start = SearchMessagesHorizontalPadding, end = SearchMessagesHorizontalPadding, bottom = 4.dp),
                                )
                            }
                        }
                    }
                    groupedResults.forEachIndexed { groupIndex, group ->
                        when (group) {
                            is HomeSearchResultGroup.Message -> {
                                item(key = group.item.key) {
                                    val selected = inlineListSelection?.selectedRowKey == group.item.key
                                    Box(
                                        Modifier
                                            .raftInlineListSelected(selected)
                                            .homeSearchReturnHighlight(returnHighlight, group.item.key)
                                    ) {
                                    SharedHomeSearchMessageResultRow(
                                        item = group.item,
                                        query = state.query,
                                        showSeparator = groupIndex > 0,
                                        selected = selected,
                                        cacheServerId = cacheServerId,
                                        memberAvatarResolver = memberAvatarResolver,
                                        timeContext = timeContext,
                                        onOpen = {
                                            searchInputFocus.release()
                                            returnHighlight.markOpened(group.item.key)
                                            inlineListSelection.opening(group.item.key) { onOpenItem(group.item) }
                                        }
                                    )
                                    }
                                }
                            }
                            is HomeSearchResultGroup.Thread -> {
                                item(key = group.key) {
                                    val selected = inlineListSelection?.selectedRowKey == group.key
                                    Box(
                                        Modifier
                                            .raftInlineListSelected(selected)
                                            .homeSearchReturnHighlight(returnHighlight, group.key)
                                    ) {
                                    SharedHomeThreadResultGroup(
                                        group = group,
                                        query = state.query,
                                        showSeparator = groupIndex > 0,
                                        selected = selected,
                                        cacheServerId = cacheServerId,
                                        memberAvatarResolver = memberAvatarResolver,
                                        timeContext = timeContext,
                                        onOpenItem = { item ->
                                            searchInputFocus.release()
                                            returnHighlight.markOpened(group.key)
                                            inlineListSelection.opening(group.key) { onOpenItem(item) }
                                        }
                                    )
                                    }
                                }
                            }
                        }
                    }
                    if (groupedResults.isNotEmpty()) {
                        item(key = "search-messages-end") { RaftSearchListEnd() }
                    }
                    if (incompleteNote != null && (projectedItems.isNotEmpty() || !entityGroups.isEmpty)) {
                        // web SearchLoadMoreFooter `searchIncomplete`: where the search got to, and
                        // Continue searching in place of Load more.
                        item(key = "search-continue") {
                            HomeSearchContinueFooter(
                                note = incompleteNote,
                                loadingMore = state.loadingMore,
                                onContinueSearching = continueSearching,
                            )
                        }
                    } else if (state.hasMore && incompleteNote == null) {
                        item {
                            SharedHomeLoadMoreRow(
                                loading = state.loadingMore,
                                label = stringResource(Res.string.search_action_load_more_results),
                                onClick = { onIntent(SharedHomeSearchSavedIntent.LoadMoreSearch) }
                            )
                        }
                    }
                    item {
                        Spacer(Modifier.height(bottomContentPadding))
                    }
                }
            }
            searchPopups()
        }
    }
    }
}

private const val SharedHomeSearchPinMenuActionToggle = "toggle-pin"

private data class SharedHomeSearchPinMenuState(
    val resultKey: String,
    val target: SearchEntityPinTarget,
    val isPinned: Boolean,
    val anchor: RaftPopoverAnchor
)

internal fun dispatchSearchEntityPinMenuSelection(
    actionId: String,
    target: SearchEntityPinTarget,
    onTogglePin: ((SearchEntityPinTarget) -> Unit)?
) {
    if (actionId == SharedHomeSearchPinMenuActionToggle) {
        onTogglePin?.invoke(target)
    }
}

// raft-ui PanelHeader / search `filtersRow`: a 1dp `border-line-seam` under each bar.
@Composable
private fun SharedHomeSearchDivider() {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(RaftAppStroke.Thin)
                .background(RaftColor.BorderSeam)
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SharedHomeSearchHistorySection(
    history: List<String>,
    editing: Boolean,
    visualTextScale: Float = 1f,
    onEditingChange: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
) {
    // The history heading reads as a search `sectionHeading` (footnote bold hint); the
    // entries are raft-ui Combobox chips (`h-7` chip radius, `fill-muted`, footnote semibold).
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    start = SearchMessagesHorizontalPadding,
                    top = 8.dp,
                    end = SearchMessagesHorizontalPadding,
                    bottom = 8.dp,
                ),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RaftLucideIcon(
                RaftLucideIconName.Clock,
                color = raftArgbHex(RaftColor.ForegroundHint),
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            RaftText(
                text = stringResource(Res.string.search_home_history),
                style =
                    RaftTheme.typography.footnote
                        .copy(fontWeight = FontWeight.Bold)
                        .withHomeSearchVisualTextScale(visualTextScale),
                color = RaftColor.ForegroundHint,
                modifier = Modifier.weight(1f),
            )
            SharedHomeSearchHistoryTextAction(
                label =
                    if (editing) {
                        stringResource(Res.string.search_home_done)
                    } else {
                        stringResource(Res.string.search_home_edit)
                    },
                onClick = { onEditingChange(!editing) },
                visualTextScale = visualTextScale,
            )
            SharedHomeSearchHistoryTextAction(
                label = stringResource(Res.string.search_home_clear),
                onClick = onClear,
                visualTextScale = visualTextScale,
            )
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            history.forEach { query ->
                SharedHomeSearchHistoryTag(
                    query = query,
                    editing = editing,
                    visualTextScale = visualTextScale,
                    onSelect = { onSelect(query) },
                    onRemove = { onRemove(query) },
                )
            }
        }
    }
}

@Composable
private fun SharedHomeSearchHistoryTextAction(
    label: String,
    onClick: () -> Unit,
    visualTextScale: Float = 1f,
) {
    Box(
        modifier =
            Modifier
                .height(44.dp)
                .raftClickable(onClick = onClick)
                .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        RaftText(
            text = label,
            style =
                RaftTheme.typography.footnote
                    .copy(fontWeight = FontWeight.Bold)
                    .withHomeSearchVisualTextScale(visualTextScale),
            color = RaftColor.TextBlack,
            maxLines = 1,
        )
    }
}

@Composable
private fun SharedHomeSearchHistoryTag(
    query: String,
    editing: Boolean,
    visualTextScale: Float = 1f,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
) {
    val removeLabel =
        if (editing) {
            Res.string.search_home_remove_history.format(listOf(query)).localized()
        } else {
            null
        }
    Box(
        modifier =
            Modifier
                .height(if (editing) 44.dp else 28.dp)
                .widthIn(max = 260.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier =
                Modifier
                    .height(28.dp)
                    .background(RaftColor.FillMuted, RoundedCornerShape(RaftAppRadius.Chip)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier =
                    Modifier
                        .height(28.dp)
                        .weight(1f, fill = false)
                        .padding(start = 8.dp, end = if (editing) 0.dp else 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                RaftLucideIcon(
                    RaftLucideIconName.Clock,
                    color = raftArgbHex(RaftColor.ForegroundHint),
                    modifier = Modifier.size(12.dp),
                )
                RaftText(
                    text = query,
                    style =
                        RaftTheme.typography.footnote
                            .copy(fontWeight = FontWeight.SemiBold)
                            .withHomeSearchVisualTextScale(visualTextScale),
                    color = RaftColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (editing) {
                // `chipRemove`: a 14dp hint X; the slot keeps the 44dp remove target.
                Box(
                    modifier = Modifier.size(width = 44.dp, height = 28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    RaftLucideIcon(
                        RaftLucideIconName.X,
                        color = raftArgbHex(RaftColor.ForegroundHint),
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        if (editing) {
            // Raw clickables on purpose: while editing, the two targets are 44dp tall over the
            // 28dp chip, so raftClickable's press would spill past the chip's edges.
            Row(modifier = Modifier.matchParentSize()) {
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(onClick = onSelect) // raw-click: the 44dp edit target, see above
                            .semantics { contentDescription = query },
                )
                Box(
                    modifier =
                        Modifier
                            .width(44.dp)
                            .fillMaxHeight()
                            .clickable(onClick = onRemove) // raw-click: the 44dp edit target, see above
                            .semantics { contentDescription = removeLabel.orEmpty() },
                )
            }
        } else {
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .raftClickable(onClick = onSelect, shape = RoundedCornerShape(RaftAppRadius.Chip))
                        .semantics { contentDescription = query },
            )
        }
    }
}

// raft-ui SearchHeader (PanelHeader): a 56dp canvas row, `pl-4` and `gap-2` on a phone; an
// ghost icon-lg Back (RaftBackButton), the 27dp brand PanelHeaderIcon with the search glyph, then the
// search field (a filled control, so it ends on the 16dp content edge).
@Composable
private fun SharedHomeSearchToolbar(
    editor: SharedHomeSearchEditorOwner,
    focusRequester: FocusRequester,
    fieldModifier: Modifier = Modifier,
    query: String,
    loading: Boolean,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    onClose: (() -> Unit)?,
    showCloseButton: Boolean = true,
    interactive: Boolean = true,
    autoFocus: Boolean = false,
    tabHead: Boolean = false,
    topSafeAreaPadding: Dp = 0.dp,
    placeholder: String
) {
    if (tabHead) {
        // Nav spec §2/§7.1: the Search tab root wears the same tab head as the other roots, a
        // 56dp frame bar (Source Yellow, the dark frame in dark) with a 1dp seam; the field
        // takes the title's place, 16dp from both edges.
        RaftTopPageHeaderFrame(
            backgroundColor = RaftColor.Frame,
            topSafeArea = topSafeAreaPadding,
            contentStartPadding = SearchHeadHorizontalPadding,
            contentEndPadding = SearchHeadHorizontalPadding,
        ) {
            SharedHomeSearchInput(
                query = editor.value,
                loading = loading,
                interactive = interactive,
                placeholder = placeholder,
                autoFocus = autoFocus,
                focusRequester = focusRequester,
                onQueryChange = { next -> editor.acceptEditorValue(next, onQueryChange) },
                onSubmit = onSubmit,
                onClear = { editor.clear(onClear) },
                raftField = true,
                modifier = Modifier.weight(1f).then(fieldModifier)
            )
        }
        return
    }
    // iPhone Duo (raft-design #859): beside the system's side column Back is the column's.
    val backInColumn = onClose != null && raftSideColumnControls(back = raftSideColumnBackAction(onClose))
    @Suppress("NAME_SHADOWING")
    val onClose = onClose.takeUnless { backInColumn || !showCloseButton }
    SharedHomeSearchControlsRow(
        topSafeAreaPadding = topSafeAreaPadding,
        // Back sits at the top-bar edge inset, its arrow 14dp in, like every page header.
        startPadding = if (onClose != null) RaftBackButtonDefaults.EdgeInset else SearchHeadHorizontalPadding
    ) {
        if (onClose != null) {
            RaftBackButton(onClick = onClose, label = stringResource(Res.string.common_back))
        }
        // No location tile: the search field already says where you are (web search page,
        // raft-design: a page whose head is a search field carries no location icon).
        SharedHomeSearchInput(
            query = editor.value,
            loading = loading,
            interactive = interactive,
            placeholder = placeholder,
            autoFocus = autoFocus,
            focusRequester = focusRequester,
            onQueryChange = { next -> editor.acceptEditorValue(next, onQueryChange) },
            onSubmit = onSubmit,
            onClear = { editor.clear(onClear) },
            raftField = true,
            modifier = Modifier.weight(1f).then(fieldModifier)
        )
    }
}

/** "#name" for a channel scope, the bare name for a DM. */
private fun SearchChannelFilterItem.searchScopeLabel(): String =
    if (icon == RaftLucideIconName.Hash) "#$label" else label

/**
 * web SearchEverywhereRow (Nav spec §7.2): a conversation's own search leads its results with
 * "Search everywhere" ("Search everywhere for “…”" once there are words), a raft-ui
 * SearchEntityResult that moves the query to the Search tab; this page stays scoped.
 */
@Composable
private fun SharedHomeSearchEverywhereRow(query: String, onClick: () -> Unit) {
    val trimmed = query.trim()
    // web SearchResultsList `pt-2`.
    Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        RaftSearchResultRow(
            onClick = onClick,
            modifier = Modifier.testTag("search-everywhere"),
            leading = { RaftSearchResultIcon(RaftGlyph.Search) },
            description = { RaftSearchResultDescription(stringResource(Res.string.search_scope_everywhere_hint)) },
        ) {
            RaftSearchResultTitle(
                if (trimmed.isEmpty()) {
                    stringResource(Res.string.search_scope_everywhere)
                } else {
                    Res.string.search_scope_everywhere_for.format(listOf(trimmed)).localized()
                }
            )
        }
    }
}

@Composable
private fun SharedHomeSearchControlsRow(
    topSafeAreaPadding: Dp,
    startPadding: Dp = SearchHeadHorizontalPadding,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(RaftColor.Canvas)
                .padding(top = topSafeAreaPadding)
                // A head: 56 with a finger, 44 under a mouse or trackpad (sizing v12).
                .height(RaftTheme.controls.headHeight)
                .raftSafeAreaHorizontalPadding()
                .padding(start = startPadding, end = SearchHeadHorizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

// raft-ui SearchEmpty (EmptyState): search icon, headline title, callout description.
@Composable
private fun SharedHomeSearchEmptyState(
    title: String,
    detail: String
) {
    RaftSearchEmptyState(
        title = title,
        detail = detail,
        modifier = Modifier.heightIn(min = 300.dp)
    )
}

// raft-ui SearchError: the EmptyState with the TriangleAlert icon, its copy and the retry
// action under it.
@Composable
private fun SharedHomeSearchErrorState(
    error: RaftHomeInlineError,
    showAction: Boolean,
    onAction: () -> Unit
) {
    val copy = error.toVisibleCopy()
    RaftSearchEmptyState(
        title = copy.title,
        detail = copy.detail,
        iconName = RaftLucideIconName.TriangleAlert,
        actions =
            if (showAction) {
                {
                    RaftButton(
                        text = copy.actionLabel,
                        onClick = onAction,
                        variant = RaftButtonVariant.Default,
                        size = RaftButtonSize.Lg,
                        leadingIcon = { RaftSlotIcon(RaftLucideIconName.RefreshCw) }
                    )
                }
            } else {
                null
            }
    )
}

@Composable
private fun SharedHomeSearchResultsCount(
    totalResults: Int,
    searching: Boolean
) {
    RaftSearchSummary(
        text =
            if (searching) {
                stringResource(Res.string.search_state_searching)
            } else {
                stringResource(Res.string.search_results_count, totalResults)
            },
        typography = RaftTheme.typography
    )
}

private fun groupHomeSearchResults(
    items: List<InboxItem>,
    threadDiscussionLabel: String
): List<HomeSearchResultGroup> {
    val groups = mutableListOf<HomeSearchResultGroup>()
    val threadGroups = linkedMapOf<String, MutableList<InboxItem>>()
    items.forEach { item ->
        val threadKey = item.threadChannelId?.value?.takeIf { item.kind == InboxItemKind.Thread }
        if (threadKey == null) {
            groups += HomeSearchResultGroup.Message(item)
            return@forEach
        }
        val existing = threadGroups.getOrPut(threadKey) {
            mutableListOf<InboxItem>().also {
                groups += HomeSearchResultGroup.Thread(
                    threadKey,
                    homeSearchThreadTitle(item.parentMessagePreview) ?: threadDiscussionLabel,
                    it
                )
            }
        }
        existing += item
    }
    return groups
}

private val SearchSort.searchKey: String
    get() =
        when (this) {
            SearchSort.Relevance -> "relevance"
            SearchSort.Recent -> "recent"
        }
