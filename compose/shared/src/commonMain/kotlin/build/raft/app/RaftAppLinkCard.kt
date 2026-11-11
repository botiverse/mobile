package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.data.AppLinkResult
import build.raft.app.data.ArtifactHome
import build.raft.app.data.ArtifactSummary
import build.raft.app.resources.*
import build.raft.app.data.RaftAppLinkCard
import build.raft.app.data.appLinkDomain
import build.raft.app.markdown.raftAppLinkKeepEnd
import build.raft.designsystem.components.RaftMessageEmbedDefaults
import build.raft.designsystem.components.RaftPopoverSurface
import build.raft.designsystem.components.RaftPreviewCardBody
import build.raft.designsystem.components.RaftPreviewCardButton
import build.raft.designsystem.components.RaftPreviewCardButtonKind
import build.raft.designsystem.components.RaftPreviewCardDefaults
import build.raft.designsystem.components.RaftPreviewCardDetail
import build.raft.designsystem.components.RaftPreviewCardEyebrow
import build.raft.designsystem.components.RaftPreviewCardItemActions
import build.raft.designsystem.components.RaftPreviewCardItemTitle
import build.raft.designsystem.components.RaftPreviewCardMeta
import build.raft.designsystem.components.RaftSeparator
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

/**
 * An App link's item card (web `AppLinkChip` hover card: raft-ui `PreviewCardContent
 * size="item"` with `PreviewCardBody` and `PreviewCardFooter`, raft-design #939 / slock #12361),
 * drawn with the design system on the popover surface (`layer-popover`, container radius,
 * `line-seam` ring, `shadow-float`).
 *
 * - Body: [eyebrow] (the App's id for the item, when it sends one), the App's title
 *   ([RaftPreviewCardItemTitle]: up to 2 lines, the whole title in a tooltip when cut), [details]
 *   (the state chip, then the details on one line separated by "·": [RaftPreviewCardMeta]), then
 *   [RaftAppLinkCardBody] for the App's v1 tree, when [card] has one, or [RaftAppLinkCardV2Body] for
 *   its v2 tree (the batched read).
 * - [buttons]: the App's buttons in its order, then Raft's own (Open Link), laid out by
 *   [RaftPreviewCardItemActions]: at most two on one row at the card's end (the App's first two
 *   actions, else the first link; frameless first, the bordered one last), everything else in the
 *   More menu (⋯) to their left.
 * - Footer: under a hairline, the App's name and the link's domain. No App icon: raft-ui keeps
 *   it on the chip, the App's only mark of its own.
 *
 * Render only: no reading, no actions run here. [onAction] is for v2's Buttons (RFC 076), which
 * report their element id; the v1 catalog has none, so it is never called yet. Width: the
 * incoming constraints ([RaftPreviewCardDefaults.ItemWidth], 400dp, where there is room).
 */
@Composable
internal fun RaftAppLinkCardView(
    card: AppLinkResult.Card,
    onAction: (elementId: String) -> Unit,
    modifier: Modifier = Modifier,
    buttons: List<RaftPreviewCardButton> = emptyList(),
    eyebrow: String? = null,
    details: List<RaftPreviewCardDetail> = emptyList(),
) {
    RaftPopoverSurface(modifier = modifier, width = null, contentPadding = PaddingValues(0.dp)) {
        // Body and actions scroll when the card is held shorter than they are (a popover in a
        // short window); the footer, where the link goes, stays.
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            RaftPreviewCardBody {
                eyebrow?.let { RaftPreviewCardEyebrow(it) }
                if (card.connectUrl != null) {
                    // connect_account (version 3): Raft's own words, one button to the App's connect URL.
                    RaftPreviewCardItemTitle(stringResource(Res.string.thread_link_preview_connect_title, card.app.name))
                    RaftText(
                        text = stringResource(Res.string.thread_link_preview_connect_body, card.app.name),
                        style = RaftTheme.typography.callout,
                        color = RaftTheme.colors.foregroundMuted,
                    )
                } else {
                    RaftPreviewCardItemTitle(card.title)
                    RaftPreviewCardMeta(details)
                    card.card?.let { RaftAppLinkCardBody(it, onAction) }
                    card.cardV2?.let { RaftAppLinkCardV2Body(it) }
                }
            }
            RaftPreviewCardItemActions(buttons)
        }
        RaftAppLinkCardFooter(appName = card.app.name, domain = appLinkDomain(card.url).orEmpty())
    }
}

/**
 * Raft's own Open Link on an App link's item card (the chip opens the card on a tap, so the card
 * must offer the link): a link out of Raft (↗), bordered. It goes after the App's buttons, so a
 * link of the App's own comes first and an App's actions keep the row.
 */
