package build.raft.app

import build.raft.app.model.MemberKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Web parity for DMs without threads Lab (`dm_main_timeline_lab_v0`, botiverse/slock #16664,
 * `#wg-mobile-sync:#305`): in a DM with an agent when the flag is on, every entry that starts a
 * new thread reply is hidden or disabled (hover toolbar, classic/context/More menu, single-tap on
 * row, and the Compact menu's Reply tool — disabled in the fixed 6-slot primary row and hidden in
 * partial text selection), while existing threads still show their replies badge and follow
 * actions. Human DMs, channels, and flag-off are unchanged.
 */
class DmMainTimelineLabTest {
    @Test
    fun hidesReplyStartOnlyInAgentDmWhenFlagEnabled() {
        assertTrue(
            threadDmMainTimelineLabHidesReplyStart(
                flagEnabled = true,
                surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                channelKind = "dm",
                dmPeerKind = MemberKind.Agent,
            )
        )
        assertTrue(
            threadDmMainTimelineLabHidesReplyStart(
                flagEnabled = true,
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = "dm",
                dmPeerKind = MemberKind.Agent,
            )
        )
        assertFalse(
            threadDmMainTimelineLabHidesReplyStart(
                flagEnabled = false,
                surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                channelKind = "dm",
                dmPeerKind = MemberKind.Agent,
            ),
            "flag off keeps Reply in thread",
        )
        assertFalse(
            threadDmMainTimelineLabHidesReplyStart(
                flagEnabled = true,
                surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                channelKind = "dm",
                dmPeerKind = MemberKind.Human,
            ),
            "human DM is unaffected",
        )
        assertFalse(
            threadDmMainTimelineLabHidesReplyStart(
                flagEnabled = true,
                surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                channelKind = "dm",
                dmPeerKind = null,
            ),
            "unknown peer kind fails closed (keeps Reply in thread)",
        )
        assertFalse(
            threadDmMainTimelineLabHidesReplyStart(
                flagEnabled = true,
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = "public",
                dmPeerKind = MemberKind.Agent,
            ),
            "regular channel is unaffected",
        )
        assertFalse(
            threadDmMainTimelineLabHidesReplyStart(
                flagEnabled = true,
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                channelKind = "dm",
                dmPeerKind = MemberKind.Agent,
            ),
            "reply thread already disables reply actions via surfaceKind",
        )
    }

    @Test
    fun agentDmWithFlagOnHidesMenuHoverAndRowTapReplyStartWhilePreservingExistingThreadBadgeAndFollow() {
        val baseDmSlice = dmSlice()
        val hiddenSlice =
            baseDmSlice.withThreadViewerMessageActions(
                viewerIsGuest = false,
                forwardSourceAvailable = true,
                hideThreadReplyStart = true,
            )
        val plainMessage = message(id = "m-plain")
        val plainReplyMeta = plainMessage.toThreadReplyMetadata()

        assertTrue(hiddenSlice.showReplyActions, "existing-thread surfaces stay enabled")
        assertFalse(hiddenSlice.allowStartThreadReply, "starting a new thread is hidden")

        val plainCaps =
            threadMessageMenuCapabilities(
                message = plainMessage,
                replyMetadata = plainReplyMeta,
                slice = hiddenSlice,
                writeActionsEnabled = true,
            )
        assertFalse(plainCaps.reply, "capabilities.reply must be false")
        assertFalse(
            threadMessageMenuShowsReplyInThread(
                capabilities = plainCaps,
                message = plainMessage,
                replyMetadata = plainReplyMeta,
                slice = hiddenSlice,
                hasReplyDraft = false,
            ),
            "classic/long-press menu hides Reply in thread",
        )
        val compactAvailability = plainCaps.toThreadNewMessageMenuActionAvailability(plainMessage, plainReplyMeta)
        assertFalse(
            compactAvailability.enabled(ThreadNewMessageMenuTool.Reply),
            "compact menu disables Reply tool",
        )
        assertFalse(
            ThreadNewMessageMenuTool.Reply in
                threadInPlaceSelectionMenuAvailability(compactAvailability, coversAll = false).primaryTools(),
            "partial in-place text selection menu hides Reply tool",
        )
        val hoverToolbar =
            assertNotNull(
                threadMessageHoverToolbarActions(
                    message = plainMessage,
                    capabilities = plainCaps,
                    onShowReactions = {},
                    onForward = {},
                    onToggleSave = {},
                )
            )
        assertFalse(hoverToolbar.reply, "hover toolbar hides Reply in thread button")
        assertTrue(hoverToolbar.reaction, "hover toolbar keeps Add Reaction")
        assertTrue(hoverToolbar.more, "hover toolbar keeps More Actions")
        assertFalse(
            threadMessageTapOpensThread(
                showReplyAction = hiddenSlice.allowStartThreadReply,
                shareSelectionActive = false,
                hasLocalSendState = false,
            ),
            "single-tap on message row does not start a new thread",
        )

        // Existing thread on a message in the same agent DM: replies badge & follow still work.
        val threadedMessage =
            message(
                id = "m-threaded",
                threadChannelId = "thread-1",
                replyCount = 3,
                isThreadFollowed = true,
            )
        val threadedMeta = threadedMessage.toThreadReplyMetadata()
        val badge =
            threadRepliesBadgeSpec(
                replyMetadata = threadedMeta,
                hasDraft = false,
                showReplyAction = hiddenSlice.showReplyActions,
                hasInlineReplies = false,
            )
        assertNotNull(badge, "existing replies badge remains visible so user can open existing thread")
        assertEquals(3, badge.replyCount)
        val inlinePresentation =
            threadInlineReplyPresentationForMessage(
                model =
                    build.raft.app.network.sync.ThreadRepliesReadModel(
                        replies =
                            listOf(
                                build.raft.app.network.sync.ReplyPreview(
                                    messageId = "r-1",
                                    seq = 1L,
                                    content = "existing reply",
                                    senderId = "a-scout",
                                    senderType = "agent",
                                    senderName = "scout",
                                    createdAt = "2026-11-26T09:01:00Z",
                                )
                            ),
                        replyCount = 3,
                        snapshotSeq = 1L,
                    ),
                replyMetadata = threadedMeta,
                hasDraft = false,
                showReplyAction = hiddenSlice.showReplyActions,
            )
        assertNotNull(inlinePresentation, "existing inline replies remain visible so user can open existing thread")
        assertEquals(3, inlinePresentation.replyCount)
        val threadedCaps =
            threadMessageMenuCapabilities(
                message = threadedMessage,
                replyMetadata = threadedMeta,
                slice = hiddenSlice,
                writeActionsEnabled = true,
            )
        assertFalse(threadedCaps.reply)
        assertTrue(threadedCaps.follow, "follow/unfollow stays available on an existing thread")
        assertEquals("thread-1", threadedCaps.markThreadUnreadChannelId)
    }

