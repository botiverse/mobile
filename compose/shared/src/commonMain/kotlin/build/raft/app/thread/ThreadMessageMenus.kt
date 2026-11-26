package build.raft.app

import build.raft.designsystem.components.RaftConversationKindGlyph
import build.raft.app.resources.*
import build.raft.app.emoji.RaftEmojiCatalog
import build.raft.app.emoji.RaftEmojiPickerPreferences
import build.raft.designsystem.theme.RaftTheme
import build.raft.app.components.RaftSlotIcon
import build.raft.designsystem.components.RaftGlyph
import build.raft.app.RAFT_ADAPTIVE_DETAIL_IDENTITY_KEY
import build.raft.app.data.RAFT_SHELL_OVERLAY_REQUEST_EVENT
import build.raft.app.data.RAFT_SHELL_OVERLAY_RESULT_EVENT
import build.raft.app.data.raftShouldApplyShellOverlayResult
import build.raft.app.data.RaftShellOverlayRequest
import build.raft.app.data.RaftShellOverlayItem
import build.raft.app.data.cancelRaftShellOverlayJson
import build.raft.app.components.raftLookOverlaySurface
import build.raft.app.base.Utils
import build.raft.app.data.RaftEventBus
import build.raft.app.data.map
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.model.TaskStatus
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import build.raft.designsystem.components.RaftConversationKindIcon
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftMessageItemToolbar
import build.raft.designsystem.components.RaftMessageItemToolbarButton
import build.raft.designsystem.components.RaftMessageItemToolbarDefaults
import build.raft.designsystem.components.raftPointerHoverPosition
import build.raft.designsystem.theme.RaftPointerInput
import androidx.compose.ui.input.pointer.pointerInput
import build.raft.app.compat.pager.LocalActivity
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.app.compat.pager.Pager
import org.jetbrains.compose.resources.StringResource
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.channels.Channel
import build.raft.designsystem.components.raftClickable

internal val ThreadNewMessageMenuWidth = 328.dp
internal val ThreadNewMessageMenuColumnHorizontalPadding = 8.dp
private val ThreadNewMessageMenuReactionRowHeight = 44.dp
internal val ThreadNewMessageMenuReactionButtonSize = 32.dp
private val ThreadNewMessageMenuReactionGlyphSize = 22.dp
internal val ThreadNewMessageMenuReactionHorizontalPadding = 4.dp
private val ThreadNewMessageMenuToolRowHeight = 52.dp
private val ThreadNewMessageMenuToolIconSize = 18.dp
private val ThreadNewMessageMenuMoreOverlayHeight = 20.dp
private val ThreadNewMessageMenuVerticalPadding = 6.dp
private val ThreadNewMessageMenuAnchorGap = 8.dp
private val ThreadNewMessageMenuHorizontalNudge = 16.dp

/**
 * artin 2026-11-03: a message's menu opened with the keyboard up keeps the keyboard (WeChat,
 * Telegram). The press that opened it started outside the composer, so the page's outside-gesture
 * dismissal ([RaftInputFocusDismissHost]) armed on its down and would clear the composer's focus at
 * its release; opening the menu cancels that pending dismissal. Composed in the page, where the
 * composer's registry is, not in the menu's own window.
 */
@Composable
internal fun ThreadMessageMenuKeepsComposerFocus(messageId: String) {
    val focusOwnerRegistry = LocalRaftInputFocusOwnerRegistry.current
    LaunchedEffect(focusOwnerRegistry, messageId) {
        focusOwnerRegistry?.cancelPendingFocusDismissalForExplicitInputIntent()
    }
}

@Composable
internal fun ThreadNewMessageMenu(
    anchor: RaftPopoverAnchor,
    availability: ThreadNewMessageMenuActionAvailability,
    onDismiss: () -> Unit,
    onReact: (String) -> Unit,
    onCopy: () -> Unit,
    onReply: () -> Unit,
    onForward: () -> Unit,
    onSave: () -> Unit,
    onTranslate: () -> Unit,
    onSelect: () -> Unit,
    onMore: () -> Unit,
    onAddReaction: () -> Unit = {},
    onCopyLink: () -> Unit = {},
    onFollowThread: () -> Unit = {},
    onTaskStatus: () -> Unit = {},
    onConvertToTask: () -> Unit = {},
    onSelectText: () -> Unit = {},
    onMarkThreadUnread: () -> Unit = {},
    onCopySelection: () -> Unit = {},
    /** A long press on a link opened the menu: that link's section on top (ThreadMessageLinkMenu.kt). */
    link: ThreadMessageLinkMenuSection? = null
) {
    val density = LocalDensity.current
    val secondaryRowCount = if (availability.secondaryTools().isEmpty()) 0 else 1
    val linkSectionHeight =
        if (link == null) 0.dp else ThreadMessageLinkMenuUrlRowHeight + ThreadNewMessageMenuToolRowHeight + RaftAppStroke.Thin
    val contentSize =
        with(density) {
            Size(
                width = ThreadNewMessageMenuWidth.toPx(),
                height =
                    (
                        ThreadNewMessageMenuReactionRowHeight +
                            RaftAppStroke.Thin +
                            ThreadNewMessageMenuToolRowHeight * (1 + secondaryRowCount) +
                            ThreadNewMessageMenuMoreOverlayHeight +
                            ThreadNewMessageMenuVerticalPadding * 2 +
                            linkSectionHeight
                    ).toPx()
            )
        }
    val anchorGapPx = with(density) { ThreadNewMessageMenuAnchorGap.toPx() }
    val horizontalNudgePx = with(density) { ThreadNewMessageMenuHorizontalNudge.toPx() }
    RaftModalAnchoredPopover(
        anchor = anchor,
        contentSize = contentSize,
        onDismiss = onDismiss,
        viewportGap = anchorGapPx,
        horizontalOffset = -horizontalNudgePx,
        verticalOffset = anchorGapPx,
        horizontalAutoFlip = true,
        verticalPlacement = RaftFloatingVerticalPlacement.Auto,
        keepsHostKeyboard = true
    ) {
        ThreadNewMessageMenuChrome(
            availability = availability,
            onReact = onReact,
            onCopy = onCopy,
            onReply = onReply,
            onForward = onForward,
            onSave = onSave,
            onTranslate = onTranslate,
            onSelect = onSelect,
            onMore = onMore,
            onAddReaction = onAddReaction,
            onCopyLink = onCopyLink,
            onFollowThread = onFollowThread,
            onTaskStatus = onTaskStatus,
            onConvertToTask = onConvertToTask,
            onSelectText = onSelectText,
            onMarkThreadUnread = onMarkThreadUnread,
            onCopySelection = onCopySelection,
            link = link
        )
    }
}

