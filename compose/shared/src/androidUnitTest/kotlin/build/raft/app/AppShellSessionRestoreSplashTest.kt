package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
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
import build.raft.app.shell.AppShellRestoreRetryTestTag
import build.raft.app.shell.AppShellRestoreSlowAfterMillis
import build.raft.app.shell.AppShellRestoreSlowTestTag
import build.raft.app.shell.AppShellSessionRestoreSplash
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

    /**
     * With the clock held, a write from outside composition (a click handler on the test thread)
     * only reaches the recomposer once the global snapshot's apply notifications go out; Android
     * posts that to the main looper, which runs after the frames below. Send them first, as
     * SettingsSearchUiTest does, then move the clock on.
     */
    private fun ComposeUiTest.advance(millis: Long) {
        Snapshot.sendApplyNotifications()
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
        // The click's writes (slow = false, attempt + 1) reach composition on the next frame.
        advance(16)
        assertEquals(1, retries)
        assertEquals(0, signOuts)
        // Try again starts the wait over (the web reloads the page): no actions until the limit again.
        onNodeWithTag(AppShellRestoreSlowTestTag).assertDoesNotExist()
        advance(AppShellRestoreSlowAfterMillis + 500)
        onNodeWithTag(AppShellRestoreSlowTestTag).assertExists()
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

    // The launch logo (`assets://`) needs Raft's image loader (see SettingsAccountScreenshotTest).
    @org.junit.Before
    fun installImageLoader() {
        build.raft.app.image.raftInstallImageLoader()
    }

    @Test
    fun slowLight() = capture("app-shell.session-restore.slow", RaftAppAppearance.Light)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            // Inspection mode loads the logo inline (Coil runs a preview request with an empty
            // coroutine context) instead of on Dispatchers.IO: settle() idles the main looper
            // only, so the first capture in a fork (dark) finished before the decode landed and
            // drew no logo, while the second hit the memory cache.
            CompositionLocalProvider(LocalInspectionMode provides true) {
                InPage {
                    // No wait: the screenshot shows the state past the limit.
                    AppShellSessionRestoreSplash(signingOut = false, onRetry = {}, onSignOut = {}, slowAfterMillis = 0L)
                }
            }
        }
}

@Composable
private fun InPage(content: @Composable () -> Unit) {
    val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
    CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) { RaftDesignSystemTheme(content) }
}
