package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.data.AgentMigrateActionCardType
import build.raft.app.data.AgentMigrationDataSource
import build.raft.app.data.AgentMigrationIntent
import build.raft.app.data.AgentMigrationStartResult
import build.raft.app.data.AgentMigrationStatusSnapshot
import build.raft.app.data.AgentMigrationStore
import build.raft.app.data.AgentMigrationTarget
import build.raft.app.data.AppLocale
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.RAFT_APP_ACTION_CARD_CAPABILITIES
import build.raft.app.data.RaftBillingPlanSnapshot
import build.raft.app.data.ThreadActionCard
import build.raft.app.data.stringDescLocaleType
import build.raft.app.model.MemberId
import build.raft.app.resources.StringDesc
import build.raft.app.thread.AgentMigrateCardAccess
import build.raft.app.thread.LocalThreadActionCardSpecHost
import build.raft.app.thread.ThreadActionCardSpecHost
import build.raft.app.thread.ThreadActionCardSpecView
import build.raft.app.thread.ThreadMigrateAgentActionDialogContent
import build.raft.app.thread.ThreadMigrateAgentActionDialogState
import build.raft.app.thread.ThreadMigrateAgentActionTarget
import build.raft.app.thread.actionCardSpecToRender
import build.raft.app.thread.dispatch
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric Compose UI tests for `#proj-mobile:#356` (native `agent:migrate` action card,
 * `intent.migrate_agent`):
 * - Card button renders `"Move agent"` (never `"Open in web"`)
 * - Unauthorized viewer sees blocked `"View only"` state with `actionCard.blocked.needMigrateAgents`
 * - Native dialog preselects `suggestedComputer`, enforces Pro upgrade gate (skipped for
 *   `placesWithoutSource`), and triggers `mark-executed` callback with `(agentId, targetMachineId)`
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h1500dp")
class ActionCardAgentMigrateUiTest {
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

    private val agentId = "3e4f5a6b-7c8d-4e9f-8a1b-2c3d4e5f6071"
    private val sourceId = "4f5a6b7c-8d9e-4f0a-9b2c-3d4e5f607182"
    private val otherId = "6b7c8d9e-0f1a-4b2c-9d4e-5f6071829304"
    private val suggestedId = "5a6b7c8d-9e0f-4a1b-8c3d-4e5f60718293"

    private val computers =
        listOf(
            AgentMigrationComputer(id = sourceId, name = "Source", isComputer = true, online = true),
            AgentMigrationComputer(id = otherId, name = "Other", isComputer = true, online = true),
            AgentMigrationComputer(id = suggestedId, name = "Suggested", isComputer = true, online = true),
        )

    private class FakeCardMigrationDataSource(
        private val plan: String?,
        private val onStart: (AgentMigrationTarget, Boolean) -> DataResult<AgentMigrationStartResult>,
    ) : AgentMigrationDataSource {
        val starts = mutableListOf<Pair<AgentMigrationTarget, Boolean>>()
        override suspend fun migrationStatus(agentId: MemberId, priority: DataRequestPriority) =
            DataResult.Content(AgentMigrationStatusSnapshot(null))
        override suspend fun startMigration(
            agentId: MemberId,
            target: AgentMigrationTarget,
            preflightOverride: Boolean,
            leaveOut: List<String>,
        ): DataResult<AgentMigrationStartResult> {
            starts += target to preflightOverride
            return onStart(target, preflightOverride)
        }
        override suspend fun cancelMigration(agentId: MemberId, migrationRef: String, expectedRevision: Int) = DataResult.Content(Unit)
        override suspend fun confirmHostedMigration(agentId: MemberId, migrationRef: String, expectedRevision: Int) = DataResult.Content(Unit)
        override suspend fun dismissMigration(agentId: MemberId, migrationRef: String, expectedRevision: Int) = DataResult.Content(Unit)
        override suspend fun billingPlan() = DataResult.Content(RaftBillingPlanSnapshot(plan))
    }

