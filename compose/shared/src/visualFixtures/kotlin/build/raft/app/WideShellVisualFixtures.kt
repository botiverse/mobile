package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.base.BasePager
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.Page
import build.raft.app.compat.pager.Pager
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.InboxFilter
import build.raft.app.data.RaftListPaneKind
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SettingsAppearanceSnapshot
import build.raft.app.data.TasksContent
import build.raft.app.data.TasksPresentationState
import build.raft.app.data.TasksUiState
import build.raft.app.data.TasksViewMode
import build.raft.app.data.parseSharedServerTasksPayload
import build.raft.app.data.summaryCounts
import build.raft.app.model.MemberKind
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

/**
 * The wide layout's first parity cases (#proj-mobile:#195, headers-and-visual.md): the app shell (top
 * bar, rail, list and detail columns) as a tablet draws it, captured on the Android wide lane
 * (1280x800dp, `wm size 2560x1600`, `wm density 320`, touch). pageData `scene`:
 *  - `activity-task`: Activity (head, toolbar, rows) beside an open task conversation;
 *  - `channel`: the Chat sidebar beside a channel conversation and its composer;
 *  - `channel-thread`: the channel with a thread column beside it;
 *  - `settings`: Settings groups with Appearance open;
 *  - `tasks-task`: the Tasks list with a task open in its column.
 * The production composables with fixture state, no session or network.
 */
@Page(WIDE_SHELL_VISUAL_PAGE, supportInLocal = true)
internal class WideShellVisualFixture : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { WideShellVisualContent() }
    }
}

internal const val WIDE_SHELL_VISUAL_PAGE = "compose_wide_shell_visual"

@Composable
private fun WideShellVisualContent() {
    val pager = LocalActivity.current.getPager() as Pager
    val scene = remember(pager.pageData.params.toString()) { pager.pageData.params.optString("scene").trim() }
    val grid = remember { RaftPaneGridStore() }
    CompositionLocalProvider(
        LocalRaftAppShellTopBar provides remember { RaftAppShellTopBarState() },
        LocalRaftPaneGridStore provides grid,
        LocalThreadHeaderMenuEventBus provides WideShellQuietEventBus,
        LocalActivityAvatarSource provides visualFixtureAvatarSource,
    ) {
        RaftDesignSystemTheme {
            when (scene) {
                "activity-task" -> WideShellFrame("activity") { WideActivityTask() }
                "channel" -> WideShellFrame("home") { WideChannel(withThread = false) }
                "channel-thread" -> WideShellFrame("home") { WideChannel(withThread = true) }
                "settings" -> WideShellFrame("settings") { WideSettingsAppearance() }
                "tasks-task" -> WideShellFrame("tasks") { WideTasksTask() }
                else -> RaftText("Unknown wide scene: $scene")
            }
        }
    }
}

/** The top bar over the rail and the columns; [selectedTab] is the rail's selected item. */
@Composable
private fun WideShellFrame(selectedTab: String, content: @Composable () -> Unit) {
    val fixture = VisualFixtureData.HomeChannelList
    val servers = listOf(RaftServerSwitcherItem(id = VisualFixtureData.Server.ID, name = fixture.SERVER_NAME, slug = VisualFixtureData.Server.SLUG))
    Column(Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
        RaftAppShellTopBar(
            currentServer = servers.first(),
            servers = servers,
            serverMenuOpen = false,
            onWorkspaceClick = {},
            onServerPick = {},
            searchLabel = "Search",
            onSearchClick = {},
            windowWidth = 1280.dp,
        )
        Row(Modifier.weight(1f)) {
            RaftNavigationRailTabBar(
                items =
                    listOf(
                        RaftBottomTabItemPayload("home", "Home", RaftLucideIconName.House, selected = selectedTab == "home"),
                        RaftBottomTabItemPayload("activity", "Activity", RaftLucideIconName.Activity, selected = selectedTab == "activity"),
                        RaftBottomTabItemPayload("tasks", "Tasks", RaftLucideIconName.SquareCheckBig, selected = selectedTab == "tasks"),
                        RaftBottomTabItemPayload("members", "Members", RaftLucideIconName.Users),
                    ),
                onSelect = {},
                modifier = Modifier.fillMaxHeight(),
                bottomItems = listOf(RaftBottomTabItemPayload("settings", "Settings", RaftLucideIconName.Settings, selected = selectedTab == "settings")),
            )
            Box(Modifier.weight(1f).fillMaxHeight()) { content() }
        }
    }
}

// ---- Activity + task ------------------------------------------------------------------------

