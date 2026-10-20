package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.ThreadAttachmentCommentItem
import build.raft.app.markdown.RaftMarkdownBody
import build.raft.app.model.formatRaftTimestampLabel
import build.raft.app.resources.MR
import build.raft.designsystem.components.RaftAvatar
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftAvatarType
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftSpinner
import build.raft.designsystem.components.RaftSpinnerSize
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke
import build.raft.designsystem.tokens.RaftUiPalette
import com.tencent.tmm.kmmresource.compose.stringResource

// web AttachmentCommentsPanel (packages/web/src/components/message/AttachmentCommentsPanel.tsx):
// a `min-h-10 px-3 py-1.5` header over a `line-seam`, the comment cards in a `px-3 py-2`
// list with `gap-3`, and the compact composer docked under one seam with `p-3` around it.
private val CommentsHeaderMinHeight = 40.dp
private val CommentsEdge = 12.dp
private val CommentsHeaderPaddingVertical = 6.dp
private val CommentsListPaddingVertical = 8.dp
private val CommentsCardGap = 12.dp
private val CommentCardPaddingVertical = 10.dp
private val CommentAvatarSize = 19.dp
// `pr-6` on the card's author row keeps the time clear of the card's corner control.
private val CommentAuthorRowEndPadding = 24.dp
private val CommentStackGap = 4.dp
// raft-ui `Composer`: 12 above the input, 8 under the toolbar, 12 at the sides (the input
// itself is inset 2 more), 8 between the input and the toolbar.
private val CommentComposerPaddingTop = 12.dp
private val CommentComposerPaddingBottom = 8.dp
private val CommentComposerInputInset = 2.dp
private val CommentComposerGap = 8.dp
private val CommentComposerInputMinHeight = 24.dp
private val CommentComposerInputMaxHeight = 120.dp
private val CommentChipIconSize = 12.dp
// On the web the card's anchor chip is `inline-flex`, so it sits on a line box whose strut
// reaches below the chip's baseline; the body starts that much lower than the `mt-1` alone.
private val CommentChipLineBoxDescent = (8f / 3f).dp
// web AvatarSlot `compact-list` for a person without a photo: lucide User at 12px.
private val CommentAvatarIconSize = 12.dp

/**
 * The attachment comments panel: the web `AttachmentCommentsPanel` on a phone. [onClose] adds
 * the header's ghost close button (the web sheet's toggle sits in the same place); without it
 * the header is the title alone. The list takes the height left by the header and composer, so
 * give [modifier] a bounded height.
 */
@Composable
internal fun RaftAttachmentCommentsPanel(
    state: ThreadAttachmentCommentsDialogState,
    onDraftChange: (TextFieldValue) -> Unit,
    onRetry: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
    onClearAnchor: (() -> Unit)? = null,
) {
    RaftDesignSystemTheme {
        val colors = RaftTheme.colors
        Column(modifier = modifier.background(colors.layerCanvas)) {
            AttachmentCommentsHeader(filename = state.attachment.name, onClose = onClose)
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                AttachmentCommentsList(state = state, onRetry = onRetry)
            }
            AttachmentCommentsDock(
                state = state,
                onDraftChange = onDraftChange,
                onSend = onSend,
                onClearAnchor = onClearAnchor
            )
        }
    }
}

@Composable
private fun AttachmentCommentsHeader(filename: String, onClose: (() -> Unit)?) {
    val colors = RaftTheme.colors
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = CommentsHeaderMinHeight - RaftStroke.Hairline)
                    .padding(horizontal = CommentsEdge, vertical = CommentsHeaderPaddingVertical),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RaftText(
                text = stringResource(MR.strings.thread_comments_header, filename),
                style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.Bold),
                color = colors.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (onClose != null) {
                val closeLabel = stringResource(MR.strings.common_close)
                RaftIconButton(
                    onClick = onClose,
                    tooltip = closeLabel,
                    variant = RaftButtonVariant.Ghost,
                    size = RaftIconButtonSize.Md
                ) {
                    RaftLucideIcon(
                        RaftLucideIconName.X,
                        modifier = Modifier.size(16.dp),
                        color = raftArgbHex(colors.foreground)
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().heightIn(min = RaftStroke.Hairline, max = RaftStroke.Hairline).background(colors.lineSeam))
    }
}

