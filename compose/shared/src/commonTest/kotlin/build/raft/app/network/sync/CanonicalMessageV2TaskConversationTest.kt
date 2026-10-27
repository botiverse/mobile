package build.raft.app.network.sync

import build.raft.app.canonical.message.CanonicalConversationContextWire
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Task v2: a task conversation's messages (`message:new` / `messages/channel/{conv}` with
 * `conversationContext.channelType = "task"`, botiverse/slock `packages/server/src/services/messageService.ts`
 * `buildFrontendConversationContext` @ 95703ec22e319f526dedf6c899751929b71062d7: `taskId`,
 * `parentChannelId` / `parentChannelType` of the task's channel, never a `parentMessageId`) normalize
 * into a flat top-level `task` scope keyed by the conversation channel id.
 */
class CanonicalMessageV2TaskConversationTest {
    @Test
    fun taskConversationContextNormalizesIntoATaskScopeAndRoundTripsThroughTheCodec() {
        listOf("channel", "private", "dm", "joint", null).forEach { parentType ->
            val normalized =
                assertIs<CanonicalMessageV2NormalizationResult.Normalized>(
                    normalizeCanonicalMessageV2(
                        input(
                            CanonicalConversationContextWire(
                                channelType = "task",
                                parentChannelId = "channel-1",
                                parentChannelType = parentType,
                            )
                        )
                    ),
                    "parentType=$parentType",
                ).envelope
            val context = assertIs<CanonicalConversationContextV2.TopLevel>(normalized.context, "parentType=$parentType")
            assertEquals(TopLevelScopeRefV2.Task("server-a", "conv-1", "channel-1"), context.scope, "parentType=$parentType")
            assertNull(normalized.childReplyScope, "parentType=$parentType")
            assertNull(normalized.replyMembership, "parentType=$parentType")

            val encoded = encodeCanonicalMessageV2(normalized, durablePartitionServerId = "server-a")
            val decoded = assertIs<CanonicalMessageV2DecodeResult.Accepted>(
                decodeCanonicalMessageV2(encoded, durablePartitionServerId = "server-a"),
                "parentType=$parentType",
            ).envelope
            assertEquals(normalized, decoded, "parentType=$parentType")
        }
    }

    @Test
    fun malformedTaskContextsAndUnknownKindsAreRejected() {
        // (case, producer context, child reply scope)
        val cases =
            listOf(
                Triple("no parent channel", CanonicalConversationContextWire(channelType = "task"), null),
                Triple("blank parent channel", CanonicalConversationContextWire(channelType = "task", parentChannelId = " "), null),
                Triple("parent message", CanonicalConversationContextWire("task", "channel-1", "channel", "message-root"), null),
                Triple("self parent", CanonicalConversationContextWire(channelType = "task", parentChannelId = "conv-1"), null),
                Triple("thread parent", CanonicalConversationContextWire("task", "channel-1", "thread"), null),
                Triple("task parent", CanonicalConversationContextWire("task", "channel-1", "task"), null),
                Triple("nested reply scope", CanonicalConversationContextWire("task", "channel-1", "channel"), "thread-1"),
                Triple("unknown kind", CanonicalConversationContextWire(channelType = "task_v3", parentChannelId = "channel-1"), null),
                Triple("unknown kind no parent", CanonicalConversationContextWire(channelType = "workspace"), null),
            )
        cases.forEach { (name, context, childReplyScopeId) ->
            val result = normalizeCanonicalMessageV2(input(context, childReplyScopeId))
            assertEquals(
                CANONICAL_MESSAGE_V2_INVALID_CONTEXT,
                assertIs<CanonicalMessageV2NormalizationResult.Rejected>(result, name).diagnostic,
                name,
            )
        }
    }

    @Test
    fun codecRejectsATaskScopeAsAReplyParentOrWithoutItsParent() {
        val cases =
            listOf(
                "task scope without parent" to
                    """{"kind":"top-level","scope":{"kind":"task","serverId":"server-a","scopeId":"conv-1"}}""",
                "task scope with parent message key" to
                    """{"kind":"top-level","scope":{"kind":"task","serverId":"server-a","scopeId":"conv-1","parentScopeId":"channel-1","parentMessageId":"m"}}""",
                "channel scope with task parent key" to
                    """{"kind":"top-level","scope":{"kind":"channel","serverId":"server-a","scopeId":"channel-1","parentScopeId":"channel-0"}}""",
            )
        cases.forEach { (name, context) ->
            assertIs<CanonicalMessageV2DecodeResult.Rejected>(decodeCanonicalMessageV2Context(json(context)), name)
        }
        val replyUnderTask =
            decodeCanonicalMessageV2Context(
                context =
                    json(
                        """{"kind":"thread","replyScope":{"serverId":"server-a","scopeId":"thread-1"},""" +
                            """"parentTopLevelScope":{"kind":"task","serverId":"server-a","scopeId":"conv-1","parentScopeId":"channel-1"},""" +
                            """"rootMessageRef":{"serverId":"server-a","messageId":"root-1"}}"""
                    ),
                replyMembership =
                    json("""{"root":{"serverId":"server-a","messageId":"root-1"},"parentScope":{"serverId":"server-a","scopeId":"thread-1"}}"""),
            )
        assertIs<CanonicalMessageV2DecodeResult.Rejected>(replyUnderTask)
        assertIs<CanonicalMessageV2DecodeResult.Accepted>(
            decodeCanonicalMessageV2Context(
                json("""{"kind":"top-level","scope":{"kind":"task","serverId":"server-a","scopeId":"conv-1","parentScopeId":"channel-1"}}""")
            )
        )
    }

    private fun input(
        context: CanonicalConversationContextWire,
        childReplyScopeId: String? = null,
    ) = CanonicalMessageV2NormalizationInput(
        partition = DurablePartitionKey("server-a", "principal-a", SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID, "env-test"),
        scopeId = "conv-1",
        fact =
            CanonicalMessageNodeV2(
                id = "reply-1",
                seq = 4,
                senderType = "user",
                senderId = "user-1",
                content = "on it",
                messageType = "chat",
                createdAt = "2026-10-27T10:00:00.000Z",
                senderName = "Artin",
            ),
        producerContext = context,
        childReplyScopeId = childReplyScopeId,
    )

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)
}
