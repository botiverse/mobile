package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.InboxItem
import build.raft.app.data.SearchScope
import build.raft.app.data.SearchFrequentEntityRank
import build.raft.app.data.SearchPersonalizationEntityKey
import build.raft.app.data.SearchPersonalizationEntityKind
import build.raft.app.data.SearchSenderFilterItem
import build.raft.app.data.SearchSort
import build.raft.app.data.SearchTimeRange
import build.raft.app.data.SettingsServerSidebarOrderSnapshot
import build.raft.app.data.SettingsServerSidebarPinnedItem
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedHomeSearchPanelState
import build.raft.app.data.SharedHomeState
import build.raft.app.data.SharedHomeTopAction
import build.raft.app.data.toHomeChannelRow
import build.raft.app.data.toHomeDirectMessageRow
import build.raft.app.data.typedPinnedOrLegacy
import build.raft.app.model.AvatarReference
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.MessageId
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.TaskStatus
import build.raft.app.model.ThreadId
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import build.raft.app.compat.pager.LocalActivity
import androidx.compose.ui.unit.dp
import build.raft.app.compat.pager.Pager
import kotlinx.coroutines.delay

private val homeSearchVisualMember: MemberProfile
    get() {
        val fixture = settingsVisualFixtureValues
        return MemberProfile(
            id = MemberId(fixture.ownerMemberId),
            displayName = fixture.ownerDisplayName,
            handle = fixture.ownerHandleBare,
            kind = MemberKind.Human,
            presence = Presence.Active
        )
    }

private val homeSearchVisualChannels = listOf(
    ConversationSummary(
        threadId = ThreadId("thread_design"),
        channelId = ChannelId("ch_design"),
        kind = ConversationKind.Channel,
        name = "design",
        subtitle = "Product and UI decisions",
        unreadCount = 3,
        unreadCountKnown = true,
        lastMessagePreview = "Search parity now covers sender filters and results.",
        lastMessageAt = "10:38",
        lastMessageSenderName = "Product UX Designer",
        memberCount = 18
    ).toHomeChannelRow(),
    ConversationSummary(
        threadId = ThreadId("thread_android"),
        channelId = ChannelId("ch_android"),
        kind = ConversationKind.Channel,
        name = "android-artifacts",
        subtitle = "Builds and screenshots",
        lastMessagePreview = "Uploaded Android visual report with Search and Saved cases.",
        lastMessageAt = "09:58",
        lastMessageSenderName = "Android Developer",
        memberCount = 8
    ).toHomeChannelRow()
)

private val homeSearchVisualDirectMessages = listOf(
    ConversationSummary(
        threadId = ThreadId("dm_product"),
        channelId = ChannelId("dm_product"),
        kind = ConversationKind.DirectMessage,
        name = "Product UX Designer",
        subtitle = "Product review owner",
        lastMessagePreview = "Search visual case needs visual review.",
        lastMessageAt = "09:42",
        lastMessageSenderName = "Product UX Designer",
        presenceMemberId = MemberId("agent_product")
    ).toHomeDirectMessageRow()
)

private val homeSearchVisualHomeState = SharedHomeState(
    content =
        SharedHomeContentState.Content(
            snapshot =
                HomeSnapshot(
                    server = homeSearchVisualServer,
                    currentMember = homeSearchVisualMember,
                    channels = homeSearchVisualChannels,
                    directMessages = homeSearchVisualDirectMessages,
                    unreadTotal = 7
                ),
            session = homeSearchVisualSession
        ),
    selectedTopAction = SharedHomeTopAction.Search
)

