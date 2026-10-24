package build.raft.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke

// raft-ui MessageQuotedPreview (raft-design packages/ui/src/components/message-quoted-preview/
// message-quoted-preview.recipe.ts, message-quoted-preview.md) as the web composes it
// (slock packages/web/src/components/ui/cards/QuotedMessageCard.tsx in PreviewShell, and the
// loading card of message/QuotedMessagePermalinkPreview.tsx): the message a pasted link points
// to, under the message that holds the link. The web draws the parts inside its preview card
// (`layer-card`, hairline, container radius) rather than on raft-ui's bare seam bar; mobile
// follows the web.

object RaftMessageQuotedPreviewDefaults {
    /** The quoted message's time: Space Mono `mono-meta`, tabular figures. */
    val TimeStyle: TextStyle
        @Composable get() = RaftTheme.typography.monoMeta.copy(fontFeatureSettings = "tnum")

    /** The time's ink, `foreground-hint`. */
    val TimeInk: Color
        @Composable get() = RaftTheme.colors.foregroundHint

    /** The author's face: an `xs` avatar (19dp, web AvatarSlot `compact-list`). */
    val AvatarSize = RaftAvatarSize.Xs

    /** The excerpt is cut at 2 lines. */
    const val TextMaxLines = 2
}

/**
 * The preview card: `layer-card`, a `line-hairline` edge, `rounded-container` (10), 12 x 10dp
 * padding, full width. A press lays `overlay-medium` over it ([onClick] opens the quoted
 * message, [onClickLabel] names it: "Open quoted message in #launch-week").
 */
