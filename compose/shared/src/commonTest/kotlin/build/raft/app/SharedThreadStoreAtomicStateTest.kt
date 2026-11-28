package build.raft.app

import build.raft.app.SharedThreadStoreTest.Companion.basePayload
import build.raft.app.SharedThreadStoreTest.Companion.sampleSession
import build.raft.app.SharedThreadStoreTest.Companion.threadSnapshot
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.SharedThreadStoreTestFakes.FakeConversationRepository
import build.raft.app.SharedThreadStoreTestFakes.FakeMemberRepository
import build.raft.app.SharedThreadStoreTestFixtures.cachePayloadForTest
import build.raft.app.SharedThreadStoreTestFixtures.contentPayload
import build.raft.app.SharedThreadStoreTestFixtures.member
import build.raft.app.data.DataResult
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.RaftAgentLounges
import build.raft.app.model.ChannelId
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.ThreadId
import build.raft.app.thread.LoadMembersStatus
import build.raft.app.thread.SharedThreadDataSource
import build.raft.app.thread.SharedThreadIntent
import build.raft.app.thread.SharedThreadState
import build.raft.app.thread.SharedThreadStore
import build.raft.app.thread.ThreadAccessResolution
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Concurrency regression tests for [SharedThreadStore] state transitions across real threads
 * (`Dispatchers.Default`), pinning that concurrent `reducePayloadPatch`, access resolution
 * (`initialHomeAccessLookupComplete`), and `loadMembers` updates never lose writes.
 */
class SharedThreadStoreAtomicStateTest {
    @BeforeTest
    fun forgetLounges() {
        RaftAgentLounges.clear()
    }

    @AfterTest
    fun forgetLoungesAfter() {
        RaftAgentLounges.clear()
    }

    @Test
    fun concurrentStartAccessResolutionAndPayloadPatchesNeverClobberLookupComplete() = runBlocking {
        // 240 iterations in parallel batches of 8: stresses the exact race between
        // ThreadAccessResolver.launchInitialAccessResolution setting
        // initialHomeAccessLookupComplete = true on Dispatchers.Default and concurrent
        // reducePayloadPatch calls (refresh loading_apply + realtime/UI patches).
        val totalIterations = 240
        val batchSize = 8
        for (batchStart in 0 until totalIterations step batchSize) {
            val batchEnd = minOf(batchStart + batchSize, totalIterations)
            coroutineScope {
                (batchStart until batchEnd).map { iteration ->
                    launch(Dispatchers.Default) {
                        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                        val loadingApplied = CompletableDeferred<Unit>()
                        val releaseRefreshFetch = CompletableDeferred<Unit>()
                        try {
                            val store =
                                SharedThreadStore(
                                    dataSource = atomicTestDataSource(),
                                    scope = scope,
                                    afterRefreshLoadingStateApplied = {
                                        loadingApplied.complete(Unit)
                                        releaseRefreshFetch.await()
                                    },
                                )
                            val startJob =
                                launch(Dispatchers.Default) {
                                    store.accept(SharedThreadIntent.Start(basePayload()))
                                }
                            withTimeout(10_000) { loadingApplied.await() }

                            val raceMessageId = "m-race-$iteration"
                            val realtimeJob =
                                launch(Dispatchers.Default) {
                                    store.accept(
                                        SharedThreadIntent.ApplyRealtimeEvent(
                                            MessageNew(
                                                Message(
                                                    id = MessageId(raceMessageId),
                                                    threadId = ThreadId("channel-1"),
                                                    channelId = ChannelId("channel-1"),
                                                    seq = 11L,
                                                    author = member("u2", "Stdrc"),
                                                    timestampLabel = "2026-05-19T16:01:00Z",
                                                    body = "race body $iteration",
                                                )
                                            )
                                        )
                                    )
                                }
                            val patchJobs =
                                listOf(
                                    launch(Dispatchers.Default) {
                                        store.accept(SharedThreadIntent.FocusHistoryStart())
                                    },
                                    launch(Dispatchers.Default) {
                                        store.accept(SharedThreadIntent.FocusHistoryStart())
                                    },
                                    launch(Dispatchers.Default) {
                                        store.accept(
                                            SharedThreadIntent.SetPagingAvailability(
                                                canLoadMoreBefore = true,
                                                canLoadMoreAfter = false,
                                            )
                                        )
                                    },
                                )
                            // Ensure the realtime event lands in the in-flight fold while racing
                            // directly against launchInitialAccessResolution on Dispatchers.Default,
                            // then release refreshOnce so FocusHistoryStart/SetPagingAvailability
                            // also race against refreshOnce's canonical commit.
                            realtimeJob.join()
                            releaseRefreshFetch.complete(Unit)
                            joinAll(startJob, *patchJobs.toTypedArray())

                            val settled =
                                awaitAtomicThreadState(
                                    store = store,
                                    reason = "iteration=$iteration resolved with lookupComplete",
                                ) { state ->
                                    state.accessResolution == ThreadAccessResolution.Resolved &&
                                        state.initialHomeAccessLookupComplete &&
                                        state.canonicalAccessResolved
                                }
                            assertTrue(
                                settled.initialHomeAccessLookupComplete,
                                "iteration=$iteration initialHomeAccessLookupComplete must never be clobbered to false"
                            )
                            assertEquals(
                                ThreadAccessResolution.Resolved,
                                settled.accessResolution,
                                "iteration=$iteration accessResolution"
                            )
                            assertEquals(
                                2,
                                settled.payload.historyStartFocusRequest,
                                "iteration=$iteration concurrent FocusHistoryStart patches must not be lost"
                            )
                            assertTrue(
                                settled.payload.messages.any { it.id == raceMessageId },
                                "iteration=$iteration concurrent realtime message $raceMessageId must survive"
                            )
                        } finally {
                            releaseRefreshFetch.complete(Unit)
                            scope.cancel()
                        }
                    }
                }.joinAll()
            }
        }
    }

