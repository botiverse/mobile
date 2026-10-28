package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.base.BridgeModule
import build.raft.app.compat.pager.IModuleCreator
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.PagerManager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.TaskHistoryDetail
import build.raft.app.data.TaskHistoryField
import build.raft.app.data.TaskHistoryFieldChange
import build.raft.app.data.TaskHistoryKind
import build.raft.app.data.ThreadAgentFollower
import build.raft.app.data.ThreadAgentFollowerRoster
import build.raft.app.data.ThreadAgentFollowersState
import build.raft.app.data.taskStatusOptions
import build.raft.app.model.MemberKind
import build.raft.app.model.TaskStatus
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Task v2: the top of a task conversation (web `TaskConversationDialog`), light and dark, at a
 * 360dp phone width: the header with the task's ref, its Agent followers and the More menu; then
 * the head — title, a description folded to three lines with Show More, the inline facts (Status,
 * Assignee, Source, Created by, Created at, Earlier discussion) and the History unfolded (created,
 * assigned, a status change drawn as two badges, an amendment, a close). Verify / record as
 * [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h900dp-xxhdpi")
class TaskConversationHeadScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    private val livePager =
        object : Pager() {}.apply {
            pagerId = "task-head-screenshot"
            pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
            // The More menu posts through the live page's bridge: give it one.
            registerModule(BridgeModule.MODULE_NAME, IModuleCreator { BridgeModule() })
        }

    @BeforeTest fun registerPager() = PagerManager.register(livePager)

    @AfterTest fun unregisterPager() = PagerManager.unregister(livePager)

    @Test
    fun headLight() = capture("task.conversation.head", RaftAppAppearance.Light)

    @Test
    fun headDark() = capture("task.conversation.head.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = livePager
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalThreadHeaderMenuEventBus provides QuietEventBus,
            ) {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) {
                        TaskHeader()
                        TaskConversationHead(
                            model = HeadModel,
                            onSelectStatus = {},
                            onOpenAssignee = {},
                            onToggleFollow = {},
                            onOpenSource = {},
                            onOpenEarlierDiscussion = {},
                            initiallyHistoryOpen = true,
                            // The whole head in view: the page caps it at 45% and scrolls inside.
                            maxHeightFraction = 1f,
                        )
                    }
                }
            }
        }
}

@Composable
private fun TaskHeader() {
    val payload =
        RaftThreadScreenPayload(
            instanceId = "task-conversation-7",
            surfaceKind = RaftThreadSurfaceKind.Channel,
            channelKind = RaftTaskConversationChannelKind,
            status = "content",
            // As the app opens a task conversation (buildAppShellThreadPayload): no tabs or settings.
            showModeTabs = false,
            showSettingsAction = false,
            showMembersAction = false,
        )
    ThreadPageHeader(
        agentFollowersHeader = {
            ThreadAgentFollowersHeader(
                state =
                    ThreadAgentFollowersState(
                        roster =
                            ThreadAgentFollowerRoster(
                                threadChannelId = "task-conversation-7",
                                canManage = true,
                                agents =
                                    listOf(
                                        follower("agent-lagoon", "Lagoon"),
                                        follower("agent-stamp", "Stamp"),
                                    ),
                            )
                    ),
                dispatch = {},
            )
        },
        payload = payload,
        headerDisplay =
            ThreadHeaderDisplayModel(
                title = "#raft-mobile:#7 Task detail parity with the web",
                subtitle = null,
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = RaftTaskConversationChannelKind,
            ),
        applyTopSafeArea = false,
        onBack = {},
        onOpenInChannel = {},
        onOpenMemberProfile = {},
        onOpenSearch = {},
        onShowMembers = {},
        onShowSettings = {},
        onViewTaskInChannel = {},
    )
}

private fun follower(id: String, name: String) =
    ThreadAgentFollower(id = id, name = name, status = "active", serverId = "server-1", isCurrentServer = true, canRemove = true)

private val HeadModel =
    TaskConversationHeadModel(
        bar =
            TaskConversationHeaderBarModel(
                status = TaskStatus.InProgress,
                statusOptions = taskStatusOptions(TaskStatus.InProgress, canManageServer = false),
                statusUpdating = false,
                assigneeName = "Lagoon",
                assigneeEditable = true,
                followed = true,
                followPending = false,
            ),
        title = "Task detail parity with the web",
        description =
            "The app's task detail lacks a lot compared with web. Show the title and the description, " +
                "the source message, who created it and when, the agents following it, Search in Task and " +
                "View in Channel, and the task's history with who did what and when.",
        source =
            TaskHeadSource(
                authorName = "artin",
                authorKind = MemberKind.Human,
                authorAvatarWireValue = null,
                preview = "The app's task detail lacks a lot compared with web",
                time = "09:12",
            ),
        createdBy = "artin",
        createdAt = "10-28 09:14",
        earlierDiscussion = TaskHeadEarlierDiscussion(replyCount = 4),
        history =
            TaskHeadHistory.Rows(
                listOf(
                    TaskHeadHistoryRow(
                        id = "e1",
                        kind = TaskHistoryKind.Created,
                        actor = "artin",
                        time = "09:14",
                        markerStatus = TaskStatus.Todo,
                        detail = TaskHistoryDetail.Created("7", TaskStatus.Todo),
                    ),
                    TaskHeadHistoryRow(
                        id = "e2",
                        kind = TaskHistoryKind.AssigneeChanged,
                        actor = "artin",
                        time = "09:14",
                        markerStatus = null,
                        detail = TaskHistoryDetail.Assigned("agent", "agent-lagoon"),
                        assigneeName = "Lagoon",
                    ),
                    TaskHeadHistoryRow(
                        id = "e3",
                        kind = TaskHistoryKind.StatusChanged,
                        actor = "Lagoon",
                        time = "09:20",
                        markerStatus = TaskStatus.InProgress,
                        detail = TaskHistoryDetail.Transition(TaskStatus.Todo, TaskStatus.InProgress),
                    ),
                    TaskHeadHistoryRow(
                        id = "e4",
                        kind = TaskHistoryKind.Amended,
                        actor = "Lagoon",
                        time = "09:31",
                        markerStatus = null,
                        detail =
                            TaskHistoryDetail.FieldChanges(
                                listOf(TaskHistoryFieldChange(TaskHistoryField.Title, "Task detail", "Task detail parity with the web"))
                            ),
                    ),
                ),
            ),
    )

/** The More menu's bus: nothing is opened in a still capture. */
private object QuietEventBus : build.raft.app.data.RaftEventBus {
    override fun observe(eventName: String, crossProcess: Boolean, listener: (kotlinx.serialization.json.JsonObject?) -> Unit) =
        build.raft.app.data.RealtimeSubscription {}

    override fun post(eventName: String, eventData: kotlinx.serialization.json.JsonObject, crossProcess: Boolean, source: String) = Unit
}
