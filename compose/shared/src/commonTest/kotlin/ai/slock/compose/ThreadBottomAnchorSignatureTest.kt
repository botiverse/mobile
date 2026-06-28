package ai.slock.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Focused regression coverage for the bottom-anchor keying in ThreadPage.
 *
 * The Thread page uses two pure functions on KuiklyThreadScreenPayload
 * to drive its bottom-anchor LaunchedEffect:
 *
 * - `latestMessageListItemIndex()`: which list index to anchor at
 * - `latestMessageLayoutKey()`: a stable string identifier for the
 *   current bottom-of-list state (used to short-circuit anchor work
 *   when content is equivalent)
 *
 * The previous code keyed `remember(...)` on the `messages` list
 * reference, which invalidated the cache on any list replacement even
 * when the content was equivalent. These tests pin the contract that
 * the anchor keys are stable across content-equivalent list
 * replacements, and change only when the relevant fields differ.
 */
class ThreadBottomAnchorSignatureTest {

    private fun message(
        id: String,
        body: String = "body-$id",
        replyCount: Int = 0,
        unreadReplyCount: Int = 0,
        taskId: String? = null,
        attachments: List<KuiklyThreadAttachmentItem> = emptyList(),
        reactions: List<KuiklyThreadReactionItem> = emptyList(),
        metadata: Map<String, String> = emptyMap(),
        deliveryState: String = ThreadMessageDeliverySent,
        pendingSendContext: KuiklyThreadPendingSendContext? = null
    ): KuiklyThreadMessageItem = KuiklyThreadMessageItem(
        id = id,
        authorId = "user-$id",
        authorName = "User $id",
        body = body,
        replyCount = replyCount,
        unreadReplyCount = unreadReplyCount,
        taskId = taskId,
        attachments = attachments,
        reactions = reactions,
        metadata = metadata,
        deliveryState = deliveryState,
        pendingSendContext = pendingSendContext
    )

    private fun payload(
        originStatus: String = "none",
        messages: List<KuiklyThreadMessageItem> = emptyList(),
        originMessage: KuiklyThreadMessageItem? = null,
        canLoadMoreBefore: Boolean = false,
        canLoadMoreAfter: Boolean = false,
        members: List<KuiklyThreadMemberItem> = emptyList()
    ): KuiklyThreadScreenPayload = KuiklyThreadScreenPayload(
        originStatus = originStatus,
        messages = messages,
        originMessage = originMessage,
        canLoadMoreBefore = canLoadMoreBefore,
        canLoadMoreAfter = canLoadMoreAfter,
        members = members
    )

    private fun KuiklyThreadScreenPayload.latestMessageLayoutKeyPublic(): String =
        latestMessageLayoutKey()

    private fun KuiklyThreadScreenPayload.latestMessageListItemIndexPublic(): Int =
        latestMessageListItemIndex()

    private fun KuiklyThreadScreenPayload.latestLocalSendBottomAnchorKeyPublic(): String? =
        latestLocalSendBottomAnchorKey()

    private fun KuiklyThreadScreenPayload.timelineSlice(): ThreadTimelineSlice =
        toThreadTimelineSlice()

    @Test
    fun latestMessageLayoutKey_equivalentListReferenceSameContent_keepsKeyEqual() {
        val sharedContent = listOf(
            message("m1", replyCount = 2, unreadReplyCount = 1),
            message("m2", body = "tail", taskId = "task-1")
        )
        val a = payload(messages = sharedContent)
        val b = payload(messages = sharedContent.toList())
        assertEquals(a.latestMessageLayoutKeyPublic(), b.latestMessageLayoutKeyPublic())
    }

    @Test
    fun latestMessageLayoutKey_appendingMessage_changesKey() {
        val a = payload(messages = listOf(message("m1")))
        val b = payload(messages = listOf(message("m1"), message("m2")))
        assertNotEquals(a.latestMessageLayoutKeyPublic(), b.latestMessageLayoutKeyPublic())
    }