// The rows, counts and clock of the web side of this case: its MainLayout primes Activity with the shared
// activityResultsFixture.json (slock packages/visual-testing/shared; the thread in #design, the
// #android-artifacts channel, the DM with Cindy; 3 conversations, 3 unread) and renders at
// fixtureData.locale's instant, the same rows and clock the phone's Activity fixture ports
// ([homeActivityVisualSnapshot], [homeVisualFixtureTimeContext]). The task open beside it is none of the rows, so no row is selected.
private val wideActivity = homeActivityVisualSnapshot

@Composable
private fun WideActivityTask() {
    RaftAdaptiveListDetailSplit(
        kind = RaftListPaneKind.List,
        widths = remember { RaftListPaneWidths(store = null) },
        dualPane = true,
        active = true,
        hasSelection = true,
        detailBottomInset = 0.dp,
        listPane = { WideActivityList() },
        detailPane = { modifier -> Box(modifier) { WideTaskPage() } },
    )
}

@Composable
private fun WideActivityList() {
    val context =
        ActivityConversationListContext(
            channelSymbolsByChannelId = mapOf("channel-design" to "#", "channel-android" to "#"),
        )
    Column(Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
        SharedActivityHeader(
            totalCount = wideActivity.totalCount,
            totalUnreadCount = wideActivity.totalUnreadCount,
            onClose = null,
            topSafeAreaPadding = 0.dp,
            refreshing = false,
            mainTab = true,
        )
        SharedActivityFilterBar(
            selectedFilter = InboxFilter.All,
            totalUnreadCount = wideActivity.totalUnreadCount,
            onFilterSelected = {},
            onMarkAllRead = {},
        )
        Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            activityConversationListEntries(wideActivity.items, homeVisualFixtureTimeContext).forEach { entry ->
                if (entry is ActivityConversationListEntry.DayHeading) {
                    ActivityDayHeading(entry.label, first = entry.first, background = RaftColor.SurfaceWhite)
                    return@forEach
                }
                val item = (entry as ActivityConversationListEntry.Row).item
                ActivityConversationListRow(
                    item = item,
                    model = item.activityConversationRowModel(context, InboxFilter.All, visualFixtureSenderProfiles),
                    divider = entry.divider,
                    selected = false,
                    timeContext = homeVisualFixtureTimeContext,
                    agentStatusResolver = EmptyRaftAgentStatusResolver,
                    canMarkDone = true,
                    openGestureKey = item.key,
                    captureMenuAnchor = false,
                    onOpen = {},
                    onOpenMenu = {},
                    modifier = Modifier.fillMaxWidth(),
                    // By touch in the wide layout, every row shows its Mark as Done at rest (web InboxRowDone).
                    onRowDone = {},
                )
            }
        }
    }
}

// ---- Task conversation (Activity's detail and the Tasks column) ------------------------------

private val wide = VisualFixtureData.WideShell

// The `GET /tasks/server` body the web side loads (slock tasksFixture.json), as TasksVisualFixtures reads it.
private val wideTasks: List<TaskSummary> =
    checkNotNull(parseSharedServerTasksPayload(VisualFixtureWire.TASKS)) { "tasksFixture.json does not parse" }.tasks

private val wideOpenTask = checkNotNull(wideTasks.firstOrNull { it.id.value == wide.TASK_ID }) { "wideShell.taskId is not a task" }

private val wideTaskRef = "#${VisualFixtureData.Channels.Design.NAME}:#${wide.TASK_NUMBER}"

@Composable
private fun WideTaskPage() {
    Column(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) {
        ThreadPageHeader(
            payload =
                RaftThreadScreenPayload(
                    instanceId = "wide-task-conversation",
                    surfaceKind = RaftThreadSurfaceKind.Channel,
                    channelKind = RaftTaskConversationChannelKind,
                    status = "content",
                    showModeTabs = false,
                    showSettingsAction = false,
                    showMembersAction = false,
                ),
            headerDisplay =
                ThreadHeaderDisplayModel(
                    title = "$wideTaskRef ${wideOpenTask.title}",
                    subtitle = null,
                    surfaceKind = RaftThreadSurfaceKind.Channel,
                    channelKind = RaftTaskConversationChannelKind,
                ),
            applyTopSafeArea = false,
            showBack = false,
            onBack = {},
            onOpenInChannel = {},
            onOpenMemberProfile = {},
            onOpenSearch = {},
            onShowMembers = {},
            onShowSettings = {},
            taskConversationChrome =
                TaskConversationHeaderChrome(
                    ref = wideTaskRef,
                    taskNumber = wide.TASK_NUMBER,
                    status = TaskStatus.InProgress,
                    assigneeName = VisualFixtureData.Agents.Cindy.DISPLAY_NAME,
                    assigneeKind = MemberKind.Agent,
                    summarized = false,
                    followed = true,
                    onToggleHeadFloat = {},
                    onViewInChannel = {},
                    onToggleFollow = {},
                ),
        )
        WideMessages(Modifier.weight(1f).fillMaxWidth(), wideTaskMessages)
        WideComposer("Message this task", asTask = false)
    }
}

