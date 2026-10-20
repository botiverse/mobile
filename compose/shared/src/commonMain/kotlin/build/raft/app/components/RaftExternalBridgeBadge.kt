package build.raft.app.components

import build.raft.app.raftDisplayUppercase
import androidx.compose.runtime.Composable
import build.raft.app.RaftColor
import build.raft.app.RaftMonoFamily
import build.raft.app.RaftAppStroke
import build.raft.app.RaftType
import build.raft.app.resources.MR
import build.raft.app.raftSquareCorners
import build.raft.app.withRaftFontWeight
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tencent.tmm.kmmresource.compose.stringResource

/** task #396: wire value of `channel.bridge.provider` for a Slack-bridged channel. */
internal const val RaftExternalBridgeProviderSlack = "slack"

/** Only providers mobile knows how to label render a badge; unknown providers stay hidden. */
internal fun externalBridgeBadgeVisible(bridgeProvider: String?): Boolean =
    bridgeProvider?.trim()?.lowercase() == RaftExternalBridgeProviderSlack

/**
 * task #396: `[Slack]` tag rendered after a bridged channel's name in the Home channel list and
 * the conversation header. Mirrors Web Sidebar's `Badge appearance="soft" variant="accent"
 * uppercase` under the brutal theme: lavender fill, black text, black border, small bold mono.
 */
@Composable
internal fun RaftExternalBridgeBadge(
    bridgeProvider: String?,
    modifier: Modifier = Modifier
) {
    if (!externalBridgeBadgeVisible(bridgeProvider)) return
    Box(
        modifier =
            modifier
                .border(RaftAppStroke.Thin, RaftColor.StickerInk, RectangleShape)
                .background(RaftColor.AccentLavender, RectangleShape)
                .raftSquareCorners()
                .padding(horizontal = 4.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(MR.strings.channel_bridge_provider_slack).raftDisplayUppercase(),
            style =
                RaftType.Caption
                    .copy(
                        fontFamily = RaftMonoFamily,
                        fontSize = 9.sp,
                        lineHeight = TextUnit.Unspecified,
                        letterSpacing = 0.4.sp
                    )
                    .withRaftFontWeight(FontWeight.Bold),
            color = RaftColor.StickerInk,
            maxLines = 1
        )
    }
}
