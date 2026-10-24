package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftBrutalActionButton
import build.raft.app.components.RaftChipLoadingIndicator
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.components.RaftUiMenuItem
import build.raft.app.components.RaftUiMenuSurface
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * artin: the app's loading indicators are raft-ui's pixel `RaftSpinner` (no rotating lucide
 * loader): a settings card still loading, busy buttons (`RaftButton` `loading`), a menu item
 * in progress, the header refresh and chip indicators, and the centred page loading line.
 * Spinners are frozen on their first step. Light and dark; verify / record as
 * [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h640dp-xxhdpi")
class LoadingIndicatorsScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun loadingIndicatorsLight() = capture("loading_indicators", RaftAppAppearance.Light)

    @Test
    fun loadingIndicatorsDark() = capture("loading_indicators.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            RaftDesignSystemTheme {
                Column(
                    modifier = Modifier.fillMaxSize().background(RaftColor.Canvas).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    SettingsLoadStateCard(
                        RaftLucideIconName.Bell,
                        "Loading",
                        "Loading notification settings…",
                        null,
                        {},
                        loading = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RaftBrutalActionButton(
                            label = "Sending…",
                            onClick = {},
                            backgroundColor = RaftColor.AccentPink,
                            leadingIcon = RaftLucideIconName.Send,
                            loading = true
                        )
                        RaftBrutalActionButton(
                            label = "Delete",
                            onClick = {},
                            backgroundColor = RaftColor.AccentRed,
                            leadingIcon = RaftLucideIconName.Trash2,
                            loading = true
                        )
                        RaftHeaderRefreshIndicator()
                        RaftChipLoadingIndicator(size = 14.dp)
                    }
                    RaftUiMenuSurface(
                        items =
                            listOf(
                                RaftUiMenuItem(id = "photos", label = "Saving to Photos…", icon = RaftLucideIconName.Save, enabled = false, loading = true),
                                RaftUiMenuItem(id = "files", label = "Save to Files", icon = RaftLucideIconName.Download)
                            ),
                        width = 240.dp
                    )
                    RaftCenteredLoading(message = "Opening server…", modifier = Modifier.fillMaxWidth().height(64.dp))
                }
            }
        }
}
