package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.data.RaftThreadOutlineApi
import build.raft.app.data.ThreadOutlineItem
import build.raft.app.data.ThreadOutlinePage
import build.raft.designsystem.theme.RaftPointerInput
import build.raft.designsystem.theme.RaftScale
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The thread timeline on a real composition (web conversationOutline.behavior.test.tsx): a touch
 * tablet taps the rail to open the list and a row to jump; on a phone the reply thread's ⋯ has
 * Timeline, which opens the sheet, and a row jumps and closes it; below four messages from people
 * the ⋯ has no Timeline. The header and the outline are wired as ThreadPageContentContent wires them.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThreadOutlineRailUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    /** The server's page: the thread's first message and [replies] replies from people, newest first. */
    private class FakeApi(private val replies: Int = 4) : RaftThreadOutlineApi {
        var reads = 0

        override suspend fun page(channelId: String, beforeSeq: Long?): ThreadOutlinePage {
            reads++
            val rows = (1..replies).map { n ->
                ThreadOutlineItem(id = "r$n", seq = 10L + n, createdAt = "2026-11-08T06:0$n:00.000Z", senderId = "u$n", senderName = "Ann", preview = "reply $n")
            }
            val lead = ThreadOutlineItem(id = "lead", seq = 900, createdAt = "2026-11-08T05:00:00.000Z", senderId = "u1", senderName = "Ann", preview = "the question")
            return ThreadOutlinePage(items = rows.reversed() + lead, hasMore = false)
        }
    }

    private object QuietEventBus : build.raft.app.data.RaftEventBus {
        override fun observe(eventName: String, crossProcess: Boolean, listener: (kotlinx.serialization.json.JsonObject?) -> Unit) =
            build.raft.app.data.RealtimeSubscription {}

        override fun post(eventName: String, eventData: kotlinx.serialization.json.JsonObject, crossProcess: Boolean, source: String) = Unit
    }

    /** The thread's first message as the page holds it once loaded. */
    private val origin =
        RaftThreadMessageItem(id = "lead", authorId = "agent-1", authorName = "Otter", authorKind = "agent", body = "the question", seq = 900)

    private fun ComposeUiTest.showThread(
        api: FakeApi,
        scale: RaftScale,
        jumps: MutableList<String>,
        originMessage: RaftThreadMessageItem? = null,
    ) =
        setContent {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalThreadOutlineApi provides api,
                LocalThreadHeaderMenuEventBus provides QuietEventBus,
                LocalThreadActionCardSpecHost provides ThreadActionCardSpecHost(viewerUserId = "u1"),
            ) {
                RaftTheme(scale = scale, pointerInput = RaftPointerInput.Touch) {
                    val outline = rememberThreadOutlineState("thread-1")
                    LaunchedEffect(outline) { outline?.loadNewest() }
                    Column(Modifier.fillMaxSize()) {
                        ThreadPageHeader(
                            payload = remember { RaftThreadScreenPayload(instanceId = "thread-1", surfaceKind = RaftThreadSurfaceKind.ReplyThread, status = "content") },
                            headerDisplay = ThreadHeaderDisplayModel(title = "Thread", subtitle = null, surfaceKind = RaftThreadSurfaceKind.ReplyThread, channelKind = "channel"),
                            applyTopSafeArea = false,
                            onBack = {},
                            onOpenInChannel = {},
                            onOpenMemberProfile = {},
                            onOpenSearch = {},
                            onShowMembers = {},
                            onShowSettings = {},
                            onOpenTimeline = outline?.openTimelineAction(leadingMessageId = "lead", origin = originMessage, messages = emptyList()),
                        )
                        Box(Modifier.weight(1f)) {
                            ThreadOutlineHost(
                                state = outline,
                                leadingMessageId = "lead",
                                origin = originMessage,
                                messages = emptyList(),
                                viewport = { ThreadOutlineViewport(emptyList()) },
                                jumpTargetId = null,
                                jumpNonce = 0,
                                listWidth = if (scale == RaftScale.Wide) 600.dp else 390.dp,
                                formatTime = { "06:00" },
                                onJump = { jumps += it },
                            )
                        }
                    }
                }
            }
        }

    @Test
    fun aTouchTabletTapsTheRailToOpenTheListAndARowToJumpThere() =
        runRobolectricComposeUiTest {
            val jumps = mutableListOf<String>()
            showThread(FakeApi(), RaftScale.Wide, jumps)
            onNodeWithTag(ThreadOutlineCardTestTag).assertDoesNotExist()
            onNodeWithTag(ThreadOutlineRailTestTag).assertExists().performClick()
            onNodeWithTag(ThreadOutlineCardTestTag).assertExists()
            onNodeWithTag(threadOutlineRowTestTag("r2")).assertExists().performClick()
            assertEquals(listOf("r2"), jumps)
            // A finger's list closes on the jump.
            onNodeWithTag(ThreadOutlineCardTestTag).assertDoesNotExist()
            onNodeWithTag(ThreadOutlineRailTestTag).assertExists()
        }

    @Test
    fun onAPhoneTheMenusTimelineOpensTheSheetAndARowJumpsAndClosesIt() =
        runRobolectricComposeUiTest {
            val jumps = mutableListOf<String>()
            showThread(FakeApi(), RaftScale.Compact, jumps)
            onNodeWithTag(ThreadOutlineRailTestTag).assertDoesNotExist()
            onNodeWithContentDescription("Thread panel menu").performClick()
            onNodeWithText("Timeline").assertExists().performClick()
            onNodeWithTag(ThreadOutlineSheetTestTag).assertExists()
            onNodeWithTag(threadOutlineSheetRowTestTag("r3")).assertExists().performClick()
            assertEquals(listOf("r3"), jumps)
            onNodeWithTag(ThreadOutlineSheetTestTag).assertDoesNotExist()
        }

    @Test
    fun belowFourMessagesFromPeopleTheMenuHasNoTimeline() =
        runRobolectricComposeUiTest {
            val api = FakeApi(replies = 3)
            showThread(api, RaftScale.Compact, mutableListOf())
            runOnIdle { assertEquals(1, api.reads) }
            // The thread may have no ⋯ at all without Timeline; where it has one, Timeline isn't in it.
            val menus = onAllNodesWithContentDescription("Thread panel menu").fetchSemanticsNodes()
            if (menus.isNotEmpty()) onNodeWithContentDescription("Thread panel menu").performClick()
            onNodeWithText("Timeline").assertDoesNotExist()
        }

    @Test
    fun theFirstMessagesRowJumpsToIt() =
        runRobolectricComposeUiTest {
            val jumps = mutableListOf<String>()
            showThread(FakeApi(), RaftScale.Wide, jumps, originMessage = origin)
            onNodeWithTag(ThreadOutlineRailTestTag).performClick()
            // The thread's first message is the list's first row, whoever wrote it.
            onNodeWithTag(threadOutlineRowTestTag("lead")).assertExists().performClick()
            assertEquals(listOf("lead"), jumps)
        }
}
