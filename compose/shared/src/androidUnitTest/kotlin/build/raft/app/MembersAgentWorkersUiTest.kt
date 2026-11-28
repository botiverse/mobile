package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AgentDetailSnapshot
import build.raft.app.data.AgentWorkerDoing
import build.raft.app.data.AgentWorkerObjectKind
import build.raft.app.data.AgentWorkerProgress
import build.raft.app.data.AgentWorkerRow
import build.raft.app.data.AgentWorkerState
import build.raft.app.data.AgentWorkerStopFeedback
import build.raft.app.data.AgentWorkersIntent
import build.raft.app.data.AgentWorkersLoadStatus
import build.raft.app.data.AgentWorkersState
import build.raft.app.data.ScreenError
import build.raft.app.data.WorkerConversationHolding
import build.raft.app.data.WorkerConversationHoldingMode
import build.raft.app.data.groupAgentActivitySubagents
import build.raft.app.members.MembersAgentActivityLoadState
import build.raft.app.members.MembersAgentActivityTab
import build.raft.app.members.MembersAgentSubagentsListTag
import build.raft.app.members.MembersAgentWorkerMarkTag
import build.raft.app.members.MembersAgentWorkerStopConfirmTag
import build.raft.app.members.MembersAgentWorkerStopDialogTag
import build.raft.app.members.MembersAgentWorkersBinding
import build.raft.app.members.MembersAgentWorkersList
import build.raft.app.members.MembersAgentWorkersListTag
import build.raft.app.members.MembersAgentWorkersStaleTag
import build.raft.app.members.MembersAgentWorkersUnknownTag
import build.raft.app.members.membersAgentWorkerRowTag
import build.raft.app.members.membersAgentWorkerStopFeedbackMessage
import build.raft.app.members.membersAgentWorkerStopTag
import build.raft.app.model.MemberId
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Worker v1 PR 2 (`docs/contracts/worker-snapshot.md`, web `agentWorkersPanelFlag.behavior.test.tsx`,
 * `agentWorkersPanel.behavior.test.tsx`, `agentWorkersObservability.behavior.test.tsx`):
 * Agent Detail › Activity › Workers UI contract on a real Robolectric Compose composition.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h2400dp")
class MembersAgentWorkersUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val agentId = MemberId("agent-1")
    private val nowMillis = checkNotNull(parseRaftTimestampEpochMillisOrNull("2026-11-23T10:12:00.000Z"))
    private val subagentLog = subagentsFixtureLog()
    private val subagentGroups = groupAgentActivitySubagents(subagentLog)

    private fun workerRow(
        worker: String = "w1",
        objectRef: String? = "#eng:#7",
        objectKind: AgentWorkerObjectKind? = AgentWorkerObjectKind.Task,
        model: String? = "claude-opus-5-5",
        rule: String? = "task conversations always go out",
        since: String = "2026-11-23T10:00:00.000Z",
        state: AgentWorkerState = AgentWorkerState.Running,
        progress: AgentWorkerProgress? = AgentWorkerProgress("@bob moved the window to Sunday.", "2026-11-23T10:08:00.000Z"),
        launchId: String? = "worker-$worker-abc",
        lastEventAt: String? = null,
        doing: AgentWorkerDoing? = null,
        tool: String? = null,
        oldestWaitingAt: String? = null,
        stalled: Boolean = false,
        inToolForMs: Long? = null,
        waitingFrom: String? = null,
        conversations: List<WorkerConversationHolding>? = null,
    ): AgentWorkerRow =
        AgentWorkerRow(
            agentId = agentId,
            worker = worker,
            objectRef = objectRef,
            objectKind = objectKind,
            model = model,
            rule = rule,
            since = since,
            state = state,
            progress = progress,
            launchId = launchId,
            lastEventAt = lastEventAt,
            doing = doing,
            tool = tool,
            oldestWaitingAt = oldestWaitingAt,
            stalled = stalled,
            inToolForMs = inToolForMs,
            waitingFrom = waitingFrom,
            conversations = conversations,
        )

    private fun workersState(
        status: AgentWorkersLoadStatus = AgentWorkersLoadStatus.Known,
        workers: List<AgentWorkerRow> = listOf(workerRow()),
        receivedAtMillis: Long? = nowMillis - 5_000L,
        serverStale: Boolean = false,
        stoppingWorker: String? = null,
    ): AgentWorkersState =
        AgentWorkersState(
            agentId = agentId,
            enabled = true,
            status = status,
            workers = workers,
            readAtMillis = nowMillis,
            receivedAtMillis = receivedAtMillis,
            serverStale = serverStale,
            nowMillis = nowMillis,
            stoppingWorker = stoppingWorker,
        )

    @Composable
    private fun WorkersTestShell(content: @Composable () -> Unit) {
        val modalHostState = rememberRaftUnifiedDialogHostState()
        val pager =
            remember {
                object : Pager() {}.apply {
                    pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
                }
            }
        CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
            RaftDesignSystemTheme {
                RaftUnifiedDialogHostProvider(modalHostState) {
                    Box(Modifier.fillMaxSize()) {
                        content()
                        RaftUnifiedDialogHost(state = modalHostState, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    }

    @Test
    fun flagOffKeepsLegacySubagentsSegmentAndRowsUnchanged() =
        runRobolectricComposeUiTest {
            val detail =
                AgentDetailSnapshot(
                    agentId = agentId,
                    directMessages = emptyList(),
                    reminders = emptyList(),
                    activity = subagentLog,
                )
            setContent {
                WorkersTestShell {
                    MembersAgentActivityTab(
                        detail = detail,
                        serverId = "server-1",
                        agentStatusIndicatorState = null,
                        loadState = MembersAgentActivityLoadState.Idle,
                        diagnosticActionState = null,
                        onCopyDiagnostics = {},
                        timeContext = SubagentsFixtureTimeContext,
                        onOpenUrl = {},
                        modifier = Modifier.fillMaxSize(),
                        autoScrollToLatest = false,
                        workers = null,
                    )
                }
            }
            waitForIdle()
            onNodeWithText("Subagents").assertIsDisplayed().performClick()
            waitForIdle()
            onNodeWithTag(MembersAgentSubagentsListTag).assertIsDisplayed()
            onAllNodesWithTag(MembersAgentWorkersListTag).assertCountEquals(0)
            onAllNodesWithText("runtime sub-agent", substring = true).assertCountEquals(0)
        }

    @Test
    fun flagOnRenamesSegmentToWorkersCountsBothAndTagsRuntimeSubagents() =
        runRobolectricComposeUiTest {
            val detail =
                AgentDetailSnapshot(
                    agentId = agentId,
                    directMessages = emptyList(),
                    reminders = emptyList(),
                    activity = subagentLog,
                )
            val stateFlow =
                MutableStateFlow(
                    workersState(
                        workers =
                            listOf(
                                workerRow(),
                                workerRow(
                                    worker = "w2",
                                    objectRef = "#ops:5be3e1aa",
                                    objectKind = AgentWorkerObjectKind.Thread,
                                    model = null,
                                    rule = null,
                                    progress = null,
                                    state = AgentWorkerState.Starting,
                                ),
                            ),
                    )
                )
            val intents = mutableListOf<AgentWorkersIntent>()
            val binding =
                MembersAgentWorkersBinding(
                    state = stateFlow,
                    parentHandle = "tenny",
                    canStop = true,
                    computerOffline = false,
                    onIntent = { intents += it },
                )
            setContent {
                WorkersTestShell {
                    MembersAgentActivityTab(
                        detail = detail,
                        serverId = "server-1",
                        agentStatusIndicatorState = null,
                        loadState = MembersAgentActivityLoadState.Idle,
                        diagnosticActionState = null,
                        onCopyDiagnostics = {},
                        timeContext = SubagentsFixtureTimeContext,
                        onOpenUrl = {},
                        modifier = Modifier.fillMaxSize(),
                        autoScrollToLatest = false,
                        workers = binding,
                    )
                }
            }
            waitForIdle()
            // Combined count = 2 live Workers + 3 fixture subagents = 5.
            onNodeWithText("Workers").assertIsDisplayed().performClick()
            waitForIdle()
            onNodeWithText((subagentGroups.size + 2).toString()).assertIsDisplayed()
            onNodeWithTag(MembersAgentWorkersListTag).assertIsDisplayed()
            onNodeWithTag(membersAgentWorkerRowTag("w1")).assertIsDisplayed()
            onNodeWithText(
                "#eng:#7 · claude-opus-5-5 · 12m 0s · progress 4 minutes ago: @bob moved the window to Sunday."
            ).assertIsDisplayed()
            onNodeWithTag(membersAgentWorkerRowTag("w2")).assertIsDisplayed()
            onNodeWithText("#ops:5be3e1aa · starting").assertIsDisplayed()
            // Rule is never rendered in v1.
            onAllNodesWithText("task conversations always go out", substring = true).assertCountEquals(0)
            // Runtime sub-agents remain visible below with the "runtime sub-agent" tag.
            onAllNodesWithText("runtime sub-agent · ", substring = true).assertCountEquals(subagentGroups.size)
        }

    @Test
    fun stopOpensConfirmationDialogAndDispatchesOnlyOnConfirm() =
        runRobolectricComposeUiTest {
            val stateFlow = MutableStateFlow(workersState())
            val intents = mutableListOf<AgentWorkersIntent>()
            val binding =
                MembersAgentWorkersBinding(
                    state = stateFlow,
                    parentHandle = "tenny",
                    canStop = true,
                    computerOffline = false,
                    onIntent = { intents += it },
                )
            setContent {
                WorkersTestShell {
                    MembersAgentWorkersList(
                        agentId = agentId,
                        workers = binding,
                        groups = emptyList(),
                        timeContext = SubagentsFixtureTimeContext,
                        onOpenSubagent = {},
                        contentPadding = PaddingValues(16.dp),
                        modifier = Modifier.fillMaxSize(),
                        nowEpochMillis = { nowMillis },
                    )
                }
            }
            waitForIdle()
            onNodeWithTag(membersAgentWorkerStopTag("w1")).assertIsEnabled().performClick()
            waitForIdle()
            onNodeWithTag(MembersAgentWorkerStopDialogTag).assertIsDisplayed()
            onNodeWithText("Stop w1?").assertIsDisplayed()
            onNodeWithText("#eng:#7 goes back to @tenny.").assertIsDisplayed()
            assertEquals(emptyList(), intents)

            // Cancel closes without stopping.
            onNodeWithText("Cancel").performClick()
            waitForIdle()
            onAllNodesWithTag(MembersAgentWorkerStopDialogTag).assertCountEquals(0)
            assertEquals(emptyList(), intents)

            // Re-open and confirm dispatches Stop.
            onNodeWithTag(membersAgentWorkerStopTag("w1")).performClick()
            waitForIdle()
            onNodeWithTag(MembersAgentWorkerStopConfirmTag).performClick()
            waitForIdle()
            assertEquals(
                listOf<AgentWorkersIntent>(AgentWorkersIntent.Stop(agentId, "w1", "#eng:#7")),
                intents,
            )
        }

    @Test
    fun stopHiddenWithoutPermissionAndDisabledWhenOfflineOrStaleWithNoStaleMarks() =
        runRobolectricComposeUiTest {
            // 1) canStop = false hides Stop button completely.
            val liveBinding =
                MembersAgentWorkersBinding(
                    state = MutableStateFlow(workersState()),
                    parentHandle = "tenny",
                    canStop = false,
                    computerOffline = false,
                    onIntent = {},
                )
            setContent {
                WorkersTestShell {
                    MembersAgentWorkersList(
                        agentId = agentId,
                        workers = liveBinding,
                        groups = emptyList(),
                        timeContext = SubagentsFixtureTimeContext,
                        onOpenSubagent = {},
                        contentPadding = PaddingValues(16.dp),
                        modifier = Modifier.fillMaxSize(),
                        nowEpochMillis = { nowMillis },
                    )
                }
            }
            waitForIdle()
            onAllNodesWithTag(membersAgentWorkerStopTag("w1")).assertCountEquals(0)
        }

    @Test
    fun staleSnapshotDisablesStopShowsLastSeenAndOmitsLiveMarks() =
        runRobolectricComposeUiTest {
            val staleRow =
                workerRow(
                    doing = AgentWorkerDoing.Model,
                    lastEventAt = "2026-11-23T09:58:00.000Z",
                    stalled = true,
                )
            val staleBinding =
                MembersAgentWorkersBinding(
                    state =
                        MutableStateFlow(
                            workersState(
                                workers = listOf(staleRow),
                                receivedAtMillis = nowMillis - 3 * 60_000L,
                                serverStale = true,
                            )
                        ),
                    parentHandle = "tenny",
                    canStop = true,
                    computerOffline = false,
                    onIntent = {},
                )
            setContent {
                WorkersTestShell {
                    MembersAgentWorkersList(
                        agentId = agentId,
                        workers = staleBinding,
                        groups = emptyList(),
                        timeContext = SubagentsFixtureTimeContext,
                        onOpenSubagent = {},
                        contentPadding = PaddingValues(16.dp),
                        modifier = Modifier.fillMaxSize(),
                        nowEpochMillis = { nowMillis },
                    )
                }
            }
            waitForIdle()
            onNodeWithTag(MembersAgentWorkersStaleTag).assertIsDisplayed()
            onNodeWithText("Last seen 3 minutes ago").assertIsDisplayed()
            onNodeWithTag(membersAgentWorkerStopTag("w1")).assertIsNotEnabled()
            onAllNodesWithTag(MembersAgentWorkerMarkTag).assertCountEquals(0)
        }

    @Test
    fun liveObservabilityMarksRenderInToolStalledAndWaiting() =
        runRobolectricComposeUiTest {
            val rows =
                listOf(
                    workerRow(
                        worker = "w1",
                        progress = null,
                        doing = AgentWorkerDoing.Tool,
                        tool = "Bash",
                        lastEventAt = "2026-11-23T09:59:00.000Z",
                        inToolForMs = 12 * 60_000L,
                    ),
                    workerRow(
                        worker = "w2",
                        progress = null,
                        model = null,
                        doing = AgentWorkerDoing.Model,
                        lastEventAt = "2026-11-23T09:58:00.000Z",
                        stalled = true,
                    ),
                    workerRow(
                        worker = "w3",
                        progress = null,
                        model = null,
                        doing = AgentWorkerDoing.Model,
                        oldestWaitingAt = "2026-11-23T10:09:00.000Z",
                        waitingFrom = "alice",
                    ),
                    workerRow(
                        worker = "w4",
                        progress = null,
                        model = null,
                        doing = AgentWorkerDoing.Model,
                        oldestWaitingAt = "2026-11-23T10:11:00.000Z",
                    ),
                )
            val binding =
                MembersAgentWorkersBinding(
                    state = MutableStateFlow(workersState(workers = rows)),
                    parentHandle = "tenny",
                    canStop = true,
                    computerOffline = true,
                    onIntent = {},
                )
            setContent {
                WorkersTestShell {
                    MembersAgentWorkersList(
                        agentId = agentId,
                        workers = binding,
                        groups = emptyList(),
                        timeContext = SubagentsFixtureTimeContext,
                        onOpenSubagent = {},
                        contentPadding = PaddingValues(16.dp),
                        modifier = Modifier.fillMaxSize(),
                        nowEpochMillis = { nowMillis },
                    )
                }
            }
            waitForIdle()
            onNodeWithText("In Bash for 12m").assertIsDisplayed()
            onNodeWithText("No activity for 14m").assertIsDisplayed()
            onNodeWithText("@alice waiting 3m").assertIsDisplayed()
            onNodeWithText("Someone waiting 1m").assertIsDisplayed()
            // Offline computer disables Stop.
            onNodeWithTag(membersAgentWorkerStopTag("w1")).assertIsNotEnabled()
        }

    @Test
    fun loadingDoesNotFlashEmptyStateAndUnknownShowsUnavailableNotice() =
        runRobolectricComposeUiTest {
            val stateFlow = MutableStateFlow(workersState(status = AgentWorkersLoadStatus.Loading, workers = emptyList()))
            val binding =
                MembersAgentWorkersBinding(
                    state = stateFlow,
                    parentHandle = "tenny",
                    canStop = true,
                    computerOffline = false,
                    onIntent = {},
                )
            setContent {
                WorkersTestShell {
                    MembersAgentWorkersList(
                        agentId = agentId,
                        workers = binding,
                        groups = emptyList(),
                        timeContext = SubagentsFixtureTimeContext,
                        onOpenSubagent = {},
                        contentPadding = PaddingValues(16.dp),
                        modifier = Modifier.fillMaxSize(),
                        nowEpochMillis = { nowMillis },
                    )
                }
            }
            waitForIdle()
            onAllNodesWithText("No Workers right now").assertCountEquals(0)

            stateFlow.value = workersState(status = AgentWorkersLoadStatus.Unknown, workers = emptyList(), receivedAtMillis = null)
            waitForIdle()
            onNodeWithTag(MembersAgentWorkersUnknownTag).assertIsDisplayed()
            onNodeWithText("Can't see this agent's Workers right now.").assertIsDisplayed()

            stateFlow.value = workersState(status = AgentWorkersLoadStatus.Known, workers = emptyList())
            waitForIdle()
            onNodeWithText("No Workers right now").assertIsDisplayed()
        }

    @Test
    fun multiHoldingsPostOnlyAndEmptyRowsAndStopDialogsMatchWebCopy() =
        runRobolectricComposeUiTest {
            val rows =
                listOf(
                    workerRow(
                        worker = "w1",
                        objectRef = "#eng:#7",
                        objectKind = AgentWorkerObjectKind.Task,
                        model = null,
                        progress = null,
                        conversations =
                            listOf(
                                WorkerConversationHolding(WorkerConversationHoldingMode.Full, "#eng:#7", AgentWorkerObjectKind.Task),
                                WorkerConversationHolding(WorkerConversationHoldingMode.Full, "#eng:5be3e1aa", AgentWorkerObjectKind.Thread),
                                WorkerConversationHolding(WorkerConversationHoldingMode.Post, "#announcements", null),
                            ),
                    ),
                    workerRow(
                        worker = "w2",
                        objectRef = "#eng:#7",
                        objectKind = AgentWorkerObjectKind.Task,
                        model = null,
                        progress = null,
                        conversations = null,
                    ),
                    workerRow(
                        worker = "w3",
                        objectRef = null,
                        objectKind = null,
                        model = null,
                        progress = null,
                        conversations =
                            listOf(
                                WorkerConversationHolding(WorkerConversationHoldingMode.Post, "#announcements", null),
                            ),
                    ),
                )
            val intents = mutableListOf<AgentWorkersIntent>()
            val binding =
                MembersAgentWorkersBinding(
                    state = MutableStateFlow(workersState(workers = rows)),
                    parentHandle = "tenny",
                    canStop = true,
                    computerOffline = false,
                    onIntent = { intents += it },
                )
            setContent {
                WorkersTestShell {
                    MembersAgentWorkersList(
                        agentId = agentId,
                        workers = binding,
                        groups = emptyList(),
                        timeContext = SubagentsFixtureTimeContext,
                        onOpenSubagent = {},
                        contentPadding = PaddingValues(16.dp),
                        modifier = Modifier.fillMaxSize(),
                        nowEpochMillis = { nowMillis },
                    )
                }
            }
            waitForIdle()
            onNodeWithText("#eng:#7 and #eng:5be3e1aa · can also post in #announcements · 12m 0s").assertIsDisplayed()
            onNodeWithText("#eng:#7 · 12m 0s").assertIsDisplayed()
            onNodeWithText("holds no conversation · can also post in #announcements · 12m 0s").assertIsDisplayed()

            // Stop dialog for 0 full holdings says nothing goes back.
            onNodeWithTag(membersAgentWorkerStopTag("w3")).performClick()
            waitForIdle()
            onNodeWithText("Stop w3?").assertIsDisplayed()
            onNodeWithText("It holds no conversation, so nothing goes back to @tenny.").assertIsDisplayed()
            onNodeWithTag(MembersAgentWorkerStopConfirmTag).performClick()
            waitForIdle()

            // Stop dialog for multiple full holdings lists all of them.
            onNodeWithTag(membersAgentWorkerStopTag("w1")).performClick()
            waitForIdle()
            onNodeWithText("Stop w1?").assertIsDisplayed()
            onNodeWithText("#eng:#7 and #eng:5be3e1aa go back to @tenny.").assertIsDisplayed()
            onNodeWithTag(MembersAgentWorkerStopConfirmTag).performClick()
            waitForIdle()

            assertEquals(
                listOf<AgentWorkersIntent>(
                    AgentWorkersIntent.Stop(agentId, "w3", emptyList()),
                    AgentWorkersIntent.Stop(agentId, "w1", listOf("#eng:#7", "#eng:5be3e1aa")),
                ),
                intents,
            )
        }

    @Test
    fun stopFeedbackMessagesMatchWebCopy() =
        runRobolectricComposeUiTest {
            val rendered = mutableListOf<String>()
            val cases =
                listOf(
                    AgentWorkerStopFeedback.Stopped("w3", emptyList()),
                    AgentWorkerStopFeedback.Stopped("w1", "#eng:#7"),
                    AgentWorkerStopFeedback.Stopped("w1", listOf("#eng:#7", "#eng:5be3e1aa")),
                    AgentWorkerStopFeedback.NotRunning("w1"),
                    AgentWorkerStopFeedback.ComputerOffline(ScreenError("computer_offline", serverCode = "computer_offline")),
                    AgentWorkerStopFeedback.SnapshotStale(ScreenError("workers_snapshot_stale", serverCode = "workers_snapshot_stale")),
                    AgentWorkerStopFeedback.UpgradeRequired(ScreenError("computer_upgrade_required", serverCode = "computer_upgrade_required")),
                    AgentWorkerStopFeedback.Failed("w1", "#eng:#7", ScreenError("http_500")),
                )
            setContent {
                RaftDesignSystemTheme {
                    rendered.clear()
                    cases.forEach { rendered += membersAgentWorkerStopFeedbackMessage(it, "tenny") }
                }
            }
            waitForIdle()
            assertEquals(
                listOf(
                    "Stopped w3.",
                    "Stopped w1. #eng:#7 is back with @tenny.",
                    "Stopped w1. #eng:#7 and #eng:5be3e1aa are back with @tenny.",
                    "w1 had already ended.",
                    "This agent's Computer is offline.",
                    "Nothing was stopped: this agent's Computer hasn't reported its Workers for a while. Check that the Computer is online, then try again.",
                    "Upgrade this agent's Computer to stop a Worker.",
                    "Couldn't stop w1.",
                ),
                rendered,
            )
        }
}
