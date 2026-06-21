package ai.slock.compose

import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.text.TextStyle
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp

/**
 * Semantic runtime configuration chip rendered in the Members agent detail profile's
 * Runtime Config section. Mirrors the inline brutal-pill styling of React's
 * `AgentDetailPanel.tsx` RUNTIME CONFIG block (Runtime/Model/Reasoning/Mode chips).
 *
 * Color palette is hardcoded per chip kind to mirror the React palette
 * (`bg-brutal-cyan` / `bg-brutal-lavender` / `bg-brutal-yellow` / `bg-brutal-orange`).
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
    textStyle: TextStyle = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 14.sp)
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
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = textStyle,
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}