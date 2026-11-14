package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.data.CardDuration
import build.raft.app.data.CardElement
import build.raft.app.data.CardRelativeTime
import build.raft.app.data.CardStack
import build.raft.app.data.CardStackDirection
import build.raft.app.data.RAFT_APP_LINK_CARD_MAX_DEPTH
import build.raft.app.data.RaftAppLinkCardV2
import build.raft.app.data.RaftLinkCardTextTone
import build.raft.app.data.RaftLinkCardV2Element
import build.raft.app.data.RaftMessageCardElement
import build.raft.designsystem.components.RaftSeparator
import build.raft.designsystem.components.RaftAppLinkCardBadge
import build.raft.designsystem.components.RaftAppLinkCardBadgeSurface
import build.raft.designsystem.components.RaftPreviewCardDetail
import build.raft.designsystem.components.RaftPreviewCardDetailTone
import build.raft.designsystem.components.RaftPreviewCardMeta
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

/**
 * A v2 App link card's elements in the item card (web `LinkCardElements.tsx`, botiverse/slock
 * 8de484350 / #12361): the App picks only the structure; spacing, faces and colours are the design
 * system's.
 *
 * - A column Stack is a column 6dp apart; a row Stack is the item card's details row
 *   ([RaftPreviewCardMeta]: the state chip, then the details on one line apart by "·", none next
 *   to the chip), wrapping only when the App asks.
 * - Text is callout (mono: `text-mono-meta`) in its tone (`warning`: warning-strong); a Badge is a
 *   neutral soft badge whatever its tone (DS PreviewCard: an App's states are its own), filled with
 *   the App's `color` when a link card gives one (`badge.color.v1`, [RaftAppLinkCardBadge] decides
 *   the type and the ring against [surface]: the block card or the hover card);
 *   RelativeTime and Duration are muted.
 * - Buttons are not drawn here (step 1 declares no `button.v1`, so Raft strips them anyway); a
 *   Stack that draws only buttons draws nothing and takes no place, nor a "·".
 * - A card message's own elements (RFC 082 v2.0 §4.8; link cards never carry them): `Markdown`
 *   through the message renderer restricted to the card subset ([RaftMessageCardMarkdown]),
 *   `Fields` as a horizontal DescriptionList ([RaftMessageCardFields]), `Divider` as a
 *   Separator. In a row each is a block of its own.
 */
@Composable
internal fun RaftAppLinkCardV2Body(card: RaftAppLinkCardV2, surface: RaftAppLinkCardBadgeSurface = RaftAppLinkCardBadgeSurface.Card) {
    RaftAppLinkCardV2Node(card, card.root, depth = 0, surface)
}

@Composable
private fun RaftAppLinkCardV2Node(card: RaftAppLinkCardV2, id: String, depth: Int, surface: RaftAppLinkCardBadgeSurface) {
    if (depth > RAFT_APP_LINK_CARD_MAX_DEPTH) return
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    when (val node = card.elements[id]) {
        is CardStack -> {
            if (node.direction == CardStackDirection.Row) {
                val segments = remember(card, id) { appLinkCardV2RowSegments(card, node, depth) }
                if (segments.isEmpty()) return
                if (segments.size == 1 && segments[0] is AppLinkCardV2RowSegment.Line) {
                    RaftPreviewCardMeta(details = (segments[0] as AppLinkCardV2RowSegment.Line).parts.map { it.detail() }, wrap = node.wrap, surface = surface)
                    return
                }
                // A message card's Markdown, Fields or Divider in a row is a block of its own (RFC 082 §4.8): the
                // row's line ends before it and starts again after it.
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    segments.forEach { segment ->
                        when (segment) {
                            is AppLinkCardV2RowSegment.Line -> RaftPreviewCardMeta(details = segment.parts.map { it.detail() }, wrap = node.wrap, surface = surface)
                            is AppLinkCardV2RowSegment.Block -> RaftAppLinkCardV2Node(card, segment.id, depth + 1, surface)
                        }
                    }
                }
            } else {
                val children = node.childIds.filter { appLinkCardV2Draws(card, it, depth + 1) }
                if (children.isEmpty()) return
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    children.forEach { RaftAppLinkCardV2Node(card, it, depth + 1, surface) }
                }
            }
        }
        is RaftLinkCardV2Element.Text ->
            RaftText(
                text = node.text,
                style = if (node.mono) typography.monoMeta else typography.callout,
                color =
                    when (node.tone) {
                        RaftLinkCardTextTone.Default -> colors.foreground
                        RaftLinkCardTextTone.Muted -> colors.foregroundMuted
                        RaftLinkCardTextTone.Warning -> colors.warningStrong
                    },
                overflow = TextOverflow.Ellipsis,
            )
        is RaftLinkCardV2Element.Badge -> RaftAppLinkCardBadge(text = node.text, fill = node.color, surface = surface)
        is CardRelativeTime ->
            RaftText(
                text = raftRelativeTimestampLabel(node.at, clampFuture = true),
                style = typography.monoMeta,
                color = colors.foregroundMuted,
                maxLines = 1,
            )
        is CardDuration ->
            raftCardDurationText(node)?.let { RaftText(text = it, style = typography.monoMeta, color = colors.foregroundMuted, maxLines = 1) }
        is RaftMessageCardElement.Markdown -> RaftMessageCardMarkdown(node.content)
        is RaftMessageCardElement.Fields -> RaftMessageCardFields(node)
        is RaftMessageCardElement.Divider -> RaftSeparator()
        // Buttons are drawn by the card's actions row, not here; a missing id draws nothing.
        else -> Unit
    }
}

