package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SharedSettingsAboutSnapshot
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * raft-ui 0.26 (#758), links that leave Raft end in ↗, light and dark:
 * - Settings > About: the Open Raft row (the web app in the system browser) ends in ↗;
 * - the in-app browser's load error: Open in System Browser with ↗ in its trailing icon's place.
 * A 390dp-wide phone. Reports land in `build/reports/home-screenshots/`; verify / record as
 * [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h720dp-xxhdpi")
class ExternalLinkMarksScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test fun aboutLight() = capture("settings.about", RaftAppAppearance.Light) { AboutFixture() }

    @Test fun aboutDark() = capture("settings.about.dark", RaftAppAppearance.Dark) { AboutFixture() }

    @Test fun browserErrorLight() = capture("browser.error", RaftAppAppearance.Light) { BrowserErrorFixture() }

    @Test fun browserErrorDark() = capture("browser.error.dark", RaftAppAppearance.Dark) { BrowserErrorFixture() }

    private fun capture(name: String, appearance: RaftAppAppearance, content: @Composable () -> Unit) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme { content() }
            }
        }
}

private val aboutFixture =
    SharedSettingsAboutSnapshot(
        appName = "Raft",
        versionLabel = "1.42.0",
        buildLabel = "1420",
        packageName = "build.raft.app",
    )

@Composable
private fun AboutFixture() {
    SharedSettingsAboutContent(
        state = ScreenLoadState.Content(aboutFixture),
        fallback = aboutFixture,
        updateCheck = null,
        actionState = null,
        onCopyBuild = {},
        onOpenRaft = {},
        onCheckHandsUpdate = {},
        onOpenSourceLicenses = {},
        onCopyEnvironmentInfo = {},
        onOpenApiEnvironment = {},
        onOpenDiagnostics = {},
    )
}

@Composable
private fun BrowserErrorFixture() {
    RaftInAppBrowserError(
        message = "This page couldn't load.",
        onRetry = {},
        onOpenSystem = {},
        onClose = {},
    )
}
