package build.raft.app

import build.raft.app.data.AgentActivityActionCount
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

/** Newest first, as the server pages them (2026-06-19, UTC+8). */
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
                trigger = AgentActivityTurnTrigger.Visible(target = "#proj-runtime:9b526607", channelId = "c1", sender = "Tenny", messageId = "m1"),
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
                trigger = AgentActivityTurnTrigger.Visible(target = "agent-event:1a2b3c4d", channelId = null, sender = null, messageId = null),
                actions = emptyList(),
                toolCalls = 0,
                problems = listOf(
                    AgentActivityTurnProblem(AgentActivityActionKind.SendMessage, AgentActivityProblemReason.SendHeld, null, 1781869208000L),
                ),
                problemCount = 1,
                messagesSent = 0,
                lastReplyMessageId = null,
            ),
        ),
        replies = mapOf(
            MessageId("reply-1") to AgentActivityReplyState.Ready(
                AgentActivityTurnReply.Loaded(content = "Fixed it: **tests pass** now.", createdAtEpochMillis = 1781870490000L),
            ),
        ),
    )
