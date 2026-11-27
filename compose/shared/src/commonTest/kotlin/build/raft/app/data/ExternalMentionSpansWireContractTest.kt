package build.raft.app.data

import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.ChannelId
import build.raft.app.model.ExternalMentionSpan
import build.raft.app.model.ExternalMessageAuthor
import build.raft.app.model.MemberKind
import build.raft.app.model.Message
import build.raft.app.model.MessageMention
import build.raft.app.model.MessageNew
import build.raft.app.model.MessageUpdated
import build.raft.app.model.SyncResumeResponse
import build.raft.app.model.ThreadId
import build.raft.app.thread.RaftThreadMessageItem
import build.raft.app.thread.RaftThreadScreenPayload
import build.raft.app.thread.mergeRealtimeUpdate
import build.raft.app.thread.threadMarkdownRenderSignature
import build.raft.app.thread.toMessage
import build.raft.app.thread.toRaftThreadMessageItem
import build.raft.app.thread.withIncomingExternalMentionSpans
import build.raft.app.thread.withKnownExternalMentionSpansFrom
import build.raft.app.thread.withoutRetractedContent
import build.raft.app.withKnownExternalMentionSpansFrom
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExternalMentionSpansWireContractTest {

    private val roundTripJson = Json { ignoreUnknownKeys = true }

    @Test
    fun restMessagesDecodeExternalMentionSpansWithoutTouchingMentions() {
        val body = "hi +slack:tygg and @alice"
        val start = body.indexOf("+slack:tygg")
        val end = start + "+slack:tygg".length
        val payload =
            parseSharedChannelMessages(
                """
                {
                  "messages": [{
                    "id": "m-ext-span-1",
                    "seq": 10,
                    "channelId": "c1",
                    "senderType": "external_projection",
                    "senderId": "ext_u1",
                    "senderName": "Slack Sam",
                    "messageType": "chat",
                    "content": "$body",
                    "createdAt": "2026-11-27T10:00:00.000Z",
                    "externalAuthor": {
                      "provider": "slack",
                      "actorKind": "human",
                      "displayName": "Slack Sam"
                    },
                    "mentions": [
                      { "type": "user", "id": "u-alice", "name": "alice" }
                    ],
                    "externalMentionSpans": [
                      { "id": "proj-tygg", "label": "+slack:tygg", "start": $start, "end": $end }
                    ]
                  }]
                }
                """.trimIndent(),
                fallbackThreadId = ThreadId("c1"),
            )

        val message = payload.messages.single()
        assertEquals(
            listOf(ExternalMentionSpan(id = "proj-tygg", label = "+slack:tygg", start = start, end = end)),
            message.externalMentionSpans,
        )
        assertEquals(
            listOf(MessageMention(type = "user", id = "u-alice", name = "alice")),
            message.mentions,
        )
    }

    @Test
    fun restMessagesIgnoreExternalMentionSpansOnNonExternalSenders() {
        val payload =
            parseSharedChannelMessages(
                """
                {
                  "messages": [{
                    "id": "m-user-1",
                    "seq": 11,
                    "channelId": "c1",
                    "senderType": "user",
                    "senderId": "u-1",
                    "senderName": "Alice",
                    "messageType": "chat",
                    "content": "hi +slack:tygg",
                    "createdAt": "2026-11-27T10:01:00.000Z",
                    "externalMentionSpans": [
                      { "id": "proj-tygg", "label": "+slack:tygg", "start": 3, "end": 14 }
                    ]
                  }]
                }
                """.trimIndent(),
                fallbackThreadId = ThreadId("c1"),
            )

        val message = payload.messages.single()
        assertEquals(MemberKind.Human, message.author.kind)
        assertTrue(message.externalMentionSpans.isEmpty())
    }

    @Test
    fun restMessagesDecodeOlderServerWithoutExternalMentionSpans() {
        val payload =
            parseSharedChannelMessages(
                """
                {
                  "messages": [{
                    "id": "m-ext-legacy",
                    "seq": 12,
                    "channelId": "c1",
                    "senderType": "external_projection",
                    "senderId": "ext_u2",
                    "senderName": "Slack Sam",
                    "messageType": "chat",
                    "content": "plain bridged body",
                    "createdAt": "2026-11-27T10:02:00.000Z",
                    "externalAuthor": {
                      "provider": "slack",
                      "actorKind": "human",
                      "displayName": "Slack Sam"
                    }
                  }]
                }
                """.trimIndent(),
                fallbackThreadId = ThreadId("c1"),
            )

        assertTrue(payload.messages.single().externalMentionSpans.isEmpty())
    }

    @Test
    fun lenientDecoderDropsMalformedSpansAndNonArraysWithoutFailingMessage() {
        val nonArray =
            parseSharedChannelMessages(
                """
                {
                  "messages": [{
                    "id": "m-ext-bad-1",
                    "seq": 13,
                    "channelId": "c1",
                    "senderType": "external_projection",
                    "senderId": "ext_u2",
                    "senderName": "Slack Sam",
                    "messageType": "chat",
                    "content": "+slack:a",
                    "createdAt": "2026-11-27T10:03:00.000Z",
                    "externalAuthor": { "provider": "slack", "actorKind": "human", "displayName": "Slack Sam" },
                    "externalMentionSpans": "nope"
                  }]
                }
                """.trimIndent(),
                fallbackThreadId = ThreadId("c1"),
            )
        assertTrue(nonArray.messages.single().externalMentionSpans.isEmpty())

        val mixed =
            parseSharedChannelMessages(
                """
                {
                  "messages": [{
                    "id": "m-ext-bad-2",
                    "seq": 14,
                    "channelId": "c1",
                    "senderType": "external_projection",
                    "senderId": "ext_u2",
                    "senderName": "Slack Sam",
                    "messageType": "chat",
                    "content": "+slack:a",
                    "createdAt": "2026-11-27T10:04:00.000Z",
                    "externalAuthor": { "provider": "slack", "actorKind": "human", "displayName": "Slack Sam" },
                    "externalMentionSpans": [
                      { "id": "p", "label": "+slack:a", "start": 0, "end": 8 },
                      { "id": "p", "label": "+slack:a", "start": 5, "end": 5 },
                      { "id": "p", "label": "+slack:a", "start": 8, "end": 2 },
                      { "id": "", "label": "+slack:a", "start": 0, "end": 8 },
                      { "id": "p", "label": "", "start": 0, "end": 8 },
                      { "id": "p", "label": "+slack:a", "start": -1, "end": 8 },
                      { "id": "p", "label": "+slack:a", "start": "0", "end": 8 },
                      { "id": "p", "label": "+slack:a", "start": 1.5, "end": 8 },
                      "not-an-object",
                      null
                    ]
                  }]
                }
                """.trimIndent(),
                fallbackThreadId = ThreadId("c1"),
            )
        assertEquals(
            listOf(ExternalMentionSpan(id = "p", label = "+slack:a", start = 0, end = 8)),
            mixed.messages.single().externalMentionSpans,
        )
    }

    @Test
    fun realtimeMessageNewUpdatedAndSyncResumeDecodeExternalMentionSpans() {
        val mapper = RealtimeEventMapper()
        val messageJson =
            """
            {
              "id": "m-rt-1",
              "seq": 21,
              "channelId": "c1",
              "senderType": "external_projection",
              "senderId": "ext_u1",
              "senderName": "Slack Sam",
              "messageType": "chat",
              "content": "ping +slack:tygg",
              "createdAt": "2026-11-27T10:05:00.000Z",
              "externalAuthor": { "provider": "slack", "actorKind": "human", "displayName": "Slack Sam" },
              "mentions": [],
              "externalMentionSpans": [
                { "id": "proj-tygg", "label": "+slack:tygg", "start": 5, "end": 16 }
              ]
            }
            """.trimIndent()
        val expected = listOf(ExternalMentionSpan(id = "proj-tygg", label = "+slack:tygg", start = 5, end = 16))

        val created = assertIs<MessageNew>(mapper.map(RealtimeSocketEvents.MessageNew, messageJson))
        assertEquals(expected, created.message.externalMentionSpans)
        assertTrue(created.message.mentions.isEmpty())

        val updated = assertIs<MessageUpdated>(mapper.map(RealtimeSocketEvents.MessageUpdated, messageJson))
        assertEquals(expected, updated.message.externalMentionSpans)

        val resume =
            assertIs<SyncResumeResponse>(
                mapper.map(
                    RealtimeSocketEvents.SyncResumeResponse,
                    """{ "messages": [$messageJson], "currentSeq": 21, "hasMore": false }""",
                )
            )
        assertEquals(expected, resume.messages.single().externalMentionSpans)
    }

    @Test
    fun wireMessageThreadMappingAndCacheSerializationRoundTripPreservesSpans() {
        val span = ExternalMentionSpan(id = "proj-bob", label = "+slack:\"Bob Smith\"", start = 4, end = 22)
        val wire =
            WireMessage(
                id = "m-wire-1",
                seq = 30,
                channelId = "c1",
                senderType = "external_projection",
                senderId = "ext_u3",
                senderName = "Slack Sam",
                messageType = "chat",
                content = "ask +slack:\"Bob Smith\" now",
                createdAt = "2026-11-27T10:06:00.000Z",
                externalAuthor = WireExternalAuthor(provider = "slack", actorKind = "human", displayName = "Slack Sam"),
                externalMentionSpans = roundTripJson.parseToJsonElement(
                    """[{"id":"proj-bob","label":"+slack:\"Bob Smith\"","start":4,"end":22}]"""
                ),
            )
        val payload =
            buildSharedThreadPayloadFromMessagesPage(
                basePayload = RaftThreadScreenPayload(instanceId = "t1", title = "general"),
                page = ThreadMessagesPage(messages = listOf(wire), canLoadMoreBefore = false, canLoadMoreAfter = false),
                session = null,
            )
        val item = payload.messages.single()
        assertEquals(listOf(span), item.externalMentionSpans)

        val encodedItem = roundTripJson.encodeToString(item)
        val decodedItem = roundTripJson.decodeFromString<RaftThreadMessageItem>(encodedItem)
        assertEquals(listOf(span), decodedItem.externalMentionSpans)

        val domain = assertNotNull(item.toMessage(threadId = ThreadId("c1"), channelId = ChannelId("c1")))
        assertEquals(listOf(span), domain.externalMentionSpans)

        val encodedDomain = roundTripJson.encodeToString(domain)
        val decodedDomain = roundTripJson.decodeFromString<Message>(encodedDomain)
        assertEquals(listOf(span), decodedDomain.externalMentionSpans)

        val remappedItem = decodedDomain.toRaftThreadMessageItem(currentUserId = null)
        assertEquals(listOf(span), remappedItem.externalMentionSpans)
    }

    @Test
    fun mergePreservesKnownSpansAcrossSparsePatchAndClearsOnBodyEditOrRetraction() {
        val span = ExternalMentionSpan(id = "proj-tygg", label = "+slack:tygg", start = 3, end = 14)
        val author = ExternalMessageAuthor(displayName = "Slack Sam", provider = "slack", actorKind = "human")
        val known =
            RaftThreadMessageItem(
                id = "m-merge-1",
                authorId = "ext_u1",
                authorName = "Slack Sam",
                authorKind = "external",
                externalAuthor = author,
                body = "hi +slack:tygg",
                externalMentionSpans = listOf(span),
            )

        // Sparse realtime patch keeping same body (e.g. reaction/task patch omitting spans) preserves known spans.
        val sparsePatch = known.copy(externalMentionSpans = emptyList())
        val afterSparse = known.mergeRealtimeUpdate(sparsePatch)
        assertEquals(listOf(span), afterSparse.externalMentionSpans)

        // Slack edit changing body and removing the mention clears stale spans.
        val editedRemoved = known.copy(body = "hi everyone", externalMentionSpans = emptyList())
        val afterRemoved = known.mergeRealtimeUpdate(editedRemoved)
        assertTrue(afterRemoved.externalMentionSpans.isEmpty())

        // Slack edit changing body and replacing the mention takes the new spans.
        val replacement = ExternalMentionSpan(id = "proj-ada", label = "+slack:ada", start = 4, end = 14)
        val editedReplaced = known.copy(body = "hey +slack:ada", externalMentionSpans = listOf(replacement))
        val afterReplaced = known.mergeRealtimeUpdate(editedReplaced)
        assertEquals(listOf(replacement), afterReplaced.externalMentionSpans)

        // Refresh copy without spans keeps known spans when body matches; drops when body differs.
        assertEquals(listOf(span), sparsePatch.withKnownExternalMentionSpansFrom(known).externalMentionSpans)
        assertTrue(editedRemoved.withKnownExternalMentionSpansFrom(known).externalMentionSpans.isEmpty())

        // Domain Message cache merge matches the same policy.
        val knownDomain = assertNotNull(known.toMessage(threadId = ThreadId("c1"), channelId = ChannelId("c1")))
        val sparseDomain = assertNotNull(sparsePatch.toMessage(threadId = ThreadId("c1"), channelId = ChannelId("c1")))
        val editedDomain = assertNotNull(editedRemoved.toMessage(threadId = ThreadId("c1"), channelId = ChannelId("c1")))
        assertEquals(listOf(span), sparseDomain.withKnownExternalMentionSpansFrom(knownDomain).externalMentionSpans)
        assertTrue(editedDomain.withKnownExternalMentionSpansFrom(knownDomain).externalMentionSpans.isEmpty())

        // Incoming copy carrying spans hydrates an already-loaded cached copy with the same body.
        val hydrated = listOf(sparsePatch).withIncomingExternalMentionSpans(listOf(known)).single()
        assertEquals(listOf(span), hydrated.externalMentionSpans)

        // Retraction clears externalMentionSpans on both UI item and domain Message.
        assertTrue(known.copy(deletedAt = "2026-11-27T10:10:00.000Z").withoutRetractedContent().externalMentionSpans.isEmpty())
        assertTrue(knownDomain.copy(deletedAt = "2026-11-27T10:10:00.000Z").withoutRetractedContent().externalMentionSpans.isEmpty())

        // Render signature distinguishes a cached row before vs after spans arrive for the same body.
        assertNotEquals(sparsePatch.threadMarkdownRenderSignature(), known.threadMarkdownRenderSignature())
    }
}
