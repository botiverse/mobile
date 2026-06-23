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
        canLoadMoreBefore: Boolean = false,
        canLoadMoreAfter: Boolean = false,
        members: List<KuiklyThreadMemberItem> = emptyList()
    ): KuiklyThreadScreenPayload = KuiklyThreadScreenPayload(
        originStatus = originStatus,
        messages = messages,
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
    fun latestMessageLayoutKey_replyCountChange_changesKey() {
        val a = payload(messages = listOf(message("m1", replyCount = 1)))
        val b = payload(messages = listOf(message("m1", replyCount = 2)))
        assertNotEquals(a.latestMessageLayoutKeyPublic(), b.latestMessageLayoutKeyPublic())
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
    fun latestMessageListItemIndex_originMessageParticipatesInScrollableItems() {
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
    fun latestLocalSendBottomAnchor_allowsReplyThreadOriginList() {
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

        assertEquals(0, threadInitialFirstVisibleItemIndex(latestIndex, topAnchoredContent = true))
        assertEquals(3, latestIndex)
        assertEquals(
            true,
            shouldAnchorLatestLocalSendToBottom(
                latestLocalSendBottomAnchorKey = replyThreadPayload.latestLocalSendBottomAnchorKeyPublic(),
                latestItemIndex = latestIndex
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
