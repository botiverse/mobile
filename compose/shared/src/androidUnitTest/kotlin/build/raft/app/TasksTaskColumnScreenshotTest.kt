package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.base.BridgeModule
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.IModuleCreator
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.PagerManager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.RaftLocalPreferenceStore
import build.raft.app.data.RaftSharedKeyValueStoreProvider
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.TaskWireDto
import build.raft.app.data.TasksContent
import build.raft.app.data.TasksPresentationState
import build.raft.app.data.TasksUiState
import build.raft.app.data.TasksViewMode
import build.raft.app.data.summaryCounts
import build.raft.app.data.toTaskSummary
import build.raft.app.model.MemberKind
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Tasks page with a task open on the wide layout (web TasksRoute), light and dark: the list,
 * the open task's card in the cell's selected fill, and the task in a 400dp column beside it whose
 * head (the production [ThreadPageHeader] of a task conversation) ends with Expand and Close.
 * `tasks.task-column.expanded`: Expand pressed (Restore), the task filling the row. The production
 * [RaftTasksTaskColumnLayout] and [SharedTasksPanel]; the column's body is a stand-in for the
 * conversation. Verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-mdpi")
class TasksTaskColumnScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val livePager =
        object : Pager() {}.apply {
            pagerId = "tasks-task-column-screenshot"
            pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
            registerModule(BridgeModule.MODULE_NAME, IModuleCreator { BridgeModule() })
        }

    @BeforeTest
    fun setUp() {
        PagerManager.register(livePager)
        // The panel reads its view-mode preference from the app-scope store; tests give it a blank one.
        val store = InMemoryRaftKeyValueStore()
        RaftSharedKeyValueStoreProvider.installAppScopeFactory(RaftLocalPreferenceStore.KuiklyNamespace) { store }
    }

    @AfterTest
    fun tearDown() {
        RaftSharedKeyValueStoreProvider.clearAppScopeFactoryForTests()
        PagerManager.unregister(livePager)
    }

    @Test fun open() = capture("tasks.task-column.open", RaftAppAppearance.Light, expanded = false)

    @Test fun openDark() = capture("tasks.task-column.open.dark", RaftAppAppearance.Dark, expanded = false)

    @Test fun expanded() = capture("tasks.task-column.expanded", RaftAppAppearance.Light, expanded = true)

    private fun capture(name: String, appearance: RaftAppAppearance, expanded: Boolean) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val grid = remember { RaftPaneGridStore() }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(livePager),
                LocalThreadHeaderMenuEventBus provides QuietColumnEventBus,
                LocalRaftPaneGridStore provides grid,
            ) {
                RaftDesignSystemTheme {
                    RaftTasksTaskColumnLayout(
                        open = true,
                        expanded = expanded,
                        active = true,
                        onToggleExpand = {},
                        onClose = {},
                        onBack = {},
                        modifier = Modifier.fillMaxSize().background(RaftColor.Canvas),
                        task = { TaskColumnPage() },
                    ) {
                        CompositionLocalProvider(LocalSharedTasksOpenTaskId provides openTask.id.value) {
                            SharedTasksPanel(
                                state = TasksUiState(loadState = ScreenLoadState.Content(TasksContent(summaryCounts = columnTasks.summaryCounts(), tasks = columnTasks))),
                                onIntent = {},
                                initialPresentationOverride = TasksPresentationState.Default.copy(selectedMode = TasksViewMode.List),
                                taskV2Enabled = true,
                            )
                        }
                    }
                }
            }
        }
}

/** The task's page in the column: its head as a task conversation's, then a stand-in for the messages. */
@Composable
private fun TaskColumnPage() {
    Column(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) {
        ThreadPageHeader(
            payload =
                RaftThreadScreenPayload(
                    instanceId = "task-conversation-4",
                    surfaceKind = RaftThreadSurfaceKind.Channel,
                    channelKind = RaftTaskConversationChannelKind,
                    status = "content",
                    // As the app opens a task conversation (buildAppShellThreadPayload): no tabs or settings.
                    showModeTabs = false,
                    showSettingsAction = false,
                    showMembersAction = false,
                ),
            headerDisplay =
                ThreadHeaderDisplayModel(
                    title = "#ship-mobile:#4 ${openTask.title}",
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
        Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp)) {
            RaftText(openTask.title, style = RaftTheme.typography.headline)
        }
    }
}

/** The More menu's bus: nothing is opened in a still capture. */
private object QuietColumnEventBus : build.raft.app.data.RaftEventBus {
    override fun observe(eventName: String, crossProcess: Boolean, listener: (kotlinx.serialization.json.JsonObject?) -> Unit) =
        build.raft.app.data.RealtimeSubscription {}

    override fun post(eventName: String, eventData: kotlinx.serialization.json.JsonObject, crossProcess: Boolean, source: String) = Unit
}

private fun columnTask(number: Int, title: String, status: String): TaskSummary =
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

private val columnTasks =
    listOf(
        columnTask(1, "Add dark mode to the onboarding screens", "todo"),
        columnTask(4, "Migrate push notifications to the new API", "in_progress"),
        columnTask(5, "Tablet layout for the Tasks page", "in_progress"),
    )

private val openTask = columnTasks[1]
