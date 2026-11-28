package build.raft.app

import build.raft.app.resources.*
import build.raft.app.thread.RaftThreadMessageItem
import build.raft.app.thread.RaftThreadPendingSendContext
import build.raft.app.thread.ThreadMessageDeliveryFailed
import build.raft.app.thread.ThreadMessageDeliverySending
import build.raft.app.thread.ThreadMessageDeliverySent
import build.raft.app.thread.ThreadMessageMenuAction
import build.raft.app.thread.ThreadMessageMenuCapabilities
import build.raft.app.thread.ThreadMessageMenuFollowTarget
import build.raft.app.thread.ThreadMessageMenuTranslateState
import build.raft.app.thread.ThreadNewMessageMenuSecondaryToolOrder
import build.raft.app.thread.ThreadNewMessageMenuTool
import build.raft.app.thread.ThreadNewMessageMenuToolCallbacks
import build.raft.app.thread.ThreadReplyMetadata
import build.raft.app.thread.ThreadTimelineSlice
import build.raft.app.thread.compactIcon
import build.raft.app.thread.compactLabelResource
import build.raft.app.thread.dispatch
import build.raft.app.thread.icon
import build.raft.app.thread.resolveThreadJointSurface
import build.raft.app.thread.threadInPlaceSelectionMenuAvailability
import build.raft.app.thread.threadMessageMenuActions
import build.raft.app.thread.threadMessageMenuCapabilities
import build.raft.app.thread.threadMessageSupportsContextView
import build.raft.app.thread.threadViewerIsGuest
import build.raft.app.thread.toThreadNewMessageMenuActionAvailability
import build.raft.app.thread.toThreadReplyMetadata
import build.raft.app.thread.withThreadViewerMessageActions
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.model.ExternalMessageAuthor
import build.raft.app.model.ForwardMessageBundle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Long-press menu parity with web MessageItem's context menu (botiverse/slock 9be029411). */
class ThreadMessageMenuParityTest {
    @Test
    fun ordinaryMessageOffersForwardAddReactionAndSelectMessage() {
        val capabilities = capabilities(message())

        assertTrue(capabilities.quickReactions)
        assertTrue(capabilities.addReaction, "the reaction row ends with Add Reaction (web reaction-add-any)")
        assertTrue(capabilities.forward)
        assertTrue(capabilities.selectMessage)
        assertTrue(capabilities.reply)
        assertTrue(capabilities.save)
        assertTrue(capabilities.copyLink)
        assertTrue(capabilities.convertToTask)
        assertFalse(capabilities.follow, "no thread conversation yet")
        assertFalse(capabilities.cancelLocalSend)
        assertFalse(capabilities.deleteLocalSend)
    }

    @Test
    fun taskV2OffersCreateTaskFromMessageInsteadOfConvertAndTheTaskStatusItems() {
        // Web MessageItem menu (slock staging 3e8ad99e): with task v2 a message's tasks are its list
        // (no single linked task), so no Mark as Done / Reopen, and "Create Task from Message" is
        // offered even on a message that already has a task.
        // (message) -> (task item shown)
        val withTask = message(taskId = "task-1", taskNumber = 11, taskStatus = "in_review")
        data class Case(val name: String, val message: RaftThreadMessageItem, val item: Boolean)
        listOf(
            Case("plain", message(), item = true),
            Case("has a task", withTask, item = true),
            Case("system", message(messageType = "System"), item = false),
        ).forEach { case ->
            val capabilities =
                threadMessageMenuCapabilities(
                    message = case.message,
                    replyMetadata = case.message.toThreadReplyMetadata(),
                    slice = slice(),
                    writeActionsEnabled = true,
                )
            assertEquals(case.item, capabilities.convertToTask, case.name)
            assertTrue(capabilities.createTaskFromMessage, case.name)
            assertFalse(capabilities.taskStatus, case.name)
            val availability = capabilities.toThreadNewMessageMenuActionAvailability(case.message, case.message.toThreadReplyMetadata())
            assertEquals(
                Res.string.thread_action_create_task_from_message,
                ThreadNewMessageMenuTool.ConvertToTask.compactLabelResource(availability),
                case.name,
            )
        }
    }

