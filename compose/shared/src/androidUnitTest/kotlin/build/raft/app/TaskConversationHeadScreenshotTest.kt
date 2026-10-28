package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
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
import build.raft.app.data.TaskHistoryDetail
import build.raft.app.data.TaskHistoryField
import build.raft.app.data.TaskHistoryFieldChange
import build.raft.app.data.TaskHistoryKind
import build.raft.app.data.ThreadAgentFollower
import build.raft.app.data.ThreadAgentFollowerRoster
import build.raft.app.data.ThreadAgentFollowersState
import build.raft.app.data.taskStatusOptions
import build.raft.app.markdown.ThreadMarkdownTagKind
import build.raft.app.model.MemberKind
import build.raft.app.model.TaskStatus
import build.raft.app.resources.*
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Task v2: a task conversation page on a 360x800dp phone (web `TaskConversationDialog`,
 * `mobile-modal`), light and dark: the top bar (the task's ref over "Task #7", its Agent followers,
 * the More menu), the head (title, description folded with Show More, the inline facts, History
 * unfolded), three messages and the composer. On a phone the head scrolls with the conversation;
 * here it is at the top. Then the same page while typing (the keyboard, stood in for by the
 * `fill-strong` block, folds the head away and the top bar names the task's status and assignee),
 * and at 2x text. Verify / record as [RaftRobolectricScreenshot].
 *
 * [TaskConversationHeadFloatScreenshotTest]: the head scrolled away and pulled down from the bar.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class TaskConversationHeadScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    private val livePager =
        object : Pager() {}.apply {
            pagerId = "task-head-screenshot"
            pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
            registerModule(BridgeModule.MODULE_NAME, IModuleCreator { BridgeModule() })
        }

    @BeforeTest fun registerPager() = PagerManager.register(livePager)

    @AfterTest fun unregisterPager() = PagerManager.unregister(livePager)

    @Test
    fun pageLight() = capture("task.conversation.head", RaftAppAppearance.Light) { TaskPage(keyboardUp = false) }

    @Test
    fun pageDark() = capture("task.conversation.head.dark", RaftAppAppearance.Dark) { TaskPage(keyboardUp = false) }

    @Test
    fun keyboardUp() = capture("task.conversation.head.keyboard", RaftAppAppearance.Light) { TaskPage(keyboardUp = true) }

    @Test
    fun largeText() =
        capture("task.conversation.head.font-2x", RaftAppAppearance.Light) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                TaskPage(keyboardUp = false, historyOpen = false)
            }
        }

    /**
     * #proj-mobile:#69 (Kirby): a short conversation (two messages) after a swipe up asked the head
     * away. The list stays bottom-anchored on the composer and the head folds only as far as the
     * conversation fills its place: "Beginning of conversation" sits right under the head, with no
     * blank gap under a folded bar. (This head is taller than its 60% share of the page, so its top,
     * the title, was already out of view; the bar has not summarized because the head is still there.)
     */
    @Test
    fun shortConversation() = capture("task.conversation.head.short", RaftAppAppearance.Light) { ShortTaskPage() }

    private fun capture(name: String, appearance: RaftAppAppearance, content: @Composable () -> Unit) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(livePager),
                LocalThreadHeaderMenuEventBus provides QuietEventBus,
            ) {
                RaftDesignSystemTheme { content() }
            }
        }
}

/** A task with two messages, as ThreadPage lays it out: the list tells the head how far it may fold. */
@Composable
private fun ShortTaskPage() {
    val scrollState = remember { TaskHeadScrollState() }
    // The reader swiped up: the head is asked all the way away.
    androidx.compose.runtime.LaunchedEffect(scrollState.scrollsWithConversation, scrollState.heightPx) {
        if (scrollState.scrollsWithConversation && scrollState.heightPx > 0f) scrollState.collapse()
    }
    val chrome =
        TaskConversationHeaderChrome(
            ref = "#raft-mobile:#7",
            taskNumber = 7,
            status = TaskStatus.InProgress,
            assigneeName = "Lagoon",
            assigneeKind = MemberKind.Agent,
            summarized = scrollState.collapsed,
            followed = true,
            onToggleHeadFloat = {},
            onViewInChannel = {},
            onToggleFollow = {},
        )
    Column(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) {
        TaskHeader(chrome)
        TaskConversationHead(
            model = HeadModel,
            onSelectStatus = {},
            onOpenAssignee = {},
            onOpenSource = {},
            onOpenEarlierDiscussion = {},
            scrollState = scrollState,
        )
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        androidx.compose.foundation.lazy.LazyColumn(
            Modifier.weight(1f).fillMaxWidth().taskHeadConversationOverflow(scrollState, listState),
            state = listState,
            reverseLayout = true,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Bottom,
        ) {
            ShortMessages.reversed().forEach { item -> item(key = item.id) { MessageCard(item) } }
            item(key = "beginning") {
                Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    build.raft.designsystem.text.RaftText(
                        stringResource(Res.string.thread_state_beginning_conversation),
                        style = RaftTheme.typography.caption,
                        color = RaftTheme.colors.foregroundMuted,
                    )
                }
            }
        }
        Composer()
    }
}

