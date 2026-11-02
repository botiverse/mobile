package build.raft.app.components

import build.raft.app.resources.*
import build.raft.app.raftDisplayUppercase
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant

/** task #396: wire value of `channel.bridge.provider` for a Slack-bridged channel. */
internal const val RaftExternalBridgeProviderSlack = "slack"

/** Only providers mobile knows how to label render a badge; unknown providers stay hidden. */
internal fun externalBridgeBadgeVisible(bridgeProvider: String?): Boolean =
    bridgeProvider?.trim()?.lowercase() == RaftExternalBridgeProviderSlack

/**
 * task #396: `[Slack]` tag rendered after a bridged channel's name in the Home channel list and
 * the conversation header. Web Sidebar's `Badge appearance="soft" variant="accent" uppercase`:
 * the design-system soft accent badge.
 */
@Composable
internal fun RaftExternalBridgeBadge(
    bridgeProvider: String?,
    modifier: Modifier = Modifier
) {
    if (!externalBridgeBadgeVisible(bridgeProvider)) return
    RaftBadge(
        text = stringResource(Res.string.channel_bridge_provider_slack).raftDisplayUppercase(),
        modifier = modifier,
        appearance = RaftBadgeAppearance.Soft,
        variant = RaftBadgeVariant.Accent
    )
}
