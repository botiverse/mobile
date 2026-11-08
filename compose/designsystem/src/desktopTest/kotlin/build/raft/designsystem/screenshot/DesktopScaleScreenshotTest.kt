package build.raft.designsystem.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftField
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowChevron
import build.raft.designsystem.components.RaftSelectTrigger
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.components.RaftTextFieldClearAction
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftPointerInput
import build.raft.designsystem.theme.RaftScale
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test

/**
 * The wide type scale (raft-ui 0.54 styles.css "Desktop type", from the app's wide breakpoint),
 * pinned with [RaftScale.Wide], light and dark: the type roles that change (headline 16/24;
 * body, subhead, callout 14/22; footnote, caption, mono-meta 12/18) and the controls that
 * follow `--control-*`, under each input:
 * - `desktop-scale_*`: a mouse or trackpad ([RaftPointerInput.Fine], the Mac at that width):
 *   buttons at `md` and `lg` (34dp, sizing v12; `md` in `text-control` 14/22) and `xl` (40dp), an `icon-lg` button
 *   (34dp, 18dp icon), a field (34dp, 14/22, a 20dp embedded clear), a select trigger and a
 *   list row.
 * - `wide-touch-scale_*`: a finger ([RaftPointerInput.Touch], a tablet at that width): the same
 *   type, the controls at 44 / 52 with a 22dp icon and a 32dp embedded clear.
 */
class DesktopScaleScreenshotTest {
    @Test
    fun wideScaleFinePointer() = capture("desktop-scale", RaftPointerInput.Fine)

    @Test
    fun wideScaleTouch() = capture("wide-touch-scale", RaftPointerInput.Touch)

    private fun capture(name: String, pointerInput: RaftPointerInput) =
        listOf(RaftAppearance.Light, RaftAppearance.Dark).forEach { appearance ->
            RaftScreenshot.capture("${name}_${appearance.name.lowercase()}", appearance, scale = RaftScale.Wide, pointerInput = pointerInput) {
                Column(Modifier.width(420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TypeSample()
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RaftButton(text = "Cancel", onClick = {}, size = RaftButtonSize.Md)
                        RaftButton(text = "Save", onClick = {}, variant = RaftButtonVariant.Primary, size = RaftButtonSize.Lg)
                        RaftIconButton(onClick = {}, tooltip = "Search", variant = RaftButtonVariant.Ghost, size = RaftIconButtonSize.Lg) {
                            RaftGlyphIcon(RaftGlyph.Search, size = RaftTheme.controls.iconSize)
                        }
                        RaftIconButton(onClick = {}, tooltip = "Search", size = RaftIconButtonSize.Lg) {
                            RaftGlyphIcon(RaftGlyph.Search, size = RaftTheme.controls.iconSize)
                        }
                    }
                    RaftButton(
                        text = "Continue",
                        onClick = {},
                        modifier = Modifier.width(420.dp),
                        variant = RaftButtonVariant.Primary,
                        size = RaftButtonSize.Xl,
                    )
                    RaftField(label = "Channel name", description = "Lowercase, no spaces.") {
                        RaftTextField("design-review", {}, trailingAction = { RaftTextFieldClearAction(onClear = {}) })
                    }
                    RaftTextField("", {}, placeholder = "Search channels", leading = { RaftGlyphIcon(RaftGlyph.Search, size = 18.dp) })
                    RaftField(label = "Time zone") {
                        RaftSelectTrigger(value = "Asia/Shanghai", onClick = {})
                    }
                    Column(Modifier.background(RaftTheme.colors.layerCard)) {
                        RaftListRow(
                            title = "Notifications",
                            subtitle = "Mentions and direct messages",
                            meta = "12:04",
                            onClick = {},
                            trailing = { RaftListRowChevron() },
                        )
                    }
                }
            }
        }

    @Composable
    private fun TypeSample() {
        val type = RaftTheme.typography
        val colors = RaftTheme.colors
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            RaftText("Headline 16/24", style = type.headline, color = colors.foreground)
            RaftText("Body 14/22: the quick brown fox jumps over the lazy dog.", style = type.body, color = colors.foreground)
            RaftText("Subhead 14/22", style = type.subhead, color = colors.foreground)
            RaftText("Callout 14/22", style = type.callout, color = colors.foregroundMuted)
            RaftText("Footnote 12/18", style = type.footnote, color = colors.foregroundMuted)
            RaftText("Caption 12/18", style = type.caption, color = colors.foregroundHint)
            RaftText("mono-meta 12/18 · 4f2a9c", style = type.monoMeta, color = colors.foregroundHint)
            RaftText("mono-code 13/20 ./gradlew check", style = type.monoCode, color = colors.foreground)
        }
    }
}
