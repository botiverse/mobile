package build.raft.designsystem.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftSeparator
import build.raft.designsystem.components.RaftSeparatorOrientation
import build.raft.designsystem.components.RaftSpinner
import build.raft.designsystem.components.RaftSpinnerSize
import build.raft.designsystem.components.RaftSpinnerVariant
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test

/**
 * One golden per component x appearance, each holding the component's full state
 * matrix. Goldens live in compose/designsystem/screenshots (see README.md).
 * Sample copy follows raft-design Content > Writing: Title Case for clickable things
 * and status names, sentence case for everything else.
 */
class ComponentScreenshotTest {
    private val appearances = listOf(RaftAppearance.Light, RaftAppearance.Dark)

    @Test
    fun separator() =
        appearances.forEach { appearance ->
            RaftScreenshot.capture("separator_${appearance.key}", appearance) {
                Column(Modifier.width(320.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Caption("Horizontal")
                    RaftSeparator()
                    Caption("Vertical, in a row of fixed height")
                    Row(
                        Modifier.height(24.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RaftText("Edit")
                        RaftSeparator(orientation = RaftSeparatorOrientation.Vertical)
                        RaftText("Share")
                        RaftSeparator(orientation = RaftSeparatorOrientation.Vertical)
                        RaftText("Delete")
                    }
                    Caption("With a word")
                    RaftSeparator(label = "Actions")
                }
            }
        }

    @Test
    fun badge() =
        appearances.forEach { appearance ->
            RaftScreenshot.capture("badge_${appearance.key}", appearance) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    RaftBadgeAppearance.entries.forEach { badgeAppearance ->
                        listOf(true, false).forEach { enabled ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Caption(badgeAppearance.name + if (enabled) "" else ", disabled", Modifier.width(110.dp))
                                RaftBadgeVariant.entries.forEach { variant ->
                                    RaftBadge(
                                        text = sampleText(badgeAppearance, variant),
                                        appearance = badgeAppearance,
                                        variant = variant,
                                        enabled = enabled,
                                    )
                                }
                            }
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Caption("Content", Modifier.width(110.dp))
                        RaftBadge("3")
                        RaftBadge("99+", variant = RaftBadgeVariant.Accent)
                        RaftBadge("Experimental", variant = RaftBadgeVariant.Success)
                        RaftBadge(
                            "Working",
                            appearance = RaftBadgeAppearance.Soft,
                            variant = RaftBadgeVariant.Success,
                            icon = { RaftSpinner(size = RaftSpinnerSize.Xs, stillStep = 0) },
                        )
                        RaftBadge("agent", appearance = RaftBadgeAppearance.Outline)
                    }
                }
            }
        }

    @Test
    fun spinner() =
        appearances.forEach { appearance ->
            RaftScreenshot.capture("spinner_${appearance.key}", appearance) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    RaftSpinnerSize.entries.forEach { size ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Caption(size.name, Modifier.width(48.dp))
                            // The four steps of the cycle: TL, TR, BR, BL lit.
                            (0 until 4).forEach { step -> RaftSpinner(size = size, stillStep = step) }
                            // Inverse on the opposite-lightness surface.
                            Box(
                                Modifier.background(RaftTheme.colors.foreground).padding(6.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                RaftSpinner(size = size, variant = RaftSpinnerVariant.Inverse, stillStep = 0)
                            }
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RaftSpinner(stillStep = 1)
                        RaftText("Loading messages")
                    }
                }
            }
        }

    @Composable
    private fun Caption(text: String, modifier: Modifier = Modifier) {
        RaftText(
            text = text,
            modifier = modifier,
            style = RaftTheme.typography.monoMeta,
            color = RaftTheme.colors.foregroundHint,
        )
    }

    private fun sampleText(appearance: RaftBadgeAppearance, variant: RaftBadgeVariant): String =
        if (appearance == RaftBadgeAppearance.Solid && variant == RaftBadgeVariant.Accent) "12" else variant.name

    private val RaftAppearance.key: String get() = name.lowercase()
}