private val ShortMessages =
    listOf(
        message("m-s1", 1, "user-artin", "artin", MemberKind.Human, "Can you check the gap above the messages?"),
        message("m-s2", 2, "agent-lagoon", "Lagoon", MemberKind.Agent, "On it."),
    )

@Composable
private fun TaskPage(keyboardUp: Boolean, historyOpen: Boolean = true) {
    val scrollState = remember { TaskHeadScrollState() }
    val chrome =
        TaskConversationHeaderChrome(
            ref = "#raft-mobile:#7",
            taskNumber = 7,
            status = TaskStatus.InProgress,
            assigneeName = "Lagoon",
            assigneeKind = MemberKind.Agent,
            summarized = scrollState.collapsed,
            followed = true,
            onToggleHeadFloat = {},
            onViewInChannel = {},
            onToggleFollow = {},
        )
    Column(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) {
        TaskHeader(chrome)
        TaskConversationHead(
            model = HeadModel,
            onSelectStatus = {},
            onOpenAssignee = {},
            onOpenSource = {},
            onOpenEarlierDiscussion = {},
            scrollState = scrollState,
            keyboardUp = keyboardUp,
            initiallyHistoryOpen = historyOpen,
        )
        Messages()
        Composer()
        // The keyboard's height (the composer's bottom padding on the page).
        if (keyboardUp) Box(Modifier.fillMaxWidth().height(300.dp).background(RaftTheme.colors.fillStrong))
    }
}

@Composable
private fun ColumnScope.Messages(earlier: Boolean = false) {
    // As the page's list: newest at the bottom, older ones cut at the top when they do not fit.
    androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f).fillMaxWidth(), reverseLayout = true) {
        (
            if (earlier) EarlierMessages else emptyList()
        ).plus(
            listOf(
                message("m-1", 1, "agent-lagoon", "Lagoon", MemberKind.Agent, "Pushed the head: on a phone it scrolls away with the conversation."),
                message("m-2", 2, "user-artin", "artin", MemberKind.Human, "Looks right on my phone. Ship it."),
                message("m-3", 3, "agent-stamp", "Stamp", MemberKind.Agent, "PR #3873 is approved."),
            )
        ).reversed().forEach { item ->
            item(key = item.id) { MessageCard(item) }
        }
    }
}