    @Test
    fun guestsGetNeitherForwardNorSelectMessage() {
        val guestSlice = slice().withThreadViewerMessageActions(viewerIsGuest = true, forwardSourceAvailable = true)
        val capabilities = capabilities(message(), slice = guestSlice)

        assertFalse(capabilities.forward)
        assertFalse(capabilities.selectMessage)
        assertTrue(capabilities.quickReactions, "guests keep reactions like web")
        assertTrue(capabilities.addReaction, "and the full picker with them")
    }

    @Test
    fun forwardFollowsWebGetForwardableMessages() {
        assertTrue(capabilities(message(taskId = "task-1", taskNumber = 3, taskStatus = "todo")).forward)
        assertFalse(capabilities(message(messageType = "System")).forward)
        assertFalse(
            capabilities(message(metadata = mapOf("actionCard.kind" to "action-card", "actionCard.type" to "channel:create"))).forward
        )
        assertFalse(capabilities(message(forwardBundle = ForwardMessageBundle(id = "bundle-1"))).forward)
    }

    @Test
    fun localSendsKeepOnlyTheMobileOnlyActions() {
        val failed = message(deliveryState = ThreadMessageDeliveryFailed).copy(pendingSendContext = pendingSend())
        val capabilities = capabilities(failed)

        assertTrue(capabilities.deleteLocalSend)
        assertFalse(capabilities.cancelLocalSend)
        assertFalse(capabilities.forward)
        assertFalse(capabilities.addReaction)
        assertFalse(capabilities.selectMessage)
        assertFalse(capabilities.copyLink)
        assertFalse(capabilities.reply)
        assertTrue(
            capabilities(message(deliveryState = ThreadMessageDeliverySending).copy(pendingSendContext = pendingSend())).cancelLocalSend
        )
    }

    @Test
    fun compactSecondaryToolsDispatchToTheirCallbacks() {
        val routed = mutableListOf<ThreadNewMessageMenuTool>()
        val callbacks =
            ThreadNewMessageMenuToolCallbacks(
                onCopy = {},
                onReply = {},
                onForward = {},
                onSave = {},
                onTranslate = {},
                onSelect = {},
                onAddReaction = { routed += ThreadNewMessageMenuTool.AddReaction },
                onCopyLink = { routed += ThreadNewMessageMenuTool.CopyLink },
                onFollowThread = { routed += ThreadNewMessageMenuTool.FollowThread },
                onTaskStatus = { routed += ThreadNewMessageMenuTool.TaskStatus },
                onConvertToTask = { routed += ThreadNewMessageMenuTool.ConvertToTask },
                onSelectText = { routed += ThreadNewMessageMenuTool.SelectText },
                onMarkThreadUnread = { routed += ThreadNewMessageMenuTool.MarkThreadUnread },
                onCopySelection = { routed += ThreadNewMessageMenuTool.CopySelection },
                onQuote = { routed += ThreadNewMessageMenuTool.Quote }
            )

        ThreadNewMessageMenuSecondaryToolOrder.forEach { it.dispatch(callbacks) }

        assertEquals(ThreadNewMessageMenuSecondaryToolOrder, routed)
    }

    @Test
    fun quoteActionAppearsInCompactMenuAndHoverToolbarAndHidesInSelectionContext() {
        val baseAvailability =
            capabilities(message())
                .toThreadNewMessageMenuActionAvailability(message(), message().toThreadReplyMetadata())
                .copy(quote = true)
        assertTrue(baseAvailability.enabled(ThreadNewMessageMenuTool.Quote))
        assertEquals(Res.string.thread_action_quote, ThreadNewMessageMenuTool.Quote.compactLabelResource(baseAvailability))
        assertEquals(RaftLucideIconName.TextQuote, ThreadNewMessageMenuTool.Quote.compactIcon(baseAvailability))

        val selectionAvailability = baseAvailability.copy(selectionContext = true)
        assertFalse(selectionAvailability.enabled(ThreadNewMessageMenuTool.Quote))

        val msg = message()
        val caps = capabilities(msg)
        val hoverWithQuote =
            kotlin.test.assertNotNull(
                build.raft.app.thread.threadMessageHoverToolbarActions(
                    message = msg,
                    capabilities = caps,
                    onShowReactions = {},
                    onForward = {},
                    onToggleSave = {},
                    quote = true,
                    onQuote = {},
                )
            )
        assertTrue(hoverWithQuote.quote)
        assertEquals(6, hoverWithQuote.buttonCount)

        val hoverWithoutQuote =
            kotlin.test.assertNotNull(
                build.raft.app.thread.threadMessageHoverToolbarActions(
                    message = msg,
                    capabilities = caps,
                    onShowReactions = {},
                    onForward = {},
                    onToggleSave = {},
                    quote = false,
                    onQuote = {},
                )
            )
        assertFalse(hoverWithoutQuote.quote)
        assertEquals(5, hoverWithoutQuote.buttonCount)
    }

