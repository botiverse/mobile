package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftCheckboxIndicator
import build.raft.designsystem.components.RaftCheckboxShape
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftFonts
import build.raft.designsystem.theme.RaftTheme

// components.ui.* fixtures drawn with compose/designsystem (raft-ui 0.8.0) components, so
// the Android capture of each case is the design-system component the pages migrate to,
// not the RaftBrutal* / build.raft.app.components helpers (AGENTS.md, Shared UI Component
// Discipline). Each frame is the web case's: 342dp wide, `layer-canvas`, 16dp padding.

/**
 * The designsystem theme pinned light (every components.ui.* page pins light), in the
 * app's bundled Space Grotesk / Space Mono faces, as [build.raft.app.components.RaftDesignSystemTheme].
 */
@Composable
private fun UiDesignSystemFrame(height: Dp, content: @Composable () -> Unit) {
    val fonts =
        remember {
            RaftFonts(
                sans = raftMultiWeightFontFamily("sans"),
                mono = raftMultiWeightFontFamily("mono")
            )
        }
    RaftTheme(appearance = RaftAppearance.Light, fonts = fonts) {
        Box(
            modifier =
                Modifier
                    .width(342.dp)
                    .height(height)
                    .background(RaftTheme.colors.layerCanvas)
                    .padding(16.dp)
        ) {
            content()
        }
    }
}

/** Tailwind `text-sm font-bold`: `text-callout` (14/20) at 700. */
private val UiCalloutBold
    @Composable get() = RaftTheme.typography.callout.copy(fontWeight = FontWeight.Bold)

@Composable
internal fun UiCheckMarkerVisualContent() {
    // web `ui-check-marker`: three raft-ui <CheckboxIndicator> rows (the fixed 20px box) in
    // a `flex flex-col gap-3 text-sm font-bold` stack, each row `gap-3`. The third is
    // `shape="circle"` checked: a black disc with the check knocked out, not a radio dot.
    // The web passes no tone, so "Circle yellow" is only the label.
    UiDesignSystemFrame(height = 112.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            UiCheckMarkerRow("Square checked", checked = true, shape = RaftCheckboxShape.Square)
            UiCheckMarkerRow("Square unchecked", checked = false, shape = RaftCheckboxShape.Square)
            UiCheckMarkerRow("Circle yellow", checked = true, shape = RaftCheckboxShape.Circle)
        }
    }
}

@Composable
private fun UiCheckMarkerRow(label: String, checked: Boolean, shape: RaftCheckboxShape) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        RaftCheckboxIndicator(checked = checked, shape = shape)
        RaftText(label, style = UiCalloutBold, color = RaftTheme.colors.foreground)
    }
}

@Composable
internal fun UiButtonVisualContent() {
    // web `ui-button` (342x150 canvas frame): two rows of `size="sm"` raft-ui <Button>s 12px
    // apart. raft-ui 0.8.0 (components/button.md, Variants) renders `accent` as `primary`,
    // and `outline`, `information`, `muted` and `success` as `default`: so Save, Add and
    // Continue are the ink-bordered default button and Sync / Delete the pink sticker;
    // there is no cyan or lime button. `sm` is an old name of the 32dp md size.
    UiDesignSystemFrame(height = 150.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                RaftButton("Save", onClick = {}, variant = RaftButtonVariant.Default)
                RaftButton("Sync", onClick = {}, variant = RaftButtonVariant.Primary)
                RaftButton("Delete", onClick = {}, variant = RaftButtonVariant.Primary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                RaftButton("Add", onClick = {}, variant = RaftButtonVariant.Default)
                RaftButton("Continue", onClick = {}, variant = RaftButtonVariant.Default)
                RaftButton("Disabled", onClick = {}, variant = RaftButtonVariant.Default, enabled = false)
            }
        }
    }
}
