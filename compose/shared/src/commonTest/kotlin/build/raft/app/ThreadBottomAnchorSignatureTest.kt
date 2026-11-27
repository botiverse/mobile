package build.raft.app

import build.raft.app.thread.FocusedAnchorBottomYieldCapMs
import build.raft.app.thread.FocusedAnchorSessionAction
import build.raft.app.thread.FocusedAnchorSessionHardCapMs
import build.raft.app.thread.FocusedAnchorSessionQuietMs
import build.raft.app.thread.RaftThreadAttachmentItem
import build.raft.app.thread.RaftThreadMemberItem
import build.raft.app.thread.RaftThreadMessageItem
import build.raft.app.thread.RaftThreadPendingSendContext
import build.raft.app.thread.RaftThreadReactionItem
import build.raft.app.thread.RaftThreadScreenPayload
import build.raft.app.thread.ThreadBackToBottomButtonState
import build.raft.app.thread.ThreadComposerLayoutBottomAnchorState
import build.raft.app.thread.ThreadImeProgrammaticViewportPolicy
import build.raft.app.thread.ThreadMessageDeliverySent
import build.raft.app.thread.ThreadTimelineSlice
import build.raft.app.thread.focusedAnchorIdentityForViewportReplacement
import build.raft.app.thread.focusedAnchorSessionAction
import build.raft.app.thread.focusedAnchorSessionUserOverrideActive
import build.raft.app.thread.focusedMessageAnchorIdentity
import build.raft.app.thread.initialMarkdownPreparseCandidateSignature
import build.raft.app.thread.latestMessageLayoutKey
import build.raft.app.thread.messageListItemKey
import build.raft.app.thread.shouldAnchorThreadComposerLayoutChange
import build.raft.app.thread.shouldContinueLatestLocalSendBottomAnchor
import build.raft.app.thread.shouldMarkThreadVisibleRead
import build.raft.app.thread.shouldRunFocusedMessageAnchor
import build.raft.app.thread.shouldShowThreadBackToBottomButton
import build.raft.app.thread.shouldYieldBottomAnchorToFocusedAnchor
import build.raft.app.thread.threadActionCard
import build.raft.app.thread.threadActionCardSignature
import build.raft.app.thread.threadBeginningReplyCountLabel
import build.raft.app.thread.threadDuplicateRandomIds
import build.raft.app.thread.threadInitialBottomAnchorTargetItemIndex
import build.raft.app.thread.threadMarkdownRenderSignature
import build.raft.app.thread.threadMessageRenderIdentity
import build.raft.app.thread.toThreadTimelineSlice
import build.raft.app.thread.visibleReadMarkerKey
import build.raft.app.thread.withOriginReplyCountAtLeastLoadedReplies
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Focused regression coverage for the bottom-anchor keying in ThreadPage.
 *
 * The Thread page uses two pure functions on RaftThreadScreenPayload
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
        attachments: List<RaftThreadAttachmentItem> = emptyList(),
        reactions: List<RaftThreadReactionItem> = emptyList(),
        metadata: Map<String, String> = emptyMap(),
        deliveryState: String = ThreadMessageDeliverySent,
        pendingSendContext: RaftThreadPendingSendContext? = null,
        randomId: String? = null
    ): RaftThreadMessageItem = RaftThreadMessageItem(
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
        messages: List<RaftThreadMessageItem> = emptyList(),
        originMessage: RaftThreadMessageItem? = null,
        canLoadMoreBefore: Boolean = false,
        canLoadMoreAfter: Boolean = false,
        members: List<RaftThreadMemberItem> = emptyList()
    ): RaftThreadScreenPayload = RaftThreadScreenPayload(
        originStatus = originStatus,
        messages = messages,
        originMessage = originMessage,
        canLoadMoreBefore = canLoadMoreBefore,
        canLoadMoreAfter = canLoadMoreAfter,
        members = members
    )

    private fun RaftThreadScreenPayload.latestMessageLayoutKeyPublic(): String =
        latestMessageLayoutKey()

    private fun RaftThreadScreenPayload.timelineSlice(): ThreadTimelineSlice =
        toThreadTimelineSlice()

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
        // A visible window that is not in use (desktop: unfocused or no input for 3 minutes).
        assertFalse(
            shouldMarkThreadVisibleRead(
                routeActive = true,
                observedHostForeground = true,
                currentHostVisibility = true,
                markerKey = marker,
                hostInUse = false
            )
        )
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
}