private val homeSearchPinVisualHomeState =
    homeSearchVisualHomeState.copy(
        content =
            (homeSearchVisualHomeState.content as SharedHomeContentState.Content).let { content ->
                content.copy(
                    snapshot =
                        content.snapshot.copy(
                            channels =
                                content.snapshot.channels +
                                    listOf(
                                        ConversationSummary(
                                            threadId = ThreadId("thread_android_pinned"),
                                            channelId = ChannelId("ch_android_pinned"),
                                            kind = ConversationKind.Channel,
                                            name = "android-pinned",
                                            subtitle = "Pinned release coordination",
                                            memberCount = 12
                                        ).toHomeChannelRow(),
                                        ConversationSummary(
                                            threadId = ThreadId("thread_android_archived"),
                                            channelId = ChannelId("ch_android_archived"),
                                            kind = ConversationKind.Channel,
                                            name = "android-archive",
                                            subtitle = "Archived build history",
                                            archived = true,
                                            memberCount = 5
                                        ).toHomeChannelRow()
                                    ),
                            directMessages =
                                content.snapshot.directMessages +
                                    ConversationSummary(
                                        threadId = ThreadId("dm_android_reviewer"),
                                        channelId = ChannelId("dm_android_reviewer"),
                                        kind = ConversationKind.DirectMessage,
                                        name = "Android Reviewer",
                                        subtitle = "Mobile release review",
                                        presenceMemberId = MemberId("user_android_reviewer")
                                    ).toHomeDirectMessageRow(),
                            sidebarOrder =
                                SettingsServerSidebarOrderSnapshot(
                                    pinned =
                                        listOf(
                                            SettingsServerSidebarPinnedItem(
                                                kind = "channel",
                                                id = "thread_android_pinned"
                                            )
                                        )
                                )
                        )
                )
            }
    )

// Canonical create-channel roster (task #370): mirrors the react render host
// priming in packages/web/visual-testing/VisualTestingCases.tsx
// (primeCreateChannelStores) — agents Cindy (selected) + Visual QA, human
// Designer (selected); the current user (artin) is excluded from the picker.
// Identity/avatar values come from fixtureData.json via VisualFixtureData.

private val homeSearchVisualSearchState = SharedHomeSearchPanelState(
    query = "android",
    sort = SearchSort.Recent,
    timeRange = SearchTimeRange.Last7Days,
    selectedScopes = listOf(SearchScope.Mentioned, SearchScope.Humans),
    myMessagesOnly = false,
    selectedSenderId = "user_artin",
    // Visual fixture is static + non-interactive (`onIntent = {}` in HomeSearchVisualContent),
    // so the store never actually runs a search. Mark `searched = true` explicitly so the
    // panel's "items/empty/results-count" branch falls into the "has results" path instead
    // of the "Search everything" empty state. See #首页专修:491a6bbd / #74.
    searched = true,
    senderFilters =
        listOf(
            SearchSenderFilterItem(
                id = "user_artin",
                label = "Artin",
                handle = "artin",
                kind = MemberKind.Human,
                sourceServerId = ServerId("server_visual"),
                isSelf = true,
                avatarWireValue = AvatarReference.Random("artin").wireValue
            ),
            SearchSenderFilterItem(
                id = "agent_android",
                label = "Android Developer",
                handle = "Android-Developer",
                kind = MemberKind.Agent,
                sourceServerId = ServerId("server_visual"),
                avatarWireValue = AvatarReference.Random("android-developer").wireValue
            ),
            SearchSenderFilterItem(
                id = "human_pin_fixture",
                label = "Pin Human",
                handle = "pin-human",
                kind = MemberKind.Human,
                sourceServerId = ServerId("server_visual"),
                avatarWireValue = AvatarReference.Random("pin-human").wireValue
            )
        ),
    selectedChannelId = "ch_design",
    items =
        listOf(
            InboxItem(
                key = "search-thread-1",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("thread_msg_agent_reply"),
                channelName = "thread",
                lastMessageId = MessageId("msg_search_thread_1"),
                lastMessagePreview = "Android search parity now covers sender filters, time ranges, and entity results.",
                lastMessageAt = "10:38",
                lastMessageSenderName = "Android Developer",
                threadChannelId = ChannelId("thread_msg_agent_reply"),
                parentChannelId = ChannelId("ch_design"),
                parentChannelName = "design",
                parentChannelKind = ConversationKind.Channel,
                parentMessageId = MessageId("msg_agent_reply"),
                parentMessagePreview = "收到，统一 MessageItem / ThreadMessageItem 的正文、回复数和任务状态。",
                latestActivityMessageId = MessageId("msg_search_thread_1"),
                latestActivityPreview = "Android search parity now covers sender filters, time ranges, and entity results.",
                latestActivityAt = "10:38",
                latestActivitySenderName = "Android Developer",
                replyCount = 5,
                participantIds = listOf(MemberId("user_artin"), MemberId("agent_android")),
                taskNumber = 154,
                taskStatus = TaskStatus.InReview,
                taskClaimedByName = "KMP-专家"
            ),
            InboxItem(
                key = "search-thread-2",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("thread_msg_agent_reply"),
                channelName = "thread",
                lastMessageId = MessageId("msg_search_thread_2"),
                lastMessagePreview = "Search needs to match Android and React behavior before visual review.",
                lastMessageAt = "10:31",
                lastMessageSenderName = "artin",
                threadChannelId = ChannelId("thread_msg_agent_reply"),
                parentChannelId = ChannelId("ch_design"),
                parentChannelName = "design",
                parentChannelKind = ConversationKind.Channel,
                parentMessageId = MessageId("msg_agent_reply"),
                parentMessagePreview = "收到，统一 MessageItem / ThreadMessageItem 的正文、回复数和任务状态。",
                latestActivityMessageId = MessageId("msg_search_thread_2"),
                latestActivityPreview = "Search needs to match Android and React behavior before visual review.",
                latestActivityAt = "10:31",
                latestActivitySenderName = "artin",
                replyCount = 5,
                participantIds = listOf(MemberId("user_artin"), MemberId("agent_android")),
                taskNumber = 154,
                taskStatus = TaskStatus.InReview,
                taskClaimedByName = "KMP-专家"
            ),
            InboxItem(
                key = "search-channel-1",
                kind = InboxItemKind.Channel,
                channelId = ChannelId("ch_android"),
                channelName = "android-artifacts",
                lastMessageId = MessageId("msg_search_channel_1"),
                lastMessagePreview = "Uploaded Android visual report with Search and Saved cases.",
                lastMessageAt = "09:58",
                lastMessageSenderName = "Android Developer",
                latestActivityMessageId = MessageId("msg_search_channel_1"),
                latestActivityPreview = "Uploaded Android visual report with Search and Saved cases.",
                latestActivityAt = "09:58",
                latestActivitySenderName = "Android Developer"
            )
        ),
    totalCount = 3,
    hasMore = true,
    nextOffset = 3
)

