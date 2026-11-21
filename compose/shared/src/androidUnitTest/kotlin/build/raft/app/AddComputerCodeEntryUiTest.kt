package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AddComputerCodeError
import build.raft.app.data.AddComputerCodeProblem
import build.raft.app.data.AddComputerCodeState
import build.raft.app.data.AddComputerCodeStep
import build.raft.app.data.AddComputerDialogState
import build.raft.app.data.AddComputerStep
import build.raft.app.data.DeviceLoginGrant
import build.raft.app.data.DeviceLoginGrantKind
import build.raft.app.data.DeviceLoginRequest
import build.raft.app.data.DeviceLoginServer
import build.raft.app.data.SharedSettingsIntent
import build.raft.designsystem.components.RaftDialogSheetDefaults
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Add Computer's terminal code on a real composition (web botiverse/slock 329e1700f7
 * AddComputerCodeEntry / AddComputerCodeReview): the field is closed until asked for, the confirm
 * screen shows the facts for this sheet's server only and takes the sheet's head, and a code for
 * anything else has nothing to deny or approve.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp")
class AddComputerCodeEntryUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val thisServer = DeviceLoginServer("server-1", "slock", "Slock Community")
    private val request =
        DeviceLoginRequest(
            userCode = "VSWA-7M58",
            clientName = "raft-computer",
            requestedAt = "2026-11-21T10:00:00.000Z",
            requesterIp = "203.0.113.7",
            requesterLocation = "São Paulo, BR",
            approvalTicket = "ticket-1",
            grant =
                DeviceLoginGrant(
                    kind = DeviceLoginGrantKind.ComputerAttach,
                    kindWireName = "computer_attach",
                    machineName = "build-box",
                    hostname = "build-box.lan",
                    os = "Linux",
                    servers = listOf(DeviceLoginServer("server-2", "beta", "Beta Labs"), thisServer),
                ),
        )

    @Test
    fun theFieldStaysClosedUntilAskedForThenChecksTheTypedCode() = runRobolectricComposeUiTest {
        val intents = mutableListOf<SharedSettingsIntent>()
        var code by mutableStateOf(AddComputerCodeState())
        setContent { Host { Column { AddComputerCodeEntry(code) { intents += it } } } }
        waitForIdle()

        onNodeWithTag(AddComputerCodeEntryTag).assertDoesNotExist()
        onNodeWithText("The terminal shows a code? Enter it here.").performClick()
        assertEquals(listOf<SharedSettingsIntent>(SharedSettingsIntent.ToggleAddComputerCode), intents)

        code = AddComputerCodeState(open = true)
        waitForIdle()
        onNodeWithText("Code from the terminal").assertExists()
        onNodeWithText("Continue").assertIsNotEnabled()
        onNode(hasSetTextAction()).performTextInput("vswa-7m58")
        code = code.copy(code = "vswa-7m58")
        waitForIdle()
        onNodeWithText("Continue").assertIsEnabled().performClick()
        // The typed text reaches the store (however many edits the input arrives in), then Continue.
        assertEquals(SharedSettingsIntent.ToggleAddComputerCode, intents.first())
        assertEquals(SharedSettingsIntent.SetAddComputerCode("vswa-7m58"), intents.filterIsInstance<SharedSettingsIntent.SetAddComputerCode>().last())
        assertEquals(SharedSettingsIntent.SubmitAddComputerCode, intents.last())

        // A refusal the device login page can help with says so in the field, with the page offered.
        code = code.copy(error = AddComputerCodeError(AddComputerCodeProblem.KindUnsupported, serverCode = "grant_kind_unsupported"))
        waitForIdle()
        onNodeWithText("This kind of device login isn't supported here", substring = true).assertExists()
        onNodeWithText("Open the device login page", substring = true).assertExists()
    }

    private val confirming = AddComputerCodeState(open = true, code = "vswa-7m58", step = AddComputerCodeStep.Confirm(request, thisServer))

    @Test
    fun theConfirmScreenShowsTheFactsForThisServerAndTakesTheSheetsHead() = runRobolectricComposeUiTest {
        val state = AddComputerDialogState(step = AddComputerStep.Waiting, code = confirming)
        setContent {
            Host {
                AddComputerSheetSurfaceForFixture(state, Modifier.fillMaxHeight(RaftDialogSheetDefaults.HeightFraction))
            }
        }
        waitForIdle()

        onNodeWithText("Add “build-box” to a server").assertExists()
        onNodeWithText("Add to server").assertIsEnabled()
        onNodeWithText("VSWA-7M58").assertExists()
        onNodeWithText("“build-box”").assertExists()
        onNodeWithText("build-box.lan · Linux").assertExists()
        onNodeWithText("203.0.113.7 · São Paulo, BR").assertExists()
        onNodeWithText("Slock Community").assertExists()
        onNodeWithText("/slock").assertExists()
        // This sheet's server and no other: nothing to pick.
        onNodeWithText("Beta Labs").assertDoesNotExist()
        // The command waits behind the confirm screen.
        onNodeWithText("The terminal shows a code? Enter it here.").assertDoesNotExist()
    }

    @Test
    fun denyAndEnterADifferentCodeGoBackToTheStore() = runRobolectricComposeUiTest {
        val intents = mutableListOf<SharedSettingsIntent>()
        setContent {
            Host {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    AddComputerCodeReview(confirming, checkNotNull(confirming.review)) { intents += it }
                }
            }
        }
        waitForIdle()

        onNodeWithText("Deny").performScrollTo().performClick()
        onNodeWithText("Enter a different code").performScrollTo().performClick()
        assertEquals(listOf(SharedSettingsIntent.DenyAddComputerCode, SharedSettingsIntent.ResetAddComputerCode), intents)
    }

    @Test
    fun aCodeForAnythingButAComputerHereHasNothingToApproveOrDeny() = runRobolectricComposeUiTest {
        val elsewhere =
            AddComputerDialogState(
                step = AddComputerStep.Waiting,
                code = AddComputerCodeState(open = true, step = AddComputerCodeStep.Confirm(request.copy(grant = null), target = null)),
            )
        setContent {
            Host {
                AddComputerSheetSurfaceForFixture(elsewhere, Modifier.fillMaxHeight(RaftDialogSheetDefaults.HeightFraction))
            }
        }
        waitForIdle()

        onNodeWithText("Approve device login").assertExists()
        onNodeWithText("This code isn’t for adding a computer", substring = true).assertExists()
        onNodeWithText("Add to server").assertDoesNotExist()
        onNodeWithText("Deny").assertDoesNotExist()
        onNodeWithText("Enter a different code").assertExists()
    }

    /** As the sheet's screenshots draw it: the page's activity and the design-system theme. */
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
