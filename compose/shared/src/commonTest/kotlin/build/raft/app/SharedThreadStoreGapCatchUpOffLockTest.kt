package build.raft.app

import build.raft.app.SharedThreadStoreTest.Companion.basePayload
import build.raft.app.SharedThreadStoreTest.Companion.message
import build.raft.app.SharedThreadStoreTest.Companion.messagesResponse
import build.raft.app.SharedThreadStoreTest.Companion.pageRangeResponse
import build.raft.app.SharedThreadStoreTest.Companion.pageResponse
import build.raft.app.SharedThreadStoreTest.Companion.sampleSession
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.KeyValueRealtimeAuthRevisionStore
import build.raft.app.data.RealtimeAuthRevision
import build.raft.app.data.RealtimeAuthoritySession
import build.raft.app.data.RealtimeAuthorityRegistry
import build.raft.app.data.RealtimeGrantRecord
import build.raft.app.model.MessageNew
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Hands bc6e392e: the Thread realtime sink ran the gap catch-up fetch inline, under the process-wide
 * realtime authority lock ([RealtimeAuthorityRegistry.runIfCurrentGrant]), so every other live event and
 * channel-join ack waited ~6 s behind one thread's network round trip. The sink now only registers the gap
 * (and applies live events into the registered recovery); the fetch runs on the store scope.
 *
 * Deliveries go through a real registry, so "the lock is free" is the registry's own mutex. Waits on a
 * sink delivery are bounded in virtual time: a delivery stuck behind the held catch-up returns `null`.
 */
