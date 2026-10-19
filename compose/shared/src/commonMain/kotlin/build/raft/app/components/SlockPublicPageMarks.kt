package build.raft.app.components

import androidx.compose.runtime.Composable
import build.raft.app.SlockColor
import build.raft.app.SlockLucideIcon
import build.raft.app.SlockLucideIconName
import build.raft.app.resources.MR
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tencent.tmm.kmmresource.compose.stringResource

/**
 * Web parity for "anyone can read this channel on the server's public page"
 * (botiverse/slock@9df0d1b3f packages/web/src/components/channel/channelKindIcon.tsx:22 and
 * packages/web/src/components/layout/Sidebar.tsx:894 / message/ChatPanel.tsx:1366):
 * only a regular `channel` (never private / joint / DM), and only while the server's public page
 * is live, the channel is visible to Guests, and the channel is not archived.
 */
internal fun channelOnPublicPage(
    serverPublicPageLive: Boolean,
    channelKind: String?,
    guestVisible: Boolean,
    archived: Boolean,
): Boolean =
    serverPublicPageLive &&
        guestVisible &&
        !archived &&
        channelKind?.trim()?.lowercase() == "channel"

/**
 * The channel-kind glyph for a channel readable on the public page. Web draws one combined
 * "hash with a globe in its bottom-right cell" SVG in place of the `#`
 * (channelKindIcon.tsx `HashGlobeIcon`). Mobile icons are native Lucide renders, so the same
 * mark is composed from the Lucide `hash` (top-left) and a smaller `globe` (bottom-right) inside
 * the same square the plain `#` would occupy. Screen readers get the meaning as text
 * (`channel.publicPageReadable`).
 */
@Composable
internal fun SlockHashGlobeIcon(
    size: Dp,
    color: String = SlockColor.TextBlackArgb,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(MR.strings.channel_public_page_readable)
    Box(
        modifier = modifier.size(size).semantics { contentDescription = label },
    ) {
        SlockLucideIcon(
            SlockLucideIconName.Hash,
            Modifier.size(size * HashGlobeHashScale).align(Alignment.TopStart),
            color = color,
        )
        SlockLucideIcon(
            SlockLucideIconName.Globe,
            Modifier.size(size * HashGlobeGlobeScale).align(Alignment.BottomEnd),
            color = color,
        )
    }
}

/**
 * Small Globe after a server's name when its public page is live (web
 * packages/web/src/components/server/PublicPageGlobe.tsx). Renders nothing when not live.
 * Accessibility text is `layout.server.publicPageLive`.
 */
@Composable
internal fun SlockPublicPageGlobe(
    live: Boolean,
    size: Dp = 13.dp,
    color: String = SlockColor.TextMutedGrayArgb,
    modifier: Modifier = Modifier,
) {
    if (!live) return
    val label = stringResource(MR.strings.layout_server_public_page_live)
    Box(
        modifier = modifier.size(size).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        SlockLucideIcon(SlockLucideIconName.Globe, Modifier.size(size), color = color)
    }
}

private const val HashGlobeHashScale = 0.74f
private const val HashGlobeGlobeScale = 0.56f
