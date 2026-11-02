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
import build.raft.app.data.InMemoryRaftKeyValueStore
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
import kotlin.test.Test
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Task v2 (`task_v2_conversations_v0`) conversation page on a phone, light and dark: no tab row;
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

    @Test fun headerDark() = capture("conversation.side-cards.header.dark", RaftAppAppearance.Dark, ConversationSideCardsState())

    @Test fun tasks() = capture("conversation.side-cards.tasks", RaftAppAppearance.Light, open(ConversationSideCard.Tasks))

    @Test fun tasksDark() = capture("conversation.side-cards.tasks.dark", RaftAppAppearance.Dark, open(ConversationSideCard.Tasks))

    @Test fun files() = capture("conversation.side-cards.files", RaftAppAppearance.Light, open(ConversationSideCard.Files))

    @Test fun filesDark() = capture("conversation.side-cards.files.dark", RaftAppAppearance.Dark, open(ConversationSideCard.Files))

    @Test fun both() =
        capture("conversation.side-cards.both", RaftAppAppearance.Light, open(ConversationSideCard.Tasks, ConversationSideCard.Files))

    @Test fun bothDark() =
        capture("conversation.side-cards.both.dark", RaftAppAppearance.Dark, open(ConversationSideCard.Tasks, ConversationSideCard.Files))

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