// ---- Channel (and its thread) ---------------------------------------------------------------

/** A `wideShell` message row (fixtureData.json), the web's wideShell.ts maps the same fields. */
private fun wideRow(row: Map<String, Any?>): RaftThreadMessageItem =
    RaftThreadMessageItem(
        id = row["id"] as String,
        authorId = row["authorId"] as String,
        authorName = row["authorName"] as String,
        authorDescription = row["authorDescription"] as String,
        authorKind = if (row["authorKind"] == "agent") "agent" else "human",
        isOwn = row["own"] as Boolean,
        avatarLabel = (row["authorName"] as String).take(1),
        seq = (row["seq"] as Int).toLong(),
        timestampLabel = row["timeLabel"] as String,
        body = row["body"] as String,
    )

private val wideChannelMessages = wide.MESSAGES.map(::wideRow)

private val wideThreadMessages =
    wideChannelMessages.filter { it.id == wide.THREAD_ROOT_ID } + wide.THREAD_MESSAGES.map(::wideRow)

private val wideTaskMessages = wide.TASK_MESSAGES.map(::wideRow)

/** The channel's rows; with its thread open, the root carries the reply count, as on the web. */
private fun wideChannelRows(withThread: Boolean): List<RaftThreadMessageItem> =
    wideChannelMessages.map { row ->
        if (!withThread || row.id != wide.THREAD_ROOT_ID) return@map row
        row.copy(threadChannelId = wide.THREAD_CHANNEL_ID, replyCount = wide.THREAD_MESSAGES.size)
    }

@Composable
private fun WideChannel(withThread: Boolean) {
    val fixture = VisualFixtureData.HomeChannelList
    val servers = listOf(RaftServerSwitcherItem(id = VisualFixtureData.Server.ID, name = fixture.SERVER_NAME, slug = VisualFixtureData.Server.SLUG))
    val channels = remember {
        // The open channel is read, as opening it makes it.
        fixture.CHANNELS.map(::homeChannelListChannel).map { if (it.id == wide.CHANNEL_ID) it.copy(unreadCount = 0) else it }
    }
    val directMessages = remember { fixture.DIRECT_MESSAGES.map(::homeChannelListDirectMessage) }
    val channelName = channels.first { it.id == wide.CHANNEL_ID }.name
    RaftAdaptiveListDetailSplit(
        kind = RaftListPaneKind.Sidebar,
        widths = remember { RaftListPaneWidths(store = null) },
        dualPane = true,
        active = true,
        hasSelection = true,
        detailBottomInset = 0.dp,
        listPane = {
            RaftStructuralHomeShell(
                currentServerName = fixture.SERVER_NAME,
                currentServerId = VisualFixtureData.Server.ID,
                servers = servers,
                channels = channels,
                directMessages = directMessages,
                selectedChannelId = wide.CHANNEL_ID,
                pinnedChannelIds = fixture.PINNED_CHANNEL_IDS,
                savedCount = fixture.SAVED_TOTAL,
                contentRefreshEnabled = false,
                selectedTabKey = "home",
                showBottomBar = false,
                serverSwitcherInTopBar = true,
                searchAndActivityOnShellNav = true,
                outerPadding = 0.dp,
                applyTopSafeArea = false,
            )
        },
        detailPane = { modifier ->
            Row(modifier) {
                WideConversation(Modifier.weight(1f).fillMaxHeight(), "#$channelName", wideChannelRows(withThread), "Message #$channelName")
                if (withThread) {
                    // A side pane's page: its head ends in Close and has no Back (as the shell's thread column).
                    CompositionLocalProvider(LocalRaftSidePanePage provides remember { RaftSidePanePage(depth = 1) {} }) {
                        WideConversation(Modifier.width(400.dp).fillMaxHeight(), "Thread", wideThreadMessages, "Message thread", sourceThreadId = wide.THREAD_CHANNEL_ID)
                    }
                }
            }
        },
    )
}

