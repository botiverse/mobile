package ai.slock.android.feature.thread

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
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
import ai.slock.android.core.state.ScreenError
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
    fun threadLoadErrorRetryInvokesRetryHandler() {
        var retries = 0

        composeRule.setContent {
            SlockTheme {
                ThreadScreen(
                    state = ThreadUiState(
                        loadState = ScreenLoadState.Error(ScreenError("thread_failed"))
                    ),
                    onRetryThreadLoad = { retries += 1 }
                )
            }
        }

        composeRule.onNodeWithText("Retry").assertIsDisplayed().performClick()

        composeRule.runOnIdle {
            assertEquals(1, retries)
        }
    }

    @Test
    fun inlineReplyErrorRetryInvokesRetryHandler() {
        var retries = 0

        composeRule.setContent {
            SlockTheme {
                ThreadScreen(
                    state = ThreadUiState(
                        loadState = ScreenLoadState.Content(
                            content(messages = emptyList(), canLoadMoreBefore = false).copy(
                                replyActionErrorCode = "thread_failed"
                            )
                        )
                    ),
                    onRetryThreadLoad = { retries += 1 }
                )
            }
        }

        composeRule.onNodeWithText("Retry").assertIsDisplayed().performClick()

        composeRule.runOnIdle {
            assertEquals(1, retries)
        }
    }

    @Test
    fun replyThreadDoesNotShowFixedRefreshBanner() {
        composeRule.setContent {
            SlockTheme {
                ThreadScreen(
                    state = ThreadUiState(
                        loadState = ScreenLoadState.Content(
                            content(messages = listOf(message(1L)), canLoadMoreBefore = false)
                                .withRefreshStatus(ThreadRefreshStatus.Error, "refresh_failed")
                        )
                    ),
                    replyHeaderContext = ThreadReplyHeaderContext(
                        parentChannelName = "android-dev",
                        parentMessagePreview = "Original thread starter"
                    ),
                    parentJumpTarget = ThreadParentJumpTarget(MessageId("origin-message"))
                )
            }
        }

        composeRule.onAllNodesWithText("Refresh failed").assertCountEquals(0)
        composeRule.onAllNodesWithText("Retry").assertCountEquals(0)
    }

    @Test
    fun replyThreadDoesNotShowChatTasksTabs() {
        composeRule.setContent {
            SlockTheme {
                ThreadScreen(
                    state = ThreadUiState(
                        loadState = ScreenLoadState.Content(
                            content(messages = listOf(message(1L)), canLoadMoreBefore = false)
                        )
                    ),
                    replyHeaderContext = ThreadReplyHeaderContext(
                        parentChannelName = "android-dev",
                        parentMessagePreview = "Original thread starter"
                    ),
                    parentJumpTarget = ThreadParentJumpTarget(MessageId("origin-message"))
                )
            }
        }

        composeRule.onAllNodesWithText("CHAT").assertCountEquals(0)
        composeRule.onAllNodesWithText("TASKS").assertCountEquals(0)
        composeRule.onNodeWithText("Original thread starter").assertIsDisplayed()
        composeRule.onNodeWithText("Message 1").assertIsDisplayed()
    }

    @Test
    fun replyThreadWithNoRepliesKeepsOriginInsteadOfEmptyState() {
        val origin =
            message(1L).copy(
                id = MessageId("origin-empty-thread"),
                body = "Original empty thread starter"
            )

        composeRule.setContent {
            SlockTheme {
                ThreadScreen(
                    state = ThreadUiState(
                        loadState = ScreenLoadState.Content(
                            content(messages = emptyList(), canLoadMoreBefore = false)
                        )
                    ),
                    replyHeaderContext = ThreadReplyHeaderContext(
                        parentChannelName = "android-dev",
                        parentMessagePreview = "Original empty thread starter",
                        parentMessage = origin
                    ),
                    parentJumpTarget = ThreadParentJumpTarget(MessageId("origin-empty-thread"))
                )
            }
        }

        composeRule.onNodeWithText("Original empty thread starter").assertIsDisplayed()
        composeRule.onAllNodesWithText("No messages yet").assertCountEquals(0)
        composeRule.onAllNodesWithText("Start the conversation when you are ready.").assertCountEquals(0)
    }

    @Test
    fun replyThreadDoesNotShowHistoryRetryState() {
        composeRule.setContent {
            SlockTheme {
                ThreadScreen(
                    state = ThreadUiState(
                        loadState = ScreenLoadState.Content(
                            content(messages = listOf(message(1L)), canLoadMoreBefore = true)
                                .copy(
                                    olderMessagesStatus = MessagePageStatus.Error,
                                    olderMessagesErrorCode = "server_unavailable"
                                )
                        )
                    ),
                    replyHeaderContext = ThreadReplyHeaderContext(
                        parentChannelName = "android-dev",
                        parentMessagePreview = "Original thread starter"
                    ),
                    parentJumpTarget = ThreadParentJumpTarget(MessageId("origin-message"))
                )
            }
        }

        composeRule.onAllNodesWithText("Retry history", substring = true).assertCountEquals(0)
        composeRule.onNodeWithText("Message 1").assertIsDisplayed()
    }

    @Test
    fun channelThreadStillShowsRefreshErrorRetry() {
        var retries = 0

        composeRule.setContent {
            SlockTheme {
                ThreadScreen(
                    state = ThreadUiState(
                        loadState = ScreenLoadState.Content(
                            content(messages = listOf(message(1L)), canLoadMoreBefore = false)
                                .withRefreshStatus(ThreadRefreshStatus.Error, "refresh_failed")
                        )
                    ),
                    onRefreshThread = { retries += 1 }
                )
            }
        }

        composeRule.onNodeWithText("Refresh failed").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertEquals(1, retries)
        }
    }

    @Test
    fun replyThreadPinsOriginOutsideScrollableReplies() {
        var openedParent: ThreadParentJumpTarget? = null
        var openedReply: Message? = null
        val origin =
            message(1L).copy(
                id = MessageId("origin-message"),
                body = "Original thread starter"
            )

        composeRule.setContent {
            SlockTheme {
                ThreadScreen(
                    state = ThreadUiState(
                        loadState = ScreenLoadState.Content(
                            content(
                                messages = (1L..24L).map(::message),
                                canLoadMoreBefore = false
                            )
                        )
                    ),
                    replyHeaderContext = ThreadReplyHeaderContext(
                        parentChannelName = "android-dev",
                        parentMessagePreview = "Original thread starter",
                        parentMessage = origin
                    ),
                    parentJumpTarget = ThreadParentJumpTarget(MessageId("origin-message")),
                    onOpenParentMessage = { openedParent = it },
                    onOpenMessageThread = { openedReply = it }
                )
            }
        }

        composeRule.onNodeWithText("Original thread starter").assertIsDisplayed()
        composeRule.onAllNodesWithText("View in channel").assertCountEquals(0)
        composeRule.onAllNodesWithText("Thread origin").assertCountEquals(0)
        composeRule.onNodeWithText("Original thread starter").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Reply").assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertEquals(ThreadParentJumpTarget(MessageId("origin-message")), openedParent)
        }

        composeRule.onNodeWithTag(THREAD_MESSAGE_LIST_TAG).performScrollToIndex(23)
        composeRule.onNodeWithText("Original thread starter").assertIsDisplayed()
        composeRule.onAllNodesWithText("Beginning of replies").assertCountEquals(0)
        composeRule.onNodeWithText("Message 23").assertIsDisplayed().performClick()
        composeRule.onAllNodesWithText("Reply").assertCountEquals(0)
        composeRule.onNodeWithText("Copy markdown").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(null, openedReply)
        }
    }

    @Test
    fun channelReplyActionCanCreateThreadWithoutExistingReplies() {
        var openedReply: Message? = null

        composeRule.setContent {
            SlockTheme {
                ThreadScreen(
                    state = ThreadUiState(
                        loadState = ScreenLoadState.Content(
                            content(
                                messages = listOf(message(1L).copy(replyCount = 0)),
                                canLoadMoreBefore = false
                            )
                        )
                    ),
                    onOpenMessageThread = { openedReply = it }
                )
            }
        }

        composeRule.onNodeWithText("Message 1").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Reply").assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertEquals(MessageId("message-1"), openedReply?.id)
        }
    }

    @Test
    fun replyThreadOriginShowsRealAgentSender() {
        val origin =
            message(1L).copy(
                id = MessageId("origin-agent-message"),
                body = "Original agent message",
                author = MemberProfile(
                    id = MemberId("agent-cindy"),
                    displayName = "Cindy",
                    handle = "cindy",
                    kind = MemberKind.Agent,
                    roleDescription = "Agent"
                )
            )

        composeRule.setContent {
            SlockTheme {
                ThreadScreen(
                    state = ThreadUiState(
                        loadState = ScreenLoadState.Content(
                            content(
                                messages = (1L..3L).map(::message),
                                canLoadMoreBefore = false
                            )
                        )
                    ),
                    replyHeaderContext = ThreadReplyHeaderContext(
                        parentChannelName = "android-dev",
                        parentMessagePreview = "Original agent message",
                        parentMessage = origin
                    ),
                    parentJumpTarget = ThreadParentJumpTarget(MessageId("origin-agent-message"))
                )
            }
        }

        composeRule.onNodeWithText("Cindy").assertIsDisplayed()
        composeRule.onNodeWithText("Agent").assertIsDisplayed()
        composeRule.onNodeWithText("Original agent message").assertIsDisplayed()
        composeRule.onAllNodesWithText("Thread origin").assertCountEquals(0)
    }

    @Test
    fun membersListOnlyShowsRemoveAfterOpeningAgentDetail() {
        var removedMember: MemberProfile? = null
        val agent = MemberProfile(
            id = MemberId("agent-member-detail"),
            displayName = "Agent Detail",
            handle = "agent-detail",
            kind = MemberKind.Agent,
            roleDescription = "Agent"
        )
        val human = MemberProfile(
            id = MemberId("human-member-detail"),
            displayName = "Human Detail",
            handle = "human-detail",
            kind = MemberKind.Human,
            roleDescription = "Owner"
        )
        val membersState = ThreadMembersPanelState(
            loadState = ScreenLoadState.Content(
                ThreadMembersPanelContent(members = listOf(agent, human))
            ),
            candidatesLoadState = ScreenLoadState.Content(
                ThreadMembersPickerContent(candidates = emptyList())
            )
        )

        composeRule.setContent {
            SlockTheme {
                ThreadScreen(
                    state = ThreadUiState(
                        loadState = ScreenLoadState.Content(
                            content(messages = listOf(message(1)), canLoadMoreBefore = false)
                        )
                    ),
                    membersPanelState = membersState,
                    onRemoveChannelMember = { removedMember = it }
                )
            }
        }

        composeRule.onNodeWithContentDescription("Members").performClick()
        composeRule.onNodeWithText("Remove").assertDoesNotExist()
        composeRule.onNodeWithText("Remove Agent").assertDoesNotExist()

        composeRule.onNodeWithText("Agent Detail").performClick()
        composeRule.onNodeWithText("Remove Agent").assertIsDisplayed().performClick()

        composeRule.runOnIdle {
            assertEquals(agent, removedMember)
        }
    }

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
