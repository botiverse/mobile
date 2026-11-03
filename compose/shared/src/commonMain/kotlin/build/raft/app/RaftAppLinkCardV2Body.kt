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
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
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
 *   neutral soft badge whatever its tone (DS PreviewCard: an App's states are its own);
 *   RelativeTime and Duration are muted.
 * - Buttons are not drawn here (step 1 declares no `button.v1`, so Raft strips them anyway); a
 *   Stack that draws only buttons draws nothing and takes no place, nor a "·".
 */
@Composable
internal fun RaftAppLinkCardV2Body(card: RaftAppLinkCardV2) {
    RaftAppLinkCardV2Node(card, card.root, depth = 0)
}

@Composable
private fun RaftAppLinkCardV2Node(card: RaftAppLinkCardV2, id: String, depth: Int) {
    if (depth > RAFT_APP_LINK_CARD_MAX_DEPTH) return
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    when (val node = card.elements[id]) {
        is CardStack -> {
            if (node.direction == CardStackDirection.Row) {
                val parts = remember(card, id) { appLinkCardV2RowParts(card, node, depth) }
                if (parts.isEmpty()) return
                RaftPreviewCardMeta(details = parts.map { it.detail() }, wrap = node.wrap)
            } else {
                val children = node.childIds.filter { appLinkCardV2Draws(card, it, depth + 1) }
                if (children.isEmpty()) return
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    children.forEach { RaftAppLinkCardV2Node(card, it, depth + 1) }
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
        is RaftLinkCardV2Element.Badge -> RaftBadge(text = node.text, appearance = RaftBadgeAppearance.Soft)
        is CardRelativeTime ->
            RaftText(
                text = raftRelativeTimestampLabel(node.at, clampFuture = true),
                style = typography.monoMeta,
                color = colors.foregroundMuted,
                maxLines = 1,
            )
        is CardDuration ->
            raftCardDurationText(node)?.let { RaftText(text = it, style = typography.monoMeta, color = colors.foregroundMuted, maxLines = 1) }
        // Buttons are drawn by the card's actions row, not here; a missing id draws nothing.
        else -> Unit
    }
}

/** One part of a row Stack's details row ([appLinkCardV2RowParts]). */
internal sealed interface AppLinkCardV2RowPart {
    data class State(val text: String) : AppLinkCardV2RowPart

    data class Text(val text: String, val tone: RaftLinkCardTextTone) : AppLinkCardV2RowPart

    data class Time(val at: String) : AppLinkCardV2RowPart

    data class Duration(val node: CardDuration) : AppLinkCardV2RowPart
}

@Composable
private fun AppLinkCardV2RowPart.detail(): RaftPreviewCardDetail =
    when (this) {
        is AppLinkCardV2RowPart.State -> RaftPreviewCardDetail.State(text)
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
            is RaftLinkCardV2Element.Badge -> listOf(AppLinkCardV2RowPart.State(node.text))
            is RaftLinkCardV2Element.Text -> listOf(AppLinkCardV2RowPart.Text(node.text, node.tone))
            is CardRelativeTime -> listOf(AppLinkCardV2RowPart.Time(node.at))
            is CardDuration -> listOf(AppLinkCardV2RowPart.Duration(node))
            is CardStack -> appLinkCardV2RowParts(card, node, depth + 1)
            else -> emptyList()
        }
    }

/** Whether an element draws anything here (web `drawsSomething`): not a Button, not a missing id, not a Stack of only those. */
internal fun appLinkCardV2Draws(card: RaftAppLinkCardV2, id: String, depth: Int): Boolean {
    val node: CardElement = card.elements[id] ?: return false
    if (depth > RAFT_APP_LINK_CARD_MAX_DEPTH || node is RaftLinkCardV2Element.Button) return false
    if (node !is CardStack) return true
    return node.childIds.any { appLinkCardV2Draws(card, it, depth + 1) }
}
