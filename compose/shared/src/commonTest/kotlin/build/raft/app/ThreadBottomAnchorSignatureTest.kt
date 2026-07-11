package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

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
        val payload =
            payload(
                messages =
                    listOf(
                        message(
                            "m1",
                            replyCount = 2,
                            unreadReplyCount = 1
                        ).copy(
                            threadChannelId = "thread-m1",
                            firstUnreadReplyMessageId = "reply-1",
                            isThreadFollowed = true
                        )
                    )
            )
        val metadata = payload.toThreadReplyMetadataMap().getValue("m1")
        val timelineMessage = payload.timelineSlice().messages.single()

        assertEquals("m1", metadata.parentMessageId)
        assertEquals(2, metadata.replyCount)
        assertEquals(1, metadata.unreadReplyCount)
        assertEquals("thread-m1", metadata.threadChannelId)
        assertEquals("reply-1", metadata.firstUnreadReplyMessageId)
        assertEquals(true, metadata.isThreadFollowed)
        assertEquals(null, timelineMessage.threadChannelId)
        assertEquals(0, timelineMessage.replyCount)
        assertEquals(0, timelineMessage.unreadReplyCount)
        assertEquals(null, timelineMessage.firstUnreadReplyMessageId)
        assertEquals(false, timelineMessage.isThreadFollowed)
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
    fun focusedMessageListItemIndex_usesMessageIdSignatureNotListReference() {
        val sharedContent = listOf(message("m1"), message("m2"), message("m3"))
        val a = payload(messages = sharedContent).copy(focusedMessageId = "m2")
        val b = payload(messages = sharedContent.toList()).copy(
            focusedMessageId = "m2",
            members = listOf(KuiklyThreadMemberItem(id = "u1", displayName = "User 1"))
        )

        assertEquals(a.timelineSlice().messageIndexSignature(), b.timelineSlice().messageIndexSignature())
        assertEquals(2, a.timelineSlice().focusedMessageListItemIndex())
        assertEquals(a.timelineSlice().focusedMessageListItemIndex(), b.timelineSlice().focusedMessageListItemIndex())
        assertEquals(a.timelineSlice().focusedMessageTargetSignature(), b.timelineSlice().focusedMessageTargetSignature())
    }

    @Test
    fun focusedMessageAnchorIdentityChangesForRepeatedOpenIntentNonce() {
        val first = focusedMessageAnchorIdentity(messageId = "m2", requestNonce = 1)
        val second = focusedMessageAnchorIdentity(messageId = "m2", requestNonce = 2)

        assertNotEquals(first, second)
    }

    @Test
    fun focusedMessageAnchorIdentityIgnoresTimelineGrowth() {
        // task #554: secondary refresh / realtime arrivals / older pages change
        // the timeline signature and lazy item count — the request identity must
        // NOT change with them, or a consumed anchor re-scrolls mid-drag.
        val identity = focusedMessageAnchorIdentity(messageId = "m2", requestNonce = 1)

        assertEquals(identity, focusedMessageAnchorIdentity(messageId = " m2 ", requestNonce = 1))
        assertFalse(
            shouldRunFocusedMessageAnchor(
                targetIdentity = identity,
                consumedIdentity = identity,
                scrollInterruptedIdentity = null
            )
        )
    }

    @Test
    fun focusedMessageAnchorWaitsForLoadingOriginOnly() {
        // task #567: a "loading" origin placeholder above the timeline still
        // changes size when it resolves — the anchor must hold until then.
        assertFalse(focusedMessageAnchorOriginSettled("loading"))
        // Every terminal state anchors immediately.
        assertTrue(focusedMessageAnchorOriginSettled("none"))
        assertTrue(focusedMessageAnchorOriginSettled("loaded"))
        assertTrue(focusedMessageAnchorOriginSettled("missing"))
        assertTrue(focusedMessageAnchorOriginSettled("error"))
    }

    @Test
    fun focusedAnchorSessionInterruptBeatsEverything() {
        // #554 semantics survive inside the session: the user always wins,
        // even at the cap or mid-drift.
        assertEquals(
            FocusedAnchorSessionAction.Interrupt,
            focusedAnchorSessionAction(
                userInteracting = true,
                drifted = true,
                quietMs = FocusedAnchorSessionQuietMs,
                elapsedMs = FocusedAnchorSessionHardCapMs
            )
        )
    }

    @Test
    fun focusedAnchorSessionHardCapConsumesEvenWhileDrifting() {
        assertEquals(
            FocusedAnchorSessionAction.Consume,
            focusedAnchorSessionAction(
                userInteracting = false,
                drifted = true,
                quietMs = 0L,
                elapsedMs = FocusedAnchorSessionHardCapMs
            )
        )
    }

    @Test
    fun focusedAnchorSessionDriftReanchorsBeforeQuietConsumption() {
        assertEquals(
            FocusedAnchorSessionAction.Reanchor,
            focusedAnchorSessionAction(
                userInteracting = false,
                drifted = true,
                quietMs = FocusedAnchorSessionQuietMs,
                elapsedMs = FocusedAnchorSessionQuietMs
            )
        )
    }

    @Test
    fun focusedAnchorSessionQuietWindowConsumes() {
        assertEquals(
            FocusedAnchorSessionAction.Consume,
            focusedAnchorSessionAction(
                userInteracting = false,
                drifted = false,
                quietMs = FocusedAnchorSessionQuietMs,
                elapsedMs = FocusedAnchorSessionQuietMs
            )
        )
    }

    @Test
    fun focusedAnchorSessionOtherwiseContinuesWatching() {
        assertEquals(
            FocusedAnchorSessionAction.Continue,
            focusedAnchorSessionAction(
                userInteracting = false,
                drifted = false,
                quietMs = FocusedAnchorSessionQuietMs - 1,
                elapsedMs = FocusedAnchorSessionHardCapMs - 1
            )
        )
    }

    @Test
    fun focusedAnchorDriftPredicateHandlesVisibilityAndTolerance() {
        // Target left the viewport = always drift (injection case: an item
        // above grows enough to push the target out).
        assertTrue(focusedAnchorTargetDrifted(anchoredOffset = 120, currentOffset = null))
        // No baseline (target was never measured at anchor time) = no drift.
        assertFalse(focusedAnchorTargetDrifted(anchoredOffset = null, currentOffset = 80))
        // Layout rounding jitter within tolerance is not drift.
        assertFalse(
            focusedAnchorTargetDrifted(
                anchoredOffset = 120,
                currentOffset = 120 + FocusedAnchorDriftTolerancePx
            )
        )
        // A real shift beyond tolerance re-anchors, in either direction —
        // this is the injected "item above grew after anchoring" assertion.
        assertTrue(
            focusedAnchorTargetDrifted(
                anchoredOffset = 120,
                currentOffset = 120 + FocusedAnchorDriftTolerancePx + 1
            )
        )
        assertTrue(
            focusedAnchorTargetDrifted(
                anchoredOffset = 120,
                currentOffset = 120 - FocusedAnchorDriftTolerancePx - 1
            )
        )
    }

    @Test
    fun bottomAnchorYieldsWhileFocusedAnchorPendingWithinCap() {
        val identity = focusedMessageAnchorIdentity(messageId = "m2", requestNonce = 1)
        // Pending focused anchor holds the bottom anchor off entirely —
        // "zero scrolls while the focused identity is active".
        assertTrue(
            shouldYieldBottomAnchorToFocusedAnchor(
                pendingIdentity = identity,
                consumedIdentity = null,
                scrollInterruptedIdentity = null,
                yieldElapsedMs = 0L
            )
        )
        // Consumed or interrupted identities release the yield immediately.
        assertFalse(
            shouldYieldBottomAnchorToFocusedAnchor(
                pendingIdentity = identity,
                consumedIdentity = identity,
                scrollInterruptedIdentity = null,
                yieldElapsedMs = 0L
            )
        )
        assertFalse(
            shouldYieldBottomAnchorToFocusedAnchor(
                pendingIdentity = identity,
                consumedIdentity = null,
                scrollInterruptedIdentity = identity,
                yieldElapsedMs = 0L
            )
        )
        // No focused request at all = normal bottom anchoring.
        assertFalse(
            shouldYieldBottomAnchorToFocusedAnchor(
                pendingIdentity = null,
                consumedIdentity = null,
                scrollInterruptedIdentity = null,
                yieldElapsedMs = 0L
            )
        )
        // Past the cap the fallback wins so a dead target can't blank the entry.
        assertFalse(
            shouldYieldBottomAnchorToFocusedAnchor(
                pendingIdentity = identity,
                consumedIdentity = null,
                scrollInterruptedIdentity = null,
                yieldElapsedMs = FocusedAnchorBottomYieldCapMs
            )
        )
    }

    @Test
    fun focusedMessageAnchorDoesNotRerunAfterUserScrollInterruption() {
        val identity = focusedMessageAnchorIdentity(messageId = "m2", requestNonce = 1)

        assertFalse(
            shouldRunFocusedMessageAnchor(
                targetIdentity = identity,
                consumedIdentity = null,
                scrollInterruptedIdentity = identity
            )
        )
    }

    @Test
    fun focusedMessageAnchorRerunsForNewNonceAfterConsumeOrInterruption() {
        val consumed = focusedMessageAnchorIdentity(messageId = "m2", requestNonce = 1)
        val renewed = focusedMessageAnchorIdentity(messageId = "m2", requestNonce = 2)

        assertTrue(
            shouldRunFocusedMessageAnchor(
                targetIdentity = renewed,
                consumedIdentity = consumed,
                scrollInterruptedIdentity = consumed
            )
        )
    }

    @Test
    fun focusedThreadListItemIndex_mapsOriginButDoesNotScrollToIt() {
        val slice =
            payload(
                originStatus = "content",
                originMessage = message("parent-message"),
                messages = listOf(message("reply-1"), message("reply-2"))
            ).copy(focusedMessageId = "parent-message").timelineSlice()

        assertEquals(0, slice.threadMessageListItemIndex("parent-message"))
        assertEquals(2, slice.threadMessageListItemIndex("reply-1"))
        assertEquals(-1, slice.focusedMessageListItemIndex())
        assertTrue(slice.isFocusedOriginMessage("parent-message"))
        assertFalse(slice.isFocusedOriginMessage("reply-1"))
    }

    @Test
    fun focusedOriginTargetSignature_changesWhenOriginMessageHydrates() {
        val loading =
            payload(
                originStatus = "loading",
                originMessage = null,
                messages = listOf(message("reply-1"))
            ).copy(focusedMessageId = "parent-message").timelineSlice()
        val loaded =
            payload(
                originStatus = "content",
                originMessage = message("parent-message"),
                messages = listOf(message("reply-1"))
            ).copy(focusedMessageId = "parent-message").timelineSlice()

        assertEquals(-1, loading.focusedMessageListItemIndex())
        assertEquals(-1, loaded.focusedMessageListItemIndex())
        assertNotEquals(loading.focusedMessageTargetSignature(), loaded.focusedMessageTargetSignature())
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
    fun markdownPreparseSignature_changesWhenOlderLoadedBodyChanges() {
        val messagesA =
            (0 until ThreadInitialMarkdownPreparseMessageCount + 2).map { index ->
                message("m$index", body = "body-$index")
            }
        val messagesB =
            messagesA.mapIndexed { index, item ->
                if (index == 0) item.copy(body = "body-older-updated") else item
            }

        assertNotEquals(
            payload(messages = messagesA).timelineSlice().initialMarkdownPreparseCandidateSignature(contentExtrasVisible = true),
            payload(messages = messagesB).timelineSlice().initialMarkdownPreparseCandidateSignature(contentExtrasVisible = true)
        )
    }

    @Test
    fun markdownPreparsePriority_usesFocusedMessageNeighborhood() {
        val messages = (0 until 10).map { index -> message("m$index") }
        val slice = payload(messages = messages).copy(focusedMessageId = "m5").timelineSlice()

        assertEquals(
            listOf("m5", "m4", "m6", "m3"),
            slice.initialMarkdownPreparsePriorityCandidateIds(contentExtrasVisible = true)
        )
    }

    @Test
    fun markdownPreparsePriority_alwaysIncludesOriginWithFocusedMessageNeighborhood() {
        val messages = (0 until 10).map { index -> message("m$index") }
        val slice =
            payload(
                originStatus = "content",
                originMessage = message("origin"),
                messages = messages
            ).copy(focusedMessageId = "m5").timelineSlice()

        assertEquals(
            listOf("origin:origin", "m5", "m4", "m6", "m3"),
            slice.initialMarkdownPreparsePriorityCandidateIds(contentExtrasVisible = true)
        )
    }

    @Test
    fun markdownPreparsePriority_usesLatestMessagesWhenFocusMissing() {
        val messages = (0 until 10).map { index -> message("m$index") }
        val slice = payload(messages = messages).copy(focusedMessageId = "missing").timelineSlice()

        assertEquals(
            listOf("m9", "m8", "m7", "m6"),
            slice.initialMarkdownPreparsePriorityCandidateIds(contentExtrasVisible = true)
        )
    }

    @Test
    fun markdownPreparsePriority_alwaysIncludesOriginWhenFocusMissing() {
        val messages = (0 until 10).map { index -> message("m$index") }
        val slice =
            payload(
                originStatus = "content",
                originMessage = message("origin"),
                messages = messages
            ).copy(focusedMessageId = "missing").timelineSlice()

        assertEquals(
            listOf("origin:origin", "m9", "m8", "m7", "m6"),
            slice.initialMarkdownPreparsePriorityCandidateIds(contentExtrasVisible = true)
        )
    }

    @Test
    fun markdownPreparsePriority_includesOriginWhenOriginIsFocused() {
        val messages = (0 until 5).map { index -> message("m$index") }
        val slice =
            payload(
                originStatus = "content",
                originMessage = message("origin"),
                messages = messages
            ).copy(focusedMessageId = "origin").timelineSlice()

        assertEquals(-1, slice.focusedMessageListItemIndex())
        assertEquals(
            listOf("origin:origin", "m4", "m3", "m2", "m1"),
            slice.initialMarkdownPreparsePriorityCandidateIds(contentExtrasVisible = true)
        )
    }

    @Test
    fun markdownPreparseOrder_keepsFocusedWindowBeforeLatestRemainder() {
        val messages = (0 until 8).map { index -> message("m$index") }
        val slice = payload(messages = messages).copy(focusedMessageId = "m3").timelineSlice()

        assertEquals(
            listOf("m3", "m2", "m4", "m1", "m5", "m0", "m6", "m7"),
            slice.initialMarkdownPreparseCandidateIds(contentExtrasVisible = true)
        )
    }

    @Test
    fun visibleMarkdownPreparse_usesVisibleWindowAndOverscan() {
        val messages = (0 until 8).map { index -> message("m$index") }
        val slice = payload(messages = messages).timelineSlice()

        assertEquals(
            listOf("m2", "m3", "m4"),
            slice.visibleMarkdownPreparseCandidateIds(
                firstVisibleItemIndex = 4,
                lastVisibleItemIndex = 4,
                overscanItems = 1,
                contentExtrasVisible = true
            )
        )
    }

    @Test
    fun visibleMarkdownPreparse_includesOriginWhenOverscanTouchesOrigin() {
        val messages = (0 until 4).map { index -> message("m$index") }
        val slice =
            payload(
                originStatus = "content",
                originMessage = message("origin"),
                messages = messages
            ).timelineSlice()

        assertEquals(
            listOf("origin:origin", "m0", "m1", "m2"),
            slice.visibleMarkdownPreparseCandidateIds(
                firstVisibleItemIndex = 2,
                lastVisibleItemIndex = 2,
                overscanItems = 2,
                contentExtrasVisible = true
            )
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
        assertEquals(
            false,
            shouldAutoCorrectThreadBottomAnchor(
                ThreadBottomAnchorCorrectionState(
                    allowBottomAutoAnchor = true,
                    isScrollInProgress = false,
                    isAtRenderedBottom = false,
                    focusedAnchorOwnsViewport = true
                )
            )
        )
    }

    @Test
    fun focusedAnchorOwnershipKeepsBottomAutoAnchorDisabledUntilRequestRetires() {
        val identity = focusedMessageAnchorIdentity(messageId = "m2", requestNonce = 1)

        assertTrue(
            focusedMessageAnchorOwnsViewport(
                pendingIdentity = identity,
                consumedIdentity = null,
                scrollInterruptedIdentity = null,
                yieldElapsedMs = FocusedAnchorBottomYieldCapMs - 1
            )
        )
        assertFalse(
            resolveThreadBottomAutoAnchorAllowed(
                ThreadBottomAutoAnchorAvailabilityState(
                    currentAllow = false,
                    isScrollInProgress = false,
                    isAtRenderedBottom = true,
                    focusedAnchorOwnsViewport = true
                )
            )
        )
        assertFalse(
            focusedMessageAnchorOwnsViewport(
                pendingIdentity = identity,
                consumedIdentity = identity,
                scrollInterruptedIdentity = null,
                yieldElapsedMs = FocusedAnchorBottomYieldCapMs - 1
            )
        )
        assertTrue(
            resolveThreadBottomAutoAnchorAllowed(
                ThreadBottomAutoAnchorAvailabilityState(
                    currentAllow = false,
                    isScrollInProgress = false,
                    isAtRenderedBottom = true,
                    focusedAnchorOwnsViewport = false
                )
            )
        )
        assertFalse(
            focusedMessageAnchorOwnsViewport(
                pendingIdentity = identity,
                consumedIdentity = null,
                scrollInterruptedIdentity = null,
                yieldElapsedMs = FocusedAnchorBottomYieldCapMs
            )
        )
        assertTrue(
            resolveThreadBottomAutoAnchorAllowed(
                ThreadBottomAutoAnchorAvailabilityState(
                    currentAllow = false,
                    isScrollInProgress = false,
                    isAtRenderedBottom = true,
                    focusedAnchorOwnsViewport = false
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
    fun visibleReadMarkerKey_tracksLatestMessageOnly() {
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

        assertEquals(a.visibleReadMarkerKey(), b.visibleReadMarkerKey())
        assertNotEquals(a.visibleReadMarkerKey(), c.visibleReadMarkerKey())
    }

    @Test
    fun visibleRead_requiresActiveForegroundThread() {
        val marker = "channel-1:11:m2"

        assertTrue(
            shouldMarkThreadVisibleRead(
                routeActive = true,
                observedHostForeground = true,
                currentHostVisibility = true,
                markerKey = marker
            )
        )
        assertFalse(
            shouldMarkThreadVisibleRead(
                routeActive = true,
                observedHostForeground = false,
                currentHostVisibility = true,
                markerKey = marker
            )
        )
        assertFalse(
            shouldMarkThreadVisibleRead(
                routeActive = false,
                observedHostForeground = true,
                currentHostVisibility = true,
                markerKey = marker
            )
        )
        assertFalse(
            shouldMarkThreadVisibleRead(
                routeActive = true,
                observedHostForeground = true,
                currentHostVisibility = true,
                markerKey = null
            )
        )
        assertFalse(
            shouldMarkThreadVisibleRead(
                routeActive = true,
                observedHostForeground = true,
                currentHostVisibility = false,
                markerKey = marker
            )
        )
    }

    @Test
    fun threadLifecycle_tracksAndroidAndSharedHostVisibility() {
        assertTrue(threadLifecycleEventTargetsThread(THREAD_PAGE))
        assertTrue(threadLifecycleEventTargetsThread(SLOCK_APP_SHELL_PAGE))
        assertFalse(threadLifecycleEventTargetsThread("compose_slock_settings_home"))
        assertTrue(appLifecycleEventTargetsShell("", "legacy-app-shell"))
        assertFalse(appLifecycleEventTargetsShell("", "shell-a"))
        assertTrue(appLifecycleEventTargetsShell("shell-a", "shell-a"))
        assertFalse(appLifecycleEventTargetsShell("shell-b", "shell-a"))
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

    @Test
    fun markdownRenderSignature_changesWhenSameMessageBodyChanges() {
        val emptyFirstPaint = message("m1", body = "")
        val hydratedBody = message("m1", body = "Loaded reply body with #channel and @artin")

        assertNotEquals(emptyFirstPaint.threadMarkdownRenderSignature(), hydratedBody.threadMarkdownRenderSignature())
        assertNotEquals(
            emptyFirstPaint.threadMarkdownRenderSignature().inlineContentScope,
            hydratedBody.threadMarkdownRenderSignature().inlineContentScope
        )
    }

    @Test
    fun markdownRenderSignature_ignoresUnrelatedMessageChromeChanges() {
        val base = message("m1", body = "Stable body with #123")
        val chromeOnlyChange =
            base.copy(
                avatarWireValue = "https://example.com/avatar.png",
                replyCount = 3,
                unreadReplyCount = 1,
                isSaved = true
            )

        assertEquals(base.threadMarkdownRenderSignature(), chromeOnlyChange.threadMarkdownRenderSignature())
        assertEquals(
            base.threadMarkdownRenderSignature().inlineContentScope,
            chromeOnlyChange.threadMarkdownRenderSignature().inlineContentScope
        )
    }

    @Test
    fun rowDerivedSignatures_ignoreUnrelatedMessageChromeChanges() {
        val metadata =
            mapOf(
                "actionCard.kind" to "action-card",
                "actionCard.type" to "channel:create",
                "actionCard.state" to "prepared"
            )
        val base =
            message(
                "m1",
                body = "original body",
                metadata = metadata
            ).copy(
                authorId = "user-1",
                authorName = "User 1",
                timestampLabel = "2026-06-28T01:00:00.000Z"
            )
        val chromeOnlyChange =
            base.copy(
                body = "edited body",
                avatarWireValue = "https://example.com/avatar.png",
                replyCount = 3,
                unreadReplyCount = 1,
                isSaved = true
            )

        assertEquals(base.threadActionCardSignature(), chromeOnlyChange.threadActionCardSignature())
        assertEquals(base.threadCompactAuthorSignature(), chromeOnlyChange.threadCompactAuthorSignature())
    }

    @Test
    fun compactAuthorSignature_changesWhenGroupingInputsChange() {
        val base =
            message("m1")
                .copy(
                    authorId = "user-1",
                    authorName = "User 1",
                    timestampLabel = "2026-06-28T01:00:00.000Z"
                )

        assertNotEquals(
            base.threadCompactAuthorSignature(),
            base.copy(authorId = "user-2").threadCompactAuthorSignature()
        )
        assertNotEquals(
            base.threadCompactAuthorSignature(),
            base.copy(timestampLabel = "2026-06-28T01:06:00.000Z").threadCompactAuthorSignature()
        )
        assertNotEquals(
            base.threadCompactAuthorSignature(),
            base.copy(messageType = "System").threadCompactAuthorSignature()
        )
    }
}