    @Test
    fun latestMessageLayoutKey_lastMessageBodyChange_changesKey() {
        val a = payload(messages = listOf(message("m1", body = "old body")))
        val b = payload(messages = listOf(message("m1", body = "new body that is longer")))
        assertNotEquals(a.latestMessageLayoutKeyPublic(), b.latestMessageLayoutKeyPublic())
    }

    @Test
    fun latestMessageLayoutKey_replyCountChange_keepsKeyEqual() {
        val a = payload(messages = listOf(message("m1", replyCount = 1)))
        val b = payload(messages = listOf(message("m1", replyCount = 2)))
        assertEquals(a.latestMessageLayoutKeyPublic(), b.latestMessageLayoutKeyPublic())
    }

    @Test
    fun replyMetadataProjection_carriesReplyFooterFactsByParentMessageId() {
        val metadata =
            payload(
                messages =
                    listOf(
                        message(
                            "m1",
                            replyCount = 2,
                            unreadReplyCount = 1
                        )
                    )
            )
                .timelineSlice()
                .toThreadReplyMetadataMap()
                .getValue("m1")

        assertEquals("m1", metadata.parentMessageId)
        assertEquals(2, metadata.replyCount)
        assertEquals(1, metadata.unreadReplyCount)
    }

    @Test
    fun latestMessageLayoutKey_reactionChange_changesKey() {
        val a =
            payload(
                messages =
                    listOf(
                        message(
                            "m1",
                            reactions = listOf(KuiklyThreadReactionItem(emoji = "👍", count = 1))
                        )
                    )
            )
        val b =
            payload(
                messages =
                    listOf(
                        message(
                            "m1",
                            reactions = listOf(KuiklyThreadReactionItem(emoji = "👍", count = 2))
                        )
                    )
            )
        assertNotEquals(a.latestMessageLayoutKeyPublic(), b.latestMessageLayoutKeyPublic())
    }

    @Test
    fun latestMessageLayoutKey_membersOnlyChange_keepsKeyEqual() {
        val a = payload(
            messages = listOf(message("m1", replyCount = 1)),
            members = listOf(KuiklyThreadMemberItem(id = "u1", displayName = "User 1", kind = "human"))
        )
        val b = payload(
            messages = listOf(message("m1", replyCount = 1)),
            members = listOf(
                KuiklyThreadMemberItem(id = "u1", displayName = "User 1", kind = "human"),
                KuiklyThreadMemberItem(id = "u2", displayName = "User 2", kind = "human")
            )
        )
        assertEquals(a.latestMessageLayoutKeyPublic(), b.latestMessageLayoutKeyPublic())
    }

    @Test
    fun latestMessageLayoutKey_canLoadMoreAfterChange_changesKey() {
        val a = payload(messages = listOf(message("m1")), canLoadMoreAfter = false)
        val b = payload(messages = listOf(message("m1")), canLoadMoreAfter = true)
        assertNotEquals(a.latestMessageLayoutKeyPublic(), b.latestMessageLayoutKeyPublic())
    }

    @Test
    fun latestMessageListItemIndex_equivalentListReferenceSameContent_keepsIndexEqual() {
        val sharedContent = listOf(message("m1"), message("m2"))
        val a = payload(messages = sharedContent, originStatus = "content")
        val b = payload(messages = sharedContent.toList(), originStatus = "content")
        assertEquals(a.latestMessageListItemIndexPublic(), b.latestMessageListItemIndexPublic())
    }

    @Test
    fun focusedMessageIndex_usesMessageIdSignatureNotListReference() {
        val sharedContent = listOf(message("m1"), message("m2"), message("m3"))
        val a = payload(messages = sharedContent).copy(focusedMessageId = "m2")
        val b = payload(messages = sharedContent.toList()).copy(
            focusedMessageId = "m2",
            members = listOf(KuiklyThreadMemberItem(id = "u1", displayName = "User 1"))
        )

        assertEquals(a.timelineSlice().messageIndexSignature(), b.timelineSlice().messageIndexSignature())
        assertEquals(1, a.timelineSlice().focusedMessageIndex())
        assertEquals(a.timelineSlice().focusedMessageIndex(), b.timelineSlice().focusedMessageIndex())
    }

