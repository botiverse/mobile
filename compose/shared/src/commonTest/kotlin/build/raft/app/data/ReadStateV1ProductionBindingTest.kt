package build.raft.app.data

import build.raft.app.network.sync.ReadStateV1CommitGuard
import build.raft.app.network.sync.ReadStateV1CommitMode
import build.raft.app.network.sync.ReadStateV1Fact
import build.raft.app.network.sync.ReadStateV1GenerationResult
import build.raft.app.network.sync.ReadStateV1ProductionCanary
import build.raft.app.network.sync.ReadStateV1ProductionDisposition
import build.raft.app.network.sync.ReadStateV1ProductionOwner
import build.raft.app.network.sync.ReadStateV1Scope
import build.raft.app.network.sync.ReadStateV1Store
import build.raft.app.network.sync.ReadStateV1StoreResult
import build.raft.app.network.sync.reduceReadStateV1
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ReadStateV1ProductionBindingTest {
    private val owner = ReadStateV1ProductionOwner("https://prod.example", "user-1", "server-1")

    @Test
    fun emptyOrIncompleteHydrateCannotSeizeOrAdvanceTheProcessCanary() = runBlocking {
        val fixture = fixture()
        assertIs<ReadStateV1ProductionDisposition.Legacy>(fixture.canary.applyHydrate(owner, "empty", 0, 0))
        assertIs<ReadStateV1ProductionDisposition.Selected>(fixture.canary.applyHydrate(owner, "active", 1, 2))
        assertEquals(
            ReadStateV1ProductionDisposition.Selected(
                ReadStateV1GenerationResult.RejectedCandidate,
                ReadStateV1Fact("active", 1, 2),
            ),
            fixture.canary.applyHydrate(owner, "active", null, 4),
        )
        assertEquals(ReadStateV1Fact("active", 1, 2), fixture.store.facts.values.single())
        assertEquals(listOf("active"), fixture.projections.map { it.channelId })
    }

    @Test
    fun hydrateThenAckAndOwnerRealtimeCommitBeforeProjectionWithLegalRewind() = runBlocking {
        val events = mutableListOf<String>()
        val fixture = fixture(events)

        fixture.canary.applyHydrate(owner, "channel-1", 1, 10)
        fixture.canary.applyMutationAck(owner, "channel-1", 2, 3)
        fixture.canary.applyOwnerRealtime(owner, "channel-1", 3, 9)

        assertEquals(
            listOf("commit:1:10", "project:1:10", "commit:2:3", "project:2:3", "commit:3:9", "project:3:9"),
            events,
        )
        assertEquals(ReadStateV1Fact("channel-1", 3, 9), fixture.store.facts.values.single())
    }

    @Test
    fun lowerAndEqualAreDarkWhileEqualConflictSingleFlightsRebaseline() = runBlocking {
        val fixture = fixture()
        fixture.canary.applyHydrate(owner, "channel-1", 3, 9)

        assertEquals(
            ReadStateV1ProductionDisposition.Selected(
                ReadStateV1GenerationResult.Stale,
                ReadStateV1Fact("channel-1", 3, 9),
            ),
            fixture.canary.applyOwnerRealtime(owner, "channel-1", 2, 99),
        )
        assertEquals(
            ReadStateV1ProductionDisposition.Selected(
                ReadStateV1GenerationResult.Current,
                ReadStateV1Fact("channel-1", 3, 9),
            ),
            fixture.canary.applyOwnerRealtime(owner, "channel-1", 3, 9),
        )
        assertEquals(
            ReadStateV1ProductionDisposition.Selected(
                ReadStateV1GenerationResult.Conflict,
                ReadStateV1Fact("channel-1", 3, 9),
            ),
            fixture.canary.applyOwnerRealtime(owner, "channel-1", 3, 8),
        )
        fixture.canary.applyOwnerRealtime(owner, "channel-1", 4, 10)
        assertEquals(listOf("channel-1"), fixture.rebaselines)
        assertEquals(ReadStateV1Fact("channel-1", 3, 9), fixture.store.facts.values.single())
    }

    @Test
    fun selectedCommitFailureDoesNotFallOpen() = runBlocking {
        val fixture = fixture(failCommit = true)
        assertIs<ReadStateV1ProductionDisposition.SelectedFailure>(
            fixture.canary.applyHydrate(owner, "channel-1", 1, 4),
        )
        assertTrue(fixture.projections.isEmpty())
    }

    @Test
    fun revokeFirstRejectsOldPreparedPublicationAndNewSameScopeWitnessPublishesNormally() = runBlocking {
        val fixture = fixture()
        val oldFact = ReadStateV1Fact("channel-1", 1, 4)
        val prepared = fixture.canary.prepareHydrate(owner, oldFact.channelId, oldFact.readStateVersion, oldFact.maxReadSeq)
        assertTrue(assertIs<ReadStateV1ProductionDisposition.Selected>(prepared).requiresProjectionConfirmation)

        fixture.canary.revoke(owner)
        var oldWriteCount = 0
        assertFalse(fixture.canary.completePreparedHydrateProjection(owner, oldFact) { oldWriteCount += 1 })
        assertEquals(0, oldWriteCount)

        val newFact = ReadStateV1Fact("channel-1", 2, 5)
        val replacement = fixture.canary.prepareHydrate(owner, newFact.channelId, newFact.readStateVersion, newFact.maxReadSeq)
        assertTrue(assertIs<ReadStateV1ProductionDisposition.Selected>(replacement).requiresProjectionConfirmation)
        var newWriteCount = 0
        assertTrue(fixture.canary.completePreparedHydrateProjection(owner, newFact) { newWriteCount += 1 })
        assertEquals(1, newWriteCount)
        assertEquals(listOf(newFact), fixture.projections)
    }

    @Test
    fun flagOffHoldsSelectedScopeUntilSnapshotBarrierCommitsAndPublishes() = runBlocking {
        var enabled = true
        val store = TestReadStateV1Store()
        val projections = mutableListOf<ReadStateV1Fact>()
        val rebaselines = mutableListOf<String>()
        val canary =
            ReadStateV1ProductionCanary(
                isEnabledForServer = { enabled },
                store = store,
                onProjection = { _, _, fact -> projections += fact },
                onRebaseline = { _, _, channelId -> rebaselines += channelId },
            )
        canary.applyHydrate(owner, "channel-1", 1, 10)
        enabled = false

        assertEquals(
            ReadStateV1ProductionDisposition.Selected(
                ReadStateV1GenerationResult.BaselineRequired(true),
                ReadStateV1Fact("channel-1", 1, 10),
            ),
            canary.applyOwnerRealtime(owner, "channel-1", 2, 20),
        )
        assertEquals(listOf("channel-1"), rebaselines)

        val barrier = canary.prepareHydrate(owner, "channel-1", 4, 40)
        val selectedBarrier = assertIs<ReadStateV1ProductionDisposition.Selected>(barrier)
        assertEquals(ReadStateV1GenerationResult.Committed, selectedBarrier.result)
        assertEquals(ReadStateV1Fact("channel-1", 4, 40), selectedBarrier.projectedFact)
        assertTrue(selectedBarrier.requiresProjectionConfirmation)
        assertTrue(selectedBarrier.preparedPublication != null)
        assertTrue(canary.completePreparedHydrateProjection(owner, ReadStateV1Fact("channel-1", 4, 40)))
        assertIs<ReadStateV1ProductionDisposition.Legacy>(
            canary.applyOwnerRealtime(owner, "channel-1", 5, 50),
        )
        assertEquals(ReadStateV1Fact("channel-1", 4, 40), projections.last())
    }

    @Test
    fun hydrateConfirmationAndConcurrentAckTerminateWithoutLockInversion() = runBlocking {
        val projectionEntered = CompletableDeferred<Unit>()
        val releaseProjection = CompletableDeferred<Unit>()
        val canary =
            ReadStateV1ProductionCanary(
                isEnabledForServer = { true },
                store = TestReadStateV1Store(),
                onProjection = { _, _, _ ->
                    projectionEntered.complete(Unit)
                    runBlocking { releaseProjection.await() }
                },
                onRebaseline = { _, _, _ -> },
            )
        val fact = ReadStateV1Fact("channel-1", 1, 10)
        val prepared = canary.prepareHydrate(owner, fact.channelId, fact.readStateVersion, fact.maxReadSeq)
        assertTrue(assertIs<ReadStateV1ProductionDisposition.Selected>(prepared).requiresProjectionConfirmation)

        val confirmation = async(Dispatchers.Default) { canary.completePreparedHydrateProjection(owner, fact) }
        withTimeout(2_000) { projectionEntered.await() }
        val concurrentAck = async(Dispatchers.Default) { canary.applyMutationAck(owner, fact.channelId, 2, 20) }
        releaseProjection.complete(Unit)

        withTimeout(2_000) {
            assertTrue(confirmation.await())
            assertIs<ReadStateV1ProductionDisposition.Selected>(concurrentAck.await())
        }
        Unit
    }

    private fun fixture(
        events: MutableList<String> = mutableListOf(),
        enabled: Boolean = true,
        failCommit: Boolean = false,
    ): Fixture {
        val store = TestReadStateV1Store(events, failCommit)
        val projections = mutableListOf<ReadStateV1Fact>()
        val rebaselines = mutableListOf<String>()
        val canary =
            ReadStateV1ProductionCanary(
                isEnabledForServer = { enabled },
                store = store,
                onProjection = { _, _, fact ->
                    events += "project:${fact.readStateVersion}:${fact.maxReadSeq}"
                    projections += fact
                },
                onRebaseline = { _, _, channelId -> rebaselines += channelId },
            )
        return Fixture(canary, store, projections, rebaselines)
    }

    private data class Fixture(
        val canary: ReadStateV1ProductionCanary,
        val store: TestReadStateV1Store,
        val projections: List<ReadStateV1Fact>,
        val rebaselines: List<String>,
    )
}

