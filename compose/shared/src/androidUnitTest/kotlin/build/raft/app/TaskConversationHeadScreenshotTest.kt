package build.raft.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import build.raft.designsystem.components.RaftMenuSurface
import kotlin.math.roundToInt
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
 * `mobile-modal`) as it opens, light and dark. On a phone the head is the conversation's first row
 * (artin 2026-11-06, #proj-mobile:ec53b56a): a short conversation opens with it above the messages
 * (title, description folded with Show More, the inline facts, History), "Beginning of
 * conversation" under it, the messages bottom-anchored on the composer; the top bar is the task's
 * ref over "Task #7". Then the same page while typing (the keyboard, stood in for by the
 * `fill-strong` block: the list keeps its newest message on the composer, the head goes up with
 * the rest), at 2x text, and with two short replies. Verify / record as [RaftRobolectricScreenshot].
 *
 * `task.conversation.head.scroll.1..4`: an eight-message conversation from its newest message
 * (1) back to its top (4) in thirds, the head coming down with the messages and the bar
 * summarizing the task until the head is back in view: a frame strip of the scroll.
 *
 * [TaskConversationHeadFloatScreenshotTest]: a conversation longer than the page, its head out of
 * view, and pulled down from the bar.
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
    fun pageLight() = capture("task.conversation.head", RaftAppAppearance.Light) { TaskPhonePage(ThreeMessages) }

    @Test
    fun pageDark() = capture("task.conversation.head.dark", RaftAppAppearance.Dark) { TaskPhonePage(ThreeMessages) }

    @Test
    fun keyboardUp() =
        capture("task.conversation.head.keyboard", RaftAppAppearance.Light) { TaskPhonePage(ThreeMessages, keyboardUp = true) }

    @Test
    fun largeText() =
        capture("task.conversation.head.font-2x", RaftAppAppearance.Light) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                TaskPhonePage(ThreeMessages)
            }
        }

    /** Two short replies: the head, "Beginning of conversation" and the replies, all in view. */
    @Test
    fun shortConversation() = capture("task.conversation.head.short", RaftAppAppearance.Light) { TaskPhonePage(ShortMessages) }

    /** Eight messages, from the newest one back to the head on top, in thirds (a frame strip of the scroll). */
    @Test
    fun scroll1() = capture("task.conversation.head.scroll.1", RaftAppAppearance.Light) { TaskPhonePage(EightMessages, scrolledTowardsTop = 0f) }

    @Test
    fun scroll2() =
        capture("task.conversation.head.scroll.2", RaftAppAppearance.Light) { TaskPhonePage(EightMessages, scrolledTowardsTop = 1f / 3f) }

    @Test
    fun scroll3() =
        capture("task.conversation.head.scroll.3", RaftAppAppearance.Light) { TaskPhonePage(EightMessages, scrolledTowardsTop = 2f / 3f) }

    @Test
    fun scroll4() = capture("task.conversation.head.scroll.4", RaftAppAppearance.Light) { TaskPhonePage(EightMessages, scrolledTowardsTop = 1f) }

    /**
     * Source and Created by draw their faces through RaftMemberAvatar: an agent's pixel avatar (no
     * URL) on Source, and an agent with no avatar of its own wearing the robot on Created by (both
     * drew initials, or nothing, before).
     */
    @Test
    fun factFaces() =
        capture("task.conversation.head.faces", RaftAppAppearance.Light) {
            Column(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas).padding(16.dp)) {
                TaskHeadContent(model = FacesHeadModel)
            }
        }

    /**
     * The Status chip tapped (artin, #proj-apps #35: nothing happened): its menu under the chip, as
     * RaftDropdownMenu places it (6dp under the chip's box), every status with the current one
     * checked. A popup is its own window, so the menu's surface is drawn in place.
     */
    @Test
    fun statusMenu() =
        capture("task.conversation.head.status-menu", RaftAppAppearance.Light) {
            var menuAt by remember { mutableStateOf<IntOffset?>(null) }
            val gap = with(LocalDensity.current) { 6.dp.roundToPx() }
            // RaftDropdownMenu's place: under the chip's box, start-aligned, 6dp down.
            val anchor: (LayoutCoordinates) -> Unit =
                remember(gap) {
                    { box ->
                        val at = box.positionInRoot()
                        menuAt = IntOffset(at.x.roundToInt(), at.y.roundToInt() + box.size.height + gap)
                    }
                }
            Box(Modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalTaskHeadStatusMenuAnchor provides anchor) {
                    TaskPhonePage(ThreeMessages)
                }
                menuAt?.let { at ->
                    RaftMenuSurface(Modifier.offset { at }, minWidth = TaskHeadStatusMenuMinWidth) {
                        TaskHeadStatusMenuRows(HeadModel.bar) {}
                    }
                }
            }
        }

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

