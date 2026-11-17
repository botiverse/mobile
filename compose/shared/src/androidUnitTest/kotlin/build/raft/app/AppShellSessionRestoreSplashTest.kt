package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLocale
import build.raft.app.data.stringDescLocaleType
import build.raft.app.resources.StringDesc
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Web #15116: the cold-restore splash waited out a hanging /auth/me with no way out. Past
 * [AppShellRestoreSlowAfterMillis] it says so and offers Sign out and Try again; nothing before.
 * The compose clock is held, so the wait is virtual time.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class AppShellSessionRestoreSplashTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    @BeforeTest
    fun english() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
    }

    @AfterTest
    fun restoreLocale() {
        StringDesc.localeType = previousLocale
    }

    private fun ComposeUiTest.advance(millis: Long) {
        mainClock.advanceTimeBy(millis)
        waitForIdle()
    }

    @Test
    fun actionsAppearOnlyPastTheLimitAndTryAgainStartsTheWaitOver() = runRobolectricComposeUiTest {
        var retries = 0
        var signOuts = 0
        mainClock.autoAdvance = false
        setContent {
            InPage {
                AppShellSessionRestoreSplash(signingOut = false, onRetry = { retries += 1 }, onSignOut = { signOuts += 1 })
            }
        }
        advance(16)
        onNodeWithTag(AppShellRestoreSlowTestTag).assertDoesNotExist()
        advance(AppShellRestoreSlowAfterMillis - 500)
        onNodeWithTag(AppShellRestoreSlowTestTag).assertDoesNotExist()
        advance(1_000)
        onNodeWithTag(AppShellRestoreSlowTestTag).assertExists()
        onNodeWithText("This is taking longer than usual.").assertExists()
        onNodeWithText("Sign out").assertExists()

        onNodeWithTag(AppShellRestoreRetryTestTag).performClick()
        // Let the click's state change reach composition: a few frames, then idle.
        repeat(4) { mainClock.advanceTimeByFrame() }
        waitForIdle()
        assertEquals(1, retries)
        assertEquals(0, signOuts)
        // Try again starts the wait over (the web reloads the page): no actions until the limit again.
        onNodeWithTag(AppShellRestoreSlowTestTag).assertDoesNotExist()
        advance(AppShellRestoreSlowAfterMillis + 500)
        onNodeWithTag(AppShellRestoreSlowTestTag).assertExists()
    }

    @Test
    fun signOutCallsBackAndDoesNotRetry() = runRobolectricComposeUiTest {
        var signOuts = 0
        var retries = 0
        mainClock.autoAdvance = false
        setContent {
            InPage {
                AppShellSessionRestoreSplash(signingOut = false, onRetry = { retries += 1 }, onSignOut = { signOuts += 1 })
            }
        }
        advance(AppShellRestoreSlowAfterMillis + 500)
        onNodeWithTag(AppShellRestoreSignOutTestTag).performClick()
        advance(16)
        assertEquals(1, signOuts)
        assertEquals(0, retries)
    }

    @Test
    fun whileSigningOutBothActionsAreDisabled() = runRobolectricComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            InPage { AppShellSessionRestoreSplash(signingOut = true, onRetry = {}, onSignOut = {}) }
        }
        advance(16)
        onNodeWithTag(AppShellRestoreSignOutTestTag).assertIsNotEnabled()
        onNodeWithTag(AppShellRestoreRetryTestTag).assertIsNotEnabled()
    }
}

/**
 * The slow cold-restore splash (logo, "This is taking longer than usual.", Sign out, Try again) in
 * light and dark. Record on CI (`record_screenshots=true`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class AppShellSessionRestoreSplashScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    @BeforeTest
    fun english() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
    }

    @AfterTest
    fun restoreLocale() {
        StringDesc.localeType = previousLocale
    }

    // The launch logo (`assets://`) needs Raft's image loader; without it the logo drew or not
    // depending on whether an earlier test in the same JVM fork had installed it (see
    // SettingsAccountScreenshotTest).
    @org.junit.Before
    fun installImageLoader() {
        runCatching { build.raft.app.image.raftInstallImageLoader() }
    }

    @Test
    fun slowLight() = capture("app-shell.session-restore.slow", RaftAppAppearance.Light)

    @Test
    fun slowDark() = capture("app-shell.session-restore.slow.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            InPage {
                // No wait: the screenshot shows the state past the limit.
                AppShellSessionRestoreSplash(signingOut = false, onRetry = {}, onSignOut = {}, slowAfterMillis = 0L)
            }
        }
}

@Composable
private fun InPage(content: @Composable () -> Unit) {
    val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
    CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) { RaftDesignSystemTheme(content) }
}
