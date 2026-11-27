package build.raft.app

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLocale
import build.raft.app.data.RaftEventBus
import build.raft.app.data.RealtimeSubscription
import build.raft.app.data.stringDescLocaleType
import build.raft.app.model.MemberKind
import build.raft.app.model.TaskStatus
import build.raft.app.resources.StringDesc
import build.raft.app.shell.LocalRaftNativeNavBar
import build.raft.app.shell.RaftNativeNavBarBackId
import build.raft.app.shell.RaftNativeNavBarBridge
import build.raft.app.shell.RaftNativeNavBarListener
import build.raft.app.shell.RaftNativeNavBarTitleId
import build.raft.app.shell.RaftNativeTabBarAvatar
import build.raft.app.thread.LocalTaskWaitingOnYouEnabled
import build.raft.app.thread.LocalThreadHeaderMenuEventBus
import build.raft.app.thread.LocalThreadHeaderOriginalState
import build.raft.app.thread.RaftThreadMessageItem
import build.raft.app.thread.RaftThreadScreenPayload
import build.raft.app.thread.ThreadHeaderDisplayModel
import build.raft.app.thread.ThreadHeaderOriginal
import build.raft.app.thread.ThreadHeaderOriginalPullChrome
import build.raft.app.thread.ThreadHeaderOriginalReporter
import build.raft.app.thread.ThreadHeaderOriginalState
import build.raft.app.thread.ThreadHeaderTaskSummaryTestTag
import build.raft.app.thread.ThreadHeaderTaskWaitingTestTag
import build.raft.app.thread.ThreadHeaderTitleChevronTestTag
import build.raft.app.thread.ThreadHeaderTitleTestTag
import build.raft.app.thread.ThreadMessageSourceTaskDoneFoldTestTag
import build.raft.app.thread.ThreadMessageSourceTaskItem
import build.raft.app.thread.ThreadOriginListItemKey
import build.raft.app.thread.ThreadOriginalPullDown
import build.raft.app.thread.ThreadOriginalPullDownEmptyTestTag
import build.raft.app.thread.ThreadOriginalPullDownJumpTestTag
import build.raft.app.thread.ThreadOriginalPullDownTasksTestTag
import build.raft.app.thread.ThreadOriginalPullDownTestTag
import build.raft.app.thread.ThreadOriginalPullDownTextTestTag
import build.raft.app.thread.ThreadOriginalPullState
import build.raft.app.thread.ThreadPageHeader
import build.raft.app.thread.ThreadReplyPageTitle
import build.raft.app.thread.ThreadTransientBackHandler
import build.raft.app.thread.ThreadTransientBackTarget
import build.raft.app.thread.summarizeThreadTasks
import build.raft.app.thread.threadOriginalPullOutsidePress
import build.raft.app.thread.threadTransientBackTarget
import build.raft.app.thread.toThreadTimelineSlice
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Reply thread header task summary and first-message pull-down on a real composition
 * (web botiverse/slock #17200 + mobile Back rule): verifies swapped header summary & chevron,
 * pull-down preview, folded completed tasks toggle, empty-task copy, Jump to this message,
 * Android Back / header Back closing pull-down before leaving the thread, outside tap / user
 * scroll / origin-return close triggers, and iOS native nav bar title/subtitle/chevron publishing.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h640dp")
class ThreadOriginalPullDownUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    @BeforeTest
    fun english() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
    }

    @AfterTest
    fun restore() {
        StringDesc.localeType = previousLocale
    }

    private object QuietEventBus : RaftEventBus {
        override fun observe(eventName: String, crossProcess: Boolean, listener: (JsonObject?) -> Unit) = RealtimeSubscription {}

        override fun post(eventName: String, eventData: JsonObject, crossProcess: Boolean, source: String) = Unit
    }

    private val origin =
        RaftThreadMessageItem(
            id = "msg-1",
            authorId = "agent-1",
            authorName = "Juno",
            authorHandle = "juno",
            authorKind = "agent",
            body = "Can we ship the release today?\nDetails below for mobile and web.",
            timestampLabel = "2026-11-27T12:00:00Z",
        )

    private val mixedTasks =
        listOf(
            ThreadMessageSourceTaskItem(
                taskId = "t1",
                taskNumber = 1,
                title = "Ship build",
                status = TaskStatus.InProgress,
                replyCount = 2,
                assigneeId = "user-viewer",
                assigneeKind = MemberKind.Human,
                assigneeName = "Artin",
                assigneeIsViewer = true,
            ),
            ThreadMessageSourceTaskItem(
                taskId = "t2",
                taskNumber = 2,
                title = "Verify rollout",
                status = TaskStatus.Todo,
                replyCount = 0,
                assigneeId = "agent-1",
                assigneeKind = MemberKind.Agent,
                assigneeName = "Juno",
                assigneeIsViewer = false,
            ),
            ThreadMessageSourceTaskItem(
                taskId = "t3",
                taskNumber = 3,
                title = "Update changelog",
                status = TaskStatus.Done,
                replyCount = 1,
                assigneeId = "agent-1",
                assigneeKind = MemberKind.Agent,
                assigneeName = "Juno",
                assigneeIsViewer = false,
            ),
            ThreadMessageSourceTaskItem(
                taskId = "t4",
                taskNumber = 4,
                title = "Cancel old flag",
                status = TaskStatus.Closed,
                replyCount = 0,
                assigneeId = null,
                assigneeKind = null,
                assigneeName = null,
                assigneeIsViewer = false,
            ),
        )

    private fun payload() =
        RaftThreadScreenPayload(
            instanceId = "reply-thread",
            title = ThreadReplyPageTitle,
            status = "content",
            surfaceKind = RaftThreadSurfaceKind.ReplyThread,
            parentChannelId = "channel-1",
            parentMessageId = "msg-1",
            sourceThreadId = "thread-1",
            originStatus = "loaded",
            originMessage = origin,
        )

    private fun ComposeUiTest.showThread(
        headerOriginalState: ThreadHeaderOriginalState,
        pullState: ThreadOriginalPullState,
        tasks: List<ThreadMessageSourceTaskItem> = mixedTasks,
        waitingShown: Boolean = true,
        nativeBar: RaftNativeNavBarBridge? = null,
        onBackExit: () -> Unit = {},
        onTitleClickFallback: () -> Unit = {},
        onOpenTask: (String) -> Unit = {},
        onJumpToOriginal: () -> Unit = {},
        onDispatcher: (OnBackPressedDispatcher?) -> Unit = {},
    ) {
        val threadPayload = payload()
        setContent {
            onDispatcher(LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher)
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            val summary = remember(tasks, waitingShown) { summarizeThreadTasks(tasks, waitingShown = waitingShown) }
            val shown = headerOriginalState.original as? ThreadHeaderOriginal.Shown
            val available = headerOriginalState.swapped && shown != null && !shown.behindHistory
            SideEffect { pullState.updateAvailable(available) }
            val chrome =
                ThreadHeaderOriginalPullChrome(
                    summary = summary,
                    open = pullState.isOpen,
                    onToggle = pullState::toggle,
                )
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalThreadHeaderMenuEventBus provides QuietEventBus,
                LocalThreadHeaderOriginalState provides headerOriginalState,
                LocalTaskWaitingOnYouEnabled provides waitingShown,
                LocalRaftNativeNavBar provides nativeBar,
            ) {
                RaftDesignSystemTheme {
                    ThreadTransientBackHandler(
                        threadTransientBackTarget(
                            activeModal = null,
                            shareSelectionActive = false,
                            inlineSearchVisible = false,
                            originalPullDownOpen = pullState.isOpen,
                        )
                    ) { target ->
                        if (target == ThreadTransientBackTarget.OriginalPullDown) pullState.close()
                    }
                    Column(
                        Modifier
                            .fillMaxSize()
                            .threadOriginalPullOutsidePress(pullState)
                    ) {
                        ThreadPageHeader(
                            payload = threadPayload,
                            headerDisplay =
                                ThreadHeaderDisplayModel(
                                    title = ThreadReplyPageTitle,
                                    subtitle = "#ops",
                                    surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                                    channelKind = "channel",
                                ),
                            originReplyCount = 48,
                            applyTopSafeArea = false,
                            onBack = {
                                if (!pullState.close()) onBackExit()
                            },
                            nativeBackIntercepts = pullState.isOpen,
                            onOpenInChannel = {},
                            onOpenMemberProfile = {},
                            onOpenSearch = {},
                            onShowMembers = {},
                            onShowSettings = {},
                            onTitleClick = onTitleClickFallback,
                            originalPull = chrome,
                        )
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            val listState = rememberLazyListState()
                            ThreadHeaderOriginalReporter(listState, threadPayload.toThreadTimelineSlice(), null)
                            LazyColumn(
                                state = listState,
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .nestedScroll(pullState.scrollCloseConnection)
                                        .testTag(ListTag),
                            ) {
                                item(key = ThreadOriginListItemKey) {
                                    Box(Modifier.fillMaxWidth().height(120.dp)) { Text("origin row") }
                                }
                                items(30) { index -> Box(Modifier.fillMaxWidth().height(80.dp)) { Text("reply $index") } }
                            }
                            ThreadOriginalPullDown(
                                state = pullState,
                                origin = origin,
                                tasks = tasks,
                                onOpenTask = onOpenTask,
                                onJumpToOriginal = onJumpToOriginal,
                                reducedMotion = true,
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun taskSummaryAndChevronAppearOnlyAfterOriginScrollsAway() =
        runRobolectricComposeUiTest {
            val headerState = ThreadHeaderOriginalState()
            val pullState = ThreadOriginalPullState()
            showThread(headerState, pullState)

            onNodeWithText("Thread").assertExists()
            onNodeWithTag(ThreadHeaderTaskSummaryTestTag, useUnmergedTree = true).assertDoesNotExist()
            onNodeWithTag(ThreadHeaderTitleChevronTestTag, useUnmergedTree = true).assertDoesNotExist()

            onNodeWithTag(ListTag).performScrollToIndex(10)
            waitForIdle()

            onNodeWithTag(ThreadHeaderTitleTestTag, useUnmergedTree = true)
                .assertContentDescriptionEquals("Thread: Juno: Can we ship the release today?")
            onNodeWithTag(ThreadHeaderTitleChevronTestTag, useUnmergedTree = true).assertExists()
            onNodeWithTag(ThreadHeaderTaskSummaryTestTag, useUnmergedTree = true).assertExists()
            onNodeWithText(" · 2 tasks").assertExists()
            onNodeWithTag(ThreadHeaderTaskWaitingTestTag, useUnmergedTree = true).assertExists()
            onNodeWithText("1 waiting on you").assertExists()

            onNodeWithTag(ListTag).performScrollToIndex(0)
            waitForIdle()
            onNodeWithTag(ThreadHeaderTaskSummaryTestTag, useUnmergedTree = true).assertDoesNotExist()
            onNodeWithTag(ThreadHeaderTitleChevronTestTag, useUnmergedTree = true).assertDoesNotExist()
        }

    @Test
    fun tappingTitleOpensPullDownWithFoldedCompletedTasksAndJumpToOriginal() =
        runRobolectricComposeUiTest {
            val headerState = ThreadHeaderOriginalState()
            val pullState = ThreadOriginalPullState()
            var fallbackTitleClicks = 0
            val openedTasks = mutableListOf<String>()
            var jumps = 0
            showThread(
                headerOriginalState = headerState,
                pullState = pullState,
                onTitleClickFallback = { fallbackTitleClicks++ },
                onOpenTask = { openedTasks += it },
                onJumpToOriginal = { jumps++ },
            )

            onNodeWithTag(ListTag).performScrollToIndex(10)
            waitForIdle()
            onNodeWithTag(ThreadOriginalPullDownTestTag).assertDoesNotExist()

            onNodeWithTag(ThreadHeaderTitleTestTag, useUnmergedTree = true).performClick()
            waitForIdle()
            runOnIdle {
                assertTrue(pullState.isOpen)
                assertEquals(0, fallbackTitleClicks)
            }
            onNodeWithTag(ThreadOriginalPullDownTestTag).assertExists()
            onNodeWithTag(ThreadOriginalPullDownTextTestTag).assertExists()
            onNodeWithTag(ThreadOriginalPullDownTasksTestTag).assertExists()

            // Open tasks visible; Done and Closed folded behind "2 completed".
            onNodeWithText("Ship build", useUnmergedTree = true).assertExists()
            onNodeWithText("Verify rollout", useUnmergedTree = true).assertExists()
            onNodeWithTag(ThreadMessageSourceTaskDoneFoldTestTag).assertExists()
            onNodeWithText("2 completed", useUnmergedTree = true).assertExists()
            onNodeWithText("Update changelog", useUnmergedTree = true).assertDoesNotExist()
            onNodeWithText("Cancel old flag", useUnmergedTree = true).assertDoesNotExist()

            // Expand and collapse folded completed rows.
            onNodeWithTag(ThreadMessageSourceTaskDoneFoldTestTag).performClick()
            waitForIdle()
            onNodeWithText("Update changelog", useUnmergedTree = true).assertExists()
            onNodeWithText("Cancel old flag", useUnmergedTree = true).assertExists()
            onNodeWithTag(ThreadMessageSourceTaskDoneFoldTestTag).performClick()
            waitForIdle()
            onNodeWithText("Update changelog", useUnmergedTree = true).assertDoesNotExist()

            // Tapping a task closes the pull-down and opens that task.
            onNodeWithText("Ship build", useUnmergedTree = true).performClick()
            waitForIdle()
            runOnIdle {
                assertFalse(pullState.isOpen)
                assertEquals(listOf("t1"), openedTasks)
            }
            onNodeWithTag(ThreadOriginalPullDownTestTag).assertDoesNotExist()

            // Re-open and tap "Jump to this message".
            onNodeWithTag(ThreadHeaderTitleTestTag, useUnmergedTree = true).performClick()
            waitForIdle()
            onNodeWithTag(ThreadOriginalPullDownJumpTestTag).performClick()
            waitForIdle()
            runOnIdle {
                assertFalse(pullState.isOpen)
                assertEquals(1, jumps)
            }
        }

    @Test
    fun emptyTasksShowsEmptyCopyAndAllDoneShowsAllTasksDoneSummary() =
        runRobolectricComposeUiTest {
            val headerState = ThreadHeaderOriginalState()
            val pullState = ThreadOriginalPullState()
            showThread(headerState, pullState, tasks = emptyList())

            onNodeWithTag(ListTag).performScrollToIndex(10)
            waitForIdle()
            onNodeWithTag(ThreadHeaderTitleChevronTestTag, useUnmergedTree = true).assertExists()
            onNodeWithTag(ThreadHeaderTaskSummaryTestTag, useUnmergedTree = true).assertDoesNotExist()

            onNodeWithTag(ThreadHeaderTitleTestTag, useUnmergedTree = true).performClick()
            waitForIdle()
            onNodeWithTag(ThreadOriginalPullDownEmptyTestTag).assertExists()
            onNodeWithText("No tasks from this message yet").assertExists()
            onNodeWithTag(ThreadOriginalPullDownJumpTestTag).assertExists()
        }

    @Test
    fun allCompletedTasksShowsAllTasksDoneSummaryWithoutFoldingRows() =
        runRobolectricComposeUiTest {
            val headerState = ThreadHeaderOriginalState()
            val pullState = ThreadOriginalPullState()
            val doneOnly = mixedTasks.filter { it.status == TaskStatus.Done || it.status == TaskStatus.Closed }
            showThread(headerState, pullState, tasks = doneOnly)

            onNodeWithTag(ListTag).performScrollToIndex(10)
            waitForIdle()
            onNodeWithText(" · All tasks done").assertExists()
            onNodeWithTag(ThreadHeaderTaskWaitingTestTag, useUnmergedTree = true).assertDoesNotExist()

            onNodeWithTag(ThreadHeaderTitleTestTag, useUnmergedTree = true).performClick()
            waitForIdle()
            onNodeWithTag(ThreadMessageSourceTaskDoneFoldTestTag).assertDoesNotExist()
            onNodeWithText("Update changelog", useUnmergedTree = true).assertExists()
            onNodeWithText("Cancel old flag", useUnmergedTree = true).assertExists()
        }

    @Test
    fun systemBackAndHeaderBackClosePullDownBeforeLeavingThread() =
        runRobolectricComposeUiTest {
            val headerState = ThreadHeaderOriginalState()
            val pullState = ThreadOriginalPullState()
            var backExits = 0
            var dispatcher: OnBackPressedDispatcher? = null
            showThread(
                headerOriginalState = headerState,
                pullState = pullState,
                onBackExit = { backExits++ },
                onDispatcher = { dispatcher = it },
            )

            onNodeWithTag(ListTag).performScrollToIndex(10)
            waitForIdle()
            onNodeWithTag(ThreadHeaderTitleTestTag, useUnmergedTree = true).performClick()
            waitForIdle()
            runOnIdle { assertTrue(pullState.isOpen) }

            // Android system Back closes pull-down first without exiting the thread.
            runOnIdle { assertNotNull(dispatcher, "activity Back dispatcher").onBackPressed() }
            waitForIdle()
            runOnIdle {
                assertFalse(pullState.isOpen)
                assertEquals(0, backExits)
            }
            onNodeWithTag(ThreadOriginalPullDownTestTag).assertDoesNotExist()

            // Header Back button also closes pull-down first without exiting the thread.
            onNodeWithTag(ThreadHeaderTitleTestTag, useUnmergedTree = true).performClick()
            waitForIdle()
            runOnIdle { assertTrue(pullState.isOpen) }
            onNodeWithContentDescription("Back").performClick()
            waitForIdle()
            runOnIdle {
                assertFalse(pullState.isOpen)
                assertEquals(0, backExits)
            }

            // Second header Back leaves the thread once the pull-down is closed.
            onNodeWithContentDescription("Back").performClick()
            waitForIdle()
            runOnIdle { assertEquals(1, backExits) }
        }

    @Test
    fun secondTitleTapOutsideTapUserScrollAndOriginReturnClosePullDown() =
        runRobolectricComposeUiTest {
            val headerState = ThreadHeaderOriginalState()
            val pullState = ThreadOriginalPullState()
            showThread(headerState, pullState)

            onNodeWithTag(ListTag).performScrollToIndex(10)
            waitForIdle()

            // Second tap on title closes.
            onNodeWithTag(ThreadHeaderTitleTestTag, useUnmergedTree = true).performClick()
            waitForIdle()
            runOnIdle { assertTrue(pullState.isOpen) }
            onNodeWithTag(ThreadHeaderTitleTestTag, useUnmergedTree = true).performClick()
            waitForIdle()
            runOnIdle { assertFalse(pullState.isOpen) }

            // Tap below the panel closes via root outside-press modifier.
            onNodeWithTag(ThreadHeaderTitleTestTag, useUnmergedTree = true).performClick()
            waitForIdle()
            runOnIdle { assertTrue(pullState.isOpen) }
            onNodeWithTag(ListTag).performTouchInput { click(Offset(centerX, bottom - 8f)) }
            waitForIdle()
            runOnIdle { assertFalse(pullState.isOpen) }

            // User vertical scroll closes.
            onNodeWithTag(ThreadHeaderTitleTestTag, useUnmergedTree = true).performClick()
            waitForIdle()
            runOnIdle {
                assertTrue(pullState.isOpen)
                pullState.scrollCloseConnection.onPreScroll(Offset(0f, -24f), NestedScrollSource.UserInput)
            }
            waitForIdle()
            runOnIdle { assertFalse(pullState.isOpen) }

            // Origin scrolling back into view closes and stays closed on scrolling away again.
            onNodeWithTag(ThreadHeaderTitleTestTag, useUnmergedTree = true).performClick()
            waitForIdle()
            runOnIdle { assertTrue(pullState.isOpen) }
            onNodeWithTag(ListTag).performScrollToIndex(0)
            waitForIdle()
            runOnIdle { assertFalse(pullState.isOpen) }
            onNodeWithTag(ListTag).performScrollToIndex(10)
            waitForIdle()
            runOnIdle { assertFalse(pullState.isOpen) }
        }

    @Test
    fun nativeNavBarPublishesSwappedTitleTaskSummaryAndChevron() =
        runRobolectricComposeUiTest {
            val headerState = ThreadHeaderOriginalState()
            val pullState = ThreadOriginalPullState()
            val bridge = RaftNativeNavBarBridge()
            var latestTitle: String? = null
            var latestSubtitle: String? = null
            var latestChevron: String? = null
            var latestHasBack = false
            var latestNested = false
            var backExits = 0
            bridge.attachListener(
                object : RaftNativeNavBarListener {
                    override fun onNativeNavBarChanged(
                        title: String,
                        subtitle: String?,
                        actionsJson: String,
                        extrasJson: String,
                        titleMenuJson: String,
                        hasBack: Boolean,
                        nested: Boolean,
                        titleImage: RaftNativeTabBarAvatar?,
                    ) {
                        latestTitle = title
                        latestSubtitle = subtitle
                        latestHasBack = hasBack
                        latestNested = nested
                        latestChevron =
                            Json.parseToJsonElement(extrasJson).jsonObject["chevron"]?.jsonPrimitive?.content
                    }

                    override fun onNativeNavBarCleared() = Unit

                    override fun onNativeNavBarScroll(offset: Double, contentHeight: Double, dragging: Boolean) = Unit
                }
            )
            showThread(headerState, pullState, nativeBar = bridge, onBackExit = { backExits++ })

            runOnIdle {
                assertEquals("Thread", latestTitle)
                assertEquals("#ops · 48 replies", latestSubtitle)
                assertNull(latestChevron)
                assertTrue(latestHasBack)
                assertFalse(latestNested)
            }

            onNodeWithTag(ListTag).performScrollToIndex(10)
            waitForIdle()
            runOnIdle {
                assertEquals("Juno:\u00A0Can we ship the release today?", latestTitle)
                assertEquals("#ops · 48 replies · 2 tasks · 1 waiting on you", latestSubtitle)
                assertEquals("down", latestChevron)
                assertFalse(latestNested)
                assertTrue(bridge.actionTapped(RaftNativeNavBarTitleId))
            }
            waitForIdle()
            runOnIdle {
                assertTrue(pullState.isOpen)
                assertEquals("up", latestChevron)
                assertTrue(latestNested, "Open pull-down must intercept iOS native Back")
                assertTrue(bridge.actionTapped(RaftNativeNavBarBackId))
            }
            waitForIdle()
            runOnIdle {
                assertFalse(pullState.isOpen)
                assertEquals(0, backExits)
                assertEquals("down", latestChevron)
                assertFalse(latestNested, "Closing pull-down must release iOS native Back interception")
                assertTrue(bridge.actionTapped(RaftNativeNavBarTitleId))
            }
            waitForIdle()
            runOnIdle {
                assertTrue(pullState.isOpen)
                assertEquals("up", latestChevron)
                assertTrue(bridge.actionTapped(RaftNativeNavBarTitleId))
            }
            waitForIdle()
            runOnIdle {
                assertFalse(pullState.isOpen)
                assertEquals("down", latestChevron)
                assertFalse(latestNested)
            }
        }

    private companion object {
        const val ListTag = "thread-original-pulldown-list"
    }
}
