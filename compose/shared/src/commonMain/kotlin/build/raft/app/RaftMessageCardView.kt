package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import build.raft.app.data.RaftLinkCardButtonAction
import build.raft.app.data.RaftLinkCardButtonVariant
import build.raft.app.data.RaftLinkCardV2Element
import build.raft.app.data.MESSAGE_CARDS_FEATURE_FLAG_KEY
import build.raft.app.data.RaftMessageCard
import build.raft.app.data.messageCardRevisionOf
import build.raft.app.data.messageCardToRender
import build.raft.app.data.rememberServerFeatureLatched
import build.raft.app.model.ServerId
import build.raft.app.data.appLinkDomain
import build.raft.app.data.messageCardButtons
import build.raft.app.markdown.raftAppLinkKeepEnd
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftCard
import build.raft.designsystem.components.RaftCardFooterText
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftMessageEmbedDefaults
import build.raft.designsystem.components.RaftTooltip
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

internal const val RaftMessageCardTestTag = "thread_message_card"

/**
 * The card a message row draws instead of its text: only a card message's, only where the
 * conversation's Server has `message_cards` on (latched for the row), and only a card that reads.
 * Null otherwise: the row draws the message's text, the card's fallback.
 */
@Composable
internal fun rememberThreadMessageCard(metadata: Map<String, String>): RaftMessageCard? {
    if (messageCardRevisionOf(metadata) == null) return null
    val host = LocalThreadAppLinkPreviewHost.current
    val enabled = rememberServerFeatureLatched(host?.featureFlags, host?.serverId?.let(::ServerId), MESSAGE_CARDS_FEATURE_FLAG_KEY)
    return remember(metadata, enabled) { messageCardToRender(metadata, enabled) }
}

/**
 * A card message's card in the message body (RFC 082 §5; web `MessageCardBody.tsx`, botiverse/slock
 * 1e9266662), under the sender's name as any message's words are: who posted it is the row's own
 * header, from Raft's records, never anything on the card.
 *
 * - A flat [RaftCard] (raft-ui Card): no header, icon, status or confirm, so it never reads as one
 *   of Raft's own action cards.
 * - The elements are drawn by the link card v2 renderer ([RaftAppLinkCardV2Body]): every valid
 *   message card is a valid link card v2. Badges are neutral (DS PreviewCard, raft-design #939).
 * - Buttons only leave Raft: each is a link with its label, its domain and ↗, in the App's order,
 *   wrapping onto more lines rather than hiding any. A button with the sender's `disabledReason`
 *   is disabled (no ↗) and the first reason is a caption under them, as touch has no hover. A URL
 *   this client can't open is never drawn ([onOpenUrl] gets only public HTTPS URLs).
 * - Once replaced (revision > 1) the footer says when ("Updated 3 min ago").
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RaftMessageCardView(
    card: RaftMessageCard,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val buttons = remember(card.card) { card.card.messageCardButtons().map { it.second } }
    val updatedAt = card.updatedAt?.takeIf { card.showsUpdated }
    RaftCard(
        modifier = modifier.widthIn(max = RaftMessageEmbedDefaults.MaxWidth).testTag(RaftMessageCardTestTag),
        footer =
            updatedAt?.let { at ->
                { RaftCardFooterText(stringResource(Res.string.message_card_updated, raftRelativeTimestampLabel(at, clampFuture = true))) }
            },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RaftAppLinkCardV2Body(card.card)
            if (buttons.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    buttons.forEach { button -> RaftMessageCardLink(button, onOpenUrl) }
                }
                buttons.firstNotNullOfOrNull { it.disabledReason }?.let { reason ->
                    RaftText(text = reason, style = RaftTheme.typography.footnote, color = RaftTheme.colors.foregroundMuted)
                }
            }
        }
    }
}

/** A card Button: a link out of Raft (label, domain, ↗), never an action; with a reason, disabled. */
@Composable
private fun RaftMessageCardLink(button: RaftLinkCardV2Element.Button, onOpenUrl: (String) -> Unit) {
    val url = (button.action as? RaftLinkCardButtonAction.Navigate)?.url ?: return
    val domain = appLinkDomain(url)?.let { raftAppLinkKeepEnd(it, 40) } ?: return
    val reason = button.disabledReason
    RaftTooltip(text = if (reason != null) "${button.label}\n$reason" else "${button.label} · $domain") {
        RaftButton(
            text = button.label,
            trailingText = if (reason == null) domain else null,
            onClick = { onOpenUrl(url) },
            variant = if (button.variant == RaftLinkCardButtonVariant.Ghost) RaftButtonVariant.Ghost else RaftButtonVariant.Default,
            enabled = reason == null,
            trailingIcon = if (reason == null) { { RaftGlyphIcon(RaftGlyph.ArrowUpRight, size = 16.dp) } } else null,
        )
    }
}
