/*
 * T-wrapper canonical fact codec — DoD ⑤ codec-layer gates (RFC 043 §9.9, form A).
 *
 * Driver-free (commonTest). Covers: full-field round-trip identity, (乙) codec-layer resolved three-state
 * (nullable null/value + list empty/non-empty byte-faithful, no explicitNulls=false flattening),
 * ③ golden-bytes pin (write-drift guard), and per-row fail-closed decode (poison-gate input).
 *
 * The (甲) MECHANICAL manifest→envelope reverse-closure is a separate androidUnitTest
 * ([CanonicalFactEnvelopeManifestGateTest]) — it needs the SHA-pinned manifest + reflection, and a
 * hand-built round-trip cannot prove the envelope covers a manifest field nobody populated (AD2).
 *
 * The store-level gates (① projection-tamper→restore-unchanged, ② columns==projector(decode), and the
 * (乙) continuation layer) live in the androidUnitTest store suite — they need the real driver.
 */
package build.raft.app.network.sync

import build.raft.app.canonical.message.CanonicalAttachmentWire
import build.raft.app.canonical.message.CanonicalCommentHostSourceWire
import build.raft.app.canonical.message.CanonicalCommentRefWire
import build.raft.app.canonical.message.CanonicalConversationContextWire
import build.raft.app.canonical.message.CanonicalExternalAuthorWire
import build.raft.app.canonical.message.CanonicalMentionWire
import build.raft.app.canonical.message.CanonicalMessageRow
import build.raft.app.canonical.message.CanonicalReactionWire
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CanonicalFactCodecTest {

    /** Every field distinct + all nested populated: a dropped envelope field loses its value here. */
    private fun fullyPopulatedRow() = CanonicalMessageRow(
        id = "m-1",
        channelId = "c-1",
        seq = 42,
        senderType = "agent",
        senderId = "u-1",
        content = "hello",
        messageType = "chat",
        createdAt = "2026-07-12T00:00:00Z",
        randomId = "r-1",
        threadId = "t-1",
        senderName = "Alice",
        senderDescription = "eng",
        senderMembershipStatus = "active",
        mentions = listOf(CanonicalMentionWire("men-1", "Bob", "user")),
        attachments = listOf(CanonicalAttachmentWire("a-1", "f.txt", "text/plain", 12288, 4, 5, "https://cdn/x")),
        reactions = listOf(CanonicalReactionWire("👍", 2, listOf("u2", "u3"), listOf("B", "C"))),
        commentRef = CanonicalCommentRefWire(
            attachmentId = "a-1",
            filename = "f.txt",
            hostMessageId = "hm-1",
            hostSource = CanonicalCommentHostSourceWire("channel", "channel", "c-host", "pm-1", "tc-1", "rtc-1"),
            anchorLabel = "line 3",
            anchorQuote = "quoted",
        ),
        actionMetadata = buildJsonObject { put("kind", JsonPrimitive("card")) },
        conversationContext = CanonicalConversationContextWire("channel", "pc-1", "channel", "pm-2"),
        externalAuthor = externalAuthor(),
    )

    private fun externalAuthor(
        avatarUrl: String? = "https://avatars.slack-edge.com/u123.png",
        avatarDigest: String? = "sha256:abc",
        workspaceName: String? = "Acme",
    ) = CanonicalExternalAuthorWire(
        actorKind = "human",
        actorProjectionRevision = 7,
        appRegistrationId = "app-1",
        avatarDigest = avatarDigest,
        avatarUrl = avatarUrl,
        displayName = "jinlu",
        externalActorId = "U123",
        externalConversationId = "C42",
        externalMessageId = "1700000000.000100",
        installId = "inst-1",
        projectionId = "proj-1",
        provider = "slack",
        workspaceId = "T1",
        workspaceName = workspaceName,
    )

    @Test
    fun fullFieldRoundTripIsIdentity() {
        val row = fullyPopulatedRow()
        val restored = decodeCanonicalFact(encodeCanonicalFact(row))
        // Every populated canonical + nested field survives encode→decode. (Envelope↔manifest COMPLETENESS
        // is the separate mechanical gate CanonicalFactEnvelopeManifestGateTest, not this hand-built row.)
        assertEquals(row, restored)
    }

    @Test
    fun resolvedThreeStateIsByteFaithful() {
        // Nullable null vs value + list empty vs non-empty must all survive; explicitNulls=false / non-null
        // defaults would flatten resolved null->[] or drop nulls = silent continuation drift ((乙) codec).
        val nulls = fullyPopulatedRow().copy(
            senderDescription = null,
            senderMembershipStatus = null,
            randomId = null,
            threadId = null,
            commentRef = null,
            actionMetadata = null,
            conversationContext = null,
            mentions = emptyList(),
            attachments = emptyList(),
            reactions = emptyList(),
        )
        val encoded = encodeCanonicalFact(nulls)
        // resolved null serializes AS null (not dropped); empty aggregate serializes as [] (not null/absent).
        assertTrue(encoded.contains("\"commentRef\":null"), "resolved null must be explicit: $encoded")
        assertTrue(encoded.contains("\"reactions\":[]"), "empty aggregate must be []: $encoded")
        val restored = decodeCanonicalFact(encoded)
        assertEquals(nulls, restored)
        assertEquals(emptyList(), restored?.reactions) // stayed empty, NOT null
        assertNull(restored?.commentRef) // stayed null, NOT []
    }

    @Test
    fun goldenBytesPin_writeDriftGuard() {
        // Byte-exact snapshot of the V1 codec output for a fixed minimal row. A silent codec change (field
        // order, explicitNulls/encodeDefaults, added/renamed field) reshapes this string = RED. Re-pin ONLY
        // with an explicit version bump.
        val row = CanonicalMessageRow(
            id = "m1", channelId = "c1", seq = 1,
            senderType = "human", senderId = "u1", content = "hi", messageType = "chat",
            createdAt = "t", senderName = "A",
        )
        val golden =
            "{\"version\":1,\"id\":\"m1\",\"channelId\":\"c1\",\"seq\":1,\"senderType\":\"human\"," +
                "\"senderId\":\"u1\",\"content\":\"hi\",\"messageType\":\"chat\",\"createdAt\":\"t\"," +
                "\"randomId\":null,\"threadId\":null,\"senderName\":\"A\",\"senderDescription\":null," +
                "\"senderMembershipStatus\":null,\"mentions\":[],\"attachments\":[],\"reactions\":[]," +
                "\"commentRef\":null,\"actionMetadata\":null,\"conversationContext\":null}"
        assertEquals(golden, encodeCanonicalFact(row))
    }

    @Test
    fun externalAuthorRoundTripsWithNullableLeavesAndIsOmittedWhenNull() {
        val base = CanonicalMessageRow(
            id = "m1", channelId = "c1", seq = 1,
            senderType = "external_projection", senderId = "ext_slack_U123", content = "hi", messageType = "chat",
            createdAt = "t", senderName = "jinlu",
        )
        // Populated, and with every nullable leaf null: both survive encode -> decode exactly.
        for (author in listOf(externalAuthor(), externalAuthor(avatarUrl = null, avatarDigest = null, workspaceName = null))) {
            val row = base.copy(externalAuthor = author)
            val encoded = encodeCanonicalFact(row)
            assertTrue(encoded.contains("\"externalAuthor\":{\"actorKind\":\"human\",\"actorProjectionRevision\":7,"), encoded)
            assertEquals(row, decodeCanonicalFact(encoded))
        }
        assertTrue(encodeCanonicalFact(base.copy(externalAuthor = externalAuthor(workspaceName = null))).contains("\"workspaceName\":null"))
        // Null (every user/agent/system sender) omits the key, so pre-existing BLOBs stay byte-identical.
        val none = encodeCanonicalFact(base)
        assertTrue("externalAuthor" !in none, none)
        assertNull(decodeCanonicalFact(none)?.externalAuthor)
    }

    @Test
    fun decodeFailsClosedOnMalformed() {
        assertNull(decodeCanonicalFact("not-json"))
        assertNull(decodeCanonicalFact("{\"version\":1}")) // missing required fields
    }

    @Test
    fun decodeFailsClosedOnUnknownVersion() {
        val v999 = encodeCanonicalFact(fullyPopulatedRow()).replaceFirst("\"version\":1", "\"version\":999")
        assertNull(decodeCanonicalFact(v999), "unknown version must fail closed (read-old guard)")
    }
}
