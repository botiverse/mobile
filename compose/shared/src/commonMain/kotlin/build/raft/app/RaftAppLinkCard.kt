package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.data.AppLinkResult
import build.raft.app.data.RAFT_APP_LINK_CARD_MAX_DEPTH
import build.raft.app.data.RaftAppLinkCard
import build.raft.app.data.RaftAppLinkCardElement
import build.raft.app.data.RaftAppLinkTextTone
import build.raft.app.data.appLinkDomain
import build.raft.app.markdown.raftAppLinkKeepEnd
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftPopoverSurface
import build.raft.designsystem.components.RaftSeparator
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

/**
 * An App link's item card (web `AppLinkChip` hover card: raft-ui `PreviewCardContent` with
 * `PreviewCardBody` and `PreviewCardFooter`), drawn with the design system on the popover
 * surface (`layer-popover`, container radius, `line-seam` ring, `shadow-float`).
 *
 * - Body: the App's full title (headline, up to 3 lines), then [RaftAppLinkCardBody] for the
 *   App's tree, when [card] has one.
 * - Footer: under a hairline, the App's name and the link's domain. No App icon: raft-ui keeps
 *   it on the chip, the App's only mark of its own.
 *
 * Render only: no reading, no actions run. [onAction] is for v2's Buttons (RFC 076), which
 * report their element id; the v1 catalog has none, so it is never called yet. Width: the
 * incoming constraints, at least 220dp; the web card is 300dp.
 */
@Composable
internal fun RaftAppLinkCardView(
    card: AppLinkResult.Card,
    onAction: (elementId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    RaftPopoverSurface(modifier = modifier, width = null, contentPadding = PaddingValues(0.dp)) {
        // PreviewCardBody: px-4 pt-4 pb-3, gap-1.5 (a footer always follows).
        Column(
            Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RaftText(
                text = card.title,
                style = RaftTheme.typography.headline,
                color = RaftTheme.colors.foreground,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            card.card?.let { RaftAppLinkCardBody(it, onAction) }
        }
        RaftAppLinkCardFooter(appName = card.app.name, domain = appLinkDomain(card.url).orEmpty())
    }
}

/**
 * The App's tree, from its root (web `defineRegistry` in `AppLinkChip.tsx`): a Stack is a
 * column 6dp apart; a Text is callout in `foreground` or, `muted`, `foreground-muted`, and wraps;
 * a Badge is the item's state in the App's words, a neutral soft Badge, never a status colour
 * (DS PreviewCard rules). The tree arrives validated; a missing id, a node past depth 4 or an
 * element this client doesn't know draws nothing. [onAction] is v2's (see [RaftAppLinkCardView]).
 */
@Composable
internal fun RaftAppLinkCardBody(
    card: RaftAppLinkCard,
    @Suppress("UNUSED_PARAMETER") onAction: (elementId: String) -> Unit,
) {
    RaftAppLinkCardNode(card, card.root, depth = 0)
}

@Composable
private fun RaftAppLinkCardNode(card: RaftAppLinkCard, id: String, depth: Int) {
    if (depth > RAFT_APP_LINK_CARD_MAX_DEPTH) return
    when (val node = card.element(id)) {
        is RaftAppLinkCardElement.Stack ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                node.children.forEach { RaftAppLinkCardNode(card, it, depth + 1) }
            }
        is RaftAppLinkCardElement.Text ->
            RaftText(
                text = node.text,
                style = RaftTheme.typography.callout,
                color = if (node.tone == RaftAppLinkTextTone.Muted) RaftTheme.colors.foregroundMuted else RaftTheme.colors.foreground,
            )
        is RaftAppLinkCardElement.Badge -> RaftBadge(text = node.text, appearance = RaftBadgeAppearance.Soft)
        // A missing id, or an element of a later catalog (v2) not drawn yet.
        else -> Unit
    }
}

/**
 * PreviewCardFooter: a caption under a hairline, px-4 py-2: the App's name, then the link's
 * domain. The name gives way first; a domain too long loses its start, never its end.
 */
@Composable
private fun RaftAppLinkCardFooter(appName: String, domain: String) {
    val muted = RaftTheme.colors.foregroundMuted
    val caption = RaftTheme.typography.caption
    RaftSeparator()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RaftText(
            text = appName,
            modifier = Modifier.weight(1f, fill = false),
            style = caption,
            color = muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (domain.isNotEmpty()) {
            RaftText(text = "·", style = caption, color = muted, maxLines = 1)
            RaftText(text = raftAppLinkKeepEnd(domain, 40), style = caption, color = muted, maxLines = 1, softWrap = false)
        }
    }
}
