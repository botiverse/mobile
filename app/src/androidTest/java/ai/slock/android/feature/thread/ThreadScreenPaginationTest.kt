package ai.slock.android.feature.thread

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import ai.slock.android.core.model.AgentStatus
import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.ConversationKind
import ai.slock.android.core.model.ConversationSummary
import ai.slock.android.core.model.MemberId
import ai.slock.android.core.model.MemberKind
import ai.slock.android.core.model.MemberProfile
import ai.slock.android.core.model.Message
import ai.slock.android.core.model.MessageId
import ai.slock.android.core.model.Presence
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
    fun horizontalSwipeSwitchesBetweenChatAndTasksTabs() {
        val selectedTab = mutableStateOf(ThreadTab.Chat)
        composeRule.setContent {
            SlockTheme {
                ThreadScreen(
                    state = ThreadUiState(
                        selectedTab = selectedTab.value,
                        loadState = ScreenLoadState.Content(
                            content(messages = listOf(message(1L)), canLoadMoreBefore = false)
                        )
                    ),
                    onModeSelected = { selectedTab.value = it }
                )
            }
        }

        composeRule.onNodeWithText("CHAT").assertIsDisplayed()
        composeRule.onNodeWithTag(THREAD_MESSAGE_LIST_TAG).performTouchInput {
            swipeLeft()
        }

        composeRule.runOnIdle {
            assertEquals(ThreadTab.Tasks, selectedTab.value)
        }
        composeRule.onNodeWithText("No tasks").assertIsDisplayed()

        composeRule.onNodeWithText("No tasks").performTouchInput {
            swipeRight()
        }

        composeRule.runOnIdle {
            assertEquals(ThreadTab.Chat, selectedTab.value)
        }
        composeRule.onNodeWithText("Message 1").assertIsDisplayed()
    }

    @Test
    fun verticalSwipeInMessageListDoesNotSwitchThreadTab() {
        val selectedTab = mutableStateOf(ThreadTab.Chat)
        composeRule.setContent {
            SlockTheme {
                ThreadScreen(
                    state = ThreadUiState(
                        selectedTab = selectedTab.value,
                        loadState = ScreenLoadState.Content(
                            content(
                                messages = (1L..24L).map(::message),
                                canLoadMoreBefore = false
                            )
                        )
                    ),
                    onModeSelected = { selectedTab.value = it }
                )
            }
        }

        composeRule.onNodeWithTag(THREAD_MESSAGE_LIST_TAG).performTouchInput {
            down(center)
            moveBy(Offset(0f, -140f))
            up()
        }

        composeRule.runOnIdle {
            assertEquals(ThreadTab.Chat, selectedTab.value)
        }
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
    fun replyThreadDoesNotShowNewerHistoryRetryState() {
        composeRule.setContent {
            SlockTheme {
                ThreadScreen(
                    state = ThreadUiState(
                        loadState = ScreenLoadState.Content(
                            content(messages = listOf(message(1L)), canLoadMoreBefore = false)
                                .copy(
                                    canLoadMoreAfter = true,
                                    newerMessagesStatus = MessagePageStatus.Error,
                                    newerMessagesErrorCode = "server_unavailable"
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
    fun replyThreadLongOriginCanCollapseAndExpand() {
        val longOriginBody =
            (1..18).joinToString("\n") { index ->
                "Original thread starter line $index with enough text to make the pinned origin tall"
            }
        val origin =
            message(1L).copy(
                id = MessageId("long-origin-message"),
                body = longOriginBody
            )

        composeRule.setContent {
            SlockTheme {
                ThreadScreen(
                    state = ThreadUiState(
                        loadState = ScreenLoadState.Content(
                            content(messages = listOf(message(2L)), canLoadMoreBefore = false)
                        )
                    ),
                    replyHeaderContext = ThreadReplyHeaderContext(
                        parentChannelName = "android-dev",
                        parentMessagePreview = "Original thread starter",
                        parentMessage = origin
                    ),
                    parentJumpTarget = ThreadParentJumpTarget(MessageId("long-origin-message"))
                )
            }
        }

        composeRule.onNodeWithText("Original thread starter line 1", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Message 2").assertIsDisplayed()
        composeRule.onNodeWithText("Expand origin").assertIsDisplayed().performClick()
        composeRule.onAllNodesWithText("Collapse origin").assertCountEquals(1)
        composeRule.onAllNodesWithText("Original thread starter line 18", substring = true)
            .assertCountEquals(1)
    }

    @Test
    fun replyThreadHugeExpandedOriginKeepsCollapseVisible() {
        val hugeOriginBody =
            (1..80).joinToString("\n") { index ->
                "Very long origin line $index with enough text to overflow the expanded panel"
            }
        val origin =
            message(1L).copy(
                id = MessageId("huge-origin-message"),
                body = hugeOriginBody
            )

        composeRule.setContent {
            SlockTheme {
                ThreadScreen(
                    state = ThreadUiState(
                        loadState = ScreenLoadState.Content(
                            content(messages = listOf(message(2L)), canLoadMoreBefore = false)
                        )
                    ),
                    replyHeaderContext = ThreadReplyHeaderContext(
                        parentChannelName = "android-dev",
                        parentMessagePreview = "Huge origin",
                        parentMessage = origin
                    ),
                    parentJumpTarget = ThreadParentJumpTarget(MessageId("huge-origin-message"))
                )
            }
        }

        composeRule.onNodeWithText("Expand origin").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Collapse origin").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Expand origin").assertIsDisplayed()
    }

    @Test
    fun replyThreadNearThresholdOriginDoesNotShowRedundantCollapseControl() {
        val origin =
            message(1L).copy(
                id = MessageId("near-threshold-origin-message"),
                body = (1..6).joinToString("\n") { index -> "Compact origin line $index" }
            )

        composeRule.setContent {
            SlockTheme {
                ThreadScreen(
                    state = ThreadUiState(
                        loadState = ScreenLoadState.Content(
                            content(messages = listOf(message(2L)), canLoadMoreBefore = false)
                        )
                    ),
                    replyHeaderContext = ThreadReplyHeaderContext(
                        parentChannelName = "android-dev",
                        parentMessagePreview = "Compact origin",
                        parentMessage = origin
                    ),
                    parentJumpTarget = ThreadParentJumpTarget(MessageId("near-threshold-origin-message"))
                )
            }
        }

        composeRule.onNodeWithText("Compact origin line 1", substring = true).assertIsDisplayed()
        composeRule.onAllNodesWithText("Expand origin").assertCountEquals(0)
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
    fun membersListAvatarOpensMemberDetail() {
        val agent = MemberProfile(
            id = MemberId("agent-avatar-detail"),
            displayName = "Avatar Detail Agent",
            handle = "avatar-detail",
            kind = MemberKind.Agent,
            roleDescription = "Agent"
        )
        val membersState = ThreadMembersPanelState(
            loadState = ScreenLoadState.Content(
                ThreadMembersPanelContent(members = listOf(agent))
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
                    membersPanelState = membersState
                )
            }
        }

        composeRule.onNodeWithContentDescription("Members").performClick()
        composeRule.onNodeWithContentDescription("Open Avatar Detail Agent details")
            .assertIsDisplayed()
            .performClick()

        composeRule.onNodeWithText("Remove Agent").assertIsDisplayed()
    }

    @Test
    fun channelSettingsArchiveActionInvokesCallback() {
        var archiveClicks = 0

        composeRule.setContent {
            SlockTheme {
                ThreadScreen(
                    state = ThreadUiState(
                        loadState = ScreenLoadState.Content(
                            content(messages = listOf(message(1)), canLoadMoreBefore = false)
                        )
                    ),
                    onArchiveChannel = { archiveClicks += 1 }
                )
            }
        }

        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.onNodeWithText("Archive Channel").assertIsDisplayed().performClick()

        composeRule.runOnIdle {
            assertEquals(1, archiveClicks)
        }
    }

    @Test
    fun channelSettingsDeleteRequiresConfirmationName() {
        var deleteClicks = 0

        composeRule.setContent {
            SlockTheme {
                ThreadScreen(
                    state = ThreadUiState(
                        loadState = ScreenLoadState.Content(
                            content(messages = listOf(message(1)), canLoadMoreBefore = false)
                        )
                    ),
                    onDeleteChannel = { deleteClicks += 1 }
                )
            }
        }

        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.onNodeWithText("Delete Channel").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Delete Channel").assertIsNotEnabled()
        composeRule.onNodeWithText("android").performTextInput("android-dev")
        composeRule.onNodeWithText("Delete Channel").assertIsEnabled().performClick()

        composeRule.runOnIdle {
            assertEquals(1, deleteClicks)
        }
    }

    @Test
    fun addMemberPickerClickingSelectedAgentAgainDeselectsIt() {
        val currentMember = MemberProfile(
            id = MemberId("human-current"),
            displayName = "Human Current",
            handle = "human-current",
            kind = MemberKind.Human,
            roleDescription = "Owner"
        )
        val candidate = MemberProfile(
            id = MemberId("agent-candidate"),
            displayName = "Agent Candidate",
            handle = "agent-candidate",
            kind = MemberKind.Agent,
            roleDescription = "Agent"
        )
        val membersState = ThreadMembersPanelState(
            loadState = ScreenLoadState.Content(
                ThreadMembersPanelContent(members = listOf(currentMember))
            ),
            candidatesLoadState = ScreenLoadState.Content(
                ThreadMembersPickerContent(candidates = listOf(candidate))
            )
        )
        var addCalls = 0

        composeRule.setContent {
            SlockTheme {
                ThreadScreen(
                    state = ThreadUiState(
                        loadState = ScreenLoadState.Content(
                            content(messages = listOf(message(1)), canLoadMoreBefore = false)
                        )
                    ),
                    membersPanelState = membersState,
                    onAddChannelMember = { _, _ -> addCalls += 1 }
                )
            }
        }

        composeRule.onNodeWithContentDescription("Members").performClick()
        composeRule.onNodeWithText("+ Add Member").assertIsDisplayed().performClick()

        composeRule.onNodeWithText("Agent Candidate").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Selected").assertIsDisplayed()
        composeRule.onNodeWithText("Agent Candidate").performClick()
        composeRule.onAllNodesWithText("Selected").assertCountEquals(0)
        composeRule.onNodeWithText("Add").assertIsDisplayed()
        composeRule.onNodeWithText("Add Selected").performClick()

        composeRule.runOnIdle {
            assertEquals(0, addCalls)
        }
    }

    @Test
    fun agentMessageAvatarShowsStatusIndicator() {
        composeRule.setContent {
            SlockTheme {
                ThreadScreen(
                    state = ThreadUiState(
                        loadState = ScreenLoadState.Content(
                            content(
                                messages =
                                    listOf(
                                        message(1).copy(
                                            author =
                                                author.copy(
                                                    displayName = "Thinking Agent",
                                                    agentStatus = AgentStatus.Thinking,
                                                    presence = Presence.Active
                                                )
                                        )
                                    ),
                                canLoadMoreBefore = false
                            )
                        )
                    )
                )
            }
        }

        composeRule.onNodeWithText("Thinking Agent").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Agent status: Thinking").assertIsDisplayed()
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
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(THREAD_BACK_TO_BOTTOM_TAG)
                .fetchSemanticsNodes()
                .isEmpty()
        }
        composeRule.onAllNodesWithTag(THREAD_BACK_TO_BOTTOM_TAG).assertCountEquals(0)

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

    @Test
    fun highlightedMessageChangeRepositionsVirtualWindowInSameThread() {
        val screenState = mutableStateOf(
            ThreadUiState(
                loadState = ScreenLoadState.Content(
                    content(
                        messages = (1L..80L).map(::message),
                        canLoadMoreBefore = false
                    ).copy(highlightedMessageId = MessageId("message-70"))
                )
            )
        )

        composeRule.setContent {
            SlockTheme {
                ThreadScreen(
                    state = screenState.value,
                    currentMemberId = MemberId("current-user")
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Message 70", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText("Message 70", useUnmergedTree = true).assertIsDisplayed()

        composeRule.runOnIdle {
            val current = (screenState.value.loadState as ScreenLoadState.Content).data
            screenState.value = screenState.value.copy(
                loadState = ScreenLoadState.Content(
                    current.copy(highlightedMessageId = MessageId("message-20"))
                )
            )
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Message 20", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText("Message 20", useUnmergedTree = true).assertIsDisplayed()
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
