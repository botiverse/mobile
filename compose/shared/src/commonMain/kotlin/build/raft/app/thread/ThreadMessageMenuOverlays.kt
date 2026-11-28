package build.raft.app.thread

import build.raft.app.composer.ThreadComposerQuoteInsert

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextStyle
import build.raft.app.assistant.threadAssistantPanelWide
import build.raft.app.members.LocalRaftJointActorSurface
import build.raft.designsystem.theme.RaftPointerInput
import build.raft.designsystem.theme.RaftTheme
import build.raft.app.RaftAgentStatusResolver
import build.raft.app.RaftPopoverAnchor
import build.raft.app.RaftTaskStatusMenuOverlay
import build.raft.app.TaskBacklogReasonDialog
import build.raft.app.data.AgentContextViewMenuEntryState
import build.raft.app.data.RaftMessageAction
import build.raft.app.data.RaftProductEvents
import build.raft.app.data.RaftSharedStoreProvider
import build.raft.app.data.SharedMessageTranslationState
import build.raft.app.data.SharedMessageTranslationStore
import build.raft.app.data.TasksUnassignedAssigneeFilterId
import build.raft.app.data.agentContextViewMenuEntryState
import build.raft.app.data.taskStatusChoices
import build.raft.app.data.taskStatusPickAsksBacklogReason
import build.raft.app.data.tasksIdentityFilterId
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.resources.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The thread list's message menus and popovers, over the list in [ThreadMessagesListContent]: the
 * author description popover, the long-press / context menu (Compact or Classic) and the in-place
 * selection menu, the quick reaction bar, the reaction picker, the task status menu / task detail
 * dialog and the backlog reason dialog. Called where this code stood, so it composes at the same
 * place, in the same order, with the same keys.
 */
