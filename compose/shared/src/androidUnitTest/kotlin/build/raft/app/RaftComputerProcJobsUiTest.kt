package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.data.AppLocale
import build.raft.app.data.ComputerProcJobSnapshot
import build.raft.app.data.ComputerProcJobState
import build.raft.app.data.ComputerProcJobStopOutcome
import build.raft.app.data.ComputerProcJobStopResult
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SettingsComputerAgentSnapshot
import build.raft.app.data.SettingsComputerDetailSnapshot
import build.raft.app.data.SettingsComputerSnapshot
import build.raft.app.data.stringDescLocaleType
import build.raft.app.model.RaftTimeContext
import build.raft.app.resources.StringDesc
import build.raft.designsystem.components.LocalRaftToastHostState
import build.raft.designsystem.components.RaftToastHost
import build.raft.designsystem.components.RaftToastHostState
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * UI tests for `raft proc` jobs on the Computer detail page (`botiverse/slock #15595`):
 * - jobs render only under agents that have them (never "no jobs");
 * - Stop appears only for running jobs when the viewer controls the Computer and it is online;
 * - Stop opens the warning confirmation dialog, cancels cleanly, and surfaces outcome toasts.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h2400dp")
class RaftComputerProcJobsUiTest {
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

    // 2026-11-21T01:00:00Z
    private val fixedNowMillis = 1_795_222_800_000L
    private val fixedTimeContext = RaftTimeContext(nowEpochMillis = fixedNowMillis, localOffsetMinutes = 0)

    private val runningJob =
        ComputerProcJobSnapshot(
            agentId = "agent-1",
            name = "dev-server",
            state = ComputerProcJobState.Running,
            startedAt = "2026-11-21T00:00:00Z",
            memoryMaxBytes = 4L * 1024L * 1024L * 1024L,
            rssBytes = 300L * 1024L * 1024L
        )

    private val exitedJob =
        ComputerProcJobSnapshot(
            agentId = "agent-1",
            name = "watcher",
            state = ComputerProcJobState.Exited,
            startedAt = "2026-11-20T22:00:00Z",
            exitedAt = "2026-11-21T00:55:00Z",
            exitReason = "owner_stop",
            stoppedBy = "tygg"
        )

    private fun detailSnapshot(
        online: Boolean = true,
        canEditMachine: Boolean = true
    ): SettingsComputerDetailSnapshot =
        SettingsComputerDetailSnapshot(
            computer =
                SettingsComputerSnapshot(
                    id = "m-1",
                    name = "Studio",
                    daemonVersion = "",
                    computerVersion = "1.0.55",
                    isComputer = true,
                    online = online,
                    canManage = canEditMachine
                ),
            workspaces = emptyList(),
            agents =
                listOf(
                    SettingsComputerAgentSnapshot(id = "agent-1", name = "Coder", runtime = "claude"),
                    SettingsComputerAgentSnapshot(id = "agent-2", name = "Idle", runtime = "codex")
                ),
            sections = emptyList(),
            canManageMachines = canEditMachine,
            canEditMachine = canEditMachine,
            canRemoveMachine = canEditMachine,
            currentUserId = "user-1"
        )

