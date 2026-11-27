package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.InboxItem
import build.raft.app.data.SharedHomeSavedPanelState
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberKind
import build.raft.app.model.MessageId
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.app.resources.*
import build.raft.app.thread.key
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A saved reply in a phone's column (web SavedItem, slock #10867): a long channel name and a long
 * sender both give way with an ellipsis, and the Thread mark and the time stay whole, before the
 * row's remove button. Drives the real [SharedHomeSavedPanel].
 */
@OptIn(ExperimentalTestApi::class)
// Real text measurement: the legacy graphics mode lays glyphs out with next to no width.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp")
class SavedRowMetaLineUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    /** 2026-11-05T12:00:00Z. */
    private val timeContext =
        RaftTimeContext(localOffsetMinutes = 0, timeFormat = "24h")
            .refreshedAt(parseRaftTimestampEpochMillisOrNull("2026-11-05T12:00:00Z")!!)

    /** A reply saved from a thread, as the saved list's parser builds it. */
    private val savedReply =
        InboxItem(
            key = "saved:msg-1",
            kind = InboxItemKind.Thread,
            channelId = ChannelId("thread-1"),
            channelName = "thread-1",
            channelKind = ConversationKind.Thread,
            lastMessageId = MessageId("msg-1"),
            latestActivityMessageId = MessageId("msg-1"),
            lastMessagePreview = "The release notes are ready for a last read.",
            lastMessageAt = "2026-11-05T10:00:00Z",
            lastMessageSenderName = LONG_SENDER,
            lastMessageSenderKind = MemberKind.Human,
            threadChannelId = ChannelId("thread-1"),
            parentChannelId = ChannelId("channel-1"),
            parentChannelName = LONG_CHANNEL,
            parentChannelKind = ConversationKind.Channel,
            parentMessageId = MessageId("parent-1"),
        )

    @Test
    fun aLongChannelAndSenderGiveWaySoTheThreadMarkAndTheTimeStayWhole() =
        runRobolectricComposeUiTest {
            build.raft.app.data.RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
            var threadLabel = ""
            var timeLabel = ""
            var removeLabel = ""
            setContent {
                val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
                CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                    RaftDesignSystemTheme {
                        threadLabel = stringResource(Res.string.saved_meta_thread)
                        timeLabel = raftRelativeTimestampLabel(savedReply.lastMessageAt, timeContext)
                        removeLabel = stringResource(Res.string.saved_action_remove)
                        Box(Modifier.fillMaxSize()) {
                            SharedHomeSavedPanel(
                                state = SharedHomeSavedPanelState(items = listOf(savedReply), totalCount = 1, loaded = true),
                                onIntent = {},
                                onClose = {},
                                onOpenItem = {},
                                bottomContentPadding = 0.dp,
                                includeTopSafeArea = false,
                                timeContext = timeContext,
                            )
                        }
                    }
                }
            }
            waitForIdle()
            val channel = textNode("#$LONG_CHANNEL")
            val thread = textNode(threadLabel)
            val sender = textNode(LONG_SENDER)
            val time = textNode(timeLabel)
            val remove = onNodeWithContentDescription(removeLabel, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

            // Both long names give way together: each is cut, and neither takes the other's room.
            assertTrue(channel.textLayout().isLineEllipsized(0), "the long channel name ends in an ellipsis")
            assertTrue(sender.textLayout().isLineEllipsized(0), "the long sender ends in an ellipsis")
            assertTrue(channel.boundsInRoot.width > 0f && sender.boundsInRoot.width > 0f, "both names keep a part of themselves")
            // The Thread mark and the time are whole, side by side, before the remove button. Whole =
            // laid out at least as wide as the text's natural width, with no ellipsis. (Not
            // `didOverflowWidth`: the semantics layout re-lays the text out at its max width, so a
            // short text that doesn't fill its constraint reads as overflowing.)
            listOf("Thread mark" to thread, "time" to time).forEach { (part, node) ->
                val layout = node.textLayout()
                val natural = ceil(layout.multiParagraph.intrinsics.maxIntrinsicWidth).toInt()
                assertTrue(
                    !layout.isLineEllipsized(0) && layout.size.width >= natural,
                    "the $part is shown whole: ${layout.size.width}px laid out for ${natural}px of text"
                )
            }
            assertTrue(channel.boundsInRoot.right <= thread.boundsInRoot.left, "the Thread mark starts after the channel")
            assertTrue(thread.boundsInRoot.right <= sender.boundsInRoot.left, "the sender starts after the Thread mark")
            assertTrue(sender.boundsInRoot.right <= time.boundsInRoot.left, "the time starts after the sender")
            assertTrue(time.boundsInRoot.right <= remove.left, "the time ends before the remove button")
        }

    private fun ComposeUiTest.textNode(text: String): SemanticsNode =
        onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()

    private fun SemanticsNode.textLayout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single()
    }

    private companion object {
        const val LONG_CHANNEL = "release-train-coordination-and-launch-readiness-review"
        const val LONG_SENDER = "Alexandra Konstantinopoulou-Vanderbilt"
    }
}
