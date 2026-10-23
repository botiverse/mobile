package build.raft.app.components

import build.raft.app.RaftAppRadius
import build.raft.app.RaftAppStroke
import build.raft.app.RaftColor
import build.raft.app.RaftAppThemeState
import build.raft.app.raftArgbHex
import build.raft.app.RaftLucideIcon
import build.raft.app.RaftLucideIconName
import build.raft.app.RaftMonoFamily
import build.raft.app.RaftType
import build.raft.app.withRaftFontWeight
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.drawWithContent
import build.raft.app.ThreadFileTypeBadge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// raft-ui MessageAttachmentCard: `h-20 w-44` (80 x 176).
internal val RaftAttachmentPreviewCardWidth = 176.dp
internal val RaftAttachmentPreviewCardHeight = 80.dp

/** One icon action at the end of the card's meta line (raft-ui `MessageAttachmentAction`). */
internal data class RaftAttachmentPreviewCardAction(
    val icon: RaftLucideIconName,
    val contentDescription: String,
    val onClick: () -> Unit
)

/**
 * raft-ui `MessageAttachmentCard` (web MessageItem file attachment): a fixed 176 x 80 tile on
 * layer-card with a line-hairline edge and the container radius, padded `py-2 pr-1.5 pl-2.5`.
 * The title row holds the file-type chip ([typeBadge], web `AttachmentTypeBadge`) and the name
 * (`text-callout font-semibold`, one truncated line); the meta line is pushed to the bottom
 * (`mt-auto`) in Space Mono hint: [meta] (the kind, e.g. "application/pdf"), then " · [metaSize]",
 * then the comment count, with the icon actions on the end. While the file opens a spinner takes
 * the actions' place; a failed open draws the edge in danger-strong. Pressing lays
 * `overlay-medium` over the card.
 */
@Composable
internal fun RaftAttachmentPreviewCard(
    title: String,
    meta: String,
    actions: List<RaftAttachmentPreviewCardAction>,
    loading: Boolean,
    modifier: Modifier = Modifier,
    typeBadge: String? = null,
    metaSize: String? = null,
    commentCount: Int = 0,
    failed: Boolean = false,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(RaftAppRadius.Container)
    val edgeColor =
        if (failed) {
            RaftColor.DangerStrong
        } else {
            // Flattened onto the card so the rounded edge matches the web (see ThreadFileRow).
            RaftColor.BorderHairline.compositeOver(RaftColor.Card)
        }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressedOverlay = RaftColor.FillPressed
    Column(
        modifier =
            modifier
                .size(RaftAttachmentPreviewCardWidth, RaftAttachmentPreviewCardHeight)
                .clip(shape)
                .background(RaftColor.Card, shape)
                .drawWithContent {
                    drawContent()
                    // `active:overlay-medium`: a wash over the whole card.
                    if (pressed) drawRect(pressedOverlay)
                }
                .border(RaftAppStroke.Thin, edgeColor, shape)
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                // CSS `border` + `py-2 pr-1.5 pl-2.5`: the 1dp edge takes layout space on the web.
                .padding(
                    start = 10.dp + RaftAppStroke.Thin,
                    end = 6.dp + RaftAppStroke.Thin,
                    top = 8.dp + RaftAppStroke.Thin,
                    bottom = 8.dp + RaftAppStroke.Thin
                )
    ) {
        // `MessageAttachmentTitle pr-1` > `flex items-center gap-1.5`: badge, then the name.
        Row(
            modifier = Modifier.fillMaxWidth().padding(end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (typeBadge != null) ThreadFileTypeBadge(label = typeBadge, inline = true)
            Text(
                text = title,
                style = RaftAttachmentCardTitleStyle,
                color = RaftColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
        }
        Spacer(Modifier.weight(1f))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // `AttachmentMetaText`: the kind truncates; the dot and size keep their width.
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = meta,
                    style = RaftAttachmentCardMetaStyle,
                    color = RaftColor.ForegroundHint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (!metaSize.isNullOrBlank()) {
                    RaftAttachmentCardMetaText("·")
                    RaftAttachmentCardMetaText(metaSize)
                }
                // Web `attachment-comment-count`: "· [MessageSquare 12] N" (`gap-1 pl-1`).
                if (commentCount > 0) {
                    RaftAttachmentCardMetaText("·")
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RaftLucideIcon(
                            RaftLucideIconName.MessageSquare,
                            modifier = Modifier.size(12.dp),
                            color = raftArgbHex(RaftColor.ForegroundHint)
                        )
                        RaftAttachmentCardMetaText(commentCount.toString())
                    }
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (loading) {
                    Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                        RaftUiSpinner(size = RaftAppSpinnerSize.Xs)
                    }
                } else {
                    actions.forEach { action -> RaftAttachmentPreviewCardActionButton(action) }
                }
            }
        }
    }
}

@Composable
private fun RaftAttachmentCardMetaText(text: String) {
    Text(
        text = text,
        style = RaftAttachmentCardMetaStyle,
        color = RaftColor.ForegroundHint,
        maxLines = 1,
        softWrap = false
    )
}

// `-my-1 size-6 rounded-chip text-foreground active:bg-fill-medium`, a 16dp glyph.
@Composable
private fun RaftAttachmentPreviewCardActionButton(action: RaftAttachmentPreviewCardAction) {
    val shape = RoundedCornerShape(RaftAppRadius.Chip)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Box(
        modifier =
            Modifier
                .size(24.dp)
                .clip(shape)
                .then(if (pressed) Modifier.background(RaftAppThemeState.colors.fillMedium, shape) else Modifier)
                .clickable(interactionSource = interactionSource, indication = null, onClick = action.onClick)
                .semantics { contentDescription = action.contentDescription },
        contentAlignment = Alignment.Center
    ) {
        RaftLucideIcon(
            action.icon,
            modifier = Modifier.size(16.dp),
            color = raftArgbHex(RaftColor.TextBlack)
        )
    }
}

// `font-sans text-callout font-semibold text-foreground` (14/20 600).
private val RaftAttachmentCardTitleStyle: TextStyle
    get() = RaftType.Button.withRaftFontWeight(FontWeight.SemiBold).copy(color = RaftColor.TextBlack)

// `font-mono text-mono-meta text-foreground-hint` (Space Mono 12/16).
private val RaftAttachmentCardMetaStyle: TextStyle
    get() = RaftType.Caption.copy(
        fontFamily = RaftMonoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = RaftColor.ForegroundHint
    )
