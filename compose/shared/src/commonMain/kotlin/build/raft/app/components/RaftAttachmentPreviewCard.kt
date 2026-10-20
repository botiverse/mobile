package build.raft.app.components

import build.raft.app.RaftColor
import build.raft.app.RaftLucideIcon
import build.raft.app.RaftLucideIconName
import build.raft.app.RaftAppSpacing
import build.raft.app.RaftAppStroke
import build.raft.app.RaftType
import build.raft.app.raftSquareCorners
import build.raft.app.withRaftFontWeight
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val RaftAttachmentPreviewCardWidth = 176.dp
internal val RaftAttachmentPreviewCardHeight = 80.dp

@Composable
internal fun RaftAttachmentPreviewCard(
    title: String,
    meta: String,
    summary: String?,
    affordanceIcon: RaftLucideIconName,
    loading: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier =
            modifier
                .size(RaftAttachmentPreviewCardWidth, RaftAttachmentPreviewCardHeight)
                .raftSquareCorners()
                .border(RaftAppStroke.Thin, RaftColor.BorderBlack.copy(alpha = 0.15f))
                // react-parity: AttachmentChip loading uses bg-palette-yellow/30.
                .background(if (loading) RaftColor.BrandYellow.copy(alpha = 0.3f) else RaftColor.SurfaceWhite)
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = RaftAppSpacing.Sm)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = RaftType.Caption.withRaftFontWeight(FontWeight.Bold).copy(fontSize = 12.sp, lineHeight = 14.sp),
                    color = RaftColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        style = RaftType.Caption.copy(fontSize = 10.sp, lineHeight = 12.sp, color = RaftColor.TextBlack.copy(alpha = 0.45f)),
                        color = RaftColor.TextBlack.copy(alpha = 0.45f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            summary?.let { label ->
                Text(
                    text = label,
                    style = RaftType.Caption.withRaftFontWeight(FontWeight.Bold).copy(
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        color = RaftColor.TextBlack.copy(alpha = 0.7f)
                    ),
                    color = RaftColor.TextBlack.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 134.dp)
                )
            }
        }
        RaftLucideIcon(
            affordanceIcon,
            modifier = Modifier.align(Alignment.BottomEnd).size(12.dp),
            color = "#99000000"
        )
        if (loading) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .height(6.dp)
                        .width(36.dp)
                        .raftSquareCorners()
                        .background(RaftColor.BrandYellow),
                contentAlignment = Alignment.Center
            ) {}
        }
    }
}
