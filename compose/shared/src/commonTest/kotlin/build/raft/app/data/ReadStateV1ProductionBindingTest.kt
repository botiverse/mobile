package build.raft.app.data

import build.raft.app.network.sync.ReadStateV1CommitGuard
import build.raft.app.network.sync.ReadStateV1CommitMode
import build.raft.app.network.sync.ReadStateV1Fact
import build.raft.app.network.sync.ReadStateV1GenerationResult
import build.raft.app.network.sync.ReadStateV1GrantWitness
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
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ReadStateV1ProductionBindingTest {
    private val owner = ReadStateV1ProductionOwner("https://prod.example", "user-1", "server-1")

    @Test
    fun flagOffAndNonSelectedScopeRemainLegacy() = runBlocking {
        val disabled = fixture(enabled = false)
        assertIs<ReadStateV1ProductionDisposition.Legacy>(disabled.canary.applyHydrate(owner, "channel-1", 1, 4))
        assertTrue(disabled.store.facts.isEmpty())

        val enabled = fixture()
        assertIs<ReadStateV1ProductionDisposition.Selected>(enabled.canary.applyHydrate(owner, "channel-1", 1, 4))
        assertIs<ReadStateV1ProductionDisposition.Legacy>(enabled.canary.applyHydrate(owner, "channel-2", 1, 8))
        assertEquals(setOf("channel-1"), enabled.projections.map { it.channelId }.toSet())
    }

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
    fun preBaselineIncrementalsCoalesceAndCarryTheOriginalWitnessIntoProjection() = runBlocking {
        val projectionWitnesses = mutableListOf<ReadStateV1GrantWitness>()
        val rebaselineWitnesses = mutableListOf<ReadStateV1GrantWitness>()
        val canary =
            ReadStateV1ProductionCanary(
                isEnabledForServer = { true },
                store = TestReadStateV1Store(),
                onProjection = { _, witness, _ -> projectionWitnesses += witness },
                onRebaseline = { _, witness, _ -> rebaselineWitnesses += witness },
            )

        assertEquals(
            ReadStateV1ProductionDisposition.Selected(ReadStateV1GenerationResult.BaselineRequired(true)),
            canary.applyMutationAck(owner, "channel-1", 1, 4),
        )
        assertEquals(
            ReadStateV1ProductionDisposition.Selected(ReadStateV1GenerationResult.BaselineRequired(false)),
            canary.applyOwnerRealtime(owner, "channel-1", 2, 5),
        )
        canary.applyHydrate(owner, "channel-1", 3, 6)

        assertEquals(1, rebaselineWitnesses.size)
        assertEquals(1, projectionWitnesses.size)
        assertSame(rebaselineWitnesses.single(), projectionWitnesses.single())
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
    fun sameDurableHydrateAfterRevokeProjectsThroughTheReplacementWitnessBeforeRelease() = runBlocking {
        val fixture = fixture()
        val fact = ReadStateV1Fact("channel-1", 1, 4)

        assertEquals(
            ReadStateV1ProductionDisposition.Selected(ReadStateV1GenerationResult.Committed, fact),
            fixture.canary.applyHydrate(owner, fact.channelId, fact.readStateVersion, fact.maxReadSeq),
        )
        fixture.canary.revoke(owner)
        assertEquals(
            ReadStateV1ProductionDisposition.Selected(ReadStateV1GenerationResult.Current, fact),
            fixture.canary.applyHydrate(owner, fact.channelId, fact.readStateVersion, fact.maxReadSeq),
        )

        assertEquals(listOf(fact, fact), fixture.projections)
    }

    @Test
    fun revokeFirstRejectsStaleIncompleteAndFailedPreparedHomePublications() = runBlocking {
        suspend fun assertRejectedAfterRevoke(
            prepare: suspend (Fixture) -> ReadStateV1ProductionDisposition,
        ) {
            val fixture = fixture()
            fixture.canary.applyHydrate(owner, "channel-1", 3, 9)
            val disposition = prepare(fixture)
            val publication =
                when (disposition) {
                    is ReadStateV1ProductionDisposition.Selected -> disposition.preparedPublication
                    is ReadStateV1ProductionDisposition.SelectedFailure -> disposition.preparedPublication
                    ReadStateV1ProductionDisposition.Legacy -> null
                }
            val preparedPublication =
                requireNotNull(publication) {
                    "every selected hydrate adjudication must carry its original witness"
                }

            fixture.canary.revoke(owner)
            var writeCount = 0
            assertFalse(fixture.canary.completePreparedPublication(preparedPublication) { writeCount += 1 })
            assertEquals(0, writeCount)
        }

        assertRejectedAfterRevoke { fixture ->
            fixture.canary.prepareHydrate(owner, "channel-1", 2, 99)
        }
        assertRejectedAfterRevoke { fixture ->
            fixture.canary.prepareHydrate(owner, "channel-1", null, 99)
        }
        assertRejectedAfterRevoke { fixture ->
            fixture.store.failCommit = true
            fixture.canary.prepareHydrate(owner, "channel-1", 4, 10)
        }
    }

    @Test
    fun publicationFirstMakesOwnerRevokeWaitAndNoOldCallbackRunsAfterReturn() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val projections = mutableListOf<ReadStateV1Fact>()
        val canary =
            ReadStateV1ProductionCanary(
                isEnabledForServer = { true },
                store = TestReadStateV1Store(),
                onProjection = { _, _, fact -> projections += fact },
                onRebaseline = { _, _, _ -> },
            )
        val fact = ReadStateV1Fact("channel-1", 1, 10)
        canary.prepareHydrate(owner, fact.channelId, fact.readStateVersion, fact.maxReadSeq)

        val confirmation =
            async(Dispatchers.Default) {
                canary.completePreparedHydrateProjection(owner, fact) {
                    entered.complete(Unit)
                    runBlocking { release.await() }
                }
            }
        withTimeout(2_000) { entered.await() }
        val revoke = async(Dispatchers.Default) { canary.revoke(owner) }
        delay(100)
        assertFalse(revoke.isCompleted, "owner revoke must wait for the exact-witness App write")

        release.complete(Unit)
        assertTrue(withTimeout(2_000) { confirmation.await() })
        withTimeout(2_000) { revoke.await() }
        assertEquals(listOf(fact), projections)
        assertFalse(canary.completePreparedHydrateProjection(owner, fact))
        assertEquals(listOf(fact), projections, "revoke return must be a terminal old-witness callback barrier")
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
    fun flagOffReplacementClosesOldPreparedDestinationBeforeReturning() = runBlocking {
        var enabled = true
        val canary =
            ReadStateV1ProductionCanary(
                isEnabledForServer = { enabled },
                store = TestReadStateV1Store(),
                onProjection = { _, _, _ -> },
                onRebaseline = { _, _, _ -> },
            )
        val oldFact = ReadStateV1Fact("channel-1", 1, 10)
        canary.prepareHydrate(owner, oldFact.channelId, oldFact.readStateVersion, oldFact.maxReadSeq)
        enabled = false

        assertIs<ReadStateV1ProductionDisposition.Selected>(
            canary.applyOwnerRealtime(owner, oldFact.channelId, 2, 20),
        )
        var oldWriteCount = 0
        assertFalse(canary.completePreparedHydrateProjection(owner, oldFact) { oldWriteCount += 1 })
        assertEquals(0, oldWriteCount)
        assertTrue(canary.isSelected(owner, oldFact.channelId), "snapshot barrier must remain selected")
    }

    @Test
    fun flagOffSnapshotCommitFailureRemainsSelectedAndDark() = runBlocking {
        var enabled = true
        val store = TestReadStateV1Store()
        val canary =
            ReadStateV1ProductionCanary(
                isEnabledForServer = { enabled },
                store = store,
                onProjection = { _, _, _ -> },
                onRebaseline = { _, _, _ -> },
            )
        canary.applyHydrate(owner, "channel-1", 1, 10)
        enabled = false
        store.failCommit = true

        assertIs<ReadStateV1ProductionDisposition.SelectedFailure>(
            canary.applyHydrate(owner, "channel-1", 2, 20),
        )
        assertTrue(canary.isSelected(owner, "channel-1"))
        assertIs<ReadStateV1ProductionDisposition.Selected>(
            canary.applyOwnerRealtime(owner, "channel-1", 3, 30),
        )
        assertEquals(ReadStateV1Fact("channel-1", 1, 10), store.facts.values.single())
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