    @Test
    fun markdownPreparseSignature_ignoresMemberOnlyChanges() {
        val fullRendererBody = "```kotlin\nprintln(\"hi\")\n```"
        val a = payload(messages = listOf(message("m1", body = fullRendererBody)))
        val b = a.copy(members = listOf(KuiklyThreadMemberItem(id = "u1", displayName = "User 1")))

        assertEquals(
            a.timelineSlice().initialMarkdownPreparseCandidateSignature(contentExtrasVisible = true),
            b.timelineSlice().initialMarkdownPreparseCandidateSignature(contentExtrasVisible = true)
        )
    }

    @Test
    fun markdownPreparseSignature_changesWhenLatestBodyChanges() {
        val a = payload(messages = listOf(message("m1", body = "```kotlin\nprintln(\"old\")\n```")))
        val b = payload(messages = listOf(message("m1", body = "```kotlin\nprintln(\"new\")\n```")))

        assertNotEquals(
            a.timelineSlice().initialMarkdownPreparseCandidateSignature(contentExtrasVisible = true),
            b.timelineSlice().initialMarkdownPreparseCandidateSignature(contentExtrasVisible = true)
        )
    }

    @Test
    fun latestMessageListItemIndex_originMessageParticipatesInScrollableItemsAgain() {
        val sig = payload(messages = listOf(message("m1"), message("m2")), originStatus = "content")
            .latestMessageListItemIndexPublic()
        assertEquals(3, sig)
    }

    @Test
    fun latestMessageListItemIndex_emptyMessages_returnsMinusOne() {
        val sig = payload().latestMessageListItemIndexPublic()
        assertEquals(-1, sig)
    }

    @Test
    fun initialFirstVisibleIndex_usesLatestItemInsteadOfTop() {
        assertEquals(0, threadInitialFirstVisibleItemIndex(-1))
        assertEquals(0, threadInitialFirstVisibleItemIndex(0))
        assertEquals(29, threadInitialFirstVisibleItemIndex(29))
    }

    @Test
    fun latestMessageListItemIndex_appendingMessage_changesIndex() {
        val a = payload(messages = listOf(message("m1")), originStatus = "content")
        val b = payload(messages = listOf(message("m1"), message("m2")), originStatus = "content")
        assertNotEquals(a.latestMessageListItemIndexPublic(), b.latestMessageListItemIndexPublic())
    }

    @Test
    fun latestLocalSendBottomAnchor_defaultsReplyThreadReplyListToLatestItem() {
        val pendingContext =
            KuiklyThreadPendingSendContext(
                channelId = "parent-channel",
                body = "reply",
                parentMessageId = "origin-message",
                streamChannelId = "thread-channel"
            )
        val replyThreadPayload =
            payload(
                originStatus = "content",
                messages =
                    listOf(
                        message("reply-1"),
                        message(
                            "local-send-1",
                            body = "reply",
                            deliveryState = ThreadMessageDeliverySending,
                            pendingSendContext = pendingContext
                        )
                    )
            )
        val latestIndex = replyThreadPayload.latestMessageListItemIndexPublic()

        assertEquals(3, latestIndex)
        assertEquals(3, threadInitialFirstVisibleItemIndex(latestIndex))
        assertEquals(
            true,
            shouldAnchorLatestLocalSendToBottom(
                latestLocalSendBottomAnchorKey = replyThreadPayload.latestLocalSendBottomAnchorKeyPublic(),
                latestItemIndex = latestIndex
            )
        )
    }

