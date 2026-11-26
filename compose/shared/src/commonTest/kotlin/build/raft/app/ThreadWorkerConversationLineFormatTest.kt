package build.raft.app

import build.raft.app.data.HeldConversationWorkerState
import build.raft.app.data.WORKER_HUMAN_WAITING_AFTER_MS
import build.raft.app.data.WORKER_QUIET_AFTER_MS
import build.raft.app.data.WorkerConversationTarget
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationWorkerStateKind
import build.raft.app.model.ConversationWorkerStateUpdated
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.MessageId
import build.raft.app.model.Presence
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThreadWorkerConversationLineFormatTest {
    private val enStrings =
        WorkerLineStrings(
            spanMinutes = "{minutes}m",
            spanHours = "{hours}h {minutes}m",
            spanDays = "{days}d {hours}h",
            handling = "Worker {worker} of @{agent} is handling this",
            handlingAnonymous = "A Worker of @{agent} is handling this",
            handlingUnknown = "A Worker is handling this",
            unknownAgentRow = "A Worker of an agent here",
            whoUnknown = "A Worker",
            handedBackUnknown = "A message went back to its agent",
            who = "Worker {worker} of @{agent}",
            whoAnonymous = "A Worker of @{agent}",
            running = "running {tool}",
            waiting = "@{person} waiting {span}",
            someoneWaiting = "someone waiting {span}",
            handedBack = "@{person}'s message went back to @{agent}",
            handedBackSomeone = "A message went back to @{agent}",
            waitingFor = "waiting {span}",
            noActivity = "no activity for {span}",
            inTool = "in {tool} for {span}",
            combined = "{count} Workers are handling this",
            popoverTitle = "Workers handling this conversation",
        )

    private val baseIso = "2026-11-26T10:00:00.000Z"
    private val baseMs = parseRaftTimestampEpochMillisOrNull(baseIso)!!

    private fun held(
        agentId: String = "agent-sage",
        worker: String? = "w3",
        state: ConversationWorkerStateKind = ConversationWorkerStateKind.Handling,
        sinceAt: String = baseIso,
        waitingSinceAt: String? = null,
        waitingFrom: String? = null,
        doing: String? = null,
        tool: String? = null,
        quietSinceAt: String? = null,
        stateSeenAtMs: Long = baseMs,
        doingSeenAtMs: Long = baseMs,
        receivedAtMs: Long = baseMs,
    ): HeldConversationWorkerState =
        HeldConversationWorkerState(
            state =
                ConversationWorkerStateUpdated(
                    channelId = ChannelId("ch-1"),
                    parentMessageId = MessageId("msg-root"),
                    agentId = MemberId(agentId),
                    worker = worker,
                    state = state,
                    sinceAt = sinceAt,
                    waitingSinceAt = waitingSinceAt,
                    waitingFrom = waitingFrom,
                    doing = doing,
                    tool = tool,
                    quietSinceAt = quietSinceAt,
                ),
            stateSeenAtMs = stateSeenAtMs,
            doingSeenAtMs = doingSeenAtMs,
            receivedAtMs = receivedAtMs,
        )

    @Test
    fun formatWorkerSpanMinutesHoursAndDays() {
        assertEquals("1m", formatWorkerSpan(enStrings, 0L))
        assertEquals("1m", formatWorkerSpan(enStrings, 45_000L))
        assertEquals("12m", formatWorkerSpan(enStrings, 12 * 60_000L))
        assertEquals("1h 0m", formatWorkerSpan(enStrings, 60 * 60_000L))
        assertEquals("2h 5m", formatWorkerSpan(enStrings, (2 * 60 + 5) * 60_000L))
        assertEquals("1d 1h", formatWorkerSpan(enStrings, 25 * 60 * 60_000L))
    }

    @Test
    fun formatConversationWorkerLineHandlingNamedAnonymousUnnamedAndReduced() {
        val nowMs = baseMs + 12 * 60_000L
        val named =
            assertNotNull(
                formatConversationWorkerLine(
                    enStrings,
                    held(doing = "tool", tool = "Bash", doingSeenAtMs = nowMs - 60_000L),
                    agentHandle = "Sage",
                    nowMs = nowMs,
                    reduced = false,
                ),
            )
        assertEquals("Worker w3 of @Sage is handling this", named.who)
        assertEquals("12m · running Bash", named.tail)
        assertEquals(WorkerLineTone.Default, named.tone)

        // Reduced live-activity mode keeps the line visible while hiding tool names.
        val reduced =
            assertNotNull(
                formatConversationWorkerLine(
                    enStrings,
                    held(doing = "tool", tool = "Bash", doingSeenAtMs = nowMs - 60_000L),
                    agentHandle = "Sage",
                    nowMs = nowMs,
                    reduced = true,
                ),
            )
        assertEquals("Worker w3 of @Sage is handling this", reduced.who)
        assertEquals("12m", reduced.tail)

        // Joint channel (anonymous worker name) and unnamed agent.
        val anonymous =
            assertNotNull(
                formatConversationWorkerLine(enStrings, held(worker = null), agentHandle = "Sage", nowMs = nowMs),
            )
        assertEquals("A Worker of @Sage is handling this", anonymous.who)
        assertEquals("12m", anonymous.tail)

        val unnamed =
            assertNotNull(
                formatConversationWorkerLine(enStrings, held(worker = "w3"), agentHandle = null, nowMs = nowMs),
            )
        assertEquals("A Worker is handling this", unnamed.who)
        assertEquals("12m", unnamed.tail)
    }

    @Test
    fun formatConversationWorkerLineLongToolTenMinutesAndReducedFallback() {
        val nowMs = baseMs + 15 * 60_000L
        val longTool =
            assertNotNull(
                formatConversationWorkerLine(
                    enStrings,
                    held(doing = "tool", tool = "Bash", quietSinceAt = "2026-11-26T10:03:00.000Z"),
                    agentHandle = "Sage",
                    nowMs = nowMs,
                    reduced = false,
                ),
            )
        assertEquals("Worker w3 of @Sage", longTool.who)
        assertEquals("in Bash for 12m", longTool.tail)
        assertEquals(WorkerLineTone.Default, longTool.tone)

        // Reduced mode suppresses long-tool naming and shows the working line instead.
        val reduced =
            assertNotNull(
                formatConversationWorkerLine(
                    enStrings,
                    held(doing = "tool", tool = "Bash", quietSinceAt = "2026-11-26T10:03:00.000Z"),
                    agentHandle = "Sage",
                    nowMs = nowMs,
                    reduced = true,
                ),
            )
        assertEquals("Worker w3 of @Sage is handling this", reduced.who)
        assertEquals("15m", reduced.tail)

        // Unnamed agent also falls back to working line instead of exposing tool.
        val unnamed =
            assertNotNull(
                formatConversationWorkerLine(
                    enStrings,
                    held(doing = "tool", tool = "Bash", quietSinceAt = "2026-11-26T10:03:00.000Z"),
                    agentHandle = null,
                    nowMs = nowMs,
                    reduced = false,
                ),
            )
        assertEquals("A Worker is handling this", unnamed.who)
    }

    @Test
    fun formatConversationWorkerLineWaitingBeforeAndAfterTwoMinutes() {
        val earlyNow = baseMs + WORKER_HUMAN_WAITING_AFTER_MS - 30_000L
        val early =
            assertNotNull(
                formatConversationWorkerLine(
                    enStrings,
                    held(state = ConversationWorkerStateKind.Waiting, waitingSinceAt = baseIso, waitingFrom = "alice"),
                    agentHandle = "Sage",
                    nowMs = earlyNow,
                ),
            )
        assertEquals("@alice waiting 1m", early.tail)
        assertEquals(WorkerLineTone.Default, early.tone)
        assertEquals(0L, early.urgency)

        val overdueNow = baseMs + 3 * 60_000L
        val overdue =
            assertNotNull(
                formatConversationWorkerLine(
                    enStrings,
                    held(state = ConversationWorkerStateKind.Waiting, waitingSinceAt = baseIso, waitingFrom = null),
                    agentHandle = "Sage",
                    nowMs = overdueNow,
                ),
            )
        assertEquals("someone waiting 3m", overdue.tail)
        assertEquals(WorkerLineTone.Warning, overdue.tone)
        assertEquals(WorkerLineScope.Tail, overdue.scope)
        assertTrue(overdue.urgency > 2_000_000_000L)
    }

    @Test
    fun formatConversationWorkerLineHandedBackNamedSomeoneAndUnnamed() {
        val nowMs = baseMs + 4 * 60_000L
        val named =
            assertNotNull(
                formatConversationWorkerLine(
                    enStrings,
                    held(state = ConversationWorkerStateKind.HandedBack, waitingSinceAt = baseIso, waitingFrom = "alice"),
                    agentHandle = "Sage",
                    nowMs = nowMs,
                ),
            )
        assertEquals("@alice's message went back to @Sage", named.who)
        assertEquals("waiting 4m", named.tail)
        assertEquals(WorkerLineTone.Warning, named.tone)
        assertEquals(WorkerLineScope.All, named.scope)

        val someone =
            assertNotNull(
                formatConversationWorkerLine(
                    enStrings,
                    held(state = ConversationWorkerStateKind.HandedBack, waitingSinceAt = baseIso, waitingFrom = null),
                    agentHandle = "Sage",
                    nowMs = nowMs,
                ),
            )
        assertEquals("A message went back to @Sage", someone.who)

        val unnamed =
            assertNotNull(
                formatConversationWorkerLine(
                    enStrings,
                    held(state = ConversationWorkerStateKind.HandedBack, waitingSinceAt = baseIso, waitingFrom = "alice"),
                    agentHandle = null,
                    nowMs = nowMs,
                ),
            )
        assertEquals("A message went back to its agent", unnamed.who)
        assertEquals(WorkerLineScope.All, unnamed.scope)
    }

    @Test
    fun formatConversationWorkerLineStalledWithQuietSinceAtCountedAndAnonymousFallback() {
        val nowMs = baseMs + 14 * 60_000L
        val withQuiet =
            assertNotNull(
                formatConversationWorkerLine(
                    enStrings,
                    held(state = ConversationWorkerStateKind.Stalled, quietSinceAt = "2026-11-26T10:02:00.000Z"),
                    agentHandle = "Sage",
                    nowMs = nowMs,
                ),
            )
        assertEquals("Worker w3 of @Sage", withQuiet.who)
        assertEquals("no activity for 12m", withQuiet.tail)
        assertEquals(WorkerLineTone.Warning, withQuiet.tone)

        // Without quietSinceAt but with worker name: counts 10m + time since stateSeenAtMs.
        val counted =
            assertNotNull(
                formatConversationWorkerLine(
                    enStrings,
                    held(
                        state = ConversationWorkerStateKind.Stalled,
                        worker = "w3",
                        quietSinceAt = null,
                        stateSeenAtMs = nowMs - 2 * 60_000L,
                    ),
                    agentHandle = null,
                    nowMs = nowMs,
                ),
            )
        assertEquals("A Worker", counted.who)
        assertEquals("no activity for 12m", counted.tail)
        assertEquals(WorkerLineTone.Warning, counted.tone)

        // Without worker name AND without quietSinceAt: falls back to working line.
        val fallback =
            assertNotNull(
                formatConversationWorkerLine(
                    enStrings,
                    held(state = ConversationWorkerStateKind.Stalled, worker = null, quietSinceAt = null),
                    agentHandle = "Sage",
                    nowMs = nowMs,
                ),
            )
        assertEquals("A Worker of @Sage is handling this", fallback.who)
        assertEquals("14m", fallback.tail)
        assertEquals(WorkerLineTone.Default, fallback.tone)
    }

    @Test
    fun formatConversationWorkerLineNoneReturnsNull() {
        assertNull(
            formatConversationWorkerLine(
                enStrings,
                held(state = ConversationWorkerStateKind.None),
                agentHandle = "Sage",
                nowMs = baseMs + 60_000L,
            ),
        )
    }

    @Test
    fun combineWorkerLinesUsesLongestHeldTimeOrMostUrgentWarningTail() {
        val lineA =
            WorkerLineParts(
                who = "Worker w1 of @Sage is handling this",
                tail = "5m",
                tone = WorkerLineTone.Default,
                scope = WorkerLineScope.Tail,
                urgency = 0L,
                heldMs = 5 * 60_000L,
            )
        val lineB =
            WorkerLineParts(
                who = "Worker w2 of @Kirby is handling this",
                tail = "18m",
                tone = WorkerLineTone.Default,
                scope = WorkerLineScope.Tail,
                urgency = 0L,
                heldMs = 18 * 60_000L,
            )
        val calm = combineWorkerLines(enStrings, listOf(lineA, lineB))
        assertEquals("2 Workers are handling this", calm.who)
        assertEquals("18m", calm.tail)
        assertEquals(WorkerLineTone.Default, calm.tone)

        val stalled =
            WorkerLineParts(
                who = "Worker w1 of @Sage",
                tail = "no activity for 12m",
                tone = WorkerLineTone.Warning,
                scope = WorkerLineScope.Tail,
                urgency = 1_000_000_000L + 12 * 60_000L,
                heldMs = 15 * 60_000L,
            )
        val waiting =
            WorkerLineParts(
                who = "Worker w2 of @Kirby is handling this",
                tail = "@alice waiting 3m",
                tone = WorkerLineTone.Warning,
                scope = WorkerLineScope.Tail,
                urgency = 2_000_000_000L + 3 * 60_000L,
                heldMs = 6 * 60_000L,
            )
        val urgent = combineWorkerLines(enStrings, listOf(stalled, waiting))
        assertEquals("2 Workers are handling this", urgent.who)
        assertEquals("@alice waiting 3m", urgent.tail)
        assertEquals(WorkerLineTone.Warning, urgent.tone)
        assertEquals(WorkerLineScope.Tail, urgent.scope)
    }

    @Test
    fun buildThreadWorkerConversationUiFiltersTypingResolvesChannelBeforeServerAndHandlesUnnamed() {
        val nowMs = baseMs + 10 * 60_000L
        val channelMembers =
            listOf(
                RaftThreadMemberItem(
                    id = "agent-joint",
                    displayName = "Joint Sage",
                    handle = "joint-sage",
                    kind = "agent",
                    avatarUrl = "https://cdn.example/joint.png",
                ),
            )
        val serverMembers =
            listOf(
                MemberProfile(
                    id = MemberId("agent-local"),
                    displayName = "Kirby",
                    handle = "kirby",
                    kind = MemberKind.Agent,
                    presence = Presence.Online,
                ),
            )
        val ui =
            assertNotNull(
                buildThreadWorkerConversationUi(
                    serverId = "srv-1",
                    heldStates =
                        listOf(
                            held(agentId = "agent-joint", worker = null),
                            held(agentId = "agent-local", worker = "w2"),
                            held(agentId = "agent-unknown", worker = "w9"),
                        ),
                    typingAgentIds = setOf("agent-local"),
                    channelMembers = channelMembers,
                    serverMembers = serverMembers,
                    strings = enStrings,
                    nowMs = nowMs,
                    reduced = false,
                ),
            )
        assertEquals(listOf("agent-joint", "agent-unknown"), ui.rows.map { it.agentId })
        val jointRow = ui.rows[0]
        assertTrue(jointRow.named)
        assertEquals("Joint Sage", jointRow.agentName)
        assertEquals("A Worker of @joint-sage is handling this", jointRow.parts.who)
        val unknownRow = ui.rows[1]
        assertFalse(unknownRow.named)
        assertEquals("A Worker of an agent here", unknownRow.agentName)
        assertNull(unknownRow.worker)
        assertEquals("A Worker is handling this", unknownRow.parts.who)
        assertEquals("2 Workers are handling this", ui.line.who)
    }

    @Test
    fun workerConversationTargetMatchesReplyThreadsAndTasksOnly() {
        val reply =
            RaftThreadScreenPayload(
                instanceId = "reply-1",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                parentChannelId = "ch-parent",
                parentMessageId = "msg-root",
                streamChannelId = "ch-thread",
                showComposer = true,
                joined = true,
                channelArchived = false,
            )
        assertEquals(
            WorkerConversationTarget(channelId = "ch-parent", parentMessageId = "msg-root", taskId = null),
            reply.workerConversationTarget(taskConversationChannelId = null, taskId = null),
        )

        val task =
            RaftThreadScreenPayload(
                instanceId = "task-1",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = RaftTaskConversationChannelKind,
                streamChannelId = "ch-task-conv",
                showComposer = true,
                joined = true,
                channelArchived = false,
            )
        assertEquals(
            WorkerConversationTarget(channelId = "ch-task-conv", parentMessageId = null, taskId = "task-42"),
            task.workerConversationTarget(taskConversationChannelId = "ch-task-conv", taskId = "task-42"),
        )

        val channel =
            RaftThreadScreenPayload(
                instanceId = "ch-1",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = "public",
                streamChannelId = "ch-general",
                showComposer = true,
                joined = true,
                channelArchived = false,
            )
        assertNull(channel.workerConversationTarget(taskConversationChannelId = null, taskId = null))

        // Archived or unjoined threads never show the line.
        assertNull(reply.copy(channelArchived = true).workerConversationTarget(taskConversationChannelId = null, taskId = null))
        assertNull(reply.copy(joined = false).workerConversationTarget(taskConversationChannelId = null, taskId = null))
    }
}
