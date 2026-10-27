package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
 * - [actions]: PreviewCardActions, at most two buttons, between the body and the footer.
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
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    RaftPopoverSurface(modifier = modifier, width = null, contentPadding = PaddingValues(0.dp)) {
        // Body and actions scroll when the card is held shorter than they are (a popover in a
        // short window); the footer, where the link goes, stays.
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
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
            // PreviewCardActions: at most two buttons, px-4 pb-3, gap-2 (a footer follows).
            if (actions != null) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
        }
        RaftAppLinkCardFooter(appName = card.app.name, domain = appLinkDomain(card.url).orEmpty())
    }
}

/**
 * An Artifact link's item card (web `ArtifactLinkPreview.tsx` in core's `ObjectLinkChip` hover
 * card): the title; the details row (the file type, the version, where it lives), then the
 * description or the start of its text, two lines; the footer names the app alone ("Artifacts"),
 * no domain: the link is Raft's own. Not drawn yet: the web's fact line ("6 pages") and "updated by".
 */
@Composable
internal fun RaftArtifactLinkCardView(
    summary: ArtifactSummary,
    modifier: Modifier = Modifier,
    actions: (@Composable RowScope.() -> Unit)? = null,
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
    val details =
        listOfNotNull(summary.kind.badge, stringResource(Res.string.artifacts_card_version, summary.version), home)
            .joinToString(" · ")
    RaftPopoverSurface(modifier = modifier, width = null, contentPadding = PaddingValues(0.dp)) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            Column(
                Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                RaftText(
                    text = summary.title,
                    style = RaftTheme.typography.headline,
                    color = RaftTheme.colors.foreground,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                RaftText(text = details, style = RaftTheme.typography.footnote, color = RaftTheme.colors.foregroundMuted)
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
            if (actions != null) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
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
