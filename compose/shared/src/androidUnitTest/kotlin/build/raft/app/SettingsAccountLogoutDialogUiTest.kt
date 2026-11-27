package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLocale
import build.raft.app.data.stringDescLocaleType
import build.raft.app.resources.StringDesc
import build.raft.app.settings.RaftSettingsAccountPayload
import build.raft.app.settings.SettingsLogoutAction
import build.raft.app.settings.SettingsLogoutButtonTag
import build.raft.app.settings.SettingsLogoutCancelTag
import build.raft.app.settings.SettingsLogoutConfirmTag
import build.raft.app.settings.SettingsLogoutDialogTag
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings › Account › Sign out on a real composition (#proj-mobile:#188): as on the web
 * (`AccountSignOutSection` + `ConfirmDialog`, `settings.session.confirm*`), the Sign out button
 * opens a "Sign out?" dialog instead of signing out; its Sign out signs out once, Cancel closes it
 * without signing out. The same composable runs on Android and iOS.
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp")
class SettingsAccountLogoutDialogUiTest {
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

    private fun ComposeUiTest.showSection(onConfirmLogout: () -> Unit) {
        setContent {
            RaftDesignSystemTheme {
                Column(Modifier.fillMaxSize()) { SettingsLogoutAction(RaftSettingsAccountPayload(), onConfirmLogout) }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.dialogShown(): Boolean =
        onAllNodes(hasTestTag(SettingsLogoutDialogTag)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun tappingSignOutOpensTheDialogWithoutSigningOut() =
        runRobolectricComposeUiTest {
            var signOuts = 0
            showSection { signOuts++ }
            assertTrue(!dialogShown(), "the dialog must not show before Sign out is tapped")

            onNodeWithTag(SettingsLogoutButtonTag).performClick()
            waitForIdle()

            onNodeWithTag(SettingsLogoutDialogTag).assertIsDisplayed()
            onNodeWithText("Sign out?").assertIsDisplayed()
            onNodeWithText("Sign out of this device? Your account and data are kept; you can sign back in any time.").assertIsDisplayed()
            onNodeWithTag(SettingsLogoutCancelTag).assertIsDisplayed()
            onNodeWithTag(SettingsLogoutConfirmTag).assertIsDisplayed()
            assertEquals(0, signOuts, "opening the dialog must not sign out")
        }

    @Test
    fun confirmSignsOutOnceAndShowsSigningOut() =
        runRobolectricComposeUiTest {
            var signOuts = 0
            showSection { signOuts++ }
            onNodeWithTag(SettingsLogoutButtonTag).performClick()
            waitForIdle()

            onNodeWithTag(SettingsLogoutConfirmTag).performClick()
            waitForIdle()
            assertEquals(1, signOuts, "confirm must dispatch sign-out")

            // Signing out: the dialog stays with the confirm busy and Cancel off; another tap does nothing.
            assertTrue(dialogShown(), "the dialog stays while signing out")
            onNodeWithTag(SettingsLogoutCancelTag).assertIsNotEnabled()
            onNodeWithTag(SettingsLogoutConfirmTag).performClick()
            waitForIdle()
            assertEquals(1, signOuts, "a second confirm tap must not sign out again")
        }
}
