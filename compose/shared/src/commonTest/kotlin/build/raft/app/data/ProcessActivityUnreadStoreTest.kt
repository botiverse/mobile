package build.raft.app.data

import build.raft.app.model.ServerId
import build.raft.app.model.ChannelId
import build.raft.app.model.DirectMessageCreated
import build.raft.app.model.MessageId
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.RealtimeRefreshScope
import build.raft.app.model.TaskCreated
import build.raft.app.model.TaskDeleted
import build.raft.app.model.TaskId
import build.raft.app.model.ThreadUpdated
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.yield
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProcessActivityUnreadStoreTest {
    @Test
    fun activityRealtimePredicateIsDomainSpecificAndIncludesSummaryMutations() {
        val threadUpdated =
            ThreadUpdated(
                parentMessageId = MessageId("parent-1"),
                threadChannelId = ChannelId("thread-1"),
                replyCount = 1,
                lastReplyAt = null,
                participantIds = emptyList(),
                unreadCount = 1
            )
        val activityEvents =
            listOf(
                DirectMessageCreated(ChannelId("dm-1")),
                threadUpdated,
                TaskCreated(emptyList()),
                TaskDeleted(TaskId("task-1")),
                RealtimeRefreshRequired(RealtimeRefreshScope.Home, reason = "gap")
            )

        assertTrue(activityEvents.all { event -> event.affectsActivityUnreadSummary() })
        assertFalse(
            RealtimeRefreshRequired(RealtimeRefreshScope.Announcements, reason = "unrelated")
                .affectsActivityUnreadSummary()
        )
    }

    @Test
    fun sameForegroundEpochCoalescesToOneLeadingEdgeFetch() = runBlocking {
        val ownerFlow = MutableStateFlow<ProcessActivityUnreadOwner?>(owner())
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var fetches = 0
        val store =
            store(ownerFlow) { _, _ ->
                fetches += 1
                started.complete(Unit)
                release.await()
                DataResult.Content(ActivityUnreadSummarySnapshot(totalUnreadCount = 4, totalCount = 9))
            }

        store.onForeground(ServerId("server-1"), epoch = 7L, reason = "app_shell_resume")
        started.await()
        store.onForeground(ServerId("server-1"), epoch = 7L, reason = "thread_resume")
        release.complete(Unit)

        val projection = awaitFresh(store)
        assertEquals(1, fetches)
        assertEquals(4, projection.totalUnreadCount)
        assertEquals(7L, projection.lastForegroundEpoch)
    }

    /** Task #164: the server's remembered inbox (`X-Raft-Stale`) never replaces a count already known. */
    @Test
    fun aRememberedSummaryKeepsTheKnownCountAndMarksItStale() = runBlocking {
        val ownerFlow = MutableStateFlow<ProcessActivityUnreadOwner?>(owner())
        val answers =
            ArrayDeque(
                listOf(
                    ActivityUnreadSummarySnapshot(totalUnreadCount = 4, totalCount = 9),
                    ActivityUnreadSummarySnapshot(totalUnreadCount = 0, totalCount = 0, staleReason = RaftStaleReason.RwOverloaded),
                )
            )
        val store = store(ownerFlow) { _, _ -> DataResult.Content(answers.removeFirst()) }

        store.onForeground(ServerId("server-1"), epoch = 1L, reason = "app_shell_resume")
        assertEquals(4, awaitFresh(store).totalUnreadCount)
        store.onForeground(ServerId("server-1"), epoch = 2L, reason = "app_shell_resume")

        val projection =
            withTimeout(2_000) {
                store.projectionFlow(ServerId("server-1"))
                    .first { it.freshness == ActivityUnreadSummaryFreshness.Stale }
            }
        assertEquals(4, projection.totalUnreadCount, "never the remembered zero")
        assertEquals(9, projection.totalCount)
    }

    @Test
    fun multipleSurfaceEnsuresShareOneInFlightFetch() = runBlocking {
        val ownerFlow = MutableStateFlow<ProcessActivityUnreadOwner?>(owner())
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var fetches = 0
        val store =
            store(ownerFlow) { _, _ ->
                fetches += 1
                started.complete(Unit)
                release.await()
                DataResult.Content(ActivityUnreadSummarySnapshot(totalUnreadCount = 2, totalCount = 3))
            }

        store.ensure(ServerId("server-1"), reason = "app_shell_attach")
        started.await()
        store.ensure(ServerId("server-1"), reason = "thread_attach")
        store.ensure(ServerId("server-1"), reason = "activity_attach")
        release.complete(Unit)

        awaitFresh(store) { it.totalUnreadCount == 2 }
        assertEquals(1, fetches)
    }

    @Test
    fun newForegroundEpochDuringFlightDrainsOneLatestFollowUp() = runBlocking {
        val ownerFlow = MutableStateFlow<ProcessActivityUnreadOwner?>(owner())
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        var fetches = 0
        val store =
            store(ownerFlow) { _, _ ->
                fetches += 1
                if (fetches == 1) {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                    DataResult.Content(ActivityUnreadSummarySnapshot(totalUnreadCount = 1, totalCount = 3))
                } else {
                    secondStarted.complete(Unit)
                    DataResult.Content(ActivityUnreadSummarySnapshot(totalUnreadCount = 6, totalCount = 8))
                }
            }

        store.onForeground(ServerId("server-1"), epoch = 10L, reason = "first")
        firstStarted.await()
        store.onForeground(ServerId("server-1"), epoch = 11L, reason = "second")
        store.onForeground(ServerId("server-1"), epoch = 12L, reason = "latest")
        releaseFirst.complete(Unit)
        secondStarted.await()

        val projection = awaitFresh(store) { it.totalUnreadCount == 6 }
        assertEquals(2, fetches)
        assertEquals(12L, projection.lastForegroundEpoch)
        assertEquals(6, projection.totalUnreadCount)
    }

    @Test
    fun factDuringFlightRejectsOldResponseAndForcesReconcile() = runBlocking {
        val ownerFlow = MutableStateFlow<ProcessActivityUnreadOwner?>(owner())
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var fetches = 0
        val store =
            store(ownerFlow) { _, _ ->
                fetches += 1
                if (fetches == 1) {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                    DataResult.Content(ActivityUnreadSummarySnapshot(totalUnreadCount = 1, totalCount = 1))
                } else {
                    DataResult.Content(ActivityUnreadSummarySnapshot(totalUnreadCount = 9, totalCount = 9))
                }
            }

        store.onForeground(ServerId("server-1"), epoch = 1L, reason = "foreground")
        firstStarted.await()
        store.submitFact(
            ActivityUnreadSummaryFact(
                accountId = "account-1",
                serverId = "server-1",
                totalUnreadCount = 7,
                totalCount = 7,
                authoritative = false,
                source = "realtime_patch"
            )
        )
        releaseFirst.complete(Unit)

        val projection = awaitFresh(store) { it.totalUnreadCount == 9 }
        assertEquals(2, fetches)
        assertEquals(9, projection.totalUnreadCount)
        assertFalse(projection.source == "foreground" && projection.totalUnreadCount == 1)
    }

    @Test
    fun authoritativeFactDuringFlightRejectsOldResponseWithoutRedundantFollowUp() = runBlocking {
        val ownerFlow = MutableStateFlow<ProcessActivityUnreadOwner?>(owner())
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var fetches = 0
        val store =
            store(ownerFlow) { _, _ ->
                fetches += 1
                firstStarted.complete(Unit)
                releaseFirst.await()
                DataResult.Content(ActivityUnreadSummarySnapshot(totalUnreadCount = 1, totalCount = 1))
            }

        store.onForeground(ServerId("server-1"), epoch = 1L, reason = "foreground")
        firstStarted.await()
        store.submitFact(
            ActivityUnreadSummaryFact(
                accountId = "account-1",
                serverId = "server-1",
                totalUnreadCount = 8,
                totalCount = 8,
                authoritative = true,
                source = "activity_canonical:all"
            )
        )
        val factProjection = awaitFresh(store) { it.totalUnreadCount == 8 }
        releaseFirst.complete(Unit)
        yield()

        assertEquals(1, fetches)
        assertEquals(8, factProjection.totalUnreadCount)
        assertEquals(8, store.currentProjection(ServerId("server-1")).totalUnreadCount)
    }

    @Test
    fun ensureBeforeAuthOwnerPublicationWaitsAndLoadsOnce() = runBlocking {
        val ownerFlow = MutableStateFlow<ProcessActivityUnreadOwner?>(null)
        var fetches = 0
        val store =
            store(ownerFlow) { _, _ ->
                fetches += 1
                DataResult.Content(ActivityUnreadSummarySnapshot(totalUnreadCount = 5, totalCount = 6))
            }

        store.ensure(ServerId("server-1"), reason = "process_restore_attach")
        yield()
        assertEquals(0, fetches)

        ownerFlow.value = owner()
        val projection = awaitFresh(store) { it.totalUnreadCount == 5 }
        assertEquals(1, fetches)
        assertEquals(6, projection.totalCount)
    }

    @Test
    fun invalidateAllMakesRestoredSurfaceReconcileAgain() = runBlocking {
        val ownerFlow = MutableStateFlow<ProcessActivityUnreadOwner?>(owner())
        var fetches = 0
        val store =
            store(ownerFlow) { _, _ ->
                fetches += 1
                DataResult.Content(ActivityUnreadSummarySnapshot(totalUnreadCount = fetches, totalCount = fetches))
            }

        store.ensure(ServerId("server-1"), reason = "initial_attach")
        awaitFresh(store) { it.totalUnreadCount == 1 }
        store.invalidateAll("process_restore")
        yield()
        store.ensure(ServerId("server-1"), reason = "restored_attach")

        val projection = awaitFresh(store) { it.totalUnreadCount == 2 }
        assertEquals(2, fetches)
        assertEquals(2, projection.totalUnreadCount)
    }

    @Test
    fun oldSameAccountResponseCannotWriteAfterProcessGenerationReset() = runBlocking {
        val ownerFlow = MutableStateFlow<ProcessActivityUnreadOwner?>(owner())
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var fetches = 0
        val store =
            store(ownerFlow) { _, _ ->
                fetches += 1
                if (fetches == 1) {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                    DataResult.Content(ActivityUnreadSummarySnapshot(totalUnreadCount = 1, totalCount = 1))
                } else {
                    DataResult.Content(ActivityUnreadSummarySnapshot(totalUnreadCount = 9, totalCount = 9))
                }
            }

        store.onForeground(ServerId("server-1"), epoch = 1L, reason = "old_session")
        firstStarted.await()
        store.invalidateAll("same_account_reauthenticated")
        store.ensure(ServerId("server-1"), reason = "new_session_attach")
        awaitFresh(store) { it.totalUnreadCount == 9 }
        releaseFirst.complete(Unit)
        yield()

        assertEquals(2, fetches)
        assertEquals(9, store.currentProjection(ServerId("server-1")).totalUnreadCount)
    }

    @Test
    fun realtimeInvalidationRequiresExactPrincipalAndAuthGeneration() = runBlocking {
        val ownerFlow = MutableStateFlow<ProcessActivityUnreadOwner?>(owner())
        var fetches = 0
        val store =
            store(ownerFlow) { _, _ ->
                fetches += 1
                DataResult.Content(ActivityUnreadSummarySnapshot(totalUnreadCount = 4, totalCount = 4))
            }
        val context =
            ProcessPresenceAuthContext(
                environmentId = "https://api.example.test",
                accountId = "account-1",
                authorityGeneration = 2L,
                writeGeneration = 2L
            )
        val sink = processActivityUnreadRealtimeSink(currentAuthContext = { context }, store = store)
        val event =
            ThreadUpdated(
                parentMessageId = MessageId("parent-1"),
                threadChannelId = ChannelId("thread-1"),
                replyCount = 1,
                lastReplyAt = null,
                participantIds = emptyList(),
                unreadCount = 1
            )

        sink(ProcessActivityUnreadAdmission("server-1", "account-1", authGeneration = 1L), event)
        yield()
        assertEquals(0, fetches)

        sink(ProcessActivityUnreadAdmission("server-1", "account-1", authGeneration = 2L), event)
        awaitFresh(store) { it.totalUnreadCount == 4 }
        assertEquals(1, fetches)
    }

    @Test
    fun ownerFlowRebindsAndRejectsOldAccountFacts() = runBlocking {
        val ownerFlow = MutableStateFlow<ProcessActivityUnreadOwner?>(owner(accountId = "account-1"))
        val store = store(ownerFlow) { _, _ -> DataResult.Empty(EmptyReason.NoInboxItems) }

        store.submitFact(
            ActivityUnreadSummaryFact(
                accountId = "account-1",
                serverId = "server-1",
                totalUnreadCount = 3,
                totalCount = 3,
                authoritative = true,
                source = "account_1"
            )
        )
        awaitFresh(store) { it.totalUnreadCount == 3 }

        ownerFlow.value = owner(accountId = "account-2")
        val rebound = withTimeout(2_000) { store.projectionFlow(ServerId("server-1")).first() }
        assertFalse(rebound.hasValue)

        store.submitFact(
            ActivityUnreadSummaryFact(
                accountId = "account-1",
                serverId = "server-1",
                totalUnreadCount = 99,
                totalCount = 99,
                authoritative = true,
                source = "late_old_account"
            )
        )
        val current = store.currentProjection(ServerId("server-1"))
        assertFalse(current.hasValue)
    }

    @Test
    fun ensureSkipsFreshProjectionButManualRefreshDoesNot() = runBlocking {
        val ownerFlow = MutableStateFlow<ProcessActivityUnreadOwner?>(owner())
        var fetches = 0
        val store =
            store(ownerFlow) { _, _ ->
                fetches += 1
                DataResult.Content(ActivityUnreadSummarySnapshot(fetches, fetches))
            }

        store.ensure(ServerId("server-1"), reason = "attach")
        awaitFresh(store) { it.totalUnreadCount == 1 }
        store.ensure(ServerId("server-1"), reason = "second_attach")
        kotlinx.coroutines.yield()
        assertEquals(1, fetches)

        store.refresh(ServerId("server-1"), reason = "manual")
        awaitFresh(store) { it.totalUnreadCount == 2 }
        assertEquals(2, fetches)
    }

    @Test
    fun processRestoreUsesPersistedProjectionOnlyAsStaleSeedThenReconciles() = runBlocking {
        val ownerFlow = MutableStateFlow<ProcessActivityUnreadOwner?>(owner())
        val keyValueStore = InMemoryRaftKeyValueStore()
        val cache = ProcessActivityUnreadProjectionCache(keyValueStore, nowMillis = { 123L })
        cache.write(owner(), totalUnreadCount = 6, totalCount = 8)
        var fetches = 0
        val store =
            store(ownerFlow, projectionCache = cache) { _, _ ->
                fetches += 1
                DataResult.Content(ActivityUnreadSummarySnapshot(totalUnreadCount = 2, totalCount = 4))
            }

        val restored = store.currentProjection(ServerId("server-1"))
        assertTrue(restored.hasValue)
        assertEquals(ActivityUnreadSummaryFreshness.Stale, restored.freshness)
        assertEquals(6, restored.totalUnreadCount)

        store.ensure(ServerId("server-1"), reason = "process_restore")
        val fresh = awaitFresh(store) { it.totalUnreadCount == 2 }
        assertEquals(1, fetches)
        assertEquals(4, fresh.totalCount)
        assertEquals(2, cache.read(owner().environmentId, owner().accountId, owner().serverId)?.totalUnreadCount)
    }

    @Test
    fun invalidatingAuthGenerationClearsPersistedProjection() = runBlocking {
        val ownerFlow = MutableStateFlow<ProcessActivityUnreadOwner?>(owner())
        val keyValueStore = InMemoryRaftKeyValueStore()
        val cache = ProcessActivityUnreadProjectionCache(keyValueStore, nowMillis = { 123L })
        cache.write(owner(), totalUnreadCount = 6, totalCount = 8)
        val store = store(ownerFlow, projectionCache = cache) { _, _ -> DataResult.Empty(EmptyReason.NoInboxItems) }

        store.invalidateAll("logout")

        assertEquals(null, cache.read(owner().environmentId, owner().accountId, owner().serverId))
        assertFalse(store.currentProjection(ServerId("server-1")).hasValue)
    }

    @Test
    fun reconcileAfterSuppressionFactKeepsSuppressedRowsOffBadge() = runBlocking {
        val ownerFlow = MutableStateFlow<ProcessActivityUnreadOwner?>(owner())
        val secondStarted = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        var fetches = 0
        val store =
            store(ownerFlow) { _, _ ->
                fetches += 1
                if (fetches == 2) {
                    // Gate the refresh so the suppression fact is guaranteed to
                    // land while this attempt is in flight.
                    secondStarted.complete(Unit)
                    releaseSecond.await()
                }
                DataResult.Content(ActivityUnreadSummarySnapshot(totalUnreadCount = 14, totalCount = 14))
            }

        store.onForeground(ServerId("server-1"), epoch = 1L, reason = "foreground")
        awaitFresh(store) { it.totalUnreadCount == 14 }

        store.refresh(ServerId("server-1"), reason = "growth_check")
        secondStarted.await()
        store.submitFact(
            ActivityUnreadSummaryFact(
                accountId = "account-1",
                serverId = "server-1",
                totalUnreadCount = 11,
                totalCount = 11,
                authoritative = false,
                source = "realtime_patch"
            )
        )
        releaseSecond.complete(Unit)

        // Fetch 2 snapshotted the pre-fact revision, so its result resolves as
        // stale_fact and is discarded; the loop auto-queues a fact_reconcile
        // attempt (fetch 3) which sees raw server aggregate (14) and deducts the
        // suppressed delta (14 - 11 = 3) instead of bouncing the badge to 14.
        val projection = awaitFresh(store) { it.totalUnreadCount == 11 }
        assertEquals(3, fetches)
        assertEquals(11, projection.totalUnreadCount)
        assertEquals(11, projection.totalCount)
    }

    @Test
    fun serverGrowthStillPassesThroughAfterSuppressionBaseline() = runBlocking {
        val ownerFlow = MutableStateFlow<ProcessActivityUnreadOwner?>(owner())
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var fetches = 0
        val store =
            store(ownerFlow) { _, _ ->
                fetches += 1
                if (fetches == 1) {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                    DataResult.Content(ActivityUnreadSummarySnapshot(totalUnreadCount = 14, totalCount = 14))
                } else {
                    DataResult.Content(ActivityUnreadSummarySnapshot(totalUnreadCount = 15, totalCount = 15))
                }
            }

        store.onForeground(ServerId("server-1"), epoch = 1L, reason = "foreground")
        firstStarted.await()
        releaseFirst.complete(Unit)
        awaitFresh(store) { it.totalUnreadCount == 14 }

        store.submitFact(
            ActivityUnreadSummaryFact(
                accountId = "account-1",
                serverId = "server-1",
                totalUnreadCount = 11,
                totalCount = 11,
                authoritative = false,
                source = "realtime_patch"
            )
        )
        store.refresh(ServerId("server-1"), reason = "growth_check")

        // Suppressed delta stays 3 (14 - 11); genuine server growth passes through.
        val projection = awaitFresh(store) { it.totalUnreadCount == 12 }
        assertEquals(2, fetches)
        assertEquals(12, projection.totalUnreadCount)
        assertEquals(12, projection.totalCount)
    }

    private fun store(
        ownerFlow: MutableStateFlow<ProcessActivityUnreadOwner?>,
        projectionCache: ProcessActivityUnreadProjectionCache? = null,
        fetch: suspend (ProcessActivityUnreadOwner, DataRequestPriority) -> DataResult<ActivityUnreadSummarySnapshot>
    ): ProcessActivityUnreadStore =
        ProcessActivityUnreadStore(
            fetchSummary = fetch,
            ownerResolver = { serverId -> ownerFlow.value?.takeIf { it.serverId == serverId } },
            ownerFlowResolver = { serverId ->
                ownerFlow.map { owner -> owner?.takeIf { it.serverId == serverId } }
            },
            projectionCache = projectionCache
        )

    private suspend fun awaitFresh(
        store: ProcessActivityUnreadStore,
        predicate: (ActivityUnreadSummaryProjection) -> Boolean = { true }
    ): ActivityUnreadSummaryProjection =
        withTimeout(2_000) {
            store.projectionFlow(ServerId("server-1"))
                .first { projection -> projection.freshness == ActivityUnreadSummaryFreshness.Fresh && predicate(projection) }
        }

    private fun owner(
        accountId: String = "account-1",
        serverId: String = "server-1"
    ): ProcessActivityUnreadOwner =
        ProcessActivityUnreadOwner(
            environmentId = "https://api.example.test",
            accountId = accountId,
            serverId = serverId
        )
}
