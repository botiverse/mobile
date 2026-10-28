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
import build.raft.app.model.Message
import build.raft.app.model.ThreadId
import build.raft.app.network.sync.CanonicalMessageV2EnterBaseline
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
    fun anchorOnlyOnTheWidePageIsMergedAfterTwoRequests() = runTest {
        // The cheap page (m12..m14) misses the anchor; the 200 page reaches back to it, so the gap is
        // small after all: merged at the newest edge, no replacement.
        val outcome =
            gapCatchUpOutcome(
                200 to pageRangeResponse(12L..14L),
                200 to pageResponse(
                    Triple("m10", 10L, "current body"),
                    Triple("m11", 11L, "body-11"),
                    Triple("m12", 12L, "body-12"),
                    Triple("m13", 13L, "body-13"),
                    Triple("m14", 14L, "body-14"),
                ),
            )
        assertEquals(listOf(SharedThreadPaging.DefaultPageSize, SharedThreadGapCatchUpPageLimit), outcome.requestLimits)
        assertEquals(listOf("m10", "m11", "m12", "m13", "m14"), outcome.payload.messages.map { it.id })
        assertEquals(false, outcome.payload.canLoadMoreAfter)
        assertTrue(outcome.logs.any { "decision=merge anchorOnPage=true steps=2" in it }, outcome.logs.toString())
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
        assertTrue(store.jumpToLatest())
        assertEquals(listOf("m500", "m501", "m502"), store.state.value.payload.messages.map { it.id })
        assertEquals(false, store.state.value.payload.canLoadMoreAfter)
        scopeJob.cancelAndJoin()
    }
    @Test
    fun failedCatchUpChangesNothingAndOffersNothingToTheDurableWindow() = runTest {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + scopeJob)
        val repository = FakeConversationRepository()
        repository.latestResult = DataResult.Error(ScreenError(code = "thread_gap_catchup_network", retryable = true))
        val extender = RecordingDurablePageExtender()
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
        val before = store.state.value.payload

        store.accept(SharedThreadIntent.RecoverRealtimeGap(serverSeq = 90))
        delay(10)

        val after = store.state.value.payload
        assertEquals(listOf(ThreadId("channel-1")), repository.latestRequests)
        assertEquals(before.messages, after.messages, "the single request failed: the timeline is untouched")
        assertEquals(before.canLoadMoreAfter, after.canLoadMoreAfter)
        assertEquals("error", after.newerMessagesStatus)
        assertTrue(extender.latestCalls.isEmpty(), extender.latestCalls.toString())
        scopeJob.cancelAndJoin()
    }
    @Test
    fun catchUpPageThatAddsMessagesIsOfferedToTheDurableWindowAndAnAllKnownOneIsNot() = runTest {
        suspend fun offered(page: List<Message>): List<Triple<String, List<String?>, Pair<Int, Boolean>>> {
            val scopeJob = SupervisorJob()
            val scope = CoroutineScope(coroutineContext + scopeJob)
            val repository = FakeConversationRepository()
            val raw =
                RawMessagePage(
                    messages = Json.parseToJsonElement(page.joinToString(prefix = "[", postfix = "]") { """{"id":"${it.id.value}"}""" }) as JsonArray,
                    requestedLimit = SharedThreadPaging.DefaultPageSize
                )
            repository.latestResult = DataResult.Content(threadSnapshot(messages = page).copy(rawMessagePage = RawMessagePageWitness(raw)))
            val extender = RecordingDurablePageExtender()
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
            store.accept(SharedThreadIntent.RefreshLatestAfterVisibleMaxSeq)
            withTimeout(5_000) { while (repository.latestRequests.isEmpty()) delay(10) }
            delay(20)
            assertEquals(listOf(SharedThreadPaging.DefaultPageSize), repository.latestLimits, "anchor on the cheap page: one request")
            scopeJob.cancelAndJoin()
            return extender.latestCalls
        }
        // The default repository snapshot shows m1 (seq 10).
        val anchor = message(id = "m1", seq = 10, body = "repository body")
        assertEquals(
            listOf(Triple("channel-1", listOf<String?>("m1", "m12"), SharedThreadPaging.DefaultPageSize to true)),
            offered(listOf(anchor, message(id = "m12", seq = 12, body = "gap body"))),
        )
        assertEquals(emptyList(), offered(listOf(anchor)), "an all-known page (the heartbeat case) writes nothing")
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
    @Test
    fun aCatchUpReadsTheDurableHeadEpochBeforeItsFetchAndHandsItToTheWrite() = runTest {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + scopeJob)
        val repository = FakeConversationRepository()
        val raw =
            RawMessagePage(
                messages = Json.parseToJsonElement("""[{"id":"m1"}]""") as JsonArray,
                requestedLimit = SharedThreadPaging.DefaultPageSize
            )
        val extender = RecordingDurablePageExtender()
        // The epoch moves with every latest-page request: a read after the fetch would see 1, not 0.
        extender.headEpochProvider = { repository.latestRequests.size.toLong() }
        // With a realtime baseline even an all-known page is written (only its commit restores continuity).
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
            DataResult.Content(
                threadSnapshot(messages = listOf(message(id = "m1", seq = 10, body = "repository body")))
                    .copy(rawMessagePage = RawMessagePageWitness(raw))
            )
        store.accept(SharedThreadIntent.RefreshLatestAfterVisibleMaxSeq)
        withTimeout(5_000) { while (repository.latestRequests.isEmpty()) delay(10) }
        delay(20)
        scopeJob.cancelAndJoin()
        assertEquals(1, repository.latestRequests.size)
        assertEquals(1, extender.latestCalls.size)
        assertEquals(listOf<Long?>(0L), extender.requestHeadEpochs, "the epoch must be read before the request")
    }

    @Test
    fun gapCatchUpDecisionReadsOnlyTheAnchorFact() {
        // Pure decision table (knife target): identical page id lists except for the anchor.
        val page = listOf("m10", "m12")
        assertEquals(GapCatchUpDecision.MergeAtNewestEdge, gapCatchUpDecision("m10", page, timelineAtTail = true, viewportFollowsLatest = true))
        assertEquals(GapCatchUpDecision.MergeAtNewestEdge, gapCatchUpDecision("m10", page, timelineAtTail = false, viewportFollowsLatest = false))
        assertEquals(GapCatchUpDecision.ReplaceWithLatestPage, gapCatchUpDecision("m9", page, timelineAtTail = true, viewportFollowsLatest = true))
        assertEquals(GapCatchUpDecision.DetachFromTail, gapCatchUpDecision("m9", page, timelineAtTail = true, viewportFollowsLatest = false))
        assertEquals(GapCatchUpDecision.DetachFromTail, gapCatchUpDecision("m9", page, timelineAtTail = false, viewportFollowsLatest = true))
        assertEquals(GapCatchUpDecision.ReplaceWithLatestPage, gapCatchUpDecision(null, page, timelineAtTail = true, viewportFollowsLatest = true))
    }
}