internal class TestReadStateV1Store(
    private val events: MutableList<String> = mutableListOf(),
    var failCommit: Boolean = false,
    private val beforeCommit: suspend (ReadStateV1Fact) -> Unit = {},
) : ReadStateV1Store {
    val facts = mutableMapOf<ReadStateV1Scope, ReadStateV1Fact>()

    override suspend fun commit(
        scope: ReadStateV1Scope,
        fact: ReadStateV1Fact,
        mode: ReadStateV1CommitMode,
        guard: ReadStateV1CommitGuard,
    ): ReadStateV1StoreResult {
        if (failCommit) error("commit failed")
        beforeCommit(fact)
        if (!guard.isCurrent()) return ReadStateV1StoreResult.RejectedCurrentGrant
        val transition = facts[scope]?.let { reduceReadStateV1(it, fact) }
        val result =
            when {
                transition == null -> ReadStateV1StoreResult.Accepted
                transition.result.name == "Accepted" -> ReadStateV1StoreResult.Accepted
                transition.result.name == "Current" -> ReadStateV1StoreResult.Current
                transition.result.name == "Stale" -> ReadStateV1StoreResult.Stale
                else -> ReadStateV1StoreResult.Conflict
            }
        if (result == ReadStateV1StoreResult.Accepted) {
            facts[scope] = fact
            events += "commit:${fact.readStateVersion}:${fact.maxReadSeq}"
        }
        return result
    }

    override suspend fun restore(scope: ReadStateV1Scope): ReadStateV1Fact? = facts[scope]
}
