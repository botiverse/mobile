/*
 * T-wrapper canonical fact codec — DoD ⑤ codec-layer gates (RFC 043 §9.9, form A).
 *
 * Driver-free (commonTest). Covers: full-field round-trip (= the (甲) manifest→envelope reverse-closure
 * proof — a dropped field would lose its distinct value), (乙) codec-layer resolved three-state
 * (nullable null/value + list empty/non-empty byte-faithful, no explicitNulls=false flattening),
 * ③ golden-bytes pin (write-drift guard), and per-row fail-closed decode (poison-gate input).
 *
 * The store-level gates (① projection-tamper→restore-unchanged, ② columns==projector(decode), and the
 * (乙) continuation layer) live in the androidUnitTest store suite — they need the real driver.
 */
package build.raft.app.network.sync

import build.raft.app.model.ChannelId
import build.raft.app.model.MessageId
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CanonicalFactCodecTest {

    /** Every field distinct + all nested populated: a dropped envelope field loses its value here. */
    private fun fullyPopulatedRow() = CanonicalMessageRow(
        id = MessageId("m-1"),
        channelId = ChannelId("c-1"),
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
    )

    @Test
    fun fullFieldRoundTripIsIdentity_reverseClosureProof() {
        val row = fullyPopulatedRow()
        val restored = decodeCanonicalFact(encodeCanonicalFact(row))
        // Full structural equality: if the envelope omitted ANY canonical field or nested field, the
        // distinct value above would not survive — this IS the (甲) manifest→envelope reverse-closure gate.
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
            id = MessageId("m1"), channelId = ChannelId("c1"), seq = 1,
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