@Composable
internal fun ThreadNewMessageMenuChrome(
    availability: ThreadNewMessageMenuActionAvailability,
    onReact: (String) -> Unit,
    onCopy: () -> Unit,
    onReply: () -> Unit,
    onForward: () -> Unit,
    onSave: () -> Unit,
    onTranslate: () -> Unit,
    onSelect: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    onAddReaction: () -> Unit = {},
    onCopyLink: () -> Unit = {},
    onFollowThread: () -> Unit = {},
    onTaskStatus: () -> Unit = {},
    onConvertToTask: () -> Unit = {},
    onSelectText: () -> Unit = {},
    onSelectAll: () -> Unit = {},
    onMarkThreadUnread: () -> Unit = {},
    onCopySelection: () -> Unit = {},
    /** A long press on a link opened the menu: that link's section on top (ThreadMessageLinkMenu.kt). */
    link: ThreadMessageLinkMenuSection? = null
) {
    val callbacks =
        ThreadNewMessageMenuToolCallbacks(
            onCopy = onCopy,
            onReply = onReply,
            onForward = onForward,
            onSave = onSave,
            onTranslate = onTranslate,
            onSelect = onSelect,
            onAddReaction = onAddReaction,
            onCopyLink = onCopyLink,
            onFollowThread = onFollowThread,
            onTaskStatus = onTaskStatus,
            onConvertToTask = onConvertToTask,
            onSelectText = onSelectText,
            onSelectAll = onSelectAll,
            onMarkThreadUnread = onMarkThreadUnread,
            onCopySelection = onCopySelection
        )
    // The link section's "Copy link" sits above: the message's own reads "Copy Message Link".
    val menuAvailability = if (link != null) availability.copy(linkSection = true) else availability
    val secondaryTools = menuAvailability.secondaryTools()
    // raft-ui overlay: popover layer, seam ring and the float shadow (no offset box).
    Box(modifier = modifier) {
        Column(
            modifier =
                Modifier
                    .width(ThreadNewMessageMenuWidth)
                    .raftLookOverlaySurface()
                    .padding(horizontal = ThreadNewMessageMenuColumnHorizontalPadding, vertical = ThreadNewMessageMenuVerticalPadding)
        ) {
            if (link != null) ThreadNewMessageMenuLinkSection(link)
            if (menuAvailability.showsReactionRow()) {
                ThreadMessageReactionRow(
                    reactionAtlasImage = LocalThreadReactionAtlasImage.current,
                    onReact = onReact,
                    rowHeight = ThreadNewMessageMenuReactionRowHeight,
                    buttonSize = ThreadNewMessageMenuReactionButtonSize,
                    glyphSize = ThreadNewMessageMenuReactionGlyphSize,
                    horizontalPadding = ThreadNewMessageMenuReactionHorizontalPadding,
                    enabled = menuAvailability.reactions,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    // Nine 32dp slots (8 quick reactions + Add Reaction) over the 304dp inner width:
                    // 9 * 32 = 288dp, leaving 2dp per gap with SpaceBetween.
                    onAddReaction = if (menuAvailability.addReaction) callbacks.onAddReaction else null
                )
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(RaftAppStroke.Thin)
                            .background(RaftColor.BorderHairline)
                )
            }
            ThreadNewMessageMenuToolRow(
                tools = menuAvailability.primaryTools(),
                callbacks = callbacks,
                availability = menuAvailability
            )
            if (secondaryTools.isNotEmpty()) {
                ThreadNewMessageMenuToolRow(
                    tools = secondaryTools,
                    callbacks = callbacks,
                    availability = menuAvailability
                )
            }
            // A selection menu has no "More": its message actions are all on the two rows.
            if (!menuAvailability.selectionContext) {
                ThreadNewMessageMenuMoreOverlay(
                    enabled = menuAvailability.more,
                    onClick = onMore
                )
            }
        }
    }
}

/**
 * The message's own Copy Link label. With a link's section on top (a long press on a link) that
 * section has its own "Copy link", so the message's reads "Copy Message Link" (artin, 1.25); a
 * plain long press keeps "Copy Link".
 */
internal fun threadMessageCopyLinkLabel(linkSection: Boolean): StringResource =
    if (linkSection) Res.string.thread_action_copy_message_link else Res.string.thread_action_copy_link

@Composable
private fun ThreadNewMessageMenuTool.compactLabel(availability: ThreadNewMessageMenuActionAvailability): String =
    stringResource(compactLabelResource(availability))

