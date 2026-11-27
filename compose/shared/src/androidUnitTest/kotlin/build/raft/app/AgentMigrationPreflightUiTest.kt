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
import build.raft.app.data.AgentMigrationCannotRespondReason
import build.raft.app.data.AgentMigrationDataSource
import build.raft.app.data.AgentMigrationErrorCodes
import build.raft.app.data.AgentMigrationIntent
import build.raft.app.data.AgentMigrationLogOutcome
import build.raft.app.data.AgentMigrationNotice
import build.raft.app.data.AgentMigrationStartResult
import build.raft.app.data.AgentMigrationState
import build.raft.app.data.AgentMigrationStatusSnapshot
import build.raft.app.data.AgentMigrationStore
import build.raft.app.data.AgentMigrationTarget
import build.raft.app.data.AppLocale
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.MigrationTargetRuntimeNotReady
import build.raft.app.data.MigrationTargetRuntimeNotReadyKind
import build.raft.app.data.RaftBillingPlanSnapshot
import build.raft.app.data.RaftProGateDecision
import build.raft.app.data.ScreenError
import build.raft.app.data.migrationTargetRuntimeNotReadyFields
import build.raft.app.data.stringDescLocaleType
import build.raft.app.model.MemberId
import build.raft.app.resources.StringDesc
import build.raft.app.thread.dispatch
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
 * Robolectric Compose UI tests for `slock#16567` (#proj-raft-computer:#135):
 * - 409 `TARGET_RUNTIME_NOT_READY` preflight refusal & `"Move anyway"` override (and target switch reset)
 * - `"not_ready"` copy without target account disclosure
 * - `"unverified"` preflight info toast
 * - `GET /agents/:id/migration` `cannotRespond` warning banner on [AgentMigrationSection]
 * - `AgentMigrationLogOutcomeBadge` `"Moved, can't respond"` parity
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h1500dp")
class AgentMigrationPreflightUiTest {
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

    private val agentId = MemberId("3e4f5a6b-7c8d-4e9f-8a1b-2c3d4e5f6071")
    private val sourceId = "4f5a6b7c-8d9e-4f0a-9b2c-3d4e5f607182"
    private val targetId = "5a6b7c8d-9e0f-4a1b-8c3d-4e5f60718293"
    private val otherId = "6b7c8d9e-0f1a-4b2c-9d4e-5f6071829304"

    private val computers =
        listOf(
            AgentMigrationComputer(id = sourceId, name = "Source", isComputer = true, online = true),
            AgentMigrationComputer(id = targetId, name = "Target", isComputer = true, online = true),
            AgentMigrationComputer(id = otherId, name = "Other", isComputer = true, online = true),
        )

    private class FakeMigrationDataSource(
        private val onStart: (AgentMigrationTarget, Boolean) -> DataResult<AgentMigrationStartResult>,
    ) : AgentMigrationDataSource {
        val startCalls = mutableListOf<Pair<AgentMigrationTarget, Boolean>>()

        override suspend fun migrationStatus(agentId: MemberId, priority: DataRequestPriority) =
            DataResult.Content(AgentMigrationStatusSnapshot(migration = null))

        override suspend fun startMigration(
            agentId: MemberId,
            target: AgentMigrationTarget,
            preflightOverride: Boolean,
            leaveOut: List<String>,
        ): DataResult<AgentMigrationStartResult> {
            startCalls += target to preflightOverride
            return onStart(target, preflightOverride)
        }

        override suspend fun cancelMigration(agentId: MemberId, migrationRef: String, expectedRevision: Int) = DataResult.Content(Unit)
        override suspend fun confirmHostedMigration(agentId: MemberId, migrationRef: String, expectedRevision: Int) = DataResult.Content(Unit)
        override suspend fun dismissMigration(agentId: MemberId, migrationRef: String, expectedRevision: Int) = DataResult.Content(Unit)
        override suspend fun billingPlan() = DataResult.Content(RaftBillingPlanSnapshot(plan = "pro"))
    }

    private fun notReadyError(kind: MigrationTargetRuntimeNotReadyKind): DataResult<AgentMigrationStartResult> =
        DataResult.Error(
            ScreenError(
                code = AgentMigrationErrorCodes.TargetRuntimeNotReady,
                serverCode = AgentMigrationErrorCodes.TargetRuntimeNotReady,
                serverMessage = "not ready",
                serverFields = migrationTargetRuntimeNotReadyFields(MigrationTargetRuntimeNotReady("claude", "Target", kind)),
            )
        )

    @Test
    fun refusedMoveShowsReasonAndMoveAnywayAndTargetSwitchResetsOverride() =
        runRobolectricComposeUiTest {
            val dataSource =
                FakeMigrationDataSource { target, override ->
                    if (!override && target == AgentMigrationTarget.Computer(targetId)) {
                        notReadyError(MigrationTargetRuntimeNotReadyKind.NeedsLogin)
                    } else {
                        DataResult.Content(
                            AgentMigrationStartResult(
                                migrationRef = "mig_ok",
                                targetMachineId = (target as AgentMigrationTarget.Computer).machineId,
                                preflightKind = "usable",
                            )
                        )
                    }
                }
            setContent {
                MigrationFixtureHost {
                    val scope = rememberCoroutineScope()
                    val store = remember { AgentMigrationStore(dataSource, scope).also { it.dispatch(AgentMigrationIntent.PrepareStart(agentId, checkPlan = false)) } }
                    val state by store.state.collectAsState()
                    val inputs =
                        AgentMigrationSectionInputs(
                            sourceMachineId = sourceId,
                            computers = computers,
                            hostedAvailable = false,
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
                    AgentMigrationStartDialog(
                        state = state,
                        inputs = inputs,
                        initialTarget = targetId,
                        onStart = { target, override, leaveOut ->
                            store.dispatch(AgentMigrationIntent.Start(target, preflightOverride = override, leaveOut = leaveOut))
                        },
                        onDismiss = {},
                    )
                }
            }

            // First click sends without override and hits 409 TARGET_RUNTIME_NOT_READY.
            onNodeWithText("Move").performClick()
            waitForIdle()
            onNodeWithText("Claude Code isn't signed in on Target", substring = true).assertExists()
            onNodeWithText("Move anyway").assertExists()
            assertEquals(listOf<Pair<AgentMigrationTarget, Boolean>>(AgentMigrationTarget.Computer(targetId) to false), dataSource.startCalls)

            // Switching to Other resets "Move anyway" back to "Move" and sends without override.
            onNodeWithText("Other").performClick()
            waitForIdle()
            onNodeWithText("Move anyway").assertDoesNotExist()
            onNodeWithText("Move").performClick()
            waitForIdle()
            assertEquals(AgentMigrationTarget.Computer(otherId) to false, dataSource.startCalls.last())
        }

    @Test
    fun moveAnywaySendsPreflightOverrideTrueAndNotReadyHidesAccountDetails() =
        runRobolectricComposeUiTest {
            val dataSource =
                FakeMigrationDataSource { target, override ->
                    if (!override) {
                        notReadyError(MigrationTargetRuntimeNotReadyKind.NotReady)
                    } else {
                        DataResult.Content(
                            AgentMigrationStartResult(
                                migrationRef = "mig_override",
                                targetMachineId = (target as AgentMigrationTarget.Computer).machineId,
                                preflightKind = "not_ready",
                            )
                        )
                    }
                }
            setContent {
                MigrationFixtureHost {
                    val scope = rememberCoroutineScope()
                    val store = remember { AgentMigrationStore(dataSource, scope).also { it.dispatch(AgentMigrationIntent.PrepareStart(agentId, checkPlan = false)) } }
                    val state by store.state.collectAsState()
                    val inputs =
                        AgentMigrationSectionInputs(
                            sourceMachineId = sourceId,
                            computers = computers,
                            hostedAvailable = false,
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
                    AgentMigrationStartDialog(
                        state = state,
                        inputs = inputs,
                        initialTarget = targetId,
                        onStart = { target, override, leaveOut ->
                            store.dispatch(AgentMigrationIntent.Start(target, preflightOverride = override, leaveOut = leaveOut))
                        },
                        onDismiss = {},
                    )
                }
            }

            onNodeWithText("Move").performClick()
            waitForIdle()
            onNodeWithText("Claude Code can't run on Target right now", substring = true).assertExists()
            onNodeWithText("Ask the computer's owner", substring = true).assertExists()

            onNodeWithText("Move anyway").performClick()
            waitForIdle()
            assertEquals(
                listOf<Pair<AgentMigrationTarget, Boolean>>(
                    AgentMigrationTarget.Computer(targetId) to false,
                    AgentMigrationTarget.Computer(targetId) to true,
                ),
                dataSource.startCalls,
            )
        }

    @Test
    fun unverifiedPreflightShowsInfoToastOnAgentMigrationSection() =
        runRobolectricComposeUiTest {
            val toasts = mutableListOf<String>()
            val dataSource =
                FakeMigrationDataSource { target, _ ->
                    DataResult.Content(
                        AgentMigrationStartResult(
                            migrationRef = "mig_unverified",
                            targetMachineId = (target as AgentMigrationTarget.Computer).machineId,
                            preflightKind = "unverified",
                        )
                    )
                }
            setContent {
                MigrationFixtureHost {
                    val scope = rememberCoroutineScope()
                    val store = remember { AgentMigrationStore(dataSource, scope).also { it.dispatch(AgentMigrationIntent.Watch(agentId, checkPlan = false)) } }
                    val state by store.state.collectAsState()
                    Column {
                        AgentMigrationSection(
                            state = state,
                            inputs =
                                AgentMigrationSectionInputs(
                                    sourceMachineId = sourceId,
                                    computers = computers,
                                    hostedAvailable = false,
                                    proGate = RaftProGateDecision.Allowed,
                                    proGateChecking = false,
                                    onIntent = store::dispatch,
                                    onOpenBilling = {},
                                    onCopy = {},
                                    formatTimestamp = { it },
                                    onAgentPlacementChanged = {},
                                    agentRuntime = "claude",
                                    agentName = "Atlas",
                                    onInfoToast = { toasts += it },
                                ),
                        )
                    }
                }
            }
            waitForIdle()
            onNodeWithText("Move to another computer").performClick()
            waitForIdle()
            onNodeWithText("Move").performClick()
            waitForIdle()

            assertEquals(1, toasts.size)
            assertTrue(
                toasts.single().contains("Started moving to Target without confirming Claude Code sign-in"),
                "Unexpected toast: ${toasts.single()}",
            )
        }

    @Test
    fun completedMoveWithCannotRespondRendersWarningBanner() =
        runRobolectricComposeUiTest {
            val completedCannotRespond =
                AgentMigrationNotice(
                    agentId = agentId.value,
                    migrationRef = "mig_done_warn",
                    state = "completed",
                    revision = 4,
                    sourceMachineId = sourceId,
                    targetKind = "computer",
                    targetMachineId = targetId,
                    cannotRespond = AgentMigrationCannotRespondReason.NeedsLogin.wire,
                )
            setContent {
                MigrationFixtureHost {
                    Column {
                        AgentMigrationSection(
                            state = AgentMigrationState(agentId = agentId, loaded = true, notice = completedCannotRespond),
                            inputs =
                                AgentMigrationSectionInputs(
                                    sourceMachineId = targetId,
                                    computers = computers,
                                    hostedAvailable = false,
                                    proGate = RaftProGateDecision.Allowed,
                                    proGateChecking = false,
                                    onIntent = {},
                                    onOpenBilling = {},
                                    onCopy = {},
                                    formatTimestamp = { it },
                                    onAgentPlacementChanged = {},
                                    agentRuntime = "claude",
                                    agentName = "Atlas",
                                ),
                        )
                    }
                }
            }
            onNodeWithTag(AgentMigrationSectionTestTags.CannotRespondBanner).assertExists()
            onNodeWithText("Moved, can't respond").assertExists()
            onNodeWithText("Moved to Target, but the agent can't reply there: its runtime isn't signed in on that computer.").assertExists()
        }

    @Test
    fun migrationLogOutcomeBadgeShowsCannotRespondWarningOnlyWhenFlagged() =
        runRobolectricComposeUiTest {
            setContent {
                MigrationFixtureHost {
                    Column {
                        AgentMigrationLogOutcomeBadge(
                            outcome = AgentMigrationLogOutcome.Completed,
                            cannotRespond = AgentMigrationCannotRespondReason.NotReady,
                        )
                        AgentMigrationLogOutcomeBadge(
                            outcome = AgentMigrationLogOutcome.Completed,
                            cannotRespond = null,
                        )
                    }
                }
            }
            onNodeWithText("Moved, can't respond").assertExists()
            onNodeWithText("Succeeded").assertExists()
        }
}
