package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.designsystem.components.RaftMenuSurface
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The in-app Artifact viewer's title bar ("two bars", owner decision 2026-11-10): Back, the title
 * and a ⋮ with only Desktop Mode (where offered) and Open in Browser, over the web page, which draws
 * its own toolbar and ⋯ menu. States: the bar; its ⋮ menu open; the ⋮ menu where Desktop Mode is
 * offered (checked); and the state a 403/404 puts in place of the page. The menu is a window on a
 * device, so its surface is drawn here over the page as the window places it. Verify / record as
 * [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h760dp-xxhdpi")
class RaftArtifactViewerScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test fun unavailableLight() = capture("artifact.viewer.unavailable", RaftAppAppearance.Light) { Unavailable() }

    @Test fun unavailableDark() = capture("artifact.viewer.unavailable.dark", RaftAppAppearance.Dark) { Unavailable() }

    @Test fun barLight() = capture("artifact.viewer.bar", RaftAppAppearance.Light) { Viewer() }

    @Test fun barDark() = capture("artifact.viewer.bar.dark", RaftAppAppearance.Dark) { Viewer() }

    // One bar: the page's toolbar is the only one, under the status bar (24dp stand-in).
    @Test fun pageBarLight() = capture("artifact.viewer.page-bar", RaftAppAppearance.Light) { Viewer(pageDrawsBar = true) }

    @Test fun pageBarDark() = capture("artifact.viewer.page-bar.dark", RaftAppAppearance.Dark) { Viewer(pageDrawsBar = true) }

    @Test fun menuLight() = capture("artifact.viewer.bar.menu", RaftAppAppearance.Light) { Viewer(overlay = { Menu() }) }

    @Test fun menuDark() = capture("artifact.viewer.bar.menu.dark", RaftAppAppearance.Dark) { Viewer(overlay = { Menu() }) }

    @Test
    fun desktopModeMenuLight() = capture("artifact.viewer.desktop-mode.menu", RaftAppAppearance.Light) { Viewer(desktopMode = true, overlay = { Menu(desktopMode = true) }) }

    @Test
    fun desktopModeMenuDark() = capture("artifact.viewer.desktop-mode.menu.dark", RaftAppAppearance.Dark) { Viewer(desktopMode = true, overlay = { Menu(desktopMode = true) }) }

    private fun capture(name: String, appearance: RaftAppAppearance, content: @Composable () -> Unit) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            RaftDesignSystemTheme { content() }
        }
}

private const val Title = "q4-launch-plan.html"

private val Actions = RaftArtifactViewerBarActions(onToggleDesktopMode = {}, onOpenInBrowser = {})

@Composable
private fun Viewer(desktopMode: Boolean? = null, pageDrawsBar: Boolean = false, overlay: (@Composable () -> Unit)? = null) {
    Box(Modifier.fillMaxSize()) {
        RaftArtifactViewerFrame(
            title = Title,
            onBack = {},
            actions = Actions,
            desktopMode = desktopMode,
            pageDrawsBar = pageDrawsBar,
            topSafeAreaOverride = if (pageDrawsBar) 24.dp else 0.dp,
        ) {
            PageStandIn()
        }
        overlay?.invoke()
    }
}

@Composable
private fun Unavailable() {
    // As the page draws it after a 403/404: the ⋮ keeps Open in Browser.
    RaftArtifactViewerFrame(
        title = Title,
        onBack = {},
        actions = RaftArtifactViewerBarActions(onOpenInBrowser = {}),
        topSafeAreaOverride = 0.dp,
    ) {
        RaftArtifactViewerUnavailable(onClose = {})
    }
}

/** The ⋮ menu where its window puts it: under the bar, at its end. */
@Composable
private fun Menu(desktopMode: Boolean? = null) {
    Box(Modifier.fillMaxSize().padding(top = 56.dp + 6.dp, end = 8.dp), contentAlignment = Alignment.TopEnd) {
        RaftMenuSurface(minWidth = 220.dp) {
            RaftArtifactViewerMenuItems(actions = Actions, onDone = {}, desktopMode = desktopMode)
        }
    }
}

/** What the WebView shows under the bar: the page's own toolbar (where it lives, Comments, ⋯), then its content. */
@Composable
private fun PageStandIn() {
    val colors = RaftTheme.colors
    val type = RaftTheme.typography
    Column(Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
        RaftText(
            "#q4-launch · Comments · ⋯",
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            style = type.caption,
            color = colors.foregroundHint,
        )
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RaftText("Q4 Launch Plan", style = type.title, color = colors.foreground)
            RaftText(
                "We ship the new onboarding to every workspace in three waves. Each wave opens once the previous one has held for a week without regressions.",
                style = type.body,
                color = colors.foregroundMuted,
            )
        }
    }
}
