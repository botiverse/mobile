package build.raft.app

import build.raft.app.data.AgentActivityActionCount
import build.raft.app.data.AgentActivityAppEvent
import build.raft.app.data.AgentActivityActionKind
import build.raft.app.data.AgentActivityOverviewState
import build.raft.app.data.AgentActivityOverviewStatus
import build.raft.app.data.AgentActivityProblemReason
import build.raft.app.data.AgentActivityReplyState
import build.raft.app.data.AgentActivityTurn
import build.raft.app.data.AgentActivityTurnOutcome
import build.raft.app.data.AgentActivityTurnProblem
import build.raft.app.data.AgentActivityTurnReply
import build.raft.app.data.AgentActivityTurnTrigger
import build.raft.app.model.MemberId
import build.raft.app.model.MessageId

/**
 * Newest first, as the server pages them (2026-06-19, UTC+8), with what newer servers add: the
 * running turn, sent messages, app notifications, and triggers named by the server.
 */
internal fun agentActivityOverviewScreenshotState(): AgentActivityOverviewState =
    AgentActivityOverviewState(
        agentId = MemberId("agent-activity-overview"),
        status = AgentActivityOverviewStatus.Loaded,
        next = "1781869209000:00000000-0000-4000-8000-000000000003",
        turns = listOf(
            AgentActivityTurn(
                id = "00000000-0000-4000-8000-000000000001",
                startedAtEpochMillis = 1781870400000L,
                endedAtEpochMillis = 1781870501000L,
                outcome = AgentActivityTurnOutcome.Completed,
                trigger = AgentActivityTurnTrigger.Visible(
                    target = "#proj-runtime:9b526607",
                    channelId = "c1",
                    sender = "Tenny",
                    messageId = "m1",
                    display = AgentActivityTurnTrigger.Display.Channel(channelName = "proj-runtime", senderName = "Tenny", thread = true),
                ),
                actions = listOf(
                    AgentActivityActionCount(AgentActivityActionKind.Search, 3),
                    AgentActivityActionCount(AgentActivityActionKind.RunCommand, 1),
                    AgentActivityActionCount(AgentActivityActionKind.SendMessage, 1),
                ),
                toolCalls = 5,
                problems = listOf(
                    AgentActivityTurnProblem(AgentActivityActionKind.RunCommand, AgentActivityProblemReason.ExitNonzero, 2, 1781870420000L),
                ),
                problemCount = 1,
                messagesSent = 2,
                lastReplyMessageId = MessageId("reply-1"),
                sentMessageIds = listOf(MessageId("sent-1"), MessageId("reply-1")),
                sentMessageCount = 2,
                hiddenSentCount = 1,
            ),
            AgentActivityTurn(
                id = "00000000-0000-4000-8000-000000000002",
                startedAtEpochMillis = 1781869800000L,
                endedAtEpochMillis = 1781869805000L,
                outcome = AgentActivityTurnOutcome.Failed,
                trigger = AgentActivityTurnTrigger.Hidden(AgentActivityTurnTrigger.HiddenPlace.PrivateChannel),
                actions = emptyList(),
                toolCalls = 0,
                problems = listOf(
                    AgentActivityTurnProblem(AgentActivityActionKind.EditFile, AgentActivityProblemReason.CallFailed, null, 1781869804000L),
                ),
                problemCount = 1,
                messagesSent = 0,
                lastReplyMessageId = null,
            ),
            AgentActivityTurn(
                id = "00000000-0000-4000-8000-000000000003",
                startedAtEpochMillis = 1781869200000L,
                endedAtEpochMillis = 1781869209000L,
                outcome = AgentActivityTurnOutcome.Completed,
                trigger = AgentActivityTurnTrigger.Visible(
                    target = "agent-event:1a2b3c4d",
                    channelId = null,
                    sender = null,
                    messageId = null,
                    appEvent = AgentActivityTurnTrigger.AppEvent(appName = "Stamp", summary = "CI failed on #10827"),
                ),
                actions = emptyList(),
                toolCalls = 0,
                problems = listOf(
                    AgentActivityTurnProblem(AgentActivityActionKind.SendMessage, AgentActivityProblemReason.SendHeld, null, 1781869208000L),
                ),
                problemCount = 1,
                messagesSent = 0,
                lastReplyMessageId = null,
                appEvents = listOf(
                    AgentActivityAppEvent("e1", "Stamp", "stamp-overview", null, "CI failed on #10827", 1781869201000L),
                    AgentActivityAppEvent("e2", "Reminders", "system.reminders", null, "Check the nightly build", 1781869204000L),
                ),
                appEventCount = 2,
            ),
        ),
        running = AgentActivityTurn(
            id = "00000000-0000-4000-8000-000000000004",
            startedAtEpochMillis = 1781870580000L,
            endedAtEpochMillis = 1781870700000L,
            outcome = AgentActivityTurnOutcome.Unknown,
            trigger = AgentActivityTurnTrigger.Visible(
                target = "dm:@richard",
                channelId = "d1",
                sender = "richard",
                messageId = null,
                display = AgentActivityTurnTrigger.Display.Dm(senderName = null, peerName = "richard"),
            ),
            actions = listOf(AgentActivityActionCount(AgentActivityActionKind.ReadFile, 2)),
            toolCalls = 2,
            problems = emptyList(),
            problemCount = 0,
            messagesSent = 0,
            lastReplyMessageId = null,
            running = true,
        ),
        replies = mapOf(
            MessageId("reply-1") to AgentActivityReplyState.Ready(
                AgentActivityTurnReply.Loaded(content = "Fixed it: **tests pass** now.", createdAtEpochMillis = 1781870490000L, channelId = "c1"),
            ),
            MessageId("sent-1") to AgentActivityReplyState.Ready(
                AgentActivityTurnReply.Loaded(content = "Looking into the failing `runtime` test.", createdAtEpochMillis = 1781870410000L, channelId = "c1"),
            ),
        ),
    )