    @Test
    fun classicMessageMenuFollowsWebMenuOrderV4GroupsAndOrder() {
        // botiverse/slock #16225 (563672da5): thread -> task -> take-it-elsewhere -> personal.
        val followedMessage = message(threadChannelId = "thread-1", replyCount = 2, isThreadFollowed = true)
        val actions =
            threadMessageMenuActions(
                message = followedMessage,
                replyMetadata = followedMessage.toThreadReplyMetadata(),
                showReplyAction = true,
                showSaveAction = true,
                showFollowAction = true,
                showTaskStatusAction = false,
                showConvertToTaskAction = true,
                createTaskFromMessage = true,
                showCopyLinkAction = true,
                showShareMessagesAction = true,
                showCancelAction = false,
                showDeleteAction = false,
                showTranslateAction = true,
                showForwardAction = true,
                showMarkThreadUnreadAction = true,
                showQuoteAction = true,
                onReply = {},
                onCopyLink = {},
                onCopy = {},
                onSelectText = {},
                onShareMessages = {},
                onSave = {},
                onFollowThread = {},
                onUnfollowThread = {},
                onTaskStatusChange = {},
                onConvertToTask = {},
                onCancel = {},
                onDelete = {},
            )
        assertEquals(
            listOf(
                Res.string.thread_action_reply_in_thread.desc() to "thread",
                Res.string.thread_action_unfollow_thread.desc() to "thread",
                Res.string.thread_action_mark_thread_unread.desc() to "thread",
                Res.string.thread_action_create_task_from_message.desc() to "task",
                Res.string.thread_action_quote.desc() to "copy",
                Res.string.thread_action_copy_markdown.desc() to "copy",
                Res.string.thread_action_copy_link.desc() to "copy",
                Res.string.thread_action_forward.desc() to "copy",
                Res.string.thread_action_select_text.desc() to "copy",
                Res.string.thread_action_select_message.desc() to "copy",
                Res.string.thread_action_save_message.desc() to "personal",
                Res.string.thread_action_translate.desc() to "personal",
            ),
            actions.map { it.label to it.group },
        )
        // Compact secondary row also puts FollowThread before MarkThreadUnread and has no conversation unread tool.
        assertEquals(
            listOf(
                ThreadNewMessageMenuTool.CopySelection,
                ThreadNewMessageMenuTool.Quote,
                ThreadNewMessageMenuTool.SelectText,
                ThreadNewMessageMenuTool.CopyLink,
                ThreadNewMessageMenuTool.FollowThread,
                ThreadNewMessageMenuTool.MarkThreadUnread,
                ThreadNewMessageMenuTool.TaskStatus,
                ThreadNewMessageMenuTool.ConvertToTask,
            ),
            ThreadNewMessageMenuSecondaryToolOrder,
        )
    }

    @Test
    fun viewerGuestRoleIsReadFromTheConversationServer() {
        val session =
            StoredAuthSession(
                user = StoredAuthUser(id = "user-1", email = "u@example.com"),
                accessToken = "a",
                refreshToken = "r",
                selectedServerId = "server-1",
                servers =
                    listOf(
                        StoredAuthServer(id = "server-1", name = "Home", role = "member"),
                        StoredAuthServer(id = "server-2", name = "Partner", role = "Guest")
                    )
            )

        assertFalse(session.threadViewerIsGuest(null))
        assertTrue(session.threadViewerIsGuest("server-2"))
        assertFalse((null as StoredAuthSession?).threadViewerIsGuest("server-2"))
    }