@Composable
private fun WideConversation(
    modifier: Modifier,
    title: String,
    messages: List<RaftThreadMessageItem>,
    placeholder: String,
    sourceThreadId: String? = null,
) {
    val thread = sourceThreadId != null
    val payload =
        remember(title, sourceThreadId) {
            RaftThreadScreenPayload(
                instanceId = "wide-${sourceThreadId ?: wide.CHANNEL_ID}",
                sourceThreadId = sourceThreadId ?: wide.CHANNEL_ID,
                title = title,
                subtitle = if (thread) "#${VisualFixtureData.HomeChannelList.Channels1.NAME}" else wide.CHANNEL_DESCRIPTION,
                status = "content",
                surfaceKind = if (thread) RaftThreadSurfaceKind.ReplyThread else RaftThreadSurfaceKind.Channel,
                selectedTab = "chat",
                showSettingsAction = !thread,
                showMembersAction = !thread,
                membersCount = 6,
                showOpenInChannelAction = false,
            )
        }
    Column(modifier.background(RaftColor.SurfaceWhite)) {
        ThreadPageHeader(
            payload = payload,
            applyTopSafeArea = false,
            // The detail pane of the split, beside the sidebar: no Back (as ThreadPageContent).
            showBack = false,
            onBack = {},
            onOpenInChannel = {},
            onOpenMemberProfile = {},
            onOpenSearch = {},
            onShowMembers = {},
            onShowSettings = {},
        )
        // A short channel stands on its composer, under the date it starts with; a thread starts at the top.
        WideMessages(Modifier.weight(1f).fillMaxWidth(), messages, dateLabel = "Today".takeUnless { thread })
        WideComposer(placeholder, asTask = !thread)
    }
}

@Composable
private fun WideMessages(modifier: Modifier, messages: List<RaftThreadMessageItem>, dateLabel: String? = null) {
    Column(
        modifier.background(RaftColor.Canvas).padding(top = RaftAppSpacing.Sm),
        verticalArrangement = if (dateLabel != null) Arrangement.Bottom else Arrangement.Top,
    ) {
        dateLabel?.let { ThreadDateDivider(it) }
        messages.forEach { message ->
            ThreadMessageCard(
                message = message,
                messageBodyTextStyle = raftMessageBodyTextStyle("md"),
                isMarkdownTagValid = { false },
                timestampLabel = message.timestampLabel,
                onShowActions = {},
                onOpenMember = {},
                onOpenThread = {},
                onOpenTask = {},
                onOpenTaskStatusMenu = {},
                onToggleReaction = {},
                onOpenAttachment = {},
                onOpenMarkdownTag = { _, _ -> },
                onOpenMarkdownUrl = {},
            )
        }
    }
}

@Composable
private fun WideComposer(placeholder: String, asTask: Boolean) {
    ThreadComposerDock {
        ThreadComposerContent(
            payload = RaftThreadComposerPayload(instanceId = "wide-composer", placeholder = placeholder, showAsTaskToggle = asTask),
            onEvent = { _, _, _, _ -> },
            attachmentPreviewEnabled = false,
        )
    }
}

// ---- Settings -------------------------------------------------------------------------------

@Composable
private fun WideSettingsAppearance() {
    SettingsWideListDetail(
        payload = appShellSettingsHomePayload(session = null, imBridgesEnabled = false, billingOpensExternally = false, serverRole = "owner"),
        widths = remember { RaftListPaneWidths(store = null) },
        active = true,
        picked = SettingsNativeDetailPage.Appearance.key,
        onPick = {},
        onBackToDefault = {},
        detailBottomInset = 0.dp,
        bottomContentPadding = 0.dp,
        detailPane = { _, modifier ->
            Box(modifier) {
                SettingsDetailShell(title = "Appearance", subtitle = "", iconName = RaftLucideIconName.Type, onBack = {}) {
                    SettingsAppearanceContent(
                        state = ScreenLoadState.Content(SettingsAppearanceSnapshot()),
                        actionState = null,
                        fontSizeDraft = "md",
                        bottomContentPadding = 0.dp,
                        onFontSizeDraftChange = {},
                        onSave = {},
                        onRetry = {},
                        showModeCard = true,
                    )
                }
            }
        },
    )
}

// ---- Tasks + task ---------------------------------------------------------------------------

@Composable
private fun WideTasksTask() {
    RaftTasksTaskColumnLayout(
        open = true,
        expanded = false,
        active = true,
        onToggleExpand = {},
        onClose = {},
        onBack = {},
        modifier = Modifier.fillMaxSize().background(RaftColor.Canvas),
        task = { WideTaskPage() },
    ) {
        CompositionLocalProvider(LocalSharedTasksOpenTaskId provides wideOpenTask.id.value) {
            SharedTasksPanel(
                state = TasksUiState(loadState = ScreenLoadState.Content(TasksContent(summaryCounts = wideTasks.summaryCounts(), tasks = wideTasks))),
                onIntent = {},
                initialPresentationOverride = TasksPresentationState.Default.copy(selectedMode = TasksViewMode.List),
            )
        }
    }
}

/** The More menu's bus: nothing is opened in a still capture. */
private object WideShellQuietEventBus : build.raft.app.data.RaftEventBus {
    override fun observe(eventName: String, crossProcess: Boolean, listener: (kotlinx.serialization.json.JsonObject?) -> Unit) =
        build.raft.app.data.RealtimeSubscription {}

    override fun post(eventName: String, eventData: kotlinx.serialization.json.JsonObject, crossProcess: Boolean, source: String) = Unit
}
