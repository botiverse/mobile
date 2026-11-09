package build.raft.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import build.raft.app.base.BridgeModule
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.IModuleCreator
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.PagerManager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.data.AppLocale
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.RaftComputersApi
import build.raft.app.data.RaftComputersApiClient
import build.raft.app.data.RaftDatetimeFoundationOwner
import build.raft.app.data.RaftEpochMillisSource
import build.raft.app.data.RaftLocalOffsetMinutesSource
import build.raft.app.data.RuntimeAccountUsageKey
import build.raft.app.data.RuntimeAccountUsageProvider
import build.raft.app.data.SharedComputersOwner
import build.raft.app.data.SharedRuntimeAccountUsageStore
import build.raft.app.data.stringDescLocaleType
import build.raft.app.model.RaftTimeFormat24Hour
import build.raft.app.model.ServerId
import build.raft.app.resources.StringDesc
import build.raft.designsystem.theme.RaftAppearance
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
import org.robolectric.annotation.GraphicsMode

/**
 * slock #13504 on the Computers page's Codex usage sheet: "N resets left" beside the account,
 * "Use a reset" for a person who may act, its confirm naming the account, and the card's note and
 * fresh count after the reset. A viewer who may not act sees the count and no action.
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h900dp")
class ComputerRuntimeLimitResetUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    private val livePager =
        object : Pager() {}.apply {
            pagerId = "computer-runtime-limit-reset"
            pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
            registerModule(BridgeModule.MODULE_NAME, IModuleCreator { BridgeModule() })
        }

    @BeforeTest
    fun setUp() {
        previousLocale = StringDesc.localeType
        PagerManager.register(livePager)
    }

    @AfterTest
    fun tearDown() {
        StringDesc.localeType = previousLocale
        PagerManager.unregister(livePager)
    }

    @Test
    fun personWhoMayActUsesAResetAndSeesTheFreshCount() = runRobolectricComposeUiTest {
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
        val api = CodexLimitResetApi(canAct = true)
        showSheet(api)
        assertShown("2 resets left")
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(COMPUTER_RUNTIME_LIMIT_RESET_ACTION_TAG, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithTag(COMPUTER_RUNTIME_LIMIT_RESET_ACTION_TAG, useUnmergedTree = true).onFirst().performClick()
        assertShown("Use a limit reset?")
        assertShown("This uses one of tea****r@example.com's resets to restore its Codex usage limits now. 2 resets left.")
        onAllNodesWithTag(COMPUTER_RUNTIME_LIMIT_RESET_CONFIRM_TAG, useUnmergedTree = true).onFirst().performClick()
        assertShown("Limits reset.")
        assertShown("1 reset left")
        onAllNodesWithText("Use a limit reset?", useUnmergedTree = true).assertCountEquals(0)
        assertEquals(1, api.redeemBodies.size)
        assertTrue(api.redeemBodies.single().startsWith("{\"idempotencyKey\":\""), api.redeemBodies.single())
    }

    @Test
    fun anOutcomeThePersonMustActOnKeepsTheDialogOpenInChinese() = runRobolectricComposeUiTest {
        StringDesc.localeType = AppLocale.SimplifiedChinese.stringDescLocaleType
        showSheet(CodexLimitResetApi(canAct = true, redeemResult = """{"result":{"kind":"reauth_required","reason":"credentials_rejected"},"snapshot":null}"""))
        assertShown("剩余 2 次重置")
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(COMPUTER_RUNTIME_LIMIT_RESET_ACTION_TAG, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithTag(COMPUTER_RUNTIME_LIMIT_RESET_ACTION_TAG, useUnmergedTree = true).onFirst().performClick()
        assertShown("使用一次额度重置？")
        onAllNodesWithTag(COMPUTER_RUNTIME_LIMIT_RESET_CONFIRM_TAG, useUnmergedTree = true).onFirst().performClick()
        assertShown("请在这台 Computer 上重新登录 Codex。未使用重置。")
        assertShown("使用一次额度重置？")
    }

    @Test
    fun viewerWhoMayNotActSeesTheCountButNoAction() = runRobolectricComposeUiTest {
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
        val api = CodexLimitResetApi(canAct = false)
        showSheet(api)
        assertShown("2 resets left")
        waitUntil(timeoutMillis = 5_000) { api.authReads > 0 }
        waitForIdle()
        onAllNodesWithTag(COMPUTER_RUNTIME_LIMIT_RESET_ACTION_TAG, useUnmergedTree = true).assertCountEquals(0)
        onAllNodesWithText("Use a reset", useUnmergedTree = true).assertCountEquals(0)
    }

    private fun ComposeUiTest.assertShown(text: String) {
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeUiTest.showSheet(api: CodexLimitResetApi) {
        setContent {
            val access =
                remember {
                    MembersRuntimeAccountUsageAccess(
                        store =
                            SharedRuntimeAccountUsageStore(
                                client = RaftComputersApiClient(api),
                                currentIdentity = { owner },
                                clock = { NOW_MILLIS },
                            ),
                        owner = owner,
                        serverId = "server-1",
                    )
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(livePager)) {
                RaftTheme(appearance = RaftAppearance.Light) {
                    val capabilities = remember { RaftTimeCapabilities(localOffsetMinutes = 8 * 60, timeFormat = RaftTimeFormat24Hour) }
                    ProvideStandaloneRaftTimeContext(clock = { NOW_MILLIS }, foundationOwner = fixedZone, capabilities = capabilities) {
                        RaftComputersTheme {
                            ComputerRuntimeUsageSheetHost(
                                runtimeLabel = "Codex",
                                key = RuntimeAccountUsageKey(owner, "server-1", "m-1", RuntimeAccountUsageProvider.Codex),
                                access = access,
                                runtimeVersion = "0.95.0",
                                onDismiss = {},
                            )
                        }
                    }
                }
            }
        }
    }
}

// 2026-11-07T10:10:00Z
private const val NOW_MILLIS = 1_794_046_200_000L

private val owner = SharedComputersOwner(environmentId = "https://api.example", accountId = "user-1", sessionGeneration = "gen-1")

private val fixedZone =
    RaftDatetimeFoundationOwner(
        usesKuiklyBase = true,
        kuiklyBaseClock = RaftEpochMillisSource { NOW_MILLIS },
        legacyClock = RaftEpochMillisSource { NOW_MILLIS },
        kuiklyBaseOffset = RaftLocalOffsetMinutesSource { 8 * 60 },
    )

// slock a6885e181 runtimeLimitReset.api.test.ts `snapshot(resets)`, with one rate-limited window.
private fun codexSnapshot(resets: Int): String =
    """{"protocolVersion":2,"provider":"codex","collectedAt":"2026-11-07T10:00:00.000Z","staleAfter":"2026-11-07T10:30:00.000Z","collectorVersion":"test","accounts":[{"accountKey":"${"a".repeat(64)}","maskedLabel":"tea****r@example.com","planLabel":"Pro","health":"rate_limited","limitResetsAvailable":$resets,"windows":[{"id":"five_hour","label":"5-hour limit","status":"limit_reached","usedRatio":1,"resetsAt":"2026-11-07T12:00:00.000Z"}]}]}"""

private class CodexLimitResetApi(
    private val canAct: Boolean,
    private val redeemResult: String = """{"result":{"kind":"reset"},"snapshot":${codexSnapshot(1).replace("\"rate_limited\"", "\"ok\"").replace("\"limit_reached\",\"usedRatio\":1", "\"ok\",\"usedRatio\":0")}}""",
) : RaftComputersApi {
    val redeemBodies = mutableListOf<String>()
    var authReads = 0

    override suspend fun runtimeAccountUsage(serverId: ServerId, machineId: String, provider: String): HomeNetworkResponse =
        HomeNetworkResponse(code = 200, bodyText = """{"state":"fresh","snapshot":${codexSnapshot(2)}}""")

    override suspend fun runtimeAuth(serverId: ServerId, machineId: String): HomeNetworkResponse {
        authReads += 1
        return HomeNetworkResponse(
            code = 200,
            bodyText = """{"status":{"collectedAt":"2026-11-07T10:00:00.000Z","runtimes":{"codex":{"kind":"logged_in"}}},"availability":"ok","runtimes":["codex"],"canAct":$canAct}""",
        )
    }

    override suspend fun redeemRuntimeLimitReset(serverId: ServerId, machineId: String, provider: String, body: String): HomeNetworkResponse {
        redeemBodies += body
        return HomeNetworkResponse(code = 200, bodyText = redeemResult)
    }

    override suspend fun refreshRuntimeAccountUsage(
        serverId: ServerId,
        machineId: String,
        provider: String,
        body: String,
        priority: DataRequestPriority,
    ): HomeNetworkResponse = HomeNetworkResponse(code = 202, bodyText = """{"accepted":false,"state":"computer_offline"}""")

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
