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
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
    fun emptyVersionZeroHydrateDoesNotSeizeTheProcessCanary() = runBlocking {
        val fixture = fixture()
        assertIs<ReadStateV1ProductionDisposition.Legacy>(fixture.canary.applyHydrate(owner, "empty", 0, 0))
        assertIs<ReadStateV1ProductionDisposition.Selected>(fixture.canary.applyHydrate(owner, "active", 1, 2))
        assertEquals(listOf("active"), fixture.projections.map { it.channelId })
    }

    @Test
    fun incompleteHydrateStaysSelectedAndDark() = runBlocking {
        val fixture = fixture()
        fixture.canary.applyHydrate(owner, "channel-1", 1, 3)
        val missingVersion = fixture.canary.applyHydrate(owner, "channel-1", null, 4)
        val missingWatermark = fixture.canary.applyHydrate(owner, "channel-1", 1, null)

        assertEquals(
            ReadStateV1ProductionDisposition.Selected(
                ReadStateV1GenerationResult.RejectedCandidate,
                ReadStateV1Fact("channel-1", 1, 3),
            ),
            missingVersion,
        )
        assertEquals(
            ReadStateV1ProductionDisposition.Selected(
                ReadStateV1GenerationResult.RejectedCandidate,
                ReadStateV1Fact("channel-1", 1, 3),
            ),
            missingWatermark,
        )
        assertEquals(ReadStateV1Fact("channel-1", 1, 3), fixture.store.facts.values.single())
        assertEquals(listOf(ReadStateV1Fact("channel-1", 1, 3)), fixture.projections)
    }

    @Test
    fun hydrateThenAckAndOwnerRealtimeCommitBeforeProjectionWithLegalRewind() = runBlocking {
        val events = mutableListOf<String>()
        val fixture = fixture(events)

        fixture.canary.applyHydrate(owner, "channel-1", 1, 10)
        fixture.canary.applyMutationAck(owner, "channel-1", 2, 3)
        fixture.canary.applyOwnerRealtime(owner, "channel-1", 3, 9)

        assertEquals(listOf("commit:1:10", "project:1:10", "commit:2:3", "project:2:3", "commit:3:9", "project:3:9"), events)
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
    fun preBaselineIncrementalsCoalesceAndNeverProject() = runBlocking {
        val fixture = fixture()
        val first = fixture.canary.applyMutationAck(owner, "channel-1", 1, 4)
        val second = fixture.canary.applyOwnerRealtime(owner, "channel-1", 2, 5)

        assertEquals(ReadStateV1ProductionDisposition.Selected(ReadStateV1GenerationResult.BaselineRequired(true)), first)
        assertEquals(ReadStateV1ProductionDisposition.Selected(ReadStateV1GenerationResult.BaselineRequired(false)), second)
        assertEquals(listOf("channel-1"), fixture.rebaselines)
        assertTrue(fixture.projections.isEmpty())
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
    fun revokeHoldsUntilSnapshotBarrierThenAllowsOneNewExactScope() = runBlocking {
        val fixture = fixture()
        fixture.canary.applyHydrate(owner, "channel-1", 1, 4)
        fixture.canary.revoke(owner)

        assertEquals(listOf("channel-1"), fixture.rebaselines)
        assertIs<ReadStateV1ProductionDisposition.Selected>(
            fixture.canary.applyOwnerRealtime(owner, "channel-1", 2, 5),
        )
        val prepared = fixture.canary.prepareHydrate(owner, "channel-1", 2, 5)
        assertTrue(fixture.canary.isSelected(owner, "channel-1"))
        val preparedFact = assertIs<ReadStateV1ProductionDisposition.Selected>(prepared).projectedFact
        assertTrue(fixture.canary.completePreparedHydrateProjection(owner, requireNotNull(preparedFact)))
        assertIs<ReadStateV1ProductionDisposition.Selected>(fixture.canary.applyHydrate(owner, "channel-2", 1, 8))
        assertEquals(listOf("channel-1", "channel-1", "channel-2"), fixture.projections.map { it.channelId })
    }

    @Test
    fun equalHydrateAfterRegrantReestablishesBaselineAndProjectsDurablePair() = runBlocking {
        val fixture = fixture()
        fixture.canary.applyHydrate(owner, "channel-1", 1, 4)
        fixture.canary.revoke(owner)

        assertEquals(
            ReadStateV1ProductionDisposition.Selected(
                ReadStateV1GenerationResult.Current,
                ReadStateV1Fact("channel-1", 1, 4),
            ),
            fixture.canary.applyHydrate(owner, "channel-1", 1, 4),
        )
        assertEquals(2, fixture.projections.size)
        assertEquals(ReadStateV1Fact("channel-1", 1, 4), fixture.projections.last())
    }

    @Test
    fun flagOffHoldsSelectedScopeUntilSnapshotBarrierCommitsAndProjects() = runBlocking {
        var enabled = true
        val store = TestReadStateV1Store()
        val projections = mutableListOf<ReadStateV1Fact>()
        val rebaselines = mutableListOf<String>()
        val canary =
            ReadStateV1ProductionCanary(
                isEnabledForServer = { enabled },
                store = store,
                onProjection = { _, fact -> projections += fact },
                onRebaseline = { _, channelId -> rebaselines += channelId },
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
        assertEquals(
            ReadStateV1ProductionDisposition.Selected(
                ReadStateV1GenerationResult.BaselineRequired(false),
                ReadStateV1Fact("channel-1", 1, 10),
            ),
            canary.applyMutationAck(owner, "channel-1", 3, 30),
        )
        assertEquals(ReadStateV1Fact("channel-1", 1, 10), store.facts.values.single())
        assertEquals(listOf("channel-1"), rebaselines)

        val barrier = canary.prepareHydrate(owner, "channel-1", 4, 40)
        assertEquals(
            ReadStateV1ProductionDisposition.Selected(
                ReadStateV1GenerationResult.Committed,
                ReadStateV1Fact("channel-1", 4, 40),
                requiresProjectionConfirmation = true,
            ),
            barrier,
        )
        assertTrue(canary.isSelected(owner, "channel-1"))
        assertTrue(canary.completePreparedHydrateProjection(owner, ReadStateV1Fact("channel-1", 4, 40)))
        assertEquals(ReadStateV1Fact("channel-1", 4, 40), projections.last())
        assertIs<ReadStateV1ProductionDisposition.Legacy>(
            canary.applyOwnerRealtime(owner, "channel-1", 5, 50),
        )
        Unit
    }

    @Test
    fun flagOffSnapshotCommitFailureRemainsSelectedAndDark() = runBlocking {
        var enabled = true
        val store = TestReadStateV1Store()
        val canary =
            ReadStateV1ProductionCanary(
                isEnabledForServer = { enabled },
                store = store,
                onProjection = { _, _ -> },
                onRebaseline = { _, _ -> },
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
                onProjection = { _, fact ->
                    events += "project:${fact.readStateVersion}:${fact.maxReadSeq}"
                    projections += fact
                },
                onRebaseline = { _, channelId -> rebaselines += channelId },
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
) : ReadStateV1Store {
    val facts = mutableMapOf<ReadStateV1Scope, ReadStateV1Fact>()

    override suspend fun commit(
        scope: ReadStateV1Scope,
        fact: ReadStateV1Fact,
        mode: ReadStateV1CommitMode,
        guard: ReadStateV1CommitGuard,
    ): ReadStateV1StoreResult {
        if (failCommit) error("commit failed")
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
