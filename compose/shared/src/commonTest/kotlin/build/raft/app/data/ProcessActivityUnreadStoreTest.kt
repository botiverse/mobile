package build.raft.app.data

import build.raft.app.model.ActivityDoneRowKind
import build.raft.app.model.ActivityDoneUpdated
import build.raft.app.model.ServerId
import build.raft.app.model.ChannelId
import build.raft.app.model.DirectMessageCreated
import build.raft.app.model.MessageId
import build.raft.app.model.NotificationLevelChanged
import build.raft.app.model.NotificationRetracted
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
    fun doneRetractAndNotificationLevelEventsAffectSummaryAndTriggerPromptRefresh() = runBlocking {
        val events =
            listOf(
                ActivityDoneUpdated(
                    serverId = ServerId("server-1"),
                    rowKind = ActivityDoneRowKind.Channel,
                    rowId = "channel-1",
                    done = true,
                    position = "42",
                ),
                NotificationRetracted(
                    serverId = ServerId("server-1"),
                    groupKey = "server-1:channel-1",
                    conversationId = "channel-1",
                    throughSeq = 42L,
                ),
                NotificationLevelChanged(
                    serverId = ServerId("server-1"),
                    scope = "channel",
                    scopeId = "channel-1",
                    level = "none",
                    prefsVersion = 3L,
                ),
            )
        events.forEach { event ->
            assertTrue(event.affectsActivityUnreadSummary(), "${event::class.simpleName} must affect activity unread summary")
            assertFalse(event.isAmbientActivityUnreadEvent(), "${event::class.simpleName} must not be throttled as ambient")
        }

        val ownerFlow = MutableStateFlow<ProcessActivityUnreadOwner?>(owner())
        var fetches = 0
        val store =
            store(ownerFlow) { _, _ ->
                fetches += 1
                DataResult.Content(ActivityUnreadSummarySnapshot(totalUnreadCount = 0, totalCount = 0))
            }
        val context =
            ProcessPresenceAuthContext(
                environmentId = "https://api.example.test",
                accountId = "account-1",
                authorityGeneration = 2L,
                writeGeneration = 2L,
            )
        val sink = processActivityUnreadRealtimeSink(currentAuthContext = { context }, store = store)
        val admission = ProcessActivityUnreadAdmission("server-1", "account-1", authGeneration = 2L)

        events.forEachIndexed { index, event ->
            sink(admission, event)
            awaitFresh(store) { it.totalUnreadCount == 0 && it.requestGeneration == (index + 1).toLong() }
        }
        assertEquals(3, fetches)
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
            projectionCache = projectionCache,
            // These tests run on real time and count reads; the throttle has its own suite.
            throttleMillis = 0L
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