/** One part of a row Stack's details row ([appLinkCardV2RowParts]). */
internal sealed interface AppLinkCardV2RowPart {
    data class State(val text: String, val fill: String? = null) : AppLinkCardV2RowPart

    data class Text(val text: String, val tone: RaftLinkCardTextTone) : AppLinkCardV2RowPart

    data class Time(val at: String) : AppLinkCardV2RowPart

    data class Duration(val node: CardDuration) : AppLinkCardV2RowPart
}

@Composable
private fun AppLinkCardV2RowPart.detail(): RaftPreviewCardDetail =
    when (this) {
        is AppLinkCardV2RowPart.State -> RaftPreviewCardDetail.State(text, fill)
        is AppLinkCardV2RowPart.Text ->
            RaftPreviewCardDetail.Text(
                text,
                when (tone) {
                    RaftLinkCardTextTone.Default -> RaftPreviewCardDetailTone.Default
                    RaftLinkCardTextTone.Muted -> RaftPreviewCardDetailTone.Muted
                    RaftLinkCardTextTone.Warning -> RaftPreviewCardDetailTone.Warning
                },
            )
        is AppLinkCardV2RowPart.Time -> RaftPreviewCardDetail.Text(raftRelativeTimestampLabel(at, clampFuture = true))
        is AppLinkCardV2RowPart.Duration -> RaftPreviewCardDetail.Text(raftCardDurationText(node).orEmpty())
    }

/**
 * What a row Stack draws, in the App's order: a Badge is the state chip, a Text, RelativeTime or
 * Duration a detail. A Stack inside the row gives its own parts in place (the details row is one
 * line); Buttons, and Stacks that draw only buttons, give nothing.
 */
internal fun appLinkCardV2RowParts(card: RaftAppLinkCardV2, stack: CardStack, depth: Int): List<AppLinkCardV2RowPart> =
    stack.childIds.flatMap { id ->
        if (depth + 1 > RAFT_APP_LINK_CARD_MAX_DEPTH) return@flatMap emptyList()
        when (val node = card.elements[id]) {
            is RaftLinkCardV2Element.Badge -> listOf(AppLinkCardV2RowPart.State(node.text, node.color))
            is RaftLinkCardV2Element.Text -> listOf(AppLinkCardV2RowPart.Text(node.text, node.tone))
            is CardRelativeTime -> listOf(AppLinkCardV2RowPart.Time(node.at))
            is CardDuration -> listOf(AppLinkCardV2RowPart.Duration(node))
            is CardStack -> appLinkCardV2RowParts(card, node, depth + 1)
            else -> emptyList()
        }
    }

/** A row Stack's run of children: one details line ([Line]), or a message card block element drawn on its own ([Block]). */
internal sealed interface AppLinkCardV2RowSegment {
    data class Line(val parts: List<AppLinkCardV2RowPart>) : AppLinkCardV2RowSegment

    data class Block(val id: String) : AppLinkCardV2RowSegment
}

/**
 * A row Stack as lines and blocks (web `MessageCardElements` row segments): consecutive children
 * that are not blocks ([appLinkCardV2IsBlock]) make one details line, each block stands alone.
 * A link card has no blocks, so its row is one line, as before.
 */
internal fun appLinkCardV2RowSegments(card: RaftAppLinkCardV2, stack: CardStack, depth: Int): List<AppLinkCardV2RowSegment> {
    val segments = mutableListOf<AppLinkCardV2RowSegment>()
    val line = mutableListOf<String>()
    fun endLine() {
        if (line.isEmpty()) return
        val parts = appLinkCardV2RowParts(card, stack.copy(children = line.toList()), depth)
        if (parts.isNotEmpty()) segments += AppLinkCardV2RowSegment.Line(parts)
        line.clear()
    }
    for (child in stack.childIds) {
        if (appLinkCardV2IsBlock(card, child, depth + 1)) {
            endLine()
            if (appLinkCardV2Draws(card, child, depth + 1)) segments += AppLinkCardV2RowSegment.Block(child)
        } else {
            line += child
        }
    }
    endLine()
    return segments
}

/** A message card's Markdown, Fields or Divider, or a Stack holding one (web `messageCardIsBlock`): never part of a row's line. */
internal fun appLinkCardV2IsBlock(card: RaftAppLinkCardV2, id: String, depth: Int): Boolean {
    val node = card.elements[id] ?: return false
    if (depth > RAFT_APP_LINK_CARD_MAX_DEPTH) return false
    if (node is RaftMessageCardElement) return true
    return node is CardStack && node.childIds.any { appLinkCardV2IsBlock(card, it, depth + 1) }
}

/** Whether an element draws anything here (web `drawsSomething`): not a Button, not a missing id, not a Stack of only those. */
internal fun appLinkCardV2Draws(card: RaftAppLinkCardV2, id: String, depth: Int): Boolean {
    val node: CardElement = card.elements[id] ?: return false
    if (depth > RAFT_APP_LINK_CARD_MAX_DEPTH || node is RaftLinkCardV2Element.Button) return false
    if (node !is CardStack) return true
    return node.childIds.any { appLinkCardV2Draws(card, it, depth + 1) }
}