@Composable
@NonRestartableComposable
internal fun ThreadMessageMenuOverlays(
    menuState: ThreadMessageMenuState,
    renderPayload: ThreadTimelineSlice,
    linkedTasksProjection: ThreadLinkedTasksProjection,
    replyMetadataByParentId: Map<String, ThreadReplyMetadata>,
    activeReplyDraftParentMessageIds: Set<String>,
    writeActionsEnabled: Boolean,
    newMessageMenuEnabled: Boolean,
    translationStore: SharedMessageTranslationStore?,
    translationState: SharedMessageTranslationState,
    taskAssigneeOptions: List<MemberProfile>,
    agentStatusResolver: RaftAgentStatusResolver,
    adaptiveSharedStores: RaftSharedStoreProvider,
    coroutineScope: CoroutineScope,
    messageBodyTextStyle: TextStyle = TextStyle.Default,
    onThreadEvent: ThreadEventHandler,
    onThreadEventKeepingAnchor: ThreadEventHandler,
    onForwardMessage: (String) -> Unit,
    onEnterShareSelection: (String) -> Unit,
    diagnosticsLogger: (String) -> Unit,
) {
    menuState.authorDescriptionPopover?.let { request ->
        ThreadAuthorDescriptionPopover(
            request = request,
            onDismiss = { menuState.authorDescriptionPopover = null }
        )
    }
    // Product analytics: one chosen long-press menu item (`message_action_selected`, surface
    // long_press). Nothing is sent for a menu that did not come from a long press.
    fun trackLongPressMenuAction(message: RaftThreadMessageItem, action: RaftMessageAction?) {
        if (action == null || !menuState.actionMenuFromLongPress) return
        RaftProductEvents.messageActionSelected(
            action,
            threadMessageIsReplyInThread(renderPayload.replyThreadSurface, renderPayload.originMessage?.id, message.id)
        )
    }
    // New Message Menu toggle: a text selection shows this same Compact menu in place
    // (ThreadInPlaceTextSelection.kt positions it against the visible selection).
    val inPlaceSelectionMenuHost = LocalThreadInPlaceTextSelectionHost.current
    // The Compact menu's message actions, shared by the long-press menu and the in-place
    // selection menu; [close] dismisses whichever surface showed them. [track] is off for the
    // in-place selection menu, which is not the long-press menu. [anchor]: where the menu
    // was, for Add Reaction's picker (none for the selection menu: the picker is a dialog).
    fun compactMenuMessageActions(
        message: RaftThreadMessageItem,
        replyMetadata: ThreadReplyMetadata,
        authorDisplayName: String,
        close: () -> Unit,
        track: Boolean = true,
        anchor: RaftPopoverAnchor? = null
    ): ThreadNewMessageMenuMessageActions {
        val followTarget =
            resolveThreadMessageMenuFollowTarget(
                message = message,
                replyMetadata = replyMetadata,
                slice = renderPayload,
                writeActionsEnabled = writeActionsEnabled,
                originReplyMetadata = renderPayload.originMessage?.let(replyMetadataByParentId::threadReplyMetadataFor),
            )
        val followTargetParentMessageId = followTarget?.parentMessageId ?: message.id
        val followThreadChannelId =
            if (followTarget != null) {
                followTarget.threadChannelId
            } else {
                replyMetadata.threadChannelId?.takeIf { it.isNotBlank() }
            }
        val isTargetThreadFollowed = followTarget?.isFollowed ?: replyMetadata.isThreadFollowed
        fun trackTool(tool: ThreadNewMessageMenuTool) {
            if (!track) return
            trackLongPressMenuAction(
                message,
                tool.productMessageAction(
                    isSaved = message.isSaved,
                    isThreadFollowed = isTargetThreadFollowed && followThreadChannelId != null,
                    isTaskDone = message.isThreadTaskDone()
                )
            )
        }
        val quoteIntoComposer = inPlaceSelectionMenuHost?.quoteIntoComposer
        val insertQuoteIntoComposer = inPlaceSelectionMenuHost?.insertQuoteIntoComposer
        val menuPendingQuote =
            if (insertQuoteIntoComposer != null || quoteIntoComposer != null) {
                threadMessageMenuPendingQuote(message, authorDisplayName)
            } else {
                null
            }
        return ThreadNewMessageMenuMessageActions(
            onReact = { emoji ->
                close()
                if (track) trackLongPressMenuAction(message, RaftMessageAction.ReactQuick)
                onThreadEventKeepingAnchor("toggleReaction", message.id, null, emoji)
            },
            tools =
                ThreadNewMessageMenuToolCallbacks(
                    onCopy = {
                        close()
                        trackTool(ThreadNewMessageMenuTool.Copy)
                        onThreadEvent("copyMessage", message.id, null, null)
                    },
                    onReply = {
                        close()
                        trackTool(ThreadNewMessageMenuTool.Reply)
                        onThreadEvent("openMessageThread", message.id, null, null)
                    },
                    onForward = {
                        close()
                        trackTool(ThreadNewMessageMenuTool.Forward)
                        onForwardMessage(message.id)
                    },
                    onSave = {
                        close()
                        trackTool(ThreadNewMessageMenuTool.Save)
                        onThreadEventKeepingAnchor("toggleMessageSaved", message.id, null, null)
                    },
                    onTranslate = {
                        close()
                        trackTool(ThreadNewMessageMenuTool.Translate)
                        translationStore?.translateNow(message.toTranslationCandidate())
                    },
                    onSelect = {
                        close()
                        trackTool(ThreadNewMessageMenuTool.Select)
                        onEnterShareSelection(message.id)
                    },
                    onAddReaction = {
                        close()
                        trackTool(ThreadNewMessageMenuTool.AddReaction)
                        menuState.reactionPickerAnchor = anchor
                        menuState.reactionPickerMessageId = message.id
                    },
                    onCopyLink = {
                        close()
                        trackTool(ThreadNewMessageMenuTool.CopyLink)
                        onThreadEvent("copyMessageLink", message.id, null, null)
                    },
                    onFollowThread = {
                        close()
                        trackTool(ThreadNewMessageMenuTool.FollowThread)
                        if (isTargetThreadFollowed && followThreadChannelId != null) {
                            onThreadEvent(UNFOLLOW_THREAD_ACTION, followTargetParentMessageId, followThreadChannelId, null)
                        } else {
                            onThreadEvent(FOLLOW_THREAD_ACTION, followTargetParentMessageId, null, null)
                        }
                    },
                    onTaskStatus = {
                        close()
                        trackTool(ThreadNewMessageMenuTool.TaskStatus)
                        onThreadEvent(
                            if (message.isThreadTaskDone()) "reopenTask" else "markTaskDone",
                            message.taskId,
                            null,
                            null
                        )
                    },
                    onConvertToTask = {
                        close()
                        trackTool(ThreadNewMessageMenuTool.ConvertToTask)
                        onThreadEvent("convertMessageToTask", message.id, null, null)
                    },
                    onSelectText = {
                        close()
                        trackTool(ThreadNewMessageMenuTool.SelectText)
                        onThreadEvent("openSelectMessageText", message.id, null, null)
                    },
                    onMarkThreadUnread = {
                        close()
                        threadMessageMarkThreadUnreadChannelId(
                            renderPayload,
                            replyMetadata,
                            renderPayload.originMessage?.let(replyMetadataByParentId::threadReplyMetadataFor)
                        )?.let { threadChannelId ->
                                onThreadEvent(MARK_THREAD_UNREAD_ACTION, message.id, threadChannelId, null)
                            }
                    },
                    onQuote = {
                        if (menuPendingQuote != null) {
                            close()
                            trackTool(ThreadNewMessageMenuTool.Quote)
                            val insert =
                                ThreadComposerQuoteInsert(
                                    quote = menuPendingQuote,
                                    fallbackMarkdown = formatThreadMessageQuote(menuPendingQuote.excerpt),
                                )
                            if (insertQuoteIntoComposer != null) {
                                insertQuoteIntoComposer(insert)
                            } else {
                                threadMessageMenuQuote(message)?.let { quoteIntoComposer?.invoke(it) }
                            }
                        }
                    }
                )
        )
    }
    fun compactMenuAvailability(
        message: RaftThreadMessageItem,
        replyMetadata: ThreadReplyMetadata
    ): ThreadNewMessageMenuActionAvailability =
        threadMessageMenuCapabilities(
            message = message,
            replyMetadata = replyMetadata,
            slice = renderPayload,
            writeActionsEnabled = writeActionsEnabled,
            translate =
                threadMessageMenuTranslateState(
                    message = message,
                    translationState = translationState,
                    translationAvailable = translationStore != null
                ),
            originReplyMetadata = renderPayload.originMessage?.let(replyMetadataByParentId::threadReplyMetadataFor)
        ).toThreadNewMessageMenuActionAvailability(message, replyMetadata)
    if (inPlaceSelectionMenuHost?.enabled == true) {
        val selectionMenu: @Composable (ThreadInPlaceSelectionMenuRequest) -> Unit = { request ->
            renderPayload.threadActionMessage(request.messageId, linkedTasksProjection)?.let { message ->
                val replyMetadata = replyMetadataByParentId.threadReplyMetadataFor(message)
                val actions =
                    compactMenuMessageActions(
                        message = message,
                        replyMetadata = replyMetadata,
                        authorDisplayName = message.localizedAuthorName(),
                        close = request.dismiss,
                        track = false,
                    )
                ThreadNewMessageMenuChrome(
                    availability =
                        threadInPlaceSelectionMenuAvailability(
                            compactMenuAvailability(message, replyMetadata),
                            coversAll = request.coversAll()
                        ),
                    onReact = actions.onReact,
                    onCopy = request.copySelection,
                    // Reply only opens the thread; quoting goes through Select Text > Quote.
                    onReply = actions.tools.onReply,
                    onForward = actions.tools.onForward,
                    onSave = actions.tools.onSave,
                    onTranslate = actions.tools.onTranslate,
                    onSelect = actions.tools.onSelect,
                    onMore = {},
                    onAddReaction = actions.tools.onAddReaction,
                    onCopyLink = actions.tools.onCopyLink,
                    onFollowThread = actions.tools.onFollowThread,
                    onTaskStatus = actions.tools.onTaskStatus,
                    onConvertToTask = actions.tools.onConvertToTask,
                    onSelectAll = request.selectAll
                )
            }
        }
        // Stable slot reference: rows never read this, so republishing does not recompose them.
        SideEffect { inPlaceSelectionMenuHost.selectionMenu.publish(selectionMenu) }
    }
    menuState.actionMessageId?.let { messageId ->
        val resolvedActionAnchor =
            menuState.actionMenuAnchor?.takeIf { it.belongsTo(threadMessageMenuOwnerIdentity(messageId)) }
                ?: return@let
        ThreadMessageMenuKeepsComposerFocus(messageId)
        renderPayload.threadActionMessage(messageId, linkedTasksProjection)?.let { message ->
            val replyMetadata = replyMetadataByParentId.threadReplyMetadataFor(message)
            val capabilities =
                threadMessageMenuCapabilities(
                    message = message,
                    replyMetadata = replyMetadata,
                    slice = renderPayload,
                    writeActionsEnabled = writeActionsEnabled,
                    translate =
                        threadMessageMenuTranslateState(
                            message = message,
                            translationState = translationState,
                            translationAvailable = translationStore != null
                        ),
                    originReplyMetadata = renderPayload.originMessage?.let(replyMetadataByParentId::threadReplyMetadataFor)
                )
            val closeActionMenu: () -> Unit = {
                menuState.actionMessageId = null
                menuState.classicMenuOverrideMessageId = null
                inPlaceSelectionMenuHost?.contextMenuSelectionCopy = null
                inPlaceSelectionMenuHost?.contextMenuLink = null
            }
            val onJointSurface = LocalRaftJointActorSurface.current
            val supportsContextView =
                threadMessageSupportsContextView(message, onJointSurface = onJointSurface)
            val contextViewServerId =
                LocalThreadViewerServerId.current?.trim()?.takeIf { it.isNotEmpty() }?.let(::ServerId)
            val contextViewStatusStore = adaptiveSharedStores.agentContextViewStatusStore
            LaunchedEffect(supportsContextView, contextViewStatusStore, message.authorId, contextViewServerId) {
                if (supportsContextView) {
                    contextViewStatusStore.loadStatus(
                        agentId = message.authorId,
                        serverId = contextViewServerId,
                    )
                }
            }
            val contextViewStatusEntries by contextViewStatusStore.entries.collectAsState()
            val viewContextState =
                if (supportsContextView) {
                    agentContextViewMenuEntryState(
                        contextViewStatusEntries[contextViewStatusStore.cacheKey(message.authorId, contextViewServerId)]
                    )
                } else {
                    AgentContextViewMenuEntryState.Hidden
                }
            val onViewContext: () -> Unit = {
                closeActionMenu()
                onThreadEvent("openViewContext", message.id, null, null)
            }
            val onRetryViewContext: () -> Unit = {
                coroutineScope.launch {
                    contextViewStatusStore.loadStatus(
                        agentId = message.authorId,
                        serverId = contextViewServerId,
                        force = true,
                    )
                }
            }
            // A long press on a link in this message (artin, 1.24): the link's section leads the
            // menu, whichever menu shows (ThreadMessageLinkMenu.kt). Open is the link's own tap.
            val linkSection =
                inPlaceSelectionMenuHost?.contextMenuLinkFor(message.id, resolvedActionAnchor)?.let { url ->
                    rememberThreadMessageLinkMenuSection(
                        url = url,
                        webUrl = adaptiveSharedStores.apiEnvironmentStore.current().webUrl,
                        close = closeActionMenu,
                        openLink = { linkUrl ->
                            onThreadEvent("openMarkdownUrl", linkUrl, null, null)
                        },
                        // The list's scope: Share answers after the menu has closed.
                        scope = coroutineScope,
                        diagnosticsLogger = diagnosticsLogger
                    )
                }
            // A right-click over selected text in this message: "Copy" copies that text, first
            // in the menu's tools (the selection itself is kept). Not tracked: only the long-press
            // menu has a `message_action_selected` surface.
            val contextMenuCopyHost = inPlaceSelectionMenuHost
            val contextMenuCopyText = contextMenuCopyHost?.contextMenuCopyFor(message.id, resolvedActionAnchor)
            val onCopySelection: (() -> Unit)? =
                if (contextMenuCopyHost != null && contextMenuCopyText != null) {
                    {
                        closeActionMenu()
                        diagnosticsLogger(
                            "thread_message_context_menu_copy_selection messageId=${message.id} " +
                                "chars=${contextMenuCopyText.length}"
                        )
                        contextMenuCopyHost.copyText(contextMenuCopyText)
                    }
                } else {
                    null
                }
            val onAddReaction: () -> Unit = {
                closeActionMenu()
                trackLongPressMenuAction(message, RaftMessageAction.OpenReactionPicker)
                menuState.reactionPickerAnchor = resolvedActionAnchor
                menuState.reactionPickerMessageId = message.id
            }
            val onTranslate: () -> Unit = {
                closeActionMenu()
                trackLongPressMenuAction(message, RaftMessageAction.Translate)
                translationStore?.translateNow(message.toTranslationCandidate())
            }
            val followTargetParentMessageId = capabilities.followTarget?.parentMessageId ?: message.id
            val onFollowThread: () -> Unit = {
                closeActionMenu()
                trackLongPressMenuAction(message, RaftMessageAction.FollowThread)
                onThreadEvent(FOLLOW_THREAD_ACTION, followTargetParentMessageId, null, null)
            }
            val onUnfollowThread: (String) -> Unit = { threadChannelId ->
                closeActionMenu()
                trackLongPressMenuAction(message, RaftMessageAction.UnfollowThread)
                onThreadEvent(UNFOLLOW_THREAD_ACTION, followTargetParentMessageId, threadChannelId, null)
            }
            val onTaskStatusChange: (String) -> Unit = { action ->
                closeActionMenu()
                trackLongPressMenuAction(message, threadTaskStatusEventProductAction(action))
                onThreadEvent(action, message.taskId, null, null)
            }
            val onConvertToTask: () -> Unit = {
                closeActionMenu()
                trackLongPressMenuAction(message, RaftMessageAction.CreateTask)
                onThreadEvent("convertMessageToTask", message.id, null, null)
            }
            val variant =
                if (menuState.classicMenuOverrideMessageId == message.id) {
                    ThreadMessageMenuVariant.Classic
                } else {
                    threadMessageMenuVariant(
                        newMessageMenuEnabled = newMessageMenuEnabled,
                        hasLocalSendState = message.hasLocalSendState()
                    )
                }
            val useBottomSheet =
                threadMessageUsesBottomSheet(
                    wide = threadAssistantPanelWide(),
                    coarsePointer = RaftTheme.pointerInput == RaftPointerInput.Touch,
                    fromLongPress = menuState.actionMenuFromLongPress,
                )
            val showReplyAction =
                threadMessageMenuShowsReplyInThread(
                    capabilities = capabilities,
                    message = message,
                    replyMetadata = replyMetadata,
                    slice = renderPayload,
                    hasReplyDraft = activeReplyDraftParentMessageIds.hasReplyComposerDraft(message.id)
                )
            val downloadVideoHandler =
                threadMessageMenuDownloadVideoAttachmentId(message)?.let { attachmentId ->
                    {
                        closeActionMenu()
                        onThreadEvent("downloadAttachment", null, attachmentId, null)
                    }
                }
            if (useBottomSheet) {
                ThreadMessageActionSheet(
                    message = message,
                    replyMetadata = replyMetadata,
                    messageBodyTextStyle = messageBodyTextStyle,
                    showReplyAction = showReplyAction,
                    showSaveAction = capabilities.save,
                    showFollowAction = capabilities.follow,
                    showTaskStatusAction = capabilities.taskStatus,
                    showConvertToTaskAction = capabilities.convertToTask,
                    createTaskFromMessage = capabilities.createTaskFromMessage,
                    showReactionActions = capabilities.quickReactions,
                    showCopyLinkAction = capabilities.copyLink,
                    showShareMessagesAction = capabilities.selectMessage,
                    showCancelAction = capabilities.cancelLocalSend,
                    showDeleteAction = capabilities.deleteLocalSend,
                    showTranslateAction = capabilities.translate != ThreadMessageMenuTranslateState.Hidden,
                    translatePending = capabilities.translate == ThreadMessageMenuTranslateState.Pending,
                    showForwardAction = capabilities.forward,
                    showAddReactionAction = capabilities.addReaction,
                    showMarkThreadUnreadAction = capabilities.markThreadUnreadChannelId != null,
                    onDismiss = closeActionMenu,
                    onCopyLink = {
                        closeActionMenu()
                        trackLongPressMenuAction(message, RaftMessageAction.CopyLink)
                        onThreadEvent("copyMessageLink", message.id, null, null)
                    },
                    onReply = {
                        closeActionMenu()
                        trackLongPressMenuAction(message, RaftMessageAction.ReplyInThread)
                        onThreadEvent("openMessageThread", message.id, null, null)
                    },
                    onCopy = {
                        closeActionMenu()
                        trackLongPressMenuAction(message, RaftMessageAction.CopyMarkdown)
                        onThreadEvent("copyMessage", message.id, null, null)
                    },
                    onCopyCustomText = { text ->
                        inPlaceSelectionMenuHost?.copyTextWithToast?.invoke(text)
                            ?: onThreadEvent("copyMessage", message.id, null, null)
                    },
                    onShareMessages = {
                        closeActionMenu()
                        trackLongPressMenuAction(message, RaftMessageAction.Select)
                        onEnterShareSelection(message.id)
                    },
                    onSave = {
                        closeActionMenu()
                        trackLongPressMenuAction(
                            message,
                            if (message.isSaved) RaftMessageAction.Unsave else RaftMessageAction.Save
                        )
                        onThreadEventKeepingAnchor("toggleMessageSaved", message.id, null, null)
                    },
                    onFollowThread = onFollowThread,
                    onUnfollowThread = onUnfollowThread,
                    onReact = { emoji ->
                        closeActionMenu()
                        trackLongPressMenuAction(message, RaftMessageAction.ReactQuick)
                        onThreadEventKeepingAnchor("toggleReaction", message.id, null, emoji)
                    },
                    onTaskStatusChange = onTaskStatusChange,
                    onConvertToTask = onConvertToTask,
                    onCancel = {
                        closeActionMenu()
                        onThreadEvent("cancelLocalSend", message.id, null, null)
                    },
                    onDelete = {
                        closeActionMenu()
                        onThreadEvent("deleteLocalFailedSend", message.id, null, null)
                    },
                    onTranslate = onTranslate,
                    onForward = {
                        closeActionMenu()
                        trackLongPressMenuAction(message, RaftMessageAction.Forward)
                        onForwardMessage(message.id)
                    },
                    onMarkThreadUnread = {
                        closeActionMenu()
                        capabilities.markThreadUnreadChannelId?.let { threadChannelId ->
                            onThreadEvent(MARK_THREAD_UNREAD_ACTION, message.id, threadChannelId, null)
                        }
                    },
                    onCopySelection = onCopySelection,
                    onDownloadVideo = downloadVideoHandler,
                    onQuoteChosen = { trackLongPressMenuAction(message, RaftMessageAction.Quote) },
                    onSelectTextOpened = { trackLongPressMenuAction(message, RaftMessageAction.SelectText) },
                    onEmojiPickerOpened = { trackLongPressMenuAction(message, RaftMessageAction.OpenReactionPicker) },
                    link = linkSection,
                    viewContextState = viewContextState,
                    onViewContext = onViewContext,
                    onRetryViewContext = onRetryViewContext,
                    followTarget = capabilities.followTarget,
                )
            } else if (variant == ThreadMessageMenuVariant.Compact) {
                val authorDisplayName = message.localizedAuthorName()
                val actions =
                    compactMenuMessageActions(
                        message = message,
                        replyMetadata = replyMetadata,
                        authorDisplayName = authorDisplayName,
                        close = closeActionMenu,
                        anchor = resolvedActionAnchor,
                    )
                val compactCanQuote =
                    capabilities.copyText &&
                        (inPlaceSelectionMenuHost?.insertQuoteIntoComposer != null ||
                            inPlaceSelectionMenuHost?.quoteIntoComposer != null) &&
                        threadMessageMenuPendingQuote(message, authorDisplayName) != null
                ThreadNewMessageMenu(
                    anchor = resolvedActionAnchor,
                    availability =
                        capabilities.toThreadNewMessageMenuActionAvailability(message, replyMetadata)
                            .copy(
                                copySelection = onCopySelection != null && capabilities.copyText,
                                quote = compactCanQuote,
                            ),
                    onDismiss = closeActionMenu,
                    onReact = actions.onReact,
                    onCopy = actions.tools.onCopy,
                    onReply = actions.tools.onReply,
                    onForward = actions.tools.onForward,
                    onSave = actions.tools.onSave,
                    onTranslate = actions.tools.onTranslate,
                    onSelect = actions.tools.onSelect,
                    onMore = {
                        menuState.classicMenuOverrideMessageId = message.id
                        diagnosticsLogger("thread_message_menu_compact_more messageId=${message.id}")
                    },
                    onAddReaction = actions.tools.onAddReaction,
                    onCopyLink = actions.tools.onCopyLink,
                    onFollowThread = actions.tools.onFollowThread,
                    onTaskStatus = actions.tools.onTaskStatus,
                    onConvertToTask = actions.tools.onConvertToTask,
                    onSelectText = actions.tools.onSelectText,
                    onMarkThreadUnread = actions.tools.onMarkThreadUnread,
                    onCopySelection = onCopySelection ?: {},
                    onQuote = actions.tools.onQuote,
                    link = linkSection,
                    viewContextState = viewContextState,
                    onViewContext = onViewContext,
                    onRetryViewContext = onRetryViewContext,
                )
            } else {
                ThreadMessageActionMenu(
                    message = message,
                    replyMetadata = replyMetadata,
                    anchor = resolvedActionAnchor,
                    // botiverse/slock #15524: not once the row shows its thread (its replies open it).
                    showReplyAction =
                        threadMessageMenuShowsReplyInThread(
                            capabilities = capabilities,
                            message = message,
                            replyMetadata = replyMetadata,
                            slice = renderPayload,
                            hasReplyDraft = activeReplyDraftParentMessageIds.hasReplyComposerDraft(message.id)
                        ),
                    showSaveAction = capabilities.save,
                    showFollowAction = capabilities.follow,
                    showTaskStatusAction = capabilities.taskStatus,
                    showConvertToTaskAction = capabilities.convertToTask,
                    createTaskFromMessage = capabilities.createTaskFromMessage,
                    showReactionActions = capabilities.quickReactions,
                    showCopyLinkAction = capabilities.copyLink,
                    showShareMessagesAction = capabilities.selectMessage,
                    showCancelAction = capabilities.cancelLocalSend,
                    showDeleteAction = capabilities.deleteLocalSend,
                    showTranslateAction = capabilities.translate != ThreadMessageMenuTranslateState.Hidden,
                    translatePending = capabilities.translate == ThreadMessageMenuTranslateState.Pending,
                    showForwardAction = capabilities.forward,
                    showAddReactionAction = capabilities.addReaction,
                    onDismiss = closeActionMenu,
                    onCopyLink = {
                        closeActionMenu()
                        trackLongPressMenuAction(message, RaftMessageAction.CopyLink)
                        onThreadEvent("copyMessageLink", message.id, null, null)
                    },
                    onReply = {
                        closeActionMenu()
                        trackLongPressMenuAction(message, RaftMessageAction.ReplyInThread)
                        onThreadEvent("openMessageThread", message.id, null, null)
                    },
                    onCopy = {
                        closeActionMenu()
                        trackLongPressMenuAction(message, RaftMessageAction.CopyMarkdown)
                        onThreadEvent("copyMessage", message.id, null, null)
                    },
                    onSelectText = {
                        closeActionMenu()
                        trackLongPressMenuAction(message, RaftMessageAction.SelectText)
                        onThreadEvent("openSelectMessageText", message.id, null, null)
                    },
                    onShareMessages = {
                        closeActionMenu()
                        trackLongPressMenuAction(message, RaftMessageAction.Select)
                        onEnterShareSelection(message.id)
                    },
                    onSave = {
                        closeActionMenu()
                        trackLongPressMenuAction(
                            message,
                            if (message.isSaved) RaftMessageAction.Unsave else RaftMessageAction.Save
                        )
                        onThreadEventKeepingAnchor("toggleMessageSaved", message.id, null, null)
                    },
                    onFollowThread = onFollowThread,
                    onUnfollowThread = onUnfollowThread,
                    onReact = { emoji ->
                        closeActionMenu()
                        trackLongPressMenuAction(message, RaftMessageAction.ReactQuick)
                        onThreadEventKeepingAnchor("toggleReaction", message.id, null, emoji)
                    },
                    onTaskStatusChange = onTaskStatusChange,
                    onConvertToTask = onConvertToTask,
                    onCancel = {
                        closeActionMenu()
                        onThreadEvent("cancelLocalSend", message.id, null, null)
                    },
                    onDelete = {
                        closeActionMenu()
                        onThreadEvent("deleteLocalFailedSend", message.id, null, null)
                    },
                    onTranslate = onTranslate,
                    onForward = {
                        closeActionMenu()
                        trackLongPressMenuAction(message, RaftMessageAction.Forward)
                        onForwardMessage(message.id)
                    },
                    onAddReaction = onAddReaction,
                    showMarkThreadUnreadAction = capabilities.markThreadUnreadChannelId != null,
                    onMarkThreadUnread = {
                        closeActionMenu()
                        capabilities.markThreadUnreadChannelId?.let { threadChannelId ->
                            onThreadEvent(MARK_THREAD_UNREAD_ACTION, message.id, threadChannelId, null)
                        }
                    },
                    onCopySelection = onCopySelection,
                    onDownloadVideo =
                        threadMessageMenuDownloadVideoAttachmentId(message)?.let { attachmentId ->
                            {
                                closeActionMenu()
                                onThreadEvent("downloadAttachment", null, attachmentId, null)
                            }
                        },
                    // botiverse/slock #16252: web MessageItem counts its menu's Quote too.
                    onQuoteChosen = { trackLongPressMenuAction(message, RaftMessageAction.Quote) },
                    link = linkSection,
                    viewContextState = viewContextState,
                    onViewContext = onViewContext,
                    onRetryViewContext = onRetryViewContext,
                    followTarget = capabilities.followTarget,
                    diagnosticsLogger = diagnosticsLogger
                )
            }
        }
    }
    menuState.quickReactionBar?.let { request ->
        val closeQuickReactionBar: () -> Unit = { menuState.quickReactionBar = null }
        val message =
            request.anchor.takeIf { it.belongsTo(threadMessageMenuOwnerIdentity(request.messageId)) }
                ?.let { renderPayload.threadActionMessage(request.messageId, linkedTasksProjection) }
                ?.takeIf { threadMessageCanQuickReact(it, renderPayload, writeActionsEnabled) }
        if (message == null) {
            // The message went away or can no longer be reacted to: drop the stale bar.
            SideEffect { closeQuickReactionBar() }
            return@let
        }
        ThreadMessageQuickReactionBar(
            messageId = message.id,
            anchor = request.anchor,
            fromHoverToolbar = request.fromHoverToolbar,
            onDismiss = closeQuickReactionBar,
            onReact = { emoji ->
                closeQuickReactionBar()
                diagnosticsLogger("thread_message_double_tap_react messageId=${message.id} emoji=$emoji")
                onThreadEventKeepingAnchor("toggleReaction", message.id, null, emoji)
            },
            onAddReaction =
                if (request.fromHoverToolbar) {
                    {
                        closeQuickReactionBar()
                        menuState.reactionPickerAnchor = request.anchor
                        menuState.reactionPickerMessageId = message.id
                    }
                } else {
                    null
                },
            diagnosticsLogger = diagnosticsLogger
        )
    }
    menuState.reactionPickerMessageId?.let { messageId ->
        val closeReactionPicker: () -> Unit = {
            menuState.reactionPickerMessageId = null
            menuState.reactionPickerAnchor = null
        }
        val onPickReaction: (String) -> Unit = { emoji ->
            closeReactionPicker()
            onThreadEventKeepingAnchor("toggleReaction", messageId, null, emoji)
        }
        // Read here: the shell host and the dialog host compose the picker outside this page.
        val emojiPickerPreferences = rememberRaftEmojiPickerPreferences()
        val windowWidthDp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp().value }
        val pickerAnchor = menuState.reactionPickerAnchor
        if (raftEmojiPickerUsesPopover(windowWidthDp, hasAnchor = pickerAnchor != null) && pickerAnchor != null) {
            ThreadMessageEmojiPickerPopover(
                messageId = messageId,
                anchor = pickerAnchor,
                preferences = emojiPickerPreferences,
                onDismiss = closeReactionPicker,
                onSelect = onPickReaction,
                diagnosticsLogger = diagnosticsLogger
            )
        } else {
            RaftEmojiPickerDialog(
                title = stringResource(Res.string.thread_action_add_reaction),
                onDismiss = closeReactionPicker,
                onSelect = onPickReaction,
                preferences = emojiPickerPreferences
            )
        }
    }
    menuState.taskStatusMenuMessageId?.let { messageId ->
        val resolvedTaskStatusAnchor =
            menuState.taskStatusMenuAnchor?.takeIf { it.belongsTo(threadMessageMenuOwnerIdentity(messageId)) }
                ?: return@let
        renderPayload.threadActionMessage(messageId, linkedTasksProjection)?.let { message ->
            val selectedStatus = message.taskStatus.threadTaskStatusOption()
            // Task #258 (artin msg=bd6a3a0b): the inline task chip opens the
            // combined task detail panel — description + status + assignee —
            // instead of the status-only menu. Fail closed to the legacy status
            // menu when the linked task summary (title/description) isn't loaded.
            val linkedTask = message.taskId?.let { linkedTasksProjection.taskById(it) }
            if (linkedTask == null || threadTaskDetailPanelUseLegacy(linkedTask)) {
                RaftTaskStatusMenuOverlay(
                    anchor = resolvedTaskStatusAnchor,
                    // A task on a message is never a legacy orphan: it opens as its own
                    // conversation, so In Review is retired (web `opensAsTaskConversation`).
                    items =
                        threadTaskStatusMenuItems(
                            taskStatusChoices(
                                selectedStatus.status,
                                v2Task = true
                            )
                        ),
                    selectedId = selectedStatus.key,
                    onDismiss = { menuState.taskStatusMenuMessageId = null },
                    onSelect = { selectedId ->
                        menuState.taskStatusMenuMessageId = null
                        val taskId = message.taskId?.takeIf { it.isNotBlank() }
                        val status = selectedId.toSharedThreadTaskStatus()
                        if (taskId != null && status != null && status.name != selectedStatus.key) {
                            if (taskStatusPickAsksBacklogReason(selectedStatus.status, status)) {
                                menuState.taskBacklogReasonTarget = ThreadTaskBacklogReasonTarget(taskId, message.taskNumber)
                            } else {
                                onThreadEvent("setTaskStatus", taskId, null, selectedId)
                            }
                        }
                    }
                )
            } else {
                ThreadTaskDetailDialog(
                    task = linkedTask,
                    assigneeOptions = taskAssigneeOptions,
                    agentStatusResolver = agentStatusResolver,
                    onDismiss = { menuState.taskStatusMenuMessageId = null },
                    onStatusSelect = { selectedId ->
                        menuState.taskStatusMenuMessageId = null
                        val taskId = linkedTask.id.value
                        val status = selectedId.toSharedThreadTaskStatus()
                        if (taskId.isNotBlank() && status != null && status.name != selectedStatus.key) {
                            if (taskStatusPickAsksBacklogReason(linkedTask.status, status)) {
                                menuState.taskBacklogReasonTarget = ThreadTaskBacklogReasonTarget(taskId, linkedTask.taskNumber ?: message.taskNumber)
                            } else {
                                onThreadEvent("setTaskStatus", taskId, null, selectedId)
                            }
                        }
                    },
                    onAssigneeSelect = { assignee ->
                        menuState.taskStatusMenuMessageId = null
                        val taskId = linkedTask.id.value
                        if (taskId.isNotBlank()) {
                            onThreadEvent(
                                "setTaskAssignee",
                                taskId,
                                null,
                                assignee?.tasksIdentityFilterId() ?: TasksUnassignedAssigneeFilterId
                            )
                        }
                    }
                )
            }
        }
    }
    menuState.taskBacklogReasonTarget?.let { target ->
        TaskBacklogReasonDialog(
            taskNumber = target.taskNumber,
            onDismiss = { menuState.taskBacklogReasonTarget = null },
            onConfirm = { reason ->
                menuState.taskBacklogReasonTarget = null
                // The reason rides in `tab` ("" for none), as setTaskAssignee's member id does.
                onThreadEvent("moveTaskToBacklog", target.taskId, null, reason.orEmpty())
            }
        )
    }
}
