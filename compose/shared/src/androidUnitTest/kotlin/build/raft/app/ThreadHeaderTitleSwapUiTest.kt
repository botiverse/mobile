package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
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
import build.raft.app.resources.StringDesc
import build.raft.app.settings.observe
import build.raft.app.thread.LocalThreadHeaderMenuEventBus
import build.raft.app.thread.LocalThreadHeaderOriginalState
import build.raft.app.thread.RaftThreadMessageItem
import build.raft.app.thread.RaftThreadScreenPayload
import build.raft.app.thread.ThreadHeaderDisplayModel
import build.raft.app.thread.ThreadHeaderOriginalReporter
import build.raft.app.thread.ThreadHeaderOriginalState
import build.raft.app.thread.ThreadHeaderTitleTestTag
import build.raft.app.thread.ThreadOriginListItemKey
import build.raft.app.thread.ThreadPageHeader
import build.raft.app.thread.ThreadReplyPageTitle
import build.raft.app.thread.key
import build.raft.app.thread.toThreadTimelineSlice
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The reply thread head's title on a real composition (raft-design conversation.md "Threads", web
 * botiverse/slock #15285): "Thread" while the original's row is in view; "<sender>: <first line>"
 * once the list scrolls it away; "Thread" again when it scrolls back. The list reports through the
 * production [ThreadHeaderOriginalReporter] and the production [ThreadPageHeader] reads it.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h600dp")
class ThreadHeaderTitleSwapUiTest {
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
            body = "Can we ship the release today?\nDetails below",
        )

    private fun payload(originStatus: String = "loaded", originMessage: RaftThreadMessageItem? = origin) =
        RaftThreadScreenPayload(
            instanceId = "reply-thread",
            title = ThreadReplyPageTitle,
            status = "content",
            surfaceKind = RaftThreadSurfaceKind.ReplyThread,
            parentChannelId = "channel-1",
            parentMessageId = "msg-1",
            sourceThreadId = "thread-1",
            originStatus = originStatus,
            originMessage = originMessage,
        )

    private fun ComposeUiTest.showThread(
        state: ThreadHeaderOriginalState,
        payload: RaftThreadScreenPayload = payload(),
        originErrorCode: String? = null,
        onTitleClick: () -> Unit = {},
        onOpenInChannel: () -> Unit = {},
    ) {
        setContent {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalThreadHeaderMenuEventBus provides QuietEventBus,
                LocalThreadHeaderOriginalState provides state,
            ) {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize()) {
                        ThreadPageHeader(
                            payload = payload,
                            headerDisplay =
                                ThreadHeaderDisplayModel(
                                    title = ThreadReplyPageTitle,
                                    subtitle = "#general",
                                    surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                                    channelKind = "channel",
                                ),
                            originReplyCount = 30,
                            applyTopSafeArea = false,
                            onBack = {},
                            onOpenInChannel = onOpenInChannel,
                            onOpenMemberProfile = {},
                            onOpenSearch = {},
                            onShowMembers = {},
                            onShowSettings = {},
                            onTitleClick = onTitleClick,
                        )
                        val listState = rememberLazyListState()
                        ThreadHeaderOriginalReporter(listState, payload.toThreadTimelineSlice(), originErrorCode)
                        LazyColumn(state = listState, modifier = Modifier.weight(1f).testTag(ListTag)) {
                            item(key = ThreadOriginListItemKey) {
                                Box(Modifier.fillMaxWidth().height(120.dp)) { Text("origin row") }
                            }
                            items(30) { index -> Box(Modifier.fillMaxWidth().height(80.dp)) { Text("reply $index") } }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun theTitleNamesTheOriginalOnlyWhileItIsScrolledAway() =
        runRobolectricComposeUiTest {
            val state = ThreadHeaderOriginalState()
            showThread(state)
            val title = onNodeWithTag(ThreadHeaderTitleTestTag, useUnmergedTree = true)
            onNodeWithText("Thread").assertExists()
            title.assertContentDescriptionEquals("Thread")
            runOnIdle { assertFalse(state.swapped) }

            onNodeWithTag(ListTag).performScrollToIndex(10)
            waitForIdle()
            runOnIdle { assertTrue(state.swapped) }
            // The sender and the first line only; no Agent tag; named with "Thread:" before them.
            // The separator is ":" and a no-break space (thread_title_original_separator).
            onNodeWithText("Juno:\u00A0Can we ship the release today?").assertExists()
            title.assertContentDescriptionEquals("Thread: Juno: Can we ship the release today?")
            // The second line is unchanged.
            onNodeWithText("#general").assertExists()

            onNodeWithTag(ListTag).performScrollToIndex(0)
            waitForIdle()
            runOnIdle { assertFalse(state.swapped) }
            onNodeWithText("Thread").assertExists()
        }

    @Test
    fun tappingTheTitleAsksTheThreadToScrollBackToTheOriginal() =
        runRobolectricComposeUiTest {
            val state = ThreadHeaderOriginalState()
            var taps = 0
            showThread(state, onTitleClick = { taps++ })
            onNodeWithTag(ListTag).performScrollToIndex(10)
            waitForIdle()
            onNodeWithTag(ThreadHeaderTitleTestTag, useUnmergedTree = true).performClick()
            runOnIdle { assertEquals(1, taps) }
        }

    private companion object {
        const val ListTag = "thread-title-swap-list"
    }
}