    @Test
    fun concurrentPayloadPatchesAndMembersLoadNeverLoseUpdates() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val memberRepository =
                FakeMemberRepository(
                    serverMembers = listOf(member("u1", "Artin")),
                    channelMembers = listOf(member("u1", "Artin")),
                )
            val dataSource =
                testSharedThreadDataSource(
                    networkApi = FakeThreadApi(failChannelMessages = true),
                    sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                    cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                    conversationRepository =
                        FakeConversationRepository(
                            threadResult = DataResult.Content(threadSnapshot(body = "cached body", seq = 0))
                        ),
                    memberRepository = memberRepository,
                ).also { it.cachePayloadForTest(contentPayload("cached body")) }

            val store = SharedThreadStore(dataSource = dataSource, scope = scope)
            store.accept(SharedThreadIntent.Start(basePayload()))
            awaitAtomicThreadState(store, "initial resolved state") { state ->
                state.accessResolution == ThreadAccessResolution.Resolved &&
                    state.initialHomeAccessLookupComplete &&
                    state.canonicalAccessResolved
            }

            val concurrentIncrements = 220
            coroutineScope {
                val jobs =
                    buildList {
                        repeat(concurrentIncrements) {
                            add(
                                launch(Dispatchers.Default) {
                                    store.accept(SharedThreadIntent.FocusHistoryStart())
                                }
                            )
                        }
                        add(
                            launch(Dispatchers.Default) {
                                store.accept(SharedThreadIntent.LoadMembers)
                            }
                        )
                        add(
                            launch(Dispatchers.Default) {
                                store.accept(SharedThreadIntent.SetAddMemberStatus(status = "done", errorCode = null))
                            }
                        )
                        add(
                            launch(Dispatchers.Default) {
                                store.accept(SharedThreadIntent.SelectTab("tasks"))
                            }
                        )
                        add(
                            launch(Dispatchers.Default) {
                                store.accept(
                                    SharedThreadIntent.ApplyRealtimeEvent(
                                        MessageNew(
                                            Message(
                                                id = MessageId("m-live"),
                                                threadId = ThreadId("channel-1"),
                                                channelId = ChannelId("channel-1"),
                                                seq = 11L,
                                                author = member("u2", "Stdrc"),
                                                timestampLabel = "2026-05-19T16:01:00Z",
                                                body = "live body",
                                            )
                                        )
                                    )
                                )
                            }
                        )
                    }
                jobs.joinAll()
            }

            val finalState = store.state.value
            assertEquals(
                concurrentIncrements,
                finalState.payload.historyStartFocusRequest,
                "every concurrent FocusHistoryStart increment must be preserved atomically"
            )
            assertTrue(
                finalState.initialHomeAccessLookupComplete,
                "initialHomeAccessLookupComplete must never be clobbered by concurrent updates"
            )
            assertEquals(ThreadAccessResolution.Resolved, finalState.accessResolution)
            assertEquals(LoadMembersStatus.Loaded, finalState.membersLoadStatus)
            assertEquals(listOf("u1"), finalState.payload.members.map { it.id })
            assertEquals("done", finalState.payload.addMemberStatus)
            assertEquals("tasks", finalState.payload.selectedTab)
            assertTrue(finalState.payload.messages.any { it.id == "m-live" })
        } finally {
            scope.cancel()
        }
    }

    private fun atomicTestDataSource(): SharedThreadDataSource =
        testSharedThreadDataSource(
            networkApi = FakeThreadApi(failChannelMessages = true),
            sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
            cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            conversationRepository =
                FakeConversationRepository(
                    threadResult = DataResult.Content(threadSnapshot(body = "cached body", seq = 0))
                ),
        ).also { it.cachePayloadForTest(contentPayload("cached body")) }
}

private suspend fun awaitAtomicThreadState(
    store: SharedThreadStore,
    reason: String,
    predicate: (SharedThreadState) -> Boolean,
): SharedThreadState =
    try {
        withTimeout(10_000) { store.state.first(predicate) }
    } catch (_: TimeoutCancellationException) {
        val state = store.state.value
        error(
            "waited for $reason: thread=${state.payload.sourceThreadId} status=${state.payload.status} " +
                "messages=${state.payload.messages.map { it.id }} access=${state.accessResolution} " +
                "authority=${state.replyParentAuthority} lookupComplete=${state.initialHomeAccessLookupComplete} " +
                "canonical=${state.canonicalAccessResolved} writeActions=${state.writeActionsEnabled} " +
                "historyStartFocusRequest=${state.payload.historyStartFocusRequest}"
        )
    }