class SharedThreadStoreGapCatchUpOffLockTest {
    @Test
    fun otherConversationEventAndJoinAckRunWhileThreadGapCatchUpIsHeldOpen() = runTest {
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
                newerBodyText =
                    pageResponse(
                        Triple("m10", 10L, "current body"),
                        Triple("m11", 11L, "gap body"),
                        Triple("m12", 12L, "gap trigger"),
                    ),
            )
        api.catchUpStarted = CompletableDeferred()
        api.releaseCatchUp = CompletableDeferred()
        val store = store(api, backgroundScope)
        val gapCatchUps = store.recordGapRecoveryJobs()
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = false)))
        val lock = realtimeLock()

        val gapDelivery = async {
            lock.deliver {
                store.accept(SharedThreadIntent.ApplyRealtimeEvent(MessageNew(message(id = "m12", seq = 12, body = "gap trigger"))))
            }
        }
        api.catchUpStarted?.await()
        assertTrue(gapDelivery.isCompleted, "the gap trigger's sink delivery must return before its catch-up does")

        val otherConversation =
            withTimeoutOrNull(SINK_DELIVERY_BUDGET_MS) {
                lock.deliver {
                    store.accept(
                        SharedThreadIntent.ApplyRealtimeEvent(
                            MessageNew(message(threadId = "channel-2", id = "c2-500", seq = 500, body = "other conversation")),
                        ),
                    )
                }
            }
        assertEquals(
            true,
            otherConversation,
            "a live event for another conversation must take the realtime lock while the thread catch-up is held, " +
                "status=${store.state.value.payload.newerMessagesStatus}",
        )
        var joinAckRecorded = false
        // Shape of `onChannelJoinAck` in RaftSharedStoreProvider: a block under the same grant lock.
        val joinAck = withTimeoutOrNull(SINK_DELIVERY_BUDGET_MS) { lock.deliver { joinAckRecorded = true } }
        assertEquals(true, joinAck, "a channel-join ack must take the realtime lock while the thread catch-up is held")
        assertTrue(joinAckRecorded)

        assertEquals(1, api.catchUpCalls)
        assertEquals("loading", store.state.value.payload.newerMessagesStatus, "the catch-up is still in flight")
        assertEquals(listOf("m10"), store.state.value.payload.messages.map { it.id })

        api.releaseCatchUp?.complete(Unit)
        gapCatchUps.joinAll()

        val payload = store.state.value.payload
        assertEquals(listOf("m10", "m11", "m12"), payload.messages.map { it.id })
        assertEquals("idle", payload.newerMessagesStatus)
        assertEquals(1, gapCatchUps.count)
        assertEquals(1, api.catchUpCalls)
    }

    @Test
    fun liveEventsAppliedAroundOffLockCatchUpMergeInOrderWithoutDuplicatesOrLosses() = runTest {
        // The screen shows m10; the gap trigger is m12. Before the catch-up even starts, m100 is applied into
        // the registered recovery. The request must still be built from the timeline at registration: with m100
        // as the anchor the cheap page (71..100) would look like a small gap and m11..m70 would be lost.
        val api = FakeThreadApi(bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false))
        api.catchUpBodyTexts.addLast(pageRangeResponse(71L..100L))
        api.catchUpBodyTexts.addLast(pageRangeResponse(10L..100L))
        val narrowStarted = CompletableDeferred<Unit>()
        val releaseNarrow = CompletableDeferred<Unit>()
        api.catchUpStartedSignals.addLast(narrowStarted)
        api.catchUpReleases.addLast(releaseNarrow)
        val store = store(api, backgroundScope)
        val gapCatchUps = store.recordGapRecoveryJobs()
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = false)))
        val lock = realtimeLock()
        suspend fun deliverLive(id: String, seq: Long) {
            val delivered =
                withTimeoutOrNull(SINK_DELIVERY_BUDGET_MS) {
                    lock.deliver {
                        store.accept(SharedThreadIntent.ApplyRealtimeEvent(MessageNew(message(id = id, seq = seq, body = "body-$seq"))))
                    }
                }
            assertEquals(true, delivered, "sink delivery of $id must not wait behind the catch-up, catchUps=${api.catchUpCalls}")
        }

        deliverLive("m12", 12L)
        deliverLive("m100", 100L)
        assertEquals(0, api.catchUpCalls, "precondition: m100 lands between the gap registration and the fetch")
        assertEquals(listOf(10L, 100L), store.state.value.payload.messages.mapNotNull { it.seq })

        narrowStarted.await()
        deliverLive("m60", 60L)
        deliverLive("m100", 100L)
        deliverLive("m101", 101L)
        releaseNarrow.complete(Unit)
        gapCatchUps.joinAll()

        val payload = store.state.value.payload
        assertEquals(
            (10L..101L).toList(),
            payload.messages.mapNotNull { it.seq },
            "page and live events must merge in order with nothing lost",
        )
        assertEquals(payload.messages.size, payload.messages.map { it.id }.distinct().size, "no message may appear twice")
        assertEquals(
            listOf(SharedThreadPaging.DefaultPageSize, SharedThreadGapCatchUpPageLimit),
            api.catchUpLimits,
            "the anchor (m10) is not on the cheap page, so the wide page must be asked for",
        )
        assertEquals(1, gapCatchUps.count, "live events during the catch-up join it instead of starting another")
        assertEquals("idle", payload.newerMessagesStatus)
        assertEquals(false, payload.canLoadMoreAfter)
    }

    @Test
    fun routeChangeDuringOffLockCatchUpDropsTheStaleResult() = runTest {
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
                newerBodyText =
                    pageResponse(Triple("m10", 10L, "current body"), Triple("m12", 12L, "stale gap body")),
            )
        api.bodyTextByThreadId["channel-2"] =
            messagesResponse("new route baseline", id = "m20", seq = 20, historyLimited = false, channelId = "channel-2")
        api.catchUpStarted = CompletableDeferred()
        api.releaseCatchUp = CompletableDeferred()
        val logs = mutableListOf<String>()
        val store = store(api, backgroundScope, logs::add)
        val gapCatchUps = store.recordGapRecoveryJobs()
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = false)))
        val lock = realtimeLock()

        val delivered =
            withTimeoutOrNull(SINK_DELIVERY_BUDGET_MS) {
                lock.deliver {
                    store.accept(SharedThreadIntent.ApplyRealtimeEvent(MessageNew(message(id = "m12", seq = 12, body = "gap trigger"))))
                }
            }
        assertEquals(true, delivered, "the gap trigger's sink delivery must not wait for its catch-up")
        api.catchUpStarted?.await()

        store.accept(
            SharedThreadIntent.Start(
                basePayload().copy(
                    instanceId = "shared-shell-thread-channel-2",
                    title = "channel-2",
                    sourceThreadId = "channel-2",
                    streamChannelId = "channel-2",
                    canLoadMoreAfter = false,
                ),
            ),
        )
        assertEquals(listOf("m20"), store.state.value.payload.messages.map { it.id })

        api.releaseCatchUp?.complete(Unit)
        gapCatchUps.joinAll()

        val payload = store.state.value.payload
        assertEquals("channel-2", payload.sourceThreadId)
        assertEquals(listOf("m20"), payload.messages.map { it.id }, "the old route's catch-up page must be dropped")
        assertEquals("idle", payload.newerMessagesStatus)
        assertTrue(
            logs.any { it.startsWith("thread_realtime_gap_stale_drop") && "stage=after_fetch" in it },
            logs.filter { "gap" in it }.toString(),
        )
    }

    private fun store(
        api: FakeThreadApi,
        scope: CoroutineScope,
        logger: ((String) -> Unit)? = null,
    ): SharedThreadStore =
        SharedThreadStore(
            dataSource =
                testSharedThreadDataSource(
                    networkApi = api,
                    sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                    cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                ),
            scope = scope,
            diagnosticsLogger = logger,
        )

    private class RealtimeLock(
        private val registry: RealtimeAuthorityRegistry,
        private val grant: RealtimeGrantRecord,
    ) {
        /** One sink delivery, under the registry mutex exactly as production applies live events. */
        suspend fun deliver(block: suspend () -> Unit): Boolean = registry.runIfCurrentGrant(grant, block)
    }

    private suspend fun realtimeLock(): RealtimeLock {
        val registry =
            RealtimeAuthorityRegistry(
                authRevisionStore = KeyValueRealtimeAuthRevisionStore(InMemoryRaftKeyValueStore()),
                processInstanceId = "thread-gap-off-lock",
            )
        registry.advanceAuth(
            RealtimeAuthRevision(
                generation = 1L,
                session = RealtimeAuthoritySession(principalId = "u1", serverIds = listOf("server-1"), environmentId = TestThreadEnvironmentId),
            ),
        )
        val lease = registry.register("thread-gap-off-lock")
        return RealtimeLock(registry, assertNotNull(registry.currentGrant(lease.id, "server-1")))
    }

    private companion object {
        const val SINK_DELIVERY_BUDGET_MS = 5_000L
    }
}