private const val HeadRowKey = "task-head-row"

/**
 * A task conversation page on a phone as ThreadPage lays it out: the top bar; the head above the
 * messages ([TaskConversationHead], no room on a phone: it tells the state the page is a phone's,
 * and the keyboard puts the panel up); the [messages] newest at the bottom with the head as their
 * first row, over "Beginning of conversation" (ThreadMessagesListContent's history boundary row),
 * the bar summarizing the task while that row is out of view; the head pulled down from the bar
 * over them; the composer. [scrolledTowardsTop]: how far back towards the head the reader has
 * scrolled (0: the newest message, as it opens; 1: the top). [pulledDown]: the reader pulled the
 * head down from the bar once it was out of view. [keyboardUp]: typing.
 */
@Composable
private fun TaskPhonePage(
    messages: List<RaftThreadMessageItem>,
    keyboardUp: Boolean = false,
    historyOpen: Boolean = false,
    pulledDown: Boolean = false,
    scrolledTowardsTop: Float = 0f,
) {
    val scrollState = remember { TaskHeadScrollState() }
    val listState = rememberLazyListState()
    // As ThreadMessagesListContent: whether any of the head in its row is on screen.
    LaunchedEffect(listState, scrollState) {
        val viewport = ThreadListViewport(listState, reverse = true)
        snapshotFlow {
            val info = viewport.layoutInfo
            val row = info.visibleItemsInfo.firstOrNull { it.key == HeadRowKey }
            taskHeadRowInView(
                rowOffsetPx = row?.offset,
                rowSizePx = row?.size ?: 0,
                headHeightPx = scrollState.rowHeadHeightPx,
                viewportStartPx = info.viewportStartOffset,
                viewportEndPx = info.viewportEndOffset,
            )
        }.collect { inView -> scrollState.updateHeadInView(inView) }
    }
    LaunchedEffect(listState, scrolledTowardsTop) {
        if (scrolledTowardsTop <= 0f) return@LaunchedEffect
        withFrameNanos {}
        // Reversed: a positive scroll goes back towards the top. All the way, then the rest back down.
        val range = listState.scrollBy(1_000_000f)
        if (scrolledTowardsTop < 1f) listState.scrollBy(-range * (1f - scrolledTowardsTop))
    }
    // The reader taps the bar once the head is out of view.
    LaunchedEffect(scrollState.collapsed, pulledDown) {
        if (pulledDown && scrollState.collapsed && !scrollState.floatOpen) scrollState.toggleFloat()
    }
    val headRow =
        rememberTaskConversationHeadRow(scrollState) {
            TaskHeadFrame {
                TaskHeadContent(model = HeadModel, onOpenSource = {}, onOpenEarlierDiscussion = {}, initiallyHistoryOpen = historyOpen)
            }
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
            Column(Modifier.fillMaxSize().nestedScroll(scrollState.connection)) {
                TaskConversationHead(
                    model = HeadModel,
                    onSelectStatus = {},
                    onOpenAssignee = {},
                    onOpenSource = {},
                    onOpenEarlierDiscussion = {},
                    scrollState = scrollState,
                    keyboardUp = keyboardUp,
                )
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    state = listState,
                    reverseLayout = true,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    messages.reversed().forEach { item -> item(key = item.id) { MessageCard(item) } }
                    // Reversed: the last row is the top one, where the conversation begins.
                    item(key = HeadRowKey) {
                        Column(Modifier.fillMaxWidth()) {
                            TaskConversationHeadRowContent(headRow)
                            ThreadHistoryStatusRow(threadOlderMessagesStatusSpec(canLoadMoreBefore = false))
                        }
                    }
                }
            }
            TaskHeadFloat(scrollState = scrollState, label = "Task #7", reducedMotion = true) {
                TaskHeadContent(model = HeadModel, onOpenSource = {}, onOpenEarlierDiscussion = {}, initiallyHistoryOpen = historyOpen)
            }
        }
        Composer()
        // The keyboard's height (the composer's bottom padding on the page).
        if (keyboardUp) Box(Modifier.fillMaxWidth().height(300.dp).background(RaftTheme.colors.fillStrong))
    }
}

