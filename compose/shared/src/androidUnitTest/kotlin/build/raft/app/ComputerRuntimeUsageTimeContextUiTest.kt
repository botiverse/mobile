package build.raft.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
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
import build.raft.app.model.ServerId
import build.raft.app.model.RaftTimeFormat24Hour
import build.raft.app.resources.StringDesc
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
 * xxchan (iOS, Computers page -> "Claude Code usage"): every window read "Used up · resets unknown
 * time" and the footer "Account-wide · updated unknown time", because the standalone Computers page
 * provided no [LocalRaftTimeContextProvider] and the relative-time helper answers Unknown without
 * one. The page now wraps itself in [ProvideStandaloneRaftTimeContext]; this renders the page's
 * sheet host inside that same scope (fixed clock and zone) and checks the web's labels
 * (`machine.runtimeUsage.windowUsedUp` / `.metadata` / `.version` with `formatRelativeTime`).
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h900dp")
class ComputerRuntimeUsageTimeContextUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    private val livePager =
        object : Pager() {}.apply {
            pagerId = "computer-runtime-usage-time-context"
            pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
            // The live time context logs day / offset changes through the page's bridge.
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
    fun pageScopeResolvesResetAndUpdatedTimesInEnglish() = runRobolectricComposeUiTest {
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
        showSheet(runtimeVersion = "2.1.304 (Claude Code)")
        // 2026-10-23T04:10Z is tomorrow at 12:10 in UTC+8 (now: 2026-10-22 10:10 local).
        assertShown("Used up · resets tomorrow")
        // 2026-10-22T05:10Z: three hours from now.
        assertShown("62% used · resets in 3 hours")
        // collectedAt 02:04Z, six minutes before now.
        assertShown("Account-wide · updated 6 minutes ago")
        assertShown("Version 2.1.304 (Claude Code)")
        onAllNodesWithText("unknown time", substring = true, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun pageScopeResolvesResetAndUpdatedTimesInChinese() = runRobolectricComposeUiTest {
        StringDesc.localeType = AppLocale.SimplifiedChinese.stringDescLocaleType
        showSheet(runtimeVersion = null)
        assertShown("已用完 · 明天重置")
        assertShown("整个账户 · 6 分钟前更新")
        assertShown("版本暂不可用")
        assertShown("拥有 Computer 编辑权限或挂载这台 Computer 的人可见")
        onAllNodesWithText("未知时间", substring = true, useUnmergedTree = true).assertCountEquals(0)
    }

    private fun ComposeUiTest.assertShown(text: String) {
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText(text, useUnmergedTree = true).assertExists()
    }

    private fun ComposeUiTest.showSheet(runtimeVersion: String?) {
        setContent {
            val access =
                remember {
                    MembersRuntimeAccountUsageAccess(
                        store =
                            SharedRuntimeAccountUsageStore(
                                client = RaftComputersApiClient(ClaudeUsageApi()),
                                currentIdentity = { owner },
                                clock = { NOW_MILLIS },
                            ),
                        owner = owner,
                        serverId = "server-1",
                    )
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(livePager)) {
                RaftTheme(appearance = RaftAppearance.Light) {
                    ProvideStandaloneRaftTimeContext(
                        clock = { NOW_MILLIS },
                        foundationOwner = fixedZone,
                        capabilities = RaftTimeCapabilities(localOffsetMinutes = 8 * 60, timeFormat = RaftTimeFormat24Hour),
                    ) {
                        RaftComputersTheme {
                            ComputerRuntimeUsageSheetHost(
                                runtimeLabel = "Claude Code",
                                key = RuntimeAccountUsageKey(owner, "server-1", "m-1", RuntimeAccountUsageProvider.Claude),
                                access = access,
                                runtimeVersion = runtimeVersion,
                                onDismiss = {},
                            )
                        }
                    }
                }
            }
        }
    }
}

// 2026-10-22T02:10:00Z
private const val NOW_MILLIS = 1_792_635_000_000L

private val owner = SharedComputersOwner(environmentId = "https://api.example", accountId = "user-1", sessionGeneration = "gen-1")

/** The device zone fixed at UTC+8 (the reporter's), whatever the CI host's zone is. */
private val fixedZone =
    RaftDatetimeFoundationOwner(
        usesKuiklyBase = true,
        kuiklyBaseClock = RaftEpochMillisSource { NOW_MILLIS },
        legacyClock = RaftEpochMillisSource { NOW_MILLIS },
        kuiklyBaseOffset = RaftLocalOffsetMinutesSource { 8 * 60 },
    )

// slock f9d094481 packages/shared/src/runtimeAccountUsage.ts (protocol 2) read envelope:
// `collectedAt` / `resetsAt` are ISO instants (isoInstantSchema).
private const val CLAUDE_USAGE =
    """{"state":"fresh","snapshot":{"protocolVersion":2,"provider":"claude","collectedAt":"2026-10-22T02:04:00.000Z","staleAfter":"2026-10-22T02:34:00.000Z","accounts":[{"accountKey":"acct-1","planLabel":"Max","health":"rate_limited","windows":[{"id":"five_hour","label":"5-hour limit","status":"ok","usedRatio":0.62,"resetsAt":"2026-10-22T05:10:00.000Z"},{"id":"seven_day_opus","label":"Opus weekly","status":"limit_reached","usedRatio":1,"resetsAt":"2026-10-23T04:10:00.000Z"}]}]}}"""

private class ClaudeUsageApi : RaftComputersApi {
    override suspend fun runtimeAccountUsage(serverId: ServerId, machineId: String, provider: String): HomeNetworkResponse =
        HomeNetworkResponse(code = 200, bodyText = CLAUDE_USAGE)

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
