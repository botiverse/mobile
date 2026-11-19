package build.raft.app

import androidx.compose.foundation.background
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
import build.raft.app.data.InboxFilter
import build.raft.app.data.InboxItem
import build.raft.app.data.RaftListPaneKind
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SettingsAppearanceSnapshot
import build.raft.app.data.TaskWireDto
import build.raft.app.data.TasksContent
import build.raft.app.data.TasksPresentationState
import build.raft.app.data.TasksUiState
import build.raft.app.data.TasksViewMode
import build.raft.app.data.summaryCounts
import build.raft.app.data.toTaskSummary
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberKind
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import build.raft.app.model.raftEpochDayForDate
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

private val wideTime =
    RaftTimeContext(
        localOffsetMinutes = 8 * 60,
        todayEpochDay = raftEpochDayForDate(2026, 10, 22),
        nowEpochMillis = 1_792_635_000_000L,
    )

private val wideInboxRows =
    listOf(
        InboxItem(
            key = "thread:task-4",
            kind = InboxItemKind.Thread,
            channelId = ChannelId("task-4"),
            channelName = "thread",
            unreadCount = 2,
            parentChannelId = ChannelId("ship-mobile"),
            parentChannelName = "ship-mobile",
            parentChannelKind = ConversationKind.Channel,
            parentMessagePreview = "Migrate push notifications to the new API",
            latestActivityPreview = "The new token flow is in; testing on the tablet next.",
            latestActivitySenderName = "Lagoon",
            latestActivitySenderId = "agent-lagoon",
            latestActivitySenderKind = MemberKind.Agent,
            latestActivityAt = "2026-10-22T01:40:00Z",
        ),
        InboxItem(
            key = "channel:release",
            kind = InboxItemKind.Channel,
            channelId = ChannelId("release"),
            channelName = "release",
            unreadCount = 1,
            hasMention = true,
            lastMessageSenderName = "Volta",
            lastMessageSenderKind = MemberKind.Agent,
            lastMessagePreview = "Deployed, and I just tested it again to confirm it still works.",
            lastMessageAt = "2026-10-22T01:17:00Z",
        ),
        InboxItem(
            key = "channel:design",
            kind = InboxItemKind.Channel,
            channelId = ChannelId("design"),
            channelName = "design",
            lastMessageSenderName = "Kirby",
            lastMessageSenderKind = MemberKind.Human,
            lastMessagePreview = "From 768 a head is one line, by touch too.",
            lastMessageAt = "2026-10-21T08:17:00Z",
        ),
        InboxItem(
            key = "channel:joint-hands",
            kind = InboxItemKind.Channel,
            channelId = ChannelId("joint-hands"),
            channelName = "joint-hands",
            channelKind = ConversationKind.Joint,
            channelType = "joint",
            unreadCount = 6,
            lastMessageSenderName = "System",
            lastMessageSenderKind = MemberKind.System,
            lastMessagePreview = "1 new task created: task #50 \"Upgrade admin vite to 6.4.4+ (security)\"",
            lastMessageAt = "2026-10-21T07:26:00Z",
        ),
    )

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
            channelSymbolsByChannelId = mapOf("ship-mobile" to "#", "release" to "#", "design" to "#", "joint-hands" to "git-branch"),
        )
    Column(Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
        SharedActivityHeader(
            totalCount = 506,
            totalUnreadCount = 657,
            onClose = null,
            topSafeAreaPadding = 0.dp,
            refreshing = false,
            mainTab = true,
        )
        SharedActivityFilterBar(
            selectedFilter = InboxFilter.All,
            totalUnreadCount = 657,
            onFilterSelected = {},
            onMarkAllRead = {},
        )
        Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            activityConversationListEntries(wideInboxRows, wideTime).forEach { entry ->
                if (entry is ActivityConversationListEntry.DayHeading) {
                    ActivityDayHeading(entry.label, first = entry.first, background = RaftColor.SurfaceWhite)
                    return@forEach
                }
                val item = (entry as ActivityConversationListEntry.Row).item
                ActivityConversationListRow(
                    item = item,
                    model = item.activityConversationRowModel(context, InboxFilter.All),
                    divider = entry.divider,
                    selected = item.key == "thread:task-4",
                    timeContext = wideTime,
                    agentStatusResolver = EmptyRaftAgentStatusResolver,
                    canMarkDone = true,
                    openGestureKey = item.key,
                    captureMenuAnchor = false,
                    onOpen = {},
                    onOpenMenu = {},
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

// ---- Task conversation (Activity's detail and the Tasks column) ------------------------------

private fun wideTask(number: Int, title: String, status: String): TaskSummary =
    TaskWireDto(
        id = "task-$number",
        messageId = "msg-task-$number",
        channelId = "ch-ship-mobile",
        channelName = "ship-mobile",
        channelType = "channel",
        taskNumber = number,
        title = title,
        status = status,
        createdByType = "user",
        createdById = "user-maya",
        createdByName = "Maya Chen",
        createdAt = "2026-10-29T18:38:00Z",
        updatedAt = "2026-10-29T19:12:05Z",
    ).toTaskSummary(diagnostics = {}) ?: error("fixture task #$number must map")

private val wideTasks =
    listOf(
        wideTask(1, "Add dark mode to the onboarding screens", "todo"),
        wideTask(4, "Migrate push notifications to the new API", "in_progress"),
        wideTask(5, "Tablet layout for the Tasks page", "in_progress"),
        wideTask(7, "Release notes for 1.27", "done"),
    )

private val wideOpenTask = wideTasks[1]

@Composable
private fun WideTaskPage() {
    Column(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) {
        ThreadPageHeader(
            payload =
                RaftThreadScreenPayload(
                    instanceId = "wide-task-conversation-4",
                    surfaceKind = RaftThreadSurfaceKind.Channel,
                    channelKind = RaftTaskConversationChannelKind,
                    status = "content",
                    showModeTabs = false,
                    showSettingsAction = false,
                    showMembersAction = false,
                ),
            headerDisplay =
                ThreadHeaderDisplayModel(
                    title = "#ship-mobile:#4 ${wideOpenTask.title}",
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
                    ref = "#ship-mobile:#4",
                    taskNumber = 4,
                    status = TaskStatus.InProgress,
                    assigneeName = "Lagoon",
                    assigneeKind = MemberKind.Agent,
                    summarized = false,
                    followed = true,
                    onToggleHeadFloat = {},
                    onViewInChannel = {},
                    onToggleFollow = {},
                ),
        )
        WideMessages(Modifier.weight(1f).fillMaxWidth(), wideTaskMessages)
        WideComposer("Message #ship-mobile:#4")
    }
}

// ---- Channel (and its thread) ---------------------------------------------------------------

private fun wideRow(id: String, seq: Long, author: String, body: String, own: Boolean = false, kind: String = "human") =
    RaftThreadMessageItem(
        id = id,
        authorId = if (own) "user-me" else "member-$author".lowercase(),
        authorName = author,
        authorKind = kind,
        isOwn = own,
        avatarLabel = author.take(1),
        seq = seq,
        timestampLabel = "2026-11-02T09:1$seq:00Z",
        body = body,
    )

private val wideChannelMessages =
    listOf(
        wideRow("m-1", 1, "Ada", "Morning. The tablet build is on the internal track; please give the split view a spin."),
        wideRow("m-2", 2, "Scout", "Checked on the Pixel Tablet: the sidebar and the conversation sit side by side, rows read well.", kind = "agent"),
        wideRow("m-3", 3, "Me", "Thanks. Next up is matching the heads to the web on wide windows.", own = true),
        wideRow("m-4", 4, "Ada", "Works for me. Touch keeps the 44dp targets; the mouse gets the tighter controls."),
    )

private val wideThreadMessages =
    listOf(
        wideRow("t-1", 1, "Scout", "Checked on the Pixel Tablet: the sidebar and the conversation sit side by side, rows read well.", kind = "agent"),
        wideRow("t-2", 2, "Kirby", "From 768 a head is one line, by touch too."),
        wideRow("t-3", 3, "Me", "Noted, the Activity head goes first.", own = true),
    )

private val wideTaskMessages =
    listOf(
        wideRow("k-1", 1, "Maya", "Push tokens move to the new endpoint this week."),
        wideRow("k-2", 2, "Lagoon", "The new token flow is in; testing on the tablet next.", kind = "agent"),
    )

@Composable
private fun WideChannel(withThread: Boolean) {
    val fixture = VisualFixtureData.HomeChannelList
    val servers = listOf(RaftServerSwitcherItem(id = VisualFixtureData.Server.ID, name = fixture.SERVER_NAME, slug = VisualFixtureData.Server.SLUG))
    val channels = remember { fixture.CHANNELS.map(::homeChannelListChannel) }
    val directMessages = remember { fixture.DIRECT_MESSAGES.map(::homeChannelListDirectMessage) }
    val channelName = channels.firstOrNull()?.name ?: "general"
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
                selectedChannelId = channels.firstOrNull()?.id,
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
                WideConversation(Modifier.weight(1f).fillMaxHeight(), "#$channelName", wideChannelMessages, "Message #$channelName")
                if (withThread) {
                    WideConversation(Modifier.width(400.dp).fillMaxHeight(), "Thread", wideThreadMessages, "Reply in thread", sourceThreadId = "thread-wide")
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
    sourceThreadId: String = "channel-wide",
) {
    val payload =
        remember(title, sourceThreadId) {
            RaftThreadScreenPayload(
                instanceId = "wide-$sourceThreadId",
                sourceThreadId = sourceThreadId,
                title = title,
                subtitle = "Release train for the tablet layout",
                status = "content",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                selectedTab = "chat",
                showSettingsAction = true,
                showMembersAction = true,
                membersCount = 6,
                showOpenInChannelAction = false,
            )
        }
    Column(modifier.background(RaftColor.SurfaceWhite)) {
        ThreadPageHeader(
            payload = payload,
            applyTopSafeArea = false,
            onBack = {},
            onOpenInChannel = {},
            onOpenMemberProfile = {},
            onOpenSearch = {},
            onShowMembers = {},
            onShowSettings = {},
        )
        WideMessages(Modifier.weight(1f).fillMaxWidth(), messages)
        WideComposer(placeholder)
    }
}

@Composable
private fun WideMessages(modifier: Modifier, messages: List<RaftThreadMessageItem>) {
    Column(modifier.background(RaftColor.Canvas).padding(top = RaftAppSpacing.Sm)) {
        messages.forEach { message ->
            ThreadMessageCard(
                message = message,
                messageBodyTextStyle = raftMessageBodyTextStyle("md"),
                isMarkdownTagValid = { false },
                timestampLabel = "9:1${message.seq} AM",
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
private fun WideComposer(placeholder: String) {
    ThreadComposerDock {
        ThreadComposerContent(
            payload = RaftThreadComposerPayload(instanceId = "wide-composer", placeholder = placeholder),
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
                taskV2Enabled = true,
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
