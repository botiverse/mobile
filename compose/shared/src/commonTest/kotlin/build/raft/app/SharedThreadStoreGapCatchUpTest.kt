package build.raft.app

import build.raft.app.SharedThreadStoreTest.Companion.basePayload
import build.raft.app.SharedThreadStoreTest.Companion.message
import build.raft.app.SharedThreadStoreTest.Companion.messagesResponse
import build.raft.app.SharedThreadStoreTest.Companion.pageRangeResponse
import build.raft.app.SharedThreadStoreTest.Companion.pageResponse
import build.raft.app.SharedThreadStoreTest.Companion.sampleSession
import build.raft.app.SharedThreadStoreTest.Companion.threadSnapshot
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.SharedThreadStoreTestFakes.FakeConversationRepository
import build.raft.app.SharedThreadStoreTestFakes.RecordingDurablePageExtender
import build.raft.app.data.DataResult
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.RawMessagePage
import build.raft.app.data.RawMessagePageWitness
import build.raft.app.data.ScreenError
import build.raft.app.network.sync.CanonicalMessageV2EnterBaseline
import build.raft.app.thread.RaftThreadScreenPayload
import build.raft.app.thread.SharedThreadGapCatchUpPageLimit
import build.raft.app.thread.SharedThreadIntent
import build.raft.app.thread.SharedThreadPaging
import build.raft.app.thread.SharedThreadStore
import build.raft.app.thread.ThreadLatestJumpOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