    @Test
    fun latestLocalSendBottomAnchor_revealsFirstLocalSendBeforeInitialAnchor() {
        val pendingContext =
            KuiklyThreadPendingSendContext(
                channelId = "parent-channel",
                body = "reply",
                parentMessageId = "origin-message",
                streamChannelId = "thread-channel"
            )
        val replyThreadPayload =
            payload(
                originStatus = "loaded",
                messages =
                    listOf(
                        message(
                            "local-send-1",
                            body = "reply",
                            deliveryState = ThreadMessageDeliverySending,
                            pendingSendContext = pendingContext
                        )
                    )
            )

        assertEquals(
            true,
            shouldRevealLatestLocalSendBeforeInitialBottomAnchor(
                initialBottomAnchorReady = false,
                latestLocalSendBottomAnchorKey = replyThreadPayload.latestLocalSendBottomAnchorKeyPublic(),
                latestItemIndex = replyThreadPayload.latestMessageListItemIndexPublic(),
                messageCount = replyThreadPayload.messages.size
            )
        )
        assertEquals(
            false,
            shouldRevealLatestLocalSendBeforeInitialBottomAnchor(
                initialBottomAnchorReady = true,
                latestLocalSendBottomAnchorKey = replyThreadPayload.latestLocalSendBottomAnchorKeyPublic(),
                latestItemIndex = replyThreadPayload.latestMessageListItemIndexPublic(),
                messageCount = replyThreadPayload.messages.size
            )
        )
        assertEquals(
            false,
            shouldRevealLatestLocalSendBeforeInitialBottomAnchor(
                initialBottomAnchorReady = false,
                latestLocalSendBottomAnchorKey = null,
                latestItemIndex = replyThreadPayload.latestMessageListItemIndexPublic(),
                messageCount = replyThreadPayload.messages.size
            )
        )
        assertEquals(
            false,
            shouldRevealLatestLocalSendBeforeInitialBottomAnchor(
                initialBottomAnchorReady = false,
                latestLocalSendBottomAnchorKey = replyThreadPayload.latestLocalSendBottomAnchorKeyPublic(),
                latestItemIndex = -1,
                messageCount = replyThreadPayload.messages.size
            )
        )
    }

    @Test
    fun latestLocalSendBottomAnchor_keepsInitialLoadingForNonFirstLocalSend() {
        val pendingContext =
            KuiklyThreadPendingSendContext(
                channelId = "channel-1",
                body = "second",
                streamChannelId = "channel-1"
            )
        val threadPayload =
            payload(
                messages =
                    listOf(
                        message("m1"),
                        message(
                            "local-send-2",
                            body = "second",
                            deliveryState = ThreadMessageDeliverySending,
                            pendingSendContext = pendingContext
                        )
                    )
            )

        assertEquals(
            false,
            shouldRevealLatestLocalSendBeforeInitialBottomAnchor(
                initialBottomAnchorReady = false,
                latestLocalSendBottomAnchorKey = threadPayload.latestLocalSendBottomAnchorKeyPublic(),
                latestItemIndex = threadPayload.latestMessageListItemIndexPublic(),
                messageCount = threadPayload.messages.size
            )
        )
    }

    @Test
    fun latestLocalSendBottomAnchor_continuesUntilRenderedBottomWithoutUserScroll() {
        assertEquals(
            true,
            shouldContinueLatestLocalSendBottomAnchor(
                activeAnchorKey = "local-send",
                targetAnchorKey = "local-send",
                startUserScrollGeneration = 0,
                currentUserScrollGeneration = 0,
                isAtRenderedBottom = false,
                isSendingAnchorVisible = false,
                attempt = 64
            )
        )
        assertEquals(
            false,
            shouldContinueLatestLocalSendBottomAnchor(
                activeAnchorKey = "local-send",
                targetAnchorKey = "local-send",
                startUserScrollGeneration = 0,
                currentUserScrollGeneration = 0,
                isAtRenderedBottom = true,
                isSendingAnchorVisible = false,
                attempt = 3
            )
        )
    }

    @Test
    fun latestLocalSendBottomAnchor_continuesWhileSendingRowIsVisible() {
        assertEquals(
            true,
            shouldContinueLatestLocalSendBottomAnchor(
                activeAnchorKey = "local-send",
                targetAnchorKey = "local-send",
                startUserScrollGeneration = 0,
                currentUserScrollGeneration = 0,
                isAtRenderedBottom = true,
                isSendingAnchorVisible = true,
                attempt = 10
            )
        )
    }

