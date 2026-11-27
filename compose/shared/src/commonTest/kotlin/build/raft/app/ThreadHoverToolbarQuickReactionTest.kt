package build.raft.app

import androidx.compose.ui.input.pointer.PointerType
import build.raft.app.data.RaftDefaultFavoriteReaction
import build.raft.app.thread.RaftThreadMessageItem
import build.raft.app.thread.RaftThreadReactionItem
import build.raft.app.thread.ThreadHoverToolbarQuickReactionLabelKind
import build.raft.app.thread.ThreadHoverToolbarReactionActivation
import build.raft.app.thread.ThreadTimelineSlice
import build.raft.app.thread.shouldScheduleHoverToolbarReactionOpen
import build.raft.app.thread.threadHoverToolbarQuickReactionLabelKind
import build.raft.app.thread.threadHoverToolbarReactionActivation
import build.raft.app.thread.threadMessageHasViewerReaction
import build.raft.app.thread.threadMessageHoverToolbarActions
import build.raft.app.thread.threadMessageMenuCapabilities
import build.raft.app.thread.threadReactionPendingKey
import build.raft.app.thread.toPendingReactionVisual
import build.raft.app.thread.toThreadReplyMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThreadHoverToolbarQuickReactionTest {
    @Test
    fun labelKindDistinguishesDefaultLikeAndOtherEmojis() {
        assertEquals(
            ThreadHoverToolbarQuickReactionLabelKind.Like,
            threadHoverToolbarQuickReactionLabelKind(RaftDefaultFavoriteReaction, reacted = false),
        )
        assertEquals(
            ThreadHoverToolbarQuickReactionLabelKind.Unlike,
            threadHoverToolbarQuickReactionLabelKind(RaftDefaultFavoriteReaction, reacted = true),
        )
        assertEquals(
            ThreadHoverToolbarQuickReactionLabelKind.React,
            threadHoverToolbarQuickReactionLabelKind("😂", reacted = false),
        )
        assertEquals(
            ThreadHoverToolbarQuickReactionLabelKind.Remove,
            threadHoverToolbarQuickReactionLabelKind("😂", reacted = true),
        )
    }

    @Test
    fun viewerReactionCheckTracksReactionsAndOptimisticPendingVisuals() {
        val messageId = "m-1"
        val reactions =
            listOf(
                RaftThreadReactionItem(emoji = "👍", count = 1, selected = true),
                RaftThreadReactionItem(emoji = "😂", count = 2, selected = false),
            )
        assertTrue(threadMessageHasViewerReaction(messageId, reactions, emptyMap(), "👍"))
        assertFalse(threadMessageHasViewerReaction(messageId, reactions, emptyMap(), "😂"))
        assertFalse(threadMessageHasViewerReaction(messageId, reactions, emptyMap(), "🔥"))
        assertFalse(threadMessageHasViewerReaction(messageId, reactions, emptyMap(), "   "))

        // Optimistic add of 😂 flips viewer state to true immediately.
        val pendingAdd =
            mapOf(
                threadReactionPendingKey(messageId, "😂") to reactions.toPendingReactionVisual("😂", selected = true)
            )
        assertTrue(threadMessageHasViewerReaction(messageId, reactions, pendingAdd, "😂"))

        // Optimistic removal of 👍 flips viewer state to false immediately.
        val pendingRemove =
            mapOf(
                threadReactionPendingKey(messageId, "👍") to reactions.toPendingReactionVisual("👍", selected = false)
            )
        assertFalse(threadMessageHasViewerReaction(messageId, reactions, pendingRemove, "👍"))
    }

    @Test
    fun activationRoutesTouchToReactionBarAndMouseOrKeyboardToQuickReact() {
        assertEquals(
            ThreadHoverToolbarReactionActivation.OpenReactionBar,
            threadHoverToolbarReactionActivation(PointerType.Touch),
        )
        assertEquals(
            ThreadHoverToolbarReactionActivation.QuickReact,
            threadHoverToolbarReactionActivation(PointerType.Mouse),
        )
        assertEquals(
            ThreadHoverToolbarReactionActivation.QuickReact,
            threadHoverToolbarReactionActivation(PointerType.Stylus),
        )
        assertEquals(
            ThreadHoverToolbarReactionActivation.QuickReact,
            threadHoverToolbarReactionActivation(null),
        )
    }

    @Test
    fun hoverOpenSchedulesOnlyWhenHoveredUnsuppressedAndPopupClosed() {
        assertTrue(
            shouldScheduleHoverToolbarReactionOpen(
                hovered = true,
                suppressedByPress = false,
                reactionPopupOpen = false,
            )
        )
        assertFalse(
            shouldScheduleHoverToolbarReactionOpen(
                hovered = false,
                suppressedByPress = false,
                reactionPopupOpen = false,
            )
        )
        assertFalse(
            shouldScheduleHoverToolbarReactionOpen(
                hovered = true,
                suppressedByPress = true,
                reactionPopupOpen = false,
            )
        )
        assertFalse(
            shouldScheduleHoverToolbarReactionOpen(
                hovered = true,
                suppressedByPress = false,
                reactionPopupOpen = true,
            )
        )
    }

    @Test
    fun hoverToolbarActionsRespectReadOnlySystemAndRetractedMessages() {
        val message =
            RaftThreadMessageItem(
                id = "m-1",
                authorId = "u-1",
                authorName = "Alice",
                authorKind = "human",
                avatarLabel = "A",
                timestampLabel = "2026-11-26T09:00:00Z",
                body = "hello",
            )
        val slice =
            ThreadTimelineSlice(
                status = "ready",
                originStatus = "none",
                messages = emptyList(),
                originMessage = null,
                focusedMessageId = null,
                focusedMessageRequestNonce = 0,
                canLoadMoreBefore = false,
                canLoadMoreAfter = false,
                olderMessagesStatus = "idle",
                newerMessagesStatus = "idle",
                emptyTitle = "",
                emptyDetail = "",
                sourceThreadId = "ch-1",
                alignOwnMessagesRight = false,
                compactConsecutiveMessages = false,
                surfaceKind = RaftThreadSurfaceKind.Channel,
                showMessageSaveAction = true,
                showReactionActions = true,
                showTaskOpenActions = false,
                showTaskStatusActions = false,
                canConvertMessagesToTasks = false,
                beginningReplyCountLabel = null,
                showForwardAction = true,
            )

        val readOnlyCaps =
            threadMessageMenuCapabilities(
                message = message,
                replyMetadata = message.toThreadReplyMetadata(),
                slice = slice,
                writeActionsEnabled = false,
            )
        val readOnlyToolbar =
            assertNotNull(
                threadMessageHoverToolbarActions(
                    message = message,
                    capabilities = readOnlyCaps,
                    onShowReactions = {},
                    onForward = {},
                    onToggleSave = {},
                )
            )
        assertFalse(readOnlyToolbar.reaction, "no reaction button without write/reaction rights")

        val systemMessage = message.copy(id = "m-sys", messageType = "system")
        val systemCaps =
            threadMessageMenuCapabilities(
                message = systemMessage,
                replyMetadata = systemMessage.toThreadReplyMetadata(),
                slice = slice,
                writeActionsEnabled = true,
            )
        val systemToolbar =
            threadMessageHoverToolbarActions(
                message = systemMessage,
                capabilities = systemCaps,
                onShowReactions = {},
                onForward = {},
                onToggleSave = {},
            )
        assertFalse(systemToolbar?.reaction == true, "no reaction button on system messages")

        val retractedMessage = message.copy(id = "m-retracted", deletedAt = "2026-11-26T09:05:00Z")
        val retractedCaps =
            threadMessageMenuCapabilities(
                message = retractedMessage,
                replyMetadata = retractedMessage.toThreadReplyMetadata(),
                slice = slice,
                writeActionsEnabled = true,
            )
        assertNull(
            threadMessageHoverToolbarActions(
                message = retractedMessage,
                capabilities = retractedCaps,
                onShowReactions = {},
                onForward = {},
                onToggleSave = {},
            ),
            "retracted messages have no hover toolbar",
        )
    }
}
