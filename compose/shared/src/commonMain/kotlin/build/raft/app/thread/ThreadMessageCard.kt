package build.raft.app

import build.raft.app.links.extractFirstQuotedRaftPermalink
import build.raft.app.links.raftPermalinkHostnames
import build.raft.app.data.RaftClientConfigAppHosts
import build.raft.app.links.ParsedRaftPermalink
import build.raft.designsystem.haptics.rememberRaftHaptics
import build.raft.designsystem.components.raftSafeAreaHorizontalPadding
import build.raft.app.resources.*
import build.raft.app.markdown.markdownToPreviewText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.components.RaftPreviewCardState
import build.raft.designsystem.components.raftLocatedStrength
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftTooltip
import build.raft.app.data.RaftDisplayPreferenceRuntime
import build.raft.app.data.ThreadActionCard
import build.raft.app.data.defaultNowMillis
import build.raft.app.data.expiredAsOf
import build.raft.app.data.stripQuoteReplyRefsForPreview
import build.raft.app.model.ConversationKind
import build.raft.app.model.appAvatarWireValueOrNull
import build.raft.app.markdown.LocalRaftMarkdownLinkLongPress
import build.raft.app.markdown.RaftMarkdownOwnTaskStatusScope
import build.raft.app.markdown.RaftMarkdownMentionReadStateScope
import build.raft.app.markdown.LocalRaftMessagePermalinkLabelResolver
import build.raft.app.markdown.LocalRaftUnavailableQuotedPermalink
import build.raft.app.markdown.LocalRaftMarkdownFindQuery
import build.raft.app.markdown.withRaftMarkdownFindMarks
import build.raft.app.markdown.RaftMarkdownBody
import build.raft.app.markdown.LocalRaftMarkdownCodeTapObserver
import build.raft.app.markdown.ThreadMarkdownTag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.RectangleShape
import build.raft.designsystem.components.RaftHoverTier
import build.raft.designsystem.components.raftPointerHover
import build.raft.designsystem.components.RaftMessageItemToolbarDefaults
import build.raft.designsystem.components.raftMessageItemToolbarHeaderMask
import build.raft.designsystem.components.raftPointerHoverPosition
import build.raft.designsystem.components.raftStateOverlay
import build.raft.designsystem.components.rememberRaftStateOverlay
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import build.raft.designsystem.components.raftClickable

internal data class ThreadMessageMetadataSlotLayout(
    val widthDp: Float,
    val heightDp: Float,
    val timestampVisible: Boolean
)

/** raft-ui MessageItemHeader `mb-px`: the gap between the header and the body. */
internal val ThreadMessageHeaderBodyGap = 1.dp

/**
 * A message's content column with the header first: [Arrangement.spacedBy] 4dp, except the body
 * starts [ThreadMessageHeaderBodyGap] under the header. The column still measures every gap at
 * 4dp, so the space the first gap gives back is left empty at its end, and
 * [threadMessageHeaderBodyGapTrim] takes it off: every child keeps its own height (and hit area).
 */
internal object ThreadMessageHeaderFirstArrangement : Arrangement.Vertical {
    override val spacing: Dp = RaftAppSpacing.Xs

    override fun Density.arrange(totalSize: Int, sizes: IntArray, outPositions: IntArray) {
        val firstGapPx = ThreadMessageHeaderBodyGap.roundToPx()
        val gapPx = spacing.roundToPx()
        var y = 0
        sizes.forEachIndexed { index, size ->
            outPositions[index] = y
            y += size + if (index == 0) firstGapPx else gapPx
        }
    }

    override fun toString(): String = "ThreadMessageHeaderFirstArrangement"
}

/** Takes off the empty end [ThreadMessageHeaderFirstArrangement] leaves under the last child. */
private fun Modifier.threadMessageHeaderBodyGapTrim(): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minHeight = 0))
        val trim = (RaftAppSpacing.Xs - ThreadMessageHeaderBodyGap).roundToPx()
        val height = (placeable.height - trim).coerceAtLeast(0).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(placeable.width, height) { placeable.place(0, 0) }
    }

/** raft-ui `--spacing-timeline-row`: every message row's inset above and below its text. */
internal val ThreadMessageTimelineRowInset = 4.dp

/** raft-ui `--spacing-timeline-turn`: the transparent band above a turn's first row. */
internal val ThreadMessageTimelineTurn = 8.dp

internal fun threadMessageMetadataSlotLayout(
    showAvatar: Boolean,
    compactTimestampGutter: Boolean,
    showTimestamp: Boolean
): ThreadMessageMetadataSlotLayout =
    ThreadMessageMetadataSlotLayout(
        // raft-ui MessageItem `gutter` / `avatarSlot`: w-[35px], the md avatar (35) under its mt-0.5.
        widthDp = 35f,
        heightDp =
            when {
                showAvatar -> 37f
                compactTimestampGutter -> 18f
                else -> 0f
            },
        timestampVisible = !showAvatar && compactTimestampGutter && showTimestamp
    )

@Composable
private fun ThreadMessageGeometryTintBox(tint: Color?, content: @Composable () -> Unit) {
    if (tint == null) {
        content()
    } else {
        Box(Modifier.background(tint)) { content() }
    }
}

/**
 * artin #20 gap 17: the message a pasted permalink points to, as the web's QuotedMessageCard
 * (raft-ui MessageQuotedPreview in the preview card): where it was posted, the author's face and
 * name, a thread marker, an Archived tag, the time in Space Mono, two lines of text and an
 * attachment count chip. Loading is the web's skeleton card; a message that can't be loaded keeps
 * mobile's muted "unavailable" line (the web shows nothing).
 */
@Composable
internal fun ThreadQuotedPermalinkCard(
    state: ThreadQuotedPermalinkUiState,
    target: ParsedRaftPermalink?,
    memberAvatarResolver: RaftMemberAvatarResolver,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    when (state) {
        ThreadQuotedPermalinkUiState.Loading ->
            build.raft.designsystem.components.RaftMessageQuotedPreviewLoading(
                label = stringResource(Res.string.thread_quoted_permalink_loading),
                modifier = modifier
            )
        // The web renders nothing for a quote it cannot read; the row never composes this state (see
        // threadQuotedPermalinkPreviewVisible), and the permalink chip says so instead.
        ThreadQuotedPermalinkUiState.Unavailable -> Unit
        // Quoted as one muted line, like an unavailable quote (web QuoteCardContent).
        ThreadQuotedPermalinkUiState.Retracted ->
            build.raft.designsystem.components.RaftMessageQuotedPreviewUnavailable(
                text = stringResource(Res.string.message_delegated_retracted),
                modifier = modifier
            )
        is ThreadQuotedPermalinkUiState.Loaded -> {
            val item = state.message.item
            val direct = target?.routeKind == ConversationKind.DirectMessage
            val resolvedChannel = target?.let { LocalRaftMessagePermalinkLabelResolver.current(it) }
            // Agent Lounge v0: a quote from a Lounge names the Lounge, without the # glyph.
            val loungeLabel = if (direct) null else agentLoungeLabelOrNull(target?.channelId?.value)
            val channel =
                loungeLabel ?: resolvedChannel?.trim()?.removePrefix("#")?.removePrefix("@")?.takeIf { it.isNotBlank() }
                    ?: if (direct) {
                        stringResource(Res.string.thread_quoted_permalink_direct_message)
                    } else {
                        stringResource(Res.string.thread_quoted_permalink_channel_fallback)
                    }
            val senderKind = item.authorKind.parsedMemberKindOrNull()
            val jointAuthor =
                rememberRaftJointActor(
                    memberAvatarResolver = memberAvatarResolver,
                    kind = senderKind,
                    memberId = item.authorId,
                    enabled = memberAvatarResolver.jointSurface,
                )
            val avatarWireValue =
                resolveThreadRowAvatarWireValue(rememberRaftMemberAvatarWireValue(memberAvatarResolver, item.authorId))
                    ?: item.avatarWireValue?.appAvatarWireValueOrNull()
                    ?: jointAuthor?.explicitAvatarWireValue()?.appAvatarWireValueOrNull()
            val quotedAvatarPending =
                avatarWireValue.isNullOrBlank() &&
                    rememberRaftMemberAvatarPending(
                        memberAvatarResolver = memberAvatarResolver,
                        kind = senderKind,
                        memberId = item.authorId,
                        jointSurface = memberAvatarResolver.jointSurface,
                    )
            build.raft.designsystem.components.RaftMessageQuotedPreview(
                authorName = item.localizedAuthorName().ifBlank { stringResource(Res.string.thread_comments_unknown_author) },
                text = markdownToPreviewText(item.body).takeIf { it.isNotBlank() }
                    ?: stringResource(Res.string.thread_quoted_permalink_no_content),
                modifier = modifier,
                // A resolved DM title names the peer; without one the fallback reads as a place.
                channel = channel,
                direct = direct && resolvedChannel != null,
                channelGlyph = loungeLabel == null,
                threadLabel =
                    if (target?.threadParentMessageId != null) {
                        stringResource(Res.string.thread_quoted_permalink_thread)
                    } else {
                        null
                    },
                archivedLabel =
                    if (state.message.channelArchived) stringResource(Res.string.thread_quoted_permalink_archived) else null,
                avatar = {
                    RaftAppAvatar(
                        kind = raftAvatarKind(senderKind),
                        avatarWireValue = avatarWireValue,
                        label = item.localizedAuthorName(),
                        avatarSize = build.raft.designsystem.components.RaftMessageQuotedPreviewDefaults.AvatarSize.edge,
                        cacheSubject = raftMemberAvatarCacheSubject(senderKind, item.authorId),
                        avatarPending = quotedAvatarPending,
                    )
                },
                // RFC 077 (web QuoteCardContent): "(sent by Friday)" after the author, words only.
                authorDelegatedBy =
                    item.delegatedBy?.let { delegatedBy ->
                        {
                            ThreadMessageDelegatedByLabel(
                                delegatedBy = delegatedBy,
                                placement = DelegatedLabelPlacement.InBody
                            )
                        }
                    },
                authorMeta = item.authorDescription?.trim()?.takeIf { it.isNotBlank() },
                time =
                    if (item.timestampLabel.isNotBlank()) {
                        {
                            RaftRelativeTimestampText(
                                value = item.timestampLabel,
                                displayGroup = RaftTimestampDisplayGroup.Conversation,
                                style = build.raft.designsystem.components.RaftMessageQuotedPreviewDefaults.TimeStyle,
                                color = build.raft.designsystem.components.RaftMessageQuotedPreviewDefaults.TimeInk,
                                maxLines = 1,
                                toggleEnabled = false
                            )
                        }
                    } else {
                        null
                    },
                attachments = listOfNotNull(threadQuotedAttachmentLabel(item.attachments)),
                onClick = onOpen
            )
        }
    }
}

/** web `buildQuotedMessageAttachmentLabel`: "1 image", "1 file", "3 images" or "3 attachments". */
@Composable
private fun threadQuotedAttachmentLabel(attachments: List<RaftThreadAttachmentItem>): String? {
    if (attachments.isEmpty()) return null
    val images = attachments.count { it.mimeType.startsWith("image/") }
    return when {
        attachments.size == 1 && images == 1 -> stringResource(Res.string.thread_quoted_permalink_one_image)
        attachments.size == 1 -> stringResource(Res.string.thread_quoted_permalink_one_file)
        images == attachments.size -> stringResource(Res.string.thread_quoted_permalink_images, images)
        else -> stringResource(Res.string.thread_quoted_permalink_attachments, attachments.size)
    }
}