    @Test
    fun hasSendingLocalSendAnchor_matchesOnlySendingPendingRow() {
        val sending =
            payload(
                messages =
                    listOf(
                        message(
                            "local-send-1",
                            deliveryState = ThreadMessageDeliverySending,
                            pendingSendContext =
                                KuiklyThreadPendingSendContext(
                                    channelId = "channel-1",
                                    body = "pending"
                                )
                        )
                    )
            )
        val sent =
            sending.copy(
                messages = sending.messages.map { message -> message.copy(deliveryState = ThreadMessageDeliverySent) }
            )

        assertEquals(true, sending.hasSendingLocalSendAnchor("local-send-1:7:0:0"))
        assertEquals(false, sent.hasSendingLocalSendAnchor("local-send-1:7:0:0"))
    }

    @Test
    fun autoCorrectBottomAnchor_runsOnlyWhenAutoAnchorAllowedAndIdle() {
        assertEquals(
            true,
            shouldAutoCorrectThreadBottomAnchor(
                ThreadBottomAnchorCorrectionState(
                    allowBottomAutoAnchor = true,
                    isScrollInProgress = false,
                    isAtRenderedBottom = false
                )
            )
        )
        assertEquals(
            false,
            shouldAutoCorrectThreadBottomAnchor(
                ThreadBottomAnchorCorrectionState(
                    allowBottomAutoAnchor = false,
                    isScrollInProgress = false,
                    isAtRenderedBottom = false
                )
            )
        )
        assertEquals(
            false,
            shouldAutoCorrectThreadBottomAnchor(
                ThreadBottomAnchorCorrectionState(
                    allowBottomAutoAnchor = true,
                    isScrollInProgress = true,
                    isAtRenderedBottom = false
                )
            )
        )
        assertEquals(
            false,
            shouldAutoCorrectThreadBottomAnchor(
                ThreadBottomAnchorCorrectionState(
                    allowBottomAutoAnchor = true,
                    isScrollInProgress = false,
                    isAtRenderedBottom = true
                )
            )
        )
    }

    @Test
    fun keyboardBottomAnchor_runsWhenInsetChangesAndBottomAutoAnchorAllowed() {
        assertEquals(
            true,
            shouldAnchorThreadKeyboardBottomOnInsetChange(
                ThreadKeyboardBottomAnchorState(
                    previousKeyboardInsetDp = 0f,
                    nextKeyboardInsetDp = 240f,
                    initialBottomAnchorReady = true,
                    latestItemIndex = 4,
                    allowBottomAutoAnchor = true
                )
            )
        )
        assertEquals(
            false,
            shouldAnchorThreadKeyboardBottomOnInsetChange(
                ThreadKeyboardBottomAnchorState(
                    previousKeyboardInsetDp = 240f,
                    nextKeyboardInsetDp = 240f,
                    initialBottomAnchorReady = true,
                    latestItemIndex = 4,
                    allowBottomAutoAnchor = true
                )
            )
        )
        assertEquals(
            false,
            shouldAnchorThreadKeyboardBottomOnInsetChange(
                ThreadKeyboardBottomAnchorState(
                    previousKeyboardInsetDp = 0f,
                    nextKeyboardInsetDp = 240f,
                    initialBottomAnchorReady = true,
                    latestItemIndex = 4,
                    allowBottomAutoAnchor = false
                )
            )
        )
        assertEquals(
            false,
            shouldAnchorThreadKeyboardBottomOnInsetChange(
                ThreadKeyboardBottomAnchorState(
                    previousKeyboardInsetDp = 0f,
                    nextKeyboardInsetDp = 240f,
                    initialBottomAnchorReady = false,
                    latestItemIndex = 4,
                    allowBottomAutoAnchor = true
                )
            )
        )
        assertEquals(
            false,
            shouldAnchorThreadKeyboardBottomOnInsetChange(
                ThreadKeyboardBottomAnchorState(
                    previousKeyboardInsetDp = 0f,
                    nextKeyboardInsetDp = 240f,
                    initialBottomAnchorReady = true,
                    latestItemIndex = -1,
                    allowBottomAutoAnchor = true
                )
            )
        )
    }