private val homeSearchHomeVisualSearchState =
    SharedHomeSearchPanelState(
        personalizationLoaded = true,
        history =
            listOf(
                "sig",
                "mobile",
                "pve",
                "runtime",
                "hands",
                "git-",
                "scopedb",
                "cpa",
                "git-h",
                "all",
            ),
        senderFilters = homeSearchVisualSearchState.senderFilters,
        frequentEntityRanks =
            mapOf(
                SearchPersonalizationEntityKey(SearchPersonalizationEntityKind.Channel, "ch_design") to
                    SearchFrequentEntityRank(score = 4.0, lastOpenedAtMillis = 4_000L),
                SearchPersonalizationEntityKey(SearchPersonalizationEntityKind.DirectMessage, "dm_product") to
                    SearchFrequentEntityRank(score = 3.0, lastOpenedAtMillis = 3_000L),
                SearchPersonalizationEntityKey(SearchPersonalizationEntityKind.Agent, "agent_android") to
                    SearchFrequentEntityRank(score = 2.0, lastOpenedAtMillis = 2_000L),
            ),
    )

// Canonical saved-messages entries (task #370): ported verbatim from
// packages/visual-testing/shared/savedMessagesFixture.json in the slock repo
// (total 5 → "5 saved" header, 3 rendered entries, hasMore → Load More).
// Relative time labels match what react renders for the fixture ISO stamps.

