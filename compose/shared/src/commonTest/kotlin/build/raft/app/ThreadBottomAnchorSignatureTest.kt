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
        pendingSendContext: KuiklyThreadPendingSendContext? = null,
        randomId: String? = null
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
        pendingSendContext = pendingSendContext,
        randomId = randomId
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
    fun focusedMessageAnchorDoesNotDependOnOriginSettlement() {
        // Hands eeb5ede7: MessageContext is the focused route's primary
        // authority. The target request is runnable as soon as its row exists;
        // parent/origin hydration is an independent late layout mutation that
        // the bounded session may correct without delaying first focus.
        val identity = focusedMessageAnchorIdentity(messageId = "m2", requestNonce = 1)
        assertTrue(
            shouldRunFocusedMessageAnchor(
                targetIdentity = identity,
                consumedIdentity = null,
                scrollInterruptedIdentity = null
            )
        )
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
    fun lateOriginCorrectionRunsOnceAfterQuietSessionWhenUserDidNotMove() {
        val identity = focusedMessageAnchorIdentity(messageId = "m2", requestNonce = 1)

        assertTrue(
            shouldCorrectFocusedAnchorAfterOriginHydration(
                originStatus = "loaded",
                targetIdentity = identity,
                anchoredIdentity = identity,
                correctedIdentity = null,
                scrollInterruptedIdentity = null,
                startUserScrollGeneration = 3,
                currentUserScrollGeneration = 3
            )
        )
        assertFalse(
            shouldCorrectFocusedAnchorAfterOriginHydration(
                originStatus = "loaded",
                targetIdentity = identity,
                anchoredIdentity = identity,
                correctedIdentity = identity,
                scrollInterruptedIdentity = null,
                startUserScrollGeneration = 3,
                currentUserScrollGeneration = 3
            )
        )
    }

    @Test
    fun lateOriginCorrectionNeverRunsAfterUserScroll() {
        val identity = focusedMessageAnchorIdentity(messageId = "m2", requestNonce = 1)

        assertFalse(
            shouldCorrectFocusedAnchorAfterOriginHydration(
                originStatus = "loaded",
                targetIdentity = identity,
                anchoredIdentity = identity,
                correctedIdentity = null,
                scrollInterruptedIdentity = identity,
                startUserScrollGeneration = 3,
                currentUserScrollGeneration = 4
            )
        )
    }

    @Test
    fun focusedAnchorSessionTreatsExplicitViewportReplacementAsUserOverride() {
        val targetIdentity = focusedMessageAnchorIdentity(messageId = "m2", requestNonce = 1)
        val staleSessionIdentity = focusedMessageAnchorIdentity(messageId = "m1", requestNonce = 1)

        // Hands edecc106: BackToBottom retires the Activity-focused anchor
        // before scrolling. Even when scrollToItem has already settled and
        // isScrollInProgress is false, that retired identity must interrupt
        // the stabilization session instead of re-anchoring the old target.
        assertEquals(
            targetIdentity,
            focusedAnchorIdentityForViewportReplacement(
                pendingIdentity = targetIdentity,
                sessionIdentity = staleSessionIdentity
            )
        )
        assertEquals(
            staleSessionIdentity,
            focusedAnchorIdentityForViewportReplacement(
                pendingIdentity = null,
                sessionIdentity = staleSessionIdentity
            )
        )
        assertTrue(
            focusedAnchorSessionUserOverrideActive(
                startUserScrollGeneration = 7,
                currentUserScrollGeneration = 7,
                targetIdentity = targetIdentity,
                scrollInterruptedIdentity = targetIdentity
            )
        )
        assertFalse(
            focusedAnchorSessionUserOverrideActive(
                startUserScrollGeneration = 7,
                currentUserScrollGeneration = 7,
                targetIdentity = targetIdentity,
                scrollInterruptedIdentity = focusedMessageAnchorIdentity("other", 1)
            )
        )
    }

    @Test
    fun focusedAnchorSessionOnlyTreatsProvenUserScrollAsOverride() {
        val targetIdentity = focusedMessageAnchorIdentity(messageId = "m2", requestNonce = 1)

        // LazyListState.isScrollInProgress also covers programmatic scroll actors.
        // User intent is proven by the direct-pointer generation fence instead.
        assertFalse(
            focusedAnchorSessionUserOverrideActive(
                startUserScrollGeneration = 4,
                currentUserScrollGeneration = 4,
                targetIdentity = targetIdentity,
                scrollInterruptedIdentity = null
            )
        )
        assertTrue(
            focusedAnchorSessionUserOverrideActive(
                startUserScrollGeneration = 4,
                currentUserScrollGeneration = 5,
                targetIdentity = targetIdentity,
                scrollInterruptedIdentity = null
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
    fun focusedReplyTargetSignature_ignoresIndependentOriginHydration() {
        val loading =
            payload(
                originStatus = "loading",
                originMessage = null,
                messages = listOf(message("reply-1"), message("reply-2"))
            ).copy(
                originParentMessageId = "parent-message",
                focusedMessageId = "reply-1"
            ).timelineSlice()
        val loaded =
            payload(
                originStatus = "content",
                originMessage = message("parent-message"),
                messages = listOf(message("reply-1"), message("reply-2"))
            ).copy(
                originParentMessageId = "parent-message",
                focusedMessageId = "reply-1"
            ).timelineSlice()

        assertEquals(loading.focusedMessageListItemIndex(), loaded.focusedMessageListItemIndex())
        assertEquals(loading.focusedMessageTargetSignature(), loaded.focusedMessageTargetSignature())
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
            (0 until 100).map { index ->
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
    fun localAndCanonicalSendWithSameRandomIdKeepRenderIdentityStable() {
        val local = message("local-send-1", body = "sent body", randomId = "rid-1")
        val canonical = message("server-message-1", body = "sent body", randomId = "rid-1")

        assertEquals(local.threadMessageRenderIdentity(), canonical.threadMessageRenderIdentity())
        assertEquals(local.messageListItemKey(), canonical.messageListItemKey())
        assertEquals(local.threadMarkdownRenderSignature(), canonical.threadMarkdownRenderSignature())
        assertEquals(
            payload(messages = listOf(local)).timelineSlice().initialMarkdownPreparseCandidateSignature(true),
            payload(messages = listOf(canonical)).timelineSlice().initialMarkdownPreparseCandidateSignature(true)
        )
    }

    @Test
    fun renderIdentityFallsBackToMessageIdAndNeverMergesDifferentRandomIds() {
        val first = message("message-1", body = "same", randomId = "rid-1")
        val second = message("message-2", body = "same", randomId = "rid-2")
        val missingFirst = message("message-1", body = "same")
        val missingSecond = message("message-2", body = "same")

        assertNotEquals(first.messageListItemKey(), second.messageListItemKey())
        assertNotEquals(missingFirst.messageListItemKey(), missingSecond.messageListItemKey())
    }

    @Test
    fun duplicateRandomIdsFallBackToDistinctMessageKeys() {
        val first = message("message-1", body = "same", randomId = "rid-collision")
        val second = message("message-2", body = "same", randomId = "rid-collision")
        val messages = listOf(first, second)
        val duplicateRandomIds = messages.threadDuplicateRandomIds()

        assertEquals(setOf("rid-collision"), duplicateRandomIds)
        assertNotEquals(
            first.messageListItemKey(duplicateRandomIds),
            second.messageListItemKey(duplicateRandomIds)
        )
    }

    @Test
    fun sameRandomIdStillInvalidatesMarkdownWhenBodyChanges() {
        val before = message("local-send-1", body = "draft", randomId = "rid-1")
        val after = message("server-message-1", body = "canonical", randomId = "rid-1")

        assertEquals(before.messageListItemKey(), after.messageListItemKey())
        assertNotEquals(before.threadMarkdownRenderSignature(), after.threadMarkdownRenderSignature())
        assertNotEquals(
            payload(messages = listOf(before)).timelineSlice().initialMarkdownPreparseCandidateSignature(true),
            payload(messages = listOf(after)).timelineSlice().initialMarkdownPreparseCandidateSignature(true)
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
    fun markdownPreparseCandidates_areBoundedToFocusedPriorityWindow() {
        val messages = (0 until 8).map { index -> message("m$index") }
        val slice = payload(messages = messages).copy(focusedMessageId = "m3").timelineSlice()

        assertEquals(
            listOf("m3", "m2", "m4", "m1"),
            slice.initialMarkdownPreparseCandidateIds(contentExtrasVisible = true)
        )
    }

    @Test
    fun jointSizedTimeline_retainsHistoryButBoundsInitialMarkdownWork() {
        val messages = (0 until 100).map { index -> message("m$index", body = "body-$index") }
        val slice = payload(messages = messages).timelineSlice()

        assertEquals(100, slice.messages.size)
        assertEquals(
            listOf("m99", "m98", "m97", "m96"),
            slice.initialMarkdownPreparseCandidateIds(contentExtrasVisible = true)
        )
        assertEquals(
            listOf("m95", "m96", "m97", "m98", "m99"),
            slice.visibleMarkdownPreparseCandidateIds(
                firstVisibleItemIndex = 100,
                lastVisibleItemIndex = 100,
                overscanItems = 4,
                contentExtrasVisible = true
            )
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
    fun visibleMarkdownPreparseWarmsOneMeasuredViewportBeforeOverscan() {
        val messages = (0 until 20).map { index -> message("m$index") }
        val slice = payload(messages = messages).timelineSlice()

        assertEquals(
            (3..14).map { index -> "m$index" },
            slice.visibleMarkdownPreparseCandidateIds(
                firstVisibleItemIndex = 10,
                lastVisibleItemIndex = 14,
                overscanItems = 1,
                prewarmBeforeItems = 5,
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
    fun visibleMarkdownPreparseRunsWhenLatestPriorityBodiesAreBlank() {
        val messages =
            listOf(
                message("older-complex", body = "```kotlin\nprintln(\"older\")\n```"),
                message("m1", body = ""),
                message("m2", body = ""),
                message("m3", body = ""),
                message("m4", body = "")
            )
        val slice = payload(messages = messages).timelineSlice()

        assertTrue(slice.initialMarkdownPreparseCandidateIds(contentExtrasVisible = true).isEmpty())
        assertEquals(
            listOf("older-complex"),
            slice.visibleMarkdownPreparseCandidateIds(
                firstVisibleItemIndex = 1,
                lastVisibleItemIndex = 1,
                overscanItems = 0,
                contentExtrasVisible = true
            )
        )
    }

    @Test
    fun visibleMarkdownPreparseUsesUpdatedOffscreenBody() {
        val before =
            payload(
                messages =
                    listOf(
                        message("older", body = "```kotlin\nprintln(\"before\")\n```"),
                        message("m1", body = "latest-1"),
                        message("m2", body = "latest-2"),
                        message("m3", body = "latest-3"),
                        message("m4", body = "latest-4")
                    )
            ).timelineSlice()
        val after =
            before.copy(
                messages =
                    before.messages.map { message ->
                        if (message.id == "older") {
                            message.copy(body = "```kotlin\nprintln(\"after\")\n```")
                        } else {
                            message
                        }
                    }
            )

        assertEquals(
            before.initialMarkdownPreparseCandidateIds(contentExtrasVisible = true),
            after.initialMarkdownPreparseCandidateIds(contentExtrasVisible = true)
        )
        assertNotEquals(
            before.visibleMarkdownPreparseCandidateSignature(
                firstVisibleItemIndex = 1,
                lastVisibleItemIndex = 1,
                overscanItems = 0,
                contentExtrasVisible = true
            ),
            after.visibleMarkdownPreparseCandidateSignature(
                firstVisibleItemIndex = 1,
                lastVisibleItemIndex = 1,
                overscanItems = 0,
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
    fun initialFirstVisibleIndex_usesAtomicLatestViewport() {
        assertEquals(0, threadInitialFirstVisibleItemIndex(-1))
        assertEquals(0, threadInitialFirstVisibleItemIndex(0))
        assertEquals(29, threadInitialFirstVisibleItemIndex(29))
    }

    @Test
    fun initialBottomAnchor_targetsDrawableLatestMessageBeforeSyntheticPadding() {
        assertEquals(
            30,
            threadInitialBottomAnchorTargetItemIndex(
                latestMessageItemIndex = 30,
                bottomPaddingItemIndex = 31
            )
        )
        assertEquals(
            0,
            threadInitialBottomAnchorTargetItemIndex(
                latestMessageItemIndex = -1,
                bottomPaddingItemIndex = 0
            )
        )
    }

    @Test
    fun initialBottomAnchor_invisibleTargetAdvancesThenContinuesAnchoring() {
        assertEquals(
            ThreadInitialBottomAnchorStage.AdvanceThenAnchor,
            threadInitialBottomAnchorStage(
                initialBottomAnchorReady = false,
                userScrollInProgress = false,
                latestItemVisible = false
            )
        )
        assertEquals(
            ThreadInitialBottomAnchorStage.Anchor,
            threadInitialBottomAnchorStage(
                initialBottomAnchorReady = false,
                userScrollInProgress = false,
                latestItemVisible = true
            )
        )
    }

    @Test
    fun initialBottomAnchor_deltaAdvancesByViewportThenAlignsDrawableTarget() {
        assertEquals(
            24,
            threadInitialBottomAnchorScrollDelta(
                itemOffset = 840,
                itemSize = 36,
                trailingPaddingSize = 24,
                viewportEndOffset = 876
            )
        )
        assertEquals(
            0,
            threadInitialBottomAnchorScrollDelta(
                itemOffset = 816,
                itemSize = 36,
                trailingPaddingSize = 24,
                viewportEndOffset = 876
            )
        )
    }

    @Test
    fun initialBottomAnchor_userGestureOwnsPendingViewport() {
        assertEquals(
            ThreadInitialBottomAnchorStage.UserOwned,
            threadInitialBottomAnchorStage(
                initialBottomAnchorReady = false,
                userScrollInProgress = true,
                latestItemVisible = false
            )
        )
        assertEquals(
            ThreadInitialBottomAnchorStage.Settled,
            threadInitialBottomAnchorStage(
                initialBottomAnchorReady = true,
                userScrollInProgress = true,
                latestItemVisible = false
            )
        )
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
        // task #990 kill-switch contract: the rise itself no longer anchors —
        // an open keyboard suppresses every automatic correction. The close
        // transition is the positive control proving the trigger still lives.
        assertEquals(
            false,
            shouldAnchorThreadComposerLayoutChange(
                composerLayoutState(
                    previousKeyboardInsetDp = 0f,
                    nextKeyboardInsetDp = 240f,
                    previousViewportHeightDp = 720f,
                    nextViewportHeightDp = 720f
                )
            )
        )
        assertEquals(
            true,
            shouldAnchorThreadComposerLayoutChange(
                composerLayoutState(
                    previousKeyboardInsetDp = 240f,
                    nextKeyboardInsetDp = 0f,
                    previousViewportHeightDp = 720f,
                    nextViewportHeightDp = 720f
                )
            )
        )
        assertEquals(
            false,
            shouldAnchorThreadComposerLayoutChange(
                composerLayoutState(
                    previousKeyboardInsetDp = 240f,
                    nextKeyboardInsetDp = 240f,
                    previousViewportHeightDp = 720f,
                    nextViewportHeightDp = 720f
                )
            )
        )
        assertEquals(
            false,
            shouldAnchorThreadComposerLayoutChange(
                composerLayoutState(
                    previousKeyboardInsetDp = 0f,
                    nextKeyboardInsetDp = 240f,
                    previousViewportHeightDp = 720f,
                    nextViewportHeightDp = 720f,
                    allowBottomAutoAnchor = false
                )
            )
        )
        assertEquals(
            false,
            shouldAnchorThreadComposerLayoutChange(
                composerLayoutState(
                    previousKeyboardInsetDp = 0f,
                    nextKeyboardInsetDp = 240f,
                    previousViewportHeightDp = 720f,
                    nextViewportHeightDp = 720f,
                    initialBottomAnchorReady = false,
                )
            )
        )
        assertEquals(
            false,
            shouldAnchorThreadComposerLayoutChange(
                composerLayoutState(
                    previousKeyboardInsetDp = 0f,
                    nextKeyboardInsetDp = 240f,
                    previousViewportHeightDp = 720f,
                    nextViewportHeightDp = 720f,
                    latestItemIndex = -1,
                )
            )
        )
    }

    @Test
    fun composerLayoutBottomAnchor_runsWhenSuggestionChangesReadyViewportHeight() {
        // task #990 kill-switch contract: with the keyboard open (the helper's
        // default insets), a suggestion-driven viewport change no longer
        // anchors. With the keyboard closed the same change still does.
        assertFalse(
            shouldAnchorThreadComposerLayoutChange(
                composerLayoutState(
                    previousViewportHeightDp = 408f,
                    nextViewportHeightDp = 240f
                )
            )
        )
        assertTrue(
            shouldAnchorThreadComposerLayoutChange(
                composerLayoutState(
                    previousKeyboardInsetDp = 0f,
                    nextKeyboardInsetDp = 0f,
                    previousViewportHeightDp = 408f,
                    nextViewportHeightDp = 240f
                )
            )
        )
    }

    @Test
    fun composerLayoutBottomAnchor_defersMixedViewportEpochUntilMeasurementsAgree() {
        assertFalse(
            threadViewportEpochCoherent(
                measuredViewportHeightPx = 2092,
                layoutViewportStartPx = 0,
                layoutViewportEndPx = 1059
            )
        )
        assertTrue(
            threadViewportEpochCoherent(
                measuredViewportHeightPx = 1059,
                layoutViewportStartPx = 0,
                layoutViewportEndPx = 1059
            )
        )
        assertTrue(
            threadViewportEpochCoherent(
                measuredViewportHeightPx = 1058,
                layoutViewportStartPx = 0,
                layoutViewportEndPx = 1059
            )
        )

        val mixedEpoch =
            resolveThreadComposerLayoutBottomAnchor(
                composerLayoutState(
                    previousKeyboardInsetDp = 0f,
                    nextKeyboardInsetDp = 299.51364f,
                    previousViewportHeightDp = 622.84546f,
                    nextViewportHeightDp = 622.84546f,
                    viewportEpochCoherent = false,
                    focusedTailAnchorCanCorrectLayout = true
                )
            )
        assertFalse(mixedEpoch.shouldAnchor)
        assertTrue(mixedEpoch.pendingLayoutChange)
        assertFalse(mixedEpoch.anchorFocusedTailAtRenderedBottom)

        // task #990 kill-switch contract: even a coherent epoch does not anchor
        // while the keyboard is open — and the open decision CLEARS the
        // automatic pending instead of replaying it. The close transition
        // recovers on its own from the real inset/viewport change.
        val coherentEpochWhileOpen =
            resolveThreadComposerLayoutBottomAnchor(
                composerLayoutState(
                    previousKeyboardInsetDp = 0f,
                    nextKeyboardInsetDp = 299.51364f,
                    previousViewportHeightDp = 622.84546f,
                    nextViewportHeightDp = 315.29318f,
                    pendingLayoutChange = true,
                    viewportEpochCoherent = true,
                    focusedTailAnchorCanCorrectLayout = true
                )
            )
        assertFalse(coherentEpochWhileOpen.shouldAnchor)
        assertFalse(coherentEpochWhileOpen.pendingLayoutChange)
        val coherentEpochOnClose =
            resolveThreadComposerLayoutBottomAnchor(
                composerLayoutState(
                    previousKeyboardInsetDp = 299.51364f,
                    nextKeyboardInsetDp = 0f,
                    previousViewportHeightDp = 315.29318f,
                    nextViewportHeightDp = 622.84546f,
                    pendingLayoutChange = coherentEpochWhileOpen.pendingLayoutChange,
                    viewportEpochCoherent = true,
                    focusedTailAnchorCanCorrectLayout = true
                )
            )
        assertTrue(coherentEpochOnClose.shouldAnchor)
        assertFalse(coherentEpochOnClose.pendingLayoutChange)
        assertTrue(coherentEpochOnClose.anchorFocusedTailAtRenderedBottom)
    }

    @Test
    fun composerLayoutBottomAnchor_retriesWhenOnlyLazyViewportEpochBecomesCoherent() {
        val mixedEpoch =
            resolveThreadComposerLayoutBottomAnchor(
                composerLayoutState(
                    previousKeyboardInsetDp = 0f,
                    nextKeyboardInsetDp = 299.51364f,
                    previousViewportHeightDp = 622.84546f,
                    nextViewportHeightDp = 622.84546f,
                    viewportEpochCoherent = false,
                    focusedTailAnchorCanCorrectLayout = true
                )
            )
        assertEquals(
            ThreadComposerLayoutBottomAnchorDecision(
                shouldAnchor = false,
                pendingLayoutChange = true
            ),
            mixedEpoch
        )

        // BoxWithConstraints did not change. The only new fact is the Lazy
        // layout viewport catching up; the pending-only retry flow must rerun
        // the decision without ever replaying the mixed one.
        // task #990 kill-switch contract: the Lazy viewport catching up while
        // the keyboard is still open no longer triggers the retry — the open
        // decision CLEARS the automatic pending instead of replaying it, and
        // the close transition recovers on its own from the real inset change.
        val layoutOnlyCatchUpWhileOpen =
            resolveThreadComposerLayoutBottomAnchor(
                composerLayoutState(
                    previousKeyboardInsetDp = 299.51364f,
                    nextKeyboardInsetDp = 299.51364f,
                    previousViewportHeightDp = 622.84546f,
                    nextViewportHeightDp = 622.84546f,
                    pendingLayoutChange = mixedEpoch.pendingLayoutChange,
                    viewportEpochCoherent = true,
                    focusedTailAnchorCanCorrectLayout = true
                )
            )
        assertFalse(layoutOnlyCatchUpWhileOpen.shouldAnchor)
        assertFalse(layoutOnlyCatchUpWhileOpen.pendingLayoutChange)
        val catchUpOnClose =
            resolveThreadComposerLayoutBottomAnchor(
                composerLayoutState(
                    previousKeyboardInsetDp = 299.51364f,
                    nextKeyboardInsetDp = 0f,
                    previousViewportHeightDp = 622.84546f,
                    nextViewportHeightDp = 622.84546f,
                    pendingLayoutChange = layoutOnlyCatchUpWhileOpen.pendingLayoutChange,
                    viewportEpochCoherent = true,
                    focusedTailAnchorCanCorrectLayout = true
                )
            )
        assertTrue(catchUpOnClose.shouldAnchor)
        assertFalse(catchUpOnClose.pendingLayoutChange)
        assertTrue(catchUpOnClose.anchorFocusedTailAtRenderedBottom)
    }

    @Test
    fun composerLayoutViewportRetry_isInactiveForOrdinaryScrollAndSingleShotForCatchUp() {
        assertFalse(
            shouldAdvanceThreadComposerViewportRetry(
                pendingLayoutChange = false,
                measuredViewportHeightPx = 1059,
                layoutViewportStartPx = 0,
                layoutViewportEndPx = 1059
            )
        )
        assertFalse(
            shouldAdvanceThreadComposerViewportRetry(
                pendingLayoutChange = false,
                measuredViewportHeightPx = 1059,
                // Ordinary scroll/remeasure changes are ignored while no
                // composer layout correction is pending.
                layoutViewportStartPx = -120,
                layoutViewportEndPx = 939
            )
        )
        assertFalse(
            shouldAdvanceThreadComposerViewportRetry(
                pendingLayoutChange = true,
                measuredViewportHeightPx = 1059,
                layoutViewportStartPx = 0,
                layoutViewportEndPx = 2092
            )
        )
        assertTrue(
            shouldAdvanceThreadComposerViewportRetry(
                pendingLayoutChange = true,
                measuredViewportHeightPx = 1059,
                layoutViewportStartPx = 0,
                layoutViewportEndPx = 1059
            )
        )
    }

    @Test
    fun pixelProbe_acceptsOnlySameGenerationInstanceTargetAndLayoutOwner() {
        fun ownerCurrent(
            currentInstance: String = "thread-a",
            currentLayoutGeneration: Long = 12L,
            currentProbeGeneration: Long = 4L,
            currentTarget: String? = "message-9",
            currentFocused: String? = "message-9",
            currentTail: String? = "message-11"
        ): Boolean =
            threadViewportPixelProbeOwnerCurrent(
                requestedThreadInstanceKey = "thread-a",
                currentThreadInstanceKey = currentInstance,
                requestedLayoutGeneration = 12L,
                currentLayoutGeneration = currentLayoutGeneration,
                requestedProbeGeneration = 4L,
                currentProbeGeneration = currentProbeGeneration,
                requestedTargetMessageId = "message-9",
                currentTargetMessageId = currentTarget,
                requestedFocusedMessageId = "message-9",
                currentFocusedMessageId = currentFocused,
                requestedTailMessageId = "message-11",
                currentTailMessageId = currentTail
            )

        assertTrue(ownerCurrent())
        assertFalse(ownerCurrent(currentInstance = "thread-b"))
        assertFalse(ownerCurrent(currentLayoutGeneration = 13L))
        assertFalse(ownerCurrent(currentProbeGeneration = 5L))
        assertFalse(ownerCurrent(currentTarget = "message-10"))
        assertFalse(ownerCurrent(currentFocused = null))
        assertFalse(ownerCurrent(currentTail = "message-12"))
    }

    @Test
    fun focusedTailFit_usesOneSnapshotForExactFitIncludingSpacingAndPadding() {
        val fit =
            resolveThreadFocusedTailFit(
                targetItemIndex = 10,
                latestMessageItemIndex = 13,
                bottomPaddingItemIndex = 14,
                targetOffsetPx = 100,
                targetSizePx = 40,
                // The 140px between the target bottom and tail start represents
                // all intermediate rows, system rows, footers, and spacing.
                latestMessageOffsetPx = 280,
                latestMessageSizePx = 60,
                viewportHeightPx = 264,
                trailingPaddingPx = 24
            )

        assertEquals(264, fit.fitHeightPx)
        assertEquals(224, fit.trailingSpanPx)
        assertTrue(fit.fitsViewport)

        assertFalse(
            resolveThreadFocusedTailFit(
                targetItemIndex = 10,
                latestMessageItemIndex = 13,
                bottomPaddingItemIndex = 14,
                targetOffsetPx = 100,
                targetSizePx = 40,
                latestMessageOffsetPx = 280,
                latestMessageSizePx = 60,
                viewportHeightPx = 263,
                trailingPaddingPx = 24
            ).fitsViewport
        )
    }

    @Test
    fun focusedTailFit_failsClosedForUnmeasuredOrInvalidSnapshot() {
        fun fit(
            targetOffsetPx: Int? = 100,
            targetSizePx: Int? = 40,
            latestOffsetPx: Int? = 280,
            latestSizePx: Int? = 60,
            bottomPaddingItemIndex: Int = 14
        ): ThreadFocusedTailFit =
            resolveThreadFocusedTailFit(
                targetItemIndex = 10,
                latestMessageItemIndex = 13,
                bottomPaddingItemIndex = bottomPaddingItemIndex,
                targetOffsetPx = targetOffsetPx,
                targetSizePx = targetSizePx,
                latestMessageOffsetPx = latestOffsetPx,
                latestMessageSizePx = latestSizePx,
                viewportHeightPx = 264,
                trailingPaddingPx = 24
            )

        listOf(
            fit(targetOffsetPx = null),
            fit(targetSizePx = null),
            fit(latestOffsetPx = null),
            fit(latestSizePx = null),
            fit(bottomPaddingItemIndex = 13)
        ).forEach { unavailable ->
            assertEquals(-1, unavailable.fitHeightPx)
            assertEquals(0, unavailable.trailingSpanPx)
            assertFalse(unavailable.fitsViewport)
        }
    }

    @Test
    fun focusedTailComposerOwner_acceptsNearTailOnlyWhenMeasuredContentFits() {
        val identity = "near-tail-message:route-request"
        assertTrue(
            focusedActiveTailMessageComposerLayoutOwnsViewport(
                targetIdentity = identity,
                targetItemIndex = 10,
                latestMessageItemIndex = 13,
                targetToTailFitsViewport = true,
                canLoadMoreAfter = false,
                anchoredIdentity = identity,
                consumedIdentity = null,
                scrollInterruptedIdentity = null,
                startUserScrollGeneration = 5,
                currentUserScrollGeneration = 5
            )
        )
        assertTrue(
            focusedTailMessageComposerLayoutOwnsViewport(
                targetIdentity = identity,
                targetItemIndex = 10,
                latestMessageItemIndex = 13,
                targetToTailFitsViewport = true,
                canLoadMoreAfter = false,
                anchoredIdentity = identity,
                consumedIdentity = identity,
                scrollInterruptedIdentity = null,
                startUserScrollGeneration = 5,
                currentUserScrollGeneration = 5
            )
        )
        assertFalse(
            focusedTailMessageComposerLayoutOwnsViewport(
                targetIdentity = identity,
                targetItemIndex = 10,
                latestMessageItemIndex = 13,
                targetToTailFitsViewport = false,
                canLoadMoreAfter = false,
                anchoredIdentity = identity,
                consumedIdentity = identity,
                scrollInterruptedIdentity = null,
                startUserScrollGeneration = 5,
                currentUserScrollGeneration = 5
            )
        )
    }

    @Test
    fun composerLayoutBottomAnchor_correctsFirstImeAfterLatestFocusedAnchorSettles() {
        val targetIdentity = "latest-message:route-request"
        assertTrue(
            focusedTailMessageComposerLayoutOwnsViewport(
                targetIdentity = targetIdentity,
                targetItemIndex = 17,
                latestMessageItemIndex = 17,
                targetToTailFitsViewport = true,
                canLoadMoreAfter = false,
                anchoredIdentity = targetIdentity,
                consumedIdentity = targetIdentity,
                scrollInterruptedIdentity = null,
                startUserScrollGeneration = 0,
                currentUserScrollGeneration = 0
            )
        )
        assertFalse(
            focusedTailMessageComposerLayoutOwnsViewport(
                targetIdentity = targetIdentity,
                targetItemIndex = 17,
                latestMessageItemIndex = 17,
                targetToTailFitsViewport = true,
                canLoadMoreAfter = true,
                anchoredIdentity = targetIdentity,
                consumedIdentity = targetIdentity,
                scrollInterruptedIdentity = null,
                startUserScrollGeneration = 0,
                currentUserScrollGeneration = 0
            )
        )
        // task #990 kill-switch contract: the settled focused owner's first-IME
        // correction is exactly the family every white capture implicates — it
        // no longer runs while the keyboard is open. The close-side control
        // proves the ownership trigger itself still works.
        assertFalse(
            shouldAnchorThreadComposerLayoutChange(
                composerLayoutState(
                    previousKeyboardInsetDp = 0f,
                    nextKeyboardInsetDp = 320f,
                    previousViewportHeightDp = 720f,
                    nextViewportHeightDp = 408f,
                    allowBottomAutoAnchor = false,
                    focusedTailAnchorCanCorrectLayout = true
                )
            )
        )
        assertTrue(
            shouldAnchorThreadComposerLayoutChange(
                composerLayoutState(
                    previousKeyboardInsetDp = 320f,
                    nextKeyboardInsetDp = 0f,
                    previousViewportHeightDp = 408f,
                    nextViewportHeightDp = 720f,
                    allowBottomAutoAnchor = false,
                    focusedTailAnchorCanCorrectLayout = true
                )
            )
        )
    }

    @Test
    fun composerLayoutBottomAnchor_activeLatestFocusedOwnerCorrectsFirstImeImmediately() {
        val targetIdentity = "latest-message:route-request"
        assertTrue(
            focusedActiveTailMessageComposerLayoutOwnsViewport(
                targetIdentity = targetIdentity,
                targetItemIndex = 17,
                latestMessageItemIndex = 17,
                targetToTailFitsViewport = true,
                canLoadMoreAfter = false,
                anchoredIdentity = targetIdentity,
                consumedIdentity = null,
                scrollInterruptedIdentity = null,
                startUserScrollGeneration = 0,
                currentUserScrollGeneration = 0
            )
        )
        assertFalse(
            focusedActiveTailMessageComposerLayoutOwnsViewport(
                targetIdentity = targetIdentity,
                targetItemIndex = 17,
                latestMessageItemIndex = 17,
                targetToTailFitsViewport = true,
                canLoadMoreAfter = true,
                anchoredIdentity = targetIdentity,
                consumedIdentity = null,
                scrollInterruptedIdentity = null,
                startUserScrollGeneration = 0,
                currentUserScrollGeneration = 0
            )
        )
        assertFalse(
            focusedActiveTailMessageComposerLayoutOwnsViewport(
                targetIdentity = targetIdentity,
                targetItemIndex = 17,
                latestMessageItemIndex = 17,
                targetToTailFitsViewport = true,
                canLoadMoreAfter = false,
                anchoredIdentity = targetIdentity,
                consumedIdentity = targetIdentity,
                scrollInterruptedIdentity = null,
                startUserScrollGeneration = 0,
                currentUserScrollGeneration = 0
            )
        )
        // task #990 kill-switch contract: even the active focused owner does
        // not correct while the keyboard is open — its immediate first-IME
        // correction is suppressed like every other automatic path, and the
        // close transition performs the correction instead.
        val whileFocused =
            resolveThreadComposerLayoutBottomAnchor(
                composerLayoutState(
                    previousKeyboardInsetDp = 0f,
                    nextKeyboardInsetDp = 320f,
                    previousViewportHeightDp = 720f,
                    nextViewportHeightDp = 408f,
                    allowBottomAutoAnchor = false,
                    focusedAnchorOwnsViewport = true,
                    focusedActiveTailAnchorCanCorrectLayout = true
                )
            )
        assertFalse(whileFocused.shouldAnchor)
        val onClose =
            resolveThreadComposerLayoutBottomAnchor(
                composerLayoutState(
                    previousKeyboardInsetDp = 320f,
                    nextKeyboardInsetDp = 0f,
                    previousViewportHeightDp = 408f,
                    nextViewportHeightDp = 720f,
                    allowBottomAutoAnchor = false,
                    focusedAnchorOwnsViewport = true,
                    focusedActiveTailAnchorCanCorrectLayout = true
                )
            )
        assertTrue(onClose.shouldAnchor)
        assertFalse(onClose.pendingLayoutChange)
        assertTrue(onClose.anchorFocusedTailAtRenderedBottom)

        val loadedLastHistoryFocused =
            resolveThreadComposerLayoutBottomAnchor(
                composerLayoutState(
                    previousKeyboardInsetDp = 0f,
                    nextKeyboardInsetDp = 320f,
                    previousViewportHeightDp = 720f,
                    nextViewportHeightDp = 408f,
                    allowBottomAutoAnchor = false,
                    focusedAnchorOwnsViewport = true,
                    focusedActiveTailAnchorCanCorrectLayout = false
                )
            )
        assertFalse(loadedLastHistoryFocused.shouldAnchor)
        assertTrue(loadedLastHistoryFocused.pendingLayoutChange)
        assertFalse(loadedLastHistoryFocused.anchorFocusedTailAtRenderedBottom)
    }

    @Test
    fun focusedTailComposerLayoutOwner_yieldsWhenTailDoesNotFitOrUserScrolls() {
        val targetIdentity = "focused-message:route-request"
        assertFalse(
            focusedActiveTailMessageComposerLayoutOwnsViewport(
                targetIdentity = targetIdentity,
                targetItemIndex = 9,
                latestMessageItemIndex = 17,
                targetToTailFitsViewport = false,
                canLoadMoreAfter = false,
                anchoredIdentity = targetIdentity,
                consumedIdentity = null,
                scrollInterruptedIdentity = null,
                startUserScrollGeneration = 0,
                currentUserScrollGeneration = 0
            )
        )
        assertFalse(
            focusedActiveTailMessageComposerLayoutOwnsViewport(
                targetIdentity = targetIdentity,
                targetItemIndex = 17,
                latestMessageItemIndex = 17,
                targetToTailFitsViewport = true,
                canLoadMoreAfter = false,
                anchoredIdentity = targetIdentity,
                consumedIdentity = null,
                scrollInterruptedIdentity = null,
                startUserScrollGeneration = 0,
                currentUserScrollGeneration = 1
            )
        )
        assertFalse(
            focusedTailMessageComposerLayoutOwnsViewport(
                targetIdentity = targetIdentity,
                targetItemIndex = 9,
                latestMessageItemIndex = 17,
                targetToTailFitsViewport = false,
                canLoadMoreAfter = false,
                anchoredIdentity = targetIdentity,
                consumedIdentity = targetIdentity,
                scrollInterruptedIdentity = null,
                startUserScrollGeneration = 0,
                currentUserScrollGeneration = 0
            )
        )
        assertFalse(
            focusedTailMessageComposerLayoutOwnsViewport(
                targetIdentity = targetIdentity,
                targetItemIndex = 17,
                latestMessageItemIndex = 17,
                targetToTailFitsViewport = true,
                canLoadMoreAfter = false,
                anchoredIdentity = targetIdentity,
                consumedIdentity = targetIdentity,
                scrollInterruptedIdentity = null,
                startUserScrollGeneration = 0,
                currentUserScrollGeneration = 1
            )
        )
    }

    @Test
    fun composerLayoutBottomAnchor_runsOncePendingColdEntryBecomesReady() {
        // task #990 kill-switch contract: with the keyboard open (the helper's
        // default insets) the cold entry becoming ready does NOT anchor — and
        // the ready decision CLEARS the automatic pending instead of replaying
        // it. The closed-keyboard arm below keeps the original single-shot
        // flow as the positive control.
        val beforeReadyOpen =
            resolveThreadComposerLayoutBottomAnchor(
                composerLayoutState(
                    previousViewportHeightDp = 720f,
                    nextViewportHeightDp = 408f,
                    initialBottomAnchorReady = false
                )
            )
        assertFalse(beforeReadyOpen.shouldAnchor)
        assertTrue(beforeReadyOpen.pendingLayoutChange)
        val afterReadyOpen =
            resolveThreadComposerLayoutBottomAnchor(
                composerLayoutState(
                    previousViewportHeightDp = 408f,
                    nextViewportHeightDp = 408f,
                    pendingLayoutChange = beforeReadyOpen.pendingLayoutChange
                )
            )
        assertFalse(afterReadyOpen.shouldAnchor, "no anchor while the keyboard is open")
        assertFalse(afterReadyOpen.pendingLayoutChange, "open clears the pending instead of replaying it")

        val beforeReady =
            resolveThreadComposerLayoutBottomAnchor(
                composerLayoutState(
                    previousKeyboardInsetDp = 0f,
                    nextKeyboardInsetDp = 0f,
                    previousViewportHeightDp = 720f,
                    nextViewportHeightDp = 408f,
                    initialBottomAnchorReady = false
                )
            )
        assertFalse(beforeReady.shouldAnchor)
        assertTrue(beforeReady.pendingLayoutChange)

        val afterReady =
            resolveThreadComposerLayoutBottomAnchor(
                composerLayoutState(
                    previousKeyboardInsetDp = 0f,
                    nextKeyboardInsetDp = 0f,
                    previousViewportHeightDp = 408f,
                    nextViewportHeightDp = 408f,
                    pendingLayoutChange = beforeReady.pendingLayoutChange
                )
            )
        assertTrue(afterReady.shouldAnchor)
        assertFalse(afterReady.pendingLayoutChange)
        assertFalse(afterReady.anchorFocusedTailAtRenderedBottom)

        assertEquals(
            ThreadComposerLayoutBottomAnchorDecision(
                shouldAnchor = false,
                pendingLayoutChange = false
            ),
            resolveThreadComposerLayoutBottomAnchor(
                composerLayoutState(
                    previousKeyboardInsetDp = 0f,
                    nextKeyboardInsetDp = 0f,
                    previousViewportHeightDp = 408f,
                    nextViewportHeightDp = 408f,
                    pendingLayoutChange = afterReady.pendingLayoutChange
                )
            )
        )
    }

    @Test
    fun composerLayoutBottomAnchor_ignoresStableGeometryAndUserOwnedViewport() {
        assertFalse(
            shouldAnchorThreadComposerLayoutChange(
                composerLayoutState(
                    previousViewportHeightDp = 0f,
                    nextViewportHeightDp = 720f
                )
            )
        )
        assertFalse(
            shouldAnchorThreadComposerLayoutChange(
                composerLayoutState(
                    previousViewportHeightDp = 408f,
                    nextViewportHeightDp = 408f
                )
            )
        )
        assertFalse(
            shouldAnchorThreadComposerLayoutChange(
                composerLayoutState(
                    previousViewportHeightDp = 408f,
                    nextViewportHeightDp = 240f,
                    allowBottomAutoAnchor = false
                )
            )
        )
        assertFalse(
            shouldAnchorThreadComposerLayoutChange(
                composerLayoutState(
                    previousViewportHeightDp = 408f,
                    nextViewportHeightDp = 240f,
                    focusedAnchorOwnsViewport = true
                )
            )
        )
    }

    @Test
    fun composerLayoutBottomAnchor_usesRenderedBottomOnlyForFocusedLatestOwner() {
        // task #990 kill-switch contract: the rise no longer anchors at all, so
        // the placement-selection contract is asserted on the close transition,
        // where anchoring still happens.
        val activeLatest =
            resolveThreadComposerLayoutBottomAnchor(
                composerLayoutState(
                    previousKeyboardInsetDp = 320f,
                    nextKeyboardInsetDp = 0f,
                    previousViewportHeightDp = 408f,
                    nextViewportHeightDp = 720f,
                    allowBottomAutoAnchor = false,
                    focusedAnchorOwnsViewport = true,
                    focusedActiveTailAnchorCanCorrectLayout = true
                )
            )
        val settledLatest =
            resolveThreadComposerLayoutBottomAnchor(
                composerLayoutState(
                    previousKeyboardInsetDp = 320f,
                    nextKeyboardInsetDp = 0f,
                    previousViewportHeightDp = 408f,
                    nextViewportHeightDp = 720f,
                    allowBottomAutoAnchor = false,
                    focusedTailAnchorCanCorrectLayout = true
                )
            )
        val ordinaryFollowingLatest =
            resolveThreadComposerLayoutBottomAnchor(
                composerLayoutState(
                    previousKeyboardInsetDp = 320f,
                    nextKeyboardInsetDp = 0f,
                    previousViewportHeightDp = 408f,
                    nextViewportHeightDp = 720f
                )
            )
        val historyOrUserOwned =
            resolveThreadComposerLayoutBottomAnchor(
                composerLayoutState(
                    previousKeyboardInsetDp = 320f,
                    nextKeyboardInsetDp = 0f,
                    previousViewportHeightDp = 408f,
                    nextViewportHeightDp = 720f,
                    allowBottomAutoAnchor = false,
                    focusedAnchorOwnsViewport = true
                )
            )

        assertTrue(activeLatest.anchorFocusedTailAtRenderedBottom)
        assertTrue(settledLatest.anchorFocusedTailAtRenderedBottom)
        assertFalse(ordinaryFollowingLatest.anchorFocusedTailAtRenderedBottom)
        assertFalse(historyOrUserOwned.anchorFocusedTailAtRenderedBottom)

        // While the keyboard is open the kill-switch suppresses anchoring for
        // every ownership variant, so no rendered-bottom placement can happen.
        val activeLatestWhileOpen =
            resolveThreadComposerLayoutBottomAnchor(
                composerLayoutState(
                    previousKeyboardInsetDp = 0f,
                    nextKeyboardInsetDp = 320f,
                    previousViewportHeightDp = 720f,
                    nextViewportHeightDp = 408f,
                    allowBottomAutoAnchor = false,
                    focusedAnchorOwnsViewport = true,
                    focusedActiveTailAnchorCanCorrectLayout = true
                )
            )
        val settledLatestWhileOpen =
            resolveThreadComposerLayoutBottomAnchor(
                composerLayoutState(
                    previousKeyboardInsetDp = 0f,
                    nextKeyboardInsetDp = 320f,
                    previousViewportHeightDp = 720f,
                    nextViewportHeightDp = 408f,
                    allowBottomAutoAnchor = false,
                    focusedTailAnchorCanCorrectLayout = true
                )
            )
        assertFalse(activeLatestWhileOpen.shouldAnchor)
        assertFalse(activeLatestWhileOpen.anchorFocusedTailAtRenderedBottom)
        assertFalse(settledLatestWhileOpen.shouldAnchor)
        assertFalse(settledLatestWhileOpen.anchorFocusedTailAtRenderedBottom)
    }

    @Test
    fun focusedAnchorReanchor_inheritsPlacementOnlyForSameIdentity() {
        val identity = "latest-message:request-9"

        assertEquals(
            LazyViewportAnchorPlacement.RenderedBottom,
            focusedAnchorPlacementForIdentity(
                targetIdentity = identity,
                placementIdentity = identity,
                placement = LazyViewportAnchorPlacement.RenderedBottom
            )
        )
        assertEquals(
            LazyViewportAnchorPlacement.Focused,
            focusedAnchorPlacementForIdentity(
                targetIdentity = "latest-message:request-10",
                placementIdentity = identity,
                placement = LazyViewportAnchorPlacement.RenderedBottom
            )
        )
        assertEquals(
            16,
            focusedAnchorPlacementOffsetForIdentity(
                targetIdentity = identity,
                placementIdentity = identity,
                placement = LazyViewportAnchorPlacement.RenderedBottom,
                placementOffsetPx = 16
            )
        )
        assertEquals(
            0,
            focusedAnchorPlacementOffsetForIdentity(
                targetIdentity = "latest-message:request-10",
                placementIdentity = identity,
                placement = LazyViewportAnchorPlacement.RenderedBottom,
                placementOffsetPx = 16
            )
        )
        assertEquals(
            "tail-message",
            focusedAnchorRenderedTailMessageIdForIdentity(
                targetIdentity = identity,
                placementIdentity = identity,
                placement = LazyViewportAnchorPlacement.RenderedBottom,
                renderedTailMessageId = "tail-message"
            )
        )
        assertEquals(
            null,
            focusedAnchorRenderedTailMessageIdForIdentity(
                targetIdentity = "latest-message:request-10",
                placementIdentity = identity,
                placement = LazyViewportAnchorPlacement.RenderedBottom,
                renderedTailMessageId = "tail-message"
            )
        )
    }

    @Test
    fun focusedTailPlacement_configChangeResetsPixelsAndRebuildsFromNewSnapshot() {
        val identity = "near-tail-message:request-12"
        val beforeConfigChange =
            renderedThreadFocusedAnchorPlacement(
                identity = identity,
                offsetPx = 224,
                renderedTailMessageId = "tail-message-3"
            )
        assertEquals(LazyViewportAnchorPlacement.RenderedBottom, beforeConfigChange.placement)
        assertEquals(224, beforeConfigChange.offsetPx)

        // A new surface/configuration gets a fresh remembered state. The old
        // density/layout snapshot must not cross that boundary.
        val afterConfigChange = resetThreadFocusedAnchorPlacement(identity = null)
        assertEquals(null, afterConfigChange.identity)
        assertEquals(LazyViewportAnchorPlacement.Focused, afterConfigChange.placement)
        assertEquals(0, afterConfigChange.offsetPx)
        assertEquals(null, afterConfigChange.renderedTailMessageId)

        val rebuiltFromNewSnapshot =
            renderedThreadFocusedAnchorPlacement(
                identity = identity,
                offsetPx = 176,
                renderedTailMessageId = "tail-message-3"
            )
        assertEquals(identity, rebuiltFromNewSnapshot.identity)
        assertEquals(LazyViewportAnchorPlacement.RenderedBottom, rebuiltFromNewSnapshot.placement)
        assertEquals(176, rebuiltFromNewSnapshot.offsetPx)
        assertEquals("tail-message-3", rebuiltFromNewSnapshot.renderedTailMessageId)
    }

    @Test
    fun focusedLatestComposerLayoutOwner_oldInterruptedNonceDoesNotFenceNewIdentity() {
        assertTrue(
            focusedTailMessageComposerLayoutOwnsViewport(
                targetIdentity = "latest-message:request-10",
                targetItemIndex = 17,
                latestMessageItemIndex = 17,
                targetToTailFitsViewport = true,
                canLoadMoreAfter = false,
                anchoredIdentity = "latest-message:request-10",
                consumedIdentity = "latest-message:request-10",
                scrollInterruptedIdentity = "latest-message:request-9",
                startUserScrollGeneration = 4,
                currentUserScrollGeneration = 4
            )
        )
    }

    @Test
    fun focusedTailComposerLayoutOwner_requiresValidMessageIndices() {
        val messageIndex = 11

        assertTrue(
            focusedTailMessageComposerLayoutOwnsViewport(
                targetIdentity = "latest-message:request-11",
                targetItemIndex = messageIndex,
                latestMessageItemIndex = messageIndex,
                targetToTailFitsViewport = true,
                canLoadMoreAfter = false,
                anchoredIdentity = "latest-message:request-11",
                consumedIdentity = "latest-message:request-11",
                scrollInterruptedIdentity = null,
                startUserScrollGeneration = 0,
                currentUserScrollGeneration = 0
            )
        )
        assertFalse(
            focusedTailMessageComposerLayoutOwnsViewport(
                targetIdentity = "latest-message:request-11",
                targetItemIndex = -1,
                latestMessageItemIndex = -1,
                targetToTailFitsViewport = false,
                canLoadMoreAfter = false,
                anchoredIdentity = "latest-message:request-11",
                consumedIdentity = "latest-message:request-11",
                scrollInterruptedIdentity = null,
                startUserScrollGeneration = 0,
                currentUserScrollGeneration = 0
            )
        )
    }

    private fun composerLayoutState(
        previousKeyboardInsetDp: Float = 320f,
        nextKeyboardInsetDp: Float = 320f,
        previousViewportHeightDp: Float,
        nextViewportHeightDp: Float,
        pendingLayoutChange: Boolean = false,
        initialBottomAnchorReady: Boolean = true,
        latestItemIndex: Int = 15,
        allowBottomAutoAnchor: Boolean = true,
        viewportEpochCoherent: Boolean = true,
        focusedAnchorOwnsViewport: Boolean = false,
        focusedActiveTailAnchorCanCorrectLayout: Boolean = false,
        focusedTailAnchorCanCorrectLayout: Boolean = false,
        imeProgrammaticViewportPolicy: ThreadImeProgrammaticViewportPolicy =
            ThreadImeProgrammaticViewportPolicy.SuppressWhileImeOpen,
    ): ThreadComposerLayoutBottomAnchorState =
        ThreadComposerLayoutBottomAnchorState(
            previousKeyboardInsetDp = previousKeyboardInsetDp,
            nextKeyboardInsetDp = nextKeyboardInsetDp,
            imeProgrammaticViewportPolicy = imeProgrammaticViewportPolicy,
            previousViewportHeightDp = previousViewportHeightDp,
            nextViewportHeightDp = nextViewportHeightDp,
            pendingLayoutChange = pendingLayoutChange,
            initialBottomAnchorReady = initialBottomAnchorReady,
            latestItemIndex = latestItemIndex,
            allowBottomAutoAnchor = allowBottomAutoAnchor,
            viewportEpochCoherent = viewportEpochCoherent,
            focusedAnchorOwnsViewport = focusedAnchorOwnsViewport,
            focusedActiveTailAnchorCanCorrectLayout = focusedActiveTailAnchorCanCorrectLayout,
            focusedTailAnchorCanCorrectLayout = focusedTailAnchorCanCorrectLayout
        )

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
    fun backToBottomButton_hidesWhileKeyboardBottomCorrectionOwnsViewport() {
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
                    pendingKeyboardBottomAnchor = false,
                    bottomAutoAnchorOwnsViewport = true,
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
    fun backToBottomButton_preservesUserAndFocusedHistoryAffordance() {
        assertEquals(
            true,
            shouldShowThreadBackToBottomButton(
                ThreadBackToBottomButtonState(
                    initialBottomAnchorReady = true,
                    pendingKeyboardBottomAnchor = false,
                    bottomAutoAnchorOwnsViewport = false,
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
    fun backToBottomOverlay_staysMountedAcrossTheClickReleaseAfterScrollHidesTheButton() {
        assertEquals(
            true,
            shouldKeepThreadBackToBottomOverlayMounted(
                shouldShowButton = true,
                interactionGuardActive = false
            )
        )
        assertEquals(
            true,
            shouldKeepThreadBackToBottomOverlayMounted(
                shouldShowButton = false,
                interactionGuardActive = true
            )
        )
        assertEquals(
            false,
            shouldKeepThreadBackToBottomOverlayMounted(
                shouldShowButton = false,
                interactionGuardActive = false
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
    fun visibleReadMarkerKey_respectsRenderedVisibleTail() {
        // task #232: the read marker must stop at the rendered visible tail so
        // live appends while off-bottom never advance the shared read frontier.
        val slice =
            payload(messages = listOf(message("m1"), message("m2"), message("m3")))
                .copy(status = "content", sourceThreadId = "channel-1")
                .toThreadTimelineSlice()

        val fullTail = slice.visibleReadMarkerKey("channel-1", null)
        val visibleTail = slice.visibleReadMarkerKey("channel-1", 0)

        assertEquals(slice.visibleReadMarkerKey("channel-1"), fullTail)
        assertNotEquals(fullTail, visibleTail)
        assertTrue(visibleTail!!.endsWith(":m1"))
        assertEquals(fullTail, slice.visibleReadMarkerKey("channel-1", 99))
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