/**
 * task #346: state payload of [ThreadMessageCard]. See RaftStructuralHomeShellData for why
 * the flat parameter list had to be grouped (Android dex verifier rejects the
 * un-minified giant composable frame on API 34/36 builds).
 */
private data class ThreadMessageCardData(
    val message: RaftThreadMessageItem,
    val messageBodyTextStyle: TextStyle,
    val replyMetadata: ThreadReplyMetadata,
    val hasReplyDraft: Boolean,
    val sourceTasks: List<ThreadMessageSourceTaskItem> = emptyList(),
    val actionCard: ThreadActionCard?,
    val messageIndex: Int,
    val highlightedMentions: Set<String>,
    val alignOwnMessageRight: Boolean,
    val messageAlignmentModeEnabled: Boolean,
    val compactConsecutiveMessages: Boolean,
    val suppressRepeatedAuthor: Boolean,
    val showReplyAction: Boolean,
    /** The message can be reacted to (the menu's quick reaction gate): its double tap shows the bar. */
    val quickReactionsEnabled: Boolean = false,
    val resolvedTaskAssigneeName: String?,
    val markdownTagContextSignature: String,
    val taskOpenEnabled: Boolean,
    val taskStatusActionsEnabled: Boolean,
    val showSaveAction: Boolean,
    val showReactionAddAction: Boolean,
    val activeMarkdownTagKey: String?,
    val timestampLabel: String,
    val compactTimestampLabel: String,
    /** When an action card on this message was done, as the thread writes a message's time. */
    val actionCardExecutedTimeLabel: String? = null,
    val attachmentOpenStates: Map<String, ThreadAttachmentOpenUiState>,
    val pendingActionCardIds: Set<String>,
    val writeActionsEnabled: Boolean,
    val currentServerSlug: String?,
    val richContentEnabled: Boolean,
    /** The row a jump took someone to: raft-ui's located highlight. */
    val focused: Boolean,
    /** The current match of the thread's inline search: `info-soft`, held while it is current. */
    val searchMatch: Boolean = false,
    /** web MessageItem `data-popup-open`: this row's context menu is open. */
    val popupOpen: Boolean = false,
    val inlineReplyParticipantsById: Map<String, RaftThreadMemberItem>,
    val shareSelectionActive: Boolean,
    val selectedForShare: Boolean,
    val translation: ThreadMessageTranslationUi?,
    val longMessageCollapse: RaftLongMessageCollapseController?,
    val longPressGestureInstanceKey: Any,
    val outerPadding: androidx.compose.ui.unit.Dp,
    val hoverToolbar: ThreadMessageHoverToolbarActions? = null,
)

/** task #346: callback payload of [ThreadMessageCard]. See ThreadMessageCardData. */
private class ThreadMessageCardCallbacks(
    val diagnosticsLogger: (String) -> Unit,
    val onOpenSourceTask: (String) -> Unit = {},
    val agentStatusResolver: RaftAgentStatusResolver,
    val resolveMarkdownTagLabel: ((ThreadMarkdownTag) -> String)?,
    val isMarkdownTagValid: (ThreadMarkdownTag) -> Boolean,
    val onShowActions: (RaftPopoverAnchor) -> Unit,
    /** Called right after a long press opened the message menu (not the reaction "+" chip). */
    val onLongPressMenuOpened: () -> Unit,
    val onOpenMember: (MemberDetailIdentity) -> Unit,
    val onExternalAuthor: (RaftThreadMessageItem) -> Unit,
    val onOpenThread: (String) -> Unit,
    /** Double tap (or the "Add reaction" accessibility action): the quick reaction bar at the anchor. */
    val onShowQuickReactions: (RaftPopoverAnchor) -> Unit = {},
    val onOpenTask: (String) -> Unit,
    val onOpenTaskStatusMenu: (RaftPopoverAnchor) -> Unit,
    val onToggleReaction: (String) -> Unit,
    /** The "Saved" chip removes the message from Saved (web `handleSavedIndicatorClick`); null keeps it a label. */
    val onToggleSave: (() -> Unit)? = null,
    /** Reaction chips name who reacted (long press / hover). */
    val showReactionReactors: Boolean,
    val onOpenCommentHost: (RaftThreadMessageCommentRef) -> Unit,
    val onOpenAttachment: (String) -> Unit,
    val onDownloadAttachment: (String) -> Unit,
    val onRetryLocalSend: () -> Unit,
    val onCopyLocalSendFailureDetails: () -> Unit,
    val onCommitActionCard: (String) -> Unit,
    /** A spec card's navigate to a channel (its id): ThreadPage `openActionCardChannel`. */
    val onOpenActionCardChannel: (String) -> Unit = {},
    val onOpenMarkdownTag: (ThreadMarkdownTag, String) -> Unit,
    val onOpenMarkdownUrl: (String) -> Unit,
    val onMentionAuthor: (RaftThreadMessageItem) -> Unit,
    val isMessageListScrolling: () -> Boolean,
    val memberAvatarResolver: RaftMemberAvatarResolver,
    val memberModelLabelResolver: RaftMemberModelLabelResolver,
    val onToggleShareSelection: () -> Unit,
    val onToggleTranslationOriginal: () -> Unit,
    val onRetryTranslation: () -> Unit,
    val onOpenForwardBundle: (String) -> Unit,
    val onToggleLongMessage: () -> Unit,
    val onLongPressInputReady: ((String) -> Unit)?,
    val quotedPermalinkLoader: ThreadQuotedPermalinkLoader?,
    val onShowAuthorDescription: ((String, RaftPopoverAnchor) -> Unit)? = null,
    /** A quote reply's card was tapped: the quoted message's id ([ThreadQuoteReplyBody]); null: cards don't jump. */
    val onJumpToQuotedMessage: ((String) -> Unit)? = null,
)

internal @Composable
fun ThreadMessageCard(
    message: RaftThreadMessageItem,
    messageBodyTextStyle: TextStyle,
    replyMetadata: ThreadReplyMetadata = message.toThreadReplyMetadata(),
    hasReplyDraft: Boolean = false,
    /** Task v2: the tasks listed under the message (web `TaskChipList`); tapping one opens it. */
    sourceTasks: List<ThreadMessageSourceTaskItem> = emptyList(),
    onOpenSourceTask: (String) -> Unit = {},
    actionCard: ThreadActionCard? = message.threadActionCard(),
    messageIndex: Int = -1,
    diagnosticsLogger: (String) -> Unit = {},
    highlightedMentions: Set<String> = emptySet(),
    agentStatusResolver: RaftAgentStatusResolver = EmptyRaftAgentStatusResolver,
    alignOwnMessageRight: Boolean = false,
    messageAlignmentModeEnabled: Boolean = alignOwnMessageRight,
    compactConsecutiveMessages: Boolean = false,
    suppressRepeatedAuthor: Boolean = false,
    showReplyAction: Boolean = true,
    quickReactionsEnabled: Boolean = false,
    resolvedTaskAssigneeName: String? = null,
    resolveMarkdownTagLabel: ((ThreadMarkdownTag) -> String)? = null,
    markdownTagContextSignature: String = "",
    taskOpenEnabled: Boolean = true,
    taskStatusActionsEnabled: Boolean = true,
    showSaveAction: Boolean = true,
    showReactionAddAction: Boolean = false,
    isMarkdownTagValid: (ThreadMarkdownTag) -> Boolean = ::defaultThreadMarkdownTagIsValid,
    activeMarkdownTagKey: String? = null,
    timestampLabel: String = message.timestampLabel,
    compactTimestampLabel: String = timestampLabel,
    actionCardExecutedTimeLabel: String? = null,
    onShowActions: (RaftPopoverAnchor) -> Unit,
    onLongPressMenuOpened: () -> Unit = {},
    onOpenMember: (MemberDetailIdentity) -> Unit,
    onExternalAuthor: (RaftThreadMessageItem) -> Unit = {},
    onOpenThread: (String) -> Unit,
    onShowQuickReactions: (RaftPopoverAnchor) -> Unit = {},
    onOpenTask: (String) -> Unit,
    onOpenTaskStatusMenu: (RaftPopoverAnchor) -> Unit,
    onToggleReaction: (String) -> Unit,
    onToggleSave: (() -> Unit)? = null,
    showReactionReactors: Boolean = false,
    onOpenCommentHost: (RaftThreadMessageCommentRef) -> Unit = {},
    onOpenAttachment: (String) -> Unit,
    onDownloadAttachment: (String) -> Unit = {},
    onRetryLocalSend: () -> Unit = {},
    onCopyLocalSendFailureDetails: () -> Unit = {},
    attachmentOpenStates: Map<String, ThreadAttachmentOpenUiState> = emptyMap(),
    pendingActionCardIds: Set<String> = emptySet(),
    writeActionsEnabled: Boolean = true,
    onCommitActionCard: (String) -> Unit = {},
    onOpenActionCardChannel: (String) -> Unit = {},
    currentServerSlug: String? = null,
    onOpenMarkdownTag: (ThreadMarkdownTag, String) -> Unit,
    onOpenMarkdownUrl: (String) -> Unit,
    onMentionAuthor: (RaftThreadMessageItem) -> Unit = {},
    richContentEnabled: Boolean = true,
    focused: Boolean = false,
    searchMatch: Boolean = false,
    popupOpen: Boolean = false,
    isMessageListScrolling: () -> Boolean = { false },
    memberAvatarResolver: RaftMemberAvatarResolver = EmptyRaftMemberAvatarResolver,
    memberModelLabelResolver: RaftMemberModelLabelResolver = EmptyRaftMemberModelLabelResolver,
    inlineReplyParticipantsById: Map<String, RaftThreadMemberItem> = emptyMap(),
    shareSelectionActive: Boolean = false,
    selectedForShare: Boolean = false,
    onToggleShareSelection: () -> Unit = {},
    translation: ThreadMessageTranslationUi? = null,
    onToggleTranslationOriginal: () -> Unit = {},
    onRetryTranslation: () -> Unit = {},
    onOpenForwardBundle: (String) -> Unit = {},
    longMessageCollapse: RaftLongMessageCollapseController? = null,
    onToggleLongMessage: () -> Unit = {},
    onLongPressInputReady: ((String) -> Unit)? = null,
    longPressGestureInstanceKey: Any = message.id,
    quotedPermalinkLoader: ThreadQuotedPermalinkLoader? = null,
    onShowAuthorDescription: ((String, RaftPopoverAnchor) -> Unit)? = null,
    outerPadding: androidx.compose.ui.unit.Dp = RaftAppSpacing.Sm,
    /** web MessageHoverToolbar: the row's hover toolbar under a mouse or trackpad; null: none. */
    hoverToolbar: ThreadMessageHoverToolbarActions? = null,
    /** A quote reply's card was tapped (web useJumpToQuotedMessage): the quoted message's id; null: the cards don't jump. */
    onJumpToQuotedMessage: ((String) -> Unit)? = null
)
 {
    // RFC 077 PR 2b (web MessageItem `retracted`): a retracted message is one non-interactive
    // record. Its sender, time, delegated mark and thread replies stay; its words, attachments,
    // quotes, reactions, translation and message menu go (the long press opens nothing).
    val retracted = message.isRetracted
    ThreadMessageCardContent(
        modifier = Modifier,
        data =
            ThreadMessageCardData(
                message = if (retracted) message.withoutRetractedContent() else message,
                messageBodyTextStyle = messageBodyTextStyle,
                replyMetadata = replyMetadata,
                hasReplyDraft = hasReplyDraft,
                // A retracted message keeps none of its content (web hides the list with it).
                sourceTasks = if (retracted) emptyList() else sourceTasks,
                actionCard = if (retracted) null else actionCard,
                messageIndex = messageIndex,
                highlightedMentions = highlightedMentions,
                alignOwnMessageRight = alignOwnMessageRight,
                messageAlignmentModeEnabled = messageAlignmentModeEnabled,
                compactConsecutiveMessages = compactConsecutiveMessages,
                suppressRepeatedAuthor = suppressRepeatedAuthor,
                showReplyAction = showReplyAction,
                quickReactionsEnabled = quickReactionsEnabled && !retracted,
                resolvedTaskAssigneeName = resolvedTaskAssigneeName,
                markdownTagContextSignature = markdownTagContextSignature,
                taskOpenEnabled = taskOpenEnabled,
                taskStatusActionsEnabled = taskStatusActionsEnabled,
                showSaveAction = showSaveAction,
                showReactionAddAction = showReactionAddAction && !retracted,
                activeMarkdownTagKey = activeMarkdownTagKey,
                timestampLabel = timestampLabel,
                compactTimestampLabel = compactTimestampLabel,
                actionCardExecutedTimeLabel = actionCardExecutedTimeLabel,
                attachmentOpenStates = attachmentOpenStates,
                pendingActionCardIds = pendingActionCardIds,
                writeActionsEnabled = writeActionsEnabled,
                currentServerSlug = currentServerSlug,
                richContentEnabled = richContentEnabled,
                focused = focused,
                searchMatch = searchMatch,
                popupOpen = popupOpen,
                inlineReplyParticipantsById = inlineReplyParticipantsById,
                shareSelectionActive = shareSelectionActive && !retracted,
                selectedForShare = selectedForShare && !retracted,
                translation = if (retracted) null else translation,
                longMessageCollapse = longMessageCollapse,
                longPressGestureInstanceKey = longPressGestureInstanceKey,
                outerPadding = outerPadding,
                // web MessageItem `actionable`: a retracted message has no toolbar.
                hoverToolbar = if (retracted) null else hoverToolbar,
            ),
        callbacks =
            ThreadMessageCardCallbacks(
                diagnosticsLogger = diagnosticsLogger,
                onOpenSourceTask = onOpenSourceTask,
                agentStatusResolver = agentStatusResolver,
                resolveMarkdownTagLabel = resolveMarkdownTagLabel,
                isMarkdownTagValid = isMarkdownTagValid,
                onShowActions = if (retracted) ({ _ -> }) else onShowActions,
                onLongPressMenuOpened = if (retracted) ({}) else onLongPressMenuOpened,
                onOpenMember = onOpenMember,
                onExternalAuthor = onExternalAuthor,
                onOpenThread = onOpenThread,
                onShowQuickReactions = if (retracted) ({ _ -> }) else onShowQuickReactions,
                onOpenTask = onOpenTask,
                onOpenTaskStatusMenu = onOpenTaskStatusMenu,
                onToggleReaction = onToggleReaction,
                onToggleSave = if (retracted) null else onToggleSave,
                showReactionReactors = showReactionReactors,
                onOpenCommentHost = onOpenCommentHost,
                onOpenAttachment = onOpenAttachment,
                onDownloadAttachment = onDownloadAttachment,
                onRetryLocalSend = onRetryLocalSend,
                onCopyLocalSendFailureDetails = onCopyLocalSendFailureDetails,
                onCommitActionCard = onCommitActionCard,
                onOpenActionCardChannel = onOpenActionCardChannel,
                onOpenMarkdownTag = onOpenMarkdownTag,
                onOpenMarkdownUrl = onOpenMarkdownUrl,
                onMentionAuthor = onMentionAuthor,
                isMessageListScrolling = isMessageListScrolling,
                memberAvatarResolver = memberAvatarResolver,
                memberModelLabelResolver = memberModelLabelResolver,
                onToggleShareSelection = onToggleShareSelection,
                onToggleTranslationOriginal = onToggleTranslationOriginal,
                onRetryTranslation = onRetryTranslation,
                onOpenForwardBundle = onOpenForwardBundle,
                onToggleLongMessage = onToggleLongMessage,
                onLongPressInputReady = onLongPressInputReady,
                quotedPermalinkLoader = quotedPermalinkLoader,
                onShowAuthorDescription = onShowAuthorDescription,
                onJumpToQuotedMessage = onJumpToQuotedMessage,
            ),
    )
}

