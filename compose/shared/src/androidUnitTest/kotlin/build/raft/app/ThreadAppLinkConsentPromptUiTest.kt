package build.raft.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLinkConsentStatus
import build.raft.app.data.AppLinkPromptApp
import build.raft.app.data.AppLocale
import build.raft.app.data.stringDescLocaleType
import build.raft.app.resources.StringDesc
import build.raft.app.thread.ThreadAppLinkConsentPrompt
import build.raft.app.thread.ThreadMessageCardConsentPrompt
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Verifies App detail v6 step-named copy on [ThreadAppLinkConsentPrompt] (botiverse/slock#17247)
 * while preserving separate "Allowing…" progress copy on [ThreadMessageCardConsentPrompt].
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp")
class ThreadAppLinkConsentPromptUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    @BeforeTest
    fun english() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
    }

    @AfterTest
    fun restore() {
        StringDesc.localeType = previousLocale
    }

    @Test
    fun linkPreviewConsentPromptNamesItsStepsAndTurningOnProgress(): Unit =
        runRobolectricComposeUiTest {
            val app = AppLinkPromptApp(id = "client-linear", name = "Linear", icon = null)
            setContent {
                RaftDesignSystemTheme {
                    ThreadAppLinkConsentPrompt(
                        app = app,
                        url = "https://linear.app/raft/issue/RAFT-812",
                        status = null,
                        onDismiss = {},
                        onAllow = {},
                        onDecline = {},
                        onOpenLink = {},
                        inWindow = false,
                    )
                }
            }
            waitForIdle()
            onNodeWithText("With previews on, Linear will know which of its links you view.").assertIsDisplayed()
            onNodeWithText("Show previews").assertIsDisplayed()
            onNodeWithText("Keep plain links").assertIsDisplayed()
            onAllNodesWithText("Don't allow").assertCountEquals(0)
        }

    @Test
    fun linkPreviewConsentPromptUsesTurningOnProgressDescription(): Unit =
        runRobolectricComposeUiTest {
            val app = AppLinkPromptApp(id = "client-linear", name = "Linear", icon = null)
            setContent {
                RaftDesignSystemTheme {
                    ThreadAppLinkConsentPrompt(
                        app = app,
                        url = "https://linear.app/raft/issue/RAFT-812",
                        status = AppLinkConsentStatus.Allowing,
                        onDismiss = {},
                        onAllow = {},
                        onDecline = {},
                        onOpenLink = {},
                        inWindow = false,
                    )
                }
            }
            waitForIdle()
            onNodeWithContentDescription("Turning on previews…", useUnmergedTree = true).assertIsDisplayed()
            onAllNodesWithContentDescription("Allowing…", useUnmergedTree = true).assertCountEquals(0)
        }

    @Test
    fun messageCardConsentPromptKeepsAllowingProgressLabel(): Unit =
        runRobolectricComposeUiTest {
            setContent {
                RaftDesignSystemTheme {
                    ThreadMessageCardConsentPrompt(
                        appName = "Linear",
                        status = AppLinkConsentStatus.Allowing,
                        onAllow = {},
                        onNotNow = {},
                        inWindow = false,
                    )
                }
            }
            waitForIdle()
            onNodeWithContentDescription("Allowing…", useUnmergedTree = true).assertIsDisplayed()
            onAllNodesWithContentDescription("Turning on previews…", useUnmergedTree = true).assertCountEquals(0)
        }
}
