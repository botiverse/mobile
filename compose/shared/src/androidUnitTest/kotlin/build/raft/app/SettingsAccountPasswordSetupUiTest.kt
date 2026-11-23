package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
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
import build.raft.app.data.DataResult
import build.raft.app.data.ScreenError
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
 * Settings › Account for an account without a password, on a real composition of the page with
 * the real store over a fake send (web AccountSection, botiverse/slock staging
 * 4be606f7fd789488e69df1095d4af028cc21ee49): "Set a password" with Set password by email instead of
 * Change password; Disconnect on the one linked sign-in asks "Set a password first" and sends the
 * setup email instead of disconnecting; with a password both stay as before.
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h1100dp")
class SettingsAccountPasswordSetupUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType
    private val tooMany = "Too many password reset requests. Please try again later."
    private val traceId = "0af7651916cd43dd8448eb211c80319c"

    private val account =
        RaftSettingsAccountPayload(
            instanceId = "settings-account-password-setup",
            profile =
                RaftSettingsAccountProfile(
                    displayName = "Artin",
                    handle = "artin",
                    email = "artin@raft.build",
                    avatarLabel = "AR",
                    verified = true,
                ),
            connectedAccounts =
                listOf(
                    RaftSettingsConnectedAccount("password", "Email password", "artin@raft.build", "connected", canConnect = false),
                    RaftSettingsConnectedAccount("github", "GitHub", "artin@github.example", "connected", canConnect = false),
                    RaftSettingsConnectedAccount("google", "Google", "Not connected", "notConnected", canConnect = true),
                ),
        )

    @BeforeTest
    fun setUp() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
        // The provider marks and the avatar placeholder go through Raft's image loader (as the account screenshots).
        build.raft.app.image.raftInstallImageLoader()
    }

    @AfterTest
    fun restore() {
        StringDesc.localeType = previousLocale
    }

    @Test
    fun withoutAPasswordTheCardSetsOneByEmailInsteadOfTheChangePasswordForm() =
        runRobolectricComposeUiTest {
            val sent = mutableListOf<String>()
            showAccount(passwordConfigured = false) { email ->
                sent += email
                DataResult.Content(Unit)
            }

            onNodeWithText("Set a password").assertExists()
            onNodeWithText("Add an email and password sign-in method before removing your final connected account.").assertExists()
            onNodeWithText("Change Password").assertDoesNotExist()
            onNodeWithText("Change password").assertDoesNotExist()

            onNodeWithTag(SettingsAccountSetPasswordButtonTag).performClick()
            waitForIdle()
            assertEquals(listOf("artin@raft.build"), sent)
            onNodeWithText("Password setup email sent. Use the link to set your password.").assertExists()
        }

    @Test
    fun disconnectingTheOneLinkedSignInSendsTheSetupEmailInstead() =
        runRobolectricComposeUiTest {
            val sent = mutableListOf<String>()
            val events = mutableListOf<Pair<String, String?>>()
            showAccount(passwordConfigured = false, events = events) { email ->
                sent += email
                DataResult.Content(Unit)
            }

            onNodeWithText("Disconnect").performClick()
            waitForIdle()
            onNodeWithTag(SettingsDisconnectProviderDialogTag).assertExists()
            onNodeWithText("Set a password first").assertExists()
            onNodeWithText("GitHub is your only sign-in account. Set a password before disconnecting it so you do not lose access.").assertExists()
            onNodeWithText("Disconnect GitHub").assertDoesNotExist()

            onNodeWithTag(SettingsDisconnectProviderConfirmTag).performClick()
            waitForIdle()
            assertEquals(listOf("artin@raft.build"), sent)
            assertEquals(emptyList(), events, "nothing is disconnected")
            onNodeWithTag(SettingsDisconnectProviderDialogTag).assertDoesNotExist()
            onNodeWithText("Password setup email sent. Use the link, then return here to disconnect GitHub.").assertExists()
            onNodeWithText("Password setup email sent. Use the link to set your password.").assertDoesNotExist()
        }

    @Test
    fun aRefusedSendStaysInTheDialogWithTheServersWordsAndTraceId() =
        runRobolectricComposeUiTest {
            showAccount(passwordConfigured = false) {
                DataResult.Error(ScreenError(code = "account_password_setup_http_429", serverMessage = tooMany, traceId = traceId))
            }

            onNodeWithText("Disconnect").performClick()
            waitForIdle()
            onNodeWithTag(SettingsDisconnectProviderConfirmTag).performClick()
            waitForIdle()

            onNodeWithTag(SettingsDisconnectProviderDialogTag).assertExists()
            // In the dialog and in the "Set a password" row behind it (web: both), each with the trace ID.
            onAllNodesWithText(tooMany, useUnmergedTree = true).assertCountEquals(2)
            onAllNodesWithTag(RaftTraceIdNoteTestTag, useUnmergedTree = true).assertCountEquals(2)
        }

    /** The account page as SettingsAccountScreen wires it: the real store, its send faked by [sendEmail]. */
    private fun ComposeUiTest.showAccount(
        passwordConfigured: Boolean?,
        events: MutableList<Pair<String, String?>> = mutableListOf(),
        sendEmail: suspend (String) -> DataResult<Unit>,
    ) {
        setContent {
            Host {
                val scope = rememberCoroutineScope()
                val store = remember { SettingsAccountPasswordSetupStore(sendEmail = sendEmail, scope = scope) }
                val state by store.state.collectAsState()
                SettingsAccountContent(
                    payload = account,
                    applyTopSafeArea = false,
                    passwordSetup = SettingsAccountPasswordSetupUi(passwordConfigured = passwordConfigured, state = state, dispatch = store::dispatch),
                    onEvent = { action, value -> events += action to value },
                )
            }
        }
        waitForIdle()
    }

    @Composable
    private fun Host(content: @Composable () -> Unit) {
        val pager =
            remember {
                object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
            }
        CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
            RaftDesignSystemTheme { content() }
        }
    }
}
