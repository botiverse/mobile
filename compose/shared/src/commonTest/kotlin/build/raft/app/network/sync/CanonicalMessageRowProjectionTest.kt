package build.raft.app.network.sync

import build.raft.app.canonical.message.CanonicalAttachmentWire
import build.raft.app.canonical.message.CanonicalCommentHostSourceWire
import build.raft.app.canonical.message.CanonicalCommentRefWire
import build.raft.app.canonical.message.CanonicalConversationContextWire
import build.raft.app.canonical.message.CanonicalExternalAuthorWire
import build.raft.app.canonical.message.CanonicalMentionWire
import build.raft.app.canonical.message.CanonicalMessageRow
import build.raft.app.canonical.message.CanonicalReactionWire
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberKind
import build.raft.app.model.MessageType
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CanonicalMessageRowProjectionTest {

    private fun row(
        senderType: String = "agent",
        messageType: String = "chat",
        senderDescription: String? = "Builder",
        attachments: List<CanonicalAttachmentWire>? = null,
        reactions: List<CanonicalReactionWire>? = null,
        actionMetadata: kotlinx.serialization.json.JsonElement? = null,
        conversationContext: CanonicalConversationContextWire? = null,
        commentRef: CanonicalCommentRefWire? = null,
    ) = CanonicalMessageRow(
        id = "m1",
        channelId = "c1",
        seq = 42,
        senderType = senderType,
        senderId = "u1",
        content = "hello",
        messageType = messageType,
        createdAt = "2026-07-12T04:00:00Z",
        randomId = "r1",
        threadId = "thread-c1",
        senderName = "Ada",
        senderDescription = senderDescription,
        senderMembershipStatus = "active",
        mentions = listOf(CanonicalMentionWire("u2", "Bob", "human")),
        attachments = attachments ?: listOf(
            CanonicalAttachmentWire(
                id = "a1",
                filename = "image.png",
                mimeType = "image/png",
                sizeBytes = 1536,
                width = 640,
                height = 480,
                thumbnailUrl = "https://example.test/thumb.png",
            ),
        ),
        reactions = reactions ?: listOf(CanonicalReactionWire("🔥", 2, listOf("u2"), listOf("Bob"))),
        commentRef = commentRef,
        actionMetadata = actionMetadata,
        conversationContext = conversationContext,
    )

    @Test
    fun mapsCanonicalFieldsAndRebuildsFlatSender() {
        var hydrationSawFallback = false
        val message =
            assertNotNull(
                row(
                    conversationContext = CanonicalConversationContextWire(
                        channelType = "direct_message",
                        parentChannelId = "parent-c",
                        parentChannelType = "joint",
                        parentMessageId = "parent-m",
                    ),
                    commentRef = CanonicalCommentRefWire(
                        attachmentId = "a1",
                        filename = "image.png",
                        hostMessageId = "host-m",
                        hostSource = CanonicalCommentHostSourceWire(
                            type = "thread",
                            routeKind = "channel",
                            channelId = "host-c",
                        ),
                        anchorLabel = "line 4",
                        anchorQuote = "wire-only quote",
                    ),
                ).toAppMessage { fallback ->
                    hydrationSawFallback = true
                    assertEquals("u1", fallback.id.value)
                    assertEquals(MemberKind.Agent, fallback.kind)
                    assertEquals("Builder", fallback.description)
                    assertNull(fallback.membershipStatus)
                    fallback.copy(serverRole = "admin")
                },
            )

        assertTrue(hydrationSawFallback)
        assertEquals("m1", message.id.value)
        assertEquals("c1", message.threadId.value)
        assertEquals("c1", message.channelId?.value)
        assertNull(message.threadChannelId)
        assertEquals(42, message.seq)
        assertEquals("Ada", message.author.displayName)
        assertEquals("admin", message.author.serverRole)
        assertEquals("2026-07-12T04:00:00Z", message.timestampLabel)
        assertEquals(1_783_828_800_000L, message.createdAtEpochMillis)
        assertEquals("hello", message.body)
        assertEquals(MessageType.Chat, message.messageType)
        assertEquals("r1", message.randomId)
        assertEquals(1536, message.attachments.single().sizeBytes)
        assertEquals("", message.attachments.single().sizeLabel)
        assertEquals(640, message.attachments.single().width)
        assertEquals(0, message.attachments.single().commentCount)
        assertEquals("🔥", message.reactions?.single()?.emoji)
        assertEquals("host-c", message.commentRef?.hostSource?.channelId?.value)
        assertEquals(ConversationKind.DirectMessage, message.conversationContext?.channelKind)
        assertEquals(ConversationKind.Joint, message.conversationContext?.parentChannelKind)
        assertEquals("parent-m", message.conversationContext?.parentMessageId?.value)
        assertEquals("active", message.senderMembershipStatus)
        assertNull(message.task)
        assertNull(message.updatedAtLabel)
    }

    @Test
    fun parsesSystemMessageTypeAndActionCardMetadata() {
        val actionMetadata = buildJsonObject {
            put("kind", "action-card")
            put("state", "prepared")
            put("action", buildJsonObject {
                put("type", "create-channel")
                put("name", "Launch")
                put("humans", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("u1")) })
            })
        }
        val message =
            assertNotNull(
                row(senderType = "user", messageType = "SYSTEM", actionMetadata = actionMetadata)
                    .toAppMessage(),
            )

        assertEquals(MessageType.System, message.messageType)
        assertEquals(MemberKind.System, message.author.kind)
        assertEquals("action-card", message.metadata["actionCard.kind"])
        assertEquals("create-channel", message.metadata["actionCard.type"])
        assertEquals("Launch", message.metadata["actionCard.name"])
    }

    @Test
    fun projectsForwardBundleFromRawActionMetadata() {
        val actionMetadata = buildJsonObject {
            put("kind", "forwarded-bundle")
            put("forwardedItems", buildJsonArray {
                add(buildJsonObject {
                    put("sourceMessageId", "source-m")
                    put("sourceCreatedAt", "t1")
                    put("contentSnapshot", "forwarded body")
                    put("sourceAuthorSnapshot", buildJsonObject {
                        put("type", "human")
                        put("id", "u9")
                        put("name", "Nina")
                    })
                })
            })
        }
        val message = assertNotNull(row(actionMetadata = actionMetadata).toAppMessage())

        assertTrue(message.metadata.isEmpty())
        assertEquals("Forwarded", message.forwardBundle?.title)
        assertEquals("source-m", message.forwardBundle?.messages?.single()?.id)
        assertEquals("forwarded body", message.forwardBundle?.messages?.single()?.body)
    }

    @Test
    fun unknownWireKindsFailClosedWithBoundedDiagnostics() {
        val diagnostics = mutableListOf<String>()
        val message =
            row(
                senderType = "Future Sender Kind With Secret Suffix 123456789",
                messageType = "chat",
                actionMetadata = buildJsonObject { put("future", true) },
                conversationContext = CanonicalConversationContextWire(
                    channelType = "future-channel-kind",
                    parentChannelType = "future-parent-kind",
                ),
                commentRef = CanonicalCommentRefWire(
                    attachmentId = "a1",
                    filename = "image.png",
                    hostSource = CanonicalCommentHostSourceWire(
                        type = "future-host-kind",
                        routeKind = "future-route-kind",
                        channelId = "c2",
                    ),
                ),
            ).toAppMessage(diagnostics = diagnostics::add)

        assertNull(message)
        assertEquals(
            listOf(
                "projection_value_rejected source=canonical_message_sender " +
                    "field=member_kind value=future_sender_kind_with_secret_s",
            ),
            diagnostics,
        )
    }

    @Test
    fun externalProjectionSenderProjectsItsFrozenAuthorLikeTheRealtimeMapper() {
        val author = CanonicalExternalAuthorWire(
            actorKind = "human",
            actorProjectionRevision = 3,
            appRegistrationId = "app-1",
            avatarUrl = "https://avatars.slack-edge.com/u123.png",
            displayName = "jinlu",
            externalActorId = "U123",
            externalConversationId = "C42",
            externalMessageId = "1700000000.000100",
            installId = "inst-1",
            projectionId = "proj-1",
            provider = "slack",
            workspaceId = "T1",
            workspaceName = "Acme",
        )
        val message = assertNotNull(row(senderType = "external_projection").copy(externalAuthor = author).toAppMessage())
        assertEquals(MemberKind.External, message.author.kind)
        assertEquals("jinlu", message.author.displayName)
        assertEquals("Slack", message.author.description)
        assertEquals("https://avatars.slack-edge.com/u123.png", message.author.avatarUrl)
        val projected = assertNotNull(message.externalAuthor)
        assertEquals("slack", projected.provider)
        assertEquals("U123", projected.externalActorId)
        assertEquals(3L, projected.actorProjectionRevision)

        // An ordinary sender never surfaces an author, even if a row carried one.
        assertNull(assertNotNull(row().copy(externalAuthor = author).toAppMessage()).externalAuthor)

        // An external sender without its author is kept with a fallback author (realtime parity),
        // with a payload-free diagnostic.
        val diagnostics = mutableListOf<String>()
        val kept = assertNotNull(row(senderType = "external_projection").toAppMessage(diagnostics = diagnostics::add))
        assertEquals(MemberKind.External, kept.author.kind)
        assertEquals("Ada", kept.author.displayName)
        assertEquals("Ada", assertNotNull(kept.externalAuthor).displayName)
        assertEquals(
            listOf("projection_value_defaulted source=canonical_message_sender field=externalAuthor value=missing messageId=m1"),
            diagnostics,
        )
    }

    @Test
    fun externalSenderWithBlankIdAndPartialAuthorKeepsRowWithFallbackAuthor() {
        // The decoder stores absent author fields blank (revision 0); the projection reads them as absent.
        val partial = CanonicalExternalAuthorWire(
            actorKind = "",
            actorProjectionRevision = 0,
            appRegistrationId = "",
            displayName = "",
            externalActorId = "",
            externalConversationId = "",
            externalMessageId = "",
            installId = "",
            projectionId = "",
            provider = "slack",
            workspaceId = "",
        )
        val blankSender = row(senderType = "third_party_app").copy(senderId = "", senderName = "", externalAuthor = partial)
        val message = assertNotNull(blankSender.toAppMessage(diagnostics = {}))
        assertEquals(MemberKind.External, message.author.kind)
        assertEquals("external:message:m1", message.author.id.value)
        assertEquals("Slack", message.author.description)
        val author = assertNotNull(message.externalAuthor)
        assertTrue(author.displayNameIsFallback)
        assertEquals("slack", author.provider)
        assertEquals("unknown", author.actorKind)
        assertNull(author.projectionId)
        assertNull(author.actorProjectionRevision)

        // A user/agent row is never widened: the blank id stays as-is (no synthetic key, no external author).
        val user = assertNotNull(row(senderType = "user").copy(senderId = "").toAppMessage(diagnostics = {}))
        assertEquals("", user.author.id.value)
        assertNull(user.externalAuthor)
    }

    @Test
    fun rawSystemSenderIsRejectedAtFrontendWireBoundary() {
        val diagnostics = mutableListOf<String>()
        val message =
            row(
                senderType = "system",
                messageType = "chat",
                senderDescription = "",
            ).toAppMessage(diagnostics = diagnostics::add)

        assertNull(message)
        assertEquals(
            listOf(
                "projection_value_rejected source=canonical_message_sender " +
                    "field=member_kind value=system",
            ),
            diagnostics,
        )
    }

    @Test
    fun adversarialValuesMatchLegacyMapperFailSoftRules() {
        val message =
            assertNotNull(
                row(
                    senderType = "user",
                    messageType = "chat",
                    senderDescription = "",
                    attachments = listOf(
                        CanonicalAttachmentWire(
                            id = "a-empty",
                            filename = "",
                            mimeType = "",
                            sizeBytes = 0,
                        ),
                    ),
                    reactions = listOf(
                        CanonicalReactionWire(emoji = "", count = 1),
                        CanonicalReactionWire(emoji = "ok", count = -1),
                    ),
                    commentRef = CanonicalCommentRefWire(attachmentId = "", filename = ""),
                ).toAppMessage(),
            )

        assertEquals(MemberKind.Human, message.author.kind)
        // Empty wire strings stay empty; the mapper must not invent presentation fallbacks.
        assertEquals("", message.author.description)
        assertEquals("", message.attachments.single().name)
        assertEquals("", message.attachments.single().mimeType)
        // Legacy mapper drops malformed reactions and an invalid commentRef.
        assertNull(message.reactions)
        assertNull(message.commentRef)
    }
}
