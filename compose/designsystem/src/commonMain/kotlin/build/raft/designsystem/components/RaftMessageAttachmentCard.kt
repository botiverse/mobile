package build.raft.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke

/** raft-ui MessageAttachmentCard metrics. */
object RaftMessageAttachmentCardDefaults {
    /** `w-44`. */
    val Width: Dp = 176.dp

    /** `h-20`. */
    val Height: Dp = 80.dp
}

/**
 * raft-ui MessageAttachmentCard: a file attached to a sent message, a fixed 176 x 80dp tile on
 * `layer-card` with a 1dp `line-hairline` edge and the container radius, `py-2 pr-1.5 pl-2.5`
 * inside the edge.
 *
 * - Title row (`MessageAttachmentTitle`): [typeBadge] (the file's type chip, when the name
 *   doesn't make it clear), 6dp, then [title] in `text-callout` semibold `foreground`, one line,
 *   cut with an ellipsis.
 * - Meta line (`MessageAttachmentMeta`), pushed to the bottom, Space Mono `text-mono-meta` in
 *   `foreground-hint`: [meta] (the kind, or a status such as "Downloading · 64%") truncates
 *   first; then " · [metaSize]" and " · [MessageSquare 12dp] [commentCount]" keep their width.
 *   [actions] (`MessageAttachmentMetaEnd`, one or two [RaftMessageAttachmentAction]) sit at its
 *   end, 4dp apart.
 * - [loading]: an xs [RaftSpinner] in the actions' place while the file opens.
 * - [failed]: the edge in `danger-strong` (an open that failed).
 * - Press (and a mouse hover): `fill-medium` over the whole card.
 */
@Composable
fun RaftMessageAttachmentCard(
    title: String,
    meta: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    metaSize: String? = null,
    commentCount: Int = 0,
    loading: Boolean = false,
    failed: Boolean = false,
    typeBadge: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val shape = RoundedCornerShape(RaftRadius.Container)
    // Flattened onto the card so the rounded edge matches the web.
    val edge = if (failed) colors.dangerStrong else colors.lineHairline.compositeOver(colors.layerCard)
    val source = remember { MutableInteractionSource() }
    val isPressed = raftIsPressed(source)
    val isHovered = raftIsHovered(source)
    val active = isPressed || isHovered
    val overlay = colors.fillMedium
    val metaStyle = typography.monoMeta
    Column(
        modifier
            .size(RaftMessageAttachmentCardDefaults.Width, RaftMessageAttachmentCardDefaults.Height)
            .clip(shape)
            .background(colors.layerCard, shape)
            .drawWithContent {
                drawContent()
                if (active) drawRect(overlay)
            }
            .border(RaftStroke.Hairline, edge, shape)
            .raftPointerHover(source)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            // CSS `border` + `py-2 pr-1.5 pl-2.5`: the 1dp edge takes layout space on the web.
            .padding(
                start = 10.dp + RaftStroke.Hairline,
                end = 6.dp + RaftStroke.Hairline,
                top = 8.dp + RaftStroke.Hairline,
                bottom = 8.dp + RaftStroke.Hairline,
            ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            typeBadge?.invoke()
            RaftText(
                title,
                Modifier.weight(1f, fill = false),
                style = typography.callout.copy(fontWeight = FontWeight.SemiBold),
                color = colors.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RaftText(
                    meta,
                    Modifier.weight(1f, fill = false),
                    style = metaStyle,
                    color = colors.foregroundHint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!metaSize.isNullOrBlank()) {
                    MetaText("·")
                    MetaText(metaSize)
                }
                // Web `attachment-comment-count`: "· [MessageSquare 12] N" (`gap-1`).
                if (commentCount > 0) {
                    MetaText("·")
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RaftGlyphIcon(RaftGlyph.MessageSquare, size = 12.dp, color = colors.foregroundHint)
                        MetaText(commentCount.toString())
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (loading) {
                    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                        RaftSpinner(size = RaftSpinnerSize.Xs, color = colors.foreground)
                    }
                } else {
                    actions()
                }
            }
        }
    }
}

/**
 * raft-ui MessageAttachmentAction: a 24dp ghost [RaftIconButton] (`size-6 rounded-chip`,
 * `active:bg-fill-medium`) at the end of a [RaftMessageAttachmentCard]'s meta line, its [icon]
 * at 16dp in `foreground` ([LocalRaftContentColor]). Name it with the file: "Download
 * report.pdf". No tooltip: a long press belongs to the message row around the card.
 */
@Composable
fun RaftMessageAttachmentAction(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
) {
    RaftIconButton(
        onClick = onClick,
        tooltip = null,
        modifier = modifier,
        contentDescription = contentDescription,
        variant = RaftButtonVariant.Ghost,
        size = RaftIconButtonSize.Xs,
    ) {
        CompositionLocalProvider(LocalRaftContentColor provides RaftTheme.colors.foreground) {
            Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { icon() }
        }
    }
}

@Composable
private fun MetaText(text: String) {
    RaftText(text, style = RaftTheme.typography.monoMeta, color = RaftTheme.colors.foregroundHint, maxLines = 1, softWrap = false)
}
