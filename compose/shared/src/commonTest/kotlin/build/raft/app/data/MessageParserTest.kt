package build.raft.app.data

import build.raft.app.model.MemberKind
import build.raft.app.model.ThreadId
import build.raft.app.network.sync.CanonicalMessageV2SegmentSource.Latest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MessageParserTest {
    @Test
    fun distinguishesAbsentValidEmptyAndMalformedEmbeddedSummaryAuthority() {
        fun parse(summaryField: String): SharedMessagesPayload =
            parseSharedChannelMessages(
                """
                {
                  "messages": [{
                    "id": "parent-1",
                    "seq": 1,
                    "channelId": "channel-1",
                    "senderType": "user",
                    "senderId": "u-1",
                    "senderName": "Artin",
                    "messageType": "chat",
                    "content": "parent",
                    "createdAt": "2026-07-25T14:00:00.000Z"
                  }]$summaryField
                }
                """.trimIndent(),
                fallbackThreadId = ThreadId("channel-1"),
            )

        assertEquals(ThreadReplySummariesAuthority.Unavailable, parse("").replySummariesAuthority)
        assertEquals(
            ThreadReplySummariesAuthority.Embedded,
            parse(", \"threadSummariesByParentMessageId\": {}").replySummariesAuthority,
        )
        assertEquals(
            ThreadReplySummariesAuthority.EmbeddedInvalid,
            parse(", \"threadSummariesByParentMessageId\": []").replySummariesAuthority,
        )
        assertEquals(
            ThreadReplySummariesAuthority.EmbeddedInvalid,
            parse(", \"threadSummariesByParentMessageId\": null").replySummariesAuthority,
        )
        assertEquals(
            ThreadReplySummariesAuthority.EmbeddedInvalid,
            parse(
                ", \"threadSummariesByParentMessageId\": {" +
                    "\"parent-1\": {\"threadChannelId\": \"\", \"replyCount\": 2}}",
            ).replySummariesAuthority,
        )
    }

    @Test
    fun decodesTheChannelPageMessageWindowAndAMalformedOneNeverDropsTheRows() {
        fun parse(windowField: String): SharedMessagesPayload =
            parseSharedChannelMessages(
                """
                {
                  "messages": [{
                    "id": "m-7",
                    "seq": 7,
                    "channelId": "channel-1",
                    "senderType": "user",
                    "senderId": "u-1",
                    "senderName": "Artin",
                    "messageType": "chat",
                    "content": "hi",
                    "createdAt": "2026-07-25T14:00:00.000Z"
                  }],
                  "historyLimited": false$windowField
                }
                """.trimIndent(),
                fallbackThreadId = ThreadId("channel-1"),
            )

        val window = assertNotNull(
            parse(
                ", \"messageWindow\": {\"schemaVersion\":1,\"domain\":\"receiver_visible_messages_v1\"," +
                    "\"serverId\":\"server-1\",\"scopeId\":\"channel-1\",\"coveredAfterSeq\":0,\"coveredFromSeq\":7,\"coveredThroughSeq\":7," +
                    "\"remoteHighWaterSeq\":7,\"hasGap\":false,\"hasNewer\":false,\"completeThroughLatest\":true}",
            ).messageWindow,
        )
        assertEquals(0L, window.coveredAfterSeq)
        assertEquals(7L, window.coveredThroughSeq)
        assertEquals(
            build.raft.app.network.sync.CanonicalMessageV2Coverage(
                1, 7, reachesOldest = true, headAtVerify = true, build.raft.app.network.sync.CanonicalMessageV2SegmentSource.Latest,
            ),
            window.toCoverage("server-1", "channel-1", historyLimited = false, Latest),
        )
        // An unknown schema or domain proves nothing.
        assertNull(window.copy(schemaVersion = 2).toCoverage("server-1", "channel-1", historyLimited = false, Latest))
        assertNull(window.copy(domain = "other").toCoverage("server-1", "channel-1", historyLimited = false, Latest))
        // A window naming another channel or server (or none) is not this scope's proof.
        assertNull(window.toCoverage("server-1", "channel-2", historyLimited = false, Latest))
        assertNull(window.toCoverage("server-2", "channel-1", historyLimited = false, Latest))
        assertNull(window.copy(scopeId = null).toCoverage("server-1", "channel-1", historyLimited = false, Latest))
        assertNull(window.copy(serverId = null).toCoverage("server-1", "channel-1", historyLimited = false, Latest))
        // Absent or malformed: no window, same rows.
        assertNull(parse("").messageWindow)
        val malformed = parse(", \"messageWindow\": {\"coveredAfterSeq\": \"x\"}")
        assertNull(malformed.messageWindow)
        assertEquals(listOf("m-7"), malformed.messages.map { it.id.value })
        assertEquals(listOf("m-7"), parse(", \"messageWindow\": 3").messages.map { it.id.value })
    }

    @Test
    fun parsesPageEmbeddedThreadSummariesWithoutPuttingPreviewIntoMessageWireState() {
        val payload =
            parseSharedChannelMessages(
                """
                {
                  "messages": [
                    {
                      "id": "parent-1",
                      "seq": 10,
                      "channelId": "channel-1",
                      "senderType": "user",
                      "senderId": "u-1",
                      "senderName": "Artin",
                      "messageType": "chat",
                      "content": "parent",
                      "createdAt": "2026-07-25T14:00:00.000Z"
                    }
                  ],
                  "threadSummariesByParentMessageId": {
                    "parent-1": {
                      "threadChannelId": "thread-1",
                      "replyCount": 5,
                      "unreadCount": 2,
                      "firstUnreadMessageId": "reply-4",
                      "latestReplies": [
                        {
                          "messageId": "reply-5",
                          "seq": 5,
                          "preview": "ready on the first visible frame",
                          "senderId": "agent-1",
                          "senderType": "agent",
                          "senderName": "MingQi",
                          "senderDisplayName": "明启",
                          "senderAvatarUrl": "https://example.test/avatar.png",
                          "createdAt": "2026-07-25T14:01:00.000Z"
                        }
                      ]
                    }
                  },
                  "historyLimited": false
                }
                """.trimIndent(),
                fallbackThreadId = ThreadId("channel-1"),
            )

        val wireMessage = payload.messages.single()
        assertEquals(ThreadReplySummariesAuthority.Embedded, payload.replySummariesAuthority)
        assertEquals(0, wireMessage.replyCount)
        assertEquals(null, wireMessage.threadChannelId)
        assertEquals(1_784_988_000_000L, wireMessage.createdAtEpochMillis)
        val summary = payload.threadSummaries.threads.single()
        assertEquals("parent-1", summary.parentMessageId?.value)
        assertEquals("thread-1", summary.threadChannelId?.value)
        assertEquals(5, summary.replyCount)
        assertEquals(2, summary.unreadCount)
        assertEquals("reply-4", summary.firstUnreadMessageId?.value)
        val preview = summary.latestReplies.single()
        assertEquals("reply-5", preview.messageId)
        assertEquals("MingQi", preview.senderName)
        assertEquals("明启", preview.senderDisplayName)
    }

    @Test
    fun parsesContextEmbeddedThreadSummariesLikeThePageParser() {
        // Task #1142: the server embeds the same summary record on the context endpoint; an
        // anchored open (Activity row, notification, search) must get reply rows on the first
        // frame instead of waiting for the targeted refetch.
        val payload =
            parseSharedMessageContext(
                """
                {
                  "channelId": "channel-1",
                  "targetMessageId": "parent-1",
                  "hasOlder": true,
                  "hasNewer": false,
                  "messages": [
                    {
                      "id": "parent-1",
                      "seq": 10,
                      "channelId": "channel-1",
                      "senderType": "user",
                      "senderId": "u-1",
                      "senderName": "Artin",
                      "messageType": "chat",
                      "content": "parent",
                      "createdAt": "2026-07-25T14:00:00.000Z"
                    }
                  ],
                  "threadSummariesByParentMessageId": {
                    "parent-1": {
                      "threadChannelId": "thread-1",
                      "replyCount": 5,
                      "unreadCount": 2,
                      "firstUnreadMessageId": "reply-4"
                    }
                  },
                  "historyLimited": false,
                  "channelArchived": false
                }
                """.trimIndent(),
                fallbackThreadId = ThreadId("channel-1"),
            )

        assertEquals(ThreadReplySummariesAuthority.Embedded, payload.replySummariesAuthority)
        assertEquals("parent-1", payload.targetMessageId?.value)
        assertEquals(true, payload.hasOlder)
        val summary = payload.threadSummaries.threads.single()
        assertEquals("parent-1", summary.parentMessageId?.value)
        assertEquals("thread-1", summary.threadChannelId?.value)
        assertEquals(5, summary.replyCount)
        assertEquals(2, summary.unreadCount)
        assertEquals("reply-4", summary.firstUnreadMessageId?.value)
    }

    @Test
    fun contextMalformedEmbeddedSummariesAreEmbeddedInvalidNotUnavailable() {
        // The distinction drives whether the store still waits for the targeted refetch:
        // a present-but-malformed record must not be read as "the server sent nothing".
        fun parse(summaryField: String) =
            parseSharedMessageContext(
                """{"channelId":"channel-1","messages":[]$summaryField}""",
                fallbackThreadId = ThreadId("channel-1"),
            ).replySummariesAuthority
        assertEquals(ThreadReplySummariesAuthority.Embedded, parse(""", "threadSummariesByParentMessageId": {}"""))
        assertEquals(ThreadReplySummariesAuthority.EmbeddedInvalid, parse(""", "threadSummariesByParentMessageId": []"""))
        assertEquals(ThreadReplySummariesAuthority.EmbeddedInvalid, parse(""", "threadSummariesByParentMessageId": null"""))
        assertEquals(
            ThreadReplySummariesAuthority.EmbeddedInvalid,
            parse(""", "threadSummariesByParentMessageId": {"parent-1": "not-an-object"}"""),
        )
    }

    @Test
    fun contextWithoutEmbeddedSummariesStaysUnavailable() {
        val payload =
            parseSharedMessageContext(
                """{"channelId":"channel-1","messages":[]}""",
                fallbackThreadId = ThreadId("channel-1"),
            )
        assertEquals(ThreadReplySummariesAuthority.Unavailable, payload.replySummariesAuthority)
        assertEquals(0, payload.threadSummaries.threads.size)
    }

    @Test
    fun parsesThreadSummaryIntoDomainMessageReplyMetadata() {
        val payload =
            parseSharedChannelMessages(
                """
                {
                  "messages": [
                    {
                      "id": "m-1",
                      "seq": 10,
                      "channelId": "channel-1",
                      "senderType": "user",
                      "senderId": "u-1",
                      "senderName": "Artin",
                      "messageType": "chat",
                      "content": "parent",
                      "createdAt": "2026-06-06T00:00:00.000Z",
                      "threadSummary": {
                        "threadChannelId": "thread-1",
                        "replyCount": 4,
                        "unreadCount": 2,
                        "firstUnreadMessageId": "reply-2"
                      }
                    }
                  ],
                  "historyLimited": false
                }
                """.trimIndent(),
                fallbackThreadId = ThreadId("channel-1")
            )

        val message = payload.messages.single()
        assertEquals(ThreadReplySummariesAuthority.Unavailable, payload.replySummariesAuthority)
        assertEquals("thread-1", message.threadChannelId?.value)
        assertEquals(4, message.replyCount)
        assertEquals(2, message.unreadReplyCount)
        assertEquals("reply-2", message.firstUnreadReplyMessageId?.value)
    }

    @Test
    fun topLevelReplyCountsOverrideNestedThreadSummaryCounts() {
        val payload =
            parseSharedChannelMessages(
                """
                {
                  "messages": [
                    {
                      "id": "m-1",
                      "channelId": "channel-1",
                      "senderType": "user",
                      "senderId": "u-1",
                      "messageType": "chat",
                      "content": "parent",
                      "createdAt": "2026-06-06T00:00:00.000Z",
                      "replyCount": 5,
                      "unreadReplyCount": 3,
                      "threadSummary": {
                        "threadChannelId": "thread-1",
                        "replyCount": 4,
                        "unreadCount": 2
                      }
                    }
                  ]
                }
                """.trimIndent(),
                fallbackThreadId = ThreadId("channel-1")
            )

        val message = payload.messages.single()
        assertEquals("thread-1", message.threadChannelId?.value)
        assertEquals(5, message.replyCount)
        assertEquals(3, message.unreadReplyCount)
    }

    @Test
    fun preservesInitialMessageReactionsMembershipStatusAndAttachmentDimensions() {
        val payload =
            parseSharedChannelMessages(
                """
                {
                  "messages": [
                    {
                      "id": "m-2",
                      "channelId": "channel-1",
                      "senderType": "user",
                      "senderId": "u-1",
                      "senderName": "Artin",
                      "senderMembershipStatus": "removed",
                      "messageType": "chat",
                      "content": "image",
                      "randomId": "rid-parser-1",
                      "createdAt": "2026-06-06T00:00:00.000Z",
                      "attachments": [
                        {
                          "id": "att-1",
                          "filename": "diagram.png",
                          "mimeType": "image/png",
                          "sizeBytes": 2048,
                          "width": 640,
                          "height": 360
                        }
                      ],
                      "reactions": [
                        {
                          "emoji": "\uD83D\uDC40",
                          "count": 2,
                          "reactorIds": ["u-1", "agent-1"],
                          "reactorNames": ["Artin", "Bot"]
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent(),
                fallbackThreadId = ThreadId("channel-1")
            )

        val message = payload.messages.single()
        assertEquals("removed", message.senderMembershipStatus)
        assertEquals("rid-parser-1", message.randomId)

        val attachment = message.attachments.single()
        assertEquals(2048, attachment.sizeBytes)
        assertEquals("", attachment.sizeLabel)
        assertEquals(640, attachment.width)
        assertEquals(360, attachment.height)

        val reaction = assertNotNull(message.reactions).single()
        assertEquals("\uD83D\uDC40", reaction.emoji)
        assertEquals(2, reaction.count)
        assertEquals(listOf("u-1", "agent-1"), reaction.reactorIds)
        assertEquals(listOf("Artin", "Bot"), reaction.reactorNames)
    }

    @Test
    fun unknownSenderKindDropsOnlyInvalidRestMessageWithBoundedDiagnostic() {
        val diagnostics = mutableListOf<String>()
        val payload =
            parseSharedChannelMessages(
                bodyText =
                    """
                    {
                      "messages": [
                        {
                          "id": "bad-message-secret-id",
                          "channelId": "channel-1",
                          "senderType": "future sender kind with long suffix 123456789",
                          "senderId": "secret-user-id",
                          "messageType": "chat",
                          "content": "secret body"
                        },
                        {
                          "id": "bad-system-sender",
                          "channelId": "channel-1",
                          "senderType": "system",
                          "senderId": "system",
                          "messageType": "chat",
                          "content": "invalid frontend sender"
                        },
                        {
                          "id": "good-system-message",
                          "channelId": "channel-1",
                          "senderType": "user",
                          "senderId": "system",
                          "messageType": "system",
                          "content": "valid system row"
                        },
                        {
                          "id": "good-message",
                          "channelId": "channel-1",
                          "senderType": "agent",
                          "senderId": "agent-1",
                          "messageType": "chat",
                          "content": "kept"
                        }
                      ]
                    }
                    """.trimIndent(),
                fallbackThreadId = ThreadId("channel-1"),
                diagnostics = diagnostics::add,
            )

        assertEquals(listOf("good-system-message", "good-message"), payload.messages.map { it.id.value })
        assertEquals(MemberKind.System, payload.messages.first().author.kind)
        assertEquals(
            listOf(
                "projection_value_rejected source=rest_message_sender " +
                    "field=member_kind value=future_sender_kind_with_long_suf",
                "projection_value_rejected source=rest_message_sender " +
                    "field=member_kind value=system",
            ),
            diagnostics,
        )
        assertEquals(false, diagnostics.any { it.contains("secret") })
    }
}