/** The string key each Compact tool shows for [availability] (pure, so the per-state model is testable). */
internal fun ThreadNewMessageMenuTool.compactLabelResource(
    availability: ThreadNewMessageMenuActionAvailability
): StringResource =
    when (this) {
        ThreadNewMessageMenuTool.Copy ->
            if (availability.selectionContext && availability.partialSelection) {
                Res.string.thread_text_selection_copy_selection
            } else {
                Res.string.thread_action_copy_markdown
            }
        ThreadNewMessageMenuTool.Reply -> Res.string.thread_new_message_menu_reply
        ThreadNewMessageMenuTool.Forward -> Res.string.thread_action_forward
        ThreadNewMessageMenuTool.Save ->
            if (availability.saved) {
                Res.string.thread_action_remove_from_saved
            } else {
                Res.string.thread_action_save_message
            }
        ThreadNewMessageMenuTool.Translate ->
            if (availability.translatePending) {
                Res.string.translation_state_in_progress
            } else {
                Res.string.thread_action_translate
            }
        ThreadNewMessageMenuTool.Select -> Res.string.thread_action_select_message
        ThreadNewMessageMenuTool.AddReaction -> Res.string.thread_action_add_reaction
        ThreadNewMessageMenuTool.CopyLink -> threadMessageCopyLinkLabel(linkSection = availability.linkSection)
        ThreadNewMessageMenuTool.FollowThread ->
            if (availability.threadFollowed) {
                Res.string.thread_action_unfollow_thread
            } else {
                Res.string.thread_action_follow_thread
            }
        ThreadNewMessageMenuTool.TaskStatus ->
            if (availability.taskDone) {
                Res.string.thread_action_reopen_task
            } else {
                Res.string.thread_action_mark_done
            }
        ThreadNewMessageMenuTool.ConvertToTask ->
            if (availability.createTaskFromMessage) {
                Res.string.thread_action_create_task_from_message
            } else {
                Res.string.thread_action_convert_to_task
            }
        ThreadNewMessageMenuTool.SelectText -> Res.string.thread_action_select_text
        ThreadNewMessageMenuTool.SelectAll -> Res.string.thread_text_selection_select_all
        ThreadNewMessageMenuTool.MarkThreadUnread -> Res.string.thread_action_mark_thread_unread
        ThreadNewMessageMenuTool.CopySelection -> Res.string.common_copy
    }

internal fun ThreadNewMessageMenuTool.compactIcon(
    availability: ThreadNewMessageMenuActionAvailability
): RaftLucideIconName =
    when (this) {
        ThreadNewMessageMenuTool.Copy -> RaftLucideIconName.Copy
        ThreadNewMessageMenuTool.Reply -> RaftLucideIconName.Thread
        ThreadNewMessageMenuTool.Forward -> RaftLucideIconName.Forward
        ThreadNewMessageMenuTool.Save ->
            if (availability.saved) RaftLucideIconName.BookmarkMinus else RaftLucideIconName.Bookmark
        ThreadNewMessageMenuTool.Translate -> RaftLucideIconName.Languages
        ThreadNewMessageMenuTool.Select -> RaftLucideIconName.ListChecks
        ThreadNewMessageMenuTool.AddReaction -> RaftLucideIconName.SmilePlus
        ThreadNewMessageMenuTool.CopyLink -> RaftLucideIconName.Link
        ThreadNewMessageMenuTool.FollowThread -> threadFollowMenuIcon(availability.threadFollowed)
        ThreadNewMessageMenuTool.TaskStatus -> threadTaskStatusMenuIcon(availability.taskDone)
        ThreadNewMessageMenuTool.ConvertToTask -> RaftLucideIconName.Plus
        ThreadNewMessageMenuTool.SelectText -> RaftLucideIconName.SquareDashedText
        // Not SquareCheckBig: that is Mark as Done in the same menu (web MessageItem). Select All
        // only shows over a partial selection, where Select Text is hidden.
        ThreadNewMessageMenuTool.SelectAll -> RaftLucideIconName.SquareDashedText
        ThreadNewMessageMenuTool.MarkThreadUnread -> RaftLucideIconName.MessageSquareDot
        // Not Copy: that is Copy Markdown beside it in the same menu (web's icon for it).
        ThreadNewMessageMenuTool.CopySelection -> RaftLucideIconName.Type
    }

@Composable
private fun ThreadNewMessageMenuToolRow(
    tools: List<ThreadNewMessageMenuTool>,
    callbacks: ThreadNewMessageMenuToolCallbacks,
    availability: ThreadNewMessageMenuActionAvailability
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(ThreadNewMessageMenuToolRowHeight),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tools.forEach { tool ->
            ThreadNewMessageMenuToolButton(
                label = tool.compactLabel(availability),
                icon = tool.compactIcon(availability),
                enabled = availability.enabled(tool),
                onClick = { tool.dispatch(callbacks) },
                modifier = Modifier.weight(1f)
            )
        }
        // A shorter second row keeps the primary row's slot width so its columns line up.
        repeat((ThreadNewMessageMenuToolOrder.size - tools.size).coerceAtLeast(0)) {
            Spacer(Modifier.weight(1f))
        }
    }
}

/**
 * The Compact menu's link section (a long press on a link): the address, its actions as tool
 * buttons in the tool rows' columns, and a hairline under them.
 */