@Composable
internal fun HomeSearchVisualContent(initialOpenFilterId: String? = null) {
    val pager = LocalActivity.current.getPager() as Pager
    val openFilterId =
        initialOpenFilterId ?: pager.pageData.params.optString("openFilterId").takeIf { it.isNotBlank() }
    // Fill the captured viewport (matched to the react fixture, which renders the search
    // panel full-frame) instead of a 342x620 sub-region — the smaller box left the crop's
    // margin painted with the window background (cream), which the react↔android diff
    // flagged as a spurious "empty beige area" drift. White backing keeps any residual gap
    // aligned with the react baseline. (visual case components.home.search.*-dropdown)
    Box(modifier = Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
        SharedHomeSearchPanel(
            state = homeSearchVisualSearchState,
            homeState = homeSearchVisualHomeState,
            onIntent = {},
            onClose = {},
            interactiveSearchField = false,
            includeTopSafeArea = false,
            showSenderFilter = true,
            initialOpenFilterId = openFilterId,
            onOpenItem = {},
            onOpenEntity = {},
            bottomContentPadding = 12.dp,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
internal fun HomeSearchHomeVisualContent(
    editing: Boolean = false,
    visualTextScale: Float = 1f,
) {
    Box(modifier = Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
        SharedHomeSearchPanel(
            state = homeSearchHomeVisualSearchState,
            homeState = homeSearchVisualHomeState,
            onIntent = {},
            onClose = {},
            interactiveSearchField = false,
            includeTopSafeArea = false,
            showSenderFilter = true,
            initialHistoryEditing = editing,
            searchHomeVisualTextScale = visualTextScale,
            onOpenItem = {},
            onOpenEntity = {},
            bottomContentPadding = 12.dp,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
internal fun HomeSearchPinVisualContent(query: String = "android") {
    var openedEntityCount by remember { mutableStateOf(0) }
    var togglePinCount by remember { mutableStateOf(0) }
    LaunchedEffect(togglePinCount) {
        if (togglePinCount > 0) {
            delay(HomeSearchPinVisualProbeDurationMs)
            togglePinCount = 0
        }
    }
    Box(modifier = Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
        SharedHomeSearchPanel(
            state = homeSearchVisualSearchState.copy(query = query),
            homeState = homeSearchPinVisualHomeState,
            onIntent = {},
            onClose = {},
            interactiveSearchField = false,
            includeTopSafeArea = false,
            showSenderFilter = true,
            onOpenItem = {},
            onOpenEntity = { openedEntityCount += 1 },
            pinnedItems =
                (homeSearchPinVisualHomeState.content as SharedHomeContentState.Content)
                    .snapshot
                    .sidebarOrder
                    ?.typedPinnedOrLegacy()
                    .orEmpty(),
            onTogglePin = { _ -> togglePinCount += 1 },
            bottomContentPadding = 12.dp,
            modifier = Modifier.fillMaxSize()
        )
        if (openedEntityCount > 0) {
            Box(modifier = Modifier.size(24.dp).background(RaftColor.BrandYellow))
        }
        if (togglePinCount > 0) {
            Box(
                modifier =
                    Modifier
                        .offset(x = 24.dp)
                        .size(24.dp)
                        .background(
                            if (togglePinCount == 1) {
                                Color(0xFF00E5FF)
                            } else {
                                Color(0xFFFF0000)
                            }
                        )
            )
        }
    }
}

private const val HomeSearchPinVisualProbeDurationMs = 900L

@Composable
internal fun HomeSearchErrorVisualContent() {
    Box(modifier = Modifier.width(342.dp).height(620.dp)) {
        SharedHomeSearchPanel(
            state =
                homeSearchVisualSearchState.copy(
                    query = "卡片",
                    sort = SearchSort.Relevance,
                    timeRange = SearchTimeRange.Any,
                    myMessagesOnly = false,
                    selectedSenderId = null,
                    selectedChannelId = null,
                    items = emptyList(),
                    totalCount = 0,
                    searched = true,
                    loading = false,
                    refreshing = false,
                    querySearchPending = false,
                    empty = false,
                    hasMore = false,
                    errorCode = "server_unavailable",
                    noPermissionCode = null
                ),
            homeState = homeSearchVisualHomeState,
            onIntent = {},
            onClose = {},
            interactiveSearchField = false,
            includeTopSafeArea = false,
            showSenderFilter = true,
            onOpenItem = {},
            onOpenEntity = {},
            bottomContentPadding = 12.dp,
            modifier = Modifier.fillMaxSize()
        )
    }
}