    @Test
    fun detailPageShowsJobsOnlyForAgentsWithJobsAndHidesStopForReadOnlyOrOffline() = runRobolectricComposeUiTest {
        val readOnlyModel =
            raftComputerDetailModel(
                ScreenLoadState.Content(detailSnapshot(online = true, canEditMachine = false)),
                machineId = "m-1",
                targetServerId = null,
                currentServerId = "server-1"
            )
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                RaftComputersTheme {
                    RaftSettingsComputerDetailContent(
                        model = readOnlyModel,
                        actionState = null,
                        onRetry = {},
                        onIntent = {},
                        onOpenAgent = {},
                        procJobsByAgent = mapOf("agent-1" to listOf(runningJob, exitedJob)),
                        onStopProcJob = { ComputerProcJobStopResult.Completed(ComputerProcJobStopOutcome.Stopped) }
                    )
                }
            }
        }
        waitForIdle()
        onAllNodesWithTag(COMPUTER_PROC_JOBS_SECTION_TEST_TAG).assertCountEquals(1)
        onNodeWithText("Jobs").assertIsDisplayed()
        onNodeWithText("dev-server").assertIsDisplayed()
        onNodeWithText("watcher").assertIsDisplayed()
        onAllNodesWithTag(computerProcJobStopButtonTestTag("dev-server")).assertCountEquals(0)
        onAllNodesWithTag(computerProcJobStopButtonTestTag("watcher")).assertCountEquals(0)
    }

    @Test
    fun detailPageOffersStopOnlyForRunningJobsWhenOnlineController() = runRobolectricComposeUiTest {
        val onlineControllerModel =
            raftComputerDetailModel(
                ScreenLoadState.Content(detailSnapshot(online = true, canEditMachine = true)),
                machineId = "m-1",
                targetServerId = null,
                currentServerId = "server-1"
            )
        val offlineControllerModel =
            raftComputerDetailModel(
                ScreenLoadState.Content(detailSnapshot(online = false, canEditMachine = true)),
                machineId = "m-1",
                targetServerId = null,
                currentServerId = "server-1"
            )

        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                RaftComputersTheme {
                    RaftSettingsComputerDetailContent(
                        model = offlineControllerModel,
                        actionState = null,
                        onRetry = {},
                        onIntent = {},
                        onOpenAgent = {},
                        procJobsByAgent = mapOf("agent-1" to listOf(runningJob, exitedJob)),
                        onStopProcJob = { ComputerProcJobStopResult.Completed(ComputerProcJobStopOutcome.Stopped) }
                    )
                }
            }
        }
        waitForIdle()
        onAllNodesWithTag(computerProcJobStopButtonTestTag("dev-server")).assertCountEquals(0)

        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                RaftComputersTheme {
                    RaftSettingsComputerDetailContent(
                        model = onlineControllerModel,
                        actionState = null,
                        onRetry = {},
                        onIntent = {},
                        onOpenAgent = {},
                        procJobsByAgent = mapOf("agent-1" to listOf(runningJob, exitedJob)),
                        onStopProcJob = { ComputerProcJobStopResult.Completed(ComputerProcJobStopOutcome.Stopped) }
                    )
                }
            }
        }
        waitForIdle()
        onNodeWithTag(computerProcJobStopButtonTestTag("dev-server")).assertIsDisplayed()
        onAllNodesWithTag(computerProcJobStopButtonTestTag("watcher")).assertCountEquals(0)
    }

    @Test
    fun stopConfirmationDialogCancelsConfirmsAndShowsToasts() = runRobolectricComposeUiTest {
        val toastHost = RaftToastHostState(limit = 3)
        val stoppedJobs = mutableListOf<String>()
        var nextResult: ComputerProcJobStopResult = ComputerProcJobStopResult.Completed(ComputerProcJobStopOutcome.Stopped)

        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                CompositionLocalProvider(LocalRaftToastHostState provides toastHost) {
                    Box(Modifier.fillMaxSize()) {
                        RaftComputerAgentProcJobs(
                            agentName = "Coder",
                            jobs = listOf(runningJob, exitedJob),
                            canStop = true,
                            onStop = { job ->
                                stoppedJobs += job.name
                                nextResult
                            },
                            timeContext = fixedTimeContext,
                            nowMillis = fixedNowMillis
                        )
                        RaftToastHost(toastHost)
                    }
                }
            }
        }
        waitForIdle()

        // Running job shows uptime; exited job shows who stopped it + relative time.
        onNodeWithText("Running 1h 0m").assertIsDisplayed()
        onNodeWithText("Stopped by @tygg · 5m ago").assertIsDisplayed()

        // Cancel closes dialog without calling onStop.
        onNodeWithTag(computerProcJobStopButtonTestTag("dev-server")).performClick()
        waitForIdle()
        onNodeWithTag(COMPUTER_PROC_JOBS_STOP_DIALOG_TEST_TAG).assertIsDisplayed()
        onNodeWithText("Stop dev-server?").assertIsDisplayed()
        onNodeWithText(
            "Coder started this job. It gets 10 seconds to exit before it is killed, and it is not restarted. Coder is told you stopped it."
        ).assertIsDisplayed()
        onNodeWithText("Cancel").performClick()
        waitForIdle()
        onAllNodesWithTag(COMPUTER_PROC_JOBS_STOP_DIALOG_TEST_TAG).assertCountEquals(0)
        assertEquals(emptyList(), stoppedJobs)

        // Already exited -> neutral toast.
        nextResult = ComputerProcJobStopResult.Completed(ComputerProcJobStopOutcome.AlreadyExited)
        onNodeWithTag(computerProcJobStopButtonTestTag("dev-server")).performClick()
        waitForIdle()
        onNodeWithTag(COMPUTER_PROC_JOBS_STOP_CONFIRM_TEST_TAG).performClick()
        waitForIdle()
        onAllNodesWithTag(COMPUTER_PROC_JOBS_STOP_DIALOG_TEST_TAG).assertCountEquals(0)
        onNodeWithText("dev-server had already ended.").assertIsDisplayed()

        // Upgrade required -> warning toast.
        nextResult = ComputerProcJobStopResult.UpgradeRequired
        onNodeWithTag(computerProcJobStopButtonTestTag("dev-server")).performClick()
        waitForIdle()
        onNodeWithTag(COMPUTER_PROC_JOBS_STOP_CONFIRM_TEST_TAG).performClick()
        waitForIdle()
        onNodeWithText("Update this Computer to stop its jobs from here.").assertIsDisplayed()

        // Failed -> warning toast.
        nextResult = ComputerProcJobStopResult.Completed(ComputerProcJobStopOutcome.Failed)
        onNodeWithTag(computerProcJobStopButtonTestTag("dev-server")).performClick()
        waitForIdle()
        onNodeWithTag(COMPUTER_PROC_JOBS_STOP_CONFIRM_TEST_TAG).performClick()
        waitForIdle()
        onNodeWithText("Couldn’t stop dev-server. Try again.").assertIsDisplayed()

        assertEquals(listOf("dev-server", "dev-server", "dev-server"), stoppedJobs)
    }
}
