package build.raft.app

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import build.raft.app.data.AppLocale
import build.raft.app.data.RuntimeAuthAvailability
import build.raft.app.data.RuntimeAuthCall
import build.raft.app.data.RuntimeAuthLoginRegion
import build.raft.app.data.RuntimeAuthLogoutResult
import build.raft.app.data.RuntimeAuthProvider
import build.raft.app.data.RuntimeAuthProviderConnectResult
import build.raft.app.data.RuntimeAuthProviderEndpoint
import build.raft.app.data.RuntimeAuthProviderEndpointOption
import build.raft.app.data.RuntimeAuthProviderGroup
import build.raft.app.data.RuntimeAuthProvidersResult
import build.raft.app.data.RuntimeAuthRequestError
import build.raft.app.data.RuntimeAuthRuntime
import build.raft.app.data.RuntimeAuthStatus
import build.raft.app.data.RuntimeAuthView
import build.raft.app.data.RuntimeInstallPlan
import build.raft.app.data.RuntimeInstallResult
import build.raft.app.data.stringDescLocaleType
import build.raft.app.resources.StringDesc
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftPointerInput
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Qwen Code Connect Provider card/dialog parity (botiverse/slock `RuntimeProviderConnectDialog.tsx`
 * and `RuntimeAuthHeader.tsx`) and `ComputerRuntimeInstallChip` touch bottom sheet vs mouse popover.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h1200dp")