@Composable
private fun ThreadNewMessageMenuLinkSection(section: ThreadMessageLinkMenuSection) {
    ThreadMessageLinkMenuUrl(section.url)
    Row(
        modifier = Modifier.fillMaxWidth().height(ThreadNewMessageMenuToolRowHeight),
        verticalAlignment = Alignment.CenterVertically
    ) {
        section.items.forEach { item ->
            ThreadNewMessageMenuToolButton(
                label = stringResource(item.action.label()),
                icon = item.action.icon(),
                enabled = true,
                onClick = item.onClick,
                modifier = Modifier.weight(1f)
            )
        }
        repeat((ThreadNewMessageMenuToolOrder.size - section.items.size).coerceAtLeast(0)) {
            Spacer(Modifier.weight(1f))
        }
    }
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(RaftAppStroke.Thin)
                .background(RaftColor.BorderHairline)
    )
}

@Composable
private fun ThreadNewMessageMenuMoreOverlay(
    enabled: Boolean,
    onClick: () -> Unit
) {
    val label = stringResource(Res.string.thread_new_message_menu_more)
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(ThreadNewMessageMenuMoreOverlayHeight)
                .raftClickable(onClick = onClick, enabled = enabled)
                .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        RaftLucideIcon(
            name = RaftLucideIconName.ChevronDown,
            modifier = Modifier.size(14.dp),
            color = RaftColor.TextMutedGrayArgb
        )
    }
}