@Composable
private fun ThreadMessageCardContent(
    modifier: Modifier = Modifier,
    data: ThreadMessageCardData,
    callbacks: ThreadMessageCardCallbacks,
) {
    val message = data.message
    val messageBodyTextStyle = data.messageBodyTextStyle
    val replyMetadata = data.replyMetadata
    val hasReplyDraft = data.hasReplyDraft
    val sourceTasks = data.sourceTasks
    val onOpenSourceTask = callbacks.onOpenSourceTask
    val actionCard = data.actionCard
    val messageIndex = data.messageIndex
    val diagnosticsLogger = callbacks.diagnosticsLogger
    val highlightedMentions = data.highlightedMentions
    val agentStatusResolver = callbacks.agentStatusResolver
    val alignOwnMessageRight = data.alignOwnMessageRight
    val messageAlignmentModeEnabled = data.messageAlignmentModeEnabled
    val compactConsecutiveMessages = data.compactConsecutiveMessages
    val suppressRepeatedAuthor = data.suppressRepeatedAuthor
    val showReplyAction = data.showReplyAction
    val quickReactionsEnabled = data.quickReactionsEnabled
    val resolvedTaskAssigneeName = data.resolvedTaskAssigneeName
    val resolveMarkdownTagLabel = callbacks.resolveMarkdownTagLabel
    val markdownTagContextSignature = data.markdownTagContextSignature
    val taskOpenEnabled = data.taskOpenEnabled
    val taskStatusActionsEnabled = data.taskStatusActionsEnabled
    val showSaveAction = data.showSaveAction
    val showReactionAddAction = data.showReactionAddAction
    val isMarkdownTagValid = callbacks.isMarkdownTagValid
    val activeMarkdownTagKey = data.activeMarkdownTagKey
    val timestampLabel = data.timestampLabel
    val compactTimestampLabel = data.compactTimestampLabel
    val onShowActions = callbacks.onShowActions
    val onLongPressMenuOpened = callbacks.onLongPressMenuOpened
    // The long press that opens a message's menu buzzes (RaftHaptics.longPress, #proj-mobile #50).
    val haptics = rememberRaftHaptics()
    val onOpenMember = callbacks.onOpenMember
    val onExternalAuthor = callbacks.onExternalAuthor
    val onOpenThread = callbacks.onOpenThread
    val onShowQuickReactions = callbacks.onShowQuickReactions
    val onOpenTask = callbacks.onOpenTask
    val onOpenTaskStatusMenu = callbacks.onOpenTaskStatusMenu
    val onToggleReaction = callbacks.onToggleReaction
    val onToggleSave = callbacks.onToggleSave
    val showReactionReactors = callbacks.showReactionReactors
    val onOpenCommentHost = callbacks.onOpenCommentHost
    val onOpenAttachment = callbacks.onOpenAttachment
    val onDownloadAttachment = callbacks.onDownloadAttachment
    val onRetryLocalSend = callbacks.onRetryLocalSend
    val onCopyLocalSendFailureDetails = callbacks.onCopyLocalSendFailureDetails
    val attachmentOpenStates = data.attachmentOpenStates
    val pendingActionCardIds = data.pendingActionCardIds
    val writeActionsEnabled = data.writeActionsEnabled
    val onCommitActionCard = callbacks.onCommitActionCard
    val currentServerSlug = data.currentServerSlug
    val onOpenMarkdownTag = callbacks.onOpenMarkdownTag
    val onOpenMarkdownUrl = callbacks.onOpenMarkdownUrl
    val onMentionAuthor = callbacks.onMentionAuthor
    val richContentEnabled = data.richContentEnabled
    val focused = data.focused
    val searchMatch = data.searchMatch
    val popupOpen = data.popupOpen
    val isMessageListScrolling = callbacks.isMessageListScrolling
    val memberAvatarResolver = callbacks.memberAvatarResolver
    val memberModelLabelResolver = callbacks.memberModelLabelResolver
    val inlineReplyParticipantsById = data.inlineReplyParticipantsById
    val shareSelectionActive = data.shareSelectionActive
    val selectedForShare = data.selectedForShare
    val onToggleShareSelection = callbacks.onToggleShareSelection
    val translation = data.translation
    val onToggleTranslationOriginal = callbacks.onToggleTranslationOriginal
    val onRetryTranslation = callbacks.onRetryTranslation
    val onOpenForwardBundle = callbacks.onOpenForwardBundle
    val longMessageCollapse = data.longMessageCollapse
    val onToggleLongMessage = callbacks.onToggleLongMessage
    val onLongPressInputReady = callbacks.onLongPressInputReady
    val longPressGestureInstanceKey = data.longPressGestureInstanceKey
    val quotedPermalinkLoader = callbacks.quotedPermalinkLoader ?: LocalThreadQuotedPermalinkLoader.current
    val outerPadding = data.outerPadding
    // read_receipts_v0: @mentioned agents' read badges on the viewer's own message (web MentionLink).
    val peerReadReceipts = LocalThreadPeerReadReceipts.current
    val mentionReadStates =
        remember(peerReadReceipts, message.id, message.seq, message.isOwn, message.authorKind) {
            peerReadReceipts?.mentionReadStates(message)
        }
    val localizedAuthorName =
        when {
            message.isOwn && message.authorName == "You" -> stringResource(Res.string.thread_author_you)
            // Task #80: a row whose sender no source could name yet (a seeded stand-in) shows the
            // neutral "Unknown", never a raw id, until the canonical load names it.
            message.authorName.isBlank() -> stringResource(Res.string.thread_comments_unknown_author)
            // An External sender the wire did not name shows the localized "External user".
            message.externalAuthor.isFallbackName -> rememberExternalAuthorFallbackName()
            else -> message.authorName
        }
    // task #392 (port of Web PR #7545): agent model label, resolved live from
    // the members directory — the message wire carries no model. `null` for
    // humans, agents without a model, and while the roster is still loading.
    val authorModelLabel =
        if (message.isThreadAgentAuthor()) {
            rememberRaftMemberModelLabel(memberModelLabelResolver, message.authorId)
        } else {
            null
        }
    // botiverse/slock #11273: an App Bot's row shows the Bot badge (and no model).
    val authorAppBot = rememberRaftMemberAppBot(message.authorId.takeIf { message.isThreadAgentAuthor() })
    // dm:@artin:#120 (web MessageItem, botiverse/slock #14629): the directory's description first,
    // so a live message (`message:new` omits `senderDescription`) shows it without a reload, and
    // this server's Guest label.
    val authorFacts =
        rememberRaftMemberAuthorFacts(
            memberModelLabelResolver,
            message.authorId.takeIf { message.externalAuthor == null && !message.authorKind.equals("system", ignoreCase = true) },
        )
    // Settings > Appearance "Show member descriptions" (web #14653): off, no description here.
    val showSenderDescription = RaftDisplayPreferenceRuntime.observeShowSenderDescription()
    val authorDescription =
        threadAuthorRowDescription(message, authorFacts, showDescription = showSenderDescription)
    val authorGuestLabel =
        if (threadAuthorRowShowsGuest(message, authorFacts)) stringResource(Res.string.channel_member_role_guest) else null
    // Member labels (web botiverse/slock #14535): a sender from another server carries that server's
    // tile on the avatar's top-left corner.
    val authorOriginServers = threadAuthorRowOriginServers(message, authorFacts)
    val pointerSessionKey = LocalThreadMessagePointerSessionKey.current
    val horizontalArrangement =
        if (alignOwnMessageRight) Arrangement.spacedBy(12.dp, Alignment.End) else Arrangement.spacedBy(12.dp)
    val contentAlignment = if (alignOwnMessageRight) Alignment.End else Alignment.Start
    // task #278: in the left/right conversation layout both sides are capped to the same
    // fraction of the row's content width, so the other party's messages leave the right
    // side free just as own messages leave the left. Outside that mode every message keeps
    // the full width, as before.
    val messageMaxWidthFraction = if (messageAlignmentModeEnabled) 0.82f else 1f
    val geometryTints = LocalThreadMessageGeometryTints.current
    val agentStatus =
        if (message.showsAgentStatusIndicator()) {
            resolveThreadAgentStatusIndicatorState(
                message = message,
                canonicalState = rememberRaftAgentStatusIndicatorState(agentStatusResolver, message.authorId)
            )
        } else {
            null
        }
    val shareCaptureActive = LocalThreadShareCaptureActive.current
    val quotedPermalinkState = remember(message.id, message.body) { mutableStateOf<ThreadQuotedPermalinkUiState?>(null) }
    var quotedPermalinkTarget by remember(message.id, message.body) { mutableStateOf<ParsedRaftPermalink?>(null) }
    LaunchedEffect(message.id, message.body, currentServerSlug, quotedPermalinkLoader) {
        quotedPermalinkState.value = null
        quotedPermalinkTarget = null
        val loader = quotedPermalinkLoader ?: return@LaunchedEffect
        val target =
            extractFirstQuotedRaftPermalink(
                content = message.body,
                hostnames = raftPermalinkHostnames(RaftClientConfigAppHosts.current),
                currentServerSlug = currentServerSlug
            )?.parsed
        if (target == null) {
            // task #1153: quoted permalink cards used to fail silently. This
            // event only proves extraction returned null — it does not
            // distinguish "no quoted permalink" from external links, code /
            // Markdown links, or slug mismatch. serverSlug=null is a useful
            // signal, but a non-null slug does not prove a slug mismatch.
            if (message.body.contains("http")) {
                diagnosticsLogger(
                    "thread_quoted_permalink_skip messageId=${message.id} " +
                        "serverSlug=${currentServerSlug ?: "null"}"
                )
            }
            return@LaunchedEffect
        }
        quotedPermalinkTarget = target
        quotedPermalinkState.value = ThreadQuotedPermalinkUiState.Loading
        quotedPermalinkState.value =
            loader(target)?.let { item ->
                if (item.item.isRetracted) ThreadQuotedPermalinkUiState.Retracted else ThreadQuotedPermalinkUiState.Loaded(item)
            }
                ?: ThreadQuotedPermalinkUiState.Unavailable
    }
    val jointAuthor =
        rememberRaftJointActor(
            memberAvatarResolver = memberAvatarResolver,
            kind = message.authorKind.projectedMemberKindOrNull(),
            memberId = message.authorId,
            enabled = memberAvatarResolver.jointSurface && message.externalAuthor == null
        )
    val avatarWireValue =
        resolveThreadRowAvatarWireValue(
            rememberRaftMemberAvatarWireValue(memberAvatarResolver, message.authorId)
        ) ?: jointAuthor?.renderedAvatarWireValue()
    val authorAvatarPending =
        message.externalAuthor == null &&
            avatarWireValue.isNullOrBlank() &&
            rememberRaftMemberAvatarPending(
                memberAvatarResolver = memberAvatarResolver,
                kind = message.authorKind.projectedMemberKindOrNull(),
                memberId = message.authorId,
                jointSurface = memberAvatarResolver.jointSurface,
            )
    val avatarDiagnostics =
        remember(message.id, message.authorId, diagnosticsLogger) {
            raftAvatarRowDiagnostics(
                messageId = message.id,
                authorId = message.authorId,
                logger = diagnosticsLogger
            )
        }
    LaunchedEffect(avatarDiagnostics, avatarWireValue) {
        avatarDiagnostics.logger(
            "thread_avatar_row messageHash=${avatarDiagnostics.messageHash} " +
                "authorHash=${avatarDiagnostics.authorHash} " +
                "source=${avatarWireValue.raftAvatarDiagnosticsSource()} authority=member_profile"
        )
    }
    val authorGestureIdentity =
        remember(message.id, message.authorId, message.authorKind) {
            message.threadAuthorGestureIdentity()
        }
    val authorProfileIdentity =
        remember(message.id, message.authorId, message.authorKind, jointAuthor?.homeServerId) {
            message.memberDetailIdentity()?.withJointAuthorHomeServer(jointAuthor)
        }
    val longPressGestureOwner =
        rememberThreadMessageLongPressGestureOwner(
            instanceKey = longPressGestureInstanceKey,
            messageId = message.id
        )
    val tapMovementGate = longPressGestureOwner.tapMovementGate
    val codeBlockTapObserver: () -> Unit =
        remember(tapMovementGate) { { tapMovementGate.rejectChildAction("code_block") } }
    fun openAuthorProfile(source: String) {
        if (!message.canOpenThreadAuthorProfile(shareSelectionActive)) return
        tapMovementGate.rejectChildAction("author_profile")
        diagnosticsLogger(
            "thread_member_profile_tap source=$source messageId=${message.id} " +
                "authorId=${message.authorId} authorKind=${message.authorKind} " +
                "targetMemberId=${authorProfileIdentity?.memberId?.value.orEmpty()}"
        )
        when (val action = message.threadAuthorTapAction(authorProfileIdentity)) {
            ThreadAuthorTapAction.ExternalHint -> onExternalAuthor(message)
            is ThreadAuthorTapAction.OpenProfile -> onOpenMember(action.identity)
            ThreadAuthorTapAction.None -> Unit
        }
    }
    // The author's profile card under a mouse or trackpad pointer on the avatar (web MessageItem's
    // avatar hover card). It has no buttons: the avatar's own click opens the profile and closes it.
    val memberPreviewCardSource = LocalRaftMemberPreviewCardSource.current
    val externalAuthor = message.externalAuthor
    val externalAuthorFallbackName = rememberExternalAuthorFallbackName()
    val externalAuthorName = externalAuthor?.let { externalAuthorShownName(it, it.displayName, externalAuthorFallbackName) }.orEmpty()
    val authorPreviewCard: (@Composable ColumnScope.(RaftPreviewCardState) -> Unit)? =
        if (memberPreviewCardSource != null && authorProfileIdentity != null && !shareSelectionActive) {
            { _ ->
                RaftMemberPreviewCardContent(
                    source = memberPreviewCardSource,
                    identity = authorProfileIdentity,
                    fallbackHandle = null,
                )
            }
        } else if (externalAuthor != null && !shareSelectionActive) {
            // A Slack-bridged author: who they are outside Raft (web ExternalIdentityPreviewCard).
            { _ -> RaftExternalIdentityPreviewCardContent(externalAuthor, externalAuthorName) }
        } else {
            null
        }
    val authorBadgeLabel = message.threadAuthorBadgeLabel()
    // raft-ui located (0.22.1 #719): info-soft at once, held 1.2s, faded out by 2s.
    val locatedStrength = raftLocatedStrength(active = focused)
    val pressed = longPressGestureOwner.pressed
    val pressRippleOrigin = longPressGestureOwner.pressRippleOrigin
    LaunchedEffect(longPressGestureOwner, pressed, pressRippleOrigin) {
        if (!pressed && pressRippleOrigin != null) {
            val rippleLease = longPressGestureOwner.captureActiveLease()
            delay(ThreadMessageRippleFadeMs.toLong() + ThreadMessageRippleResetMarginMs)
            if (rippleLease != null) {
                longPressGestureOwner.clearReleasedPressRipple(rippleLease, pressRippleOrigin)
            }
        }
    }
    val showAuthorChrome = !compactConsecutiveMessages || !suppressRepeatedAuthor
    val showTimestampInHeader = !compactConsecutiveMessages || showAuthorChrome
    val showTimestampInAvatarSlot =
        compactConsecutiveMessages && !showAuthorChrome && pressed && compactTimestampLabel.isNotBlank()
    val compactTimestampGutter = compactConsecutiveMessages
    // Screen readers hear every row's time: a compact row without its header has no visible one
    // (only while pressed), so its avatar slot carries it, as the header would show it.
    val rowTimeContext = currentRaftDateTimeContext(null)
    val rowMessageTime = rememberRaftMessageTimeFormat()
    val rowTimeAccessibilityLabel =
        if (showTimestampInHeader) {
            null
        } else {
            threadRowTimeAccessibilityLabel(timestampLabel, compactTimestampLabel, rowTimeContext, rowMessageTime)
        }
    val messageHasAttachments = message.attachments.isNotEmpty()
    val hasThreadAttachments = richContentEnabled && messageHasAttachments
    // The body always renders in full; a long one is clipped visually by RaftLongMessageCollapsibleBody.
    val renderedBody = translation?.displayBody ?: message.body
    val bodyForRender = renderedBody
    val showSavedBadge =
        shouldShowThreadMessageSavedBadge(
            showSaveAction = showSaveAction,
            richContentEnabled = richContentEnabled,
            hasLocalSendState = message.hasLocalSendState(),
            isSaved = message.isSaved
        )
    val reserveInlineTrailingSpace = richContentEnabled && message.isSendingLocalSend() && !alignOwnMessageRight
    // artin: the sending spinner (row and image overlay) only draws once a send has been in flight
    // for 5 s; its space stays reserved meanwhile, so it appears without a layout jump.
    val sendingIndicatorVisible =
        rememberDelayedSendingVisible(localId = message.id, isSending = message.isSendingLocalSend())
    val hasPendingReaction =
        LocalThreadPendingReactionVisuals.current.any { (key, visual) ->
            key.messageId == message.id && visual.reaction.count > 0
        }
    val inlineReplyPresentation =
        remember(message.inlineReplies, replyMetadata, hasReplyDraft, showReplyAction) {
            threadInlineReplyPresentationForMessage(
                model = message.inlineReplies,
                replyMetadata = replyMetadata,
                hasDraft = hasReplyDraft,
                showReplyAction = showReplyAction,
            )
        }
    val repliesBadge =
        threadRepliesBadgeSpec(
            replyMetadata = replyMetadata,
            hasDraft = hasReplyDraft,
            showReplyAction = showReplyAction,
            hasInlineReplies = inlineReplyPresentation != null,
        )
    val hasFooterMeta =
        richContentEnabled &&
            !message.hasLocalSendState() &&
            message.hasThreadFooterMeta(
                showSavedBadge = showSavedBadge,
                hasPendingReaction = hasPendingReaction,
                showRepliesBadge = repliesBadge != null
            )
    val messagePerformanceKey =
        remember(message.id, messageIndex) {
            threadMessagePerformanceKey(message.id, messageIndex)
        }
    val inPlaceSelection =
        rememberThreadInPlaceSelectionController(
            messageId = message.id,
            enabled =
                !shareSelectionActive && !message.isRetracted &&
                    threadMessageUsesFullRowLongPressActions(shareSelectionActive)
        )
    // stdrc, 2026-10-29 (#proj-raft-ui:911ccc08): a single tap opens the message's thread, with
    // or without replies; a double tap shows its quick reaction bar where it can be reacted to.
    // Finger taps only: a mouse, trackpad or stylus click leaves the row alone and selects text
    // ([threadMessageRowTapGesturesFor], checked per gesture in threadMessageLongPressActions).
    val rowTapAction: (() -> Unit)? =
        if (
            threadMessageTapOpensThread(
                showReplyAction = showReplyAction,
                shareSelectionActive = shareSelectionActive,
                hasLocalSendState = message.hasLocalSendState()
            )
        ) {
            {
                diagnosticsLogger(
                    "thread_message_row_tap_open messageId=${message.id} replyCount=${replyMetadata.replyCount}"
                )
                onOpenThread(message.id)
            }
        } else {
            null
        }
    val rowDoubleTapAction: ((RaftPopoverAnchor) -> Unit)? =
        if (
            threadMessageDoubleTapShowsReactions(
                quickReactions = quickReactionsEnabled,
                shareSelectionActive = shareSelectionActive,
                hasLocalSendState = message.hasLocalSendState()
            )
        ) {
            { anchor -> onShowQuickReactions(anchor) }
        } else {
            null
        }
    // A tap whose gesture started while a selection was showing (here or on another row) only
    // dismisses it. The flag is captured at DOWN because the selection is already gone by onTap.
    val keyboardTapDismissal = LocalThreadKeyboardTapDismissal.current
    val consumeSelectionDismissalTap: (() -> Boolean)? =
        if (rowTapAction == null && rowDoubleTapAction == null) {
            null
        } else {
            {
                val selectionShowingAtDown = inPlaceSelection?.dismissalGestureInFlight == true
                inPlaceSelection?.dismissalGestureInFlight = false
                when (threadInPlaceSelectionTapDecision(selectionShowingAtDown)) {
                    ThreadInPlaceSelectionTapDecision.DismissSelectionOnly -> {
                        diagnosticsLogger("thread_in_place_selection_tap_clear messageId=${message.id}")
                        inPlaceSelection?.clear()
                        true
                    }
                    ThreadInPlaceSelectionTapDecision.RunRowAction -> {
                        val keyboardClosed = keyboardTapDismissal?.dismissIfUp() == true
                        if (keyboardClosed) diagnosticsLogger("thread_message_row_tap_keyboard_only messageId=${message.id}")
                        keyboardClosed
                    }
                }
            }
        }
    val openThreadAccessibilityLabel = stringResource(Res.string.thread_message_a11y_open_thread)
    val addReactionAccessibilityLabel = stringResource(Res.string.thread_message_a11y_add_reaction)
    // Screen readers: the row's tap and double tap as named actions (a retracted row has neither).
    val rowAccessibilityActions: List<CustomAccessibilityAction> =
        if (message.isRetracted) {
            emptyList()
        } else {
            buildList<CustomAccessibilityAction> {
                if (rowTapAction != null) {
                    add(
                        CustomAccessibilityAction(openThreadAccessibilityLabel) {
                            diagnosticsLogger("thread_message_a11y_open_thread messageId=${message.id}")
                            onOpenThread(message.id)
                            true
                        }
                    )
                }
                if (rowDoubleTapAction != null) {
                    add(
                        CustomAccessibilityAction(addReactionAccessibilityLabel) {
                            val anchor =
                                longPressGestureOwner.rootAnchor.resolve(RaftPopoverLocalPoint.BottomStart)
                            if (anchor != null) {
                                diagnosticsLogger("thread_message_a11y_reactions messageId=${message.id}")
                                onShowQuickReactions(anchor)
                            }
                            anchor != null
                        }
                    )
                }
            }
        }
    val inPlaceSelectionHost = LocalThreadInPlaceTextSelectionHost.current
    // artin, 1.24: a finger's long press on a link in the body opens this row's menu too, with the
    // link on top (RaftMarkdownLinkLongPress.kt); none where the row has no long-press menu.
    val linkLongPress =
        if (!message.isRetracted && threadMessageUsesFullRowLongPressActions(shareSelectionActive)) {
            remember(message.id, longPressGestureOwner, inPlaceSelectionHost, inPlaceSelection) {
                threadMessageLinkLongPressHandler(message.id, longPressGestureOwner, inPlaceSelectionHost, inPlaceSelection)
            }
        } else {
            null
        }
    // raft-ui MessageItem `not-data-located:hover:[--mi-row-fill:var(--fill-subtle)]`: a mouse or
    // trackpad over the row lays the subtle tier; a finger never does. A row that already has a
    // fill (checked, its menu open, the current search match, located) keeps it.
    val rowHoverSource = remember { MutableInteractionSource() }
    val rowHoverEnabled =
        !(shareSelectionActive && selectedForShare) && !popupOpen && !searchMatch && locatedStrength <= 0f
    val rowHover = rememberRaftStateOverlay(rowHoverSource, RaftHoverTier.Subtle, enabled = rowHoverEnabled)
    // web MessageHoverToolbar: none while messages are being selected (select mode).
    val hoverToolbar = data.hoverToolbar?.takeIf { !shareSelectionActive }
    // A mouse or trackpad over the row, open menu or not (the row's hover fill pauses under one).
    var rowPointerOver by remember { mutableStateOf(false) }
    // Whether the hover toolbar shows: the header fades its end under it (raft-ui MessageItemHeader).
    var hoverToolbarShown by remember { mutableStateOf(false) }
    val headerMaskWidth =
        hoverToolbar?.takeIf { hoverToolbarShown }?.let { RaftMessageItemToolbarDefaults.width(it.buttonCount) }
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(RaftColor.Canvas)
                .raftPointerHover(rowHoverSource)
                .raftPointerHoverPosition(enabled = hoverToolbar != null) { rowPointerOver = it != null }
                .then(
                    if (inPlaceSelection != null) {
                        Modifier.onGloballyPositioned { inPlaceSelection.rowCoordinates = it }
                    } else {
                        Modifier
                    }
                )
                .then(
                    if (rowAccessibilityActions.isNotEmpty()) {
                        Modifier.semantics { customActions = rowAccessibilityActions }
                    } else {
                        Modifier
                    }
                )
                .then(
                    // RFC 077 PR 2b: a retracted message has no long-press gesture at all.
                    if (!message.isRetracted && threadMessageUsesFullRowLongPressActions(shareSelectionActive)) {
                        Modifier.threadMessageLongPressActions(
                            messageId = message.id,
                            pointerSessionKey = pointerSessionKey,
                            gestureOwner = longPressGestureOwner,
                            isScrollInProgress = isMessageListScrolling,
                            diagnosticsLogger = diagnosticsLogger,
                            onTap = rowTapAction,
                            onDoubleTap = rowDoubleTapAction,
                            consumeSelectionDismissalTap = consumeSelectionDismissalTap,
                            onTapRejected = { decision ->
                                diagnosticsLogger(
                                    "thread_message_row_tap_rejected messageId=${message.id} " +
                                        "reason=${decision.reason} movement=${decision.maxDistance} " +
                                        "touchSlop=${decision.touchSlop} pointers=${decision.pointerCount}"
                                )
                            },
                            onShowActions = { anchor ->
                                haptics.longPress()
                                onShowActions(anchor)
                                onLongPressMenuOpened()
                            },
                            // A right-click opens the same menu at the pointer: no haptic, and not
                            // the long-press menu for analytics (there is no context-menu surface).
                            // Text selected in this message is offered as the menu's first "Copy".
                            onContextClick = { anchor ->
                                val selectionCopyText =
                                    inPlaceSelection?.let { selection ->
                                        threadContextMenuSelectionCopyText(
                                            selectedTexts = selection.selectedTexts(),
                                            selectableTexts = selection.selectableTexts(),
                                            fullMessageText =
                                                if (bodyForRender == message.body) {
                                                    threadMessageSelectionSourceText(message)
                                                } else {
                                                    null
                                                }
                                        )
                                    }
                                inPlaceSelectionHost?.contextMenuSelectionCopy =
                                    selectionCopyText?.let { text ->
                                        ThreadContextMenuSelectionCopy(messageId = message.id, anchor = anchor, text = text)
                                    }
                                diagnosticsLogger(
                                    "thread_message_context_menu messageId=${message.id} " +
                                        "selectionCopy=${selectionCopyText != null}"
                                )
                                onShowActions(anchor)
                            },
                            onInputReady = onLongPressInputReady,
                            deferLongPressAt =
                                inPlaceSelection?.let { selection ->
                                    { offset -> selection.bodyContainsRowOffset(offset) }
                                },
                            isInPlaceSelectionTaken =
                                inPlaceSelection?.let { selection ->
                                    { selection.hasSelection || selection.linkLongPressOwnsGesture }
                                },
                            onPointerDown =
                                inPlaceSelection?.let { selection ->
                                    { offset, pointerType ->
                                        selection.lastPointerDownFromTouch = pointerType.raftIsTouchLike()
                                        selection.linkLongPressOwnsGesture = false
                                        selection.dismissalGestureInFlight =
                                            inPlaceSelectionHost?.onRowPointerDown(
                                                messageId = message.id,
                                                insideBody = selection.bodyContainsRowOffset(offset)
                                            ) == true
                                    }
                                }
                        )
                    } else {
                        Modifier
                    }
                )
    ) {
        // raft-ui MessageItem `--mi-row-fill`: one value for the row wash and for the parts that
        // paint the row's surface around themselves (the assistant face's ring).
        val rowFill =
            // raft-ui MessageItem: a checked multi-select row washes info-soft.
            if (shareSelectionActive && selectedForShare) {
                RaftColor.InfoSoft
            } else if (popupOpen) {
                // raft-ui 0.13 MessageItem `data-popup-open:bg-fill-row-open`: the row
                // under an open context menu keeps one step past the hover.
                RaftTheme.colors.fillSubtle
            } else if (searchMatch) {
                // raft-ui: the current match of a search inside a page is a selection; it
                // stays info-soft while it is current, and doesn't fade.
                RaftColor.InfoSoft
            } else {
                threadMessageLocatedFill(locatedStrength)
            }
        // The delegated face's ring paints the row's surface, hover included (read only then, so
        // other rows don't recompose as the hover fades).
        val rowSurface =
            if (message.delegatedBy != null) {
                rowHover.value.compositeOver(rowFill.compositeOver(RaftColor.Canvas))
            } else {
                rowFill.compositeOver(RaftColor.Canvas)
            }
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    // raft-ui 0.61 `MessageItem` root (timeline rhythm): a turn's first row
                    // carries `timeline-turn` (8) as a transparent top border the wash doesn't
                    // paint (`bg-clip-padding`); a continuation row (`compact`) has none. The share
                    // image replaces it with its own 2dp row frame (web selectScreenshot.ts sets the
                    // clone's `border: 2px solid transparent`), so a captured row has no band.
                    .padding(top = if (showAuthorChrome && !shareCaptureActive) ThreadMessageTimelineTurn else 0.dp)
                    // The row wash spans the whole row; the content sits at px-2
                    // `py-timeline-row` (4) inside it.
                    .background(rowFill)
                    .raftStateOverlay(rowHover, RectangleShape)
                    // The wash runs edge to edge; the message stays out of the safe area.
                    .raftSafeAreaHorizontalPadding()
                    .padding(horizontal = outerPadding, vertical = ThreadMessageTimelineRowInset),
            verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Xs)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = horizontalArrangement
            ) {
                if (shareSelectionActive && !alignOwnMessageRight) {
                    ThreadMessageSelectionToggle(
                        checked = selectedForShare,
                        onClick = onToggleShareSelection,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                if (!alignOwnMessageRight) {
                    ThreadMessageAvatarSlot(
                        interactionIdentity = authorGestureIdentity,
                        showAvatar = showAuthorChrome,
                        showTimestamp = showTimestampInAvatarSlot,
                        timestampAccessibilityLabel = rowTimeAccessibilityLabel,
                        compactTimestampGutter = compactTimestampGutter,
                        timestampLabel = compactTimestampLabel,
                        timestampAlignment = Alignment.TopStart,
                        timestampTextAlign = TextAlign.Start,
                        avatarLabel = message.threadAvatarName(),
                        avatarKind = message.threadAvatarKind(),
                        avatarWireValue =
                            if (message.externalAuthor != null) message.avatarWireValue else avatarWireValue,
                        avatarPending = authorAvatarPending,
                        avatarDiagnostics = avatarDiagnostics,
                        cacheSubject =
                            raftMemberAvatarCacheSubject(
                                message.authorKind.projectedMemberKindOrNull(),
                                message.authorId
                            ),
                        status = agentStatus,
                        // web MessageItem: only an archived agent's face is dimmed (`grayscale opacity-dimmed`);
                        // a person who left or was removed keeps theirs.
                        muted = message.isDeletedThreadAuthor(),
                        delegatedBy = message.delegatedBy,
                        delegatedSurface = rowSurface,
                        originServers = authorOriginServers,
                        // Selecting messages: the avatar toggles the row like its body does, so the
                        // strip it shares with the checkbox's touch target selects too (Lagoon,
                        // device check of #4091); no profile, mention or hover card meanwhile.
                        onClick = if (shareSelectionActive) onToggleShareSelection else { { openAuthorProfile("avatar") } },
                        onLongPress = if (shareSelectionActive) ({}) else { { onMentionAuthor(message) } },
                        profileActions = !shareSelectionActive,
                        previewCard = authorPreviewCard.takeUnless { shareSelectionActive },
                        // Lagoon 11-02: a finger gets what a hover gets; the card replaces the hint toast.
                        previewCardPinOnClick = message.externalAuthor != null && !shareSelectionActive,
                    )
                }
                // Raw clickable on purpose: while selecting, the message body is a tap target
                // of the message row, which shows selection through its checkbox and row fill,
                // not a press wash (message rows keep their own tap, long press and selection).
                val contentModifier =
                    if (shareSelectionActive) {
                        Modifier
                            .weight(1f)
                            .clickable(onClick = onToggleShareSelection)
                    } else {
                        Modifier.weight(1f)
                    }
                Box(
                    modifier = contentModifier
                ) {
                    Column(
                        modifier =
                            Modifier
                                .then(if (showAuthorChrome) Modifier.threadMessageHeaderBodyGapTrim() else Modifier)
                                .fillMaxWidth(),
                        // raft-ui MessageItemHeader `mb-px`: the body starts 1px under the header;
                        // the parts after the body keep the 4dp step.
                        verticalArrangement =
                            if (showAuthorChrome) ThreadMessageHeaderFirstArrangement else Arrangement.spacedBy(RaftAppSpacing.Xs),
                        horizontalAlignment = contentAlignment
                    ) {
                        if (showAuthorChrome) {
                            Box(
                                modifier =
                                    Modifier
                                        .threadMessageContentWidth(messageMaxWidthFraction)
                                        .padding(end = if (reserveInlineTrailingSpace) 30.dp else 0.dp)
                                        .background(Color.Transparent)
                            ) {
                                // Web MessageItem: the sender's name mentions them in the composer
                                // (tooltip "Mention @name"); the avatar opens the profile (artin
                                // 2026-11-02). External, deleted and removed authors keep the profile.
                                val nameMentionHandle =
                                    message.authorHandle.trim().removePrefix("@").takeIf {
                                        it.isNotBlank() && !shareSelectionActive && message.externalAuthor == null &&
                                            message.threadAuthorBadgeLabel() == null
                                    }
                                // raft-ui 0.17 MessageItemHeader `items-baseline`: name, meta,
                                // subtitle and time share the first baseline.
                                ThreadMessageHeaderRow(
                                    alignEnd = alignOwnMessageRight,
                                    modifier = Modifier.fillMaxWidth().raftMessageItemToolbarHeaderMask(headerMaskWidth),
                                    time = { timeModifier ->
                                        if (showTimestampInHeader) {
                                            ThreadMessageTimestampLabel(
                                                timestampLabel = timestampLabel,
                                                modifier = timeModifier,
                                                onToggle = {
                                                    tapMovementGate.rejectChildAction("timestamp")
                                                }
                                            )
                                        }
                                    },
                                    meta = {
                                        authorAppBot?.let { appBot ->
                                            RaftAppBotBadge(identity = appBot, botName = localizedAuthorName)
                                        }
                                        // task #392 (port of Web PR #7545): agent
                                        // model name after the name, before
                                        // badge/subtitle; raft-ui MessageItemMeta.
                                        authorModelLabel?.takeIf { authorAppBot == null }?.let { modelLabel ->
                                            Text(
                                                text = modelLabel,
                                                style =
                                                    RaftType.Caption.copy(
                                                        fontWeight = FontWeight.Medium,
                                                        color = RaftColor.ForegroundHint,
                                                    ),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.alignByBaseline(),
                                            )
                                        }
                                        authorBadgeLabel?.let { label ->
                                            ThreadMessageAuthorBadge(label)
                                        }
                                        // Web: this server's Guest, then the description. Same meta
                                        // style; the Guest word keeps its room, the description
                                        // gives way first.
                                        authorGuestLabel?.let { guest ->
                                            ThreadMessageAuthorSubtitle(
                                                description = guest,
                                                modifier = Modifier.alignByBaseline()
                                            )
                                        }
                                        authorDescription?.let { description ->
                                            val subtitleAnchor =
                                                remember(message.id) {
                                                    RaftLiveRootAnchorRef(
                                                        identity = "thread_author_subtitle:${message.id}"
                                                    )
                                                }
                                            val onShowDescription = callbacks.onShowAuthorDescription
                                            val handleSubtitleClick: (() -> Unit)? =
                                                if (onShowDescription != null && !shareSelectionActive && description.isNotBlank()) {
                                                    {
                                                        tapMovementGate.rejectChildAction("author_subtitle")
                                                        diagnosticsLogger("thread_author_subtitle_tap messageId=${message.id}")
                                                        subtitleAnchor
                                                            .resolve(RaftPopoverLocalPoint.BottomStart)
                                                            ?.let { anchor ->
                                                                onShowDescription(description, anchor)
                                                            }
                                                    }
                                                } else {
                                                    null
                                                }
                                            ThreadMessageAuthorSubtitle(
                                                description = description,
                                                rootAnchor = subtitleAnchor,
                                                onClick = handleSubtitleClick,
                                                modifier = Modifier.weight(1f, fill = false).alignByBaseline()
                                            )
                                        }
                                        // RFC 077: "sent by Friday" after the sender cluster, before
                                        // the time (web MessageItem DelegatedByLabel).
                                        message.delegatedBy?.let { delegatedBy ->
                                            ThreadMessageDelegatedByLabel(
                                                delegatedBy = delegatedBy,
                                                placement = DelegatedLabelPlacement.Header,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                        }
                                    }
                                ) { inCluster, nameText ->
                                    // Web MessageItem sender context menu: a long press (a finger's
                                    // right click) on the name offers Copy name / Copy handle.
                                    var senderMenuOpen by remember(message.id) { mutableStateOf(false) }
                                    val senderMenuItems =
                                        remember(localizedAuthorName, nameMentionHandle) {
                                            threadSenderMenuItems(localizedAuthorName, nameMentionHandle)
                                        }
                                    val authorNameText: @Composable (Modifier) -> Unit = { nameModifier ->
                                        Text(
                                            text = localizedAuthorName,
                                            // raft-ui `MessageItemSender`: text-subhead (15/20) bold.
                                            style = RaftType.Button.withRaftFontWeight(FontWeight.Bold).copy(color = RaftColor.TextBlack, fontSize = 15.sp, lineHeight = 20.sp).withRaftUserContentLocale(),
                                            color = RaftColor.TextBlack,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier =
                                                nameModifier
                                                    .raftClickable(
                                                        onClick = {
                                                            if (nameMentionHandle != null) onMentionAuthor(message) else openAuthorProfile("author_name")
                                                        },
                                                        shape = RoundedCornerShape(RaftAppRadius.Sm),
                                                        tier = RaftHoverTier.Subtle,
                                                        onLongClick =
                                                            if (shareSelectionActive || senderMenuItems.isEmpty()) {
                                                                null
                                                            } else {
                                                                {
                                                                    diagnosticsLogger("thread_sender_menu_open messageId=${message.id}")
                                                                    senderMenuOpen = true
                                                                }
                                                            },
                                                    )
                                        )
                                        ThreadMessageSenderMenu(
                                            expanded = senderMenuOpen,
                                            items = senderMenuItems,
                                            onDismiss = { senderMenuOpen = false },
                                            diagnosticsLogger = diagnosticsLogger,
                                        )
                                    }
                                    if (nameMentionHandle != null) {
                                        RaftTooltip(
                                            text = stringResource(Res.string.message_mention_sender, nameMentionHandle),
                                            modifier = inCluster,
                                            openOnLongPress = false,
                                        ) { authorNameText(nameText) }
                                    } else {
                                        authorNameText(inCluster.then(nameText))
                                    }
                                }
                            }
                        }
                        val forwardBundle = message.forwardBundle
                        // RFC 082: a card message's card, else its text below.
                        val messageCard = if (richContentEnabled && actionCard == null) rememberThreadMessageCard(message.metadata) else null
                        if (richContentEnabled && actionCard != null) {
                            // A prepared card past its `expiresAt` by the live clock draws as expired: no button,
                            // no dialog (the server's 409 stays final).
                            val actionCardNowMillis = currentRaftTimeContextProvider?.current?.nowEpochMillis ?: defaultNowMillis()
                            val shownActionCard = actionCard.expiredAsOf(actionCardNowMillis)
                            // A4: the spec card; for every card without a spec, the typed card.
                            val specHost = LocalThreadActionCardSpecHost.current
                            // A card a phone shows as one line (plan:trial, web mobile): no card at all.
                            val mobileLine = shownActionCard.actionCardMobileLine()
                            val viewerServerId = LocalThreadViewerServerId.current
                            val feedbackTicketVisible =
                                shownActionCard.showsFeedbackTicket(specHost.viewerUserId, specHost.clientOs)
                            val specCard =
                                remember(shownActionCard, specHost.capabilities, feedbackTicketVisible) {
                                    if (mobileLine != null) null else shownActionCard.actionCardSpecToRender(specHost.capabilities, feedbackTicketVisible)
                                }
                            if (mobileLine != null) {
                                ThreadActionCardMobileLineView(
                                    line = mobileLine,
                                    textStyle = messageBodyTextStyle,
                                    modifier = Modifier.threadMessageContentWidth(messageMaxWidthFraction),
                                )
                            } else if (specCard != null) {
                                ThreadActionCardSpecView(
                                    messageId = message.id,
                                    card = shownActionCard,
                                    spec = specCard,
                                    commitEnabled = writeActionsEnabled,
                                    typedPending = message.id in pendingActionCardIds,
                                    executedTimeLabel = data.actionCardExecutedTimeLabel,
                                    modifier = Modifier.threadMessageContentWidth(messageMaxWidthFraction),
                                    askerName = localizedAuthorName,
                                    onAction = { elementId ->
                                        when (val press = threadActionCardSpecPress(shownActionCard, specCard, elementId, viewerServerId)) {
                                            ThreadActionCardSpecPress.Ignore -> Unit
                                            ThreadActionCardSpecPress.Typed -> {
                                                specHost.onIntent(ThreadActionCardSpecIntent.TypedPress(message.id, elementId))
                                                onCommitActionCard(message.id)
                                            }
                                            ThreadActionCardSpecPress.Store ->
                                                specHost.onIntent(ThreadActionCardSpecIntent.Press(message.id, elementId, shownActionCard, specCard))
                                            ThreadActionCardSpecPress.OpenInWeb -> specHost.openInWeb(message.id)
                                            ThreadActionCardSpecPress.ViewPlan -> specHost.openPlan()
                                            is ThreadActionCardSpecPress.OpenFeedbackTicket -> specHost.openFeedbackTicket(press.ticketId)
                                            is ThreadActionCardSpecPress.OpenUrl -> onOpenMarkdownUrl(press.url)
                                            is ThreadActionCardSpecPress.Navigate ->
                                                openActionCardSpecTarget(
                                                    target = press.target,
                                                    cardSourceServerId = shownActionCard.sourceServerId,
                                                    viewerServerId = viewerServerId,
                                                    serverSlug = currentServerSlug,
                                                    isDirectMessageChannel = specHost.isDirectMessageChannel,
                                                    onOpenUrl = onOpenMarkdownUrl,
                                                    onOpenChannel = callbacks.onOpenActionCardChannel,
                                                    onOpenMember = callbacks.onOpenMember,
                                                    onOpenTask = callbacks.onOpenTask,
                                                )
                                        }
                                    },
                                )
                            } else {
                                ThreadActionCardView(
                                    card = shownActionCard,
                                    pending = message.id in pendingActionCardIds,
                                    commitEnabled = writeActionsEnabled,
                                    executedTimeLabel = data.actionCardExecutedTimeLabel,
                                    modifier = Modifier.threadMessageContentWidth(messageMaxWidthFraction),
                                    onOpenInWeb = { specHost.openUnsupportedInWeb(message.id, shownActionCard.type) },
                                    onCommit = { onCommitActionCard(message.id) }
                                )
                            }
                        } else if (messageCard != null) {
                            RaftMessageCardView(
                                card = messageCard,
                                onOpenUrl = onOpenMarkdownUrl,
                                modifier = Modifier.threadMessageContentWidth(messageMaxWidthFraction),
                                // RFC 086: a message already sent, whose acting buttons are read for this viewer.
                                messageId = message.id.takeIf { message.pendingSendContext == null },
                            )
                        } else if (richContentEnabled && forwardBundle != null) {
                            // Web MessageItem parity: the server stores the forward
                            // note as the message content; render it above the card
                            // unless it is the generated placeholder itself.
                            val forwardNoteBody = threadForwardNoteBody(message.body, forwardBundle.displayCount())
                            if (forwardNoteBody != null) {
                                CompositionLocalProvider(LocalRaftMarkdownCodeTapObserver provides codeBlockTapObserver) {
                                    RaftMarkdownBody(
                                        body = forwardNoteBody,
                                        textStyle = messageBodyTextStyle,
                                        // The note is this message's own text: its refs read the
                                        // conversation's tag context like the body (Web MessageItem).
                                        isTagValid = isMarkdownTagValid,
                                        resolveTagLabel = resolveMarkdownTagLabel,
                                        onOpenTagAction = { tag, actionId ->
                                            tapMovementGate.rejectChildAction("markdown_tag")
                                            onOpenMarkdownTag(tag, actionId)
                                        },
                                        onOpenUrl = onOpenMarkdownUrl,
                                        modifier = Modifier.threadMessageContentWidth(messageMaxWidthFraction)
                                    )
                                }
                            }
                            ThreadForwardBundleCard(
                                bundle = forwardBundle,
                                messageBodyTextStyle = messageBodyTextStyle,
                                modifier = Modifier.threadMessageContentWidth(messageMaxWidthFraction),
                                onOpenUrl = onOpenMarkdownUrl,
                                onOpenAll = { onOpenForwardBundle(message.id) },
                                // web handleOpenForwardedSource: the source message's link,
                                // which opens a reply in its thread.
                                onOpenSource = { visible ->
                                    visible.forwardSourcePermalink(currentServerSlug)?.let(onOpenMarkdownUrl)
                                },
                                onOpenAttachment = onOpenAttachment
                            )
                        } else if (message.isRetracted) {
                            ThreadMessageRetractedBody(
                                textStyle = messageBodyTextStyle,
                                modifier = Modifier.threadMessageContentWidth(messageMaxWidthFraction)
                            )
                        } else if (message.body.isNotBlank()) {
                            if (richContentEnabled) {
                                message.commentRef?.let { commentRef ->
                                    ThreadMessageCommentRefChip(
                                        ref = commentRef,
                                        enabled = commentRef.hasThreadCommentHostSource(),
                                        modifier = Modifier.threadMessageContentWidth(messageMaxWidthFraction),
                                        onOpen = { onOpenCommentHost(commentRef) }
                                    )
                                }
                            }
                            Column(
                                modifier =
                                    Modifier
                                        .threadMessageContentWidth(messageMaxWidthFraction)
                                        .background(geometryTints?.capTint ?: Color.Transparent),
                                verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Xs),
                                horizontalAlignment = contentAlignment
                            ) {
                                // task #800: the sending spinner shares the End
                                // content coordinates with the body — an in-flow
                                // Row child, so sending → sent removes only
                                // left-side width and the End edge cannot move.
                                val messageBodyContent: @Composable () -> Unit = {
                                    // #proj-mobile:#201: while the translation is read, the original body
                                    // keeps the row's height (measured, not drawn) and the placeholder sits on
                                    // it, so the row doesn't collapse to one line and grow again.
                                    ThreadMessageTranslationPendingFrame(
                                        pending = translation?.pendingPlaceholder == true,
                                        alignEnd = alignOwnMessageRight,
                                    ) {
                                        RaftLongMessageCollapsibleBody(
                                            controller = longMessageCollapse,
                                            messageId = message.id,
                                            contentKey = "${message.id}:${bodyForRender.length}:${bodyForRender.hashCode()}",
                                            alignEnd = alignOwnMessageRight,
                                            onToggle = {
                                                tapMovementGate.rejectChildAction("long_message_toggle")
                                                onToggleLongMessage()
                                            }
                                        ) {
                                            ThreadInPlaceSelectableBody(
                                                controller = inPlaceSelection,
                                                messageMenuOpen = popupOpen,
                                                fullMessageText =
                                                    if (bodyForRender == message.body) {
                                                        threadMessageSelectionSourceText(message)
                                                    } else {
                                                        null
                                                    },
                                                wholeMessageText = threadMessageSelectionSourceText(message),
                                                diagnosticsLogger = diagnosticsLogger
                                            ) {
                                                // A cut Activity preview is plain text: no Markdown,
                                                // link chips or mentions made from the cut text.
                                                if (threadMessageRendersRichBody(message, richContentEnabled)) {
                                                    val markdownRenderSignature =
                                                        remember(message.id, bodyForRender) {
                                                            if (bodyForRender == message.body) {
                                                                message.threadMarkdownRenderSignature()
                                                            } else {
                                                                message.copy(body = bodyForRender).threadMarkdownRenderSignature()
                                                            }
                                                        }
                                                    LaunchedEffect(markdownRenderSignature) {
                                                        diagnosticsLogger(
                                                            "thread_markdown_row key=${messagePerformanceKey.orEmpty()} " +
                                                                "message=${message.id.take(8)} bodyLen=${bodyForRender.length} " +
                                                                "sourceBodyLen=${renderedBody.length} " +
                                                                "bodyHash=${bodyForRender.hashCode().toString(16)} " +
                                                                "context=$markdownTagContextSignature"
                                                        )
                                                    }
                                                    key(markdownRenderSignature) {
                                                        // web MessageItem taskByNumber: the message's own
                                                        // linked task wins, for this body only.
                                                        RaftMarkdownOwnTaskStatusScope(
                                                            taskNumber = message.taskNumber,
                                                            taskStatus = message.taskStatus,
                                                            isTagValid = isMarkdownTagValid,
                                                        ) { ownTaskTagValid ->
                                                          RaftMarkdownMentionReadStateScope(mentionReadStates) {
                                                          // artin #20 lane L: App links as chips, read
                                                          // for a sent message only (web MessageItem).
                                                          ThreadAppLinkPreviewScope(
                                                              messageId = message.id,
                                                              body = message.body,
                                                              enabled = message.pendingSendContext == null,
                                                              onOpenLink = onOpenMarkdownUrl,
                                                          ) {
                                                          // A tap on a code block is not the row's tap (web skips `pre, code`).
                                                          CompositionLocalProvider(
                                                            LocalRaftMarkdownCodeTapObserver provides codeBlockTapObserver,
                                                            LocalRaftMarkdownLinkLongPress provides linkLongPress,
                                                            LocalRaftUnavailableQuotedPermalink provides
                                                                quotedPermalinkTarget.takeIf {
                                                                    quotedPermalinkState.value == ThreadQuotedPermalinkUiState.Unavailable
                                                                },
                                                            // Search in this thread marks its words in the open match only.
                                                            LocalRaftMarkdownFindQuery provides
                                                                (if (searchMatch) LocalThreadFindHighlightQuery.current else null)
                                                          ) {
                                                            // Quote replies (web QuoteReplyBlocks): a verified quote is a
                                                            // card; any other keeps its words, without its `msg=` line.
                                                            ThreadQuoteReplyBody(
                                                                messageId = message.id,
                                                                body = bodyForRender,
                                                                resolvedQuotes = message.resolvedQuotes,
                                                                // While selecting messages a tap selects the row instead.
                                                                onJumpToQuotedMessage =
                                                                    callbacks.onJumpToQuotedMessage
                                                                        ?.takeUnless { shareSelectionActive }
                                                                        ?.let { jump ->
                                                                            { quotedMessageId: String ->
                                                                                tapMovementGate.rejectChildAction("quote_reply")
                                                                                jump(quotedMessageId)
                                                                            }
                                                                        },
                                                                alignEnd = alignOwnMessageRight,
                                                            ) { markdownBody ->
                                                            RaftMarkdownBody(
                                                                body = markdownBody,
                                                                fillWidth = !alignOwnMessageRight,
                                                                textStyle = messageBodyTextStyle,
                                                                highlightedMentions = highlightedMentions,
                                                                isTagValid = ownTaskTagValid,
                                                                resolveTagLabel = resolveMarkdownTagLabel,
                                                                inlineContentScope = markdownRenderSignature.inlineContentScope,
                                                                activeTagKey = activeMarkdownTagKey,
                                                                onOpenTagAction = { tag, actionId ->
                                                                    tapMovementGate.rejectChildAction("markdown_tag")
                                                                    onOpenMarkdownTag(tag, actionId)
                                                                },
                                                                onOpenUrl = { url ->
                                                                    tapMovementGate.rejectChildAction("markdown_url")
                                                                    onOpenMarkdownUrl(url)
                                                                },
                                                                performanceKey = messagePerformanceKey,
                                                                performanceLogger = diagnosticsLogger
                                                            )
                                                            }
                                                          }
                                                          }
                                                          }
                                                        }
                                                    }
                                                } else {
                                                    val findQuery = if (searchMatch) LocalThreadFindHighlightQuery.current else null
                                                    Text(
                                                        // A cut preview's quote reply reads without its `msg=` line too.
                                                        text = AnnotatedString(stripQuoteReplyRefsForPreview(bodyForRender)).withRaftMarkdownFindMarks(findQuery),
                                                        style = messageBodyTextStyle,
                                                        color = RaftColor.TextBlack,
                                                        modifier = if (!alignOwnMessageRight) Modifier.fillMaxWidth() else Modifier
                                                    )
                                                }
                                            }
                                        }
                                                                        }
                                }
                                // artin 10-26: a body cut from an Activity preview ends in a loading
                                // indicator until the canonical load replaces the message, or says
                                // the full message couldn't load when that load failed.
                                val messageBodyBlock: @Composable () -> Unit = {
                                    if (message.hasActivityCutBody()) {
                                        Column(
                                            horizontalAlignment =
                                                if (alignOwnMessageRight) Alignment.End else Alignment.Start
                                        ) {
                                            messageBodyContent()
                                            if (message.activityPreviewTruncated) {
                                                ThreadMessageFullBodyLoadingIndicator()
                                            } else {
                                                ThreadMessageFullBodyLoadFailedNote()
                                            }
                                        }
                                    } else {
                                        messageBodyContent()
                                    }
                                }
                                if (alignOwnMessageRight) {
                                    Row(
                                        verticalAlignment = Alignment.Top,
                                        horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Xs)
                                    ) {
                                        if (richContentEnabled && message.isSendingLocalSend()) {
                                            ThreadMessageGeometryTintBox(geometryTints?.statusTint) {
                                                ThreadMessageSendingIndicator(visible = sendingIndicatorVisible)
                                            }
                                        }
                                        ThreadMessageGeometryTintBox(geometryTints?.bodyTint) {
                                            messageBodyBlock()
                                        }
                                    }
                                } else {
                                    ThreadMessageGeometryTintBox(geometryTints?.bodyTint) {
                                        messageBodyBlock()
                                    }
                                }
                                // web MessageItem `ArtifactMessageRefs`: the cards for the Artifacts
                                // the text links to, right under the body (web `mt-2`: 8dp, the
                                // column adds 4); viewer-only, so a share image has none.
                                if (richContentEnabled) {
                                    ThreadArtifactMessageRefs(
                                        messageId = message.id,
                                        body = message.body,
                                        enabled = message.pendingSendContext == null,
                                        modifier = Modifier.padding(top = RaftAppSpacing.Xs)
                                    )
                                }
                                // A share image is public: the quoted message is viewer-only, so
                                // capture mode drops the whole preview (and its margin); the link
                                // stays in the body above.
                                quotedPermalinkState.value
                                    ?.takeIf { threadQuotedPermalinkPreviewVisible(it, shareCaptureActive) }
                                    ?.let { state ->
                                    ThreadQuotedPermalinkCard(
                                        state = state,
                                        target = quotedPermalinkTarget,
                                        memberAvatarResolver = memberAvatarResolver,
                                        onOpen = {
                                            quotedPermalinkTarget?.let { target ->
                                                onOpenMarkdownUrl(target.rawUrl)
                                            }
                                        },
                                        // web `mt-1.5`: 6dp under the body (the column adds 4).
                                        modifier = Modifier.padding(top = RaftAppSpacing.Xxs)
                                    )
                                }
                            }
                        }
                        // RFC 077: a continuation row has no header, so "(sent by Friday)" ends
                        // its text instead, without the face (web MessageItem continuation).
                        val delegatedBy = message.delegatedBy
                        if (delegatedBy != null && !showAuthorChrome) {
                            Box(modifier = Modifier.threadMessageContentWidth(messageMaxWidthFraction)) {
                                ThreadMessageDelegatedByLabel(
                                    delegatedBy = delegatedBy,
                                    placement = DelegatedLabelPlacement.InBody
                                )
                            }
                        }
                        if (actionCard == null && messageCard == null) {
                            translation?.indicator?.let { indicator ->
                                Box(modifier = Modifier.threadMessageContentWidth(messageMaxWidthFraction)) {
                                    ThreadMessageTranslationIndicator(
                                        indicator = indicator,
                                        onToggleOriginal = onToggleTranslationOriginal,
                                        onRetry = onRetryTranslation
                                    )
                                }
                            }
                        }
                        if (hasThreadAttachments) {
                            Box(modifier = Modifier.threadMessageContentWidth(messageMaxWidthFraction)) {
                                ThreadMessageAttachments(
                                    attachments = message.attachments,
                                    alignEnd = alignOwnMessageRight,
                                    pending = message.isSendingLocalSend(),
                                    pendingIndicatorVisible = sendingIndicatorVisible,
                                    attachmentOpenStates = attachmentOpenStates,
                                    onOpenAttachment = onOpenAttachment,
                                    onDownloadAttachment = onDownloadAttachment
                                )
                            }
                        }
                        if (message.isFailedLocalSend()) {
                            Box(modifier = Modifier.threadMessageContentWidth(messageMaxWidthFraction)) {
                                ThreadMessageGeometryTintBox(geometryTints?.statusTint) {
                                    ThreadMessageLocalSendStatus(
                                        message = message,
                                        alignEnd = alignOwnMessageRight,
                                        retryEnabled = writeActionsEnabled,
                                        onRetry = onRetryLocalSend,
                                        onCopyDetails = onCopyLocalSendFailureDetails
                                    )
                                }
                            }
                        }
                        threadMessageTrailingSectionOrder(
                            hasMetaChips = hasFooterMeta,
                            hasInlineReplies = inlineReplyPresentation != null,
                            hasSourceTasks = sourceTasks.isNotEmpty(),
                        ).forEach { section ->
                            when (section) {
                                ThreadMessageTrailingSection.SourceTasks -> {
                                    Box(modifier = Modifier.threadMessageContentWidth(messageMaxWidthFraction)) {
                                        ThreadMessageSourceTaskList(
                                            tasks = sourceTasks,
                                            memberAvatarResolver = memberAvatarResolver,
                                            agentStatusResolver = agentStatusResolver,
                                            onOpenTask = onOpenSourceTask,
                                            // web `mt-1.5`: 6dp under the body (the column adds 4).
                                            modifier = Modifier.padding(top = RaftAppSpacing.Xxs)
                                        )
                                    }
                                }
                                ThreadMessageTrailingSection.MetaChips -> {
                                    // web MessageItemFooter `mt-1.5`: 6dp under the body (the column adds 4).
                                    Box(
                                        modifier =
                                            Modifier
                                                .threadMessageContentWidth(messageMaxWidthFraction)
                                                .padding(top = RaftAppSpacing.Xxs)
                                    ) {
                                        ThreadMessageFooterMeta(
                                            message = message,
                                            taskOpenEnabled = taskOpenEnabled,
                                            taskStatusActionsEnabled = taskStatusActionsEnabled,
                                            resolvedTaskAssigneeName = resolvedTaskAssigneeName,
                                            alignEnd = alignOwnMessageRight,
                                            onOpenTask = onOpenTask,
                                            onShowActions = onShowActions,
                                            onOpenTaskStatusMenu = onOpenTaskStatusMenu,
                                            showSavedBadge = showSavedBadge,
                                            onToggleSave = onToggleSave,
                                            repliesBadge = repliesBadge,
                                            onOpenReplies = { onOpenThread(message.id) },
                                            showReactionAddAction = showReactionAddAction,
                                            onToggleReaction = onToggleReaction,
                                            showReactionReactors = showReactionReactors,
                                            onShowQuickReactions =
                                                if (quickReactionsEnabled) onShowQuickReactions else null
                                        )
                                    }
                                }
                                ThreadMessageTrailingSection.InlineReplies -> {
                                    inlineReplyPresentation?.let { presentation ->
                                        Box(modifier = Modifier.threadMessageContentWidth(messageMaxWidthFraction)) {
                                            ThreadInlineRepliesSurface(
                                                presentation = presentation,
                                                participantsById = inlineReplyParticipantsById,
                                                memberAvatarResolver = memberAvatarResolver,
                                                onOpenThread = { onOpenThread(message.id) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (richContentEnabled) {
                        when {
                            message.isSendingLocalSend() && !alignOwnMessageRight -> {
                                ThreadMessageSendingIndicator(
                                    visible = sendingIndicatorVisible,
                                    modifier = Modifier.align(Alignment.TopEnd)
                                )
                            }
                        }
                    }
                }
                if (alignOwnMessageRight) {
                    ThreadMessageAvatarSlot(
                        interactionIdentity = authorGestureIdentity,
                        showAvatar = showAuthorChrome,
                        showTimestamp = showTimestampInAvatarSlot,
                        timestampAccessibilityLabel = rowTimeAccessibilityLabel,
                        compactTimestampGutter = compactTimestampGutter,
                        timestampLabel = compactTimestampLabel,
                        timestampAlignment = Alignment.TopEnd,
                        timestampTextAlign = TextAlign.End,
                        avatarLabel = message.threadAvatarName(),
                        avatarKind = message.threadAvatarKind(),
                        avatarWireValue =
                            if (message.externalAuthor != null) message.avatarWireValue else avatarWireValue,
                        avatarPending = authorAvatarPending,
                        avatarDiagnostics = avatarDiagnostics,
                        cacheSubject =
                            raftMemberAvatarCacheSubject(
                                message.authorKind.projectedMemberKindOrNull(),
                                message.authorId
                            ),
                        status = agentStatus,
                        // web MessageItem: only an archived agent's face is dimmed (`grayscale opacity-dimmed`);
                        // a person who left or was removed keeps theirs.
                        muted = message.isDeletedThreadAuthor(),
                        delegatedBy = message.delegatedBy,
                        delegatedSurface = rowSurface,
                        originServers = authorOriginServers,
                        onClick = { openAuthorProfile("avatar") },
                        onLongPress = { onMentionAuthor(message) }
                    )
                }
                if (shareSelectionActive && alignOwnMessageRight) {
                    ThreadMessageSelectionToggle(
                        checked = selectedForShare,
                        onClick = onToggleShareSelection,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
        ThreadMessagePressRipple(
            pressed = pressed,
            origin = pressRippleOrigin
        )
        if (hoverToolbar != null) {
            ThreadMessageHoverToolbar(
                messageId = message.id,
                actions = hoverToolbar,
                rowHovered = rowPointerOver,
                popupOpen = popupOpen,
                onShowActions = onShowActions,
                onReplyInThread = { onOpenThread(message.id) },
                onShownChange = { hoverToolbarShown = it },
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .threadMessageHoverToolbarOverlay()
                        .offset(
                            x = -RaftMessageItemToolbarDefaults.EndInset,
                            // web `top-[-18px]` is measured inside the row's border, below a turn's
                            // transparent `timeline-turn` band.
                            y =
                                RaftMessageItemToolbarDefaults.TopOffset +
                                    if (showAuthorChrome) ThreadMessageTimelineTurn else 0.dp
                        )
            )
        }
    }
}

private fun Modifier.threadMessageContentWidth(maxWidthFraction: Float): Modifier =
    if (maxWidthFraction < 1f) {
        fillMaxWidth(maxWidthFraction)
    } else {
        fillMaxWidth()
    }

private const val ThreadMessageRippleResetMarginMs = 60L

@Composable
private fun ThreadMessageAuthorBadge(label: String) {
    // web MessageItem: `<Badge appearance="soft" variant="muted">` after the sender's name.
    RaftBadge(
        text = label,
        appearance = RaftBadgeAppearance.Soft,
        variant = RaftBadgeVariant.Muted,
    )
}

/**
 * web MessageItem's AvatarSlot `type`: an external (Slack-bridge) author is `app` (their picture,
 * else their initials), an agent `agent`, anyone else `human`.
 */
private fun RaftThreadMessageItem.threadAvatarKind(): RaftAvatarKind =
    when {
        externalAuthor != null -> RaftAvatarKind.Labeled
        isThreadAgentAuthor() -> RaftAvatarKind.Agent
        else -> RaftAvatarKind.Human
    }

/**
 * The hover toolbar floats over its row (web `absolute`): it is drawn and takes pointer input at
 * its full size, but tells the row's Box it has no height, so the row never grows to fit it. A
 * one-line continuation row is 32dp (4 + 24 + 4) and the toolbar 36dp; counted in the Box, it made
 * the hovered row 4dp taller and shifted every row above it in the bottom-anchored list.
 */
private fun Modifier.threadMessageHoverToolbarOverlay(): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        layout(placeable.width, 0) { placeable.place(0, 0) }
    }
