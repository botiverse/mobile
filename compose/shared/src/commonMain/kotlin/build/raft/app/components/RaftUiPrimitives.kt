package build.raft.app.components

import build.raft.app.RaftColor
import build.raft.app.RaftMonoFamily
import build.raft.app.RaftLucideIcon
import build.raft.app.RaftLucideIconName
import build.raft.app.RaftAppRadius
import build.raft.app.RaftAppStroke
import build.raft.app.raftArgbHex
import build.raft.app.RaftType
import build.raft.app.withRaftFontWeight
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun RaftSectionHeader(
    label: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
    action: (@Composable () -> Unit)? = null,
    iconName: RaftLucideIconName? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            iconName?.let {
                RaftLucideIcon(
                    it,
                    modifier = Modifier.size(16.dp),
                    color = raftArgbHex(RaftColor.ForegroundHint)
                )
            }
            RaftAppSectionEyebrow(label = label, modifier = Modifier)
            count?.takeIf { it >= 0 }?.let {
                // raft section count: Space Mono 12/16, hint ink.
                Text(
                    text = it.toString(),
                    style = RaftType.Caption.copy(fontFamily = RaftMonoFamily, fontSize = 12.sp, lineHeight = 16.sp),
                    color = RaftColor.ForegroundHint,
                    maxLines = 1
                )
            }
        }
        action?.invoke()
    }
}

@Composable
internal fun RaftSurfaceListItem(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    interactive: Boolean = true,
    onClick: (() -> Unit)? = null,
    horizontalPadding: Dp = 16.dp,
    verticalPadding: Dp = 12.dp,
    content: @Composable () -> Unit
) {
    // raft-ui ListRow on its own Card: container radius, card layer (selected =
    // `--info-soft`), 1dp hairline, no ink line and no lift.
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .raftCardSurface(fill = if (selected) RaftColor.InfoSoft else RaftColor.Card)
                .let { base ->
                    if (onClick != null) {
                        base.clickable(enabled = true, role = Role.Button, onClick = onClick)
                    } else {
                        base
                    }
                }
                .padding(horizontal = horizontalPadding, vertical = verticalPadding)
    ) {
        content()
    }
}