@Composable
private fun AttachmentCommentsList(
    state: ThreadAttachmentCommentsDialogState,
    onRetry: () -> Unit
) {
    val colors = RaftTheme.colors
    val comments = state.page?.comments.orEmpty()
    when {
        state.status == "loading" ->
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                RaftSpinner(size = RaftSpinnerSize.Md)
            }
        state.status == "error" -> {
            val labels = threadUserFacingErrorLabels()
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = CommentsEdge, vertical = CommentsListPaddingVertical),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                RaftText(
                    text =
                        attachmentCommentsVisibleErrorMessage(
                            errorCode = state.errorCode,
                            serverMessage = state.errorServerMessage,
                            labels = labels,
                            genericMessage = stringResource(MR.strings.thread_comments_load_failed)
                        ),
                    style = RaftTheme.typography.footnote,
                    color = colors.foregroundMuted
                )
                build.raft.designsystem.components.RaftButton(
                    text = stringResource(MR.strings.common_retry),
                    onClick = onRetry
                )
            }
        }
        comments.isEmpty() ->
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = CommentsEdge, vertical = CommentsListPaddingVertical)) {
                RaftText(
                    text = stringResource(MR.strings.thread_comments_empty_hint, state.attachment.name),
                    style = RaftTheme.typography.footnote,
                    color = colors.foregroundMuted,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp)
                )
            }
        else ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding =
                    androidx.compose.foundation.layout.PaddingValues(
                        horizontal = CommentsEdge,
                        vertical = CommentsListPaddingVertical
                    ),
                verticalArrangement = Arrangement.spacedBy(CommentsCardGap)
            ) {
                items(comments.size, key = { comments[it].id }) { index ->
                    AttachmentCommentCard(comment = comments[index], filename = state.attachment.name)
                }
            }
    }
}

@Composable
private fun AttachmentCommentCard(comment: ThreadAttachmentCommentItem, filename: String) {
    val colors = RaftTheme.colors
    val shape = RoundedCornerShape(RaftRadius.Container)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.layerCard, shape)
                .border(RaftStroke.Hairline, colors.lineHairline, shape)
                .padding(
                    horizontal = CommentsEdge + RaftStroke.Hairline,
                    vertical = CommentCardPaddingVertical + RaftStroke.Hairline
                ),
        verticalArrangement = Arrangement.spacedBy(CommentStackGap)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(end = CommentAuthorRowEndPadding),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val agent = comment.senderType == "agent"
            RaftAvatar(
                type = if (agent) RaftAvatarType.Agent else RaftAvatarType.Human,
                size = RaftAvatarSize.Xs,
                modifier = Modifier.size(CommentAvatarSize),
                image =
                    if (agent) {
                        null
                    } else {
                        {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                RaftLucideIcon(
                                    RaftLucideIconName.User,
                                    modifier = Modifier.size(CommentAvatarIconSize),
                                    color = raftArgbHex(RaftUiPalette.stickerInk)
                                )
                            }
                        }
                    }
            )
            RaftText(
                text = comment.senderName.ifBlank { stringResource(MR.strings.thread_comments_unknown_author) },
                style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.Bold),
                color = colors.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            val time = formatRaftTimestampLabel(comment.createdAt, LocalRaftTimeContextProvider.current?.current)
            if (time.isNotBlank()) {
                RaftText(
                    text = time,
                    style = RaftTheme.typography.monoMeta,
                    color = colors.foregroundHint,
                    maxLines = 1
                )
            }
        }
        AttachmentCommentChip(
            icon = if (comment.anchorLabel.isNullOrBlank()) RaftLucideIconName.FileText else RaftLucideIconName.MapPin,
            label = comment.anchorLabel?.takeIf { it.isNotBlank() } ?: filename
        )
        RaftMarkdownBody(
            body = comment.content,
            textStyle =
                RaftType.Body.copy(
                    fontSize = RaftTheme.typography.footnote.fontSize,
                    lineHeight = RaftTheme.typography.footnote.lineHeight,
                    color = colors.foreground
                ),
            modifier = Modifier.padding(top = CommentChipLineBoxDescent)
        )
    }
}