    @Test
    fun agentMigrateCardRendersMoveAgentNotOpenInWebAndPressDispatches() =
        runRobolectricComposeUiTest {
            val card =
                ThreadActionCard(
                    type = AgentMigrateActionCardType,
                    agentId = agentId,
                    suggestedComputer = suggestedId,
                    presentationCardJson = ActionCardSpecFixtures.AGENT_MIGRATE_V9,
                )
            val spec = assertNotNull(card.actionCardSpecToRender(RAFT_APP_ACTION_CARD_CAPABILITIES))
            var tappedElement: String? = null
            setContent {
                MigrationFixtureHost {
                    CompositionLocalProvider(
                        LocalThreadActionCardSpecHost provides
                            ThreadActionCardSpecHost(
                                evaluateMigrateAgentAccess = { AgentMigrateCardAccess(canMigrate = true, agentName = "Atlas") },
                            ),
                    ) {
                        Column {
                            ThreadActionCardSpecView(
                                messageId = "msg-1",
                                card = card,
                                spec = spec,
                                commitEnabled = true,
                                typedPending = false,
                                executedTimeLabel = null,
                                onAction = { tappedElement = it },
                            )
                        }
                    }
                }
            }
            onNodeWithText("Move agent").assertExists()
            onNodeWithText("Open in web").assertDoesNotExist()
            onNodeWithText("Move agent").performClick()
            assertEquals("migrateAgent", tappedElement)
        }

    @Test
    fun agentMigrateCardBlockedForUnauthorizedViewerShowsNeedMigrateAgentsCopy() =
        runRobolectricComposeUiTest {
            val card =
                ThreadActionCard(
                    type = AgentMigrateActionCardType,
                    agentId = agentId,
                    suggestedComputer = suggestedId,
                    presentationCardJson = ActionCardSpecFixtures.AGENT_MIGRATE_V9,
                )
            val spec = assertNotNull(card.actionCardSpecToRender(RAFT_APP_ACTION_CARD_CAPABILITIES))
            setContent {
                MigrationFixtureHost {
                    CompositionLocalProvider(
                        LocalThreadActionCardSpecHost provides
                            ThreadActionCardSpecHost(
                                evaluateMigrateAgentAccess = { AgentMigrateCardAccess(canMigrate = false, agentName = "Atlas") },
                            ),
                    ) {
                        Column {
                            ThreadActionCardSpecView(
                                messageId = "msg-2",
                                card = card,
                                spec = spec,
                                commitEnabled = true,
                                typedPending = false,
                                executedTimeLabel = null,
                                onAction = {},
                            )
                        }
                    }
                }
            }
            onNodeWithText("View only").assertExists()
            onNodeWithText("Only Atlas's creator or someone with permission to migrate agents can do this.").assertExists()
        }

    @Test
    fun migrateAgentDialogPreselectsSuggestedComputerAndReportsTargetToMarkExecuted() =
        runRobolectricComposeUiTest {
            val dataSource =
                FakeCardMigrationDataSource(plan = "pro") { target, _ ->
                    DataResult.Content(
                        AgentMigrationStartResult(
                            migrationRef = "mig_card",
                            targetMachineId = (target as AgentMigrationTarget.Computer).machineId,
                            preflightKind = "usable",
                        )
                    )
                }
            val card =
                ThreadActionCard(
                    type = AgentMigrateActionCardType,
                    agentId = agentId,
                    suggestedComputer = suggestedId,
                    confirmationVersion = 5,
                )
            var dismissed = false
            var startedTriple: Triple<String, String, Boolean>? = null
            setContent {
                MigrationFixtureHost {
                    val scope = rememberCoroutineScope()
                    val store = remember { AgentMigrationStore(dataSource, scope).also { it.dispatch(AgentMigrationIntent.PrepareStart(MemberId(agentId), checkPlan = true)) } }
                    val state by store.state.collectAsState()
                    ThreadMigrateAgentActionDialogContent(
                        dialogState = ThreadMigrateAgentActionDialogState(messageId = "msg-card", card = card),
                        target =
                            ThreadMigrateAgentActionTarget(
                                agentId = agentId,
                                agentName = "Atlas",
                                runtime = "claude",
                                executionMode = "byoc",
                                sourceMachineId = sourceId,
                                blockedReason = null,
                            ),
                        migrationState = state,
                        computers = computers,
                        viewerUserId = "user-1",
                        onMigrationIntent = store::dispatch,
                        onOpenUpgrade = {},
                        onOpenBilling = {},
                        onCopy = {},
                        onToast = {},
                        onDismiss = { dismissed = true },
                        onMigrationStarted = { _, _, aid, tid, placed -> startedTriple = Triple(aid, tid, placed) },
                    )
                }
            }
            waitForIdle()
            // Submit immediately without tapping a computer: suggestedComputer ("Suggested") must be preselected.
            onNodeWithText("Move").performClick()
            waitForIdle()

            assertEquals(listOf<Pair<AgentMigrationTarget, Boolean>>(AgentMigrationTarget.Computer(suggestedId) to false), dataSource.starts)
            assertTrue(dismissed)
            assertEquals(Triple(agentId, suggestedId, false), startedTriple)
        }

