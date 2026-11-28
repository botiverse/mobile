package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
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
import build.raft.app.data.AppLogin
import build.raft.app.data.AppLoginKind
import build.raft.app.data.RuntimeAccountUsageLoad
import build.raft.app.data.RuntimeAccountUsageProvider
import build.raft.app.data.RuntimeAccountUsageRead
import build.raft.app.data.RuntimeAuthAvailability
import build.raft.app.data.RuntimeAuthRuntime
import build.raft.app.data.RuntimeAuthStatus
import build.raft.app.data.RuntimeAuthView
import build.raft.app.data.SharedAppLoginsIntent
import build.raft.app.data.SharedAppLoginsState
import build.raft.app.data.stringDescLocaleType
import build.raft.app.members.MembersAgentRuntimeConfigResolution
import build.raft.app.members.MembersAgentRuntimeConfigRows
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.RuntimeConfig
import build.raft.app.model.RuntimeModeConfig
import build.raft.app.model.RuntimeModelConfig
import build.raft.app.model.RuntimeProviderConfig
import build.raft.app.model.raftEpochDayForDate
import build.raft.app.resources.StringDesc
import build.raft.app.settings.SettingsAppLoginsOverlays
import build.raft.app.settings.SettingsAppLoginsSection
import build.raft.app.settings.SettingsAppLoginsUi
import build.raft.app.settings.settingsAppLoginRevokeTag
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric Compose UI tests for 1.32.0 Web/Backend parity polish items:
 * - `botiverse/slock#17344`: Antigravity usage card/sheet renders the unavailable-under-terms notice
 *   with no Check again button when signed in and `state == unsupported` (hidden when signed out or
 *   for other providers).
 * - `botiverse/slock#17336`: Settings › Account App Logins renders card-specific row and revoke
 *   confirmation copy for `kind = "card"` while preserving sign-in copy for `kind = "sign_in"`.
 * - `#proj-runtime:#124`: Read-only agent profile rows render the Provider row for `runtime = "pi-sdk"`.
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h1400dp")
class WebParityPolish132UiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    private val fixedTimeContext =
        RaftTimeContext(
            localOffsetMinutes = 0,
            todayEpochDay = raftEpochDayForDate(2026, 11, 28),
            // 2026-11-28T10:00:00Z
            nowEpochMillis = 1_795_860_000_000L,
        )

    @BeforeTest
    fun setUp() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
        build.raft.app.image.raftInstallImageLoader()
    }

    @AfterTest
    fun tearDown() {
        StringDesc.localeType = previousLocale
    }

    // --- #17344: Antigravity usage unavailable under terms --------------------------------------

    @Test
    fun antigravityUnsupportedCardAndSheetShowTermsNoticeWithoutCheckAgainWhenSignedIn() =
        runRobolectricComposeUiTest {
            val unsupportedState =
                ComputerRuntimeUsageSheetState(
                    load = RuntimeAccountUsageLoad.Loaded(RuntimeAccountUsageRead.Unsupported),
                    loading = false,
                    checkDue = false,
                    runtimeVersion = "1.0.0",
                    provider = RuntimeAccountUsageProvider.Antigravity,
                )
            val signedInView =
                RuntimeAuthView(
                    canAct = true,
                    availability = RuntimeAuthAvailability.Ok,
                    status = mapOf(RuntimeAuthRuntime.Antigravity to RuntimeAuthStatus.LoggedIn()),
                    runtimes = listOf(RuntimeAuthRuntime.Antigravity),
                    logoutRuntimes = listOf(RuntimeAuthRuntime.Antigravity),
                )
            val signedOutView =
                signedInView.copy(
                    status = mapOf(RuntimeAuthRuntime.Antigravity to RuntimeAuthStatus.LoggedOut()),
                )
            var signedOut by mutableStateOf(false)
            var provider by mutableStateOf(RuntimeAccountUsageProvider.Antigravity)

            setContent {
                CompositionLocalProvider(LocalRaftTimeContextProvider provides RaftTimeContextProvider(fixedTimeContext)) {
                    RaftTheme(appearance = RaftAppearance.Light) {
                        RaftComputersTheme {
                            val auth =
                                if (provider == RuntimeAccountUsageProvider.Antigravity) {
                                    ComputerRuntimeAuthCardState(
                                        raftRuntimeId = "antigravity",
                                        runtime = RuntimeAuthRuntime.Antigravity,
                                        view = if (signedOut) signedOutView else signedInView,
                                    )
                                } else {
                                    null
                                }
                            Column(Modifier.fillMaxSize()) {
                                ComputerRuntimeUsageCard(
                                    runtimeLabel = if (provider == RuntimeAccountUsageProvider.Antigravity) "Antigravity" else "Codex CLI",
                                    state = unsupportedState.copy(provider = provider),
                                    timeContext = fixedTimeContext,
                                    onRefresh = {},
                                    onClose = {},
                                    auth = auth,
                                    authRuntimeName = if (provider == RuntimeAccountUsageProvider.Antigravity) "Antigravity" else "Codex CLI",
                                    runtimeId = provider.wire,
                                )
                            }
                        }
                    }
                }
            }
            waitForIdle()

            // Signed in + Antigravity + unsupported -> terms notice, no Check again, no "No usage yet".
            onNodeWithTag(COMPUTER_RUNTIME_USAGE_UNAVAILABLE_TERMS_TAG).assertIsDisplayed()
            onNodeWithText("Usage lookup is currently unavailable under Antigravity's terms.").assertIsDisplayed()
            onAllNodesWithTag(COMPUTER_RUNTIME_USAGE_REFRESH_TAG).assertCountEquals(0)
            onAllNodesWithText("No usage yet").assertCountEquals(0)

            // Signed out + Antigravity + unsupported -> Sign in shown, no terms notice.
            signedOut = true
            waitForIdle()
            onAllNodesWithTag(COMPUTER_RUNTIME_USAGE_UNAVAILABLE_TERMS_TAG).assertCountEquals(0)
            onNodeWithTag(COMPUTER_RUNTIME_AUTH_SIGN_IN_TAG).assertIsDisplayed()
            onAllNodesWithTag(COMPUTER_RUNTIME_USAGE_REFRESH_TAG).assertCountEquals(0)

            // Other provider + unsupported -> no Antigravity terms notice, no Check again.
            signedOut = false
            provider = RuntimeAccountUsageProvider.Codex
            waitForIdle()
            onAllNodesWithTag(COMPUTER_RUNTIME_USAGE_UNAVAILABLE_TERMS_TAG).assertCountEquals(0)
            onAllNodesWithTag(COMPUTER_RUNTIME_USAGE_REFRESH_TAG).assertCountEquals(0)
        }

    @Test
    fun antigravityUnsupportedSheetSurfaceShowsTermsNoticeWithoutCheckAgain() =
        runRobolectricComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalRaftTimeContextProvider provides RaftTimeContextProvider(fixedTimeContext)) {
                    RaftTheme(appearance = RaftAppearance.Light) {
                        RaftComputersTheme {
                            ComputerRuntimeUsageSheetSurface(
                                runtimeLabel = "Antigravity",
                                state =
                                    ComputerRuntimeUsageSheetState(
                                        load = RuntimeAccountUsageLoad.Loaded(RuntimeAccountUsageRead.Unsupported),
                                        loading = false,
                                        checkDue = false,
                                        provider = RuntimeAccountUsageProvider.Antigravity,
                                    ),
                                timeContext = fixedTimeContext,
                                runtimeId = "antigravity",
                            )
                        }
                    }
                }
            }
            waitForIdle()

            onNodeWithTag(COMPUTER_RUNTIME_USAGE_UNAVAILABLE_TERMS_TAG).assertIsDisplayed()
            onNodeWithText("Usage lookup is currently unavailable under Antigravity's terms.").assertIsDisplayed()
            onAllNodesWithTag(COMPUTER_RUNTIME_USAGE_REFRESH_TAG).assertCountEquals(0)
        }

    // --- #17336: App Logins kind: "card" copy ---------------------------------------------------

    @Test
    fun appLoginsRendersCardCopyAndSignInCopyInRowsAndRevokeDialogs() =
        runRobolectricComposeUiTest {
            val signInLogin =
                AppLogin(
                    id = "consent-1",
                    clientId = "rft_app_calendar",
                    name = "Calendar",
                    logoUrl = null,
                    grantedAt = "2026-11-10T08:00:00.000Z",
                    kind = AppLoginKind.SignIn,
                )
            val cardLogin =
                AppLogin(
                    id = "card-1",
                    clientId = "rft_app_approvals",
                    name = "Approvals",
                    logoUrl = null,
                    grantedAt = "2026-11-20T08:00:00.000Z",
                    kind = AppLoginKind.Card,
                )
            var state by mutableStateOf(SharedAppLoginsState(logins = listOf(signInLogin, cardLogin)))
            val dispatch: (SharedAppLoginsIntent) -> Unit = { intent ->
                when (intent) {
                    is SharedAppLoginsIntent.OpenRevoke -> state = state.copy(pending = intent.login, revokeFailed = false)
                    SharedAppLoginsIntent.CloseRevoke -> state = state.copy(pending = null, revokeFailed = false)
                    else -> Unit
                }
            }

            setContent {
                ActivityHost {
                    RaftDesignSystemTheme {
                        val ui = SettingsAppLoginsUi(state = state, dispatch = dispatch, timeContext = fixedTimeContext)
                        Column(Modifier.fillMaxSize()) {
                            SettingsAppLoginsSection(ui)
                        }
                        SettingsAppLoginsOverlays(ui)
                    }
                }
            }
            waitForIdle()

            onNodeWithText("Calendar").assertIsDisplayed()
            onNode(hasText("Signed in ", substring = true)).assertIsDisplayed()
            onNodeWithText("Approvals").assertIsDisplayed()
            onNode(hasText("Used its card ", substring = true)).assertIsDisplayed()

            // Card entry revoke confirm uses confirmBodyCard.
            onNodeWithTag(settingsAppLoginRevokeTag(cardLogin.clientId)).performClick()
            waitForIdle()
            onNodeWithText("Revoke Approvals?").assertIsDisplayed()
            onNodeWithText("Approvals stops getting your role and membership changes right away. Using its card again starts them again.").assertIsDisplayed()

            // Switch pending to sign-in entry and verify original confirmBody.
            state = state.copy(pending = signInLogin)
            waitForIdle()
            onNodeWithText("Revoke Calendar?").assertIsDisplayed()
            onNodeWithText("Calendar loses access right away. To use it again, sign in with Raft again.").assertIsDisplayed()
        }

    // --- #proj-runtime:#124: pi-sdk read-only Provider row --------------------------------------

    @Test
    fun piSdkReadOnlyRuntimeConfigRowsRendersProviderRow() =
        runRobolectricComposeUiTest {
            var provider by mutableStateOf<RuntimeProviderConfig?>(RuntimeProviderConfig.PiBuiltin("deepseek", ""))

            setContent {
                ActivityHost {
                    RaftDesignSystemTheme {
                        MembersAgentRuntimeConfigRows(
                            resolution =
                                MembersAgentRuntimeConfigResolution.Content(
                                    RuntimeConfig(
                                        runtime = "pi-sdk",
                                        provider = provider,
                                        model = RuntimeModelConfig.Preset("deepseek/deepseek-v4-pro"),
                                        mode = RuntimeModeConfig.Default,
                                        reasoningEffort = null,
                                        envVars = null,
                                    )
                                ),
                            runtimeModelsCatalog = null,
                            machineId = null,
                            serverId = null,
                        )
                    }
                }
            }
            waitForIdle()

            onNodeWithText("Provider").assertIsDisplayed()
            onNodeWithText("deepseek").assertIsDisplayed()

            // Without an explicit pi-builtin provider, pi-sdk still displays the Provider row as Default.
            provider = null
            waitForIdle()
            onNodeWithText("Provider").assertIsDisplayed()
            onNodeWithText("Default").assertIsDisplayed()
        }

    @Composable
    private fun ActivityHost(content: @Composable () -> Unit) {
        val pager =
            remember {
                object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
            }
        CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) { content() }
    }
}