    @Test
    fun aRightClickOverSelectedTextLeadsTheMenuWithCopy() {
        // artin, 2026-10-29 (Mac): right-click opens the message menu; text selected in the
        // message adds "Copy" for it, first in the tools.
        var copied = 0
        val classic = classicActions(message(), onCopySelection = { copied += 1 })
        val first = classic.first()
        assertEquals(Res.string.common_copy.desc(), first.label)
        assertEquals(RaftLucideIconName.Copy, first.icon)
        assertEquals("copy", first.group)
        first.onClick()
        assertEquals(1, copied)
        assertEquals(
            classic.drop(1).map { it.label },
            classicActions(message()).map { it.label },
            "nothing selected: the menu is unchanged"
        )

        val plain = capabilities(message()).toThreadNewMessageMenuActionAvailability(message(), message().toThreadReplyMetadata())
        assertFalse(ThreadNewMessageMenuTool.CopySelection in plain.secondaryTools(), "not in the long-press menu")
        val overSelection = plain.copy(copySelection = true)
        assertEquals(
            listOf(ThreadNewMessageMenuTool.CopySelection, ThreadNewMessageMenuTool.SelectText),
            overSelection.secondaryTools().take(2)
        )
        assertEquals(plain.primaryTools(), overSelection.primaryTools(), "the full message menu stays")
        assertTrue(overSelection.showsReactionRow())
        assertEquals(Res.string.common_copy, ThreadNewMessageMenuTool.CopySelection.compactLabelResource(overSelection))
        // Not Copy: that is Copy Markdown in the same menu.
        assertEquals(RaftLucideIconName.Type, ThreadNewMessageMenuTool.CopySelection.compactIcon(overSelection))
        assertFalse(
            ThreadNewMessageMenuTool.CopySelection in threadInPlaceSelectionMenuAvailability(overSelection, coversAll = false).secondaryTools(),
            "the selection menu has its own Copy"
        )
    }

    @Test
    fun contextViewIsHiddenOnJointSurfacesAndNonEligibleMessages() {
        val agentMessage = message(authorKind = "agent")
        assertTrue(threadMessageSupportsContextView(agentMessage))
        assertTrue(threadMessageSupportsContextView(agentMessage, onJointSurface = false))
        assertFalse(
            threadMessageSupportsContextView(agentMessage, onJointSurface = true),
            "joint channels, reply threads, and task conversations suppress View Context (web parity)"
        )
        assertFalse(threadMessageSupportsContextView(message(authorKind = "human")))
        assertFalse(threadMessageSupportsContextView(message(authorKind = "agent", messageType = "System")))
        assertFalse(
            threadMessageSupportsContextView(
                message(
                    authorKind = "agent",
                    externalAuthor = ExternalMessageAuthor(displayName = "Bridge Bot", provider = "slack", actorKind = "bot")
                )
            )
        )
        assertFalse(
            threadMessageSupportsContextView(
                message(authorKind = "agent", deliveryState = ThreadMessageDeliverySending)
            )
        )

        assertFalse(
            resolveThreadJointSurface(
                jointMessageSurface = false,
                taskChannelType = "channel",
                taskParentChannelId = null,
                currentServerId = "s-1",
                conversationIdentityProvider = null,
            )
        )
        assertTrue(
            resolveThreadJointSurface(
                jointMessageSurface = true,
                taskChannelType = null,
                taskParentChannelId = null,
                currentServerId = "s-1",
                conversationIdentityProvider = null,
            )
        )
        assertTrue(
            resolveThreadJointSurface(
                jointMessageSurface = false,
                taskChannelType = "joint",
                taskParentChannelId = null,
                currentServerId = "s-1",
                conversationIdentityProvider = null,
            )
        )
    }

