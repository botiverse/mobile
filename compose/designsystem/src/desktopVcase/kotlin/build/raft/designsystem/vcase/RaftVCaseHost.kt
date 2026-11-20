package build.raft.designsystem.vcase

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftPointerInput
import build.raft.designsystem.theme.RaftScale
import build.raft.designsystem.theme.RaftTheme
import dev.vcase.VCasePointer
import dev.vcase.compose.desktop.CaptureSize
import dev.vcase.compose.desktop.VCaseHost
import dev.vcase.core.ManifestDefaults
import dev.vcase.core.ManifestViewport
import dev.vcase.core.VCaseVariant

/**
 * How the design system renders a vcase case: the same frame as [build.raft.designsystem.screenshot.RaftScreenshot]
 * (RaftTheme with the axis appearance, the theme canvas, 16dp of padding, sized to the content,
 * at most 1200 x 2400 dp, 1dp = 2px), so a case and the gallery test it replaces should give the
 * same pixels.
 *
 * Axes: theme light / dark -> [RaftAppearance]; pointer touch / fine -> [RaftPointerInput].
 * [RaftScale] stays pinned to Compact (the theme would otherwise pick it from the scene width).
 * Locale is not applied yet: the existing goldens use the inherited one.
 */
object RaftVCaseHost : VCaseHost {
    override val project = "raft-designsystem"

    override val defaults =
        ManifestDefaults(
            theme = listOf("light", "dark"),
            // A gallery canvas, not a device: the width and height bound the wrap-content capture.
            viewport = listOf(ManifestViewport("compact", 1200, 2400, 2f)),
            locale = listOf("en"),
            pointer = listOf("touch"),
        )

    override fun captureSize(variant: VCaseVariant): CaptureSize = CaptureSize.WrapContent(maxWidthDp = 1200, maxHeightDp = 2400)

    @Composable
    override fun Wrap(
        variant: VCaseVariant,
        content: @Composable () -> Unit,
    ) {
        RaftTheme(
            appearance = if (variant.axes.theme == "dark") RaftAppearance.Dark else RaftAppearance.Light,
            systemDark = false,
            scale = RaftScale.Compact,
            pointerInput = if (variant.axes.pointer == VCasePointer.Fine) RaftPointerInput.Fine else RaftPointerInput.Touch,
        ) {
            Box(Modifier.background(RaftTheme.colors.layerCanvas).padding(16.dp)) { content() }
        }
    }
}
