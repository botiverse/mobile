package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Semantic runtime configuration chip rendered in the Members agent detail profile's
 * Runtime Config section. Mirrors the inline brutal-pill styling of React's
 * `AgentDetailPanel.tsx` RUNTIME CONFIG block (Runtime/Model/Reasoning/Mode chips).
 *
 * Color palette is hardcoded per chip kind to mirror the React palette
 * (`bg-palette-cyan` / `bg-palette-lavender` / `bg-palette-yellow` / `bg-palette-orange`).
 * No caller-controlled color is exposed — runtime chip semantics are fixed
 * (Runtime → cyan, Model → lavender, Reasoning → yellow, Mode → orange), so we
 * don't want callers picking arbitrary colors that could break the visual contract.
 */
internal enum class MembersAgentRuntimeChipKind {
    Runtime,
    Model,
    Reasoning,
    Mode
}

@Composable
internal fun MembersAgentRuntimeChip(
    label: String,
    kind: MembersAgentRuntimeChipKind,
    modifier: Modifier = Modifier,
    statusColor: Color? = null,
    textStyle: TextStyle = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp)
        .withSlockFontWeight(FontWeight.Bold)
) {
    val backgroundColor: Color = when (kind) {
        MembersAgentRuntimeChipKind.Runtime -> SlockColor.AccentCyan
        MembersAgentRuntimeChipKind.Model -> SlockColor.AccentLavender
        MembersAgentRuntimeChipKind.Reasoning -> SlockColor.BrandYellow
        MembersAgentRuntimeChipKind.Mode -> SlockColor.AccentOrange
    }
    Box(
        modifier = modifier
            .background(backgroundColor)
            .border(SlockStroke.Thin, SlockColor.BorderBlack)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = textStyle,
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            statusColor?.let { color ->
                SlockStatusIndicator(color = color, size = SlockStatusIndicatorTokens.InlineSize)
            }
        }
    }
}
