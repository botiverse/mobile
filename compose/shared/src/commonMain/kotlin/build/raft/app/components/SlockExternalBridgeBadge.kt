package build.raft.app.components

import androidx.compose.runtime.Composable
import build.raft.app.SlockColor
import build.raft.app.SlockMonoFamily
import build.raft.app.SlockStroke
import build.raft.app.SlockType
import build.raft.app.resources.MR
import build.raft.app.slockSquareCorners
import build.raft.app.withSlockFontWeight
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.graphics.RectangleShape
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.unit.TextUnit
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.tmm.kmmresource.compose.stringResource

/** task #396: wire value of `channel.bridge.provider` for a Slack-bridged channel. */
internal const val SlockExternalBridgeProviderSlack = "slack"

/** Only providers mobile knows how to label render a badge; unknown providers stay hidden. */
internal fun externalBridgeBadgeVisible(bridgeProvider: String?): Boolean =
    bridgeProvider?.trim()?.lowercase() == SlockExternalBridgeProviderSlack

/**
 * task #396: `[Slack]` tag rendered after a bridged channel's name in the Home channel list and
 * the conversation header. Mirrors Web Sidebar's `Badge appearance="soft" variant="accent"
 * uppercase` under the brutal theme: lavender fill, black text, black border, small bold mono.
 */
@Composable
internal fun SlockExternalBridgeBadge(
    bridgeProvider: String?,
    modifier: Modifier = Modifier
) {
    if (!externalBridgeBadgeVisible(bridgeProvider)) return
    Box(
        modifier =
            modifier
                .border(SlockStroke.Thin, SlockColor.BorderBlack, RectangleShape)
                .background(SlockColor.AccentLavender, RectangleShape)
                .slockSquareCorners()
                .padding(horizontal = 4.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(MR.strings.channel_bridge_provider_slack).uppercase(),
            style =
                SlockType.Caption
                    .copy(
                        fontFamily = SlockMonoFamily,
                        fontSize = 9.sp,
                        lineHeight = TextUnit.Unspecified,
                        letterSpacing = 0.4.sp
                    )
                    .withSlockFontWeight(FontWeight.Bold),
            color = SlockColor.TextBlack,
            maxLines = 1
        )
    }
}