    @Test
    fun humanDmAndFlagOffKeepAllReplyInThreadEntries() {
        val visibleSlice =
            dmSlice().withThreadViewerMessageActions(
                viewerIsGuest = false,
                forwardSourceAvailable = true,
                hideThreadReplyStart = false,
            )
        val msg = message(id = "m-visible")
        val meta = msg.toThreadReplyMetadata()
        val caps =
            threadMessageMenuCapabilities(
                message = msg,
                replyMetadata = meta,
                slice = visibleSlice,
                writeActionsEnabled = true,
            )
        assertTrue(visibleSlice.allowStartThreadReply)
        assertTrue(caps.reply)
        assertTrue(
            threadMessageMenuShowsReplyInThread(
                capabilities = caps,
                message = msg,
                replyMetadata = meta,
                slice = visibleSlice,
                hasReplyDraft = false,
            )
        )
        assertTrue(caps.toThreadNewMessageMenuActionAvailability(msg, meta).enabled(ThreadNewMessageMenuTool.Reply))
        val hoverToolbar =
            assertNotNull(
                threadMessageHoverToolbarActions(
                    message = msg,
                    capabilities = caps,
                    onShowReactions = {},
                    onForward = {},
                    onToggleSave = {},
                )
            )
        assertTrue(hoverToolbar.reply)
        assertTrue(
            threadMessageTapOpensThread(
                showReplyAction = visibleSlice.allowStartThreadReply,
                shareSelectionActive = false,
                hasLocalSendState = false,
            )
        )
    }

    private fun dmSlice(): ThreadTimelineSlice =
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
            sourceThreadId = "dm-1",
            alignOwnMessagesRight = false,
            compactConsecutiveMessages = false,
            surfaceKind = RaftThreadSurfaceKind.DirectMessage,
            channelKind = "dm",
            directMessageSurface = true,
            showMessageSaveAction = true,
            showReactionActions = true,
            showTaskOpenActions = false,
            showTaskStatusActions = false,
            canConvertMessagesToTasks = false,
            beginningReplyCountLabel = null,
        )

    private fun message(
        id: String,
        threadChannelId: String? = null,
        replyCount: Int = 0,
        isThreadFollowed: Boolean = false,
    ): RaftThreadMessageItem =
        RaftThreadMessageItem(
            id = id,
            authorName = "Cindy",
            authorId = "u-1",
            authorKind = "human",
            timestampLabel = "09:00",
            body = "hello",
            threadChannelId = threadChannelId,
            replyCount = replyCount,
            isThreadFollowed = isThreadFollowed,
        )
}
