package build.raft.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.data.BundledRuntimeCatalog
import build.raft.app.data.ChannelSlackBridgeOption
import build.raft.app.data.ChannelSlackBridgeState
import build.raft.app.data.ComputerRuntimeRescanResult
import build.raft.app.data.RuntimeAccountUsageAccount
import build.raft.app.data.RuntimeAccountUsageHealth
import build.raft.app.data.RuntimeAccountUsageLoad
import build.raft.app.data.RuntimeAccountUsageProvider
import build.raft.app.data.RuntimeAccountUsageRead
import build.raft.app.data.RuntimeAccountUsageSnapshot
import build.raft.app.data.SettingsComputerDetailSnapshot
import build.raft.app.data.SettingsComputerRuntimeDetection
import build.raft.app.data.SettingsComputerRuntimeDetectionReason
import build.raft.app.data.SettingsComputerRuntimeDetectionStatus
import build.raft.app.data.SettingsComputerSnapshot
import build.raft.app.data.SettingsComputerWorkspaceSnapshot
import build.raft.app.data.SettingsSectionErrorSnapshot
import build.raft.app.data.SlackBridgeReadFloor
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.raftEpochDayForDate
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric Compose UI tests for `#wg-mobile-sync:#282` (1.30 web-parity polish bundle):
 * - slock `#16477`: `ComputerRuntimeDetectionLine` pending / incomplete / Retry / forbidden / complete
 * - slock `#16501`: `ComputerRuntimeUsageSheetSurface` Check again gating, Retry on timeout/failure, offline
 * - slock `#16502`: `RaftComputerWorkspacesSection` desktop Refresh icon vs phone pull hint & Retry banner over kept list
 * - slock `#16506`: `ChannelSlackBridgePicker` reading row on open & warning Retry banner on failure
 * - slock `#16350`: `ChannelActionsSection` Leave channel row renders cleanly with `DoorOpen`
 * - slock `#16108`: `RaftActionCardView` renders without `"Runs on"` server notice
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h1600dp")
class WebParityPolishBundle130UiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val fixedTimeContext =
        RaftTimeContext(
            localOffsetMinutes = 0,
            todayEpochDay = raftEpochDayForDate(2026, 11, 24),
            // 2026-11-24T10:05:00Z
            nowEpochMillis = 1_795_514_700_000L,
        )

    // --- #16477: Runtime detection line ---------------------------------------------------------

    @Test
    fun runtimeDetectionLineShowsIncompleteRetryStaysDetectingUntilComputerUpdates() = runRobolectricComposeUiTest {
        var detection by mutableStateOf<SettingsComputerRuntimeDetection?>(
            SettingsComputerRuntimeDetection(
                status = SettingsComputerRuntimeDetectionStatus.Incomplete,
                unfinishedRuntimes = listOf("codex"),
                reason = SettingsComputerRuntimeDetectionReason.Deadline,
                updatedAtMs = 100L,
            )
        )
        var rescanCount = 0
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                RaftComputersTheme {
                    ComputerRuntimeDetectionLine(
                        machineId = "m-1",
                        online = true,
                        detection = detection,
                        canRescan = true,
                        runtimeCatalog = BundledRuntimeCatalog,
                        onRescan = {
                            rescanCount += 1
                            ComputerRuntimeRescanResult.Accepted
                        },
                    )
                }
            }
        }
        waitForIdle()
        onNodeWithTag(COMPUTER_RUNTIME_DETECTION_TAG).assertIsDisplayed()
        onNodeWithText("Runtime detection did not finish (timed out). Not checked: Codex CLI. Their last known state is shown.").assertIsDisplayed()
        onNodeWithTag(COMPUTER_RUNTIME_DETECTION_RETRY_TAG).performClick()
        waitForIdle()
        assertEquals(1, rescanCount)
        onNodeWithText("Detecting runtimes… the list below is the last confirmed one.").assertIsDisplayed()
        onAllNodesWithTag(COMPUTER_RUNTIME_DETECTION_RETRY_TAG).assertCountEquals(0)

        // Computer finishes detection -> line disappears.
        detection =
            SettingsComputerRuntimeDetection(
                status = SettingsComputerRuntimeDetectionStatus.Complete,
                unfinishedRuntimes = emptyList(),
                updatedAtMs = 200L,
            )
        waitForIdle()
        onAllNodesWithTag(COMPUTER_RUNTIME_DETECTION_TAG).assertCountEquals(0)
    }

    @Test
    fun runtimeDetectionLineShowsPermissionHintWhenViewerCannotRescan() = runRobolectricComposeUiTest {
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                RaftComputersTheme {
                    ComputerRuntimeDetectionLine(
                        machineId = "m-1",
                        online = true,
                        detection =
                            SettingsComputerRuntimeDetection(
                                status = SettingsComputerRuntimeDetectionStatus.Incomplete,
                                unfinishedRuntimes = emptyList(),
                                reason = SettingsComputerRuntimeDetectionReason.Cancelled,
                                updatedAtMs = 100L,
                            ),
                        canRescan = false,
                        runtimeCatalog = BundledRuntimeCatalog,
                        onRescan = { ComputerRuntimeRescanResult.Accepted },
                    )
                }
            }
        }
        waitForIdle()
        onNodeWithTag(COMPUTER_RUNTIME_DETECTION_HINT_TAG).assertIsDisplayed()
        onAllNodesWithTag(COMPUTER_RUNTIME_DETECTION_RETRY_TAG).assertCountEquals(0)
    }

    // --- #16501: Runtime usage Check again / Retry / Offline ------------------------------------

    @Test
    fun runtimeUsageCardHidesCheckAgainWhenFreshShowsCheckAgainWhenDueAndShowsOutcomeInline() = runRobolectricComposeUiTest {
        val snapshot =
            RuntimeAccountUsageSnapshot(
                provider = "claude",
                collectedAt = "2026-11-24T10:04:00.000Z",
                accounts = listOf(RuntimeAccountUsageAccount("a1", "Max", null, RuntimeAccountUsageHealth.Ok, emptyList())),
            )
        val loaded = RuntimeAccountUsageLoad.Loaded(RuntimeAccountUsageRead.Available(stale = false, snapshot = snapshot))
        var sheetState by mutableStateOf(
            ComputerRuntimeUsageSheetState(
                load = loaded,
                loading = false,
                checkState = ComputerRuntimeUsageCheckState.Idle,
                checkDue = false,
            )
        )
        setContent {
            CompositionLocalProvider(LocalRaftTimeContextProvider provides RaftTimeContextProvider(fixedTimeContext)) {
                RaftTheme(appearance = RaftAppearance.Light) {
                    RaftComputersTheme {
                        ComputerRuntimeUsageSheetSurface(
                            runtimeLabel = "Claude Code",
                            state = sheetState,
                            timeContext = fixedTimeContext,
                        )
                    }
                }
            }
        }
        waitForIdle()
        // Fresh (< 2 min): Check again is hidden.
        onAllNodesWithTag(COMPUTER_RUNTIME_USAGE_REFRESH_TAG).assertCountEquals(0)

        // Due (>= 2 min): Check again appears.
        sheetState = sheetState.copy(checkDue = true)
        waitForIdle()
        onNodeWithTag(COMPUTER_RUNTIME_USAGE_REFRESH_TAG).assertIsDisplayed()
        onNodeWithText("Check again").assertIsDisplayed()

        // Timeout: Retry appears and "This check timed out" sits on the Updated line.
        sheetState = sheetState.copy(checkState = ComputerRuntimeUsageCheckState.Timeout)
        waitForIdle()
        onNodeWithText("Retry").assertIsDisplayed()
        onNode(hasText("This check timed out", substring = true)).assertIsDisplayed()

        // Offline: Check button hidden and "Computer offline" sits on the Updated line.
        sheetState = sheetState.copy(checkState = ComputerRuntimeUsageCheckState.ComputerOffline)
        waitForIdle()
        onAllNodesWithTag(COMPUTER_RUNTIME_USAGE_REFRESH_TAG).assertCountEquals(0)
        onNode(hasText("Computer offline", substring = true)).assertIsDisplayed()
    }

    // --- #16502: Agent workspaces Refresh icon / Phone pull / Retry banner over kept list -------

    @Test
    fun workspacesSectionUsesRefreshIconOnWidePullHintOnPhoneAndShowsRetryBannerOverKeptList() = runRobolectricComposeUiTest {
        val baseSnapshot =
            SettingsComputerDetailSnapshot(
                computer = SettingsComputerSnapshot(id = "m-1", name = "Studio", daemonVersion = "", online = true),
                workspaces = emptyList(),
                agents = emptyList(),
                sections = emptyList(),
                workspacesScanned = false,
            )
        var snapshot by mutableStateOf(baseSnapshot)
        var phonePull by mutableStateOf(false)
        var scans = 0

        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                RaftComputersTheme {
                    RaftComputerWorkspacesSection(
                        snapshot = snapshot,
                        scanning = false,
                        onScan = { scans += 1 },
                        phonePullToRefresh = phonePull,
                    )
                }
            }
        }
        waitForIdle()
        // Desktop / wide: Refresh icon button visible + desktop hint.
        onNodeWithTag(COMPUTER_WORKSPACES_REFRESH_TAG).assertIsDisplayed()
        onNodeWithText("Select Refresh at the top right to read the workspace directories on this computer.").assertIsDisplayed()
        onNodeWithTag(COMPUTER_WORKSPACES_REFRESH_TAG).performClick()
        waitForIdle()
        assertEquals(1, scans)

        // Phone: no Refresh icon button + pull-down hint.
        phonePull = true
        waitForIdle()
        onAllNodesWithTag(COMPUTER_WORKSPACES_REFRESH_TAG).assertCountEquals(0)
        onNodeWithText("Pull down to read the workspace directories on this computer.").assertIsDisplayed()

        // Re-scan failure after an earlier scan: warning banner with Retry sits ABOVE the kept workspace list!
        snapshot =
            baseSnapshot.copy(
                workspaces =
                    listOf(
                        SettingsComputerWorkspaceSnapshot(
                            id = "ws-1",
                            name = "Raft",
                            path = "~/.slock/agents/Raft/",
                            statusLabel = "active",
                        )
                    ),
                workspacesScanned = true,
                workspacesError = SettingsSectionErrorSnapshot(code = "scan_failed", retryable = true),
            )
        waitForIdle()
        onNodeWithTag(COMPUTER_WORKSPACES_ERROR_TAG).assertIsDisplayed()
        onNodeWithText("Couldn't read the workspaces on this computer.").assertIsDisplayed()
        onNodeWithText("Raft").assertIsDisplayed()
        onNodeWithTag(COMPUTER_WORKSPACES_RETRY_TAG).performClick()
        waitForIdle()
        assertEquals(2, scans)
    }

    // --- #16506: Slack channel picker reads on open & shows Retry banner on failure -------------

    @Test
    fun slackBridgePickerReadsOnOpenShowsReadingRowAndShowsRetryBannerOnFailure() = runRobolectricComposeUiTest {
        SlackBridgeReadFloor.resetForTest()
        val firstGate = CompletableDeferred<ChannelSlackBridgeReadOutcome>()
        var attempts = 0
        val slackState =
            ChannelSlackBridgeState(
                options = listOf(ChannelSlackBridgeOption("C1", "general", membershipRequired = false, pairedElsewhere = false)),
                pairedSlackChannelId = null,
            )
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                ChannelSlackBridgePicker(
                    state = slackState,
                    selected = null,
                    enabled = true,
                    onSelect = {},
                    label = "Slack",
                    onRefresh = {
                        attempts += 1
                        if (attempts == 1) firstGate.await() else ChannelSlackBridgeReadOutcome.Success
                    },
                )
            }
        }
        waitForIdle()
        // Open menu -> triggers read and shows "Reading Slack channels…" at top of menu.
        onNodeWithTag(CHANNEL_SLACK_TRIGGER_TAG).performClick()
        waitForIdle()
        assertEquals(1, attempts)
        onNodeWithTag(CHANNEL_SLACK_READING_TAG).assertIsDisplayed()
        onNodeWithText("Reading Slack channels…").assertIsDisplayed()

        // First read fails -> warning banner with Retry appears under the field.
        firstGate.complete(ChannelSlackBridgeReadOutcome.Failed)
        waitForIdle()
        onNodeWithTag(CHANNEL_SLACK_READ_FAILED_TAG).assertIsDisplayed()
        onNodeWithText("Couldn't read Slack channels.").assertIsDisplayed()
        onNodeWithTag(CHANNEL_SLACK_READ_RETRY_TAG).assertIsDisplayed()

        // Retry succeeds (no floor noted in fake) -> banner clears.
        onNodeWithTag(CHANNEL_SLACK_READ_RETRY_TAG).performClick()
        waitForIdle()
        assertEquals(2, attempts)
        onAllNodesWithTag(CHANNEL_SLACK_READ_FAILED_TAG).assertCountEquals(0)
        SlackBridgeReadFloor.resetForTest()
    }

    // --- #16350: Leave channel action renders with DoorOpen -------------------------------------

    @Test
    fun channelActionsLeaveRowRendersWithDoorOpen() = runRobolectricComposeUiTest {
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                RaftComputersTheme {
                    ChannelActionsSection(
                        actions = listOf(ChannelSettingsActionRow.Leave),
                        enabled = true,
                        actionInFlight = null,
                        callbacks = ChannelSettingsCallbacks(),
                    )
                }
            }
        }
        waitForIdle()
        onNodeWithText("Leave channel").assertIsDisplayed()
    }

    // --- #16108: Action card drops "Runs on <server>" notice ------------------------------------

    @Test
    fun actionCardDoesNotRenderRunsOnServerNotice() = runRobolectricComposeUiTest {
        val card = ActionCardSpecFixtures.card("icon_shield")
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                RaftActionCardView(
                    card = card,
                    state = RaftActionCardFrameState(status = RaftActionCardStatus.Prepared),
                    entityName = { _, _ -> null },
                    onAction = {},
                )
            }
        }
        waitForIdle()
        assertEquals(0, onAllNodes(hasText("Runs on", substring = true)).fetchSemanticsNodes().size)
    }
}
