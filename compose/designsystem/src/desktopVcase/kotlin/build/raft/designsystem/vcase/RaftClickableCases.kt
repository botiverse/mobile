package build.raft.designsystem.vcase

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.LocalRaftHoveredPreview
import build.raft.designsystem.components.LocalRaftPressedPreview
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftHoverTier
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.raftClickable
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke
import dev.vcase.VCase

// Hover and press on `Modifier.raftClickable`, the one way app code makes something tappable
// that no component covers (scripts/ci/check_raw_clickables.py, #wg-mobile-sync:#268): the same
// parts in three columns, rest, hovered (a mouse or trackpad over them) and pressed, forced
// through [LocalRaftHoveredPreview] / [LocalRaftPressedPreview] (an offscreen frame has no
// pointer). A row and a readable card take the subtle tier, a chip and a small text action the
// medium tier, a picker row the highlight tier, and press lays the medium tier on all of them.
// Selected rows take no hover; a disabled chip neither hover nor press. The DS buttons are
// beside them for comparison: their hover is the press overlay, as the web's `hover:`.

private enum class ClickState(val caption: String) { Rest("Rest"), Hovered("Hovered"), Pressed("Pressed") }

@VCase("ds.hover.raft-clickable")
@Composable
fun HoverRaftClickable() {
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
        ClickState.entries.forEach { state ->
            Column(Modifier.width(240.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Caption(state.caption)
                CompositionLocalProvider(
                    LocalRaftHoveredPreview provides (state == ClickState.Hovered),
                    LocalRaftPressedPreview provides (state == ClickState.Pressed),
                ) {
                    ClickableParts()
                }
            }
        }
    }
}

@Composable
private fun ClickableParts() {
    val colors = RaftTheme.colors
    val type = RaftTheme.typography
    val card = RoundedCornerShape(RaftRadius.Container)
    val chip = RoundedCornerShape(RaftRadius.Chip)
    val control = RoundedCornerShape(RaftRadius.Control)
    Column(Modifier.fillMaxWidth()) {
        RaftText(
            "Row (subtle)",
            Modifier.fillMaxWidth().raftClickable(onClick = {}, tier = RaftHoverTier.Subtle).padding(horizontal = 12.dp, vertical = 10.dp),
            style = type.body,
        )
        RaftText(
            "Selected row",
            Modifier.fillMaxWidth()
                .raftClickable(onClick = {}, tier = RaftHoverTier.Subtle, selected = true)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            style = type.body,
        )
        RaftText(
            "Picker row (highlight)",
            Modifier.fillMaxWidth()
                .raftClickable(onClick = {}, tier = RaftHoverTier.Highlight, shape = control)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            style = type.body,
        )
    }
    Column(
        Modifier.fillMaxWidth()
            .border(RaftStroke.Hairline, colors.lineHairline, card)
            .raftClickable(onClick = {}, tier = RaftHoverTier.Subtle, shape = card)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        RaftText("Readable card (subtle)", style = type.callout)
        RaftText("Opens the item; long press for its menu", style = type.footnote, color = colors.foregroundMuted)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        RaftText(
            "Chip",
            Modifier.border(RaftStroke.Hairline, colors.lineHairline, chip)
                .raftClickable(onClick = {}, shape = chip)
                .padding(horizontal = 8.dp, vertical = 2.dp),
            style = type.footnote,
        )
        RaftText(
            "Disabled",
            Modifier.border(RaftStroke.Hairline, colors.lineHairline, chip)
                .raftClickable(onClick = {}, shape = chip, enabled = false)
                .padding(horizontal = 8.dp, vertical = 2.dp),
            style = type.footnote,
            color = colors.foregroundHint,
        )
        RaftText(
            "Show more",
            Modifier.raftClickable(onClick = {}, shape = control).padding(horizontal = 4.dp, vertical = 2.dp),
            style = type.footnote,
            color = colors.foregroundMuted,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        RaftButton("Default", {})
        RaftButton("Ghost", {}, variant = RaftButtonVariant.Ghost)
        RaftIconButton({}, tooltip = "Copy Link", variant = RaftButtonVariant.Ghost) { RaftGlyphIcon(RaftGlyph.Copy) }
    }
}
