package build.raft.app.data

import build.raft.app.model.ReadReceiptPeerKind
import build.raft.app.model.ScopeReadUpdated
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class SharedPeerReadReceiptStoreTest {
    private val owner = PeerReadReceiptOwner("https://api.raft.test", "user-1", "srv-1")
    private val otherServer = owner.copy(serverId = "srv-2")
    private val otherAccount = owner.copy(accountId = "user-2")

    private fun peers(vararg watermarks: Pair<String, Long>) =
        PeerReadReceiptScope.Peers(watermarks.map { (id, seq) -> PeerReadState(ReadReceiptPeerKind.Agent, id, seq) })

    private fun advance(peerId: String, seq: Long, scopeId: String = "ch-1") =
        ScopeReadUpdated(scopeId, ReadReceiptPeerKind.Agent, peerId, seq)

    private fun reduce(vararg intents: PeerReadReceiptIntent): PeerReadReceiptState =
        intents.fold(PeerReadReceiptState()) { state, intent -> reducePeerReadReceipts(state, intent) }

    private fun hydrated(scope: PeerReadReceiptScope = peers("agent-1" to 5, "agent-2" to 2)) =
        reduce(PeerReadReceiptIntent.BindOwner(owner), PeerReadReceiptIntent.HydrateLoaded(owner, "ch-1", scope))

    @Test fun hydrateStoresTheScopeAndAnEmptyAnswerRemovesIt() {
        val state = hydrated()
        assertEquals(peers("agent-1" to 5, "agent-2" to 2), state.scopes["ch-1"])
        val off = reducePeerReadReceipts(state, PeerReadReceiptIntent.HydrateLoaded(owner, "ch-1", null))
        assertNull(off.scopes["ch-1"])
    }

    @Test fun peerFramesAdvanceAndOutOfOrderOrReplayedFramesAreIgnored() {
        val advanced = reducePeerReadReceipts(hydrated(), PeerReadReceiptIntent.PeerAdvanced(owner, advance("agent-2", 7)))
        assertEquals(peers("agent-1" to 5, "agent-2" to 7), advanced.scopes["ch-1"])
        listOf(advance("agent-2", 6), advance("agent-2", 7)).forEach { stale ->
            assertSame(advanced, reducePeerReadReceipts(advanced, PeerReadReceiptIntent.PeerAdvanced(owner, stale)))
        }
        // Unknown peer, un-hydrated scope, and another owner's frame never create identities.
        assertSame(advanced, reducePeerReadReceipts(advanced, PeerReadReceiptIntent.PeerAdvanced(owner, advance("agent-9", 99))))
        assertSame(advanced, reducePeerReadReceipts(advanced, PeerReadReceiptIntent.PeerAdvanced(owner, advance("agent-1", 99, "ch-2"))))
        assertSame(advanced, reducePeerReadReceipts(advanced, PeerReadReceiptIntent.PeerAdvanced(otherServer, advance("agent-1", 99))))
    }

    @Test fun aLaterHydrateKeepsWatermarksRealtimeAlreadyAdvanced() {
        val advanced = reducePeerReadReceipts(hydrated(), PeerReadReceiptIntent.PeerAdvanced(owner, advance("agent-1", 9)))
        val rehydrated =
            reducePeerReadReceipts(advanced, PeerReadReceiptIntent.HydrateLoaded(owner, "ch-1", peers("agent-1" to 6, "agent-2" to 3)))
        assertEquals(peers("agent-1" to 9, "agent-2" to 3), rehydrated.scopes["ch-1"])
    }

    @Test fun summaryScopesIgnorePeerFrames() {
        val summary = PeerReadReceiptScope.Summary(PeerReadSummary(52, listOf(PeerReadCountAtSeq(0, 52), PeerReadCountAtSeq(4, 1))))
        val state = hydrated(summary)
        assertSame(state, reducePeerReadReceipts(state, PeerReadReceiptIntent.PeerAdvanced(owner, advance("agent-1", 9))))
        assertSame(state, reducePeerReadReceipts(state, PeerReadReceiptIntent.PeerAdvanced(owner, ScopeReadUpdated("ch-1", summaryChanged = true))))
    }

    @Test fun switchingServerOrAccountClearsEveryScopeAndDropsStaleAnswers() {
        listOf(otherServer, otherAccount, null).forEach { next ->
            val switched = reducePeerReadReceipts(hydrated(), PeerReadReceiptIntent.BindOwner(next))
            assertEquals(PeerReadReceiptState(owner = next), switched)
            // The previous owner's in-flight GET lands after the switch: ignored.
            assertSame(switched, reducePeerReadReceipts(switched, PeerReadReceiptIntent.HydrateLoaded(owner, "ch-1", peers("agent-1" to 1))))
        }
        val same = hydrated()
        assertSame(same, reducePeerReadReceipts(same, PeerReadReceiptIntent.BindOwner(owner)))
        assertEquals(emptyMap(), reducePeerReadReceipts(hydrated(), PeerReadReceiptIntent.ClearAll).scopes)
    }

    @Test fun storeHydratesSingleFlightAndRefetchesOnlySummaryScopesOnSummaryChanged() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val answers = ArrayDeque<CompletableDeferred<PeerReadReceiptHydrateResult>>()
        val requested = mutableListOf<String>()
        val store = SharedPeerReadReceiptStore(scope, fetchHydrate = { _, scopeId ->
            requested += scopeId
            CompletableDeferred<PeerReadReceiptHydrateResult>().also { answers.addLast(it) }.await()
        })
        store.dispatch(PeerReadReceiptIntent.BindOwner(owner))
        store.hydrate(owner, "ch-1")
        store.hydrate(owner, "ch-1")
        scope.advanceUntilIdle()
        assertEquals(listOf("ch-1"), requested)
        val summary = PeerReadReceiptScope.Summary(PeerReadSummary(60, listOf(PeerReadCountAtSeq(11, 1))))
        answers.removeFirst().complete(PeerReadReceiptHydrateResult.Loaded(summary))
        scope.advanceUntilIdle()
        assertEquals(summary, store.state.value.scopes["ch-1"])

        // An anonymous summaryChanged re-fetches canonical detail without learning identities.
        store.consumeScopeUpdated(owner, ScopeReadUpdated("ch-1", summaryChanged = true))
        scope.advanceUntilIdle()
        assertEquals(listOf("ch-1", "ch-1"), requested)
        val refreshed = PeerReadReceiptScope.Summary(PeerReadSummary(60, listOf(PeerReadCountAtSeq(12, 1))))
        answers.removeFirst().complete(PeerReadReceiptHydrateResult.Loaded(refreshed))
        scope.advanceUntilIdle()
        assertEquals(refreshed, store.state.value.scopes["ch-1"])

        // A failed re-fetch keeps the last answer; frames for scopes never hydrated do nothing.
        store.consumeScopeUpdated(owner, ScopeReadUpdated("ch-1", summaryChanged = true))
        scope.advanceUntilIdle()
        answers.removeFirst().complete(PeerReadReceiptHydrateResult.Failed("status_500"))
        scope.advanceUntilIdle()
        assertEquals(refreshed, store.state.value.scopes["ch-1"])
        store.consumeScopeUpdated(owner, ScopeReadUpdated("ch-9", summaryChanged = true))
        scope.advanceUntilIdle()
        assertEquals(3, requested.size)
    }
}
