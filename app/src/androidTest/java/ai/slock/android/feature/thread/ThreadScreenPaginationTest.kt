package ai.slock.android.feature.thread

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.ConversationKind
import ai.slock.android.core.model.ConversationSummary
import ai.slock.android.core.model.MemberId
import ai.slock.android.core.model.MemberKind
import ai.slock.android.core.model.MemberProfile
import ai.slock.android.core.model.Message
import ai.slock.android.core.model.MessageId
import ai.slock.android.core.model.ThreadId
import ai.slock.android.core.state.ScreenLoadState
import ai.slock.android.ui.theme.SlockTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

class ThreadScreenPaginationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun nearTopScrollLoadsOlderPageWithBeforeCursorAndRetainsAnchor() {
        val fakeRepository = FakeOlderMessageRepository(
            olderMessages = (16L..20L).map(::message)
        )
        val screenState = mutableStateOf(
            ThreadUiState(
                loadState = ScreenLoadState.Content(
                    content(messages = (21L..60L).map(::message), canLoadMoreBefore = true)
                )
            )
        )
        var request: OlderLoadRequest? = null
        var showLoadingMore = false

        composeRule.setContent {
            SlockTheme {
                ThreadScreen(
                    state = screenState.value,
                    currentMemberId = MemberId("current-user"),
                    onLoadOlderMessages = { anchorMessageId, anchorOffset ->
                        val current = (screenState.value.loadState as ScreenLoadState.Content).data
                        request = OlderLoadRequest(
                            beforeSeq = current.messages.mapNotNull { it.seq }.minOrNull(),
                            anchorMessageId = anchorMessageId,
                            anchorOffset = anchorOffset
                        )
                        val olderMessages = fakeRepository.loadOlderMessages(requireNotNull(request?.beforeSeq))
                        showLoadingMore = true
                        screenState.value = screenState.value.copy(
                            loadState = ScreenLoadState.Content(
                                current.copy(
                                    olderMessagesStatus = MessagePageStatus.Loading,
                                    anchorMessageId = anchorMessageId,
                                    anchorOffset = anchorOffset
                                )
                            )
                        )
                    }
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Message 60").assertIsDisplayed()
        composeRule.onNodeWithText("Beginning of messages").assertDoesNotExist()

        composeRule.onNodeWithTag(THREAD_MESSAGE_LIST_TAG).performTouchInput {
            repeat(10) { swipeDown() }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) { fakeRepository.loadCalls == 1 }
        composeRule.onNodeWithText("Loading older messages...").assertExists()
        composeRule.runOnIdle {
            val current = (screenState.value.loadState as ScreenLoadState.Content).data
            val olderMessages = fakeRepository.lastOlderMessages
            screenState.value = screenState.value.copy(
                loadState = ScreenLoadState.Content(
                    current.copy(
                        messages = olderMessages + current.messages,
                        canLoadMoreBefore = false,
                        olderMessagesStatus = MessagePageStatus.EndReached,
                        anchorMessageId = request?.anchorMessageId,
                        anchorOffset = request?.anchorOffset ?: 0
                    )
                )
            )
            showLoadingMore = false
        }
        composeRule.waitForIdle()
        assertEquals(false, showLoadingMore)

        assertEquals(21L, request?.beforeSeq)
        assertEquals(MessageId("message-22"), request?.anchorMessageId)
        assertNotNull(request?.anchorOffset)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Message 21", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText("Message 21", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Message 20", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Beginning of messages").assertIsDisplayed()
        composeRule.onNodeWithTag(THREAD_BACK_TO_BOTTOM_TAG).assertIsDisplayed()

        composeRule.onNodeWithTag(THREAD_BACK_TO_BOTTOM_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Message 60").assertIsDisplayed()

        composeRule.onNodeWithTag(THREAD_MESSAGE_LIST_TAG).performScrollToIndex(0)
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Message 16", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText("Message 16", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Message 21", useUnmergedTree = true).assertIsDisplayed()
    }

    private data class OlderLoadRequest(
        val beforeSeq: Long?,
        val anchorMessageId: MessageId?,
        val anchorOffset: Int
    )

    private class FakeOlderMessageRepository(
        private val olderMessages: List<Message>
    ) {
        var loadCalls = 0
            private set
        val lastOlderMessages: List<Message>
            get() = olderMessages

        fun loadOlderMessages(beforeSeq: Long): List<Message> {
            loadCalls += 1
            assertEquals(21L, beforeSeq)
            return olderMessages
        }
    }

    private companion object {
        private val threadId = ThreadId("thread-scroll-test")
        private val channelId = ChannelId("channel-scroll-test")
        private val author = MemberProfile(
            id = MemberId("agent-scroll-test"),
            displayName = "Scroll Tester",
            handle = "@scroll-tester",
            kind = MemberKind.Agent,
            avatarLabel = "S"
        )

        private fun content(
            messages: List<Message>,
            canLoadMoreBefore: Boolean
        ) = ThreadContent(
            header = ConversationSummary(
                threadId = threadId,
                channelId = channelId,
                kind = ConversationKind.Channel,
                name = "scroll-test"
            ),
            messages = messages,
            canLoadMoreBefore = canLoadMoreBefore
        )

        private fun message(seq: Long) = Message(
            id = MessageId("message-$seq"),
            threadId = threadId,
            channelId = channelId,
            seq = seq,
            author = author,
            timestampLabel = "13:$seq",
            body = "Message $seq"
        )
    }
}