    @Test
    fun originReplyCountProjection_usesLiveReplyListWhenItExceedsOriginCount() {
        assertEquals(
            "3 replies",
            payload(originMessage = message("origin", replyCount = 3))
                .withOriginReplyCountAtLeastLoadedReplies()
                .threadBeginningReplyCountLabel()
        )
        assertEquals(
            "1 reply",
            payload(originMessage = message("origin", replyCount = 1))
                .withOriginReplyCountAtLeastLoadedReplies()
                .threadBeginningReplyCountLabel()
        )
        val projected =
            payload(
                originMessage = message("origin", replyCount = 0),
                messages = listOf(message("reply-1"), message("reply-2"))
            ).withOriginReplyCountAtLeastLoadedReplies()
        assertEquals(2, projected.originMessage?.replyCount)
        assertEquals("2 replies", projected.threadBeginningReplyCountLabel())
        val unchanged =
            payload(
                originMessage = message("origin", replyCount = 5),
                messages = listOf(message("reply-1"), message("reply-2"))
            ).withOriginReplyCountAtLeastLoadedReplies()
        assertEquals(5, unchanged.originMessage?.replyCount)
        assertEquals(
            null,
            payload(messages = listOf(message("m1", replyCount = 5)))
                .withOriginReplyCountAtLeastLoadedReplies()
                .threadBeginningReplyCountLabel()
        )
    }

    @Test
    fun beginningReplyCountLabel_readsOriginProjectionOnly() {
        assertEquals(
            "0 replies",
            payload(
                originMessage = message("origin", replyCount = 0),
                messages = listOf(message("reply-1"), message("reply-2"))
            )
                .threadBeginningReplyCountLabel()
        )
    }

    @Test
    fun backToBottomButton_dependsOnBottomReadinessAndKeyboardAnchor() {
        assertEquals(
            true,
            shouldShowThreadBackToBottomButton(
                ThreadBackToBottomButtonState(
                    initialBottomAnchorReady = true,
                    pendingKeyboardBottomAnchor = false,
                    latestItemOffBottom = true
                )
            )
        )
        assertEquals(
            true,
            shouldShowThreadBackToBottomButton(
                ThreadBackToBottomButtonState(
                    initialBottomAnchorReady = true,
                    pendingKeyboardBottomAnchor = false,
                    latestItemOffBottom = true
                )
            )
        )
        assertEquals(
            false,
            shouldShowThreadBackToBottomButton(
                ThreadBackToBottomButtonState(
                    initialBottomAnchorReady = true,
                    pendingKeyboardBottomAnchor = true,
                    latestItemOffBottom = true
                )
            )
        )
    }

    @Test
    fun backToBottomButton_hidesWhenListCannotScrollTowardBottom() {
        assertEquals(
            false,
            shouldShowThreadBackToBottomButton(
                ThreadBackToBottomButtonState(
                    initialBottomAnchorReady = true,
                    pendingKeyboardBottomAnchor = false,
                    canScrollTowardBottom = false,
                    latestItemOffBottom = true
                )
            )
        )
    }

    @Test
    fun initialBottomAnchor_revealsOnlyAfterLatestRenderedAtBottom() {
        assertEquals(
            false,
            shouldRevealInitialThreadBottomAnchor(
                ThreadInitialBottomAnchorState(
                    canScrollToLatest = true,
                    isAtRenderedBottom = false,
                    attempt = 12
                )
            )
        )
        assertEquals(
            false,
            shouldRevealInitialThreadBottomAnchor(
                ThreadInitialBottomAnchorState(
                    canScrollToLatest = true,
                    isAtRenderedBottom = true,
                    attempt = 1
                )
            )
        )
        assertEquals(
            false,
            shouldRevealInitialThreadBottomAnchor(
                ThreadInitialBottomAnchorState(
                    canScrollToLatest = false,
                    isAtRenderedBottom = true,
                    attempt = 12
                )
            )
        )
        assertEquals(
            true,
            shouldRevealInitialThreadBottomAnchor(
                ThreadInitialBottomAnchorState(
                    canScrollToLatest = true,
                    isAtRenderedBottom = true,
                    attempt = 2
                )
            )
        )
    }