@Composable
internal fun raftAppLinkOpenLinkButton(onOpenLink: () -> Unit): RaftPreviewCardButton =
    RaftPreviewCardButton(
        label = stringResource(Res.string.thread_link_preview_open_link),
        kind = RaftPreviewCardButtonKind.Link,
        onClick = onOpenLink,
    )

/**
 * The button a chip's card offers for [card] ([onOpen] gets the address to open): Open Link to the
 * link, or, for a `connect_account` answer, Connect account to the App's connect URL, the card's
 * one button. A link out of Raft (↗), bordered.
 */
@Composable
internal fun raftAppLinkCardOpenButton(card: AppLinkResult.Card, onOpen: (String) -> Unit): RaftPreviewCardButton {
    val connectUrl = card.connectUrl ?: return raftAppLinkOpenLinkButton { onOpen(card.url) }
    return RaftPreviewCardButton(
        label = stringResource(Res.string.thread_link_preview_connect_action),
        kind = RaftPreviewCardButtonKind.Link,
        onClick = { onOpen(connectUrl) },
    )
}

/**
 * An Artifact link's item card (web `ArtifactLinkPreview.tsx` in core's `ObjectLinkChip` hover
 * card): the title; the details row (the file type, the version, where it lives), then the
 * description or the start of its text, two lines; the footer names the app alone ("Artifacts"),
 * no domain: the link is Raft's own. Not drawn yet: the web's fact line ("6 pages") and "updated by".
 * An item card (raft-design #939): the title up to 2 lines, [buttons] at the card's end
 * ([RaftPreviewCardItemActions]).
 */
@Composable
internal fun RaftArtifactLinkCardView(
    summary: ArtifactSummary,
    modifier: Modifier = Modifier,
    buttons: List<RaftPreviewCardButton> = emptyList(),
) {
    val home =
        when (val where = summary.home) {
            is ArtifactHome.Conversation ->
                where.name.takeIf { it.isNotBlank() }?.let {
                    if (where.dm) stringResource(Res.string.artifacts_home_dm, it) else "#$it"
                }
            ArtifactHome.Server -> stringResource(Res.string.artifacts_scope_server)
            ArtifactHome.Public -> stringResource(Res.string.artifacts_scope_public)
        }
    // PreviewCardMeta (web ArtifactLinkPreview): no state chip, the details on one line apart by "·".
    val details =
        listOfNotNull(summary.kind.badge, stringResource(Res.string.artifacts_card_version, summary.version), home)
            .map { RaftPreviewCardDetail.Text(it) }
    RaftPopoverSurface(modifier = modifier, width = null, contentPadding = PaddingValues(0.dp)) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            RaftPreviewCardBody {
                RaftPreviewCardItemTitle(summary.title)
                RaftPreviewCardMeta(details)
                summary.description?.let {
                    RaftText(
                        text = it,
                        style = RaftTheme.typography.footnote,
                        color = RaftTheme.colors.foregroundMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            RaftPreviewCardItemActions(buttons)
        }
        RaftAppLinkCardFooter(appName = stringResource(Res.string.artifacts_app_name), domain = "")
    }
}

/**
 * The App's tree, from its root (web `defineRegistry` in `AppLinkChip.tsx`), drawn by the card
 * renderer link cards and action cards share ([RaftCardTree]): a Stack is a column 6dp apart; a
 * Text is callout in `foreground` or, `muted`, `foreground-muted`, and wraps; a Badge is the
 * item's state in the App's words, a neutral soft Badge, never a status colour (DS PreviewCard
 * rules). The tree arrives validated; a missing id, a node past depth 4 or an element this
 * client doesn't know draws nothing. [onAction] is v2's (see [RaftAppLinkCardView]).
 */
@Composable
internal fun RaftAppLinkCardBody(
    card: RaftAppLinkCard,
    onAction: (elementId: String) -> Unit,
) {
    val context = remember(onAction) { RaftCardTreeContext(onAction = onAction) }
    RaftCardTree(card.root, card.elements, context)
}

/**
 * PreviewCardFooter (raft-ui 0.84.0 `messageCard` `sourceFooter`): a caption under a hairline,
 * px-3 py-2: the App's name, then the link's domain. The name gives way first; a domain too long
 * loses its start, never its end.
 */
@Composable
private fun RaftAppLinkCardFooter(appName: String, domain: String) {
    val muted = RaftTheme.colors.foregroundMuted
    val caption = RaftTheme.typography.caption
    RaftSeparator()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = RaftMessageEmbedDefaults.Inset, vertical = 8.dp),
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
