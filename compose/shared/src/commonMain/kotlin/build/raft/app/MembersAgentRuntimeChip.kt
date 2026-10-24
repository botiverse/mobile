package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Runtime configuration chip of the Members agent detail profile's Runtime Config section,
 * after web `AgentDetailPanel` (raft-ui): a soft [Badge] (`appearance="soft"`), the kind's
 * `-soft` wash, foreground type, chip radius, no edge. The runtime is the DS runtime chip
 * (`.rf-runtime`: information-soft in machine type); model, reasoning and mode read as
 * quieter soft labels so the section is not a row of saturated blocks.
 */
internal enum class MembersAgentRuntimeChipKind {
    Runtime,
    Model,
    Reasoning,
    Mode
}

/** The soft wash of each chip kind (raft-ui Badge soft `information` / `default` / `primary` / `warning`). */
internal val MembersAgentRuntimeChipKind.softFill: Color
    get() =
        when (this) {
            MembersAgentRuntimeChipKind.Runtime -> RaftColor.InfoSoft
            MembersAgentRuntimeChipKind.Model -> RaftColor.FillMuted
            MembersAgentRuntimeChipKind.Reasoning -> RaftColor.BrandSoft
            MembersAgentRuntimeChipKind.Mode -> RaftColor.WarningSoft
        }

@Composable
internal fun MembersAgentRuntimeChip(
    label: String,
    kind: MembersAgentRuntimeChipKind,
    modifier: Modifier = Modifier,
    statusColor: Color? = null,
    /** Accessible name of the status dot (web `aria-label`); never drawn as text. */
    statusDescription: String? = null,
    textStyle: TextStyle =
        if (kind == MembersAgentRuntimeChipKind.Runtime || kind == MembersAgentRuntimeChipKind.Model) {
            // `font-mono text-mono-meta font-normal`.
            RaftType.Caption.copy(fontFamily = RaftMonoFamily, fontSize = 12.sp, lineHeight = 16.sp)
        } else {
            RaftType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp).withRaftFontWeight(FontWeight.SemiBold)
        }
) {
    val shape = RoundedCornerShape(RaftAppRadius.Chip)
    Box(
        modifier = modifier
            .heightIn(min = 22.dp)
            .background(kind.softFill, shape)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = textStyle,
                color = RaftColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            statusColor?.let { color ->
                RaftStatusIndicator(
                    color = color,
                    size = RaftStatusIndicatorTokens.InlineSize,
                    modifier = Modifier.semantics { statusDescription?.let { contentDescription = it } }
                )
            }
        }
    }
}