    @Test
    fun initialBottomAnchor_keepsMessageListVisibleWhileAnchorIsPending() {
        assertEquals(
            1f,
            threadMessagesListAlpha(
                initialBottomAnchorReady = false,
                latestItemIndex = 49,
                revealLatestLocalSendBeforeInitialAnchor = false
            )
        )
        assertEquals(
            1f,
            threadMessagesListAlpha(
                initialBottomAnchorReady = true,
                latestItemIndex = 49,
                revealLatestLocalSendBeforeInitialAnchor = false
            )
        )
        assertEquals(
            1f,
            threadMessagesListAlpha(
                initialBottomAnchorReady = false,
                latestItemIndex = 0,
                revealLatestLocalSendBeforeInitialAnchor = true
            )
        )
    }

    @Test
    fun latestLocalSendBottomAnchor_stopsWhenUserScrolls() {
        assertEquals(
            false,
            shouldContinueLatestLocalSendBottomAnchor(
                activeAnchorKey = "local-send",
                targetAnchorKey = "local-send",
                startUserScrollGeneration = 0,
                currentUserScrollGeneration = 1,
                isAtRenderedBottom = false,
                isSendingAnchorVisible = true,
                attempt = 1
            )
        )
    }

    @Test
    fun visibleReadAllMarkerKey_tracksLatestMessageOnly() {
        val a =
            payload(
                messages = listOf(message("m1")),
                members = listOf(KuiklyThreadMemberItem(id = "u1", displayName = "User 1"))
            )
                .copy(status = "content", sourceThreadId = "channel-1")
        val b =
            payload(
                messages = listOf(message("m1")),
                members =
                    listOf(
                        KuiklyThreadMemberItem(id = "u1", displayName = "User 1"),
                        KuiklyThreadMemberItem(id = "u2", displayName = "User 2")
                    )
            ).copy(status = "content", sourceThreadId = "channel-1")
        val c =
            payload(messages = listOf(message("m1"), message("m2")))
                .copy(status = "content", sourceThreadId = "channel-1")

        assertEquals(a.visibleReadAllMarkerKey(), b.visibleReadAllMarkerKey())
        assertNotEquals(a.visibleReadAllMarkerKey(), c.visibleReadAllMarkerKey())
    }

    @Test
    fun actionCardSignature_equivalentMetadataKeepsSignatureEqual() {
        val metadata =
            mapOf(
                "actionCard.kind" to "action-card",
                "actionCard.type" to "channel:create",
                "actionCard.state" to "prepared"
            )
        val a = message("m1", metadata = metadata)
        val b = message("m1", metadata = metadata.toMap())
        assertEquals(a.threadActionCardSignature(), b.threadActionCardSignature())
        assertEquals(a.threadActionCard(), b.threadActionCard())
    }

    @Test
    fun actionCardSignature_stateChangeChangesSignature() {
        val prepared =
            message(
                "m1",
                metadata =
                    mapOf(
                        "actionCard.kind" to "action-card",
                        "actionCard.type" to "channel:create",
                        "actionCard.state" to "prepared"
                    )
            )
        val executed =
            message(
                "m1",
                metadata =
                    mapOf(
                        "actionCard.kind" to "action-card",
                        "actionCard.type" to "channel:create",
                        "actionCard.state" to "executed"
                    )
            )
        assertNotEquals(prepared.threadActionCardSignature(), executed.threadActionCardSignature())
        assertNotEquals(prepared.threadActionCard(), executed.threadActionCard())
    }
}
