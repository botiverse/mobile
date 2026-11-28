package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
import build.raft.app.data.SharedAppLoginsState
import build.raft.app.data.stringDescLocaleType
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.raftEpochDayForDate
import build.raft.app.resources.StringDesc
import build.raft.app.settings.SettingsAppLoginsOverlays
import build.raft.app.settings.SettingsAppLoginsSection
import build.raft.app.settings.SettingsAppLoginsUi
import build.raft.designsystem.components.RaftPreviewCardDefaults
import build.raft.designsystem.components.RaftPreviewCardSurface
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric UI tests for web parity items:
 * - #25: Antigravity usage terms-unavailable notice (`botiverse/slock#17344`)
 * - #26: App Logins `kind: "card"` row & revoke-confirm copy (`botiverse/slock#17336`)
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h2400dp")
class AntigravityAndAppLoginsParityUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    @BeforeTest
    fun english() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
    }

    @AfterTest
    fun restoreLocale() {
        StringDesc.localeType = previousLocale
    }

    private val fixedTimeContext =
        RaftTimeContext(
            localOffsetMinutes = 0,
            todayEpochDay = raftEpochDayForDate(2026, 11, 28),
            nowEpochMillis = 1_795_843_200_000L,
        )

    private fun authCardState(
        raftRuntimeId: String,
        runtime: RuntimeAuthRuntime,
        status: RuntimeAuthStatus,
    ): ComputerRuntimeAuthCardState =
        ComputerRuntimeAuthCardState(
            raftRuntimeId = raftRuntimeId,
            runtime = runtime,
            view =
                RuntimeAuthView(
                    canAct = true,
                    availability = RuntimeAuthAvailability.Ok,
                    runtimes = listOf(runtime),
                    logoutRuntimes = listOf(runtime),
                    status = mapOf(runtime to status),
                ),
        )

    @Test
    fun antigravityUnsupportedSignedInCardShowsTermsNoticeWithoutUsageOrRefresh() =
        runRobolectricComposeUiTest {
            val state =
                ComputerRuntimeUsageSheetState(
                    load = RuntimeAccountUsageLoad.Loaded(RuntimeAccountUsageRead.Unsupported),
                    loading = false,
                    runtimeVersion = "1.2.3",
                    provider = RuntimeAccountUsageProvider.Antigravity,
                )
            val auth = authCardState("antigravity", RuntimeAuthRuntime.Antigravity, RuntimeAuthStatus.LoggedIn())
            setContent {
                RaftTheme(appearance = RaftAppearance.Light) {
                    RaftPreviewCardSurface(width = RaftPreviewCardDefaults.WideWidth) {
                        ComputerRuntimeUsageCard(
                            runtimeLabel = "Antigravity",
                            state = state,
                            timeContext = fixedTimeContext,
                            onRefresh = {},
                            onClose = {},
                            auth = auth,
                            authRuntimeName = "Antigravity",
                        )
                    }
                }
            }
            waitForIdle()
            onNodeWithTag(COMPUTER_RUNTIME_USAGE_UNAVAILABLE_TERMS_TAG)
                .assertIsDisplayed()
                .assertTextEquals("Usage lookup is currently unavailable under Antigravity's terms.")
            onNodeWithText("Antigravity").assertIsDisplayed()
            onNodeWithText("Antigravity usage").assertDoesNotExist()
            onNodeWithTag(COMPUTER_RUNTIME_USAGE_REFRESH_TAG).assertDoesNotExist()
            onNodeWithText("No usage yet").assertDoesNotExist()
        }

    @Test
    fun antigravityUnsupportedMembersCardWithoutAuthAndSheetSurfaceShowTermsNotice() =
        runRobolectricComposeUiTest {
            val state =
                ComputerRuntimeUsageSheetState(
                    load = RuntimeAccountUsageLoad.Loaded(RuntimeAccountUsageRead.Unsupported),
                    loading = false,
                    runtimeVersion = "1.2.3",
                    provider = RuntimeAccountUsageProvider.Antigravity,
                )
            setContent {
                RaftTheme(appearance = RaftAppearance.Light) {
                    Column {
                        RaftPreviewCardSurface(width = RaftPreviewCardDefaults.WideWidth) {
                            ComputerRuntimeUsageCard(
                                runtimeLabel = "Antigravity",
                                state = state,
                                timeContext = fixedTimeContext,
                                onRefresh = {},
                                onClose = {},
                                auth = null,
                                authRuntimeName = "Antigravity",
                            )
                        }
                        ComputerRuntimeUsageSheetSurface(
                            runtimeLabel = "Antigravity",
                            state = state,
                            timeContext = fixedTimeContext,
                            auth = null,
                        )
                    }
                }
            }
            waitForIdle()
            onAllNodesWithTag(COMPUTER_RUNTIME_USAGE_UNAVAILABLE_TERMS_TAG).assertCountEquals(2)
            onNodeWithText("Antigravity usage").assertDoesNotExist()
            onNodeWithText("No usage yet").assertDoesNotExist()
            onNodeWithTag(COMPUTER_RUNTIME_USAGE_REFRESH_TAG).assertDoesNotExist()
        }

    @Test
    fun antigravityUnsupportedSheetSurfaceShowsTermsNoticeAndPlainTitle() =
        runRobolectricComposeUiTest {
            val state =
                ComputerRuntimeUsageSheetState(
                    load = RuntimeAccountUsageLoad.Loaded(RuntimeAccountUsageRead.Unsupported),
                    loading = false,
                    runtimeVersion = "1.2.3",
                    provider = RuntimeAccountUsageProvider.Antigravity,
                )
            val auth = authCardState("antigravity", RuntimeAuthRuntime.Antigravity, RuntimeAuthStatus.LoggedIn())
            setContent {
                RaftTheme(appearance = RaftAppearance.Light) {
                    ComputerRuntimeUsageSheetSurface(
                        runtimeLabel = "Antigravity",
                        state = state,
                        timeContext = fixedTimeContext,
                        auth = auth,
                    )
                }
            }
            waitForIdle()
            onNodeWithTag(COMPUTER_RUNTIME_USAGE_UNAVAILABLE_TERMS_TAG)
                .assertIsDisplayed()
                .assertTextEquals("Usage lookup is currently unavailable under Antigravity's terms.")
            onNodeWithText("Antigravity").assertIsDisplayed()
            onNodeWithText("Antigravity usage").assertDoesNotExist()
            onNodeWithText("No usage yet").assertDoesNotExist()
        }

    @Test
    fun antigravitySignedOutAndNonAntigravityUnsupportedDoNotShowTermsNotice() =
        runRobolectricComposeUiTest {
            val agySignedOutAuth = authCardState("antigravity", RuntimeAuthRuntime.Antigravity, RuntimeAuthStatus.LoggedOut())
            val claudeSignedInAuth = authCardState("claude", RuntimeAuthRuntime.Claude, RuntimeAuthStatus.LoggedIn())
            setContent {
                RaftTheme(appearance = RaftAppearance.Light) {
                    Column {
                        RaftPreviewCardSurface(width = RaftPreviewCardDefaults.WideWidth) {
                            ComputerRuntimeUsageCard(
                                runtimeLabel = "Antigravity",
                                state =
                                    ComputerRuntimeUsageSheetState(
                                        load = RuntimeAccountUsageLoad.Loaded(RuntimeAccountUsageRead.Unsupported),
                                        loading = false,
                                        provider = RuntimeAccountUsageProvider.Antigravity,
                                    ),
                                timeContext = fixedTimeContext,
                                onRefresh = {},
                                onClose = {},
                                auth = agySignedOutAuth,
                                authRuntimeName = "Antigravity",
                            )
                        }
                        RaftPreviewCardSurface(width = RaftPreviewCardDefaults.WideWidth) {
                            ComputerRuntimeUsageCard(
                                runtimeLabel = "Claude Code",
                                state =
                                    ComputerRuntimeUsageSheetState(
                                        load = RuntimeAccountUsageLoad.Loaded(RuntimeAccountUsageRead.Unsupported),
                                        loading = false,
                                        provider = RuntimeAccountUsageProvider.Claude,
                                    ),
                                timeContext = fixedTimeContext,
                                onRefresh = {},
                                onClose = {},
                                auth = claudeSignedInAuth,
                                authRuntimeName = "Claude Code",
                            )
                        }
                    }
                }
            }
            waitForIdle()
            onNodeWithTag(COMPUTER_RUNTIME_USAGE_UNAVAILABLE_TERMS_TAG).assertDoesNotExist()
            onNodeWithText("No usage yet").assertDoesNotExist()
        }

    @Test
    fun appLoginsSectionAndRevokeDialogRenderSignInAndCardSpecificCopy() =
        runRobolectricComposeUiTest {
            val signInApp =
                AppLogin(
                    id = "in-1",
                    clientId = "rft_app_notes",
                    name = "Notes",
                    logoUrl = null,
                    grantedAt = "2026-11-10T08:00:00.000Z",
                    kind = AppLoginKind.SignIn,
                )
            val cardApp =
                AppLogin(
                    id = "card-1",
                    clientId = "rft_app_poll",
                    name = "Poll Bot",
                    logoUrl = null,
                    grantedAt = "2026-11-20T08:00:00.000Z",
                    kind = AppLoginKind.Card,
                )
            var pending by mutableStateOf<AppLogin?>(null)
            setContent {
                val ui =
                    SettingsAppLoginsUi(
                        state = SharedAppLoginsState(logins = listOf(signInApp, cardApp), pending = pending),
                        dispatch = {},
                        timeContext = fixedTimeContext,
                    )
                RaftTheme(appearance = RaftAppearance.Light) {
                    SettingsAppLoginsSection(ui)
                    SettingsAppLoginsOverlays(ui)
                }
            }
            waitForIdle()
            onNodeWithText("Signed in Nov 10").assertIsDisplayed()
            onNodeWithText("Used its card Nov 20").assertIsDisplayed()

            runOnIdle { pending = cardApp }
            waitForIdle()
            onNodeWithText(
                "Poll Bot stops getting your role and membership changes right away. Using its card again starts them again."
            ).assertIsDisplayed()

            runOnIdle { pending = signInApp }
            waitForIdle()
            onNodeWithText(
                "Notes loses access right away. To use it again, sign in with Raft again."
            ).assertIsDisplayed()
        }
}
