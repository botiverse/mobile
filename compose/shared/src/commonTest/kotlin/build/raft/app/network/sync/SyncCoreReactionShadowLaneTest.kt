package build.raft.app.network.sync

import build.raft.app.canonical.message.CanonicalMessageCarry
import build.raft.app.canonical.message.CanonicalMessageRow
import build.raft.app.canonical.message.CanonicalReactionWire
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY
import build.raft.app.data.SharedServerFeatureFlags
import build.raft.app.data.RaftFeatureFlagsApi
import build.raft.app.model.ServerId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncCoreReactionShadowLaneTest {
    private class FakeFlagsApi(private val enabled: Boolean) : RaftFeatureFlagsApi {
        override suspend fun evaluate(serverId: ServerId, keys: List<String>): HomeNetworkResponse =
            HomeNetworkResponse(
                code = 200,
                bodyText =
                    """{"evaluations":[{"key":"$SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY","enabled":$enabled}]}""",
            )
    }

    private fun flags(enabled: Boolean): SharedServerFeatureFlags =
        SharedServerFeatureFlags(FakeFlagsApi(enabled)).also { flags ->
            runBlocking { flags.prefetch(ServerId("s1")) }
        }

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
    fun flagOffDoesNoNormalizationWork() {
        val logs = mutableListOf<String>()
        val lane = SyncCoreReactionShadowLane(flags(false), logs::add)

        val result = lane.observe(
            "s1",
            carry(CanonicalReactionWire("like", 1, listOf("u1"), listOf("Alice"))),
        )

        assertNull(result)
        assertTrue(logs.isEmpty())
    }

    @Test
    fun flagOnObservesChannelRoomWithoutApplyingViewerState() {
        val logs = mutableListOf<String>()
        val lane = SyncCoreReactionShadowLane(flags(true), logs::add)

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
        val lane = SyncCoreReactionShadowLane(flags(true), logs::add)

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
        val lane = SyncCoreReactionShadowLane(flags(true)) { error("logger unavailable") }

        val result = lane.observe(
            "s1",
            carry(CanonicalReactionWire("like", 2, listOf("u1", "u1"), listOf("Alice", "Other"))),
        )

        assertNull(result)
    }

    @Test
    fun wholeCallRejectionIsDiagnosedSeparatelyFromInlineViolations() {
        val logs = mutableListOf<String>()
        val lane = SyncCoreReactionShadowLane(flags(true), logs::add)

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