/** raft-ui chip on `fill-muted`: `rounded-chip px-1.5 py-0.5 text-caption font-semibold`. */
@Composable
private fun AttachmentCommentChip(
    icon: RaftLucideIconName,
    label: String,
    onRemove: (() -> Unit)? = null,
) {
    val colors = RaftTheme.colors
    val shape = RoundedCornerShape(RaftRadius.Chip)
    Row(
        modifier =
            Modifier
                .clip(shape)
                .background(colors.fillMuted, shape)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RaftLucideIcon(icon, modifier = Modifier.size(CommentChipIconSize), color = raftArgbHex(colors.foreground))
        RaftText(
            text = label,
            style = RaftTheme.typography.caption.copy(fontWeight = FontWeight.SemiBold),
            color = colors.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (onRemove != null) {
            val removeLabel = stringResource(MR.strings.common_close)
            Box(
                modifier =
                    Modifier
                        .size(CommentChipIconSize)
                        .semantics { contentDescription = removeLabel }
                        .clickable(onClick = onRemove)
            ) {
                RaftLucideIcon(
                    RaftLucideIconName.X,
                    modifier = Modifier.size(CommentChipIconSize),
                    color = raftArgbHex(colors.foregroundMuted)
                )
            }
        }
    }
}

@Composable
private fun AttachmentCommentsDock(
    state: ThreadAttachmentCommentsDialogState,
    onDraftChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    onClearAnchor: (() -> Unit)?,
) {
    val colors = RaftTheme.colors
    val viewer = state.page?.viewer
    val canComment = viewer?.canComment ?: true
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().heightIn(min = RaftStroke.Hairline, max = RaftStroke.Hairline).background(colors.lineSeam))
        Column(
            modifier = Modifier.fillMaxWidth().padding(CommentsEdge),
            verticalArrangement = Arrangement.spacedBy(CommentComposerGap)
        ) {
            if (!canComment) {
                RaftText(
                    text = threadAttachmentCommentBlockedCopy(viewer?.reason),
                    style = RaftTheme.typography.footnote,
                    color = colors.foregroundHint,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
                )
                return@Column
            }
            val anchor = state.pendingAnchorLabel?.takeIf { it.isNotBlank() }
            val quote = state.pendingQuoteLabel?.takeIf { it.isNotBlank() }
            if (anchor != null || quote != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    anchor?.let {
                        AttachmentCommentChip(icon = RaftLucideIconName.MapPin, label = it, onRemove = onClearAnchor)
                    }
                    quote?.let {
                        AttachmentCommentChip(
                            icon = RaftLucideIconName.MessageSquarePlus,
                            label = stringResource(MR.strings.thread_comments_quote, it)
                        )
                    }
                }
            }
            AttachmentCommentComposer(
                state = state,
                onDraftChange = onDraftChange,
                onSend = onSend
            )
            state.errorCode?.takeIf { state.status != "error" && it.isNotBlank() }?.let { code ->
                RaftText(
                    text =
                        attachmentCommentsVisibleErrorMessage(
                            errorCode = code,
                            serverMessage = state.errorServerMessage,
                            labels = threadUserFacingErrorLabels(),
                            genericMessage = stringResource(MR.strings.common_error)
                        ),
                    style = RaftTheme.typography.caption,
                    color = colors.dangerStrong,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** raft-ui `Composer` in its compact form: the input over a toolbar holding only the send button. */
@Composable
private fun AttachmentCommentComposer(
    state: ThreadAttachmentCommentsDialogState,
    onDraftChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
) {
    val colors = RaftTheme.colors
    val shape = RoundedCornerShape(RaftRadius.Container)
    val enabled = state.status == "loaded" && !state.sending
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(colors.layerCard, shape)
                .border(RaftStroke.Hairline, colors.lineField, shape)
                .padding(
                    start = CommentsEdge + RaftStroke.Hairline,
                    top = CommentComposerPaddingTop + RaftStroke.Hairline,
                    end = CommentsEdge + RaftStroke.Hairline,
                    bottom = CommentComposerPaddingBottom + RaftStroke.Hairline
                ),
        verticalArrangement = Arrangement.spacedBy(CommentComposerGap)
    ) {
        TextField(
            value = state.draft,
            placeholder = stringResource(MR.strings.thread_comments_composer_placeholder, state.attachment.name),
            autoFocus = false,
            onValueChange = onDraftChange,
            enabled = enabled,
            // CSS wraps greedily (a break after `-` included); Compose's default paragraph
            // strategy would re-balance the lines instead.
            textStyle =
                RaftType.Input.copy(
                    color = if (enabled) colors.foreground else colors.foregroundDisabled,
                    lineBreak = LineBreak.Simple
                ),
            placeholderColor = colors.foregroundHint,
            cursorBrush = SolidColor(colors.foreground),
            maxLines = 5,
            systemNewlineAction = true,
            imeActionRequested = false,
            contentAlignment = Alignment.TopStart,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CommentComposerInputInset)
                    .heightIn(min = CommentComposerInputMinHeight, max = CommentComposerInputMaxHeight)
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            val sendLabel = stringResource(MR.strings.common_send)
            RaftIconButton(
                onClick = onSend,
                tooltip = sendLabel,
                variant = RaftButtonVariant.Primary,
                size = RaftIconButtonSize.Md,
                enabled = enabled && state.draft.text.isNotBlank(),
                loading = state.sending,
                loadingLabel = stringResource(MR.strings.thread_comments_sending)
            ) {
                val ink = LocalRaftContentColor.current
                RaftLucideIcon(RaftLucideIconName.Send, modifier = Modifier.size(18.dp), color = raftArgbHex(ink))
            }
        }
    }
}
