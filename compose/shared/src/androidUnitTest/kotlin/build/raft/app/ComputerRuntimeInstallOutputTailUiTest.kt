package build.raft.app

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import build.raft.app.data.AppLocale
import build.raft.app.data.RuntimeAuthAvailability
import build.raft.app.data.RuntimeAuthCall
import build.raft.app.data.RuntimeAuthRuntime
import build.raft.app.data.RuntimeAuthView
import build.raft.app.data.RuntimeInstallPlan
import build.raft.app.data.RuntimeInstallResult
import build.raft.app.data.stringDescLocaleType
import build.raft.app.resources.StringDesc
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * botiverse/slock #17305 (commit dc3153798, web `RuntimeInstallOutcome.tsx`):
 * Failed runtime installs carrying redacted `outputTail` render "Last lines of the install output:"
 * and the multiline tail in a monospace scrollable code block under the failure sentence; failures
 * without `outputTail` hide the section cleanly.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h1200dp")
class ComputerRuntimeInstallOutputTailUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    @BeforeTest
    fun setUp() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
    }

    @AfterTest
    fun tearDown() {
        StringDesc.localeType = previousLocale
    }

    @Test
    fun failedInstallWithOutputTailRendersLabelAndMultilineTailUnderFailureSentence() = runRobolectricComposeUiTest {
        val state =
            ComputerRuntimeInstallState.Done(
                runtime = RuntimeAuthRuntime.Grok,
                raftRuntimeId = "grok",
                runtimeName = "Grok",
                result = RuntimeInstallResult.Failed(exitCode = 1, outputTail = SAMPLE_TAIL),
            )
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                RaftComputersTheme {
                    ComputerRuntimeInstallOutcome(state)
                }
            }
        }
        waitForIdle()

        onNodeWithTag(COMPUTER_RUNTIME_OPERATION_OUTCOME_TAG).assertIsDisplayed()
        onNodeWithText("Grok's installer didn't finish, so it is still not installed.").assertIsDisplayed()
        onNodeWithTag(COMPUTER_RUNTIME_INSTALL_OUTPUT_TAG).assertIsDisplayed()
        onNodeWithText("Last lines of the install output:").assertIsDisplayed()
        onNodeWithTag(COMPUTER_RUNTIME_INSTALL_OUTPUT_TAIL_TAG).assertIsDisplayed().assertTextEquals(SAMPLE_TAIL)

        val outcomeBounds = onNodeWithTag(COMPUTER_RUNTIME_OPERATION_OUTCOME_TAG).fetchSemanticsNode().boundsInRoot
        val outputBounds = onNodeWithTag(COMPUTER_RUNTIME_INSTALL_OUTPUT_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue(
            outputBounds.top >= outcomeBounds.bottom,
            "expected output tail ($outputBounds) below failure outcome ($outcomeBounds)",
        )
    }

    @Test
    fun failedInstallWithoutOutputTailHidesOutputTailSection() = runRobolectricComposeUiTest {
        val state =
            ComputerRuntimeInstallState.Done(
                runtime = RuntimeAuthRuntime.Grok,
                raftRuntimeId = "grok",
                runtimeName = "Grok",
                result = RuntimeInstallResult.Failed(exitCode = 1, outputTail = null),
            )
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                RaftComputersTheme {
                    ComputerRuntimeInstallOutcome(state)
                }
            }
        }
        waitForIdle()

        onNodeWithTag(COMPUTER_RUNTIME_OPERATION_OUTCOME_TAG).assertIsDisplayed()
        onNodeWithText("Grok's installer didn't finish, so it is still not installed.").assertIsDisplayed()
        onAllNodesWithTag(COMPUTER_RUNTIME_INSTALL_OUTPUT_TAG).assertCountEquals(0)
        onAllNodesWithTag(COMPUTER_RUNTIME_INSTALL_OUTPUT_TAIL_TAG).assertCountEquals(0)
        onAllNodesWithText("Last lines of the install output:").assertCountEquals(0)
    }

    @Test
    fun failedInstallWithBlankOutputTailHidesOutputTailSection() = runRobolectricComposeUiTest {
        val state =
            ComputerRuntimeInstallState.Done(
                runtime = RuntimeAuthRuntime.Grok,
                raftRuntimeId = "grok",
                runtimeName = "Grok",
                result = RuntimeInstallResult.Failed(exitCode = 1, outputTail = "   \n\t  "),
            )
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                RaftComputersTheme {
                    ComputerRuntimeInstallOutcome(state)
                }
            }
        }
        waitForIdle()

        onNodeWithTag(COMPUTER_RUNTIME_OPERATION_OUTCOME_TAG).assertIsDisplayed()
        onAllNodesWithTag(COMPUTER_RUNTIME_INSTALL_OUTPUT_TAG).assertCountEquals(0)
        onAllNodesWithTag(COMPUTER_RUNTIME_INSTALL_OUTPUT_TAIL_TAG).assertCountEquals(0)
        onAllNodesWithText("Last lines of the install output:").assertCountEquals(0)
    }

    @Test
    fun retryingFailedInstallClearsDisplayedOutputTail() = runRobolectricComposeUiTest {
        val answers =
            ArrayDeque<RuntimeAuthCall<RuntimeInstallResult>>(
                listOf(
                    RuntimeAuthCall.Done(RuntimeInstallResult.Failed(exitCode = 1, outputTail = SAMPLE_TAIL)),
                    RuntimeAuthCall.Done(RuntimeInstallResult.Installed("0.1.9")),
                )
            )
        val ops =
            object : ComputerRuntimeInstallOps {
                override suspend fun view(force: Boolean): RuntimeAuthView = INSTALL_VIEW

                override suspend fun installPlan(runtime: RuntimeAuthRuntime): RuntimeAuthCall<RuntimeInstallPlan> =
                    RuntimeAuthCall.Done(RuntimeInstallPlan.Plan(listOf(SAMPLE_STEP), SAMPLE_SOURCE))

                override suspend fun install(runtime: RuntimeAuthRuntime): RuntimeAuthCall<RuntimeInstallResult> =
                    answers.removeFirst()
            }
        lateinit var controller: ComputerRuntimeInstallController
        setContent {
            val scope = rememberCoroutineScope()
            controller = remember { ComputerRuntimeInstallController(ops, scope) }
            RaftTheme(appearance = RaftAppearance.Light) {
                RaftComputersTheme {
                    ComputerRuntimeInstallStatus(controller = controller, computerName = "Build Box", openUrl = {})
                }
            }
        }
        waitForIdle()

        runOnIdle { controller.start(RuntimeAuthRuntime.Grok, "grok", "Grok") }
        waitUntil(timeoutMillis = 5_000) { controller.state is ComputerRuntimeInstallState.Confirm }
        runOnIdle { controller.confirm() }
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag(COMPUTER_RUNTIME_INSTALL_OUTPUT_TAIL_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Last lines of the install output:").assertIsDisplayed()
        onNodeWithTag(COMPUTER_RUNTIME_INSTALL_OUTPUT_TAIL_TAG).assertTextEquals(SAMPLE_TAIL)

        runOnIdle { controller.start(RuntimeAuthRuntime.Grok, "grok", "Grok") }
        waitUntil(timeoutMillis = 5_000) { controller.state is ComputerRuntimeInstallState.Confirm }
        onAllNodesWithTag(COMPUTER_RUNTIME_INSTALL_OUTPUT_TAG).assertCountEquals(0)
        runOnIdle { controller.confirm() }
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText("Installed Grok 0.1.9.").fetchSemanticsNodes().isNotEmpty()
        }
        onAllNodesWithTag(COMPUTER_RUNTIME_INSTALL_OUTPUT_TAG).assertCountEquals(0)
        onAllNodesWithTag(COMPUTER_RUNTIME_INSTALL_OUTPUT_TAIL_TAG).assertCountEquals(0)
    }

    private companion object {
        const val SAMPLE_TAIL = "npm error code E404\nnpm error 404 Not Found - GET https://registry.npmjs.org/@deepseek-ai%2fdsh"
        const val SAMPLE_STEP = "curl -fsSL https://x.ai/cli/install.sh | bash"
        const val SAMPLE_SOURCE = "https://docs.x.ai/docs/grok-build"

        val INSTALL_VIEW =
            RuntimeAuthView(
                canAct = true,
                availability = RuntimeAuthAvailability.Ok,
                runtimes = listOf(RuntimeAuthRuntime.Grok),
                installableRuntimes = listOf(RuntimeAuthRuntime.Grok),
            )
    }
}
