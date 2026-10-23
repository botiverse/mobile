package build.raft.app.network.sync

import build.raft.app.canonical.message.CanonicalMessageCarry
import build.raft.app.canonical.message.CanonicalMessageRow
import build.raft.app.canonical.message.CanonicalReactionWire
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncCoreReactionShadowLaneTest {
    private fun carry(
        reaction: CanonicalReactionWire,
        messageId: String = "m1",
    ): CanonicalMessageCarry =
        CanonicalMessageCarry(
            row = CanonicalMessageRow(
                id = messageId,
                channelId = "c1",
                seq = 1,
                senderType = "human",
                senderId = "u1",
                content = "hello",
                messageType = "chat",
                createdAt = "now",
                senderName = "Alice",
                reactions = listOf(reaction),
            ),
            presentAggregates = emptySet(),
        )

    @Test
    fun observesChannelRoomWithoutApplyingViewerState() {
        val logs = mutableListOf<String>()
        val lane = SyncCoreReactionShadowLane(logs::add)

        val result = assertNotNull(
            lane.observe(
                "s1",
                carry(CanonicalReactionWire("like", 2, listOf("u2", "u1"), listOf("Bob", "Alice"))),
            ),
        )

        assertEquals(listOf(ReactionFact("like", 2, emptyList())), result.sharedFact)
        assertNull(result.viewerOverlay)
        assertEquals(listOf("u1", "u2"), result.readCacheSeed.single().actors.map(ReactionActorRef::id))
        assertEquals(emptyList(), result.violations)
        assertTrue(logs.isEmpty())
    }

    @Test
    fun malformedRosterProducesBoundedDiagnosticButKeepsSharedCountFact() {
        val logs = mutableListOf<String>()
        val lane = SyncCoreReactionShadowLane(logs::add)

        val result = assertNotNull(
            lane.observe(
                "s1",
                carry(CanonicalReactionWire("like", 2, listOf("u1", "u1"), listOf("Alice", "Other"))),
            ),
        )

        assertEquals(listOf(ReactionFact("like", 2, emptyList())), result.sharedFact)
        assertNull(result.viewerOverlay)
        assertEquals(emptyList(), result.readCacheSeed)
        assertEquals(
            listOf(ReactionRosterDiagnosticKind.DuplicateActorId, ReactionRosterDiagnosticKind.ActorCountMismatch),
            result.violations.map(ReactionNormalizationDiagnostic::kind),
        )
        assertEquals(1, logs.size)
        assertTrue(logs.single().contains("result=violations"))
        assertTrue(logs.single().contains("msg=m1"))
        assertTrue(logs.single().contains("violationCount=2"))
    }

    @Test
    fun diagnosticLoggerFailureIsSwallowedAndCannotEscapeToLegacyCaller() {
        val lane = SyncCoreReactionShadowLane { error("logger unavailable") }

        val result = lane.observe(
            "s1",
            carry(CanonicalReactionWire("like", 2, listOf("u1", "u1"), listOf("Alice", "Other"))),
        )

        assertNull(result)
    }

    @Test
    fun wholeCallRejectionIsDiagnosedSeparatelyFromInlineViolations() {
        val logs = mutableListOf<String>()
        val lane = SyncCoreReactionShadowLane(logs::add)

        val result = lane.observe(
            "s1",
            carry(
                CanonicalReactionWire("like", 1, listOf("u1"), listOf("Alice")),
                messageId = "",
            ),
        )

        assertNull(result)
        assertEquals(1, logs.size)
        assertTrue(logs.single().contains("result=rejected"))
        assertTrue(logs.single().contains("reason=InvalidContext"))
    }
}
