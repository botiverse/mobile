package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.model.ExternalMessageAuthor
import build.raft.app.model.providerLabel
import build.raft.designsystem.components.RaftAvatar
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftAvatarType
import build.raft.designsystem.components.RaftPreviewCardHeader
import build.raft.designsystem.components.RaftPreviewCardTitle
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftStroke

/**
 * Web `ExternalIdentityPreviewBody` (`components/message/ExternalIdentityPreviewCard.tsx`): who a
 * Slack-bridged (external) author is, in a [build.raft.designsystem.components.RaftPreviewCard]
 * on their avatar or their @mention. Never a Raft member: no profile, no actions.
 *
 * - The avatar (the provider's picture, else the cyan link sticker the message row shows).
 * - The name (`text-headline`), then "Slack · Human" (`text-footnote` `foreground-muted`; the kind
 *   alone without a provider), "From Acme" when the workspace is known, and "External identity"
 *   (`text-caption` `foreground-hint`).
 *
 * The web's App line and "Posted as" need the author's App and linked profile, which the app's
 * message author does not carry yet.
 */
@Composable
internal fun RaftExternalIdentityPreviewCardContent(
    author: ExternalMessageAuthor,
    /** The name to show: [externalAuthorShownName] (the localized "External user" when unnamed). */
    shownName: String,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    // Web `flex items-start gap-3 p-4`.
    RaftPreviewCardHeader(leading = { ExternalIdentityAvatar(author, shownName) }) {
        RaftPreviewCardTitle(shownName)
        val kind = stringResource(author.actorKindLabel())
        val provider = author.providerLabel()
        RaftText(
            if (provider != null) stringResource(Res.string.message_external_identity_provider_and_kind, provider, kind) else kind,
            style = typography.footnote,
            color = colors.foregroundMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        author.workspaceName?.let { workspace ->
            Spacer(Modifier.height(4.dp))
            RaftText(
                stringResource(Res.string.message_external_identity_from_workspace, workspace),
                style = typography.footnote,
                color = colors.foregroundMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(4.dp))
        RaftText(
            stringResource(Res.string.message_external_identity_projection),
            style = typography.caption,
            color = colors.foregroundHint,
        )
    }
}

/** Web `EXTERNAL_ACTOR_KIND_MESSAGE_IDS`: `bot` reads "App"; anything unknown "External participant". */
internal fun ExternalMessageAuthor.actorKindLabel() =
    when (actorKind) {
        "human" -> Res.string.message_external_identity_kind_human
        "guest" -> Res.string.message_external_identity_kind_guest
        "remote" -> Res.string.message_external_identity_kind_remote
        "bot" -> Res.string.message_external_identity_kind_app
        else -> Res.string.message_external_identity_kind_unknown
    }

@Composable
private fun ExternalIdentityAvatar(author: ExternalMessageAuthor, shownName: String) {
    val size = RaftAvatarSize.Lg
    val inner = size.edge - RaftStroke.Control * 2
    val url = author.avatarUrl
    RaftAvatar(
        type = RaftAvatarType.Human,
        size = size,
        image = {
            if (url != null) {
                RaftAppAvatar(avatarWireValue = url, label = shownName, avatarSize = inner, borderWidth = 0.dp)
            } else {
                // The message row's external sticker (cyan, a link) without its own edge.
                RaftAppAvatarFrame(modifier = Modifier, avatarSize = inner, borderWidth = 0.dp, fill = RaftColor.AccentCyan) {
                    RaftLucideIcon(RaftLucideIconName.Link, modifier = Modifier.size(24.dp), color = raftArgbHex(RaftColor.StickerInk))
                }
            }
        },
    )
}