@Composable
private fun MessageCard(item: RaftThreadMessageItem) {
    ThreadMessageCard(
        message = item,
        messageBodyTextStyle = raftMessageBodyTextStyle("md"),
        isMarkdownTagValid = { tag -> tag.kind == ThreadMarkdownTagKind.UserMention },
        timestampLabel = "9:${item.timestampLabel.substring(14, 16)}",
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

/** Earlier messages: once the head has scrolled away the list fills the page. */
private val EarlierMessages =
    listOf(
        message("m-e1", -6, "user-artin", "artin", MemberKind.Human, "Once the head is gone, how do I check the status?"),
        message("m-e2", -5, "agent-lagoon", "Lagoon", MemberKind.Agent, "The bar keeps it: Task #7, the status and the assignee."),
        message("m-e3", -4, "user-artin", "artin", MemberKind.Human, "And the description?"),
        message("m-e4", -3, "agent-lagoon", "Lagoon", MemberKind.Agent, "Tap the bar: the head comes down over the messages."),
        message("m-e5", -2, "user-artin", "artin", MemberKind.Human, "Without losing my place?"),
        message("m-e6", -1, "agent-lagoon", "Lagoon", MemberKind.Agent, "Yes, nothing under it moves."),
    )

private fun message(id: String, seq: Long, authorId: String, author: String, kind: MemberKind, body: String) =
    RaftThreadMessageItem(
        id = id,
        authorId = authorId,
        authorName = author,
        authorKind = if (kind == MemberKind.Agent) "agent" else "human",
        isOwn = false,
        avatarLabel = author.take(1),
        seq = seq,
        timestampLabel = "2026-10-28T09:${30 + seq}:00Z",
        body = body,
    )

@Composable
private fun Composer() {
    ThreadComposerDock {
        ThreadComposerContent(
            payload =
                RaftThreadComposerPayload(
                    instanceId = "task-head-screenshot",
                    placeholder = stringResource(Res.string.task_conversation_composer_placeholder),
                    attachmentActionsEnabled = false,
                    originalImageOptionEnabled = false,
                    showAsTaskToggle = false,
                ),
            onEvent = { _, _, _, _ -> },
            onSendRequest = { ThreadComposerSendAdmission.Rejected },
            attachmentPreviewEnabled = false,
        )
    }
}

@Composable
private fun TaskHeader(chrome: TaskConversationHeaderChrome) {
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
        taskConversationChrome = chrome,
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
        createdAt = TaskHeadDateTime(2026, 10, 28, 9, 14),
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

/**
 * Task v2 on a phone once the head has scrolled away (slock #11722, Kirby's web `TaskHeadPullDown`):
 * the top bar's compact line ("Task #7", the status badge, the assignee's face, a chevron) over
 * the messages, then the head pulled down from it over them (`layer-canvas`, `shadow-float`, the
 * `line-seam` hairline at its bottom; no scrim, the messages stay put). 360dp and 412dp, light and
 * dark. Verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class TaskConversationHeadFloatScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    private val livePager =
        object : Pager() {}.apply {
            pagerId = "task-head-float-screenshot"
            pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
            registerModule(BridgeModule.MODULE_NAME, IModuleCreator { BridgeModule() })
        }

    @BeforeTest fun registerPager() = PagerManager.register(livePager)

    @AfterTest fun unregisterPager() = PagerManager.unregister(livePager)

    @Test
    fun closedLight() = capture("task.conversation.head-float.closed", RaftAppAppearance.Light) { TaskFloatPage(open = false) }

    @Test
    fun closedDark() = capture("task.conversation.head-float.closed.dark", RaftAppAppearance.Dark) { TaskFloatPage(open = false) }

    @Test
    @Config(qualifiers = "w412dp-h800dp-xxhdpi")
    fun closedWide() = capture("task.conversation.head-float.closed.w412", RaftAppAppearance.Light) { TaskFloatPage(open = false) }

    @Test
    fun openLight() = capture("task.conversation.head-float.open", RaftAppAppearance.Light) { TaskFloatPage(open = true) }

    @Test
    fun openDark() = capture("task.conversation.head-float.open.dark", RaftAppAppearance.Dark) { TaskFloatPage(open = true) }

    @Test
    @Config(qualifiers = "w412dp-h800dp-xxhdpi")
    fun openWide() = capture("task.conversation.head-float.open.w412", RaftAppAppearance.Light) { TaskFloatPage(open = true) }

    private fun capture(name: String, appearance: RaftAppAppearance, content: @Composable () -> Unit) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(livePager),
                LocalThreadHeaderMenuEventBus provides QuietEventBus,
            ) {
                RaftDesignSystemTheme { content() }
            }
        }
}

/** The page as ThreadPage lays it out for a task: the head and the messages in a box the pulled-down head hangs in. */
@Composable
private fun TaskFloatPage(open: Boolean) {
    val scrollState = remember { TaskHeadScrollState() }
    // Scrolled away, as after reading down the conversation; then the bar is tapped.
    androidx.compose.runtime.LaunchedEffect(scrollState.scrollsWithConversation) {
        if (scrollState.scrollsWithConversation) scrollState.collapse()
    }
    androidx.compose.runtime.LaunchedEffect(scrollState.collapsed) {
        if (open && scrollState.collapsed && !scrollState.floatOpen) scrollState.toggleFloat()
    }
    val chrome =
        TaskConversationHeaderChrome(
            ref = "#raft-mobile:#7",
            taskNumber = 7,
            status = TaskStatus.InProgress,
            assigneeName = "Lagoon",
            assigneeKind = MemberKind.Agent,
            summarized = scrollState.collapsed,
            headFloatOpen = scrollState.floatOpen,
            followed = true,
            onToggleHeadFloat = { scrollState.toggleFloat() },
            onViewInChannel = {},
            onToggleFollow = {},
        )
    Column(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) {
        TaskHeader(chrome)
        Box(Modifier.weight(1f).fillMaxWidth().taskHeadFloatOutsidePress(scrollState)) {
            Column(Modifier.fillMaxSize()) {
                TaskConversationHead(
                    model = HeadModel,
                    onSelectStatus = {},
                    onOpenAssignee = {},
                    onOpenSource = {},
                    onOpenEarlierDiscussion = {},
                    scrollState = scrollState,
                )
                Messages(earlier = true)
            }
            TaskHeadFloat(scrollState = scrollState, label = "Task #7", reducedMotion = true) {
                TaskHeadContent(model = HeadModel, onOpenSource = {}, onOpenEarlierDiscussion = {})
            }
        }
        Composer()
    }
}
