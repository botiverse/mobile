package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.data.ActionCardSpecMsg
import build.raft.app.data.ActionCardSpecMsgParam
import build.raft.app.data.InboxFilter
import build.raft.app.data.SharedConversationDirectoryState
import build.raft.app.data.SharedToAnswerState
import build.raft.app.data.ToAnswerAddressee
import build.raft.app.data.ToAnswerCardKind
import build.raft.app.data.ToAnswerConversation
import build.raft.app.data.ToAnswerConversationParent
import build.raft.app.data.ToAnswerEnded
import build.raft.app.data.ToAnswerItem
import build.raft.app.data.ToAnswerRequester
import build.raft.app.data.ToAnswerRequesterType
import build.raft.app.data.ToAnswerStatus
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MessageId
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.RaftTimeFormat24Hour
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.designsystem.components.RaftMenuSurface
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftPointerInput
import build.raft.designsystem.theme.RaftScale
import build.raft.designsystem.theme.RaftTheme
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Robolectric UI tests for Activity's "To answer" filter choice & list (`to_answer_list`,
 * botiverse/slock #17252 / #17261): flag-off suppression, filter menu placement after Mentions with
 * live count, active chip with count, hidden Mark all read, row hierarchy (question / confirm /
 * request / ended linger / empty / error+retry), and tap-to-open navigation.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class ActivityToAnswerUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val nowContext =
        RaftTimeContext(
            localOffsetMinutes = 0,
            nowEpochMillis = requireNotNull(parseRaftTimestampEpochMillisOrNull("2026-11-27T10:05:00Z", 0)),
            timeFormat = RaftTimeFormat24Hour,
        )

    private fun ComposeUiTest.setActivityContent(content: @androidx.compose.runtime.Composable () -> Unit) {
        build.raft.app.data.RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
        setContent {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftTheme(
                    appearance = RaftAppearance.Light,
                    scale = RaftScale.Compact,
                    pointerInput = RaftPointerInput.Touch,
                ) {
                    content()
                }
            }
        }
    }

    @Test
    fun flagOffHidesToAnswerFromMenuAndChipAndKeepsMarkAllRead() =
        runRobolectricComposeUiTest {
            setActivityContent {
                Column {
                    SharedActivityFilterBar(
                        selectedFilter = InboxFilter.All,
                        totalUnreadCount = 3,
                        onFilterSelected = {},
                        onMarkAllRead = {},
                        toAnswerEnabled = false,
                        toAnswerSelected = true,
                        toAnswerCount = 4,
                    )
                    RaftMenuSurface {
                        ActivityFilterMenuRows(
                            selectedFilter = InboxFilter.All,
                            onPick = {},
                            toAnswerEnabled = false,
                            toAnswerSelected = true,
                            toAnswerCount = 4,
                        )
                    }
                }
            }

            onNodeWithTag("inbox-filter-toAnswer", useUnmergedTree = true).assertDoesNotExist()
            onNodeWithTag("inbox-filter-chip", useUnmergedTree = true).assertDoesNotExist()
            onNodeWithContentDescription("Mark all read").assertExists()
        }

    @Test
    fun flagOnShowsToAnswerAfterMentionsWithCountAndChipHidesMarkAllRead() =
        runRobolectricComposeUiTest {
            var pickedToAnswer = false
            setActivityContent {
                Column {
                    SharedActivityFilterBar(
                        selectedFilter = InboxFilter.All,
                        totalUnreadCount = 5,
                        onFilterSelected = {},
                        onMarkAllRead = {},
                        toAnswerEnabled = true,
                        toAnswerSelected = true,
                        toAnswerCount = 3,
                        onToAnswerSelected = { pickedToAnswer = true },
                    )
                    RaftMenuSurface {
                        ActivityFilterMenuRows(
                            selectedFilter = InboxFilter.All,
                            onPick = {},
                            toAnswerEnabled = true,
                            toAnswerSelected = false,
                            toAnswerCount = 3,
                            onPickToAnswer = { pickedToAnswer = true },
                        )
                    }
                }
            }

            val mentionsBounds = onNodeWithText("Mentions").getUnclippedBoundsInRoot()
            val toAnswerNode = onNodeWithTag("inbox-filter-toAnswer", useUnmergedTree = true)
            toAnswerNode.assertExists()
            val toAnswerBounds = toAnswerNode.getUnclippedBoundsInRoot()
            val pinnedBounds = onNodeWithText("Pinned").getUnclippedBoundsInRoot()
            assertTrue(mentionsBounds.bottom <= toAnswerBounds.top, "To answer must appear right after Mentions")
            assertTrue(toAnswerBounds.bottom <= pinnedBounds.top, "To answer must appear before Pinned")

            onNodeWithTag("inbox-filter-chip", useUnmergedTree = true)
                .assertExists()
            onNodeWithText("To answer 3", useUnmergedTree = true).assertExists()
            onNodeWithText("Mark all read").assertDoesNotExist()
            onNodeWithContentDescription("Mark all read").assertDoesNotExist()

            toAnswerNode.performClick()
            assertTrue(pickedToAnswer)
        }

    @Test
    fun toAnswerListRendersQuestionConfirmRequestEndedLingerAndDispatchesOpen() =
        runRobolectricComposeUiTest {
            val opened = mutableListOf<ToAnswerItem>()
            val items =
                listOf(
                    ToAnswerItem(
                        messageId = "m1",
                        kind = ToAnswerCardKind.Question,
                        addressee = ToAnswerAddressee.You,
                        conversation = ToAnswerConversation("chan-1", "channel", "proj-mobile"),
                        requester = ToAnswerRequester(ToAnswerRequesterType.Agent, "agent-1", "Lagoon"),
                        text = "Where should an iOS push land when tapped?",
                        moreQuestions = 0,
                        createdAt = "2026-11-27T10:02:00Z",
                    ),
                    ToAnswerItem(
                        messageId = "m2",
                        kind = ToAnswerCardKind.Confirm,
                        addressee = ToAnswerAddressee.Anyone,
                        conversation =
                            ToAnswerConversation(
                                id = "thread-1",
                                type = "thread",
                                name = "thread-abc",
                                parent = ToAnswerConversationParent("chan-2", "channel", "wg-mobile-sync"),
                                parentMessageId = "p1",
                            ),
                        requester = ToAnswerRequester(ToAnswerRequesterType.Agent, "agent-2", "Reef"),
                        text = "OK to submit 1.4.2 for review?",
                        moreQuestions = 0,
                        createdAt = "2026-11-27T10:00:00Z",
                    ),
                    ToAnswerItem(
                        messageId = "m3",
                        kind = ToAnswerCardKind.Request,
                        addressee = ToAnswerAddressee.Anyone,
                        conversation = ToAnswerConversation("dm-1", "dm", "dm-x"),
                        requester = ToAnswerRequester(ToAnswerRequesterType.User, "u-1", "Artin"),
                        text = "Need read access to slock",
                        moreQuestions = 2,
                        requestTitle =
                            ActionCardSpecMsg(
                                key = "actionCard.request.access",
                                text = mapOf("en" to "Approve {app} access"),
                                params = mapOf("app" to ActionCardSpecMsgParam.Text("GitHub")),
                            ),
                        createdAt = "2026-11-27T09:50:00Z",
                        expiresAt = "2026-12-02T10:05:00Z",
                    ),
                )
            val state =
                SharedToAnswerState(
                    serverId = "srv-1",
                    items = items,
                    count = 2,
                    status = ToAnswerStatus.Loaded,
                    ended = mapOf("m2" to ToAnswerEnded(state = "executed", byName = "AndyLok")),
                )

            setActivityContent {
                ActivityToAnswerList(
                    state = state,
                    pullRefreshing = false,
                    timeContext = nowContext,
                    onOpenItem = { opened += it },
                    onRetry = {},
                    onPullRefresh = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }

            val rows = onAllNodesWithTag("to-answer-row")
            rows.assertCountEquals(3)

            // Row 1: question, asks you, accessible content description names place, kind, addressee.
            rows[0]
                .assertContentDescriptionContains("Lagoon", substring = true)
                .assertContentDescriptionContains("proj-mobile", substring = true)
                .assertContentDescriptionContains("Question", substring = true)
                .assertContentDescriptionContains("Asks you", substring = true)
            onNodeWithTag("to-answer-you", useUnmergedTree = true).assertExists()

            // Row 2: ended confirm card in thread shows "Answered by AndyLok" and "#wg-mobile-sync · Thread".
            onNodeWithTag("to-answer-ended", useUnmergedTree = true)
                .assertExists()
                .assertTextContains("Answered by AndyLok")
            onNodeWithText("#wg-mobile-sync · Thread", useUnmergedTree = true).assertExists()
            onAllNodesWithTag("to-answer-anyone", useUnmergedTree = true).assertCountEquals(2)

            // Row 3: request card shows localized title + note, +2 more, deadline, and Direct message.
            onNodeWithText("Approve GitHub access: Need read access to slock", useUnmergedTree = true).assertExists()
            onNodeWithText("+2 more", useUnmergedTree = true).assertExists()
            onNodeWithText("Expires in 5 days", useUnmergedTree = true).assertExists()
            onNodeWithText("Direct message", useUnmergedTree = true).assertExists()

            // Tapping a row dispatches open navigation to the card's message/thread.
            rows[1].performClick()
            assertEquals(listOf("m2"), opened.map { it.messageId })
            val resolved =
                assertIs<ConversationOpenResolution.Resolved>(
                    opened.single().toHomeMessageOpenRequest("srv-1", SharedConversationDirectoryState())
                ).request
            assertEquals(ConversationKind.Thread, resolved.conversationIdentity.kind)
            assertEquals(ChannelId("chan-2"), resolved.conversationIdentity.thread?.parentChannelId)
            assertEquals(MessageId("p1"), resolved.conversationIdentity.thread?.parentMessageId)
            assertEquals("m2", resolved.anchorMessageId)
        }

    @Test
    fun toAnswerListShowsEmptyState() =
        runRobolectricComposeUiTest {
            setActivityContent {
                ActivityToAnswerList(
                    state = SharedToAnswerState(serverId = "srv-1", items = emptyList(), count = 0, status = ToAnswerStatus.Loaded),
                    pullRefreshing = false,
                    timeContext = nowContext,
                    onOpenItem = {},
                    onRetry = {},
                    onPullRefresh = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
            onNodeWithTag("to-answer-empty", useUnmergedTree = true).assertExists()
            onNodeWithText("No cards are waiting on you").assertExists()
        }

    @Test
    fun toAnswerListShowsRetryBannerOnFailure() =
        runRobolectricComposeUiTest {
            var retried = false
            setActivityContent {
                ActivityToAnswerList(
                    state = SharedToAnswerState(serverId = "srv-1", items = emptyList(), count = 0, status = ToAnswerStatus.Failed, errorCode = "network"),
                    pullRefreshing = false,
                    timeContext = nowContext,
                    onOpenItem = {},
                    onRetry = { retried = true },
                    onPullRefresh = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
            onNodeWithTag("to-answer-load-failed", useUnmergedTree = true).assertExists()
            onNodeWithText("Retry").performClick()
            assertTrue(retried)
        }
}
