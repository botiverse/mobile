package build.raft.app.data

import build.raft.app.SharedThreadStoreTestFakes.FakeMemberRepository
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Behavior tests for store-owned channel members freshness (#wg-mobile-feedback:#274 Phase 1).
 *
 * Replaces page-owned `RaftLiveCatchUpEffect` in `ThreadMembersPage`:
 * 1. Actively observed channel member slices receive Foreground, Reconnect, and NetworkRestored
 *    catch-up refreshes in the data layer (Background priority, content kept on refresh and error,
 *    failed catch-up does not burn the 10 s gate window, mid-flight NetworkRestored is latched).
 * 2. Non-visible / unsubscribed stores (no collector, collector cancelled, owner switched away,
 *    or server dropped) do not trigger spurious catch-up refreshes.
 */
class SharedServerChannelMembersFreshnessTest {
    private val envId = "https://raft.example"
    private val accountId = "user-1"
    private val serverId = ServerId("server-1")
    private val channelId = ChannelId("channel-1")
    private val routeOwner = AuthenticatedRouteOwner(envId, accountId, serverId.value)

    private fun member(id: String, name: String = id) =
        MemberProfile(id = MemberId(id), displayName = name, handle = name.lowercase(), kind = MemberKind.Human)

    private fun owner(
        environmentId: String = envId,
        account: String = accountId,
        server: String = serverId.value,
    ) = ProcessServerMembersOwner(environmentId, account, server)

    private class ScriptedChannelMembersRepository : MemberRepository by FakeMemberRepository() {
        val calls = mutableListOf<Pair<DataRequestPriority, ChannelId>>()
        var nextAnswer: suspend (DataRequestPriority, ChannelId) -> DataResult<ChannelMemberSnapshot> = { _, id ->
            DataResult.Content(ChannelMemberSnapshot(channelId = id, members = emptyList()))
        }

        override suspend fun getChannelMembers(
            priority: DataRequestPriority,
            channelId: ChannelId,
        ): DataResult<ChannelMemberSnapshot> {
            calls += priority to channelId
            return nextAnswer(priority, channelId)
        }
    }

    private fun createStore(
        repository: MemberRepository,
        ownerFlow: MutableStateFlow<ProcessServerMembersOwner?>,
        writeGeneration: () -> Long = { 1L },
    ): SharedServerMembersStore {
        val bindingFor: (ServerId) -> SharedServerChannelMembersOwnerBinding? = { id ->
            ownerFlow.value
                ?.takeIf { it.serverId == id.value.trim() }
                ?.let {
                    SharedServerChannelMembersOwnerBinding(
                        environmentId = it.environmentId,
                        accountId = it.accountId,
                        serverId = it.serverId,
                        writeGeneration = writeGeneration(),
                    )
                }
        }
        return SharedServerMembersStore(
            memberRepository = repository,
            presenceOwner = { id -> ownerFlow.value?.takeIf { it.serverId == id.value.trim() } },
            presenceOwnerFlow = { id ->
                MutableStateFlow(ownerFlow.value?.takeIf { it.serverId == id.value.trim() }).also {
                    // Test helper uses explicit channelMembersOwnerFlow below.
                }
            },
            presenceWrite = { id ->
                ownerFlow.value
                    ?.takeIf { it.serverId == id.value.trim() }
                    ?.let { ProcessPresenceWrite(it, writeGeneration()) }
            },
            channelMembersOwner = bindingFor,
            channelMembersOwnerFlow = { id ->
                kotlinx.coroutines.flow.flow {
                    ownerFlow.collect { current ->
                        emit(
                            current
                                ?.takeIf { it.serverId == id.value.trim() }
                                ?.let {
                                    SharedServerChannelMembersOwnerBinding(
                                        environmentId = it.environmentId,
                                        accountId = it.accountId,
                                        serverId = it.serverId,
                                        writeGeneration = writeGeneration(),
                                    )
                                }
                        )
                    }
                }
            },
        )
    }

    @Test
    fun observedChannelSliceCatchesUpOnForegroundReconnectAndNetworkRestoredKeepingContentAndOutcomeWindow() =
        runTest {
            val repository = ScriptedChannelMembersRepository()
            repository.nextAnswer = { _, id ->
                DataResult.Content(ChannelMemberSnapshot(channelId = id, members = listOf(member("m1", "Alice"))))
            }
            val ownerFlow = MutableStateFlow<ProcessServerMembersOwner?>(owner())
            val store = createStore(repository, ownerFlow)
            val freshness = MutableSharedFlow<RaftFreshnessEvent>(extraBufferCapacity = 16)
            var clock = 1_000_000L
            store.startChannelMembersFreshness(
                scope = backgroundScope,
                freshness = freshness,
                nowMillis = { clock },
                reconnectJitterMillis = { 0L },
                liveConnectionToken = { null },
            )

            // Initial load & active page subscription (ThreadMembersPage routeOwner flow).
            store.loadChannelMembers(routeOwner, channelId, DataRequestPriority.Visible, force = true)
            val observedSlices = mutableListOf<SharedServerChannelMembersSlice>()
            val collectorJob =
                backgroundScope.launch {
                    store.channelMemberSliceFlow(routeOwner, channelId).collect { observedSlices += it }
                }
            runCurrent()
            assertEquals(listOf("m1"), observedSlices.last().members.map { it.id.value })
            assertEquals(1, repository.calls.size)

            // 1. Foreground fails while offline: existing rows stay intact and the 10 s gate window is NOT burned.
            repository.nextAnswer = { _, _ ->
                DataResult.Error(ScreenError(code = "network_offline", retryable = true))
            }
            freshness.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.Foreground))
            runCurrent()
            assertEquals(2, repository.calls.size)
            assertEquals(DataRequestPriority.Background, repository.calls.last().first)
            assertEquals(listOf("m1"), observedSlices.last().members.map { it.id.value }, "failed catch-up keeps existing rows")

            // 2 s later (< 10 s coalesce window): NetworkRestored retries immediately and delivers the new roster.
            clock += 2_000L
            repository.nextAnswer = { _, id ->
                DataResult.Content(
                    ChannelMemberSnapshot(channelId = id, members = listOf(member("m1", "Alice"), member("m2", "Bob")))
                )
            }
            freshness.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.NetworkRestored))
            runCurrent()
            assertEquals(3, repository.calls.size)
            assertEquals(DataRequestPriority.Background, repository.calls.last().first)
            assertEquals(listOf("m1", "m2"), observedSlices.last().members.map { it.id.value })

            // Redundant NetworkRestored 1 s after the successful catch-up coalesces inside the 10 s window.
            clock += 1_000L
            freshness.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.NetworkRestored))
            runCurrent()
            assertEquals(3, repository.calls.size)

            // 2. Reconnect for another server is ignored; new-epoch own-server Reconnect refreshes even inside 10 s.
            freshness.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.Reconnect, serverId = "server-2", transportEpoch = 1L))
            runCurrent()
            assertEquals(3, repository.calls.size)

            repository.nextAnswer = { _, id ->
                DataResult.Content(
                    ChannelMemberSnapshot(
                        channelId = id,
                        members = listOf(member("m1", "Alice"), member("m2", "Bob"), member("m3", "Cara")),
                    )
                )
            }
            freshness.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.Reconnect, serverId = serverId.value, transportEpoch = 2L))
            runCurrent()
            assertEquals(4, repository.calls.size)
            assertEquals(listOf("m1", "m2", "m3"), observedSlices.last().members.map { it.id.value })

            // Same transport epoch again (`rooms:joined` after `connected`) does not re-fetch.
            freshness.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.Reconnect, serverId = serverId.value, transportEpoch = 2L))
            runCurrent()
            assertEquals(4, repository.calls.size)

            collectorJob.cancel()
            store.dispose()
        }

    @Test
    fun midFlightNetworkRestoredDuringFailingForegroundCatchUpRunsImmediatelyAndKeepsContent() =
        runTest {
            val repository = ScriptedChannelMembersRepository()
            repository.nextAnswer = { _, id ->
                DataResult.Content(ChannelMemberSnapshot(channelId = id, members = listOf(member("m1", "Alice"))))
            }
            val ownerFlow = MutableStateFlow<ProcessServerMembersOwner?>(owner())
            val store = createStore(repository, ownerFlow)
            val freshness = MutableSharedFlow<RaftFreshnessEvent>(extraBufferCapacity = 16)
            var clock = 1_000_000L
            store.startChannelMembersFreshness(
                scope = backgroundScope,
                freshness = freshness,
                nowMillis = { clock },
                reconnectJitterMillis = { 0L },
                liveConnectionToken = { null },
            )

            store.loadChannelMembers(routeOwner, channelId, DataRequestPriority.Visible, force = true)
            val collectorJob =
                backgroundScope.launch {
                    store.channelMemberSliceFlow(routeOwner, channelId).collect {}
                }
            runCurrent()

            val fgGate = CompletableDeferred<Unit>()
            var callNumber = 0
            repository.nextAnswer = { _, id ->
                callNumber += 1
                if (callNumber == 1) {
                    fgGate.await()
                    DataResult.Error(ScreenError(code = "timeout", retryable = true))
                } else {
                    DataResult.Content(
                        ChannelMemberSnapshot(channelId = id, members = listOf(member("m1", "Alice"), member("m2", "Bob")))
                    )
                }
            }

            // Foreground starts and hangs; NetworkRestored arrives mid-flight -> latched (single-flight).
            freshness.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.Foreground))
            runCurrent()
            assertEquals(2, repository.calls.size)
            assertEquals(
                listOf("m1"),
                store.currentChannelMemberSlice(routeOwner, channelId).members.map { it.id.value },
                "existing rows stay visible while refresh is in flight",
            )

            clock += 1_500L
            freshness.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.NetworkRestored))
            runCurrent()
            assertEquals(2, repository.calls.size, "single-flight waits for hanging foreground call")

            // Hanging foreground call fails -> latched NetworkRestored fires immediately and updates rows.
            clock += 500L
            fgGate.complete(Unit)
            runCurrent()
            assertEquals(3, repository.calls.size)
            assertEquals(
                listOf("m1", "m2"),
                store.currentChannelMemberSlice(routeOwner, channelId).members.map { it.id.value },
            )

            collectorJob.cancel()
            store.dispose()
        }

    @Test
    fun unobservedOrUnsubscribedStoreDoesNotTriggerSpuriousCatchUpRefreshes() =
        runTest {
            val repository = ScriptedChannelMembersRepository()
            repository.nextAnswer = { _, id ->
                DataResult.Content(ChannelMemberSnapshot(channelId = id, members = listOf(member("m1", "Alice"))))
            }
            val ownerFlow = MutableStateFlow<ProcessServerMembersOwner?>(owner())
            val store = createStore(repository, ownerFlow)
            val freshness = MutableSharedFlow<RaftFreshnessEvent>(extraBufferCapacity = 16)
            var clock = 1_000_000L
            store.startChannelMembersFreshness(
                scope = backgroundScope,
                freshness = freshness,
                nowMillis = { clock },
                reconnectJitterMillis = { 0L },
                liveConnectionToken = { null },
            )

            // Loaded in cache, but ZERO active subscribers -> freshness events must not fetch.
            store.loadChannelMembers(routeOwner, channelId, DataRequestPriority.Visible, force = true)
            assertEquals(1, repository.calls.size)
            assertTrue(store.observedChannelMemberScopes().isEmpty())

            freshness.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.Foreground))
            freshness.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.NetworkRestored))
            freshness.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.Reconnect, serverId = serverId.value, transportEpoch = 1L))
            runCurrent()
            assertEquals(1, repository.calls.size, "unobserved store must ignore freshness events")

            // Subscribe and unsubscribe cleanly -> observer count returns to 0 and subsequent events are ignored.
            val collectorJob =
                backgroundScope.launch {
                    store.channelMemberSliceFlow(routeOwner, channelId).collect {}
                }
            runCurrent()
            assertEquals(1, store.observedChannelMemberScopes().size)

            collectorJob.cancel()
            runCurrent()
            assertTrue(store.observedChannelMemberScopes().isEmpty())

            clock += RAFT_FRESHNESS_COALESCE_MILLIS + 1L
            freshness.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.Foreground))
            freshness.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.NetworkRestored))
            freshness.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.Reconnect, serverId = serverId.value, transportEpoch = 2L))
            runCurrent()
            assertEquals(1, repository.calls.size, "unsubscribed store must not trigger spurious catch-ups")

            // Account switches away from routeOwner while page is still composed -> observer detaches automatically.
            val staleRouteCollector =
                backgroundScope.launch {
                    store.channelMemberSliceFlow(routeOwner, channelId).collect {}
                }
            runCurrent()
            assertEquals(1, store.observedChannelMemberScopes().size)

            ownerFlow.value = owner(account = "user-2")
            runCurrent()
            assertTrue(store.observedChannelMemberScopes().isEmpty(), "owner mismatch detaches route observer")

            clock += RAFT_FRESHNESS_COALESCE_MILLIS + 1L
            freshness.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.Foreground))
            runCurrent()
            assertEquals(1, repository.calls.size, "stale-owner route must not catch up under successor account")

            staleRouteCollector.cancel()
            store.dispose()
        }

    @Test
    fun afterDropReconnectWaitsForJitterAndSkipsWhenObserverLeaves() =
        runTest {
            val repository = ScriptedChannelMembersRepository()
            repository.nextAnswer = { _, id ->
                DataResult.Content(ChannelMemberSnapshot(channelId = id, members = listOf(member("m1", "Alice"))))
            }
            val ownerFlow = MutableStateFlow<ProcessServerMembersOwner?>(owner())
            val store = createStore(repository, ownerFlow)
            val freshness = MutableSharedFlow<RaftFreshnessEvent>(extraBufferCapacity = 16)
            store.startChannelMembersFreshness(
                scope = backgroundScope,
                freshness = freshness,
                nowMillis = { 1_000_000L },
                reconnectJitterMillis = { 8_000L },
                liveConnectionToken = { null },
            )

            val collector1 =
                backgroundScope.launch {
                    store.channelMemberSliceFlow(serverId, channelId).collect {}
                }
            runCurrent()

            freshness.tryEmit(
                RaftFreshnessEvent(
                    trigger = RaftFreshnessTrigger.Reconnect,
                    serverId = serverId.value,
                    transportEpoch = 1L,
                    afterDrop = true,
                )
            )
            runCurrent()
            assertEquals(0, repository.calls.size, "afterDrop reconnect waits for jitter")

            // Unsubscribe before jitter fires -> cancelled, no fetch.
            collector1.cancel()
            runCurrent()
            advanceTimeBy(10_000L)
            runCurrent()
            assertEquals(0, repository.calls.size, "leaving during jitter cancels pending refresh")

            // Re-subscribe and let jitter elapse -> fetches once.
            val collector2 =
                backgroundScope.launch {
                    store.channelMemberSliceFlow(serverId, channelId).collect {}
                }
            runCurrent()
            freshness.tryEmit(
                RaftFreshnessEvent(
                    trigger = RaftFreshnessTrigger.Reconnect,
                    serverId = serverId.value,
                    transportEpoch = 2L,
                    afterDrop = true,
                )
            )
            runCurrent()
            assertEquals(0, repository.calls.size)
            advanceTimeBy(8_000L)
            runCurrent()
            assertEquals(1, repository.calls.size)
            assertFalse(store.observedChannelMemberScopes().isEmpty())

            collector2.cancel()
            store.dispose()
        }
}
