package build.raft.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
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
import build.raft.app.data.RaftAppWebStepUpBrowser
import build.raft.app.data.RaftAppWebStepUpBrowserResult
import build.raft.app.data.SecondFactorWebStepUpPhase
import build.raft.app.data.SecondFactorWebStepUpSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
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
        opened: MutableList<String> = mutableListOf(),
        browser: RaftAppWebStepUpBrowser = RaftAppWebStepUpBrowser { _, _ -> error("no web page asked") },
        callbacks: Flow<String> = emptyFlow(),
    ) {
        setContent {
            RaftDesignSystemTheme {
                SecondFactorStepUpDialog(
                    state = state.value,
                    onIntent = { intents += it },
                    passkeyAssertion = port,
                    openWebPage = { opened += it },
                    webStepUpBrowser = browser,
                    webStepUpCallbacks = callbacks,
                )
            }
        }
        waitForIdle()
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

        // A local passkey/network verification failure gives an actionable fallback hint.
        runOnIdle { state.value = passkeyFirst.copy(banner = SecondFactorStepUpBanner.PasskeyFailed) }
        waitForIdle()
        onNodeWithText("Passkey verification couldn't complete. Check your network connection and try again, or use another way.").assertExists()
    }

    @Test
    fun continueInBrowserOpensTheCoordinatorsPageOnceAndHandsBackHowItEnded() = runRobolectricComposeUiTest {
        val intents = mutableListOf<SecondFactorStepUpIntent>()
        val pages = mutableListOf<Pair<String, String>>()
        val browser = RaftAppWebStepUpBrowser { url, scheme ->
            pages += url to scheme
            RaftAppWebStepUpBrowserResult.Callback("raft://login/callback?requestId=r-1&code=k-1")
        }
        val callbacks = MutableSharedFlow<String>(extraBufferCapacity = 1)
        val ready = passkeyOnlyHere.copy(webUrl = "https://app.raft.build/settings/account", webStepUp = SecondFactorWebStepUpPhase.Ready)
        val state = mutableStateOf(ready)
        show(state, intents, browser = browser, callbacks = callbacks)

        // Continue in browser stands in for Open on the web, and goes on by itself: no second tap.
        onNodeWithText("add a new passkey", substring = true).assertExists()
        onAllNodesWithTag(SECOND_FACTOR_STEP_UP_OPEN_ON_WEB_TAG).assertCountEquals(0)
        waitForIdle()
        assertEquals(listOf<SecondFactorStepUpIntent>(SecondFactorStepUpIntent.ContinueInBrowser), intents)

        // The coordinator asks for its page: opened once, and how it ended goes back.
        val session = SecondFactorWebStepUpSession(3, "https://app.raft.build/login/app/verify?request=r-1", "raft")
        runOnIdle { state.value = ready.copy(webStepUp = SecondFactorWebStepUpPhase.Waiting, webStepUpSession = session) }
        waitForIdle()
        onNodeWithText("Waiting for you to finish in the browser", substring = true).assertExists()
        assertEquals(listOf(session.url to "raft"), pages)
        assertEquals(
            SecondFactorStepUpIntent.WebStepUpBrowserFinished(3, RaftAppWebStepUpBrowserResult.Callback("raft://login/callback?requestId=r-1&code=k-1")),
            intents.last(),
        )

        // While waiting, a callback handed to the app elsewhere goes to the coordinator too.
        runOnIdle { callbacks.tryEmit("raft://login/callback?requestId=r-1&code=k-1") }
        waitForIdle()
        assertEquals(SecondFactorStepUpIntent.WebStepUpCallback("raft://login/callback?requestId=r-1&code=k-1"), intents.last())
        assertEquals(1, pages.size)
    }

    @Test
    fun continueInBrowserWithOtherMethodsAutoOpensAndShowsMethodLinksAfterCancel() = runRobolectricComposeUiTest {
        val intents = mutableListOf<SecondFactorStepUpIntent>()
        val readyWithCodes =
            SecondFactorStepUpDialogState(
                methods = listOf(SecondFactorStepUpMethod.Totp, SecondFactorStepUpMethod.BackupCode),
                method = null,
                passkeyListed = true,
                webStepUp = SecondFactorWebStepUpPhase.Ready,
            )
        val state = mutableStateOf(readyWithCodes)
        show(state, intents)

        // Straight into browser step-up with no PasskeyUnsupported warning banner.
        assertEquals(listOf<SecondFactorStepUpIntent>(SecondFactorStepUpIntent.ContinueInBrowser), intents)
        onAllNodesWithTag(SECOND_FACTOR_STEP_UP_BANNER_TAG).assertCountEquals(0)

        // After cancelling the browser sheet, Continue in browser stays primary with code switch links underneath.
        runOnIdle { state.value = readyWithCodes.copy(banner = SecondFactorStepUpBanner.WebStepUpCancelled) }
        waitForIdle()
        onNodeWithTag(SECOND_FACTOR_STEP_UP_CONTINUE_IN_BROWSER_TAG).assertExists()
        onNodeWithTag(secondFactorStepUpMethodLinkTag(SecondFactorStepUpMethod.Totp)).assertExists()
        onNodeWithTag(secondFactorStepUpMethodLinkTag(SecondFactorStepUpMethod.BackupCode)).assertExists()

        onNodeWithTag(secondFactorStepUpMethodLinkTag(SecondFactorStepUpMethod.Totp)).performClick()
        waitForIdle()
        assertEquals(SecondFactorStepUpIntent.SwitchMethod(SecondFactorStepUpMethod.Totp), intents.last())
    }

    private val passkeyOnlyHere = SecondFactorStepUpDialogState(methods = emptyList(), method = null, passkeyListed = true)
}
