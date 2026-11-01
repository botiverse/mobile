package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.ArtifactViewerVersion
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.raftEpochDayForDate
import build.raft.designsystem.components.RaftChoicePickerSheetSurface
import build.raft.designsystem.components.RaftMenuSurface
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The in-app Artifact viewer's one native bar (artin's approved mock, 2026-11-01): Back, the title
 * over "#channel · vN ▾ · N read", Comments with its count, ⋮. States: the default bar; its ⋮ menu
 * open; the version list open; the reads failed (no counts, the page still shown); and the state a
 * 403/404 puts in place of the page. The menu and the sheet are windows on a device, so their
 * surfaces are drawn here over the page as the windows place them. Verify / record as
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

    @Test fun barLight() = capture("artifact.viewer.bar", RaftAppAppearance.Light) { Viewer(Loaded) }

    @Test fun barDark() = capture("artifact.viewer.bar.dark", RaftAppAppearance.Dark) { Viewer(Loaded) }

    @Test fun menuLight() = capture("artifact.viewer.bar.menu", RaftAppAppearance.Light) { Viewer(Loaded, overlay = { Menu() }) }

    @Test fun menuDark() = capture("artifact.viewer.bar.menu.dark", RaftAppAppearance.Dark) { Viewer(Loaded, overlay = { Menu() }) }

    @Test fun versionsLight() = capture("artifact.viewer.bar.versions", RaftAppAppearance.Light) { Viewer(Loaded, overlay = { Versions() }) }

    @Test fun versionsDark() = capture("artifact.viewer.bar.versions.dark", RaftAppAppearance.Dark) { Viewer(Loaded, overlay = { Versions() }) }

    @Test fun loadFailedLight() = capture("artifact.viewer.bar.load-failed", RaftAppAppearance.Light) { Viewer(Failed) }

    @Test fun loadFailedDark() = capture("artifact.viewer.bar.load-failed.dark", RaftAppAppearance.Dark) { Viewer(Failed) }

    private fun capture(name: String, appearance: RaftAppAppearance, content: @Composable () -> Unit) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            CompositionLocalProvider(LocalRaftTimeContextProvider provides RaftTimeContextProvider(TimeContext)) {
                RaftDesignSystemTheme { content() }
            }
        }
}

private const val Title = "q4-launch-plan.html"

// "Now" pinned (2026-11-01 18:00 in UTC+8), so the version list's times never move.
private val TimeContext =
    RaftTimeContext(
        localOffsetMinutes = 8 * 60,
        todayEpochDay = raftEpochDayForDate(2026, 11, 1),
        nowEpochMillis = 1_793_527_200_000L,
    )

private val Versions =
    listOf(
        ArtifactViewerVersion(3, "Kirby", "2026-11-01T08:00:00.000Z"),
        ArtifactViewerVersion(2, "Artea", "2026-10-31T09:00:00.000Z"),
        ArtifactViewerVersion(1, "Kirby", "2026-10-28T09:00:00.000Z"),
    )

private val Loaded =
    RaftArtifactViewerBarContent(
        home = "#q4-launch",
        version = 3,
        versions = Versions,
        latestVersion = 3,
        readCount = 5,
        commentCount = 3,
        following = false,
    )

/** The reads failed: the version from the opener's card, no place, no counts, no Follow. */
private val Failed = RaftArtifactViewerBarContent(version = 3)

private val Actions =
    RaftArtifactViewerBarActions(
        onToggleComments = {},
        onPickVersion = {},
        onCopyLink = {},
        onToggleFollow = {},
        onOpenInBrowser = {},
    )

@Composable
private fun Viewer(bar: RaftArtifactViewerBarContent, overlay: (@Composable () -> Unit)? = null) {
    Box(Modifier.fillMaxSize()) {
        RaftArtifactViewerFrame(title = Title, onBack = {}, bar = bar, actions = Actions, topSafeAreaOverride = 0.dp) {
            PageStandIn()
        }
        overlay?.invoke()
    }
}

@Composable
private fun Unavailable() {
    // As the page draws it after a 403/404: the reads failed too, the ⋮ keeps Copy Link and Open in Browser.
    RaftArtifactViewerFrame(
        title = Title,
        onBack = {},
        bar = RaftArtifactViewerBarContent(version = 1),
        actions = RaftArtifactViewerBarActions(onCopyLink = {}, onOpenInBrowser = {}),
        topSafeAreaOverride = 0.dp,
    ) {
        RaftArtifactViewerUnavailable(onClose = {})
    }
}

/** The ⋮ menu where its window puts it: under the bar, at its end. */
@Composable
private fun Menu() {
    Box(Modifier.fillMaxSize().padding(top = 56.dp + 6.dp, end = 8.dp), contentAlignment = Alignment.TopEnd) {
        RaftMenuSurface(minWidth = 220.dp) {
            RaftArtifactViewerMenuItems(following = false, actions = Actions, onDone = {})
        }
    }
}

/** The version list's sheet over the scrim. */
@Composable
private fun Versions() {
    Box(Modifier.fillMaxSize().background(RaftTheme.scrim), contentAlignment = Alignment.BottomCenter) {
        RaftChoicePickerSheetSurface(
            value = 3,
            options = raftArtifactViewerVersionOptions(Versions, latestVersion = 3),
            onPick = {},
            title = "Version History",
            emptyText = { "" },
            searchable = false,
            modifier = Modifier.height(300.dp),
        )
    }
}

/** What the WebView shows under the bar: only the content (`chrome=none`). */
@Composable
private fun PageStandIn() {
    val colors = RaftTheme.colors
    val type = RaftTheme.typography
    Column(
        Modifier.fillMaxSize().background(RaftColor.SurfaceWhite).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RaftText("Q4 Launch Plan", style = type.title, color = colors.foreground)
        RaftText(
            "We ship the new onboarding to every workspace in three waves. Each wave opens once the previous one has held for a week without regressions.",
            style = type.body,
            color = colors.foregroundMuted,
        )
    }
}
