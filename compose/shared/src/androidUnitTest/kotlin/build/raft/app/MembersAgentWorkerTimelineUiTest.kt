package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
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
import build.raft.app.data.AgentRecentWorkerRow
import build.raft.app.data.AgentWorkerDeliverySender
import build.raft.app.data.AgentWorkerDeliveryVia
import build.raft.app.data.AgentWorkerEndReason
import build.raft.app.data.AgentWorkerEventKind
import build.raft.app.data.AgentWorkerEventTokens
import build.raft.app.data.AgentWorkerObjectKind
import build.raft.app.data.AgentWorkerOpenTimeline
import build.raft.app.data.AgentWorkerQuietDoing
import build.raft.app.data.AgentWorkerReportKind
import build.raft.app.data.AgentWorkerRow
import build.raft.app.data.AgentWorkerSettledBy
import build.raft.app.data.AgentWorkerSettledHow
import build.raft.app.data.AgentWorkerState
import build.raft.app.data.AgentWorkerTimelineEvent
import build.raft.app.data.AgentWorkerTimelineEventData
import build.raft.app.data.AgentWorkerTimelineLoadStatus
import build.raft.app.data.AgentWorkerTimelineSnapshot
import build.raft.app.data.AgentWorkerTimelineState
import build.raft.app.data.AgentWorkerTimelineStore
import build.raft.app.data.AgentWorkerTurnOutcome
import build.raft.app.data.AgentWorkersLoadStatus
import build.raft.app.data.AgentWorkersState
import build.raft.app.data.DataResult
import build.raft.app.data.ScreenError
import build.raft.app.model.MemberId
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.RaftTimeFormat24Hour
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Worker v1 PR 3 (`docs/contracts/worker-events.md`, web `agentWorkersObservability.behavior.test.tsx`):
 * Recently ended (7 days) & Worker Timeline Detail on a real Robolectric Compose composition.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h2400dp")
class MembersAgentWorkerTimelineUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val agentId = MemberId("agent-1")
    private val nowMillis = checkNotNull(parseRaftTimestampEpochMillisOrNull("2026-11-25T10:12:00.000Z"))
    private val timeContext =
        RaftTimeContext(
            localOffsetMinutes = 0,
            todayEpochDay = 20_782L,
            nowEpochMillis = nowMillis,
            timeFormat = RaftTimeFormat24Hour,
        )

    private val recentRows =
        listOf(
            AgentRecentWorkerRow(
                launchId = "worker-w2-run",
                worker = "w2",
                objectRef = "#eng:#9",
                objectKind = AgentWorkerObjectKind.Task,
                startedAt = "2026-11-25T09:00:00.000Z",
                endedAt = null,
                reason = null,
                handedBack = null,
            ),
            AgentRecentWorkerRow(
                launchId = "worker-w1-old",
                worker = "w1",
                objectRef = "#eng:#7",
                objectKind = AgentWorkerObjectKind.Task,
                startedAt = "2026-11-25T08:00:00.000Z",
                endedAt = "2026-11-25T08:47:00.000Z",
                reason = AgentWorkerEndReason.Done,
                handedBack = 1,
                noteFirstLine = "PR opened, waiting on @warden.",
            ),
            AgentRecentWorkerRow(
                launchId = "worker-w3-old",
                worker = "w3",
                objectRef = "#ops:5be3e1aa",
                objectKind = AgentWorkerObjectKind.Thread,
                startedAt = "2026-11-24T08:00:00.000Z",
                endedAt = "2026-11-24T09:00:00.000Z",
                reason = AgentWorkerEndReason.Expired,
                handedBack = 0,
            ),
            AgentRecentWorkerRow(
                launchId = "worker-w6-old",
                worker = "w6",
                objectRef = "#eng:#4",
                objectKind = AgentWorkerObjectKind.Task,
                startedAt = "2026-11-23T08:00:00.000Z",
                endedAt = "2026-11-23T08:31:00.000Z",
                reason = AgentWorkerEndReason.WaitingOn,
                handedBack = 0,
                waitingOn = "warden",
            ),
        )

    private val timelineEvents =
        listOf(
            AgentWorkerTimelineEvent(
                seq = 1,
                kind = AgentWorkerEventKind.HandedOut,
                occurredAt = "2026-11-25T08:00:00.000Z",
                data = AgentWorkerTimelineEventData.HandedOut(
                    objectRef = "#eng:#7",
                    objectKind = AgentWorkerObjectKind.Task,
                    model = "claude-opus-5-5",
                ),
            ),
            AgentWorkerTimelineEvent(
                seq = 2,
                kind = AgentWorkerEventKind.Delivered,
                occurredAt = "2026-11-25T08:00:01.000Z",
                data = AgentWorkerTimelineEventData.Delivered(
                    messageId = "msg11111-aaaa",
                    sender = AgentWorkerDeliverySender.Human,
                    via = AgentWorkerDeliveryVia.NewTurn,
                ),
            ),
            AgentWorkerTimelineEvent(
                seq = 3,
                kind = AgentWorkerEventKind.TurnStarted,
                occurredAt = "2026-11-25T08:00:02.000Z",
                data = AgentWorkerTimelineEventData.TurnStarted(turn = 1, inputs = listOf("msg11111-aaaa")),
            ),
            AgentWorkerTimelineEvent(
                seq = 4,
                kind = AgentWorkerEventKind.TurnEnded,
                occurredAt = "2026-11-25T08:05:00.000Z",
                data = AgentWorkerTimelineEventData.TurnEnded(
                    turn = 1,
                    outcome = AgentWorkerTurnOutcome.Completed,
                    durationMs = 5 * 60_000L,
                    toolCalls = 7,
                    sent = listOf("msg22222-bbbb"),
                    tokens = AgentWorkerEventTokens(input = 1000L, output = 234L),
                ),
            ),
            AgentWorkerTimelineEvent(
                seq = 5,
                kind = AgentWorkerEventKind.TurnStarted,
                occurredAt = "2026-11-25T08:06:00.000Z",
                data = AgentWorkerTimelineEventData.TurnStarted(turn = 2),
            ),
            AgentWorkerTimelineEvent(
                seq = 6,
                kind = AgentWorkerEventKind.Quiet,
                occurredAt = "2026-11-25T08:20:00.000Z",
                data = AgentWorkerTimelineEventData.Quiet(
                    doing = AgentWorkerQuietDoing.Model,
                    quietSinceAt = "2026-11-25T08:10:00.000Z",
                ),
            ),
            AgentWorkerTimelineEvent(
                seq = 7,
                kind = AgentWorkerEventKind.Reported,
                occurredAt = "2026-11-25T08:21:00.000Z",
                data = AgentWorkerTimelineEventData.Reported(reportKind = AgentWorkerReportKind.NeedsParent),
            ),
            AgentWorkerTimelineEvent(
                seq = 8,
                kind = AgentWorkerEventKind.Ended,
                occurredAt = "2026-11-25T08:47:00.000Z",
                data = AgentWorkerTimelineEventData.Ended(
                    reason = AgentWorkerEndReason.Done,
                    handedBack = 1,
                    durationMs = 47 * 60_000L,
                    note = "PR opened.",
                ),
            ),
        )

    @Composable
    private fun TimelineTestShell(content: @Composable () -> Unit) {
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
    fun recentlyEndedGroupRendersEndedLaunchesOnlyAndInvokesOpenTimeline() =
        runRobolectricComposeUiTest {
            val opened = mutableListOf<AgentWorkerOpenTimeline>()
            val stateFlow =
                MutableStateFlow(
                    AgentWorkersState(
                        agentId = agentId,
                        enabled = true,
                        status = AgentWorkersLoadStatus.Known,
                        workers = emptyList(),
                        recentWorkers = recentRows,
                        recentLoaded = true,
                        nowMillis = nowMillis,
                    )
                )
            val binding =
                MembersAgentWorkersBinding(
                    state = stateFlow,
                    parentHandle = "tenny",
                    canStop = false,
                    computerOffline = false,
                    onIntent = {},
                    onOpenTimeline = { opened += it },
                )
            setContent {
                TimelineTestShell {
                    MembersAgentWorkersList(
                        agentId = agentId,
                        workers = binding,
                        groups = emptyList(),
                        timeContext = timeContext,
                        onOpenSubagent = {},
                        contentPadding = PaddingValues(16.dp),
                        modifier = Modifier.fillMaxSize(),
                        nowEpochMillis = { nowMillis },
                    )
                }
            }
            waitForIdle()
            onNodeWithTag(MembersAgentWorkersRecentGroupTag).assertIsDisplayed()
            onNodeWithText("Recently ended").assertIsDisplayed()
            onNodeWithText("Workers that ended in the last 7 days.").assertIsDisplayed()
            // Running launch stays out of Recently ended.
            onAllNodesWithTag(membersAgentRecentWorkerRowTag("worker-w2-run")).assertCountEquals(0)
            onNodeWithTag(membersAgentRecentWorkerRowTag("worker-w1-old")).assertIsDisplayed()
            onNodeWithText("#eng:#7 · finished · ran 47m · 1 handed back").assertIsDisplayed()
            onNodeWithText("#ops:5be3e1aa · expired · ran 1h 0m").assertIsDisplayed()
            onNodeWithText("#eng:#4 · paused, waiting on @warden · ran 31m").assertIsDisplayed()

            onNodeWithTag(membersAgentRecentWorkerRowTag("worker-w1-old")).performClick()
            waitForIdle()
            assertEquals(
                listOf(AgentWorkerOpenTimeline(launchId = "worker-w1-old", worker = "w1", objectRef = "#eng:#7")),
                opened,
            )
        }

    @Test
    fun tappingRecentRowPushesTimelineWithFoldedTurnsViewMessageAndBackReturns() =
        runRobolectricComposeUiTest {
            val detail =
                AgentDetailSnapshot(
                    agentId = agentId,
                    directMessages = emptyList(),
                    reminders = emptyList(),
                    activity = emptyList(),
                )
            val workersStateFlow =
                MutableStateFlow(
                    AgentWorkersState(
                        agentId = agentId,
                        enabled = true,
                        status = AgentWorkersLoadStatus.Known,
                        workers = emptyList(),
                        recentWorkers = recentRows,
                        recentLoaded = true,
                        nowMillis = nowMillis,
                    )
                )
            val openedMessages = mutableListOf<String>()
            setContent {
                val scope = rememberCoroutineScope()
                val timelineStore =
                    remember(scope) {
                        AgentWorkerTimelineStore(
                            getTimeline = { _, launchId, _ ->
                                DataResult.Content(AgentWorkerTimelineSnapshot(launchId, timelineEvents))
                            },
                            scope = scope,
                        )
                    }
                val binding =
                    remember(timelineStore) {
                        MembersAgentWorkersBinding(
                            state = workersStateFlow,
                            parentHandle = "tenny",
                            canStop = true,
                            computerOffline = false,
                            onIntent = {},
                            timelineState = timelineStore.state,
                            onTimelineIntent = timelineStore::dispatch,
                            onOpenTimelineMessage = { openedMessages += it },
                        )
                    }
                TimelineTestShell {
                    MembersAgentActivityTab(
                        detail = detail,
                        serverId = "server-1",
                        agentStatusIndicatorState = null,
                        loadState = MembersAgentActivityLoadState.Idle,
                        diagnosticActionState = null,
                        onCopyDiagnostics = {},
                        timeContext = timeContext,
                        onOpenUrl = {},
                        modifier = Modifier.fillMaxSize(),
                        autoScrollToLatest = false,
                        workers = binding,
                    )
                }
            }
            waitForIdle()
            onNodeWithText("Workers").performClick()
            waitForIdle()
            onNodeWithTag(membersAgentRecentWorkerRowTag("worker-w1-old")).performClick()
            waitForIdle()

            onNodeWithTag(MembersAgentWorkerTimelineTag).assertIsDisplayed()
            onNodeWithTag(MembersAgentWorkerTimelineStateTag).assertIsDisplayed()
            onNodeWithText("Completed").assertIsDisplayed()
            onAllNodesWithTag(membersAgentWorkerStopTag("w1")).assertCountEquals(0)

            // 6 visible events: turn 1 start folded into turn 1 end, needs_parent omitted.
            onNodeWithTag(membersAgentWorkerTimelineEventTag(1)).assertIsDisplayed()
            onNodeWithText("Handed out by @tenny").assertIsDisplayed()
            onNodeWithTag(membersAgentWorkerTimelineEventTag(2)).assertIsDisplayed()
            onNodeWithText("Got a message from a person").assertIsDisplayed()
            onAllNodesWithTag(membersAgentWorkerTimelineEventTag(3)).assertCountEquals(0)
            onNodeWithTag(membersAgentWorkerTimelineEventTag(4)).assertIsDisplayed()
            onNodeWithText("Turn 1 · 5m 0s · 7 tool calls").assertIsDisplayed()
            onNodeWithText("1 message sent · 1.2K tokens").assertIsDisplayed()
            onNodeWithTag(membersAgentWorkerTimelineEventTag(5)).assertIsDisplayed()
            onNodeWithText("Turn 2 · running").assertIsDisplayed()
            onNodeWithTag(membersAgentWorkerTimelineEventTag(6)).assertIsDisplayed()
            onNodeWithText("No activity for 10m").assertIsDisplayed()
            onNodeWithText("in the model").assertIsDisplayed()
            onAllNodesWithTag(membersAgentWorkerTimelineEventTag(7)).assertCountEquals(0)
            onNodeWithTag(membersAgentWorkerTimelineEventTag(8)).assertIsDisplayed()
            onNodeWithText("Finished · ran 47m").assertIsDisplayed()
            onNodeWithText("PR opened. · 1 handed back").assertIsDisplayed()
            onNodeWithText(
                "Kept for 7 days. Message text and tool input and output are never recorded; the Worker's progress lines and ending note are."
            ).assertIsDisplayed()

            onNodeWithTag(membersAgentWorkerTimelineViewMessageTag("msg11111-aaaa")).performClick()
            waitForIdle()
            assertEquals(listOf("msg11111-aaaa"), openedMessages)

            onNodeWithTag(MembersAgentWorkerTimelineBackTag).performClick()
            waitForIdle()
            onAllNodesWithTag(MembersAgentWorkerTimelineTag).assertCountEquals(0)
            onNodeWithTag(MembersAgentWorkersRecentGroupTag).assertIsDisplayed()
        }

    @Test
    fun liveRowOpensTimelineWithWorkingChipAndStopWhileRowStopStaysSeparate() =
        runRobolectricComposeUiTest {
            val liveRow =
                AgentWorkerRow(
                    agentId = agentId,
                    worker = "w2",
                    objectRef = "#eng:#9",
                    objectKind = AgentWorkerObjectKind.Task,
                    since = "2026-11-25T10:00:00.000Z",
                    state = AgentWorkerState.Running,
                    launchId = "worker-w2-run",
                )
            val detail =
                AgentDetailSnapshot(
                    agentId = agentId,
                    directMessages = emptyList(),
                    reminders = emptyList(),
                    activity = emptyList(),
                )
            val workersStateFlow =
                MutableStateFlow(
                    AgentWorkersState(
                        agentId = agentId,
                        enabled = true,
                        status = AgentWorkersLoadStatus.Known,
                        workers = listOf(liveRow),
                        recentWorkers = emptyList(),
                        recentLoaded = true,
                        nowMillis = nowMillis,
                    )
                )
            setContent {
                val scope = rememberCoroutineScope()
                val timelineStore =
                    remember(scope) {
                        AgentWorkerTimelineStore(
                            getTimeline = { _, launchId, _ ->
                                DataResult.Content(AgentWorkerTimelineSnapshot(launchId, timelineEvents))
                            },
                            scope = scope,
                        )
                    }
                val binding =
                    remember(timelineStore) {
                        MembersAgentWorkersBinding(
                            state = workersStateFlow,
                            parentHandle = "tenny",
                            canStop = true,
                            computerOffline = false,
                            onIntent = {},
                            timelineState = timelineStore.state,
                            onTimelineIntent = timelineStore::dispatch,
                        )
                    }
                TimelineTestShell {
                    MembersAgentActivityTab(
                        detail = detail,
                        serverId = "server-1",
                        agentStatusIndicatorState = null,
                        loadState = MembersAgentActivityLoadState.Idle,
                        diagnosticActionState = null,
                        onCopyDiagnostics = {},
                        timeContext = timeContext,
                        onOpenUrl = {},
                        modifier = Modifier.fillMaxSize(),
                        autoScrollToLatest = false,
                        workers = binding,
                    )
                }
            }
            waitForIdle()
            onNodeWithText("Workers").performClick()
            waitForIdle()

            // Stop button opens Stop dialog and does NOT open timeline.
            onNodeWithTag(membersAgentWorkerStopTag("w2")).performClick()
            waitForIdle()
            onNodeWithTag(MembersAgentWorkerStopDialogTag).assertIsDisplayed()
            onAllNodesWithTag(MembersAgentWorkerTimelineTag).assertCountEquals(0)
            onNodeWithText("Cancel").performClick()
            waitForIdle()

            // Tapping the live row opens its timeline with Working chip + header Stop control.
            onNodeWithTag(membersAgentWorkerRowTag("w2")).performClick()
            waitForIdle()
            onNodeWithTag(MembersAgentWorkerTimelineTag).assertIsDisplayed()
            onNodeWithText("Working").assertIsDisplayed()
            onNodeWithTag(membersAgentWorkerStopTag("w2")).assertIsDisplayed()
        }

    @Test
    fun timelineUnavailableAndServerErrorRenderTraceIdAndRetry() =
        runRobolectricComposeUiTest {
            val open = AgentWorkerOpenTimeline(launchId = "worker-w1-old", worker = "w1", objectRef = "#eng:#7")
            val traceId = "0123456789abcdef0123456789abcdef"
            var retries = 0
            val timelineState =
                MutableStateFlow(
                    AgentWorkerTimelineState(
                        agentId = agentId,
                        open = open,
                        status = AgentWorkerTimelineLoadStatus.Unavailable,
                    )
                )
            setContent {
                val state by timelineState.collectAsState()
                TimelineTestShell {
                    MembersAgentWorkerTimelineDetail(
                        agentId = agentId,
                        open = open,
                        parentHandle = "tenny",
                        live = null,
                        ended = null,
                        timelineState = state,
                        nowMillis = nowMillis,
                        stale = false,
                        canStop = false,
                        computerOffline = false,
                        stoppingWorker = null,
                        timeContext = timeContext,
                        horizontalInset = 16.dp,
                        onBack = {},
                        onRefresh = { retries += 1 },
                        onWorkersIntent = {},
                        onOpenMessage = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            waitForIdle()
            onNodeWithTag(MembersAgentWorkerTimelineUnavailableTag).assertIsDisplayed()
            onNodeWithText("Can't open this Worker's timeline").assertIsDisplayed()
            onAllNodesWithTag(MembersAgentWorkerTimelineRetryTag).assertCountEquals(0)

            // Server error shows Retry + RaftTraceIdNote.
            timelineState.value =
                AgentWorkerTimelineState(
                    agentId = agentId,
                    open = open,
                    status = AgentWorkerTimelineLoadStatus.Error,
                    error = ScreenError(code = "http_500", retryable = true, traceId = traceId),
                )
            waitForIdle()
            onNodeWithText("Can't open this Worker's timeline").assertIsDisplayed()
            onNodeWithTag(RaftTraceIdNoteTestTag).assertIsDisplayed()
            onNodeWithTag(MembersAgentWorkerTimelineRetryTag).performClick()
            waitForIdle()
            assertEquals(1, retries)
        }

    @Test
    fun timelineWordingMatchesWebForDeliveriesReportsSettledAndEndedReasons() =
        runRobolectricComposeUiTest {
            val open = AgentWorkerOpenTimeline(launchId = "worker-w1-old", worker = "w1", objectRef = "#eng:#7")
            val wordingEvents =
                listOf(
                    AgentWorkerTimelineEvent(
                        seq = 1,
                        kind = AgentWorkerEventKind.Delivered,
                        occurredAt = "2026-11-25T08:00:01.000Z",
                        data = AgentWorkerTimelineEventData.Delivered(
                            messageId = "msg33333-cccc",
                            sender = AgentWorkerDeliverySender.System,
                            via = AgentWorkerDeliveryVia.Steered,
                        ),
                    ),
                    AgentWorkerTimelineEvent(
                        seq = 2,
                        kind = AgentWorkerEventKind.Delivered,
                        occurredAt = "2026-11-25T08:00:02.000Z",
                        data = AgentWorkerTimelineEventData.Delivered(
                            messageId = "msg44444-dddd",
                            sender = AgentWorkerDeliverySender.Agent,
                            via = AgentWorkerDeliveryVia.Queued,
                        ),
                    ),
                    AgentWorkerTimelineEvent(
                        seq = 3,
                        kind = AgentWorkerEventKind.Reported,
                        occurredAt = "2026-11-25T08:01:00.000Z",
                        data = AgentWorkerTimelineEventData.Reported(reportKind = AgentWorkerReportKind.OutOfScope),
                    ),
                    AgentWorkerTimelineEvent(
                        seq = 4,
                        kind = AgentWorkerEventKind.HumanSettled,
                        occurredAt = "2026-11-25T08:05:00.000Z",
                        data = AgentWorkerTimelineEventData.HumanSettled(
                            messageId = "msg33333-cccc",
                            by = AgentWorkerSettledBy.Parent,
                            how = AgentWorkerSettledHow.Reacted,
                            waitedMs = 5 * 60_000L,
                        ),
                    ),
                    AgentWorkerTimelineEvent(
                        seq = 5,
                        kind = AgentWorkerEventKind.HumanSettled,
                        occurredAt = "2026-11-25T08:06:00.000Z",
                        data = AgentWorkerTimelineEventData.HumanSettled(
                            messageId = "msg44444-dddd",
                            by = AgentWorkerSettledBy.Worker,
                            how = AgentWorkerSettledHow.Replied,
                            waitedMs = 3 * 60_000L,
                        ),
                    ),
                    AgentWorkerTimelineEvent(
                        seq = 6,
                        kind = AgentWorkerEventKind.HumanSettled,
                        occurredAt = "2026-11-25T08:07:00.000Z",
                        data = AgentWorkerTimelineEventData.HumanSettled(
                            messageId = "msg44444-dddd",
                            by = null,
                            how = AgentWorkerSettledHow.AgentLeft,
                            waitedMs = 7 * 60_000L,
                        ),
                    ),
                    AgentWorkerTimelineEvent(
                        seq = 7,
                        kind = AgentWorkerEventKind.Ended,
                        occurredAt = "2026-11-25T08:31:00.000Z",
                        data = AgentWorkerTimelineEventData.Ended(
                            reason = AgentWorkerEndReason.WaitingOn,
                            waitingOn = "warden",
                            handedBack = 0,
                            durationMs = 31 * 60_000L,
                        ),
                    ),
                )
            setContent {
                TimelineTestShell {
                    MembersAgentWorkerTimelineDetail(
                        agentId = agentId,
                        open = open,
                        parentHandle = "tenny",
                        live = null,
                        ended = null,
                        timelineState =
                            AgentWorkerTimelineState(
                                agentId = agentId,
                                open = open,
                                status = AgentWorkerTimelineLoadStatus.Loaded,
                                events = wordingEvents,
                            ),
                        nowMillis = nowMillis,
                        stale = false,
                        canStop = false,
                        computerOffline = false,
                        stoppingWorker = null,
                        timeContext = timeContext,
                        horizontalInset = 16.dp,
                        onBack = {},
                        onRefresh = {},
                        onWorkersIntent = {},
                        onOpenMessage = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            waitForIdle()
            onNodeWithText("Got a system notice").assertIsDisplayed()
            onNodeWithText("added to the running turn ·").assertIsDisplayed()
            onNodeWithText("Got a message from an agent").assertIsDisplayed()
            onNodeWithText("queued ·").assertIsDisplayed()
            onNodeWithText("Told @tenny this is out of scope").assertIsDisplayed()
            onNodeWithText("@tenny reacted after 5m").assertIsDisplayed()
            onNodeWithText("w1 answered after 3m").assertIsDisplayed()
            onNodeWithText("@tenny left the channel after 7m").assertIsDisplayed()
            onNodeWithText("Paused, waiting on @warden · ran 31m").assertIsDisplayed()
            onNodeWithText("no note").assertIsDisplayed()
        }

    @Test
    fun flagOffKeepsLegacySubagentsWithoutRecentWorkersOrTimeline() =
        runRobolectricComposeUiTest {
            val detail =
                AgentDetailSnapshot(
                    agentId = agentId,
                    directMessages = emptyList(),
                    reminders = emptyList(),
                    activity = subagentsFixtureLog(),
                )
            setContent {
                TimelineTestShell {
                    MembersAgentActivityTab(
                        detail = detail,
                        serverId = "server-1",
                        agentStatusIndicatorState = null,
                        loadState = MembersAgentActivityLoadState.Idle,
                        diagnosticActionState = null,
                        onCopyDiagnostics = {},
                        timeContext = timeContext,
                        onOpenUrl = {},
                        modifier = Modifier.fillMaxSize(),
                        autoScrollToLatest = false,
                        workers = null,
                    )
                }
            }
            waitForIdle()
            onNodeWithText("Subagents").performClick()
            waitForIdle()
            onNodeWithTag(MembersAgentSubagentsListTag).assertIsDisplayed()
            onAllNodesWithTag(MembersAgentWorkersRecentGroupTag).assertCountEquals(0)
            onAllNodesWithTag(MembersAgentWorkerTimelineTag).assertCountEquals(0)
            onAllNodesWithText("Recently ended").assertCountEquals(0)
        }
}
