package build.raft.app.data.realtime

import build.raft.app.data.SettingsServerSidebarPinnedItem
import build.raft.app.data.TaskWireDto
import build.raft.app.data.toTaskSummary
import build.raft.app.model.AgentStatus
import build.raft.app.model.ConversationKind
import build.raft.app.model.DirectMessageCreated
import build.raft.app.model.Heartbeat
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberPresenceUpdated
import build.raft.app.model.MessageNew
import build.raft.app.model.MessageType
import build.raft.app.model.MessageUpdated
import build.raft.app.model.NotificationPushReceived
import build.raft.app.model.PerReceiverNotificationPrefsChanged
import build.raft.app.model.PerReceiverPinnedChanged
import build.raft.app.model.PerReceiverReadStateChanged
import build.raft.app.model.Presence
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.RealtimeRefreshScope
import build.raft.app.model.ServerNotificationPreferencesChanged
import build.raft.app.model.TaskCreated
import build.raft.app.model.TaskUpdated
import build.raft.app.model.ThreadUpdated
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RealtimeEventMapperTest {
    @Test
    fun syncResumeRejectsWholeBatchAndSelectsGapRecoveryWhenOneMessageIsInvalid() {
        val logs = mutableListOf<String>()
        val event =
            RealtimeEventMapper(diagnosticsLogger = logs::add).map(
                RealtimeSocketEvents.SyncResumeResponse,
                """
                {
                  "currentSeq": 12,
                  "hasMore": false,
                  "messages": [
                    {
                      "id": "message-valid",
                      "seq": 11,
                      "channelId": "channel-1",
                      "threadId": "thread-1",
                      "content": "valid",
                      "messageType": "chat",
                      "senderType": "user",
                      "senderId": "user-1",
                      "senderName": "Artin"
                    },
                    {
                      "id": "message-invalid",
                      "seq": 12,
                      "channelId": "channel-1",
                      "threadId": "thread-1",
                      "content": "invalid",
                      "messageType": "chat",
                      "senderType": "system",
                      "senderId": "system",
                      "senderName": "System"
                    }
                  ]
                }
                """.trimIndent(),
            )

        val recovery = assertIs<Heartbeat>(event)
        assertEquals(12L, recovery.seq)
        assertTrue(logs.any { it == "sync_resume_projection_rejected recovery=heartbeat currentSeq=12" })
    }

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
                  "messageType": "chat",
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
    fun messageUpdatedKeepsCreationTimeAsTimelineTimestamp() {
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.MessageUpdated,
                """
                {
                  "id": "message-1",
                  "seq": 30,
                  "channelId": "channel-1",
                  "senderType": "agent",
                  "messageType": "chat",
                  "senderId": "agent-1",
                  "senderName": "Argus",
                  "content": "task state changed",
                  "createdAt": "2026-07-28T19:30:35+08:00",
                  "updatedAt": "2026-07-28T19:32:09+08:00"
                }
                """.trimIndent()
            )

        val updated = assertIs<MessageUpdated>(event)
        assertEquals("2026-07-28T19:30:35+08:00", updated.message.timestampLabel)
        assertEquals("2026-07-28T19:32:09+08:00", updated.message.updatedAtLabel)
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
                  "messageType": "chat",
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
    fun messageUpdatedTreatsExplicitNullReactionsAsCleared() {
        // A web-side "remove last reaction" emits `reactions: null`; realtime must clear
        // it, not preserve the stale reaction. Explicit JSON null maps to an empty list
        // so the store's realtime merge drops the removed reaction.
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.MessageUpdated,
                """
                {
                  "id": "message-1",
                  "channelId": "channel-1",
                  "senderType": "user",
                  "messageType": "chat",
                  "senderId": "u1",
                  "senderName": "Artin",
                  "content": "hello",
                  "reactions": null
                }
                """.trimIndent()
            )

        val updated = assertIs<MessageUpdated>(event)
        assertEquals(emptyList(), updated.message.reactions)
    }

    @Test
    fun messageUpdatedKeepsReactionsNullWhenFieldAbsent() {
        // A content-only `message:updated` omits `reactions`; it must stay null so the
        // realtime merge preserves the message's existing reactions instead of wiping
        // them on an unrelated edit.
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.MessageUpdated,
                """
                {
                  "id": "message-1",
                  "channelId": "channel-1",
                  "senderType": "user",
                  "messageType": "chat",
                  "senderId": "u1",
                  "senderName": "Artin",
                  "content": "edited body"
                }
                """.trimIndent()
            )

        val updated = assertIs<MessageUpdated>(event)
        assertNull(updated.message.reactions)
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
                  "messageType": "chat",
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
                  "messageType": "chat",
                  "senderId": "u1",
                  "senderName": "Artin",
                  "senderMembershipStatus": "active",
                  "content": "hello world",
                  "randomId": "rid-realtime-1",
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
        assertEquals("rid-realtime-1", message.randomId)

        val attachment = message.attachments.single()
        assertEquals("att-1", attachment.id.value)
        assertEquals("photo.png", attachment.name)
        assertEquals("image/png", attachment.mimeType)
        assertEquals(1024, attachment.sizeBytes)
        assertEquals("", attachment.sizeLabel)
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
        assertEquals(1_781_312_400_000L, message.createdAtEpochMillis)
        assertEquals("✅ artin moved #252 \"Implement Thread timeline system-message rendering\" to Done", message.body)
    }

    @Test
    fun messageEventsRejectUnknownSenderKindAndMessageTypeWithPayloadFreeDiagnostics() {
        val logs = mutableListOf<String>()
        val mapper = RealtimeEventMapper(diagnosticsLogger = logs::add)

        val unknownSender =
            mapper.map(
                RealtimeSocketEvents.MessageNew,
                """
                {
                  "id": "secret-message-id",
                  "channelId": "channel-1",
                  "senderType": "future-member",
                  "senderId": "secret-sender-id",
                  "senderName": "Secret Sender",
                  "messageType": "chat",
                  "content": "private body",
                  "createdAt": "2026-06-13T01:00:00Z"
                }
                """.trimIndent(),
            )
        val unknownMessageType =
            mapper.map(
                RealtimeSocketEvents.MessageUpdated,
                """
                {
                  "id": "secret-message-id-2",
                  "channelId": "channel-1",
                  "senderType": "user",
                  "senderId": "secret-sender-id-2",
                  "senderName": "Secret Sender Two",
                  "messageType": "future-message",
                  "content": "another private body",
                  "createdAt": "2026-06-13T01:00:00Z"
                }
                """.trimIndent(),
            )

        assertNull(unknownSender)
        assertNull(unknownMessageType)
        assertTrue(logs.any { it == "projection_value_rejected source=realtime_message_sender field=member_kind value=future-member" })
        assertTrue(logs.any { it == "projection_value_rejected source=realtime_message_type field=message_type value=future-message" })
        assertTrue(
            logs.none {
                "secret-message-id" in it || "secret-sender-id" in it || "private body" in it || "Secret Sender" in it
            },
        )
    }

    @Test
    fun taskCreatedDropsUnknownStatusAndKeepsValidSibling() {
        val logs = mutableListOf<String>()
        val event =
            RealtimeEventMapper(diagnosticsLogger = logs::add).map(
                RealtimeSocketEvents.TaskCreated,
                """
                {
                  "channelId": "channel-3",
                  "tasks": [
                    {
                      "id": "task-invalid",
                      "messageId": "m-invalid",
                      "title": "Must not become Todo",
                      "status": "future-status"
                    },
                    {
                      "id": "task-valid",
                      "messageId": "m-valid",
                      "title": "Keep valid",
                      "status": "in_review"
                    }
                  ]
                }
                """.trimIndent(),
            )

        val created = assertIs<TaskCreated>(event)
        assertEquals(listOf("task-valid"), created.tasks.map { it.id.value })
        assertEquals(
            "projection_value_rejected source=realtime_task field=task_status value=future-status",
            logs.first { it.startsWith("projection_value_rejected") },
        )
    }

    @Test
    fun taskUpdatedDropsUnknownAssigneeKindWithoutDroppingTask() {
        val logs = mutableListOf<String>()
        val event =
            RealtimeEventMapper(diagnosticsLogger = logs::add).map(
                RealtimeSocketEvents.TaskUpdated,
                """
                {
                  "channelId": "channel-2",
                  "task": {
                    "id": "task-2",
                    "revision": 42,
                    "messageId": "m2",
                    "title": "Keep task",
                    "status": "in_review",
                    "claimedByType": "future-member",
                    "claimedById": "secret-assignee-id",
                    "claimedByName": "Secret Assignee"
                  }
                }
                """.trimIndent(),
            )

        val updated = assertIs<TaskUpdated>(event)
        assertNull(updated.task.assignee)
        assertTrue(logs.any { it == "projection_value_rejected source=realtime_task_member field=member_kind value=future-member" })
        assertTrue(logs.none { "secret-assignee-id" in it || "Secret Assignee" in it })
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
                    "revision": 42,
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
        assertEquals("m2", updated.task.parentMessageId?.value)
        assertEquals(42L, updated.task.revision)
    }

    @Test
    fun realtimeAndHttpTaskShapesProduceTheSameCanonicalFactAtOneRevision() {
        val http =
            TaskWireDto(
                id = "task-2",
                revision = 42L,
                messageId = "m2",
                channelId = "channel-http",
                channelName = "joint",
                taskNumber = 2,
                title = "Canonical task",
                status = "in_review",
                updatedAt = "2026-06-25T06:10:00Z"
            ).toTaskSummary()
        val realtime =
            assertIs<TaskUpdated>(
                RealtimeEventMapper().map(
                    RealtimeSocketEvents.TaskUpdated,
                    """
                    {
                      "channelId": "channel-realtime",
                      "task": {
                        "id": "task-2",
                        "revision": 42,
                        "messageId": "m2",
                        "channelName": "joint",
                        "taskNumber": 2,
                        "title": "Canonical task",
                        "status": "in_review",
                        "updatedAt": "2026-06-25T06:10:00Z"
                      }
                    }
                    """.trimIndent()
                )
            ).task

        assertEquals(
            http?.copy(channelId = null, channelName = null),
            realtime.copy(channelId = null, channelName = null)
        )
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
                    "messageType": "chat",
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
                  "messageType": "chat",
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
                  "participantIds": ["u1", "agent-1"],
                  "latestReply": {
                    "id": "dm-reply-1",
                    "seq": 8,
                    "content": "hello",
                    "senderId": "agent-1",
                    "senderType": "agent",
                    "senderName": "MingQi",
                    "senderDisplayName": "明启",
                    "senderAvatarUrl": "/agent.webp",
                    "createdAt": "2026-07-24T08:00:00Z"
                  }
                }
                """.trimIndent()
            )

        val updated = assertIs<ThreadUpdated>(event)
        assertEquals("dm-parent-1", updated.parentMessageId.value)
        assertEquals("dm-thread-1", updated.threadChannelId.value)
        assertEquals(1, updated.replyCount)
        assertEquals(1, updated.unreadCount)
        assertEquals("dm-reply-1", updated.firstUnreadMessageId?.value)
        assertEquals("dm-reply-1", updated.latestReply?.messageId)
        assertEquals("MingQi", updated.latestReply?.senderName)
        assertEquals("明启", updated.latestReply?.senderDisplayName)
    }

    @Test
    fun threadUpdatedDoesNotPromoteStableHandleIntoDisplayNameProjection() {
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.ThreadUpdated,
                """
                {
                  "parentMessageId": "parent-1",
                  "threadChannelId": "thread-1",
                  "replyCount": 1,
                  "latestReply": {
                    "id": "reply-1",
                    "seq": 1,
                    "content": "hello",
                    "senderId": "agent-1",
                    "senderType": "agent",
                    "senderName": "MingQi",
                    "createdAt": "2026-07-24T08:00:00Z"
                  }
                }
                """.trimIndent()
            )

        val updated = assertIs<ThreadUpdated>(event)
        assertEquals("MingQi", updated.latestReply?.senderName)
        assertNull(updated.latestReply?.senderDisplayName)
    }

    @Test
    fun threadUpdatedProjectsExistingRepliesSyncCoreV1Envelope() {
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.ThreadUpdated,
                """
                {
                  "parentMessageId": "parent-1",
                  "threadChannelId": "thread-1",
                  "replyCount": 1,
                  "syncCoreReplyWindow": {
                    "producer": "message-service.thread-replies-window.v1",
                    "discussion": {
                      "root": { "kind": "message", "serverId": "server-1", "id": "parent-1" },
                      "relation": { "kind": "replies" },
                      "parentScopeKey": {
                        "serverId": "server-1",
                        "scopeKind": "channel",
                        "scopeId": "channel-1"
                      },
                      "backing": "sync-scope"
                    },
                    "window": { "kind": "sync-scope-window", "scopeCursor": null, "epoch": null }
                  },
                  "latestReply": {
                    "id": "reply-1",
                    "seq": 1,
                    "content": "hello",
                    "senderId": "user-1",
                    "senderType": "user",
                    "senderName": "user-1",
                    "createdAt": "2026-07-25T00:00:00Z"
                  }
                }
                """.trimIndent(),
            )

        val envelope = assertIs<ThreadUpdated>(event).syncCoreReplyWindow
        assertEquals("message-service.thread-replies-window.v1", envelope?.producer)
        assertEquals("server-1", envelope?.rootServerId)
        assertEquals("parent-1", envelope?.rootMessageId)
        assertEquals("channel", envelope?.parentScopeKind)
        assertEquals("channel-1", envelope?.parentScopeId)
        assertNull(envelope?.scopeCursor)
        assertNull(envelope?.epoch)
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
                  "timestamp": 1782367200000,
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
    fun agentActivityPreservesTrajectoryEntries() {
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.AgentActivity,
                """
                {
                  "agentId": "agent-1",
                  "activity": "working",
                  "detail": "Running command",
                  "timestamp": 1782367200000,
                  "entries": [
                    {
                      "kind": "tool_start",
                      "toolName": "run_command",
                      "toolInput": {
                        "cmd": "echo hello",
                        "token": "secret"
                      },
                      "subagent": {
                        "parentToolUseId": "toolu-outer",
                        "subagentType": "Plan",
                        "phase": "active"
                      }
                    },
                    { "kind": "text", "text": "hello" },
                    { "kind": "status", "activity": "working", "detail": "Running command" }
                  ],
                  "serverSeq": 44
                }
                """.trimIndent()
            )

        val presence = assertIs<MemberPresenceUpdated>(event)
        assertEquals(AgentStatus.Working, presence.agentStatus)
        assertEquals("Running command", presence.statusLabel)
        val rows = presence.activityEntries
        assertEquals(3, rows.size)
        assertEquals("realtime:agent-1:seq:44:0:tool_start", rows[0].id)
        assertEquals("tool_start", rows[0].type)
        assertEquals("Run command", rows[0].title)
        assertEquals("Structured input: cmd", rows[0].body)
        assertEquals("run_command", rows[0].toolName)
        assertEquals(true, rows[0].isSubagent)
        assertEquals("Plan", rows[0].subagentType)
        assertEquals("1782367200000", rows[0].timestamp)
        assertEquals("Output", rows[1].title)
        assertEquals("hello", rows[1].body)
        assertEquals("Working", rows[2].title)
        assertEquals("Running command", rows[2].body)
        assertEquals("working", rows[2].statusActivity)
    }

    @Test
    fun agentActivityPreservesStoppedReasonAndTypelessSubagentLineage() {
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.AgentActivity,
                """
                {
                  "agentId": "agent-1",
                  "activity": "offline",
                  "detail": "Computer shutdown",
                  "entries": [
                    {
                      "kind": "tool_start",
                      "toolName": "check_messages",
                      "subagent": { "parentToolUseId": "toolu-outer" }
                    },
                    {
                      "kind": "status",
                      "activity": "offline",
                      "detail": "Computer shutdown",
                      "detailKind": "stopped"
                    }
                  ]
                }
                """.trimIndent()
            )

        val rows = assertIs<MemberPresenceUpdated>(event).activityEntries
        assertEquals(2, rows.size)
        assertEquals(true, rows[0].isSubagent)
        assertEquals(null, rows[0].subagentType)
        assertEquals("Stopped", rows[1].title)
        assertEquals("Computer shutdown", rows[1].body)
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
    fun agentActivityIgnoresInvalidPushedActivity() {
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

        assertNull(event)
    }

    @Test
    fun agentActivityLifecycleActiveDoesNotProjectAsOnline() {
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.AgentActivity,
                """
                {
                  "agentId": "agent-4",
                  "activity": "active",
                  "detail": ""
                }
                """.trimIndent()
            )

        assertNull(event)
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

    // task #425: message:new conversationContext (server task #86)

    @Test
    fun messageNewMapsThreadConversationContext() {
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.MessageNew,
                """
                {
                  "id": "message-1",
                  "channelId": "thread-channel-1",
                  "senderType": "user",
                  "messageType": "chat",
                  "senderId": "u1",
                  "senderName": "Artin",
                  "content": "reply in dm thread",
                  "conversationContext": {
                    "channelType": "thread",
                    "parentMessageId": "parent-message-1",
                    "parentChannelId": "dm-channel-1",
                    "parentChannelType": "dm"
                  }
                }
                """.trimIndent()
            )

        val context = assertIs<MessageNew>(event).message.conversationContext
        assertEquals(ConversationKind.Thread, context?.channelKind)
        assertEquals("parent-message-1", context?.parentMessageId?.value)
        assertEquals("dm-channel-1", context?.parentChannelId?.value)
        assertEquals(ConversationKind.DirectMessage, context?.parentChannelKind)
    }

    @Test
    fun messageNewMapsDmConversationContextAndFoldsVisibilityToChannel() {
        val dm =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.MessageNew,
                """
                {
                  "id": "m1", "channelId": "c1", "senderType": "user", "senderId": "u1", "messageType": "chat",
                  "content": "hi",
                  "conversationContext": { "channelType": "dm" }
                }
                """.trimIndent()
            )
        assertEquals(
            ConversationKind.DirectMessage,
            assertIs<MessageNew>(dm).message.conversationContext?.channelKind
        )

        // "private" is visibility, not kind — folds to Channel per the #417 contract.
        val privateChannel =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.MessageNew,
                """
                {
                  "id": "m2", "channelId": "c2", "senderType": "user", "senderId": "u1", "messageType": "chat",
                  "content": "hi",
                  "conversationContext": { "channelType": "private" }
                }
                """.trimIndent()
            )
        assertEquals(
            ConversationKind.Channel,
            assertIs<MessageNew>(privateChannel).message.conversationContext?.channelKind
        )
    }

    @Test
    fun messageNewWithoutConversationContextKeepsNullForOldServers() {
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.MessageNew,
                """
                {
                  "id": "m1", "channelId": "c1", "senderType": "user", "senderId": "u1", "messageType": "chat",
                  "content": "hi"
                }
                """.trimIndent()
            )

        assertNull(assertIs<MessageNew>(event).message.conversationContext)
    }

    @Test
    fun messageNewDropsConversationContextWithOnlyUnknownValues() {
        // Unknown kind strings parse to null (never a guessed Channel); a
        // context carrying nothing is dropped so consumers treat it exactly
        // like an old server omitting the field.
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.MessageNew,
                """
                {
                  "id": "m1", "channelId": "c1", "senderType": "user", "senderId": "u1", "messageType": "chat",
                  "content": "hi",
                  "conversationContext": { "channelType": "mystery-kind", "parentChannelType": " " }
                }
                """.trimIndent()
            )

        assertNull(assertIs<MessageNew>(event).message.conversationContext)
    }

    // ── RFC 041 per-receiver state push (server PR #4175) ────────────────────
    // Payload shapes mirror `routes/channels.ts::emitReadStateUpdated`,
    // `emitReadStateUpdatedBulk`, and `emitNotificationPrefsUpdated` verbatim.

    @Test
    fun readStateUpdatedMapsSingleScope() {
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.ReadStateUpdated,
                """
                {
                  "serverId": "server-1",
                  "scopeId": "channel-1",
                  "maxReadSeq": 4211,
                  "readStateVersion": 7
                }
                """.trimIndent()
            )

        val changed = assertIs<PerReceiverReadStateChanged>(event)
        assertEquals("server-1", changed.serverId?.value)
        val scope = changed.scopes.single()
        assertEquals("channel-1", scope.scopeId.value)
        assertEquals(4211L, scope.maxReadSeq)
        assertEquals(7L, scope.readStateVersion)
    }

    @Test
    fun readStateUpdatedBulkMapsAllScopesAndDropsBlankScopeIds() {
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.ReadStateUpdatedBulk,
                """
                {
                  "serverId": "server-1",
                  "scopes": [
                    { "scopeId": "channel-1", "maxReadSeq": 10, "readStateVersion": 3 },
                    { "scopeId": "", "maxReadSeq": 11, "readStateVersion": 4 },
                    { "scopeId": "channel-2", "maxReadSeq": 0, "readStateVersion": 1 }
                  ]
                }
                """.trimIndent()
            )

        val changed = assertIs<PerReceiverReadStateChanged>(event)
        assertEquals(listOf("channel-1", "channel-2"), changed.scopes.map { it.scopeId.value })
        assertEquals(listOf(3L, 1L), changed.scopes.map { it.readStateVersion })
    }

    @Test
    fun readStateUpdatedBulkWithNoUsableScopesIsIgnored() {
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.ReadStateUpdatedBulk,
                """{ "serverId": "server-1", "scopes": [] }"""
            )

        assertNull(event)
    }

    @Test
    fun readStateUpdatedWithoutVersionIsIgnored() {
        // An event we cannot version-gate is unusable: drop it (fail closed)
        // and let hydrate reconcile, per RFC 041 client obligations.
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.ReadStateUpdated,
                """{ "serverId": "server-1", "scopeId": "channel-1", "maxReadSeq": 12 }"""
            )

        assertNull(event)
    }

    @Test
    fun readStateUpdatedWithoutUsableWatermarkIsIgnored() {
        val mapper = RealtimeEventMapper()

        assertNull(
            mapper.map(
                RealtimeSocketEvents.ReadStateUpdated,
                """{ "serverId": "server-1", "scopeId": "channel-1", "readStateVersion": 2 }"""
            )
        )
        assertNull(
            mapper.map(
                RealtimeSocketEvents.ReadStateUpdated,
                """{ "serverId": "server-1", "scopeId": "channel-1", "maxReadSeq": -1, "readStateVersion": 2 }"""
            )
        )
        assertNull(
            mapper.map(
                RealtimeSocketEvents.ReadStateUpdated,
                """{ "serverId": "server-1", "scopeId": "channel-1", "maxReadSeq": 2, "readStateVersion": 0 }"""
            )
        )
        assertNull(
            mapper.map(
                RealtimeSocketEvents.ReadStateUpdated,
                """{ "serverId": "server-1", "scopeId": "channel-1", "maxReadSeq": 2, "readStateVersion": "abc" }"""
            )
        )
    }

    @Test
    fun notificationPrefsUpdatedMapsPreferenceAndVersion() {
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.NotificationPrefsUpdated,
                """
                {
                  "serverId": "server-1",
                  "scopeId": "channel-9",
                  "prefs": { "activityMuted": true, "muteFromSeq": 900 },
                  "prefsVersion": 2
                }
                """.trimIndent()
            )

        val changed = assertIs<PerReceiverNotificationPrefsChanged>(event)
        assertEquals("channel-9", changed.scopeId.value)
        assertTrue(changed.preference.activityMuted)
        assertEquals(900L, changed.preference.muteFromSeq)
        assertTrue(changed.preference.activityMuteSupported)
        assertEquals(2L, changed.prefsVersion)
    }

    @Test
    fun serverNotificationPrefsUpdatedMapsOnlyMentionsMode() {
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.NotificationPrefsUpdated,
                """
                {
                  "serverId": "server-1",
                  "scopeId": "server-1",
                  "prefs": {
                    "serverPushMuted": false,
                    "serverPushMentionsOnly": true,
                    "serverPushMode": "mentions",
                    "pushPreviewMode": "private"
                  },
                  "prefsVersion": 4
                }
                """.trimIndent()
            )

        val changed = assertIs<ServerNotificationPreferencesChanged>(event)
        assertEquals("server-1", changed.serverId.value)
        assertEquals("mentions", changed.serverPushMode)
        assertEquals("private", changed.pushPreviewMode)
        assertEquals(4L, changed.prefsVersion)
    }

    @Test
    fun serverNotificationPrefsUpdatedRejectsUnknownPushPreviewMode() {
        val diagnostics = mutableListOf<String>()
        val event =
            RealtimeEventMapper(diagnosticsLogger = diagnostics::add).map(
                RealtimeSocketEvents.NotificationPrefsUpdated,
                """
                {
                  "serverId": "server-1",
                  "scopeId": "server-1",
                  "prefs": {
                    "serverPushMuted": false,
                    "serverPushMode": "all",
                    "pushPreviewMode": "future"
                  },
                  "prefsVersion": 5
                }
                """.trimIndent()
            )

        assertNull(event)
        assertTrue(diagnostics.any { it.contains("realtime_server_notification") && it.contains("push_preview_mode") })
    }

    @Test
    fun serverNotificationPrefsUpdatedRejectsPresentUnknownCanonicalMode() {
        val diagnostics = mutableListOf<String>()
        val event =
            RealtimeEventMapper(diagnosticsLogger = diagnostics::add).map(
                RealtimeSocketEvents.NotificationPrefsUpdated,
                """
                {
                  "serverId": "server-1",
                  "scopeId": "server-1",
                  "prefs": {
                    "serverPushMuted": false,
                    "serverPushMode": "future_restrictive"
                  },
                  "prefsVersion": 5
                }
                """.trimIndent()
            )

        assertNull(event)
        assertTrue(diagnostics.any { it.contains("realtime_server_notification") && it.contains("server_push_mode") })
    }

    @Test
    fun serverNotificationPrefsUpdatedFallsBackOnlyWhenCanonicalModeIsAbsent() {
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.NotificationPrefsUpdated,
                """
                {
                  "serverId": "server-1",
                  "scopeId": "server-1",
                  "prefs": { "serverPushMuted": true },
                  "prefsVersion": 6
                }
                """.trimIndent()
            )

        val changed = assertIs<ServerNotificationPreferencesChanged>(event)
        assertEquals("none", changed.serverPushMode)
    }

    @Test
    fun pinnedUpdatedMapsTypedPinnedAndVersion() {
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.PinnedUpdated,
                """
                {
                  "serverId": "server-1",
                  "pinned": [
                    { "kind": "channel", "id": "channel-1" },
                    { "kind": "agent", "id": "agent-1" },
                    { "kind": "human", "id": "user-1" }
                  ],
                  "pinnedVersion": 12
                }
                """.trimIndent()
            )

        val changed = assertIs<PerReceiverPinnedChanged>(event)
        assertEquals("server-1", changed.serverId?.value)
        assertEquals(
            listOf(
                SettingsServerSidebarPinnedItem(kind = "channel", id = "channel-1"),
                SettingsServerSidebarPinnedItem(kind = "agent", id = "agent-1"),
                SettingsServerSidebarPinnedItem(kind = "human", id = "user-1")
            ),
            changed.pinned
        )
        assertEquals(12L, changed.pinnedVersion)
    }

    @Test
    fun pinnedUpdatedWithoutVersionIsIgnored() {
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.PinnedUpdated,
                """{ "serverId": "server-1", "pinned": [{ "kind": "channel", "id": "channel-1" }] }"""
            )

        assertNull(event)
    }

    // task #459: server-qualified notification stream (server PR #4233).
    @Test
    fun notificationPushMapsThreadIdentityAndDisplay() {
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.NotificationPush,
                """
                {
                  "serverId": "server-1",
                  "kind": "thread",
                  "channelId": null,
                  "threadId": "reply-stream",
                  "parentChannelId": "parent-channel",
                  "parentMessageId": "parent-message",
                  "messageId": "reply-1",
                  "title": "Thread in #home-fixes · Raft",
                  "body": "Cindy: hello",
                  "tag": "message:reply-1",
                  "serverName": "Raft",
                  "channelName": "home-fixes",
                  "parentChannelKind": "channel",
                  "senderId": "agent-cindy",
                  "senderType": "agent",
                  "senderName": "Cindy",
                  "messagePreview": "hello",
                  "mentioned": false,
                  "url": "https://app.raft.build/s/raft/c/parent-channel"
                }
                """.trimIndent()
            )

        val push = assertIs<NotificationPushReceived>(event)
        assertEquals("server-1", push.serverId.value)
        assertEquals(ConversationKind.Thread, push.kind)
        assertEquals("reply-stream", push.threadId?.value)
        assertEquals("parent-channel", push.parentChannelId?.value)
        assertEquals("parent-message", push.parentMessageId?.value)
        assertEquals("reply-1", push.messageId.value)
        assertEquals("Thread in #home-fixes · Raft", push.title)
        assertEquals("Cindy: hello", push.body)
        assertEquals("Raft", push.serverName)
        assertEquals("home-fixes", push.channelName)
        assertEquals(ConversationKind.Channel, push.parentChannelKind)
        assertEquals("agent-cindy", push.senderId?.value)
        assertEquals(MemberKind.Agent, push.senderKind)
        assertEquals("Cindy", push.senderName)
        assertEquals("hello", push.messagePreview)
        assertEquals(false, push.mentioned)
    }

    @Test
    fun notificationPushThreadWithoutParentPairIsDropped() {
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.NotificationPush,
                """{ "serverId": "server-1", "kind": "thread", "threadId": "reply-stream", "messageId": "reply-1" }"""
            )

        assertNull(event)
    }

    @Test
    fun notificationPushChannelWithoutChannelIdIsDropped() {
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.NotificationPush,
                """{ "serverId": "server-1", "kind": "channel", "messageId": "m-1" }"""
            )

        assertNull(event)
    }

    @Test
    fun notificationPushUnknownKindIsDropped() {
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.NotificationPush,
                """{ "serverId": "server-1", "kind": "carrier-pigeon", "channelId": "c-1", "messageId": "m-1" }"""
            )

        assertNull(event)
    }

    @Test
    fun notificationPushDmMapsChannelIdentity() {
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.NotificationPush,
                """{ "serverId": "server-1", "kind": "dm", "channelId": "dm-1", "messageId": "m-2", "title": "DM · Raft", "body": "Cindy: hi", "tag": "message:m-2", "senderId": "user-cindy", "senderType": "user" }"""
            )

        val push = assertIs<NotificationPushReceived>(event)
        assertEquals(ConversationKind.DirectMessage, push.kind)
        assertEquals("dm-1", push.channelId?.value)
        assertEquals("user-cindy", push.senderId?.value)
        assertEquals(MemberKind.Human, push.senderKind)
    }

    @Test
    fun notificationPushIncompleteSenderIdentityFallsBackWithoutGuessingByName() {
        val logs = mutableListOf<String>()
        val event =
            RealtimeEventMapper(diagnosticsLogger = logs::add).map(
                RealtimeSocketEvents.NotificationPush,
                """{ "serverId": "server-1", "kind": "channel", "channelId": "c-1", "messageId": "m-1", "senderId": "user-1", "senderName": "Duplicate Name" }"""
            )

        val push = assertIs<NotificationPushReceived>(event)
        assertNull(push.senderId)
        assertNull(push.senderKind)
        assertEquals("Duplicate Name", push.senderName)
        assertTrue(logs.any { "source=notification_push_sender" in it && "value=incomplete" in it })
    }

    @Test
    fun agentSeenMapsToPresenceFactCarryingLastSeenAt() {
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.AgentSeen,
                """{ "agentId": "agent-ext-1", "lastSeenAt": "2026-09-28T01:02:03.456Z" }"""
            )

        val seen = assertIs<MemberPresenceUpdated>(event)
        assertEquals("agent-ext-1", seen.memberId.value)
        assertEquals(MemberKind.Agent, seen.kind)
        assertEquals(Presence.Active, seen.presence)
        assertEquals("2026-09-28T01:02:03.456Z", seen.lastSeenAt)
        assertNull(seen.serverSeq)
        assertNull(seen.agentStatus)
    }

    @Test
    fun agentSeenDropsMalformedPayloads() {
        assertNull(
            RealtimeEventMapper().map(
                RealtimeSocketEvents.AgentSeen,
                """{ "agentId": "agent-ext-1" }"""
            )
        )
        assertNull(
            RealtimeEventMapper().map(
                RealtimeSocketEvents.AgentSeen,
                """{ "agentId": "", "lastSeenAt": "2026-09-28T01:02:03.456Z" }"""
            )
        )
    }

    @Test
    fun notificationPushUnknownSenderTypeFallsBackWithoutUsingSenderId() {
        val logs = mutableListOf<String>()
        val event =
            RealtimeEventMapper(diagnosticsLogger = logs::add).map(
                RealtimeSocketEvents.NotificationPush,
                """{ "serverId": "server-1", "kind": "channel", "channelId": "c-1", "messageId": "m-1", "senderId": "member-1", "senderType": "bot" }"""
            )

        val push = assertIs<NotificationPushReceived>(event)
        assertNull(push.senderId)
        assertNull(push.senderKind)
        assertTrue(logs.any { "source=notification_push_sender" in it && "field=member_kind" in it })
    }
}