    @Test
    fun freePlanTriggersProUpgradeGateUnlessPlacesWithoutSource() =
        runRobolectricComposeUiTest {
            var upgradeOpens = 0
            val freeSource =
                FakeCardMigrationDataSource(plan = "free") { target, _ ->
                    DataResult.Content(
                        AgentMigrationStartResult(
                            migrationRef = null,
                            placedWithoutSource = true,
                            targetMachineId = (target as AgentMigrationTarget.Computer).machineId,
                        )
                    )
                }
            val card = ThreadActionCard(type = AgentMigrateActionCardType, agentId = agentId, suggestedComputer = suggestedId)
            setContent {
                MigrationFixtureHost {
                    val scope = rememberCoroutineScope()
                    val store = remember { AgentMigrationStore(freeSource, scope).also { it.dispatch(AgentMigrationIntent.PrepareStart(MemberId(agentId), checkPlan = true)) } }
                    val state by store.state.collectAsState()
                    ThreadMigrateAgentActionDialogContent(
                        dialogState = ThreadMigrateAgentActionDialogState(messageId = "msg-free", card = card),
                        target =
                            ThreadMigrateAgentActionTarget(
                                agentId = agentId,
                                agentName = "Atlas",
                                runtime = "claude",
                                executionMode = "byoc",
                                sourceMachineId = sourceId,
                                blockedReason = null,
                            ),
                        migrationState = state,
                        computers = computers,
                        viewerUserId = "user-1",
                        onMigrationIntent = store::dispatch,
                        onOpenUpgrade = { upgradeOpens += 1 },
                        onOpenBilling = {},
                        onCopy = {},
                        onToast = {},
                        onDismiss = {},
                        onMigrationStarted = { _, _, _, _, _ -> },
                        offersUpgrade = true,
                    )
                }
            }
            waitForIdle()
            assertEquals(1, upgradeOpens)
            onNodeWithText("Move").assertDoesNotExist()
        }

    @Test
    fun freePlanAllowsMoveWithoutUpgradeWhenPlacesWithoutSource() =
        runRobolectricComposeUiTest {
            var upgradeOpens = 0
            var startedPlaced = false
            val freeSource =
                FakeCardMigrationDataSource(plan = "free") { target, _ ->
                    DataResult.Content(
                        AgentMigrationStartResult(
                            migrationRef = null,
                            placedWithoutSource = true,
                            targetMachineId = (target as AgentMigrationTarget.Computer).machineId,
                        )
                    )
                }
            val card = ThreadActionCard(type = AgentMigrateActionCardType, agentId = agentId, suggestedComputer = suggestedId)
            setContent {
                MigrationFixtureHost {
                    val scope = rememberCoroutineScope()
                    val store = remember { AgentMigrationStore(freeSource, scope).also { it.dispatch(AgentMigrationIntent.PrepareStart(MemberId(agentId), checkPlan = true)) } }
                    val state by store.state.collectAsState()
                    ThreadMigrateAgentActionDialogContent(
                        dialogState = ThreadMigrateAgentActionDialogState(messageId = "msg-place", card = card),
                        target =
                            ThreadMigrateAgentActionTarget(
                                agentId = agentId,
                                agentName = "Atlas",
                                runtime = "claude",
                                executionMode = "byoc",
                                sourceMachineId = null,
                                blockedReason = null,
                            ),
                        migrationState = state,
                        computers = computers,
                        viewerUserId = "user-1",
                        onMigrationIntent = store::dispatch,
                        onOpenUpgrade = { upgradeOpens += 1 },
                        onOpenBilling = {},
                        onCopy = {},
                        onToast = {},
                        onDismiss = {},
                        onMigrationStarted = { _, _, _, _, placed -> startedPlaced = placed },
                        offersUpgrade = true,
                    )
                }
            }
            waitForIdle()
            assertEquals(0, upgradeOpens)
            onNodeWithText("Move").performClick()
            waitForIdle()
            assertTrue(startedPlaced)
        }
}