private val ShortMessages =
    listOf(
        message("m-s1", 1, "user-artin", "artin", MemberKind.Human, "Can you check the status line?"),
        message("m-s2", 2, "agent-lagoon", "Lagoon", MemberKind.Agent, "On it."),
    )

private val ThreeMessages =
    listOf(
        message("m-1", 1, "agent-lagoon", "Lagoon", MemberKind.Agent, "Pushed the head: on a phone it is the conversation's first row."),
        message("m-2", 2, "user-artin", "artin", MemberKind.Human, "Looks right on my phone. Ship it."),
        message("m-3", 3, "agent-stamp", "Stamp", MemberKind.Agent, "PR #3873 is approved."),
    )

/** Eight messages: a little longer than the page, for the scroll strip. */
private val EightMessages
    get() = EarlierMessages.takeLast(5) + ThreeMessages

/** A conversation well past the page: its head far out of view at the newest message. */
private val LongMessages
    get() =
        (1..12).map { index ->
            if (index % 2 == 1) {
                message("m-l$index", -20L + index, "user-artin", "artin", MemberKind.Human, "Earlier question $index about the task head.")
            } else {
                message("m-l$index", -20L + index, "agent-lagoon", "Lagoon", MemberKind.Agent, "Earlier answer $index: it is the first row.")
            }
        } + EarlierMessages + ThreeMessages

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

/** Earlier messages: a conversation longer than the page. */
private val EarlierMessages =
    listOf(
        message("m-e1", -6, "user-artin", "artin", MemberKind.Human, "Once the head is up, how do I check the status?"),
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

private val FacesHeadModel
    get() =
        HeadModel.copy(
            description = null,
            source =
                TaskHeadSource(
                    authorName = "Niko",
                    authorKind = MemberKind.Agent,
                    authorAvatarWireValue = "pixel:antenna",
                    preview = "Avatars render as initials on the task page",
                    time = "09:12",
                    authorId = "agent-niko",
                ),
            createdBy = "Kirby",
            creator = TaskHeadPerson(MemberKind.Agent, "agent-kirby"),
            history = TaskHeadHistory.Rows(emptyList()),
        )

private fun follower(id: String, name: String) =
    ThreadAgentFollower(id = id, name = name, status = "active", serverId = "server-1", isCurrentServer = true, canRemove = true)

private val HeadModel =
    TaskConversationHeadModel(
        bar =
            TaskConversationHeaderBarModel(
                status = TaskStatus.InProgress,
                statusOptions = taskStatusOptions(TaskStatus.InProgress, v2Task = true),
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
                authorId = "user-artin",
            ),
        createdBy = "artin",
        creator = TaskHeadPerson(MemberKind.Human, "user-artin"),
        avatarServerId = "server-1",
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
 * Task v2 on a phone, a conversation longer than the page (slock #11722, Kirby's web
 * `TaskHeadPullDown`): it opens at its newest message, the head's row far out of view above, so the
 * top bar summarizes the task ("Task #7", the status badge, the assignee's face, a chevron) and
 * nothing is pulled down; then the head pulled down from the bar over the messages by the reader
 * (`layer-canvas`, `shadow-float`, the `line-seam` hairline at its bottom; no scrim, the messages
 * stay put). 360dp and 412dp, light and dark. Verify / record as [RaftRobolectricScreenshot].
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

/** A conversation longer than the page as it opens ([open] false), and with the head pulled down from the bar. */
@Composable
private fun TaskFloatPage(open: Boolean) {
    TaskPhonePage(LongMessages, historyOpen = true, pulledDown = open)
}
