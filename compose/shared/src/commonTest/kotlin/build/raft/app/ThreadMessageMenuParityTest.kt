package build.raft.app

import build.raft.app.data.SharedMessageTranslationEntry
import build.raft.app.data.SharedMessageTranslationSettings
import build.raft.app.data.SharedMessageTranslationState
import build.raft.app.data.SharedMessageTranslationStatus
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.TranslationModePreference
import build.raft.app.model.ExternalMessageAuthor
import build.raft.app.model.ForwardMessageBundle
import build.raft.app.resources.MR
import com.tencent.tmm.kmmresource.resource.desc.desc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Long-press menu parity with web MessageItem's context menu (botiverse/slock 9be029411). */
class ThreadMessageMenuParityTest {
    @Test
    fun ordinaryMessageOffersForwardAddReactionAndSelectMessage() {
        val capabilities = capabilities(message())

        assertTrue(capabilities.quickReactions)
        assertFalse(capabilities.addReaction, "web's message menu has no Add reaction row")
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
    fun guestsGetNeitherForwardNorSelectMessage() {
        val guestSlice = slice().withThreadViewerMessageActions(viewerIsGuest = true, forwardSourceAvailable = true)
        val capabilities = capabilities(message(), slice = guestSlice)

        assertFalse(capabilities.forward)
        assertFalse(capabilities.selectMessage)
        assertTrue(capabilities.quickReactions, "guests keep reactions like web")
        assertFalse(capabilities.addReaction)
    }

    @Test
    fun forwardNeedsASourceChannelAndWriteAccess() {
        val noSource = slice().withThreadViewerMessageActions(viewerIsGuest = false, forwardSourceAvailable = false)
        assertFalse(capabilities(message(), slice = noSource).forward)
        assertFalse(capabilities(message(), writeActionsEnabled = false).forward)
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
    fun systemMessagesHideReactionsAndSelectMessage() {
        val capabilities = capabilities(message(messageType = "System"))

        assertFalse(capabilities.quickReactions)
        assertFalse(capabilities.addReaction)
        assertFalse(capabilities.selectMessage)
        assertFalse(capabilities.forward)
    }

    @Test
    fun externalSendersCannotBeConvertedOrHaveTheirTaskToggled() {
        val external = ExternalMessageAuthor(displayName = "Slack user", provider = "slack", actorKind = "user")
        assertFalse(capabilities(message(externalAuthor = external)).convertToTask)
        assertFalse(
            capabilities(message(externalAuthor = external, taskId = "task-1", taskNumber = 1, taskStatus = "todo")).taskStatus
        )
        assertTrue(capabilities(message(taskId = "task-1", taskNumber = 1, taskStatus = "todo")).taskStatus)
    }

    @Test
    fun followNeedsAThreadConversationOnAThreadActionSurface() {
        val withThread = message(threadChannelId = "thread-1", replyCount = 2)
        assertTrue(capabilities(withThread).follow)
        assertFalse(capabilities(withThread, slice = slice(showReplyActions = false)).follow)
        // The thread panel's parent keeps Follow (web showThreadFollowAction).
        assertTrue(
            capabilities(withThread, slice = slice(showReplyActions = false, originMessage = withThread)).follow
        )
        assertFalse(capabilities(withThread, writeActionsEnabled = false).follow)
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
    fun classicMenuOrdersReplyInThreadForwardThenSaveAfterAddReaction() {
        var forwarded = 0
        var reactionPickerOpened = 0
        val actions =
            classicActions(
                message(),
                showForwardAction = true,
                showAddReactionAction = true,
                onForward = { forwarded += 1 },
                onAddReaction = { reactionPickerOpened += 1 }
            )

        assertEquals(
            listOf(
                MR.strings.thread_action_add_reaction.desc(),
                MR.strings.thread_action_copy_link.desc(),
                MR.strings.thread_action_copy_markdown.desc(),
                MR.strings.thread_action_select_text.desc(),
                MR.strings.thread_action_select_message.desc(),
                MR.strings.thread_action_reply_in_thread.desc(),
                MR.strings.thread_action_forward.desc(),
                MR.strings.thread_action_save_message.desc()
            ),
            actions.map { it.label }
        )
        assertEquals(RaftLucideIconName.SmilePlus, actions.first().icon)
        assertEquals(RaftLucideIconName.Send, actions.single { it.label == MR.strings.thread_action_forward.desc() }.icon)
        actions.single { it.label == MR.strings.thread_action_forward.desc() }.onClick()
        actions.first().onClick()
        assertEquals(1, forwarded)
        assertEquals(1, reactionPickerOpened)
    }

    @Test
    fun classicTranslateShowsADisabledTranslatingEntryWhilePending() {
        val pending = classicActions(message(), showTranslateAction = true, translatePending = true)
        val entry = pending.single { it.icon == RaftLucideIconName.Languages }
        assertEquals(MR.strings.translation_state_in_progress.desc(), entry.label)
        assertFalse(entry.enabled)

        val idle = classicActions(message(), showTranslateAction = true).single { it.icon == RaftLucideIconName.Languages }
        assertEquals(MR.strings.thread_action_translate.desc(), idle.label)
        assertTrue(idle.enabled)
    }

    @Test
    fun classicSaveTogglesToRemoveFromSaved() {
        val saved = classicActions(message(isSaved = true)).single { it.group == "message" && it.icon == RaftLucideIconName.BookmarkMinus }
        assertEquals(MR.strings.thread_action_remove_from_saved.desc(), saved.label)
    }

    @Test
    fun translateEntryTightensEligibilityLikeWeb() {
        val foreign = message(body = "你好")
        assertEquals(ThreadMessageMenuTranslateState.Available, translateState(foreign, entry = null))
        assertEquals(
            ThreadMessageMenuTranslateState.Pending,
            translateState(foreign, entry = entry(SharedMessageTranslationStatus.Pending))
        )
        assertEquals(
            ThreadMessageMenuTranslateState.Hidden,
            translateState(foreign, entry = entry(SharedMessageTranslationStatus.Translated, translated = "Hello"))
        )
        assertEquals(
            ThreadMessageMenuTranslateState.Hidden,
            translateState(foreign, entry = entry(SharedMessageTranslationStatus.Failed))
        )
        // A stale entry produced for older content does not hide the entry.
        assertEquals(
            ThreadMessageMenuTranslateState.Available,
            translateState(foreign, entry = entry(SharedMessageTranslationStatus.Failed, original = "旧内容"))
        )
        assertEquals(ThreadMessageMenuTranslateState.Hidden, translateState(message(body = "  "), entry = null))
        assertEquals(
            ThreadMessageMenuTranslateState.Hidden,
            translateState(message(body = "你好", metadata = mapOf("actionCard.kind" to "action-card")), entry = null)
        )
        assertEquals(
            ThreadMessageMenuTranslateState.Hidden,
            threadMessageMenuTranslateState(foreign, SharedMessageTranslationState(settings = translationSettings()), translationAvailable = false)
        )
    }

    @Test
    fun compactMenuAddsSecondaryRowWithTheClassicOnlyActions() {
        val withThread = message(threadChannelId = "thread-1", replyCount = 1, isThreadFollowed = true)
        val availability =
            capabilities(withThread).toThreadNewMessageMenuActionAvailability(withThread, withThread.toThreadReplyMetadata())

        assertEquals(
            listOf(
                ThreadNewMessageMenuTool.CopyLink,
                ThreadNewMessageMenuTool.FollowThread,
                ThreadNewMessageMenuTool.ConvertToTask
            ),
            availability.secondaryTools()
        )
        assertTrue(availability.threadFollowed)
        assertTrue(availability.enabled(ThreadNewMessageMenuTool.Forward))

        val task = message(taskId = "task-1", taskNumber = 1, taskStatus = "done", isSaved = true)
        val taskAvailability = capabilities(task).toThreadNewMessageMenuActionAvailability(task, task.toThreadReplyMetadata())
        assertTrue(ThreadNewMessageMenuTool.TaskStatus in taskAvailability.secondaryTools())
        assertFalse(ThreadNewMessageMenuTool.ConvertToTask in taskAvailability.secondaryTools())
        assertTrue(taskAvailability.taskDone)
        assertTrue(taskAvailability.saved)
        assertEquals(RaftLucideIconName.BookmarkMinus, ThreadNewMessageMenuTool.Save.compactIcon(taskAvailability))
    }

    @Test
    fun compactMenuGreysOutSelectForGuestsAndTranslateWhilePending() {
        val guestSlice = slice().withThreadViewerMessageActions(viewerIsGuest = true, forwardSourceAvailable = true)
        val availability =
            capabilities(message(), slice = guestSlice, translate = ThreadMessageMenuTranslateState.Pending)
                .toThreadNewMessageMenuActionAvailability(message(), message().toThreadReplyMetadata())

        assertFalse(availability.enabled(ThreadNewMessageMenuTool.Select))
        assertFalse(availability.enabled(ThreadNewMessageMenuTool.Forward))
        assertTrue(availability.translatePending)
        assertFalse(availability.enabled(ThreadNewMessageMenuTool.Translate))
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
                onConvertToTask = { routed += ThreadNewMessageMenuTool.ConvertToTask }
            )

        ThreadNewMessageMenuSecondaryToolOrder.forEach { it.dispatch(callbacks) }

        assertEquals(ThreadNewMessageMenuSecondaryToolOrder, routed)
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
    fun viewerActionsKeepTheSliceInstanceWhenUnchanged() {
        val base = slice()
        assertSame(base, base.withThreadViewerMessageActions(viewerIsGuest = false, forwardSourceAvailable = true))
    }

    private fun message(
        id: String = "message-1",
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
        showReplyActions: Boolean = true,
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
            showReplyActions = showReplyActions,
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
        showAddReactionAction: Boolean = false,
        showTranslateAction: Boolean = false,
        translatePending: Boolean = false,
        onForward: () -> Unit = {},
        onAddReaction: () -> Unit = {}
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
            showAddReactionAction = showAddReactionAction,
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
            onAddReaction = onAddReaction
        )

    private fun translationSettings(): SharedMessageTranslationSettings =
        SharedMessageTranslationSettings(
            loaded = true,
            serverId = "server-1",
            serverTranslationEnabled = true,
            providerAvailable = true,
            translationMode = TranslationModePreference.Manual,
            effectiveLanguage = "en"
        )

    private fun entry(
        status: SharedMessageTranslationStatus,
        translated: String? = null,
        original: String = "你好"
    ): SharedMessageTranslationEntry =
        SharedMessageTranslationEntry(
            messageId = "message-1",
            status = status,
            translatedContent = translated,
            originalContent = original
        )

    private fun translateState(
        message: RaftThreadMessageItem,
        entry: SharedMessageTranslationEntry?
    ): ThreadMessageMenuTranslateState =
        threadMessageMenuTranslateState(
            message = message,
            translationState =
                SharedMessageTranslationState(
                    settings = translationSettings(),
                    entries = listOfNotNull(entry).associateBy { it.messageId }
                ),
            translationAvailable = true
        )
}
