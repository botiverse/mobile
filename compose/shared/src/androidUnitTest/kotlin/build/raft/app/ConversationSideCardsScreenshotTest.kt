package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import androidx.compose.foundation.layout.padding
import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.KeyValueChatLayoutPreferenceStore
import build.raft.app.data.RaftCollapsibleColumn
import build.raft.app.data.RaftListPaneKind
import build.raft.app.data.raftSharedPreferenceStore
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.app.data.RaftLocalPreferenceStore
import build.raft.app.data.RaftSharedKeyValueStoreProvider
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.TaskWireDto
import build.raft.app.data.TasksContent
import build.raft.app.data.TasksUiState
import build.raft.app.data.summaryCounts
import build.raft.app.data.toTaskSummary
import build.raft.app.markdown.ThreadMarkdownTagKind
import build.raft.app.model.ChannelId
import build.raft.app.model.TaskSummary
import build.raft.app.shell.LocalRaftPaneGridStore
import build.raft.app.shell.RaftAdaptiveListDetailSplit
import build.raft.app.shell.RaftListPaneWidths
import build.raft.app.shell.RaftPaneGrid
import build.raft.app.shell.RaftPaneGridCell
import build.raft.app.shell.RaftPaneGridColumn
import build.raft.app.shell.RaftPaneGridStore
import build.raft.app.shell.RaftSidebarCollapseState
import build.raft.app.thread.ConversationSideCard
import build.raft.app.thread.ConversationSideCardsCover
import build.raft.app.thread.ConversationSideCardsState
import build.raft.app.thread.RaftThreadFileItem
import build.raft.app.thread.RaftThreadFilesPayload
import build.raft.app.thread.RaftThreadMessageItem
import build.raft.app.thread.RaftThreadScreenPayload
import build.raft.app.thread.ThreadComposerDock
import build.raft.app.thread.ThreadFilesContent
import build.raft.app.thread.ThreadHeaderSideCardToggles
import build.raft.app.thread.ThreadMessageCard
import build.raft.app.thread.ThreadPageHeader
import build.raft.app.thread.conversationSideCardsColumn
import build.raft.app.thread.raftMessageBodyTextStyle
import kotlin.test.Test
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Task v2 conversation page on a phone, light and dark: no tab row;
 * the header's Search · Tasks · Files · Settings; an open card covers the conversation under the
 * header (web ChannelSideCard `overlay`), both open show one under a Tasks / Files switch.
 * The production [ThreadPageHeader] and [ConversationSideCardsCover] over a fixture
 * conversation. Verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h760dp-xxhdpi")
class ConversationSideCardsScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Before
    fun installPreferenceStore() {
        val store = InMemoryRaftKeyValueStore()
        RaftSharedKeyValueStoreProvider.installAppScopeFactory(RaftLocalPreferenceStore.KuiklyNamespace) { store }
    }

    @After
    fun clearPreferenceStore() = RaftSharedKeyValueStoreProvider.clearAppScopeFactoryForTests()

    @Test fun header() = capture("conversation.side-cards.header", RaftAppAppearance.Light, ConversationSideCardsState())

    @Test fun tasks() = capture("conversation.side-cards.tasks", RaftAppAppearance.Light, open(ConversationSideCard.Tasks))

    private fun open(vararg cards: ConversationSideCard): ConversationSideCardsState =
        cards.fold(ConversationSideCardsState()) { state, card -> state.withOpen(card, true) }

    private fun capture(name: String, appearance: RaftAppAppearance, cards: ConversationSideCardsState) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/side-cards", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize().background(RaftColor.Canvas)) { Page(cards) }
                }
            }
        }
}

