/*
 * #607 gate (d): the in-process canonical carry on MessageNew/MessageUpdated is @Transient — it is an
 * in-process gateway→accept hand-off only, never part of the wire/cache shape. A serialization round-trip
 * must drop it (deserialized events carry null and the shadow fold lane skips them, fail-open) while the
 * authoritative message survives intact.
 */
package build.raft.app.model

import build.raft.app.canonical.message.CanonicalMessageCarry
import build.raft.app.canonical.message.CanonicalMessageRow
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RealtimeEventCanonicalCarryTransientTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun message(): Message = Message(
        id = MessageId("m-1"),
        threadId = ThreadId("c-1"),
        channelId = ChannelId("c-1"),
        seq = 7L,
        author = MemberProfile(MemberId("u-1"), "Alice", "alice", MemberKind.Human),
        timestampLabel = "now",
        body = "hello",
    )

    private fun carry(): CanonicalMessageCarry = CanonicalMessageCarry(
        row = CanonicalMessageRow(
            id = "m-1",
            channelId = "c-1",
            seq = 7L,
            senderType = "human",
            senderId = "u-1",
            content = "hello",
            messageType = "chat",
            createdAt = "2026-07-12T00:00:00Z",
            senderName = "Alice",
        ),
        presentAggregates = emptySet(),
    )

    @Test
    fun messageNew_roundTrip_dropsTheCarry() {
        val event = MessageNew(message = message(), decodedCanonical = carry())
        val restored = json.decodeFromString(
            MessageNew.serializer(),
            json.encodeToString(MessageNew.serializer(), event),
        )
        assertNull(restored.decodedCanonical, "the @Transient carry must not survive serialization")
        assertEquals(event.message, restored.message, "the authoritative message must round-trip unchanged")
    }

    @Test
    fun messageUpdated_roundTrip_dropsTheCarry() {
        val event = MessageUpdated(message = message(), decodedCanonical = carry())
        val restored = json.decodeFromString(
            MessageUpdated.serializer(),
            json.encodeToString(MessageUpdated.serializer(), event),
        )
        assertNull(restored.decodedCanonical, "the @Transient carry must not survive serialization")
        assertEquals(event.message, restored.message, "the authoritative message must round-trip unchanged")
    }
}