    @Test
    fun replyThreadMessagesOfferEnclosingThreadFollowInClassicAndCompactMenus() {
        val origin = message(id = "parent-1", threadChannelId = "thread-ch-1", replyCount = 2, isThreadFollowed = false)
        val reply = message(id = "reply-2")
        val replyThreadSlice =
            slice(surfaceKind = RaftThreadSurfaceKind.ReplyThread, originMessage = origin).copy(
                sourceThreadId = "thread-ch-1",
            )
        val unfollowedOriginMeta =
            ThreadReplyMetadata(
                parentMessageId = "parent-1",
                threadChannelId = "thread-ch-1",
                replyCount = 2,
                unreadReplyCount = 0,
                firstUnreadReplyMessageId = null,
                isThreadFollowed = false,
            )
        val followedOriginMeta = unfollowedOriginMeta.copy(isThreadFollowed = true)

        // Reply row inside an unfollowed reply thread -> Follow thread acting on parent-1.
        val unfollowedCaps =
            threadMessageMenuCapabilities(
                message = reply,
                replyMetadata = reply.toThreadReplyMetadata(),
                slice = replyThreadSlice,
                writeActionsEnabled = true,
                originReplyMetadata = unfollowedOriginMeta,
            )
        assertTrue(unfollowedCaps.follow)
        assertEquals(
            ThreadMessageMenuFollowTarget(
                parentMessageId = "parent-1",
                threadChannelId = "thread-ch-1",
                isFollowed = false,
            ),
            unfollowedCaps.followTarget,
        )
        val unfollowedCompact =
            unfollowedCaps.toThreadNewMessageMenuActionAvailability(reply, reply.toThreadReplyMetadata())
        assertTrue(unfollowedCompact.followThread)
        assertFalse(unfollowedCompact.threadFollowed)
        var followedInvocations = 0
        var unfollowedChannel: String? = null
        val unfollowedActions =
            threadMessageMenuActions(
                message = reply,
                replyMetadata = reply.toThreadReplyMetadata(),
                showReplyAction = false,
                showSaveAction = true,
                showFollowAction = unfollowedCaps.follow,
                showTaskStatusAction = false,
                showConvertToTaskAction = false,
                showCopyLinkAction = true,
                showShareMessagesAction = true,
                showCancelAction = false,
                showDeleteAction = false,
                onReply = {},
                onCopyLink = {},
                onCopy = {},
                onSelectText = {},
                onShareMessages = {},
                onSave = {},
                onFollowThread = { followedInvocations += 1 },
                onUnfollowThread = { unfollowedChannel = it },
                onTaskStatusChange = {},
                onConvertToTask = {},
                onCancel = {},
                onDelete = {},
                followTarget = unfollowedCaps.followTarget,
            )
        val followRow = unfollowedActions.first { it.group == "thread" }
        assertEquals(Res.string.thread_action_follow_thread.desc(), followRow.label)
        assertEquals(RaftLucideIconName.FollowThread, followRow.icon)
        followRow.onClick()
        assertEquals(1, followedInvocations)
        assertEquals(null, unfollowedChannel)

        // Reply row inside a followed reply thread -> Unfollow thread acting on parent-1 / thread-ch-1.
        val followedCaps =
            threadMessageMenuCapabilities(
                message = reply,
                replyMetadata = reply.toThreadReplyMetadata(),
                slice = replyThreadSlice,
                writeActionsEnabled = true,
                originReplyMetadata = followedOriginMeta,
            )
        assertTrue(followedCaps.follow)
        assertEquals(
            ThreadMessageMenuFollowTarget(
                parentMessageId = "parent-1",
                threadChannelId = "thread-ch-1",
                isFollowed = true,
            ),
            followedCaps.followTarget,
        )
        val followedCompact =
            followedCaps.toThreadNewMessageMenuActionAvailability(reply, reply.toThreadReplyMetadata())
        assertTrue(followedCompact.followThread)
        assertTrue(followedCompact.threadFollowed)
        val followedActions =
            threadMessageMenuActions(
                message = reply,
                replyMetadata = reply.toThreadReplyMetadata(),
                showReplyAction = false,
                showSaveAction = true,
                showFollowAction = followedCaps.follow,
                showTaskStatusAction = false,
                showConvertToTaskAction = false,
                showCopyLinkAction = true,
                showShareMessagesAction = true,
                showCancelAction = false,
                showDeleteAction = false,
                onReply = {},
                onCopyLink = {},
                onCopy = {},
                onSelectText = {},
                onShareMessages = {},
                onSave = {},
                onFollowThread = { followedInvocations += 1 },
                onUnfollowThread = { unfollowedChannel = it },
                onTaskStatusChange = {},
                onConvertToTask = {},
                onCancel = {},
                onDelete = {},
                followTarget = followedCaps.followTarget,
            )
        val unfollowRow = followedActions.first { it.group == "thread" }
        assertEquals(Res.string.thread_action_unfollow_thread.desc(), unfollowRow.label)
        assertEquals(RaftLucideIconName.UnfollowThread, unfollowRow.icon)
        unfollowRow.onClick()
        assertEquals("thread-ch-1", unfollowedChannel)

        // Local send reply row inside a reply thread must not offer Follow/Unfollow.
        val sendingReply =
            message(id = "reply-local", deliveryState = ThreadMessageDeliverySending)
                .copy(pendingSendContext = pendingSend())
        val localCaps =
            threadMessageMenuCapabilities(
                message = sendingReply,
                replyMetadata = sendingReply.toThreadReplyMetadata(),
                slice = replyThreadSlice,
                writeActionsEnabled = true,
                originReplyMetadata = followedOriginMeta,
            )
        assertFalse(localCaps.follow)
        assertEquals(null, localCaps.followTarget)

        // Followed reply thread without a thread channel id hides unfollow rather than offering a no-op.
        val missingChannelSlice = replyThreadSlice.copy(sourceThreadId = null)
        val missingChannelCaps =
            threadMessageMenuCapabilities(
                message = reply,
                replyMetadata = reply.toThreadReplyMetadata(),
                slice = missingChannelSlice,
                writeActionsEnabled = true,
                originReplyMetadata = followedOriginMeta.copy(threadChannelId = null),
            )
        assertFalse(missingChannelCaps.follow)
        assertEquals(null, missingChannelCaps.followTarget)

        // 0-reply origin inside a reply thread (no threadChannelId yet) still offers Follow thread (web parity).
        val zeroReplyOrigin = message(id = "parent-empty")
        val zeroReplySlice =
            slice(surfaceKind = RaftThreadSurfaceKind.ReplyThread, originMessage = zeroReplyOrigin).copy(
                sourceThreadId = null,
            )
        val zeroReplyCaps =
            threadMessageMenuCapabilities(
                message = zeroReplyOrigin,
                replyMetadata = zeroReplyOrigin.toThreadReplyMetadata(),
                slice = zeroReplySlice,
                writeActionsEnabled = true,
                originReplyMetadata = zeroReplyOrigin.toThreadReplyMetadata(),
            )
        assertTrue(zeroReplyCaps.follow)
        assertEquals(
            ThreadMessageMenuFollowTarget(
                parentMessageId = "parent-empty",
                threadChannelId = null,
                isFollowed = false,
            ),
            zeroReplyCaps.followTarget,
        )

        // Followed reply thread where originReplyMetadata omits threadChannelId falls back to slice.sourceThreadId.
        val fallbackChannelCaps =
            threadMessageMenuCapabilities(
                message = reply,
                replyMetadata = reply.toThreadReplyMetadata(),
                slice = replyThreadSlice,
                writeActionsEnabled = true,
                originReplyMetadata = followedOriginMeta.copy(threadChannelId = null),
            )
        assertTrue(fallbackChannelCaps.follow)
        assertEquals(
            ThreadMessageMenuFollowTarget(
                parentMessageId = "parent-1",
                threadChannelId = "thread-ch-1",
                isFollowed = true,
            ),
            fallbackChannelCaps.followTarget,
        )

        // Task conversation suppresses nested thread follow even when origin/reply metadata has thread fields.
        val taskSlice =
            slice(surfaceKind = RaftThreadSurfaceKind.Channel, originMessage = origin).copy(
                channelKind = RaftTaskConversationChannelKind,
            )
        val taskCaps =
            threadMessageMenuCapabilities(
                message = origin,
                replyMetadata = followedOriginMeta,
                slice = taskSlice,
                writeActionsEnabled = true,
                originReplyMetadata = followedOriginMeta,
            )
        assertFalse(taskCaps.follow)
        assertEquals(null, taskCaps.followTarget)
    }