@Composable
private fun ThreadNewMessageMenuToolButton(
    label: String,
    icon: RaftLucideIconName,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier =
            modifier
                .height(ThreadNewMessageMenuToolRowHeight)
                .raftClickable(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(RaftAppRadius.Control))
                .semantics { contentDescription = label }
                .padding(horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        RaftLucideIcon(
            name = icon,
            modifier = Modifier.size(ThreadNewMessageMenuToolIconSize),
            color = if (enabled) RaftColor.TextBlackArgb else RaftColor.TextMutedGrayArgb
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style =
                RaftType.Caption.copy(
                    color = if (enabled) RaftColor.TextBlack else RaftColor.TextMutedGray,
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    letterSpacing = 0.sp,
                    fontWeight = FontWeight.Normal
                ),
            color = if (enabled) RaftColor.TextBlack else RaftColor.TextMutedGray,
            // Web labels ("Remove from Saved", "Unfollow Thread") wrap to a second line in the
            // ~54dp slot; the 52dp row fits an 18dp icon plus two 12sp lines.
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
internal fun ThreadMessageActionMenu(
    message: RaftThreadMessageItem,
    replyMetadata: ThreadReplyMetadata = message.toThreadReplyMetadata(),
    anchor: RaftPopoverAnchor,
    showReplyAction: Boolean,
    showSaveAction: Boolean,
    showFollowAction: Boolean,
    showTaskStatusAction: Boolean,
    showConvertToTaskAction: Boolean,
    createTaskFromMessage: Boolean = false,
    showReactionActions: Boolean,
    showCopyLinkAction: Boolean,
    showShareMessagesAction: Boolean,
    showCancelAction: Boolean,
    showDeleteAction: Boolean,
    showTranslateAction: Boolean = false,
    translatePending: Boolean = false,
    showForwardAction: Boolean = false,
    showAddReactionAction: Boolean = false,
    showMarkThreadUnreadAction: Boolean = false,
    onDismiss: () -> Unit,
    onReply: () -> Unit,
    onCopyLink: () -> Unit,
    onCopy: () -> Unit,
    onSelectText: () -> Unit,
    onShareMessages: () -> Unit,
    onSave: () -> Unit,
    onFollowThread: () -> Unit,
    onUnfollowThread: (String) -> Unit,
    onReact: (String) -> Unit,
    onTaskStatusChange: (String) -> Unit,
    onConvertToTask: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onTranslate: () -> Unit = {},
    onForward: () -> Unit = {},
    onAddReaction: () -> Unit = {},
    onMarkThreadUnread: () -> Unit = {},
    onCopySelection: (() -> Unit)? = null,
    onDownloadVideo: (() -> Unit)? = null,
    /** Quote was chosen (the menu quotes the message into the composer itself), for its `message_action_selected`. */
    onQuoteChosen: () -> Unit = {},
    /** A long press on a link opened the menu: that link's section on top (ThreadMessageLinkMenu.kt). */
    link: ThreadMessageLinkMenuSection? = null,
    diagnosticsLogger: (String) -> Unit = {}
) {
    val reactionAtlasImage = LocalThreadReactionAtlasImage.current
    // web MessageItem "Quote": inserts the message's opening as a structured quote chip into this
    // page's composer; no composer, no Quote.
    val selectionHost = LocalThreadInPlaceTextSelectionHost.current
    val quoteIntoComposer = selectionHost?.quoteIntoComposer
    val insertQuoteIntoComposer = selectionHost?.insertQuoteIntoComposer
    val authorDisplayName = message.localizedAuthorName()
    val menuPendingQuote =
        if (insertQuoteIntoComposer != null || quoteIntoComposer != null) {
            threadMessageMenuPendingQuote(message, authorDisplayName)
        } else {
            null
        }
    val actions =
        remember(
            message,
            replyMetadata,
            showReplyAction,
            showSaveAction,
            showFollowAction,
            showTaskStatusAction,
            showConvertToTaskAction,
            createTaskFromMessage,
            showCopyLinkAction,
            showShareMessagesAction,
            showCancelAction,
            showDeleteAction,
            showTranslateAction,
            translatePending,
            showForwardAction,
            showMarkThreadUnreadAction,
            onReply,
            onCopyLink,
            onCopy,
            onSelectText,
            onShareMessages,
            onSave,
            onFollowThread,
            onUnfollowThread,
            onTaskStatusChange,
            onConvertToTask,
            onCancel,
            onDelete,
            onTranslate,
            onForward,
            onMarkThreadUnread,
            onCopySelection,
            onDownloadVideo,
            link != null,
            menuPendingQuote,
            insertQuoteIntoComposer,
            quoteIntoComposer,
            onQuoteChosen,
            onDismiss
        ) {
            threadMessageMenuActions(
                message = message,
                replyMetadata = replyMetadata,
                showReplyAction = showReplyAction,
                showSaveAction = showSaveAction,
                showFollowAction = showFollowAction,
                showTaskStatusAction = showTaskStatusAction,
                showConvertToTaskAction = showConvertToTaskAction,
                createTaskFromMessage = createTaskFromMessage,
                showCopyLinkAction = showCopyLinkAction,
                showShareMessagesAction = showShareMessagesAction,
                showCancelAction = showCancelAction,
                showDeleteAction = showDeleteAction,
                showTranslateAction = showTranslateAction,
                translatePending = translatePending,
                showForwardAction = showForwardAction,
                showMarkThreadUnreadAction = showMarkThreadUnreadAction,
                onCopySelection = onCopySelection,
                linkSection = link != null,
                onReply = onReply,
                onCopyLink = onCopyLink,
                onCopy = onCopy,
                onSelectText = onSelectText,
                onShareMessages = onShareMessages,
                onSave = onSave,
                onFollowThread = onFollowThread,
                onUnfollowThread = onUnfollowThread,
                onTaskStatusChange = onTaskStatusChange,
                onConvertToTask = onConvertToTask,
                onCancel = onCancel,
                onDelete = onDelete,
                onTranslate = onTranslate,
                onForward = onForward,
                onMarkThreadUnread = onMarkThreadUnread,
                onDownloadVideo = onDownloadVideo,
                showQuoteAction = menuPendingQuote != null,
                onQuote = {
                    if (menuPendingQuote != null) {
                        onQuoteChosen()
                        onDismiss()
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
        }
    // web `reaction-add-any`: the reaction header's trailing Add Reaction (full emoji picker).
    val showAddReaction = showReactionActions && showAddReactionAction
    val density = LocalDensity.current
    val menuWidth =
        with(density) {
            threadMessageMenuWidthDp(showReactionActions, showAddReactionAction = showAddReaction).dp.toPx()
        }
    val menuHeight =
        with(density) {
            threadMessageMenuContentHeightDp(
                actionGroups = actions.map { it.group },
                showReactionActions = showReactionActions,
                linkActionCount = link?.items?.size ?: 0
            ).dp.toPx()
        }
    val viewportGapPx = with(density) { ThreadMessageMenuViewportGap.toPx() }
    val contentSize = Size(
        width = menuWidth,
        height = menuHeight
    )
    val shellItems =
        actions.mapIndexed { index, action ->
            RaftShellOverlayItem(
                id = index.toString(),
                label = action.label.localized(),
                iconName = action.icon.name,
                enabled = action.enabled
            )
        }
    ThreadShellAwareAnchoredPopover(
        anchor = anchor,
        contentSize = contentSize,
        viewportGapPx = viewportGapPx,
        onDismiss = onDismiss,
        shellRequestId = { pageRootAnchor ->
            "thread-message-${message.id}-${pageRootAnchor?.hashCode() ?: 0}-${actions.size}"
        },
        shellItems = shellItems,
        shellContentKey = Triple(showReactionActions, showAddReaction, link?.url),
        keepsHostKeyboard = true,
        shellContent = { onSelected ->
            // The shell host composes this in its own page. Decode the reaction atlas
            // there instead of capturing this page's `reactionAtlasImage`: the atlas
            // loads asynchronously, so a lambda registered before it was ready kept
            // the first-frame null forever and every reaction rendered as an empty
            // box (HuaTuo device check of 6de03d9a, all layouts).
            ThreadReactionAtlasProvider {
                ThreadMessageMenuChrome(
                    actions = actions.mapIndexed { index, action ->
                        action.copy(onClick = { onSelected(index.toString()) })
                    },
                    showReactionActions = showReactionActions,
                    reactionAtlasImage = LocalThreadReactionAtlasImage.current,
                    onReact = { emoji ->
                        onReact(emoji)
                    },
                    modifier = Modifier,
                    // Like the emojis: called straight into this page, which closes the menu and
                    // opens the picker at the menu's anchor.
                    onAddReaction = if (showAddReaction) onAddReaction else null,
                    // Its actions too.
                    link = link
                )
            }
        },
        onShellSelected = { id ->
            actions.getOrNull(id.toIntOrNull() ?: -1)
                ?.takeIf { it.enabled }
                ?.onClick
                ?.invoke()
        },
        onShellRequested = { requestId, pageWindowAnchor ->
            diagnosticsLogger(
                "thread_message_menu_shell_request messageId=${message.id} requestId=$requestId " +
                    "anchor=$pageWindowAnchor menuW=$menuWidth menuH=$menuHeight"
            )
        },
        inPageContent = { inShellFallback ->
            if (!inShellFallback) {
                LaunchedEffect(message.id, anchor) {
                    diagnosticsLogger(
                        "thread_message_menu_positioned messageId=${message.id} menuW=$menuWidth menuH=$menuHeight"
                    )
                }
            }
            ThreadMessageMenuChrome(
                actions = actions,
                showReactionActions = showReactionActions,
                reactionAtlasImage = reactionAtlasImage,
                onReact = onReact,
                modifier = Modifier,
                onAddReaction = if (showAddReaction) onAddReaction else null,
                link = link
            )
        }
    )
}

/**
 * A popover opened from a thread message, at [anchor] with web's context menu placement. A thread
 * is often rendered in a 400dp child page, and a Modal created there receives that page's native
 * surface and clips whatever overflows it; on such a page the popover goes through the app-shell
 * host instead ([RaftShellOverlayContentRegistry]), which owns a window-sized surface while the
 * callbacks stay in this page. [shellContent] is composed in the shell's page, so it must not read
 * this page's composition locals; [inPageContent] gets whether it is the shell path's fallback.
 */
@Composable
private fun ThreadShellAwareAnchoredPopover(
    anchor: RaftPopoverAnchor,
    contentSize: Size,
    viewportGapPx: Float,
    onDismiss: () -> Unit,
    shellRequestId: (pageRootAnchor: Offset?) -> String,
    // The shell's contract wants at least one row; it draws them only when no content was registered.
    shellItems: List<RaftShellOverlayItem>,
    shellContentKey: Any?,
    shellContent: @Composable (onSelected: (String) -> Unit) -> Unit,
    onShellSelected: (String) -> Unit,
    onShellRequested: (requestId: String, pageWindowAnchor: Offset) -> Unit,
    // A menu with no text field: the composer's keyboard stays up (RaftModalAnchoredPopover).
    keepsHostKeyboard: Boolean = false,
    inPageContent: @Composable (inShellFallback: Boolean) -> Unit
) {
    // Task #185 (narrowed for 1.14.2): only an adaptive split-pane detail page clips a Modal to
    // its 400dp child surface. Everywhere else (single-pane phones, full-width pages) keep the
    // original in-page Modal path unchanged; only the clipping case goes through the shell host.
    // The adaptive identity key alone is not enough: phones open threads through the same adaptive
    // workspace, so it is also set on a full-width single-pane page (device check of 6de03d9a took
    // the shell path in 720x1280 portrait). Only a page that starts right of the window's left
    // edge sits in a narrower pane whose Modal surface can clip the menu. Until the page has been
    // measured its origin reads as zero, which would look full-width; route an unmeasured adaptive
    // page through the shell host, which renders the menu whole in every geometry, rather than
    // risk one clipped in-page menu (review: CC-Cata, 沈括).
    val adaptiveDetailPage =
        LocalActivity.current.pageData.params
            .optString(RAFT_ADAPTIVE_DETAIL_IDENTITY_KEY)
            .isNotBlank()
    val pageOriginState =
        RaftChildPageOriginRegistry
            .stateFor(raftChildPageOriginKey(THREAD_PAGE, LocalActivity.current.pageData.params))
    val clippingChildPage =
        threadMenuUsesShellHost(
            adaptiveDetailPage = adaptiveDetailPage,
            originMeasured = pageOriginState.measured,
            windowOriginX = pageOriginState.windowOrigin.x
        )
    if (!clippingChildPage) {
        RaftModalAnchoredPopover(
            anchor = anchor,
            contentSize = contentSize,
            onDismiss = onDismiss,
            viewportGap = viewportGapPx,
            horizontalOffset = 0f,
            verticalOffset = 0f,
            verticalOffsetAbove = 0f,
            verticalPlacement = RaftFloatingVerticalPlacement.ContextMenu,
            horizontalPlacement = RaftFloatingHorizontalPlacement.ContextMenu,
            keepsHostKeyboard = keepsHostKeyboard
        ) {
            inPageContent(false)
        }
        return
    }
    // A thread message is often rendered in a 400dp child page. A Modal created here therefore
    // receives that page's native surface and clips the right side of the menu. Register the full
    // composable with the app-shell host instead; the shell owns a window-sized surface while the
    // action callbacks and reaction bitmap remain in this page's process-local state.
    val localPager = LocalActivity.current.getPager() as Pager
    val eventBusOverride = LocalThreadHeaderMenuEventBus.current
    val pageEventBus: build.raft.app.data.RaftEventBus =
        remember(localPager.pagerId, eventBusOverride) {
            eventBusOverride ?: RaftSharedStoreRegistry.get(Utils.bridgeModule(localPager.pagerId)).eventBus
        }
    val pageKey =
        LocalActivity.current.pageData.params
            .optString(RAFT_ADAPTIVE_DETAIL_IDENTITY_KEY)
            .trim()
            .ifEmpty { "thread-panel" }
    val pageRootAnchor = anchor.rootOffsetOrNull()
    // Already window space: the child page is composed inline (no page origin to add).
    val pageWindowAnchor = raftChildPageWindowAnchor(anchor)
    val requestId = shellRequestId(pageRootAnchor)
    DisposableEffect(pageEventBus, requestId) {
        val subscription =
            pageEventBus.observe(RAFT_SHELL_OVERLAY_RESULT_EVENT) { eventData ->
                if (raftShouldApplyShellOverlayResult(requestId, eventData)) {
                    onDismiss()
                }
            }
        onDispose {
            subscription.close()
            RaftShellOverlayContentRegistry.remove(requestId)
            pageEventBus.post(
                RAFT_SHELL_OVERLAY_REQUEST_EVENT,
                cancelRaftShellOverlayJson(requestId)
            )
        }
    }
    if (pageWindowAnchor == null) {
        // Keep an in-page fallback for fixtures that intentionally omit layout coordinates. A real
        // child page always has a root anchor before its long-press action menu is shown.
        RaftModalAnchoredPopover(
            anchor = anchor,
            contentSize = contentSize,
            onDismiss = onDismiss,
            viewportGap = viewportGapPx,
            horizontalOffset = 0f,
            verticalOffset = 0f,
            verticalOffsetAbove = 0f,
            verticalPlacement = RaftFloatingVerticalPlacement.ContextMenu,
            horizontalPlacement = RaftFloatingHorizontalPlacement.ContextMenu,
            keepsHostKeyboard = keepsHostKeyboard
        ) {
            inPageContent(true)
        }
    } else {
        LaunchedEffect(requestId, pageWindowAnchor, shellContentKey, shellItems) {
            RaftShellOverlayContentRegistry.register(
                RaftShellOverlayContentEntry(
                    requestId = requestId,
                    contentSize = contentSize,
                    viewportGap = viewportGapPx,
                    horizontalOffset = 0f,
                    verticalOffset = 0f,
                    keepsHostKeyboard = keepsHostKeyboard,
                    content = shellContent,
                    onSelected = onShellSelected,
                )
            )
            pageEventBus.post(
                RAFT_SHELL_OVERLAY_REQUEST_EVENT,
                RaftShellOverlayRequest(
                    requestId = requestId,
                    pageKey = pageKey,
                    anchor = pageWindowAnchor,
                    items = shellItems
                ).toJson()
            )
            onShellRequested(requestId, pageWindowAnchor)
        }
    }
}

/**
 * Add Reaction's picker on a wide screen: web's floating panel, opened where the message menu was
 * and kept inside the window; through the app-shell host on a split-pane page, like the menu.
 */
@Composable
internal fun ThreadMessageEmojiPickerPopover(
    messageId: String,
    anchor: RaftPopoverAnchor,
    preferences: RaftEmojiPickerPreferences,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    diagnosticsLogger: (String) -> Unit = {}
) {
    val density = LocalDensity.current
    val shellItemLabel = stringResource(Res.string.thread_action_add_reaction)
    ThreadShellAwareAnchoredPopover(
        anchor = anchor,
        contentSize = raftEmojiPickerPopoverSize(density),
        viewportGapPx = with(density) { RaftEmojiPickerPopoverViewportGap.toPx() },
        onDismiss = onDismiss,
        shellRequestId = { pageRootAnchor -> "thread-emoji-picker-$messageId-${pageRootAnchor?.hashCode() ?: 0}" },
        // Never drawn: the panel below is registered with the request.
        shellItems = listOf(RaftShellOverlayItem(id = "emoji-picker", label = shellItemLabel)),
        shellContentKey = preferences,
        shellContent = { onSelected ->
            RaftEmojiPickerPopoverSurface(
                preferences = preferences,
                catalog = RaftEmojiCatalog.Default,
                onSelect = onSelected
            )
        },
        // The panel reports the picked emoji as the selected id.
        onShellSelected = onSelect,
        onShellRequested = { requestId, pageWindowAnchor ->
            diagnosticsLogger("thread_emoji_picker_shell_request messageId=$messageId requestId=$requestId anchor=$pageWindowAnchor")
        },
        inPageContent = {
            RaftEmojiPickerPopoverSurface(
                preferences = preferences,
                catalog = RaftEmojiCatalog.Default,
                onSelect = onSelect
            )
        }
    )
}

/** A double tap's quick reaction bar: which message, anchored where the second tap landed. */
internal data class ThreadQuickReactionBarRequest(
    val messageId: String,
    val anchor: RaftPopoverAnchor,
    /** Opened by the hover toolbar's Add Reaction: under the button, its end on the button's end. */
    val fromHoverToolbar: Boolean = false
)

/**
 * web MessageHoverToolbar (slock packages/web/src/components/message/MessageHoverToolbar.tsx):
 * which buttons one message's hover toolbar has, in the web's order (the message menu's: Add
 * Reaction, Reply in Thread, Forward, Save, then More Actions, which opens that menu). Built from
 * the message menu's capabilities ([threadMessageHoverToolbarActions]), so a button is there
 * exactly when its menu item is.
 */
internal class ThreadMessageHoverToolbarActions(
    /** Add Reaction: the quick reactions under the button (web `canReact && !isSystem`). */
    val reaction: Boolean,
    /**
     * Reply in Thread: not on the rows of a thread (web `hideThreadActions`). Kept on a message that
     * shows its thread, which the menu drops it from (botiverse/slock #15524).
     */
    val reply: Boolean,
    /** Forward: where the message can be forwarded (web `canForwardThisMessage`). */
    val forward: Boolean,
    val save: Boolean,
    val saved: Boolean,
    /** More Actions: the message menu under the button (web `canOpenMenuFromToolbar`). */
    val more: Boolean,
    val onShowReactions: (RaftPopoverAnchor) -> Unit,
    val onForward: () -> Unit,
    val onToggleSave: () -> Unit,
    /** Whether the toolbar shows: the list draws that row over its neighbours, as the toolbar reaches into the row above. */
    val onShownChange: (Boolean) -> Unit = {},
) {
    val isEmpty: Boolean
        get() = buttonCount == 0

    val buttonCount: Int
        get() = listOf(reaction, reply, forward, save, more).count { it }
}

internal fun threadMessageHoverToolbarActions(
    message: RaftThreadMessageItem,
    capabilities: ThreadMessageMenuCapabilities,
    onShowReactions: (RaftPopoverAnchor) -> Unit,
    onForward: () -> Unit,
    onToggleSave: () -> Unit,
    onShownChange: (Boolean) -> Unit = {},
): ThreadMessageHoverToolbarActions? {
    if (message.isRetracted) return null
    val actions =
        ThreadMessageHoverToolbarActions(
            reaction = capabilities.quickReactions,
            reply = capabilities.reply,
            forward = capabilities.forward,
            save = capabilities.save,
            saved = message.isSaved,
            more = !message.isSystemMessage(),
            onShowReactions = onShowReactions,
            onForward = onForward,
            onToggleSave = onToggleSave,
            onShownChange = onShownChange,
        )
    return actions.takeUnless { it.isEmpty }
}

/** Between a hover toolbar button and the menu or reactions it opens (web: 4px). */
internal val ThreadMessageHoverToolbarPopupGap = 4.dp

private enum class ThreadMessageHoverToolbarOpener { Reaction, More }

/**
 * web MessageHoverToolbar on a message row: the design-system [RaftMessageItemToolbar] over the
 * row's top right corner (the caller places it), shown only for a mouse or trackpad
 * ([RaftPointerInput.Fine]; web returns null for `pointer: coarse`, where a press and hold opens
 * the menu) while the pointer is over the row or over the toolbar itself (its upper half rides
 * above the row), and while one of the row's popups is open (raft-ui `has-data-popup-open`).
 * The button whose popup is open stays marked (`data-popup-open`). No tooltips, as on the web.
 */
@Composable
internal fun ThreadMessageHoverToolbar(
    messageId: String,
    actions: ThreadMessageHoverToolbarActions,
    rowHovered: Boolean,
    popupOpen: Boolean,
    onShowActions: (RaftPopoverAnchor) -> Unit,
    onReplyInThread: () -> Unit,
    modifier: Modifier = Modifier,
    /** Whether it shows, for the row (its header fades under it). */
    onShownChange: (Boolean) -> Unit = {},
) {
    var toolbarHovered by remember { mutableStateOf(false) }
    var opener by remember { mutableStateOf<ThreadMessageHoverToolbarOpener?>(null) }
    LaunchedEffect(popupOpen) { if (!popupOpen) opener = null }
    val shown = RaftTheme.pointerInput == RaftPointerInput.Fine && (rowHovered || toolbarHovered || popupOpen)
    val currentOnShownChange by rememberUpdatedState(actions.onShownChange)
    val currentOnRowShownChange by rememberUpdatedState(onShownChange)
    DisposableEffect(shown) {
        if (shown) {
            currentOnShownChange(true)
            currentOnRowShownChange(true)
        }
        onDispose {
            if (shown) {
                currentOnShownChange(false)
                currentOnRowShownChange(false)
            }
        }
    }
    if (!shown) return
    val density = LocalDensity.current
    val anchorIdentity = threadMessageMenuOwnerIdentity(messageId)
    val reactionAnchor = remember(anchorIdentity) { RaftLiveRootAnchorRef(identity = anchorIdentity) }
    val moreAnchor = remember(anchorIdentity) { RaftLiveRootAnchorRef(identity = anchorIdentity) }
    val buttonPx = with(density) { RaftMessageItemToolbarDefaults.ButtonSize.toPx() }
    val gapPx = with(density) { ThreadMessageHoverToolbarPopupGap.toPx() }
    RaftMessageItemToolbar(
        modifier = modifier.raftPointerHoverPosition { toolbarHovered = it != null }
    ) {
        if (actions.reaction) {
            RaftMessageItemToolbarButton(
                onClick = {
                    // web `positionReactionPickerFromRect`: the bar's end on the button's end.
                    reactionAnchor.resolve(RaftPopoverLocalPoint.BottomEnd)?.let { anchor ->
                        opener = ThreadMessageHoverToolbarOpener.Reaction
                        actions.onShowReactions(anchor)
                    }
                },
                label = stringResource(Res.string.thread_action_add_reaction),
                modifier = Modifier.raftCaptureLiveRootAnchor(reactionAnchor),
                open = popupOpen && opener == ThreadMessageHoverToolbarOpener.Reaction,
            ) {
                RaftSlotIcon(RaftLucideIconName.SmilePlus)
            }
        }
        if (actions.reply) {
            RaftMessageItemToolbarButton(
                onClick = onReplyInThread,
                label = stringResource(Res.string.thread_action_reply_in_thread),
            ) {
                RaftConversationKindIcon(RaftConversationKindGlyph.Thread, size = RaftMessageItemToolbarDefaults.IconSize)
            }
        }
        if (actions.forward) {
            RaftMessageItemToolbarButton(
                onClick = actions.onForward,
                label = stringResource(Res.string.thread_action_forward),
            ) {
                RaftGlyphIcon(RaftGlyph.Forward)
            }
        }
        if (actions.save) {
            RaftMessageItemToolbarButton(
                onClick = actions.onToggleSave,
                label =
                    stringResource(
                        if (actions.saved) Res.string.thread_action_remove_from_saved else Res.string.common_save
                    ),
                active = actions.saved,
            ) {
                if (actions.saved) RaftSlotIcon(RaftLucideIconName.BookmarkMinus) else RaftGlyphIcon(RaftGlyph.Bookmark)
            }
        }
        if (actions.more) {
            RaftMessageItemToolbarButton(
                onClick = {
                    // web `handleOpenMenuFromToolbar`: the menu at the button's start, 4px under it.
                    moreAnchor.resolve(RaftPopoverLocalPoint.Exact(Offset(0f, buttonPx + gapPx)))?.let { anchor ->
                        opener = ThreadMessageHoverToolbarOpener.More
                        onShowActions(anchor)
                    }
                },
                label = stringResource(Res.string.common_more_actions),
                modifier = Modifier.raftCaptureLiveRootAnchor(moreAnchor),
                open = popupOpen && opener == ThreadMessageHoverToolbarOpener.More,
            ) {
                RaftGlyphIcon(RaftGlyph.Ellipsis)
            }
        }
    }
}