/**
 * The workspace's one pane grid (web MainLayout row), light and dark: the sidebar as the grid's
 * leading column at a width someone dragged (300, kept under its own key, the sidebar's default
 * being 240), then the conversation, the reply thread and Task v2's Tasks / Files column, left to
 * right as on web. Nested as in the app: [RaftAdaptiveListDetailSplit] around the workspace's
 * detail grid (the thread's column) around the conversation's (the cards' column), which hand
 * their columns up to the split's row.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1600dp-h900dp-mdpi")
class WorkspacePaneGridScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Before
    fun installPreferenceStore() {
        val store = InMemoryRaftKeyValueStore()
        RaftSharedKeyValueStoreProvider.installAppScopeFactory(RaftLocalPreferenceStore.KuiklyNamespace) { store }
    }

    @After
    fun clearPreferenceStore() = RaftSharedKeyValueStoreProvider.clearAppScopeFactoryForTests()

    @Test fun conversationThreadAndCards() = capture("workspace.pane-grid.thread-cards", RaftAppAppearance.Light)

    @Test fun conversationThreadAndCardsDark() = capture("workspace.pane-grid.thread-cards.dark", RaftAppAppearance.Dark)

    private val both = ConversationSideCardsState().withOpen(ConversationSideCard.Tasks, true).withOpen(ConversationSideCard.Files, true)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/side-cards", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            val widths =
                remember {
                    val preferences = KeyValueChatLayoutPreferenceStore(raftSharedPreferenceStore())
                    preferences.saveAdaptiveListPaneWidth(RaftListPaneKind.Sidebar, "300")
                    RaftListPaneWidths(preferences)
                }
            val grid = remember { RaftPaneGridStore() }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager), LocalRaftPaneGridStore provides grid) {
                RaftDesignSystemTheme {
                    Box(Modifier.fillMaxSize().background(RaftColor.Canvas)) {
                        RaftAdaptiveListDetailSplit(
                            kind = RaftListPaneKind.Sidebar,
                            widths = widths,
                            dualPane = true,
                            active = true,
                            hasSelection = true,
                            detailBottomInset = 0.dp,
                            listPane = { SidebarFixture() },
                            detailPane = { modifier ->
                                RaftPaneGrid(
                                    columns =
                                        listOf(
                                            RaftPaneGridColumn(
                                                bounds = RaftPaneGridColumns.Thread,
                                                resizeLabel = "Resize thread",
                                                cells = listOf(RaftPaneGridCell(id = "thread") { ThreadFixture() }),
                                            )
                                        ),
                                    modifier = modifier,
                                ) {
                                    RaftPaneGrid(
                                        columns =
                                            listOfNotNull(
                                                conversationSideCardsColumn(
                                                    cards = both,
                                                    onPick = {},
                                                    onClose = {},
                                                    tasksContent = { TasksFixture() },
                                                    filesContent = { ThreadFilesContent(payload = filesPayload, onEvent = { _, _ -> }) },
                                                )
                                            ),
                                        modifier = Modifier.fillMaxSize(),
                                    ) {
                                        Column(Modifier.fillMaxSize().background(RaftColor.Canvas)) {
                                            Header(both)
                                            Column(Modifier.weight(1f).fillMaxWidth().background(RaftColor.SurfaceWhite)) { Conversation() }
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
}

/**
 * #337 (Reef's patrol item 7): an iPad held upright (820 x 1180) with the sidebar open and Tasks / Files open beside
 * the conversation. The sidebar's 240 would leave the conversation and the cards 579, under their 360 + 280, so the
 * row would clip the conversation's composer (its Send). On a portrait touch tablet the sidebar gives way
 * ([raftSidebarGivesWay], [RaftSidebarCollapseState]): it collapses, not saved, and the conversation keeps 360.
 * `kept-open` is the sidebar the person brought back with the rail's toggle (and the row before #337): web's row, the
 * conversation clipped.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w820dp-h1180dp-mdpi")
class PortraitTabletPaneGridScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Before
    fun installPreferenceStore() {
        val store = InMemoryRaftKeyValueStore()
        RaftSharedKeyValueStoreProvider.installAppScopeFactory(RaftLocalPreferenceStore.KuiklyNamespace) { store }
    }

    @After
    fun clearPreferenceStore() = RaftSharedKeyValueStoreProvider.clearAppScopeFactoryForTests()

    @Test fun sidebarGivesWayToTheCards() = capture("workspace.pane-grid.portrait-tablet.cards", givesWay = true)

    @Test fun sidebarKeptOpenClipsTheRow() = capture("workspace.pane-grid.portrait-tablet.cards.kept-open", givesWay = false)

    private val both = ConversationSideCardsState().withOpen(ConversationSideCard.Tasks, true).withOpen(ConversationSideCard.Files, true)

    private fun capture(name: String, givesWay: Boolean) =
        RaftRobolectricScreenshot.capture(name, RaftAppAppearance.Light, reportDir = "build/reports/side-cards", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            val widths = remember { RaftListPaneWidths(KeyValueChatLayoutPreferenceStore(raftSharedPreferenceStore())) }
            val collapse = remember { RaftSidebarCollapseState(store = null) }
            val grid = remember { RaftPaneGridStore() }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager), LocalRaftPaneGridStore provides grid) {
                RaftDesignSystemTheme {
                    Box(Modifier.fillMaxSize().background(RaftColor.Canvas)) {
                        RaftAdaptiveListDetailSplit(
                            kind = RaftListPaneKind.Sidebar,
                            widths = widths,
                            dualPane = true,
                            active = true,
                            hasSelection = true,
                            detailBottomInset = 0.dp,
                            listPane = { SidebarFixture() },
                            collapsed = collapse.isHidden(RaftCollapsibleColumn.Sidebar),
                            onGivesWayChange = if (givesWay) { yields: Boolean -> collapse.setGivingWay(RaftCollapsibleColumn.Sidebar, yields) } else null,
                            detailPane = { modifier ->
                                RaftPaneGrid(
                                    columns =
                                        listOfNotNull(
                                            conversationSideCardsColumn(
                                                cards = both,
                                                onPick = {},
                                                onClose = {},
                                                tasksContent = { TasksFixture() },
                                                filesContent = { ThreadFilesContent(payload = filesPayload, onEvent = { _, _ -> }) },
                                            )
                                        ),
                                    modifier = modifier,
                                ) {
                                    Column(Modifier.fillMaxSize().background(RaftColor.Canvas)) {
                                        Header(both)
                                        Column(Modifier.weight(1f).fillMaxWidth().background(RaftColor.SurfaceWhite)) { Conversation() }
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
}

@Composable
private fun SidebarFixture() {
    Column(Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
        RaftText("Botiverse", modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp), style = RaftTheme.typography.headline)
        listOf("triage", "wg-context-p", "proj-workflow", "design", "general").forEach { name ->
            RaftListRow(title = "# $name", selected = name == "wg-context-p", onClick = {})
        }
    }
}

@Composable
private fun ThreadFixture() {
    Column(Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
        RaftPanelBackHeader(title = "Thread", subtitle = "#wg-context-p", onBack = null)
        Column(Modifier.weight(1f).fillMaxWidth()) {
            listOf(messages[3], msg(5, "Cora", "agent", "Started: the table compares storage, serving and tenancy.")).forEach { message ->
                ThreadMessageCard(
                    message = message,
                    messageBodyTextStyle = raftMessageBodyTextStyle("md"),
                    isMarkdownTagValid = { false },
                    timestampLabel = message.timestampLabel.substring(11, 16),
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
        ThreadComposerDock {
            ThreadComposerContent(
                payload = RaftThreadComposerPayload(instanceId = "pane-grid-thread-dock", placeholder = "Reply"),
                onEvent = { _, _, _, _ -> },
                attachmentPreviewEnabled = false,
            )
        }
    }
}

@Composable
private fun Header(cards: ConversationSideCardsState) =
    ThreadPageHeader(
        payload = channelPayload,
        applyTopSafeArea = false,
        onBack = {},
        onOpenInChannel = {},
        onOpenMemberProfile = {},
        onOpenSearch = {},
        onShowMembers = {},
        onShowSettings = {},
        sideCardToggles = ThreadHeaderSideCardToggles(tasksAvailable = true, cards = cards, onToggle = {}),
    )

@Composable
private fun TasksFixture() =
    SharedTasksPanel(
        state = TasksUiState(loadState = ScreenLoadState.Content(TasksContent(summaryCounts = tasks.summaryCounts(), tasks = tasks))),
        showHeader = false,
        showChannelFilter = false,
        createTasksChannelId = ChannelId("ch-wg-context"),
        timeContext = build.raft.app.model.RaftTimeContext(
            localOffsetMinutes = 8 * 60,
            todayEpochDay = build.raft.app.model.raftEpochDayForDate(2026, 10, 30),
            nowEpochMillis = 1_793_331_000_000L,
        ),
        onIntent = {},
    )

@Composable
private fun ColumnScope.Page(cards: ConversationSideCardsState) {
    ThreadPageHeader(
        payload = channelPayload,
        applyTopSafeArea = false,
        onBack = {},
        onOpenInChannel = {},
        onOpenMemberProfile = {},
        onOpenSearch = {},
        onShowMembers = {},
        onShowSettings = {},
        sideCardToggles = ThreadHeaderSideCardToggles(tasksAvailable = true, cards = cards, onToggle = {}),
    )
    Box(Modifier.weight(1f).fillMaxWidth()) {
        Column(Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) { Conversation() }
        ConversationSideCardsCover(
            cards = cards,
            onPick = {},
            onBack = null,
            onSwipeClose = {},
            tasksContent = {
                SharedTasksPanel(
                    state = TasksUiState(loadState = ScreenLoadState.Content(TasksContent(summaryCounts = tasks.summaryCounts(), tasks = tasks))),
                    showHeader = false,
                    showChannelFilter = false,
                    createTasksChannelId = ChannelId("ch-wg-context"),
                    timeContext = build.raft.app.model.RaftTimeContext(
                        localOffsetMinutes = 8 * 60,
                        todayEpochDay = build.raft.app.model.raftEpochDayForDate(2026, 10, 30),
                        nowEpochMillis = 1_793_331_000_000L,
                    ),
                    onIntent = {},
                )
            },
            filesContent = { ThreadFilesContent(payload = filesPayload, onEvent = { _, _ -> }) },
        )
    }
}

private val channelPayload =
    RaftThreadScreenPayload(
        instanceId = "side-cards-channel",
        sourceThreadId = "channel-wg-context",
        title = "#wg-context-p",
        subtitle = "Working group: context platform",
        status = "content",
        surfaceKind = RaftThreadSurfaceKind.Channel,
        selectedTab = "chat",
        showModeTabs = true,
        showSettingsAction = true,
        showMembersAction = false,
        showOpenInChannelAction = false,
    )

@Composable
private fun ColumnScope.Conversation() {
    Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Bottom) {
        messages.forEach { message ->
            ThreadMessageCard(
                message = message,
                messageBodyTextStyle = raftMessageBodyTextStyle("md"),
                isMarkdownTagValid = { tag -> tag.kind == ThreadMarkdownTagKind.UserMention },
                timestampLabel = message.timestampLabel.substring(11, 16),
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
        Spacer(Modifier.height(8.dp))
    }
    ThreadComposerDock {
        ThreadComposerContent(
            payload = RaftThreadComposerPayload(instanceId = "side-cards-dock", placeholder = "Message #wg-context-p"),
            onEvent = { _, _, _, _ -> },
            attachmentPreviewEnabled = false,
        )
    }
}

private fun msg(seq: Long, author: String, kind: String, body: String, own: Boolean = false) =
    RaftThreadMessageItem(
        id = "m-$seq",
        authorId = "id-$author",
        authorName = author,
        authorKind = kind,
        isOwn = own,
        avatarLabel = author.take(1),
        seq = seq,
        timestampLabel = "2026-10-12T12:${50 + seq}:00Z",
        body = body,
    )

private val messages =
    listOf(
        msg(1, "artin", "human", "OceanBase PowerContext is prior art, please help me understand this project.", own = true),
        msg(2, "Cora", "agent", "On it, @artin. I'll go through the repo and the docs first."),
        msg(3, "Cora", "agent", "Here's the summary: it keeps a per-tenant context store and serves it over SQL."),
        msg(4, "artin", "human", "Great, make a task for the comparison table.", own = true),
    )

private fun task(number: Int, title: String, status: String): TaskSummary =
    TaskWireDto(
        id = "task-$number",
        messageId = "msg-task-$number",
        channelId = "ch-wg-context",
        channelName = "wg-context-p",
        channelType = "channel",
        taskNumber = number,
        title = title,
        status = status,
        createdByType = "user",
        createdById = "user-artin",
        createdByName = "artin",
        completedAt = if (status == "done") "2026-10-29T19:12:05Z" else null,
        createdAt = "2026-10-29T18:38:00Z",
        updatedAt = "2026-10-29T19:12:05Z",
    ).toTaskSummary() ?: error("fixture task #$number must map")

private val tasks =
    listOf(
        task(1, "OceanBase PowerContext: understand the project", "in_review"),
        task(2, "Comparison table: PowerContext vs ours", "in_progress"),
        task(3, "Draft the context store schema", "todo"),
        task(4, "Collect prior-art links", "done"),
    )

private val filesPayload =
    RaftThreadFilesPayload(
        instanceId = "side-cards-files",
        status = "content",
        items =
            listOf(
                RaftThreadFileItem(attachmentId = "f1", messageId = "m1", name = "powercontext-notes.md", sizeLabel = "12 KB", createdAtLabel = "Oct 12, 12:56", mimeType = "text/markdown"),
                RaftThreadFileItem(attachmentId = "f2", messageId = "m2", name = "architecture.png", sizeLabel = "428 KB", createdAtLabel = "Oct 12, 12:58", mimeType = "image/png"),
                RaftThreadFileItem(attachmentId = "f3", messageId = "m3", name = "comparison.csv", sizeLabel = "3 KB", createdAtLabel = "Oct 12, 13:10", mimeType = "text/csv"),
            ),
    )
