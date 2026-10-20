package build.raft.app.components

import build.raft.app.RaftAppRadius
import build.raft.app.RaftAppThemeState
import build.raft.app.RaftLucideIcon
import build.raft.app.RaftLucideIconName
import build.raft.app.RaftType
import build.raft.app.raftArgbHex
import build.raft.app.raftMonoFamilyForWeight
import build.raft.app.withRaftFontWeight
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val RaftAttachmentPreviewCardWidth = 176.dp
internal val RaftAttachmentPreviewCardHeight = 80.dp

/** raft-ui `text-mono-meta`: Space Mono 12/16. */
private fun raftAttachmentMonoMeta(): TextStyle =
    RaftType.Caption.copy(
        fontFamily = raftMonoFamilyForWeight(FontWeight.Normal),
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp
    )

/**
 * raft-ui `MessageAttachmentCard` as web `AttachmentChip` lays it out: a 176x80 card
 * (`rounded-container`, `line-hairline` edge, `layer-card`, `py-2 pr-1.5 pl-2.5`) with the
 * title row (the inline `FilePreviewBadge` [typeBadge] + the callout-semibold filename), an
 * optional mono summary line, and the mono meta line ([metaLabel] · [sizeLabel]) ending in the
 * 24dp action glyph.
 */
@Composable
internal fun RaftAttachmentPreviewCard(
    title: String,
    typeBadge: String?,
    metaLabel: String,
    sizeLabel: String,
    summary: String?,
    affordanceIcon: RaftLucideIconName,
    loading: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val colors = RaftAppThemeState.colors
    val shape = RoundedCornerShape(RaftAppRadius.Container)
    val mono = raftAttachmentMonoMeta()
    Column(
        modifier =
            modifier
                .size(RaftAttachmentPreviewCardWidth, RaftAttachmentPreviewCardHeight)
                .background(if (loading) colors.fillPressed else colors.layerCard, shape)
                .border(1.dp, colors.lineHairline, shape)
                .clickable(onClick = onClick)
                .padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            typeBadge?.let { RaftAttachmentTypeBadge(it) }
            Text(
                text = title,
                style = RaftType.Button.withRaftFontWeight(FontWeight.SemiBold).copy(color = colors.foreground),
                color = colors.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
        }
        summary?.let { label ->
            Text(
                text = label,
                style = mono.copy(color = colors.foregroundHint),
                color = colors.foregroundHint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp, end = 4.dp)
            )
        }
        Spacer(Modifier.weight(1f))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (metaLabel.isNotBlank()) {
                    Text(
                        text = metaLabel,
                        style = mono.copy(color = colors.foregroundHint),
                        color = colors.foregroundHint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
                if (sizeLabel.isNotBlank()) {
                    if (metaLabel.isNotBlank()) {
                        Text(text = "·", style = mono.copy(color = colors.foregroundHint), color = colors.foregroundHint, maxLines = 1)
                    }
                    Text(text = sizeLabel, style = mono.copy(color = colors.foregroundHint), color = colors.foregroundHint, maxLines = 1)
                }
            }
            // raft-ui `MessageAttachmentAction`: a 24dp box (`-my-1`) holding the 16dp glyph.
            Box(modifier = Modifier.size(24.dp).padding(0.dp), contentAlignment = Alignment.Center) {
                RaftLucideIcon(
                    affordanceIcon,
                    modifier = Modifier.size(16.dp),
                    color = raftArgbHex(colors.foreground)
                )
            }
        }
    }
}

/** raft-ui `FilePreviewBadge placement="inline"`: 28x14, sticker radius, fill-muted, hairline ring, mono 10 bold. */
@Composable
internal fun RaftAttachmentTypeBadge(label: String) {
    val colors = RaftAppThemeState.colors
    val shape = RoundedCornerShape(RaftAppRadius.Sticker)
    Box(
        modifier =
            Modifier
                .width(28.dp)
                .height(14.dp)
                .background(colors.fillMuted, shape)
                .border(1.dp, colors.lineHairline, shape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style =
                RaftType.Caption.copy(
                    fontFamily = raftMonoFamilyForWeight(FontWeight.Bold),
                    fontWeight = FontWeight.Normal,
                    fontSize = 10.sp,
                    lineHeight = 10.sp,
                    textAlign = TextAlign.Center,
                    color = colors.foregroundMuted
                ),
            color = colors.foregroundMuted,
            maxLines = 1,
            softWrap = false
        )
    }
}

private val RaftAttachmentBadgedExtensions =
    setOf(
        "pdf", "doc", "docx", "txt", "md", "markdown", "csv", "json", "zip", "html",
        "png", "jpg", "jpeg", "gif", "webp", "svg", "mp4", "mov"
    )

/** Web `attachmentBadgeLabel`: the upper-cased extension for the badged kinds, else null. */
internal fun raftAttachmentBadgeLabel(filename: String): String? =
    filename.substringAfterLast('.', "").lowercase().takeIf { it in RaftAttachmentBadgedExtensions }?.uppercase()

/** Web `formatFileSizeBytes`: one decimal from KB up ("184.0 KB"). */
internal fun raftWebFileSizeLabel(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    if (bytes < 1024L) return "$bytes B"
    val units = listOf("KB" to 1024.0, "MB" to 1024.0 * 1024, "GB" to 1024.0 * 1024 * 1024)
    val (unit, divisor) = units.lastOrNull { bytes >= it.second } ?: units.first()
    val tenths = kotlin.math.round(bytes / divisor * 10).toLong()
    return "${tenths / 10}.${tenths % 10} $unit"
}
