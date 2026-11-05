package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import build.raft.designsystem.components.RaftConversationKindGlyph
import build.raft.app.model.TaskSummary
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** THROWAWAY mock (never merge): channel header before/after Task v2 toggles. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h760dp-xxhdpi")
class ConvHeaderMockScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @org.junit.Before
    fun installPreferenceStore() {
        val store = InMemoryRaftKeyValueStore()
        RaftSharedKeyValueStoreProvider.installAppScopeFactory(RaftLocalPreferenceStore.KuiklyNamespace) { store }
    }

    @org.junit.After
    fun clearPreferenceStore() = RaftSharedKeyValueStoreProvider.clearAppScopeFactoryForTests()

    @Test fun a_before() = capture("convhdr.a.before") { Before() }

    @Test fun b_after() = capture("convhdr.b.after") { After(card = null) }

    @Test fun c_afterTasks() = capture("convhdr.c.after-tasks") { After(card = RaftThreadTab.Tasks) }

    @Test fun d_afterFiles() = capture("convhdr.d.after-files") { After(card = RaftThreadTab.Files) }

    private fun capture(name: String, content: @Composable ColumnScope.() -> Unit) =
        RaftRobolectricScreenshot.capture(name, RaftAppAppearance.Light, reportDir = "build/reports/convhdr", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize().background(RaftColor.Canvas)) { content() }
                }
            }
        }
}

private val channelPayload =
    RaftThreadScreenPayload(
        instanceId = "convhdr-channel",
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
private fun Header(tasksOpen: Boolean?, filesOpen: Boolean = false) {
    ThreadPageHeader(
        payload = channelPayload,
        applyTopSafeArea = false,
        onBack = {},
        onOpenInChannel = {},
        onOpenMemberProfile = {},
        onOpenSearch = {},
        onShowMembers = {},
        onShowSettings = {},
        mockTasksOpen = tasksOpen,
        mockFilesOpen = filesOpen,
    )
}

@Composable
private fun ColumnScope.Before() {
    Header(tasksOpen = null)
    RaftTabbedScaffold(
        tabs =
            listOf(
                RaftTabSpec(RaftThreadTab.Chat, "Chat", RaftLucideIconName.MessageSquare, glyph = RaftConversationKindGlyph.Chat),
                RaftTabSpec(RaftThreadTab.Tasks, "Tasks", RaftLucideIconName.ListTodo),
                RaftTabSpec(RaftThreadTab.Files, "Files", RaftLucideIconName.Paperclip),
            ),
        selectedKey = RaftThreadTab.Chat,
        onSelectedKeyChange = {},
        modifier = Modifier.weight(1f).fillMaxWidth(),
        itemKeyPrefix = "thread-tab",
    ) { _, modifier, _ ->
        Column(modifier.fillMaxWidth().background(RaftColor.SurfaceWhite)) { Conversation() }
    }
}

@Composable
private fun ColumnScope.After(card: RaftThreadTab?) {
    Header(tasksOpen = card == RaftThreadTab.Tasks, filesOpen = card == RaftThreadTab.Files)
    Box(Modifier.weight(1f).fillMaxWidth()) {
        Column(Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) { Conversation() }
        if (card != null) {
            // web ChannelSideCard covering the conversation on a phone: 56dp head with the title, seam, body.
            Column(Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Text(
                        if (card == RaftThreadTab.Tasks) "Tasks" else "Files",
                        style = RaftType.Body.copy(fontWeight = FontWeight.Bold, fontSize = 17.sp),
                        color = RaftColor.TextBlack,
                    )
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(RaftColor.BorderSeam))
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (card == RaftThreadTab.Tasks) {
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
                    } else {
                        ThreadFilesContent(payload = filesPayload, onEvent = { _, _ -> })
                    }
                }
            }
        }
    }
}

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
            payload = RaftThreadComposerPayload(instanceId = "convhdr-dock", placeholder = "Message #wg-context-p"),
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
        instanceId = "convhdr-files",
        status = "content",
        items =
            listOf(
                RaftThreadFileItem(attachmentId = "f1", messageId = "m1", name = "powercontext-notes.md", sizeLabel = "12 KB", createdAtLabel = "Oct 12, 12:56", mimeType = "text/markdown"),
                RaftThreadFileItem(attachmentId = "f2", messageId = "m2", name = "architecture.png", sizeLabel = "428 KB", createdAtLabel = "Oct 12, 12:58", mimeType = "image/png"),
                RaftThreadFileItem(attachmentId = "f3", messageId = "m3", name = "comparison.csv", sizeLabel = "3 KB", createdAtLabel = "Oct 12, 13:10", mimeType = "text/csv"),
            ),
    )
