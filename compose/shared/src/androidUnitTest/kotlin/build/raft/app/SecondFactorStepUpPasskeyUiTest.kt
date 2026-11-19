package build.raft.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLocale
import build.raft.app.data.RaftNativePasskeyAssertion
import build.raft.app.data.RaftNativePasskeyAssertionResult
import build.raft.app.data.RaftNativePasskeyRequest
import build.raft.app.data.SecondFactorPasskeyCeremony
import build.raft.app.data.SecondFactorPasskeyOptionsResult
import build.raft.app.data.SecondFactorStepUpBanner
import build.raft.app.data.SecondFactorStepUpDialogState
import build.raft.app.data.SecondFactorStepUpIntent
import build.raft.app.data.SecondFactorStepUpMethod
import build.raft.app.data.parseSharedSecondFactorPasskeyAssertionOptions
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

/**
 * "Verify it's you" with a passkey on a real composition (AGENTS.md "Compose UI tests"): Use
 * passkey is the primary button and the codes are links; the coordinator's prompt runs once on
 * the platform port and its answer goes back; a refused passkey shows its trace ID.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h900dp")
class SecondFactorStepUpPasskeyUiTest {
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

    private val passkeyFirst =
        SecondFactorStepUpDialogState(
            methods = listOf(SecondFactorStepUpMethod.Passkey, SecondFactorStepUpMethod.Totp, SecondFactorStepUpMethod.BackupCode),
            method = SecondFactorStepUpMethod.Passkey,
            passkeyListed = true,
        )

    private val request =
        (
            parseSharedSecondFactorPasskeyAssertionOptions(
                200,
                """{"options":{"rpId":"raft.test","challenge":"Y2hhbGxlbmdlLXRva2Vu","allowCredentials":[],"timeout":300000,"userVerification":"required"}}"""
            ) as SecondFactorPasskeyOptionsResult.Ready
        ).let { RaftNativePasskeyRequest(it.requestJson, it.options) }

    private fun ComposeUiTest.show(
        state: androidx.compose.runtime.State<SecondFactorStepUpDialogState>,
        intents: MutableList<SecondFactorStepUpIntent>,
        port: RaftNativePasskeyAssertion = RaftNativePasskeyAssertion { error("no prompt asked") },
    ) {
        setContent {
            RaftDesignSystemTheme {
                SecondFactorStepUpDialog(state = state.value, onIntent = { intents += it }, passkeyAssertion = port)
            }
        }
        waitForIdle()
    }

    @Test
    fun usePasskeyIsThePrimaryButtonAndTheCodesAreLinks() = runRobolectricComposeUiTest {
        val intents = mutableListOf<SecondFactorStepUpIntent>()
        show(mutableStateOf(passkeyFirst), intents)

        onNodeWithText("Use Passkey").assertExists()
        onNodeWithText("Authenticator code").assertDoesNotExist()
        onNodeWithTag(secondFactorStepUpMethodLinkTag(SecondFactorStepUpMethod.Totp)).assertExists()
        onNodeWithTag(secondFactorStepUpMethodLinkTag(SecondFactorStepUpMethod.BackupCode)).assertExists()
        onNodeWithText("Make this change in Raft on the web", substring = true).assertDoesNotExist()

        onNodeWithTag(SECOND_FACTOR_STEP_UP_SUBMIT_TAG).assertIsEnabled().performClick()
        onNodeWithTag(secondFactorStepUpMethodLinkTag(SecondFactorStepUpMethod.Totp)).performClick()
        waitForIdle()
        assertEquals(
            listOf(SecondFactorStepUpIntent.Submit, SecondFactorStepUpIntent.SwitchMethod(SecondFactorStepUpMethod.Totp)),
            intents
        )
    }

    @Test
    fun thePromptRunsOnceOnThePortAndItsAnswerGoesBack() = runRobolectricComposeUiTest {
        val intents = mutableListOf<SecondFactorStepUpIntent>()
        val asked = mutableListOf<RaftNativePasskeyRequest>()
        val ceremony = SecondFactorPasskeyCeremony(id = 7, request = request)
        val state = mutableStateOf(passkeyFirst.copy(passkeyCeremony = ceremony))
        show(state, intents, RaftNativePasskeyAssertion { asked += it; RaftNativePasskeyAssertionResult.Cancelled })

        assertEquals(listOf(request), asked)
        assertEquals(listOf<SecondFactorStepUpIntent>(SecondFactorStepUpIntent.PasskeyAssertionFinished(7, RaftNativePasskeyAssertionResult.Cancelled)), intents)

        // The coordinator clears the prompt and shows why; no second prompt.
        runOnIdle { state.value = passkeyFirst.copy(banner = SecondFactorStepUpBanner.PasskeyCancelled) }
        waitForIdle()
        assertEquals(1, asked.size)
        onNodeWithText("The passkey prompt was closed. Try again, or use another way.").assertExists()
    }

    @Test
    fun aRefusedPasskeyShowsItsTraceId() = runRobolectricComposeUiTest {
        val intents = mutableListOf<SecondFactorStepUpIntent>()
        show(mutableStateOf(passkeyFirst.copy(banner = SecondFactorStepUpBanner.PasskeyInvalid("4bf92f3577b34da6a3ce929d0e0e4736"))), intents)

        onNodeWithText("That passkey didn't work. Try again, or use another way.").assertExists()
        onAllNodesWithTag(RaftTraceIdNoteTestTag).assertCountEquals(1)
        onNodeWithText("4bf92f35", substring = true).assertExists()
    }
}