    private fun message(
        id: String = "message-1",
        authorKind: String = "human",
        body: String = "Hello",
        messageType: String = "Chat",
        metadata: Map<String, String> = emptyMap(),
        taskId: String? = null,
        taskNumber: Int? = null,
        taskStatus: String? = null,
        externalAuthor: ExternalMessageAuthor? = null,
        threadChannelId: String? = null,
        replyCount: Int = 0,
        isThreadFollowed: Boolean = false,
        isSaved: Boolean = false,
        forwardBundle: ForwardMessageBundle? = null,
        deliveryState: String = ThreadMessageDeliverySent
    ): RaftThreadMessageItem =
        RaftThreadMessageItem(
            id = id,
            authorId = "member-1",
            authorName = "Member",
            authorKind = authorKind,
            messageType = messageType,
            metadata = metadata,
            body = body,
            taskId = taskId,
            taskNumber = taskNumber,
            taskStatus = taskStatus,
            externalAuthor = externalAuthor,
            threadChannelId = threadChannelId,
            replyCount = replyCount,
            isThreadFollowed = isThreadFollowed,
            isSaved = isSaved,
            forwardBundle = forwardBundle,
            deliveryState = deliveryState
        )

    private fun pendingSend(): RaftThreadPendingSendContext =
        RaftThreadPendingSendContext(channelId = "channel-1", body = "Hello")

