package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import build.raft.app.data.AgentMigrationActionError
import build.raft.app.data.AgentMigrationDataSource
import build.raft.app.data.AgentMigrationIntent
import build.raft.app.data.AgentMigrationNotice
import build.raft.app.data.AgentMigrationStartResult
import build.raft.app.data.AgentMigrationStatusSnapshot
import build.raft.app.data.AgentMigrationStore
import build.raft.app.data.AgentMigrationTarget
import build.raft.app.data.AppLocale
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.MigrationErrorContext
import build.raft.app.data.RaftBillingPlanSnapshot
import build.raft.app.data.RaftProGateDecision
import build.raft.app.data.migrationLeaveOutCandidates
import build.raft.app.data.stringDescLocaleType
import build.raft.app.model.MemberId
import build.raft.app.resources.StringDesc
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric Compose UI tests for `#wg-mobile-sync:#342` (`botiverse/slock` #17048 / #17131 / #17027):
 * - Retry leave-out picker shows measured `topEntries` checkboxes (filtering `MEMORY.md` & `other/`)
 *   plus custom path input when retrying after `MIGRATION_OBJECT_STORE_BUNDLE_TOO_LARGE`.
 * - Custom paths validate against `MEMORY.md`/`notes/`/`.raft/` (`alwaysMoved`) and malformed paths
 *   (`invalid`), support both Add button and IME Done, and survive switching to Cloud and back.
 * - Switching target to Cloud hides the picker and omits `leaveOut`; switching back to a Computer
 *   preserves both checked and typed custom paths and sends them in `startMigration`.
 * - `SOURCE_COMPUTER_OFFLINE` (named and unnamed), `MIGRATION_LEAVE_OUT_INVALID`, and
 *   `MIGRATION_LEAVE_OUT_UNSUPPORTED` render dedicated start-error banner copy.
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h1500dp")
class AgentMigrationLeaveOutUiTest {
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

    private val agentId = MemberId("7e4f5a6b-7c8d-4e9f-8a1b-2c3d4e5f6071")
    private val sourceId = "8f5a6b7c-8d9e-4f0a-9b2c-3d4e5f607182"
    private val targetId = "9a6b7c8d-9e0f-4a1b-8c3d-4e5f60718293"

    private val computers =
        listOf(
            AgentMigrationComputer(id = sourceId, name = "Studio Mac", isComputer = true, online = true),
            AgentMigrationComputer(id = targetId, name = "Office Mini", isComputer = true, online = true),
        )

    private data class RecordedStart(
        val target: AgentMigrationTarget,
        val preflightOverride: Boolean,
        val leaveOut: List<String>,
    )

    private class FakeMigrationDataSource(
        private val onStart: (AgentMigrationTarget, Boolean, List<String>) -> DataResult<AgentMigrationStartResult>,
    ) : AgentMigrationDataSource {
        val startCalls = mutableListOf<RecordedStart>()

        override suspend fun migrationStatus(agentId: MemberId, priority: DataRequestPriority) =
            DataResult.Content(AgentMigrationStatusSnapshot(migration = null))

        override suspend fun startMigration(
            agentId: MemberId,
            target: AgentMigrationTarget,
            preflightOverride: Boolean,
            leaveOut: List<String>,
        ): DataResult<AgentMigrationStartResult> {
            startCalls += RecordedStart(target, preflightOverride, leaveOut)
            return onStart(target, preflightOverride, leaveOut)
        }

        override suspend fun cancelMigration(agentId: MemberId, migrationRef: String, expectedRevision: Int) = DataResult.Content(Unit)
        override suspend fun confirmHostedMigration(agentId: MemberId, migrationRef: String, expectedRevision: Int) = DataResult.Content(Unit)
        override suspend fun dismissMigration(agentId: MemberId, migrationRef: String, expectedRevision: Int) = DataResult.Content(Unit)
        override suspend fun billingPlan() = DataResult.Content(RaftBillingPlanSnapshot(plan = "pro"))
    }

    private fun sizeLimitFailedNotice(): AgentMigrationNotice =
        AgentMigrationNotice(
            agentId = agentId.value,
            migrationRef = "mig_big_1",
            state = "failed",
            revision = 2,
            sourceMachineId = sourceId,
            targetKind = "computer",
            targetMachineId = targetId,
            transportErrorCode = "MIGRATION_OBJECT_STORE_BUNDLE_TOO_LARGE",
            transportErrorMessage =
                "MIGRATION_OBJECT_STORE_BUNDLE_TOO_LARGE:sizeBytes=2147483648:maxBytes=1073741824:" +
                    "topEntries=repos/=335544320,MEMORY.md=4096,datasets/raw.bin=125829120,other/=5000",
        )

    private fun inputsFor(store: AgentMigrationStore, hostedAvailable: Boolean = true): AgentMigrationSectionInputs =
        AgentMigrationSectionInputs(
            sourceMachineId = sourceId,
            computers = computers,
            hostedAvailable = hostedAvailable,
            proGate = RaftProGateDecision.Allowed,
            proGateChecking = false,
            onIntent = store::dispatch,
            onOpenBilling = {},
            onCopy = {},
            formatTimestamp = { it },
            onAgentPlacementChanged = {},
            agentRuntime = "claude",
            agentName = "Atlas",
        )

    @Test
    fun retryPickerSelectsCandidatesValidatesCustomPathsAndSendsLeaveOut() =
        runRobolectricComposeUiTest {
            val dataSource =
                FakeMigrationDataSource { target, _, _ ->
                    DataResult.Content(
                        AgentMigrationStartResult(
                            migrationRef = "mig_retry_ok",
                            targetMachineId = (target as? AgentMigrationTarget.Computer)?.machineId,
                        )
                    )
                }
            val failedNotice = sizeLimitFailedNotice()
            val candidates = migrationLeaveOutCandidates(failedNotice, sourceId)
            setContent {
                MigrationFixtureHost {
                    val scope = rememberCoroutineScope()
                    val store =
                        remember {
                            AgentMigrationStore(dataSource, scope).also {
                                it.dispatch(AgentMigrationIntent.PrepareStart(agentId, checkPlan = false))
                            }
                        }
                    val state by store.state.collectAsState()
                    AgentMigrationStartDialog(
                        state = state.copy(notice = failedNotice),
                        inputs = inputsFor(store, hostedAvailable = false),
                        initialTarget = targetId,
                        leaveOutCandidates = candidates,
                        onStart = { target, override, leaveOut ->
                            store.dispatch(AgentMigrationIntent.Start(target, preflightOverride = override, leaveOut = leaveOut))
                        },
                        onDismiss = {},
                    )
                }
            }

            // Picker renders measured candidates (filtering out MEMORY.md and other/).
            onNodeWithTag(AgentMigrationLeaveOutTestTags.Root).assertExists()
            onNodeWithText("Don't move these paths").assertExists()
            onNodeWithText("repos/").assertExists()
            onNodeWithText("datasets/raw.bin").assertExists()
            onNodeWithText("MEMORY.md").assertDoesNotExist()
            onNodeWithText("other/").assertDoesNotExist()

            // Select measured candidate.
            onNodeWithText("repos/").performClick()
            waitForIdle()

            // Typing a protected path shows alwaysMoved error.
            onNodeWithTag(AgentMigrationLeaveOutTestTags.Input).performTextInput("notes/today.md")
            onNodeWithTag(AgentMigrationLeaveOutTestTags.AddButton).performClick()
            waitForIdle()
            onNodeWithText("MEMORY.md, notes/, the ignore files and .raft/ always move, so they can't be left out.").assertExists()

            // Replacing with an invalid path via IME Done shows invalid error.
            onNodeWithTag(AgentMigrationLeaveOutTestTags.Input).performTextReplacement("../secret")
            onNodeWithTag(AgentMigrationLeaveOutTestTags.Input).performImeAction()
            waitForIdle()
            onNodeWithText("Use a path inside the workspace, like work/build, or **/ and a folder name to skip that folder everywhere.").assertExists()

            // Replacing with a valid custom path via IME Done adds and checks it.
            onNodeWithTag(AgentMigrationLeaveOutTestTags.Input).performTextReplacement("work/build")
            onNodeWithTag(AgentMigrationLeaveOutTestTags.Input).performImeAction()
            waitForIdle()
            onNodeWithText("work/build").assertExists()

            onNodeWithText("Move").performClick()
            waitForIdle()
            assertEquals(1, dataSource.startCalls.size)
            assertEquals(AgentMigrationTarget.Computer(targetId), dataSource.startCalls.single().target)
            assertEquals(listOf("repos/", "work/build"), dataSource.startCalls.single().leaveOut)
        }

    @Test
    fun switchingToCloudHidesPickerAndOmitsLeaveOutWhilePreservingSelectionAcrossRoundTrip() =
        runRobolectricComposeUiTest {
            val dataSource =
                FakeMigrationDataSource { target, _, _ ->
                    DataResult.Content(
                        AgentMigrationStartResult(
                            migrationRef = "mig_switch",
                            targetMachineId = (target as? AgentMigrationTarget.Computer)?.machineId,
                        )
                    )
                }
            val failedNotice = sizeLimitFailedNotice()
            val candidates = migrationLeaveOutCandidates(failedNotice, sourceId)
            setContent {
                MigrationFixtureHost {
                    val scope = rememberCoroutineScope()
                    val store =
                        remember {
                            AgentMigrationStore(dataSource, scope).also {
                                it.dispatch(AgentMigrationIntent.PrepareStart(agentId, checkPlan = false))
                            }
                        }
                    val state by store.state.collectAsState()
                    AgentMigrationStartDialog(
                        state = state.copy(notice = failedNotice),
                        inputs = inputsFor(store, hostedAvailable = true),
                        initialTarget = targetId,
                        leaveOutCandidates = candidates,
                        onStart = { target, override, leaveOut ->
                            store.dispatch(AgentMigrationIntent.Start(target, preflightOverride = override, leaveOut = leaveOut))
                        },
                        onDismiss = {},
                    )
                }
            }

            // Check measured candidate and add a custom path.
            onNodeWithText("repos/").performClick()
            onNodeWithTag(AgentMigrationLeaveOutTestTags.Input).performTextInput("cache/models")
            onNodeWithTag(AgentMigrationLeaveOutTestTags.AddButton).performClick()
            waitForIdle()
            onNodeWithText("cache/models").assertExists()

            // Switch target to Cloud -> picker disappears and Cloud submit omits leaveOut.
            onNodeWithText("Cloud").performClick()
            waitForIdle()
            onNodeWithTag(AgentMigrationLeaveOutTestTags.Root).assertDoesNotExist()
            onNodeWithText("Move to the cloud").performClick()
            waitForIdle()
            assertEquals(AgentMigrationTarget.Cloud, dataSource.startCalls.last().target)
            assertEquals(emptyList(), dataSource.startCalls.last().leaveOut)

            // Switch back to Office Mini -> picker and custom path remain visible & selected.
            onNodeWithText("Office Mini").performClick()
            waitForIdle()
            onNodeWithTag(AgentMigrationLeaveOutTestTags.Root).assertExists()
            onNodeWithText("cache/models").assertExists()
            onNodeWithText("Move to another computer").performClick()
            waitForIdle()
            assertEquals(AgentMigrationTarget.Computer(targetId), dataSource.startCalls.last().target)
            assertEquals(listOf("repos/", "cache/models"), dataSource.startCalls.last().leaveOut)
        }

    @Test
    fun startErrorBannerRendersSourceOfflineAndLeaveOutErrorCopy() =
        runRobolectricComposeUiTest {
            setContent {
                MigrationFixtureHost {
                    Column {
                        AgentMigrationActionErrorBanner(
                            error = AgentMigrationActionError("SOURCE_COMPUTER_OFFLINE"),
                            context = MigrationErrorContext.Start,
                            sourceComputerName = "Studio Mac",
                        )
                        AgentMigrationActionErrorBanner(
                            error = AgentMigrationActionError("SOURCE_COMPUTER_OFFLINE"),
                            context = MigrationErrorContext.Start,
                            sourceComputerName = null,
                        )
                        AgentMigrationActionErrorBanner(
                            error = AgentMigrationActionError("MIGRATION_LEAVE_OUT_INVALID"),
                            context = MigrationErrorContext.Start,
                        )
                        AgentMigrationActionErrorBanner(
                            error = AgentMigrationActionError("MIGRATION_LEAVE_OUT_UNSUPPORTED"),
                            context = MigrationErrorContext.Start,
                        )
                    }
                }
            }

            onNodeWithText("Studio Mac, where this agent is now, is offline. Start Raft Computer on it and try again.").assertExists()
            onNodeWithText("The computer this agent is on is offline. Start Raft Computer on it and try again.").assertExists()
            onNodeWithText("One of the paths to leave out can't be used. MEMORY.md and notes/ always move, and each path must be inside the workspace.").assertExists()
            onNodeWithText("Raft on the source computer is too old to leave paths out. Update it, then try again.").assertExists()
        }
}
