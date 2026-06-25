package ai.slock.compose.data.realtime

import ai.slock.compose.model.AgentStatus
import ai.slock.compose.model.DirectMessageCreated
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberPresenceUpdated
import ai.slock.compose.model.MessageNew
import ai.slock.compose.model.MessageType
import ai.slock.compose.model.MessageUpdated
import ai.slock.compose.model.Presence
import ai.slock.compose.model.RealtimeRefreshRequired
import ai.slock.compose.model.RealtimeRefreshScope
import ai.slock.compose.model.TaskCreated
import ai.slock.compose.model.TaskUpdated
import ai.slock.compose.model.ThreadUpdated
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RealtimeEventMapperTest {
    @Test
    fun messageUpdatedMapsReactionPayload() {
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.MessageUpdated,
                """
                {
                  "id": "message-1",
                  "channelId": "channel-1",
                  "senderType": "user",
                  "senderId": "u1",
                  "senderName": "Artin",
                  "content": "hello",
                  "reactions": [
                    {
                      "emoji": "👀",
                      "count": 1,
                      "reactorIds": ["u2"],
                      "reactorNames": ["Cindy"]
                    }
                  ]
                }
                """.trimIndent()
            )

        val updated = assertIs<MessageUpdated>(event)
        val reaction = updated.message.reactions?.single()
        assertEquals("👀", reaction?.emoji)
        assertEquals(1, reaction?.count)
        assertEquals(listOf("u2"), reaction?.reactorIds)
        assertEquals(listOf("Cindy"), reaction?.reactorNames)
    }

    @Test
    fun messageUpdatedPreservesKnownEmptyReactionPayload() {
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.MessageUpdated,
                """
                {
                  "id": "message-1",
                  "channelId": "channel-1",
                  "senderType": "user",
                  "senderId": "u1",
                  "senderName": "Artin",
                  "content": "hello",
                  "reactions": []
                }
                """.trimIndent()
            )

        val updated = assertIs<MessageUpdated>(event)
        assertEquals(emptyList(), updated.message.reactions)
    }

    @Test
    fun messageUpdatedPrefersChannelIdForTimelineIdentityWhenThreadIdIsReplyStream() {
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.MessageUpdated,
                """
                {
                  "id": "parent-message-1",
                  "threadId": "reply-stream-1",
                  "channelId": "parent-channel-1",
                  "senderType": "user",
                  "senderId": "u1",
                  "senderName": "Artin",
                  "content": "parent edited"
                }
                """.trimIndent()
            )

        val updated = assertIs<MessageUpdated>(event)
        assertEquals("parent-channel-1", updated.message.threadId.value)
        assertEquals("parent-channel-1", updated.message.channelId?.value)
        assertNull(updated.message.threadChannelId)
    }

    @Test
    fun messageNewRawPayloadMapsWithFullFields() {
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.MessageNew,
                """
                {
                  "id": "msg-1",
                  "channelId": "ch-1",
                  "senderType": "user",
                  "senderId": "u1",
                  "senderName": "Artin",
                  "senderMembershipStatus": "active",
                  "content": "hello world",
                  "attachments": [
                    {
                      "id": "att-1",
                      "filename": "photo.png",
                      "mimeType": "image/png",
                      "sizeBytes": 1024,
                      "width": 800,
                      "height": 600,
                      "thumbnailUrl": "https://example.com/thumb.png"
                    }
                  ],
                  "reactions": [
                    { "emoji": "👍", "count": 2, "reactorIds": ["u2"], "reactorNames": ["Bob"] }
                  ]
                }
                """.trimIndent()
            )

        val messageNew = assertIs<MessageNew>(event)
        val message = messageNew.message
        assertEquals("msg-1", message.id.value)
        assertEquals("Artin", message.author.displayName)
        assertEquals("active", message.senderMembershipStatus)
        assertEquals("hello world", message.body)

        val attachment = message.attachments.single()
        assertEquals("att-1", attachment.id.value)
        assertEquals("photo.png", attachment.name)
        assertEquals("image/png", attachment.mimeType)
        assertEquals("1024 B", attachment.sizeLabel)
        assertEquals(800, attachment.width)
        assertEquals(600, attachment.height)
        assertEquals("https://example.com/thumb.png", attachment.thumbnailUrl)

        val reaction = message.reactions?.single()
        assertEquals("👍", reaction?.emoji)
        assertEquals(2, reaction?.count)
        assertEquals(listOf("u2"), reaction?.reactorIds)
    }

    @Test
    fun messageNewPreservesThreadSystemMessageType() {
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.MessageNew,
                """
                {
                  "id": "sys-done-1",
                  "channelId": "thread-1",
                  "senderType": "user",
                  "senderId": "system",
                  "senderName": "System",
                  "messageType": "system",
                  "content": "✅ artin moved #252 \"Implement Thread timeline system-message rendering\" to Done",
                  "createdAt": "2026-06-13T01:00:00Z",
                  "seq": 44
                }
                """.trimIndent()
            )

        val message = assertIs<MessageNew>(event).message
        assertEquals("sys-done-1", message.id.value)
        assertEquals("thread-1", message.channelId?.value)
        assertEquals(MessageType.System, message.messageType)
        assertEquals(MemberKind.System, message.author.kind)
        assertEquals("✅ artin moved #252 \"Implement Thread timeline system-message rendering\" to Done", message.body)
    }

    @Test
    fun taskUpdatedUsesEnvelopeChannelIdWhenTaskOmitsChannelId() {
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.TaskUpdated,
                """
                {
                  "channelId": "channel-2",
                  "task": {
                    "id": "task-2",
                    "messageId": "m2",
                    "taskNumber": 2,
                    "title": "Update channel two",
                    "status": "in_review",
                    "updatedAt": "2026-06-25T06:10:00Z"
                  }
                }
                """.trimIndent()
            )

        val updated = assertIs<TaskUpdated>(event)
        assertEquals("task-2", updated.task.id.value)
        assertEquals("channel-2", updated.task.channelId?.value)
        assertEquals("m2", updated.task.messageId?.value)
    }

    @Test
    fun taskCreatedUsesEnvelopeChannelIdWhenTaskOmitsChannelId() {
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.TaskCreated,
                """
                {
                  "channelId": "channel-3",
                  "tasks": [
                    {
                      "id": "task-3",
                      "messageId": "m3",
                      "taskNumber": 3,
                      "title": "Create channel three",
                      "status": "todo",
                      "createdAt": "2026-06-25T06:11:00Z"
                    }
                  ]
                }
                """.trimIndent()
            )

        val created = assertIs<TaskCreated>(event)
        val task = created.tasks.single()
        assertEquals("task-3", task.id.value)
        assertEquals("channel-3", task.channelId?.value)
        assertEquals("m3", task.messageId?.value)
    }

    @Test
    fun messageNewRejectsEnvelopePayload() {
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.MessageNew,
                """
                {
                  "message": {
                    "id": "msg-1",
                    "channelId": "ch-1",
                    "senderType": "user",
                    "senderId": "u1",
                    "content": "hello"
                  }
                }
                """.trimIndent()
            )

        assertNull(event)
    }

    @Test
    fun directMessageNewOnlyMapsChannelCreatedPayload() {
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.DirectMessageNew,
                """{"channelId":"dm-1"}"""
            )

        val created = assertIs<DirectMessageCreated>(event)
        assertEquals("dm-1", created.channelId.value)
    }

    @Test
    fun directMessageNewRejectsMessagePayload() {
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.DirectMessageNew,
                """
                {
                  "id": "msg-1",
                  "channelId": "dm-1",
                  "senderType": "user",
                  "senderId": "u1",
                  "content": "hello"
                }
                """.trimIndent()
            )

        assertNull(event)
    }

    @Test
    fun threadUpdatedAcceptsChannelIdFallbackAndUnreadMetadata() {
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.ThreadUpdated,
                """
                {
                  "parentMessageId": "dm-parent-1",
                  "channelId": "dm-thread-1",
                  "replyCount": 1,
                  "unreadCount": 1,
                  "firstUnreadMessageId": "dm-reply-1",
                  "participantIds": ["u1", "agent-1"]
                }
                """.trimIndent()
            )

        val updated = assertIs<ThreadUpdated>(event)
        assertEquals("dm-parent-1", updated.parentMessageId.value)
        assertEquals("dm-thread-1", updated.threadChannelId.value)
        assertEquals(1, updated.replyCount)
        assertEquals(1, updated.unreadCount)
        assertEquals("dm-reply-1", updated.firstUnreadMessageId?.value)
    }

    @Test
    fun agentActivityPreservesServerSeq() {
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.AgentActivity,
                """
                {
                  "agentId": "agent-1",
                  "activity": "thinking",
                  "detail": "Planning",
                  "serverSeq": 42
                }
                """.trimIndent()
            )

        val presence = assertIs<MemberPresenceUpdated>(event)
        assertEquals("agent-1", presence.memberId.value)
        assertEquals(Presence.Active, presence.presence)
        assertEquals(MemberKind.Agent, presence.kind)
        assertEquals(AgentStatus.Thinking, presence.agentStatus)
        assertEquals("Planning", presence.statusLabel)
        assertEquals(42L, presence.serverSeq)
    }

    @Test
    fun agentActivityCompactionEntryProjectsWorkingStatus() {
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.AgentActivity,
                """
                {
                  "agentId": "agent-1",
                  "activity": "online",
                  "detail": "",
                  "entries": [
                    { "kind": "compaction_started" }
                  ],
                  "serverSeq": 43
                }
                """.trimIndent()
            )

        val presence = assertIs<MemberPresenceUpdated>(event)
        assertEquals("agent-1", presence.memberId.value)
        assertEquals(Presence.Active, presence.presence)
        assertEquals(AgentStatus.Working, presence.agentStatus)
        assertEquals("Compacting context...", presence.statusLabel)
        assertEquals(43L, presence.serverSeq)
    }

    @Test
    fun agentActivityStatusEntryProjectsWorkingStatus() {
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.AgentActivity,
                """
                {
                  "agentId": "agent-2",
                  "activity": "online",
                  "detail": "",
                  "entries": [
                    { "kind": "status", "activity": "working", "detail": "Running extension" }
                  ]
                }
                """.trimIndent()
            )

        val presence = assertIs<MemberPresenceUpdated>(event)
        assertEquals("agent-2", presence.memberId.value)
        assertEquals(Presence.Active, presence.presence)
        assertEquals(AgentStatus.Working, presence.agentStatus)
        assertEquals("Running extension", presence.statusLabel)
    }

    @Test
    fun agentActivityDetailLikeWireValueProjectsWorkingStatus() {
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.AgentActivity,
                """
                {
                  "agentId": "agent-3",
                  "activity": "running_extension",
                  "detail": "Running extension"
                }
                """.trimIndent()
            )

        val presence = assertIs<MemberPresenceUpdated>(event)
        assertEquals("agent-3", presence.memberId.value)
        assertEquals(Presence.Active, presence.presence)
        assertEquals(AgentStatus.Working, presence.agentStatus)
        assertEquals("Running extension", presence.statusLabel)
    }

    @Test
    fun serverWireEventsIncludeWebRealtimeContractEvents() {
        val events = RealtimeSocketEvents.ServerWireEvents

        assertTrue(RealtimeSocketEvents.AgentSession in events)
        assertTrue(RealtimeSocketEvents.ComputerUpgradeProgress in events)
        assertTrue(RealtimeSocketEvents.ComputerUpgradeDone in events)
        assertTrue(RealtimeSocketEvents.DaemonStatus in events)
    }

    @Test
    fun agentSessionRefreshesAgentsScope() {
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.AgentSession,
                """{"agentId":"agent-1","sessionId":"session-1"}"""
            )

        val refresh = assertIs<RealtimeRefreshRequired>(event)
        assertEquals(RealtimeRefreshScope.Agents, refresh.scope)
        assertEquals(RealtimeSocketEvents.AgentSession, refresh.reason)
        assertEquals("agent-1", refresh.subjectId)
    }

    @Test
    fun computerUpgradeEventsRefreshMachinesScope() {
        val mapper = RealtimeEventMapper()

        val progress =
            mapper.map(
                RealtimeSocketEvents.ComputerUpgradeProgress,
                """
                {
                  "machineId": "machine-1",
                  "requestId": "req-1",
                  "phase": "downloading",
                  "percent": 50
                }
                """.trimIndent()
            )
        val done =
            mapper.map(
                RealtimeSocketEvents.ComputerUpgradeDone,
                """
                {
                  "machineId": "machine-1",
                  "requestId": "req-1",
                  "ok": true,
                  "newVersion": "1.2.3"
                }
                """.trimIndent()
            )

        val progressRefresh = assertIs<RealtimeRefreshRequired>(progress)
        assertEquals(RealtimeRefreshScope.Machines, progressRefresh.scope)
        assertEquals(RealtimeSocketEvents.ComputerUpgradeProgress, progressRefresh.reason)
        assertEquals("machine-1", progressRefresh.subjectId)

        val doneRefresh = assertIs<RealtimeRefreshRequired>(done)
        assertEquals(RealtimeRefreshScope.Machines, doneRefresh.scope)
        assertEquals(RealtimeSocketEvents.ComputerUpgradeDone, doneRefresh.reason)
        assertEquals("machine-1", doneRefresh.subjectId)
    }

    @Test
    fun legacyDaemonStatusRefreshesMachinesScope() {
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.DaemonStatus,
                """{"daemonId":"machine-legacy","status":"online","statusVersion":12}"""
            )

        val refresh = assertIs<RealtimeRefreshRequired>(event)
        assertEquals(RealtimeRefreshScope.Machines, refresh.scope)
        assertEquals(RealtimeSocketEvents.DaemonStatus, refresh.reason)
        assertEquals("machine-legacy", refresh.subjectId)
    }
}
