package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AccountLoginSecurity
import build.raft.app.data.AppLocale
import build.raft.app.data.ScreenError
import build.raft.app.data.SharedAccountLoginSecurityState
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
 * Settings › Account › Login & security on a real composition (AGENTS.md "Compose UI tests";
 * #proj-mobile:#121): the web Account page's Passkeys count and Two-step verification state
 * (slock PasskeysSection / TwoStepVerificationSection copy), each row opening its web section in
 * the system browser on the environment's web URL ([settingsAccountLoginSecurityOpener], as the
 * page wires it to `bridge.openUrl(url, inBrowser = true)`).
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h900dp")
class SettingsAccountLoginSecurityUiTest {
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

    private fun ComposeUiTest.showGroup(
        state: SharedAccountLoginSecurityState,
        opened: MutableList<String> = mutableListOf(),
        onRetry: () -> Unit = {},
    ) {
        val ui =
            SettingsAccountLoginSecurityUi(
                state = state,
                onOpen =
                    settingsAccountLoginSecurityOpener(
                        webBaseUrl = "https://staging.raft.build",
                        serverSlug = { "hq" },
                        log = {},
                        openInBrowser = { opened += it },
                    ),
                onRetry = onRetry,
            )
        setContent {
            RaftDesignSystemTheme {
                Column(Modifier.fillMaxSize()) { SettingsAccountLoginSecurityGroup(ui) }
            }
        }
    }

    @Test
    fun theRowsShowTheStateAndOpenTheirWebSectionInTheBrowser() =
        runRobolectricComposeUiTest {
            val opened = mutableListOf<String>()
            showGroup(SharedAccountLoginSecurityState(security = AccountLoginSecurity(twoStepEnabled = true, passkeyCount = 2)), opened)

            onNodeWithText("Login & security").assertExists()
            onNodeWithText("Two-step verification").assertExists()
            onNodeWithText("On").assertExists()
            onNodeWithText("Passkeys").assertExists()
            onNodeWithText("2 passkeys").assertExists()

            onNodeWithTag(SettingsAccountTwoStepRowTag).performClick()
            onNodeWithTag(SettingsAccountPasskeysRowTag).performClick()
            assertEquals(
                listOf(
                    "https://staging.raft.build/s/hq/settings/account#two-step",
                    "https://staging.raft.build/s/hq/settings/account#passkeys",
                ),
                opened,
            )
        }

    @Test
    fun offAndNoPasskeysReadAsTheWebSays() =
        runRobolectricComposeUiTest {
            showGroup(SharedAccountLoginSecurityState(security = AccountLoginSecurity(twoStepEnabled = false, passkeyCount = 0)))
            onNodeWithText("Off").assertExists()
            onNodeWithText("No passkeys yet").assertExists()
        }

    @Test
    fun aFailedFirstReadSaysSoWithTheTraceIdAndRetries() =
        runRobolectricComposeUiTest {
            var retries = 0
            showGroup(
                SharedAccountLoginSecurityState(error = ScreenError(code = "second_factor_status_http_500", traceId = "0af7651916cd43dd8448eb211c80319c")),
                onRetry = { retries++ },
            )
            onNodeWithText("Couldn't load two-step verification and passkeys.").assertExists()
            onAllNodesWithTag(RaftTraceIdNoteTestTag).assertCountEquals(1)
            onAllNodesWithTag(SettingsAccountTwoStepRowTag).assertCountEquals(0)
            onNodeWithText("Retry").performClick()
            assertEquals(1, retries)
        }
}
