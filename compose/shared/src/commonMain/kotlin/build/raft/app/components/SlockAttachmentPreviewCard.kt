package build.raft.app.components

import build.raft.app.SlockColor
import build.raft.app.SlockLucideIcon
import build.raft.app.SlockLucideIconName
import build.raft.app.SlockSpacing
import build.raft.app.SlockStroke
import build.raft.app.SlockType
import build.raft.app.slockSquareCorners
import build.raft.app.withSlockFontWeight
import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.layout.widthIn
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp

internal val SlockAttachmentPreviewCardWidth = 176.dp
internal val SlockAttachmentPreviewCardHeight = 80.dp

@Composable
internal fun SlockAttachmentPreviewCard(
    title: String,
    meta: String,
    summary: String?,
    affordanceIcon: SlockLucideIconName,
    loading: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier =
            modifier
                .size(SlockAttachmentPreviewCardWidth, SlockAttachmentPreviewCardHeight)
                .slockSquareCorners()
                .border(SlockStroke.Thin, SlockColor.BorderBlack.copy(alpha = 0.15f))
                // react-parity: AttachmentChip loading uses bg-palette-yellow/30.
                .background(if (loading) SlockColor.BrandYellow.copy(alpha = 0.3f) else SlockColor.SurfaceWhite)
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = SlockSpacing.Sm)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 12.sp, lineHeight = 14.sp),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 12.sp, color = SlockColor.TextBlack.copy(alpha = 0.45f)),
                        color = SlockColor.TextBlack.copy(alpha = 0.45f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            summary?.let { label ->
                Text(
                    text = label,
                    style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        color = SlockColor.TextBlack.copy(alpha = 0.7f)
                    ),
                    color = SlockColor.TextBlack.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 134.dp)
                )
            }
        }
        SlockLucideIcon(
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
                        .slockSquareCorners()
                        .background(SlockColor.BrandYellow),
                contentAlignment = Alignment.Center
            ) {}
        }
    }
}
