package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import build.raft.app.base.BridgeModule
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.IModuleCreator
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.PagerManager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.data.AppLocale
import build.raft.app.data.AuthSessionEvent
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.RaftComputersApi
import build.raft.app.data.RaftComputersApiClient
import build.raft.app.data.RaftDatetimeFoundationOwner
import build.raft.app.data.RaftEpochMillisSource
import build.raft.app.data.RaftLocalOffsetMinutesSource
import build.raft.app.data.RealtimeGrantRecord
import build.raft.app.data.RealtimeSlotKey
import build.raft.app.data.RuntimeAccountUsageKey
import build.raft.app.data.RuntimeAccountUsageProvider
import build.raft.app.data.SharedComputersOwner
import build.raft.app.data.SharedRealtimeDomainEventOwner
import build.raft.app.data.SharedRuntimeAccountUsageStore
import build.raft.app.data.stringDescLocaleType
import build.raft.app.members.MembersRuntimeAccountUsageAccess
import build.raft.app.members.MembersRuntimeAccountUsageOwnerTracker
import build.raft.app.model.RaftTimeFormat24Hour
import build.raft.app.model.RuntimeAuthLoginEventPushed
import build.raft.app.model.RuntimeAuthLoginStep
import build.raft.app.model.ServerId
import build.raft.app.resources.StringDesc
import build.raft.app.thread.key
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Hands 420d13cb: signing into Antigravity from mobile Computers opens the OAuth sign-in dialog
 * with an external browser button and a code-entry field.
 *
 * 1. Opening Sign in from the usage sheet hides the sheet so only the sign-in dialog is mounted
 *    (never two stacked modal windows across a browser return).
 * 2. Returning from the browser when foreground refresh emits [AuthSessionEvent.BindingMetadataChanged]
 *    for the same `(owner, serverId)` must not flash null, cancel the running login, or drop the
 *    code-entry dialog.
 * 3. Actually switching server or signing out still cancels the running login and closes the dialog.
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h900dp")
class ComputerRuntimeSignInBrowserReturnUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    private val livePager =
        object : Pager() {}.apply {
            pagerId = "computer-runtime-sign-in-browser-return"
            pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
            registerModule(BridgeModule.MODULE_NAME, IModuleCreator { BridgeModule() })
        }

    @BeforeTest
    fun setUp() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
        PagerManager.register(livePager)
    }

    @AfterTest
    fun tearDown() {
        StringDesc.localeType = previousLocale
        PagerManager.unregister(livePager)
    }

    @Test
    fun antigravitySignInFromUsageSheetHidesSheetKeepsCodeFieldAcrossMetadataRefreshAndSubmitsCode() =
        runRobolectricComposeUiTest {
            val api = FakeAntigravitySignInApi()
            val realtime = SharedRealtimeDomainEventOwner(runIfCurrentGrant = { _, block -> block(); true })
            val authEvents = MutableSharedFlow<AuthSessionEvent>(extraBufferCapacity = 8)
            var currentOwner: SharedComputersOwner? = OWNER
            var currentServerId: String? = SERVER_ID
            val openedUrls = mutableListOf<String>()

            setSheetHostContent(
                api = api,
                realtime = realtime,
                authEvents = authEvents,
                readOwner = { currentOwner },
                readServerId = { currentServerId },
                openUrl = { openedUrls += it },
            )

            // Wait for signed-out Antigravity sheet to show the Sign in button, then open sign-in.
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithTag(COMPUTER_RUNTIME_AUTH_SIGN_IN_TAG, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            onAllNodesWithTag(COMPUTER_RUNTIME_AUTH_SIGN_IN_TAG, useUnmergedTree = true).onFirst().performClick()
            waitUntil(timeoutMillis = 5_000) { api.startCalls == 1 }

            // The sign-in dialog took over and the underlying usage sheet is hidden (no stacked dialogs).
            onNodeWithTag(COMPUTER_RUNTIME_SIGN_IN_DIALOG_TAG, useUnmergedTree = true).assertExists()
            onAllNodesWithTag(COMPUTER_RUNTIME_AUTH_SIGN_IN_TAG, useUnmergedTree = true).assertCountEquals(0)

            // Deliver Google OAuth URL + paste-code prompt from the Computer over realtime.
            val grant = testGrant(SERVER_ID)
            runBlocking {
                realtime.accept(
                    grant,
                    RuntimeAuthLoginEventPushed(SERVER_ID, MACHINE_ID, "antigravity", LOGIN_ID, RuntimeAuthLoginStep.AuthUrl(OAUTH_URL)),
                )
                realtime.accept(
                    grant,
                    RuntimeAuthLoginEventPushed(SERVER_ID, MACHINE_ID, "antigravity", LOGIN_ID, RuntimeAuthLoginStep.CodePrompt()),
                )
            }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithTag(COMPUTER_RUNTIME_SIGN_IN_OPEN_PAGE_TAG, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() &&
                    onAllNodesWithTag(COMPUTER_RUNTIME_SIGN_IN_CODE_TAG, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }

            // Tap Open Sign-in Page to leave for Google login.
            onNodeWithTag(COMPUTER_RUNTIME_SIGN_IN_OPEN_PAGE_TAG, useUnmergedTree = true).performClick()
            assertEquals(listOf(OAUTH_URL), openedUrls)

            // Simulate foreground return triggering metadata refresh (same owner & serverId).
            assertTrue(authEvents.tryEmit(AuthSessionEvent.BindingMetadataChanged(generation = 2)))
            waitForIdle()

            // Running login was NOT cancelled, no second login started, and code field is still right here.
            assertEquals(0, api.cancelledLoginIds.size)
            assertEquals(1, api.startCalls)
            onNodeWithTag(COMPUTER_RUNTIME_SIGN_IN_DIALOG_TAG, useUnmergedTree = true).assertExists()
            onNodeWithTag(COMPUTER_RUNTIME_SIGN_IN_CODE_TAG, useUnmergedTree = true).assertExists()

            // Paste the code returned from Google login and submit it against the original loginId.
            onNodeWithTag(COMPUTER_RUNTIME_SIGN_IN_CODE_TAG, useUnmergedTree = true).performTextInput("4/0A-google-auth-code")
            onNodeWithText("Submit", useUnmergedTree = true).performClick()
            waitUntil(timeoutMillis = 5_000) { api.submittedCodes.isNotEmpty() }
            assertEquals(listOf(LOGIN_ID to "4/0A-google-auth-code"), api.submittedCodes)
            assertEquals(0, api.cancelledLoginIds.size)
        }

    private fun ComposeUiTest.setSheetHostContent(
        api: FakeAntigravitySignInApi,
        realtime: SharedRealtimeDomainEventOwner,
        authEvents: MutableSharedFlow<AuthSessionEvent>,
        readOwner: () -> SharedComputersOwner?,
        readServerId: () -> String?,
        openUrl: (String) -> Unit,
    ) {
        setContent {
            val store =
                remember {
                    SharedRuntimeAccountUsageStore(
                        client = RaftComputersApiClient(api),
                        currentIdentity = { readOwner() },
                        clock = { NOW_MILLIS },
                    )
                }
            val tracker = remember { MembersRuntimeAccountUsageOwnerTracker { readOwner() } }
            var serverId by remember { mutableStateOf(readServerId()) }
            LaunchedEffect(tracker) {
                suspend fun read() {
                    tracker.refresh()
                    serverId = readServerId()
                }
                read()
                authEvents.collect { event ->
                    applyShellRuntimeAccountUsageAuthEvent(
                        event = event,
                        clearImmediateAndRead = {
                            serverId = null
                            tracker.invalidate()
                            serverId = readServerId()
                        },
                        refreshInPlace = ::read,
                    )
                }
            }
            val owner by tracker.owner.collectAsState()
            val authHost = remember { ComputerRuntimeAuthHost(realtime = realtime, openUrl = openUrl) }
            val access =
                remember(store, owner, serverId, authHost) {
                    MembersRuntimeAccountUsageAccess(
                        store = store,
                        owner = owner,
                        serverId = serverId,
                        auth = authHost,
                    )
                }
            TestHostShell {
                ComputerRuntimeUsageSheetHost(
                    runtimeLabel = "Antigravity",
                    key = RuntimeAccountUsageKey(OWNER, SERVER_ID, MACHINE_ID, RuntimeAccountUsageProvider.Antigravity),
                    access = access,
                    runtimeVersion = "1.0.0",
                    runtimeId = "antigravity",
                    computerName = "Studio Mac",
                    onDismiss = {},
                )
            }
        }
    }

    @Composable
    private fun TestHostShell(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalActivity provides RaftPageActivity(livePager)) {
            RaftTheme(appearance = RaftAppearance.Light) {
                val capabilities = remember { RaftTimeCapabilities(localOffsetMinutes = 8 * 60, timeFormat = RaftTimeFormat24Hour) }
                val timeProvider = rememberLiveRaftTimeContext(capabilities = capabilities, clock = { NOW_MILLIS }, foundationOwner = fixedZone)
                CompositionLocalProvider(LocalRaftTimeContextProvider provides timeProvider) {
                    RaftComputersTheme { content() }
                }
            }
        }
    }

    private class FakeAntigravitySignInApi : RaftComputersApi {
        var authReads = 0
        var startCalls = 0
        val submittedCodes = mutableListOf<Pair<String, String>>()
        val cancelledLoginIds = mutableListOf<String>()

        override suspend fun runtimeAccountUsage(serverId: ServerId, machineId: String, provider: String): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = """{"state":"missing"}""")

        override suspend fun runtimeAuth(serverId: ServerId, machineId: String): HomeNetworkResponse {
            authReads += 1
            return HomeNetworkResponse(
                code = 200,
                bodyText =
                    """{"status":{"collectedAt":"2026-11-22T09:00:00.000Z","runtimes":{"antigravity":{"kind":"logged_out"}}},"availability":"ok","runtimes":["antigravity"],"canAct":true}""",
            )
        }

        override suspend fun startRuntimeLogin(serverId: ServerId, machineId: String, runtime: String, body: String): HomeNetworkResponse {
            startCalls += 1
            return HomeNetworkResponse(code = 200, bodyText = """{"loginId":"$LOGIN_ID"}""")
        }

        override suspend fun submitRuntimeLoginCode(
            serverId: ServerId,
            machineId: String,
            runtime: String,
            loginId: String,
            body: String,
        ): HomeNetworkResponse {
            val code = Regex("\"code\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.getOrNull(1).orEmpty()
            submittedCodes += loginId to code
            return HomeNetworkResponse(code = 200, bodyText = "{}")
        }

        override suspend fun cancelRuntimeLogin(serverId: ServerId, machineId: String, runtime: String, loginId: String): HomeNetworkResponse {
            cancelledLoginIds += loginId
            return HomeNetworkResponse(code = 200, bodyText = "{}")
        }

        override suspend fun machines(serverId: ServerId): HomeNetworkResponse = error("unused")
        override suspend fun registerMachine(serverId: ServerId, body: String): HomeNetworkResponse = error("unused")
        override suspend fun renameMachine(serverId: ServerId, machineId: String, body: String): HomeNetworkResponse = error("unused")
        override suspend fun deleteMachine(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
        override suspend fun rotateMachineKey(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
        override suspend fun machineWorkspaces(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
        override suspend fun runtimeModels(serverId: ServerId, machineId: String, runtime: String): HomeNetworkResponse = error("unused")
        override suspend fun deleteMachineWorkspace(serverId: ServerId, machineId: String, directoryName: String): HomeNetworkResponse =
            error("unused")
    }

    private companion object {
        const val NOW_MILLIS = 1_795_340_000_000L
        const val SERVER_ID = "server-1"
        const val MACHINE_ID = "m-1"
        const val LOGIN_ID = "rtl_antigravity123456"
        const val OAUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth?client_id=test"
        val OWNER = SharedComputersOwner(environmentId = "https://api.example", accountId = "user-1", sessionGeneration = "gen-1")
        val fixedZone =
            RaftDatetimeFoundationOwner(
                kuiklyBaseClock = RaftEpochMillisSource { NOW_MILLIS },
                kuiklyBaseOffset = RaftLocalOffsetMinutesSource { 8 * 60 },
            )

        fun testGrant(serverId: String) =
            RealtimeGrantRecord(
                slotKey = RealtimeSlotKey(scopeLeaseId = "lease-1", serverId = serverId),
                principalId = "user-1",
                environmentId = "https://api.example",
                authGeneration = 1L,
                connectionFamilyId = "family-1",
                connectionEpochId = "epoch-1",
            )
    }
}