class SharedThreadStoreGapCatchUpTest {
    private class GapCatchUpOutcome(
        val payload: RaftThreadScreenPayload,
        val logs: List<String>,
        val requestLimits: List<Int>,
    )
    /** Screen shows m10 (seq 10); [pages] answer the catch-up steps in order (narrow, then wide). */
    private suspend fun gapCatchUpOutcome(
        vararg pages: Pair<Int, String>,
        followsLatest: Boolean = true,
    ): GapCatchUpOutcome = coroutineScope {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + scopeJob)
        val logs = mutableListOf<String>()
        val api = FakeThreadApi(bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false))
        pages.forEach { (code, body) ->
            api.catchUpResponseCodes.addLast(code)
            api.catchUpBodyTexts.addLast(body)
        }
        val dataSource =
            testSharedThreadDataSource(
                storeScope = scope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope, diagnosticsLogger = logs::add)
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = false)))
        store.setViewportFollowsLatest(followsLatest)
        store.accept(SharedThreadIntent.RefreshLatestAfterVisibleMaxSeq)
        assertEquals(0, api.messageSyncCalls, "gap recovery never reads the /messages/sync delta")
        val payload = store.state.value.payload
        scopeJob.cancelAndJoin()
        GapCatchUpOutcome(payload, logs, api.catchUpLimits.toList())
    }
    @Test
    fun gapCatchUpTwinAnchorOnPageMergesAndAnchorAbsentReplacesUnderIdenticalSeqArithmetic() = runTest {
        // Twin pair: the screen shows m10 (seq 10); both pages have exactly the seqs 10 and 12 and the
        // same size. Only the id at seq 10 differs — page A carries the anchor m10, page B does not.
        // Any decision made from counts or seq comparisons answers both the same way; only the anchor
        // fact separates "attach at the newest edge" from "show the newest page".
        val pageA = pageResponse(Triple("m10", 10L, "current body"), Triple("m12", 12L, "gap body"))
        val merged = gapCatchUpOutcome(200 to pageA)
        assertEquals(listOf("m10", "m12"), merged.payload.messages.map { it.id })
        assertEquals(false, merged.payload.canLoadMoreAfter)
        assertEquals("idle", merged.payload.newerMessagesStatus)
        assertEquals(
            listOf(SharedThreadPaging.DefaultPageSize),
            merged.requestLimits,
            "the anchor is on the cheap page: exactly one request, of the thread page size",
        )
        assertTrue(
            merged.logs.any { "thread_gap_catchup" in it && "decision=merge anchorOnPage=true steps=1" in it },
            merged.logs.toString(),
        )

        val pageB = pageResponse(Triple("x10", 10L, "other body"), Triple("m12", 12L, "gap body"))
        val replaced = gapCatchUpOutcome(200 to pageB, 200 to pageB)
        assertEquals(
            listOf("x10", "m12"),
            replaced.payload.messages.map { it.id },
            "the anchor is on neither page: the timeline is replaced by the newest page, m10 is gone",
        )
        assertEquals(listOf(SharedThreadPaging.DefaultPageSize, SharedThreadGapCatchUpPageLimit), replaced.requestLimits)
        assertEquals(false, replaced.payload.canLoadMoreAfter)
        assertEquals("content", replaced.payload.status)
        assertEquals("idle", replaced.payload.newerMessagesStatus)
        assertTrue(replaced.logs.any { "decision=replace anchorOnPage=false steps=2" in it }, replaced.logs.toString())
    }
    @Test
    fun aFailedSecondStepChangesNothing() = runTest {
        // The cheap page misses the anchor and the wide request fails: the cheap page is never
        // committed on its own, the timeline is exactly what it was.
        val outcome = gapCatchUpOutcome(200 to pageRangeResponse(500L..502L), 500 to "{}")
        assertEquals(listOf(SharedThreadPaging.DefaultPageSize, SharedThreadGapCatchUpPageLimit), outcome.requestLimits)
        assertEquals(listOf("m10"), outcome.payload.messages.map { it.id })
        assertEquals(listOf("current body"), outcome.payload.messages.map { it.body })
        assertEquals(false, outcome.payload.canLoadMoreAfter)
        assertEquals("error", outcome.payload.newerMessagesStatus)
        assertTrue(outcome.logs.none { "thread_gap_catchup " in it && "decision=" in it }, outcome.logs.toString())
    }
    @Test
    fun largeGapWhileScrolledUpKeepsTheViewAndJumpToLatestShowsTheNewestPage() = runTest {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + scopeJob)
        val logs = mutableListOf<String>()
        val api = FakeThreadApi(bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false))
        api.catchUpBodyTexts.addLast(pageRangeResponse(500L..502L))
        api.catchUpBodyTexts.addLast(pageRangeResponse(500L..502L))
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope, diagnosticsLogger = logs::add)
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = false)))
        store.setViewportFollowsLatest(false)

        store.accept(SharedThreadIntent.RefreshLatestAfterVisibleMaxSeq)
        val kept = store.state.value.payload
        assertEquals(listOf("m10"), kept.messages.map { it.id }, "the reader's timeline is not yanked")
        assertTrue(kept.canLoadMoreAfter, "detached from the tail: the ↓ button / newer paging take them forward")
        assertEquals("idle", kept.newerMessagesStatus)
        assertTrue(logs.any { "decision=detach anchorOnPage=false steps=2" in it }, logs.toString())
        assertEquals(2, api.catchUpCalls)

        // Twin of the scrolled-up user: the ↓ tap (jumpToLatest) shows the newest page like channel open.
        api.bodyText = pageRangeResponse(500L..502L)
        assertEquals(ThreadLatestJumpOutcome.Landed, store.jumpToLatest())
        assertEquals(listOf("m500", "m501", "m502"), store.state.value.payload.messages.map { it.id })
        assertEquals(false, store.state.value.payload.canLoadMoreAfter)
        scopeJob.cancelAndJoin()
    }
    @Test
    fun aCatchUpRegistersItsRealtimeBaselineBeforeItsFetchAndHandsItToTheDurableWrite() = runTest {
        suspend fun run(fail: Boolean): Pair<List<String>, Int> {
            val scopeJob = SupervisorJob()
            val scope = CoroutineScope(coroutineContext + scopeJob)
            val repository = FakeConversationRepository()
            val anchor = message(id = "m1", seq = 10, body = "repository body")
            val raw =
                RawMessagePage(
                    messages = Json.parseToJsonElement("""[{"id":"m1"}]""") as JsonArray,
                    requestedLimit = SharedThreadPaging.DefaultPageSize
                )
            val extender = RecordingDurablePageExtender()
            extender.baselineToRegister = { partition, channelId ->
                CanonicalMessageV2EnterBaseline(
                    partition = partition,
                    scopeId = channelId,
                    epochAtRequest = 2L,
                    roomLeaveCountAtRequest = 0L,
                    nonMemberAtRequest = false,
                    roomsJoinedAtRequest = true,
                    source = "catch_up",
                )
            }
            val dataSource =
                testSharedThreadDataSource(
                    storeScope = backgroundScope,
                    networkApi = FakeThreadApi(),
                    sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                    cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                    conversationRepository = repository,
                    durablePageExtender = extender
                )
            val store = SharedThreadStore(dataSource = dataSource, scope = scope)
            store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = false)))
            repository.latestResult =
                if (fail) {
                    DataResult.Error(ScreenError(code = "thread_gap_catchup_network", retryable = true))
                } else {
                    // An all-known page: offered anyway, because only its commit restores continuity.
                    DataResult.Content(threadSnapshot(messages = listOf(anchor)).copy(rawMessagePage = RawMessagePageWitness(raw)))
                }
            extender.onBegin = { extender.baselineEvents += "fetches_before_begin=${repository.latestRequests.size}" }
            store.accept(SharedThreadIntent.RefreshLatestAfterVisibleMaxSeq)
            withTimeout(5_000) { while (repository.latestRequests.isEmpty()) delay(10) }
            delay(20)
            scopeJob.cancelAndJoin()
            return extender.baselineEvents.toList() to extender.latestCalls.size
        }
        val (committed, writes) = run(fail = false)
        assertEquals(listOf("fetches_before_begin=0", "begin", "apply:true", "abandon"), committed, "registered before the fetch")
        assertEquals(1, writes)
        // Twin: the same catch-up whose fetch fails abandons the baseline and writes nothing.
        val (failed, failedWrites) = run(fail = true)
        assertEquals(listOf("fetches_before_begin=0", "begin", "abandon"), failed)
        assertEquals(0, failedWrites)
    }
}