@Composable
private fun QuotedPreviewShell(
    modifier: Modifier,
    muted: Boolean,
    onClick: (() -> Unit)?,
    onClickLabel: String?,
    content: @Composable () -> Unit,
) {
    val colors = RaftTheme.colors
    val shape = RoundedCornerShape(RaftRadius.Container)
    val source = remember { MutableInteractionSource() }
    val isPressed = raftIsPressed(source)
    val pressed = onClick != null && isPressed
    val isFocused = raftIsFocused(source)
    val focused = onClick != null && isFocused
    Box(
        modifier
            .fillMaxWidth()
            .raftFocusRing(focused, RaftRadius.Container, colors)
            .clip(shape)
            .background(if (muted) colors.fillMuted else colors.layerCard, shape)
            .raftPressOverlay(pressed, colors.fillMedium, RaftRadius.Container)
            .then(
                if (onClick != null) {
                    Modifier.clickable(source, indication = null, role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .border(RaftStroke.Hairline, colors.lineHairline, shape),
    ) {
        content()
    }
}

/**
 * raft-ui `MessageQuotedPreview` in the web's preview card ([QuotedPreviewShell]).
 *
 * The header (`mb-1`, items 8dp apart, top-aligned) wraps its parts 6 x 4dp apart and puts
 * the [time] at the end of its first line:
 * - [channel], where the message was posted (`footnote` semibold, `foreground-hint`): a 14dp
 *   `hash` before a channel's name, or the "@name" of a direct message ([direct]) with no icon;
 * - a middle dot, then the author: the [avatar] (an `xs` face), [authorName] (`footnote` bold,
 *   `foreground-muted`, truncates first), [authorDelegatedBy] ("(sent by Friday)", never cut)
 *   and [authorMeta] (a role, `caption` `foreground-hint`);
 * - with [threadLabel], a dot and the thread marker (`message-square` 14 + "Thread");
 * - with [archivedLabel], a dot and a muted soft Badge.
 *
 * Then the excerpt [text] (`callout`, `foreground-muted`, cut at 2 lines) and, with
 * [attachments], 22dp chips 6dp under it ("2 files": `fill-muted`, `rounded-chip`, 6dp sides,
 * Space Mono `mono-meta` in `foreground-muted`, no edge).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RaftMessageQuotedPreview(
    authorName: String,
    text: String,
    modifier: Modifier = Modifier,
    channel: String? = null,
    direct: Boolean = false,
    threadLabel: String? = null,
    archivedLabel: String? = null,
    avatar: (@Composable () -> Unit)? = null,
    authorDelegatedBy: (@Composable () -> Unit)? = null,
    authorMeta: String? = null,
    time: (@Composable () -> Unit)? = null,
    attachments: List<String> = emptyList(),
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val hintPart = typography.footnote.copy(fontWeight = FontWeight.SemiBold)
    QuotedPreviewShell(modifier, muted = false, onClick = onClick, onClickLabel = onClickLabel) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                FlowRow(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (channel != null) {
                        Row(Modifier.align(Alignment.CenterVertically), verticalAlignment = Alignment.CenterVertically) {
                            if (!direct) {
                                RaftGlyphIcon(RaftGlyph.Hash, size = 14.dp, color = colors.foregroundHint)
                                Box(Modifier.width(4.dp))
                            }
                            RaftText(
                                if (direct) "@$channel" else channel,
                                style = hintPart,
                                color = colors.foregroundHint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (channel != null) QuotedDot(Modifier.align(Alignment.CenterVertically))
                    Row(
                        Modifier.align(Alignment.CenterVertically),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        avatar?.invoke()
                        RaftText(
                            authorName,
                            modifier = Modifier.weight(1f, fill = false),
                            style = typography.footnote.copy(fontWeight = FontWeight.Bold),
                            color = colors.foregroundMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        authorDelegatedBy?.invoke()
                        if (authorMeta != null) {
                            RaftText(
                                authorMeta,
                                modifier = Modifier.weight(1f, fill = false),
                                style = typography.caption,
                                color = colors.foregroundHint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (threadLabel != null) {
                        QuotedDot(Modifier.align(Alignment.CenterVertically))
                        Row(
                            Modifier.align(Alignment.CenterVertically),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RaftGlyphIcon(RaftGlyph.MessageSquare, size = 14.dp, color = colors.foregroundHint)
                            RaftText(threadLabel, style = hintPart, color = colors.foregroundHint, maxLines = 1)
                        }
                    }
                    if (archivedLabel != null) {
                        QuotedDot(Modifier.align(Alignment.CenterVertically))
                        RaftBadge(
                            text = archivedLabel,
                            modifier = Modifier.align(Alignment.CenterVertically),
                            appearance = RaftBadgeAppearance.Soft,
                            variant = RaftBadgeVariant.Muted,
                        )
                    }
                }
                if (time != null) {
                    // The time centres on the first line of the header (an 18dp footnote line,
                    // or the 19dp face).
                    Box(Modifier.heightIn(min = 19.dp), contentAlignment = Alignment.Center) { time() }
                }
            }
            RaftText(
                text,
                style = typography.callout,
                color = colors.foregroundMuted,
                maxLines = RaftMessageQuotedPreviewDefaults.TextMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
            if (attachments.isNotEmpty()) {
                FlowRow(
                    Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    attachments.forEach { label -> RaftMessageQuotedPreviewAttachment(label) }
                }
            }
        }
    }
}

@Composable
private fun QuotedDot(modifier: Modifier = Modifier) {
    RaftText(
        "·",
        modifier = modifier.clearAndSetSemantics {},
        style = RaftTheme.typography.footnote,
        color = RaftTheme.colors.foregroundHint,
        maxLines = 1,
    )
}

/**
 * raft-ui `MessageQuotedPreviewAttachment`: a 22dp chip, `fill-muted`, `rounded-chip`, 6dp
 * sides, the [label] ("2 files") in Space Mono `mono-meta`, `foreground-muted`, no edge.
 */
@Composable
fun RaftMessageQuotedPreviewAttachment(label: String, modifier: Modifier = Modifier) {
    val colors = RaftTheme.colors
    Box(
        modifier
            .height(22.dp)
            .clip(RoundedCornerShape(RaftRadius.Chip))
            .background(colors.fillMuted)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        RaftText(label, style = RaftTheme.typography.monoMeta, color = colors.foregroundMuted, maxLines = 1)
    }
}

/**
 * The quoted message while it loads (web QuotedMessagePermalinkPreview): the card with a 34dp
 * header strip over a hairline, then a 28dp block and three pulsing lines (128dp, full and
 * three quarters wide, 8dp apart) in 12 x 10dp padding, items 12dp apart. [label] ("Loading
 * quoted message") is what assistive tech reads; the skeleton itself is hidden.
 */
@Composable
fun RaftMessageQuotedPreviewLoading(label: String, modifier: Modifier = Modifier) {
    val colors = RaftTheme.colors
    QuotedPreviewShell(
        modifier.semantics { contentDescription = label },
        muted = false,
        onClick = null,
        onClickLabel = null,
    ) {
        Column(Modifier.fillMaxWidth()) {
            val hairline = colors.lineHairline
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .drawBehind {
                        val y = size.height - RaftStroke.Hairline.toPx() / 2
                        drawLine(hairline, Offset(0f, y), Offset(size.width, y), RaftStroke.Hairline.toPx())
                    },
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RaftSkeleton(Modifier.size(28.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RaftSkeleton(Modifier.width(128.dp), variant = RaftSkeletonVariant.Line)
                    RaftSkeleton(Modifier.fillMaxWidth(), variant = RaftSkeletonVariant.Line)
                    RaftSkeleton(Modifier.fillMaxWidth(0.75f), variant = RaftSkeletonVariant.Line)
                }
            }
        }
    }
}

/**
 * raft-ui `MessageQuotedPreviewUnavailable` in the web's muted preview card: `fill-muted`, the
 * hairline, container radius, 12 x 10dp padding and one `callout` line in `foreground-hint`
 * saying why ("Message unavailable"). Not a control: there is nothing to open.
 */
@Composable
fun RaftMessageQuotedPreviewUnavailable(text: String, modifier: Modifier = Modifier) {
    QuotedPreviewShell(modifier, muted = true, onClick = null, onClickLabel = null) {
        RaftText(
            text,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            style = RaftTheme.typography.callout,
            color = RaftTheme.colors.foregroundHint,
        )
    }
}