    private fun slice(
        surfaceKind: String = RaftThreadSurfaceKind.Channel,
        originMessage: RaftThreadMessageItem? = null
    ): ThreadTimelineSlice =
        ThreadTimelineSlice(
            status = "ready",
            originStatus = "ready",
            messages = emptyList(),
            originMessage = originMessage,
            focusedMessageId = null,
            focusedMessageRequestNonce = 0,
            canLoadMoreBefore = false,
            canLoadMoreAfter = false,
            olderMessagesStatus = "idle",
            newerMessagesStatus = "idle",
            emptyTitle = "",
            emptyDetail = null,
            sourceThreadId = null,
            alignOwnMessagesRight = false,
            compactConsecutiveMessages = false,
            surfaceKind = surfaceKind,
            showMessageSaveAction = true,
            showReactionActions = true,
            showTaskOpenActions = true,
            showTaskStatusActions = true,
            canConvertMessagesToTasks = true,
            beginningReplyCountLabel = null,
            showForwardAction = true
        )

    private fun capabilities(
        message: RaftThreadMessageItem,
        slice: ThreadTimelineSlice = slice(),
        writeActionsEnabled: Boolean = true,
        translate: ThreadMessageMenuTranslateState = ThreadMessageMenuTranslateState.Hidden
    ): ThreadMessageMenuCapabilities =
        threadMessageMenuCapabilities(
            message = message,
            replyMetadata = message.toThreadReplyMetadata(),
            slice = slice,
            writeActionsEnabled = writeActionsEnabled,
            translate = translate
        )

    private fun classicActions(
        message: RaftThreadMessageItem,
        showForwardAction: Boolean = false,
        showTranslateAction: Boolean = false,
        translatePending: Boolean = false,
        onForward: () -> Unit = {},
        onCopySelection: (() -> Unit)? = null
    ): List<ThreadMessageMenuAction> =
        threadMessageMenuActions(
            message = message,
            showReplyAction = true,
            showSaveAction = true,
            showFollowAction = false,
            showTaskStatusAction = false,
            showConvertToTaskAction = false,
            showCopyLinkAction = true,
            showShareMessagesAction = true,
            showCancelAction = false,
            showDeleteAction = false,
            showTranslateAction = showTranslateAction,
            translatePending = translatePending,
            showForwardAction = showForwardAction,
            onReply = {},
            onCopyLink = {},
            onCopy = {},
            onSelectText = {},
            onShareMessages = {},
            onSave = {},
            onFollowThread = {},
            onUnfollowThread = {},
            onTaskStatusChange = {},
            onConvertToTask = {},
            onCancel = {},
            onDelete = {},
            onForward = onForward,
            onCopySelection = onCopySelection
        )
}