class ComputerQwenConnectAndInstallSheetUiTest {
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
    fun qwenCardShowsConfiguredAndUnconfiguredBadgesWithoutSignOut() = runRobolectricComposeUiTest {
        val unconfigured =
            ComputerRuntimeAuthCardState(
                raftRuntimeId = "qwen",
                runtime = RuntimeAuthRuntime.Qwen,
                view =
                    RuntimeAuthView(
                        canAct = true,
                        availability = RuntimeAuthAvailability.Ok,
                        status = mapOf(RuntimeAuthRuntime.Qwen to RuntimeAuthStatus.LoggedOut()),
                        runtimes = listOf(RuntimeAuthRuntime.Qwen),
                    ),
            )
        var opened = 0
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                RaftComputersTheme {
                    ComputerRuntimeAuthOnlyCard(
                        runtimeName = "Qwen Code",
                        runtimeVersion = "0.10.2",
                        auth = unconfigured,
                        onClose = null,
                        onSignIn = { opened++ },
                        onSignOut = {},
                        onSignOutProvider = {},
                    )
                }
            }
        }
        waitForIdle()

        onNodeWithTag(COMPUTER_RUNTIME_PROVIDER_NOT_CONFIGURED_TAG).assertIsDisplayed()
        onNodeWithText("No provider configured").assertIsDisplayed()
        onAllNodesWithTag(COMPUTER_RUNTIME_AUTH_SIGN_OUT_TAG).assertCountEquals(0)
        onNodeWithTag(COMPUTER_RUNTIME_AUTH_CONNECT_PROVIDER_TAG).assertIsDisplayed().performClick()
        assertEquals(1, opened)
    }

    @Test
    fun qwenCardShowsProviderConfiguredBadgeWhenLoggedIn() = runRobolectricComposeUiTest {
        val configured =
            ComputerRuntimeAuthCardState(
                raftRuntimeId = "qwen",
                runtime = RuntimeAuthRuntime.Qwen,
                view =
                    RuntimeAuthView(
                        canAct = true,
                        availability = RuntimeAuthAvailability.Ok,
                        status = mapOf(RuntimeAuthRuntime.Qwen to RuntimeAuthStatus.LoggedIn()),
                        runtimes = listOf(RuntimeAuthRuntime.Qwen),
                    ),
            )
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                RaftComputersTheme {
                    ComputerRuntimeAuthOnlyCard(
                        runtimeName = "Qwen Code",
                        runtimeVersion = "0.10.2",
                        auth = configured,
                        onClose = null,
                        onSignIn = {},
                        onSignOut = {},
                        onSignOutProvider = {},
                    )
                }
            }
        }
        waitForIdle()

        onNodeWithTag(COMPUTER_RUNTIME_PROVIDER_CONFIGURED_TAG).assertIsDisplayed()
        onNodeWithText("Provider configured").assertIsDisplayed()
        onNodeWithTag(COMPUTER_RUNTIME_AUTH_CONNECT_PROVIDER_TAG).assertIsDisplayed()
        onAllNodesWithTag(COMPUTER_RUNTIME_AUTH_SIGN_OUT_TAG).assertCountEquals(0)
    }

    @Test
    fun qwenConnectProviderDialogTwoStepFlowBackResetAndFieldError() = runRobolectricComposeUiTest {
        val ops =
            FakeQwenOps(
                providers =
                    listOf(
                        RuntimeAuthProvider(
                            id = "bailian",
                            label = "Alibaba Cloud Model Studio",
                            description = "DashScope API key",
                            group = RuntimeAuthProviderGroup("alibaba", "Alibaba Cloud"),
                            configured = true,
                            endpoint =
                                RuntimeAuthProviderEndpoint.Choice(
                                    listOf(
                                        RuntimeAuthProviderEndpointOption("cn", "China", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
                                        RuntimeAuthProviderEndpointOption("intl", "International", "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"),
                                    )
                                ),
                        ),
                        RuntimeAuthProvider(
                            id = "openrouter",
                            label = "OpenRouter",
                            endpoint = RuntimeAuthProviderEndpoint.Fixed,
                        ),
                        RuntimeAuthProvider(
                            id = "custom-openai",
                            label = "OpenAI-compatible",
                            group = RuntimeAuthProviderGroup("custom"),
                            endpoint = RuntimeAuthProviderEndpoint.Custom("https://api.example.com/v1"),
                            needsModelIds = true,
                        ),
                    ),
            )
        lateinit var controller: ComputerRuntimeAuthController
        setContent {
            val scope = rememberCoroutineScope()
            controller =
                remember {
                    ComputerRuntimeAuthController(
                        serverId = "s1",
                        machineId = "m1",
                        raftRuntimeId = "qwen",
                        runtime = RuntimeAuthRuntime.Qwen,
                        ops = ops,
                        scope = scope,
                    ).also { it.openSignIn() }
                }
            RaftTheme(appearance = RaftAppearance.Light) {
                ComputerRuntimeAuthDialogHost(
                    controller = controller,
                    runtimeName = "Qwen Code",
                    computerName = "Mac Studio",
                    agents = emptyList(),
                    openUrl = {},
                )
            }
        }
        waitForIdle()

        // Step 1: grouped provider list with configured caption.
        onNodeWithTag(COMPUTER_RUNTIME_PROVIDER_DIALOG_TAG).assertIsDisplayed()
        onNodeWithText("Connect Qwen Code to a provider").assertIsDisplayed()
        onNodeWithText("Alibaba Cloud").assertIsDisplayed()
        onNodeWithText("Other providers").assertIsDisplayed()
        onNodeWithText("Custom").assertIsDisplayed()
        onNodeWithText("Already configured on this Computer").assertIsDisplayed()
        onNodeWithTag(COMPUTER_RUNTIME_PROVIDER_CONTINUE_TAG).assertIsNotEnabled()

        // Select custom provider and continue to Step 2.
        onNodeWithText("OpenAI-compatible").performClick()
        waitForIdle()
        onNodeWithTag(COMPUTER_RUNTIME_PROVIDER_CONTINUE_TAG).assertIsEnabled().performClick()
        waitForIdle()

        onNodeWithTag(COMPUTER_RUNTIME_PROVIDER_CHOSEN_TAG).assertIsDisplayed()
        onNodeWithTag(COMPUTER_RUNTIME_PROVIDER_BASE_URL_TAG).assertIsDisplayed()
        onNodeWithTag(COMPUTER_RUNTIME_PROVIDER_MODEL_TAG).assertIsDisplayed()
        onNodeWithTag(COMPUTER_RUNTIME_PROVIDER_API_KEY_TAG).assertIsDisplayed()
        onNodeWithTag(COMPUTER_RUNTIME_PROVIDER_CONNECT_TAG).assertIsNotEnabled()

        // Enter values, then Back resets form + key.
        onNodeWithTag(COMPUTER_RUNTIME_PROVIDER_BASE_URL_TAG).performTextInput("https://temp.example.com/v1")
        onNodeWithTag(COMPUTER_RUNTIME_PROVIDER_MODEL_TAG).performTextInput("qwen3-temp")
        onNodeWithTag(COMPUTER_RUNTIME_PROVIDER_API_KEY_TAG).performTextInput("sk-temp")
        waitForIdle()
        onNodeWithTag(COMPUTER_RUNTIME_PROVIDER_CONNECT_TAG).assertIsEnabled()
        onNodeWithTag(COMPUTER_RUNTIME_PROVIDER_BACK_TAG).performClick()
        waitForIdle()

        // Back on Step 1; Continue again starts with blank form.
        onNodeWithTag(COMPUTER_RUNTIME_PROVIDER_CHOICES_TAG).assertIsDisplayed()
        onNodeWithTag(COMPUTER_RUNTIME_PROVIDER_CONTINUE_TAG).assertIsEnabled().performClick()
        waitForIdle()
        onNodeWithTag(COMPUTER_RUNTIME_PROVIDER_CONNECT_TAG).assertIsNotEnabled()

        // Trigger field error for invalid_model, then edit model to clear error and connect.
        ops.nextConnect = RuntimeAuthCall.Refused(RuntimeAuthRequestError(400, "invalid_model"))
        onNodeWithTag(COMPUTER_RUNTIME_PROVIDER_BASE_URL_TAG).performTextInput("https://api.example.com/v1")
        onNodeWithTag(COMPUTER_RUNTIME_PROVIDER_MODEL_TAG).performTextInput("bad model")
        onNodeWithTag(COMPUTER_RUNTIME_PROVIDER_API_KEY_TAG).performTextInput("sk-live-key")
        waitForIdle()
        onNodeWithTag(COMPUTER_RUNTIME_PROVIDER_CONNECT_TAG).performClick()
        waitForIdle()
        onNodeWithText("Enter one model name, with no spaces.").assertIsDisplayed()

        onNodeWithTag(COMPUTER_RUNTIME_PROVIDER_MODEL_TAG).performTextClearance()
        onNodeWithTag(COMPUTER_RUNTIME_PROVIDER_MODEL_TAG).performTextInput("qwen3-coder")
        waitForIdle()
        onAllNodesWithText("Enter one model name, with no spaces.").assertCountEquals(0)

        ops.nextConnect = RuntimeAuthCall.Done(RuntimeAuthProviderConnectResult.Connected)
        onNodeWithTag(COMPUTER_RUNTIME_PROVIDER_CONNECT_TAG).performClick()
        waitForIdle()

        onNodeWithTag(COMPUTER_RUNTIME_PROVIDER_SAVED_TAG).assertIsDisplayed()
        onNodeWithText("Saved on this Computer. Qwen Code checks the key when an agent runs.").assertIsDisplayed()
        assertEquals(
            RecordedConnect(
                providerId = "custom-openai",
                baseUrl = "https://api.example.com/v1",
                modelIds = listOf("qwen3-coder"),
            ),
            ops.lastConnect,
        )
    }

    @Test
    fun installChipOpensBottomSheetOnTouchAndStartsInstallConfirm() = runRobolectricComposeUiTest {
        val installOps = FakeInstallOps()
        setContent {
            val scope = rememberCoroutineScope()
            val controller = remember { ComputerRuntimeInstallController(installOps, scope) }
            RaftTheme(appearance = RaftAppearance.Light, pointerInput = RaftPointerInput.Touch) {
                RaftComputersTheme {
                    ComputerRuntimeInstallChip(
                        label = "Qwen Code (not installed)",
                        raftRuntimeId = "qwen",
                        runtimeName = "Qwen Code",
                        installAs = RuntimeAuthRuntime.Qwen,
                        controller = controller,
                    )
                    ComputerRuntimeInstallStatus(controller, "Mac Studio", openUrl = {})
                }
            }
        }
        waitForIdle()

        // Touch tap opens RaftSheet bottom sheet.
        onNodeWithTag("$COMPUTER_RUNTIME_INSTALL_CHIP_TAG-qwen").assertIsDisplayed().performClick()
        waitForIdle()
        onNodeWithTag("$COMPUTER_RUNTIME_INSTALL_SHEET_TAG-qwen").assertIsDisplayed()
        onNodeWithTag("$COMPUTER_RUNTIME_INSTALL_TAG-qwen").assertIsDisplayed().performClick()
        waitForIdle()

        // Sheet dismisses and install confirm dialog opens.
        onAllNodesWithTag("$COMPUTER_RUNTIME_INSTALL_SHEET_TAG-qwen").assertCountEquals(0)
        onNodeWithTag(COMPUTER_RUNTIME_INSTALL_DIALOG_TAG).assertIsDisplayed()
        assertEquals(listOf(RuntimeAuthRuntime.Qwen), installOps.planRequests)
    }

    @Test
    fun installChipPinsHoverCardOnFinePointerWithoutOpeningSheet() = runRobolectricComposeUiTest {
        val installOps = FakeInstallOps()
        setContent {
            val scope = rememberCoroutineScope()
            val controller = remember { ComputerRuntimeInstallController(installOps, scope) }
            RaftTheme(appearance = RaftAppearance.Light, pointerInput = RaftPointerInput.Fine) {
                RaftComputersTheme {
                    ComputerRuntimeInstallChip(
                        label = "Qwen Code (not installed)",
                        raftRuntimeId = "qwen",
                        runtimeName = "Qwen Code",
                        installAs = RuntimeAuthRuntime.Qwen,
                        controller = controller,
                    )
                    ComputerRuntimeInstallStatus(controller, "Mac Studio", openUrl = {})
                }
            }
        }
        waitForIdle()

        // Mouse/fine-pointer click pins RaftPreviewCard popover instead of opening bottom sheet.
        onNodeWithTag("$COMPUTER_RUNTIME_INSTALL_CHIP_TAG-qwen").assertIsDisplayed().performMouseInput { click(center) }
        waitForIdle()
        onAllNodesWithTag("$COMPUTER_RUNTIME_INSTALL_SHEET_TAG-qwen").assertCountEquals(0)
        onNodeWithTag("$COMPUTER_RUNTIME_INSTALL_TAG-qwen").assertIsDisplayed().performClick()
        waitForIdle()
        onNodeWithTag(COMPUTER_RUNTIME_INSTALL_DIALOG_TAG).assertIsDisplayed()
        assertTrue(installOps.planRequests.contains(RuntimeAuthRuntime.Qwen))
    }

    private data class RecordedConnect(
        val providerId: String,
        val baseUrl: String?,
        val modelIds: List<String>?,
    )

    private class FakeQwenOps(
        private val providers: List<RuntimeAuthProvider>,
    ) : ComputerRuntimeAuthOps {
        var nextConnect: RuntimeAuthCall<RuntimeAuthProviderConnectResult> = RuntimeAuthCall.Done(RuntimeAuthProviderConnectResult.Connected)
        var lastConnect: RecordedConnect? = null

        override suspend fun view(force: Boolean): RuntimeAuthView =
            RuntimeAuthView(
                canAct = true,
                availability = RuntimeAuthAvailability.Ok,
                status = mapOf(RuntimeAuthRuntime.Qwen to RuntimeAuthStatus.LoggedIn()),
                runtimes = listOf(RuntimeAuthRuntime.Qwen),
            )

        override suspend fun invalidate() = Unit

        override suspend fun startLogin(runtime: RuntimeAuthRuntime, region: RuntimeAuthLoginRegion?, providerId: String?, oauthMethod: String?): RuntimeAuthCall<String> =
            error("unused")

        override suspend fun submitCode(runtime: RuntimeAuthRuntime, loginId: String, code: String): RuntimeAuthCall<Unit> = error("unused")

        override fun cancelLogin(runtime: RuntimeAuthRuntime, loginId: String) = Unit

        override suspend fun logout(runtime: RuntimeAuthRuntime, providerId: String?): RuntimeAuthCall<RuntimeAuthLogoutResult> = error("unused")

        override suspend fun providers(runtime: RuntimeAuthRuntime): RuntimeAuthCall<RuntimeAuthProvidersResult> =
            RuntimeAuthCall.Done(RuntimeAuthProvidersResult.Listed(providers))

        override suspend fun connect(
            runtime: RuntimeAuthRuntime,
            providerId: String,
            apiKey: String,
            baseUrl: String?,
            modelIds: List<String>?,
        ): RuntimeAuthCall<RuntimeAuthProviderConnectResult> {
            lastConnect = RecordedConnect(providerId = providerId, baseUrl = baseUrl, modelIds = modelIds)
            return nextConnect
        }
    }

    private class FakeInstallOps : ComputerRuntimeInstallOps {
        val planRequests = mutableListOf<RuntimeAuthRuntime>()

        override suspend fun view(force: Boolean): RuntimeAuthView =
            RuntimeAuthView(
                canAct = true,
                availability = RuntimeAuthAvailability.Ok,
                installableRuntimes = listOf(RuntimeAuthRuntime.Qwen),
            )

        override suspend fun installPlan(runtime: RuntimeAuthRuntime): RuntimeAuthCall<RuntimeInstallPlan> {
            planRequests += runtime
            return RuntimeAuthCall.Done(
                RuntimeInstallPlan.Plan(
                    steps = listOf("npm install -g @qwen-code/qwen-code"),
                    source = "https://qwenlm.github.io/qwen-code-docs/",
                )
            )
        }

        override suspend fun install(runtime: RuntimeAuthRuntime): RuntimeAuthCall<RuntimeInstallResult> =
            RuntimeAuthCall.Done(RuntimeInstallResult.Installed(version = "0.10.2"))
    }
}
