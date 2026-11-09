package build.raft.app.data

import build.raft.app.model.ServerId
import build.raft.app.model.DirectMessageCreated
import build.raft.app.model.MessageNew
import build.raft.app.model.MessageUpdated
import build.raft.app.model.PerReceiverReadStateChanged
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.RealtimeRefreshScope
import build.raft.app.model.SyncResumeResponse
import build.raft.app.model.TaskCreated
import build.raft.app.model.TaskDeleted
import build.raft.app.model.TaskUpdated
import build.raft.app.model.ThreadUpdated
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class ActivityUnreadSummarySnapshot(
    val totalUnreadCount: Int,
    val totalCount: Int,
    /** Task #164: the server answered from its remembered inbox body (`X-Raft-Stale`). */
    val staleReason: RaftStaleReason? = null
)

internal data class ProcessActivityUnreadOwner(
    val environmentId: String,
    val accountId: String,
    val serverId: String
) {
    val diagnosticsKey: String
        get() = "environment=$environmentId account=$accountId server=$serverId"
}

internal data class ProcessActivityUnreadAdmission(
    val serverId: String,
    val principalId: String,
    val authGeneration: Long
)

internal enum class ActivityUnreadSummaryFreshness {
    Empty,
    Stale,
    Refreshing,
    Fresh,
    Error
}

internal data class ActivityUnreadSummaryProjection(
    val serverId: String,
    val totalUnreadCount: Int = 0,
    val totalCount: Int = 0,
    val hasValue: Boolean = false,
    val freshness: ActivityUnreadSummaryFreshness = ActivityUnreadSummaryFreshness.Empty,
    val revision: Long = 0L,
    val requestGeneration: Long = 0L,
    val lastForegroundEpoch: Long = 0L,
    val errorCode: String? = null,
    val source: String = "empty"
)

internal data class ActivityUnreadSummaryFact(
    val accountId: String,
    val serverId: String,
    val totalUnreadCount: Int,
    val totalCount: Int,
    val authoritative: Boolean,
    val source: String
)

internal data class ActivityUnreadSummaryInvalidation(
    val accountId: String,
    val serverId: String,
    val source: String,
    /** The realtime event behind it, when one is ([InboxRefreshTrigger]); null = [InboxRefreshTrigger.Other]. */
    val trigger: InboxRefreshTrigger? = null
)

/**
 * Process-owned Activity unread summary. Activity rows, filters, pagination, and viewport state
 * remain page-scoped; every mounted surface observes this one owner-partitioned summary projection.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ProcessActivityUnreadStore(
    private val fetchSummary: suspend (
        owner: ProcessActivityUnreadOwner,
        priority: DataRequestPriority
    ) -> DataResult<ActivityUnreadSummarySnapshot>,
    private val ownerResolver: (String) -> ProcessActivityUnreadOwner?,
    private val ownerFlowResolver: (String) -> Flow<ProcessActivityUnreadOwner?> = { serverId ->
        flowOf(ownerResolver(serverId))
    },
    private val projectionCache: ProcessActivityUnreadProjectionCache? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
    private val logger: (String) -> Unit = {},
    /**
     * Throttle window (leading + trailing), like the Activity list's realtime reconcile (web
     * `requestInboxBackgroundRefresh`): the first read after a quiet window runs at once; reads
     * requested inside the window collapse into ONE trailing read at its end.
     */
    private val throttleMillis: Long = ActivityRealtimeBackgroundRefreshIntervalMillis,
    /**
     * The window for a read only "ambient" realtime asked for, measured from the last read like
     * [throttleMillis]: new / edited messages, thread and task changes, object rows
     * ([processActivityUnreadRealtimeSink]) and Activity's own realtime patches
     * ([submitInvalidation] with a realtime source). Any other ask in the same round keeps
     * [throttleMillis]: the person's own actions (read, Done, Mark all read, follow), a route attach,
     * a return, a reconnect catch-up (`sync:resume`, refresh-required) and a read or Done on another
     * device. Null keeps [throttleMillis] for every read. The process host passes
     * [ActivityUnreadRealtimeMinIntervalMillis] (feedback fb-52cc0499).
     */
    private val realtimeMinIntervalMillis: Long? = null,
    /** Monotonic clock for the throttle window; injectable for virtual time. */
    private val nowMillis: () -> Long = activityMonotonicClock(),
    /**
     * The process is in the foreground ([RaftStaleReadRecoverySignals.foreground]). No read starts
     * while it is false (the socket keeps running for notifications, so events still arrive); the
     * return does one read, reason `app_foreground_return` (`visibility`).
     */
    private val appForeground: StateFlow<Boolean> = RaftStaleReadRecoverySignals.foreground
) {
    private val mutex = Mutex()
    private val entries = linkedMapOf<ProcessActivityUnreadOwner, Entry>()
    private val _projections = MutableStateFlow<Map<ProcessActivityUnreadOwner, ActivityUnreadSummaryProjection>>(emptyMap())
    internal val projections: StateFlow<Map<ProcessActivityUnreadOwner, ActivityUnreadSummaryProjection>> = _projections
    private var nextRevision = 0L
    private var processGeneration = 0L

    /**
     * Reads once on every background -> foreground return of [appForeground]. Started by the host
     * (not by the constructor): the collector never completes, so a store built on a test scope
     * would otherwise keep that scope from finishing.
     */
    fun followAppLifecycle(): Job =
        scope.launch {
            var previous = appForeground.value
            appForeground.collect { foreground ->
                val returned = foreground && !previous
                previous = foreground
                if (returned) onReturnedToForeground()
            }
        }

    /** One read per known owner for the return; a resume read already started by the shell folds into it. */
    private suspend fun onReturnedToForeground() {
        val owners = mutex.withLock { entries.keys.toList() }
        owners.forEach { owner ->
            request(
                serverId = ServerId(owner.serverId),
                foregroundEpoch = null,
                force = true,
                reason = "app_foreground_return",
                expectedOwner = owner
            )
        }
    }

    fun projectionFlow(serverId: ServerId): Flow<ActivityUnreadSummaryProjection> {
        val normalizedServerId = serverId.value.trim()
        if (normalizedServerId.isBlank()) return flowOf(ActivityUnreadSummaryProjection(serverId = ""))
        return ownerFlowResolver(normalizedServerId)
            .flatMapLatest { owner ->
                if (owner == null) {
                    flowOf(ActivityUnreadSummaryProjection(serverId = normalizedServerId))
                } else {
                    projections.map { current ->
                        current[owner] ?: initialProjection(owner)
                    }
                }
            }
            .distinctUntilChanged()
    }

    fun currentProjection(serverId: ServerId): ActivityUnreadSummaryProjection {
        val owner = ownerResolver(serverId.value) ?: return ActivityUnreadSummaryProjection(serverId = serverId.value.trim())
        return _projections.value[owner] ?: initialProjection(owner)
    }

    fun ensure(serverId: ServerId, reason: String = "ensure") {
        val normalizedServerId = serverId.value.trim()
        if (normalizedServerId.isBlank()) return
        val currentOwner = ownerResolver(normalizedServerId)
        if (currentOwner != null) {
            request(serverId, foregroundEpoch = null, force = false, reason = reason, expectedOwner = currentOwner)
            return
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val owner = ownerFlowResolver(normalizedServerId).filterNotNull().first()
            request(
                serverId = ServerId(normalizedServerId),
                foregroundEpoch = null,
                force = false,
                reason = "$reason:owner_ready",
                expectedOwner = owner
            )
        }
    }

    fun refresh(serverId: ServerId, reason: String = "manual") {
        request(serverId, foregroundEpoch = null, force = true, reason = reason, expectedOwner = null)
    }

    fun invalidate(serverId: ServerId, reason: String) {
        refresh(serverId, reason = "invalidate:$reason")
    }

    /**
     * [eventMarker] identifies the realtime event behind this invalidation. More than one realtime
     * subscriber delivers the same frame (on iOS both the process ingress coordinator and the
     * AppShell listener feed the process domain owner), and hint events such as
     * [RealtimeRefreshRequired] carry no dedup key upstream. A marker already admitted for the
     * pending or in-flight fetch round is coalesced; markers retire when that round's fetch
     * completes, so a later identical hint still fetches again.
     */
    fun invalidate(
        owner: ProcessActivityUnreadOwner,
        reason: String,
        eventMarker: Any? = null,
        /** Only ambient realtime asked (see [realtimeMinIntervalMillis]); false reads in the short window. */
        realtimeOnly: Boolean = false,
        /** What the `X-Raft-Fetch-Trigger` of a realtime-fallback read names (null = [InboxRefreshTrigger.Other]). */
        trigger: InboxRefreshTrigger? = null
    ) {
        request(
            serverId = ServerId(owner.serverId),
            foregroundEpoch = null,
            force = true,
            reason = "invalidate:$reason",
            expectedOwner = owner,
            eventMarker = eventMarker,
            realtimeOnly = realtimeOnly,
            trigger = trigger
        )
    }

    fun onForeground(serverId: ServerId, epoch: Long, reason: String) {
        if (epoch <= 0L) {
            refresh(serverId, reason = "$reason:missing_epoch")
            return
        }
        val normalizedServerId = serverId.value.trim()
        if (normalizedServerId.isBlank()) return
        val currentOwner = ownerResolver(normalizedServerId)
        if (currentOwner != null) {
            request(serverId, foregroundEpoch = epoch, force = true, reason = reason, expectedOwner = currentOwner)
            return
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val owner = ownerFlowResolver(normalizedServerId).filterNotNull().first()
            request(
                serverId = ServerId(normalizedServerId),
                foregroundEpoch = epoch,
                force = true,
                reason = "$reason:owner_ready",
                expectedOwner = owner
            )
        }
    }

    fun submitFact(fact: ActivityUnreadSummaryFact) {
        val normalizedServerId = fact.serverId.trim()
        val normalizedAccountId = fact.accountId.trim()
        if (normalizedServerId.isBlank() || normalizedAccountId.isBlank()) return
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            mutex.withLock {
                val owner = ownerResolver(normalizedServerId)
                if (owner == null || owner.accountId != normalizedAccountId) {
                    logger(
                        "process_activity_unread_fact result=drop reason=owner_mismatch " +
                            "serverId=$normalizedServerId source=${fact.source}"
                    )
                    return@withLock
                }
                val entry = entries.getOrPut(owner) { Entry(projection = initialProjection(owner)) }
                entry.factRevision += 1L
                entry.canonicalDirty = !fact.authoritative
                if (fact.authoritative) {
                    entry.latestFactUnread = null
                    entry.latestFactTotal = null
                    entry.lastServerUnread = null
                    entry.lastServerTotal = null
                } else {
                    entry.latestFactUnread = fact.totalUnreadCount.coerceAtLeast(0)
                    entry.latestFactTotal = fact.totalCount.coerceAtLeast(0)
                }
                entry.projection =
                    entry.projection.copy(
                        totalUnreadCount = fact.totalUnreadCount.coerceAtLeast(0),
                        totalCount = fact.totalCount.coerceAtLeast(0),
                        hasValue = true,
                        freshness =
                            if (fact.authoritative) {
                                ActivityUnreadSummaryFreshness.Fresh
                            } else {
                                ActivityUnreadSummaryFreshness.Stale
                            },
                        revision = nextProjectionRevision(),
                        errorCode = null,
                        source = fact.source
                    )
                publish(owner, entry)
                if (fact.authoritative) {
                    persist(owner, entry.projection)
                }
                logger(
                    "process_activity_unread_fact result=apply ${owner.diagnosticsKey} " +
                        "unread=${entry.projection.totalUnreadCount} total=${entry.projection.totalCount} " +
                        "authoritative=${fact.authoritative} active=${entry.active} source=${fact.source}"
                )
            }
        }
    }

    fun submitInvalidation(invalidation: ActivityUnreadSummaryInvalidation) {
        val normalizedServerId = invalidation.serverId.trim()
        val normalizedAccountId = invalidation.accountId.trim()
        if (normalizedServerId.isBlank() || normalizedAccountId.isBlank()) return
        val owner = ownerResolver(normalizedServerId)
        if (owner == null || owner.accountId != normalizedAccountId) {
            logger(
                "process_activity_unread_invalidation result=drop reason=owner_mismatch " +
                    "serverId=$normalizedServerId source=${invalidation.source}"
            )
            return
        }
        invalidate(
            owner,
            reason = "activity:${invalidation.source}",
            realtimeOnly = invalidation.source.isRealtimeOnlyActivitySummarySource(),
            trigger = invalidation.trigger
        )
    }

    suspend fun invalidateAll(reason: String) {
        mutex.withLock {
            processGeneration += 1L
            entries.clear()
            _projections.value = emptyMap()
            projectionCache?.clearAll()
            logger("process_activity_unread_invalidate scope=all generation=$processGeneration reason=$reason")
        }
    }

    private fun request(
        serverId: ServerId,
        foregroundEpoch: Long?,
        force: Boolean,
        reason: String,
        expectedOwner: ProcessActivityUnreadOwner?,
        eventMarker: Any? = null,
        realtimeOnly: Boolean = false,
        trigger: InboxRefreshTrigger? = null
    ) {
        val normalizedServerId = serverId.value.trim()
        if (normalizedServerId.isBlank()) return
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var startLoop = false
            var ownerForLoop: ProcessActivityUnreadOwner? = null
            mutex.withLock {
                val owner = ownerResolver(normalizedServerId)
                if (owner == null || (expectedOwner != null && owner != expectedOwner)) {
                    logger(
                        "process_activity_unread_request result=drop reason=owner_missing_or_changed " +
                            "serverId=$normalizedServerId source=$reason"
                    )
                    return@withLock
                }
                val entry = entries.getOrPut(owner) { Entry(projection = initialProjection(owner)) }
                if (!appForeground.value) {
                    // Nothing reads in the background; the return reads once (onReturnedToForeground).
                    logger(
                        "process_activity_unread_request result=deferred reason=app_background " +
                            "${owner.diagnosticsKey} source=$reason"
                    )
                    return@withLock
                }
                if (activityUnreadSummaryFetchReason(reason) == InboxFetchReason.Visibility) {
                    // The shell's resume read and the store's own return read are one read.
                    val now = nowMillis()
                    val lastVisibility = entry.lastVisibilityRequestAtMillis
                    if (lastVisibility != null && now - lastVisibility < throttleMillis) {
                        logger(
                            "process_activity_unread_request result=coalesced reason=visibility_window " +
                                "${owner.diagnosticsKey} source=$reason"
                        )
                        return@withLock
                    }
                    entry.lastVisibilityRequestAtMillis = now
                }
                if (eventMarker != null) {
                    if (eventMarker in entry.pendingEventMarkers || eventMarker in entry.inFlightEventMarkers) {
                        logger(
                            "process_activity_unread_request result=coalesced reason=same_event_round " +
                                "${owner.diagnosticsKey} source=$reason"
                        )
                        return@withLock
                    }
                    entry.pendingEventMarkers += eventMarker
                }
                if (foregroundEpoch != null) {
                    if (foregroundEpoch <= entry.acceptedForegroundEpoch) {
                        logger(
                            "process_activity_unread_request result=coalesced ${owner.diagnosticsKey} " +
                                "epoch=$foregroundEpoch acceptedEpoch=${entry.acceptedForegroundEpoch} source=$reason"
                        )
                        return@withLock
                    }
                    entry.acceptedForegroundEpoch = foregroundEpoch
                } else if (!force && entry.projection.freshness == ActivityUnreadSummaryFreshness.Fresh) {
                    logger(
                        "process_activity_unread_request result=fresh_skip ${owner.diagnosticsKey} source=$reason"
                    )
                    return@withLock
                }
                if (entry.active && foregroundEpoch == null && !force) {
                    logger(
                        "process_activity_unread_request result=coalesced reason=active_ensure " +
                            "${owner.diagnosticsKey} source=$reason"
                    )
                    return@withLock
                }
                entry.pendingReason = reason
                if (!realtimeOnly) entry.pendingUrgent = true
                // Observability only (#13126): every realtime-fallback ask folded into the round counts;
                // the first one names it. A duplicate delivery dropped above is the same ask.
                if (activityUnreadSummaryFetchReason(reason) == InboxFetchReason.RealtimeFallback) {
                    entry.pendingTrigger = entry.pendingTrigger.foldWith(trigger ?: InboxRefreshTrigger.Other)
                }
                if (entry.active) {
                    entry.pending = true
                    entry.projection =
                        entry.projection.copy(
                            freshness = ActivityUnreadSummaryFreshness.Refreshing,
                            revision = nextProjectionRevision(),
                            lastForegroundEpoch = maxOf(entry.projection.lastForegroundEpoch, foregroundEpoch ?: 0L),
                            source = reason
                        )
                    publish(owner, entry)
                    logger(
                        "process_activity_unread_request result=pending ${owner.diagnosticsKey} " +
                            "epoch=${foregroundEpoch ?: 0L} source=$reason"
                    )
                    return@withLock
                }
                entry.active = true
                entry.pending = false
                entry.projection =
                    entry.projection.copy(
                        freshness = ActivityUnreadSummaryFreshness.Refreshing,
                        revision = nextProjectionRevision(),
                        lastForegroundEpoch = maxOf(entry.projection.lastForegroundEpoch, foregroundEpoch ?: 0L),
                        errorCode = null,
                        source = reason
                    )
                publish(owner, entry)
                ownerForLoop = owner
                startLoop = true
                logger(
                    "process_activity_unread_request result=start ${owner.diagnosticsKey} " +
                        "epoch=${foregroundEpoch ?: 0L} source=$reason"
                )
            }
            if (startLoop) {
                runRefreshLoop(ownerForLoop ?: return@launch)
            }
        }
    }

    private suspend fun runRefreshLoop(owner: ProcessActivityUnreadOwner) {
        while (true) {
            // Throttle: a read starts at once after a quiet window; inside it the loop waits for the
            // window's end, and every request made meanwhile folds into the one pending read. A round
            // only realtime asked for waits the longer realtime window; the wait is taken in steps of
            // the short one, so a person's ask arriving meanwhile is not held behind it.
            while (true) {
                val waitMillis =
                    mutex.withLock {
                        val entry = entries[owner]
                        val window =
                            if (entry?.pendingUrgent == false && realtimeMinIntervalMillis != null) {
                                maxOf(realtimeMinIntervalMillis, throttleMillis)
                            } else {
                                throttleMillis
                            }
                        entry?.lastAttemptStartedAtMillis
                            ?.let { startedAt -> window - (nowMillis() - startedAt) }
                            ?.coerceAtLeast(0L)
                            ?: 0L
                    }
                if (waitMillis <= 0L) break
                delay(minOf(waitMillis, throttleMillis.coerceAtLeast(1L)))
            }
            val attempt =
                mutex.withLock {
                    val entry = entries[owner] ?: return
                    if (!appForeground.value) {
                        // Went to the background while a read was queued: stop; the return reads once.
                        entry.active = false
                        entry.pending = false
                        entry.pendingUrgent = false
                        entry.pendingEventMarkers = emptySet()
                        entry.pendingTrigger = null
                        entry.projection =
                            entry.projection.copy(
                                freshness = ActivityUnreadSummaryFreshness.Stale,
                                revision = nextProjectionRevision()
                            )
                        publish(owner, entry)
                        logger(
                            "process_activity_unread_load result=deferred reason=app_background " +
                                "${owner.diagnosticsKey} source=${entry.pendingReason}"
                        )
                        return
                    }
                    entry.lastAttemptStartedAtMillis = nowMillis()
                    entry.pending = false
                    entry.pendingUrgent = false
                    entry.canonicalDirty = false
                    entry.requestGeneration += 1L
                    // This attempt covers every event admitted so far; later ones open the next round.
                    entry.inFlightEventMarkers = entry.pendingEventMarkers
                    entry.pendingEventMarkers = emptySet()
                    val reason = entry.pendingReason
                    // This attempt answers every ask folded so far; later ones count for the next.
                    val trigger = entry.pendingTrigger
                    entry.pendingTrigger = null
                    entry.projection =
                        entry.projection.copy(
                            freshness = ActivityUnreadSummaryFreshness.Refreshing,
                            revision = nextProjectionRevision(),
                            requestGeneration = entry.requestGeneration,
                            errorCode = null,
                            source = reason
                        )
                    publish(owner, entry)
                    Attempt(
                        processGeneration = processGeneration,
                        generation = entry.requestGeneration,
                        factRevision = entry.factRevision,
                        reason = reason,
                        trigger = trigger
                    )
                }
            val result =
                try {
                    withInboxFetchReason(activityUnreadSummaryFetchReason(attempt.reason), attempt.trigger) {
                        fetchSummary(owner, DataRequestPriority.Foreground)
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    logger(
                        "process_activity_unread_load result=exception ${owner.diagnosticsKey} " +
                            "generation=${attempt.generation} type=${error::class.simpleName} source=${attempt.reason}"
                    )
                    DataResult.Error(ScreenError(code = "activity_unread_exception", retryable = true))
                }
            val continueLoop =
                mutex.withLock {
                    val entry = entries[owner] ?: return
                    entry.inFlightEventMarkers = emptySet()
                    val currentOwner = ownerResolver(owner.serverId)
                    if (
                        currentOwner != owner ||
                        processGeneration != attempt.processGeneration ||
                        entry.requestGeneration != attempt.generation
                    ) {
                        logger(
                            "process_activity_unread_load result=stale_owner ${owner.diagnosticsKey} " +
                                "generation=${attempt.generation} source=${attempt.reason}"
                        )
                        entry.active = false
                        // No later attempt of this loop covers them; let a redelivery fetch again.
                        entry.pendingEventMarkers = emptySet()
                        entry.pendingTrigger = null
                        return@withLock false
                    }
                    val factChanged = entry.factRevision != attempt.factRevision
                    // Task #164: a remembered (stale) server body never replaces a count this
                    // process already knows; it only fills a count it does not have yet.
                    val keepKnownOverStale =
                        result is DataResult.Content && result.data.staleReason != null && entry.projection.hasValue
                    if (!factChanged && keepKnownOverStale) {
                        entry.projection =
                            entry.projection.copy(
                                freshness = ActivityUnreadSummaryFreshness.Stale,
                                revision = nextProjectionRevision(),
                                source = attempt.reason
                            )
                        publish(owner, entry)
                    } else if (!factChanged) {
                        if (result is DataResult.Content) {
                            // Raw aggregates include suppressed rows; reuse the latest
                            // non-authoritative fact (suppression-aware) to deduct the
                            // suppressed delta so the badge stays consistent with the tab.
                            val rawUnread = result.data.totalUnreadCount.coerceAtLeast(0)
                            val rawTotal = result.data.totalCount.coerceAtLeast(0)
                            // Delta is only meaningful while a non-authoritative
                            // suppression fact is measured against a prior server
                            // baseline; without one, raw aggregates pass through.
                            val lastServerUnread = entry.lastServerUnread
                            val lastServerTotal = entry.lastServerTotal
                            val suppressedUnread =
                                entry.latestFactUnread
                                    ?.takeIf { lastServerUnread != null }
                                    ?.let { factUnread -> (lastServerUnread!! - factUnread).coerceAtLeast(0) }
                                    ?: 0
                            val suppressedTotal =
                                entry.latestFactTotal
                                    ?.takeIf { lastServerTotal != null }
                                    ?.let { factTotal -> (lastServerTotal!! - factTotal).coerceAtLeast(0) }
                                    ?: 0
                            entry.lastServerUnread = rawUnread
                            entry.lastServerTotal = rawTotal
                            entry.projection =
                                entry.projection.copy(
                                    totalUnreadCount = (rawUnread - suppressedUnread).coerceAtLeast(0),
                                    totalCount = (rawTotal - suppressedTotal).coerceAtLeast(0),
                                    hasValue = true,
                                    freshness =
                                        if (result.data.staleReason != null) {
                                            ActivityUnreadSummaryFreshness.Stale
                                        } else {
                                            ActivityUnreadSummaryFreshness.Fresh
                                        },
                                    revision = nextProjectionRevision(),
                                    errorCode = null,
                                    source = attempt.reason
                                )
                        } else {
                            entry.projection = result.toProjection(entry.projection, nextProjectionRevision(), attempt.reason)
                            if (result is DataResult.Empty) {
                                entry.lastServerUnread = 0
                                entry.lastServerTotal = 0
                            }
                        }
                        publish(owner, entry)
                        if (result is DataResult.Content || result is DataResult.Empty) {
                            persist(owner, entry.projection)
                        }
                    } else {
                        logger(
                            "process_activity_unread_load result=stale_fact ${owner.diagnosticsKey} " +
                                "generation=${attempt.generation} source=${attempt.reason}"
                        )
                    }
                    val shouldContinue = entry.pending || (factChanged && entry.canonicalDirty)
                    if (!shouldContinue) {
                        entry.active = false
                    } else if (factChanged && entry.canonicalDirty && !entry.pending) {
                        entry.pending = true
                        entry.pendingReason = "fact_reconcile"
                        // A local fact landed mid-read: no realtime event names this read.
                        entry.pendingTrigger = entry.pendingTrigger.foldWith(InboxRefreshTrigger.Other)
                        // A local fact (the person's action in Activity) landed mid-read: not ambient.
                        entry.pendingUrgent = true
                    }
                    logger(
                        "process_activity_unread_load result=${result.resultLabel()} ${owner.diagnosticsKey} " +
                            "generation=${attempt.generation} factChanged=$factChanged continue=$shouldContinue " +
                            "source=${attempt.reason}"
                    )
                    shouldContinue
                }
            if (!continueLoop) return
        }
    }

    private fun DataResult<ActivityUnreadSummarySnapshot>.toProjection(
        previous: ActivityUnreadSummaryProjection,
        revision: Long,
        source: String
    ): ActivityUnreadSummaryProjection =
        when (this) {
            is DataResult.Content ->
                previous.copy(
                    totalUnreadCount = data.totalUnreadCount.coerceAtLeast(0),
                    totalCount = data.totalCount.coerceAtLeast(0),
                    hasValue = true,
                    freshness = ActivityUnreadSummaryFreshness.Fresh,
                    revision = revision,
                    errorCode = null,
                    source = source
                )
            is DataResult.Empty ->
                previous.copy(
                    totalUnreadCount = 0,
                    totalCount = 0,
                    hasValue = true,
                    freshness = ActivityUnreadSummaryFreshness.Fresh,
                    revision = revision,
                    errorCode = null,
                    source = source
                )
            is DataResult.Error ->
                previous.copy(
                    freshness = ActivityUnreadSummaryFreshness.Error,
                    revision = revision,
                    errorCode = error.code,
                    source = source
                )
            is DataResult.NoPermission ->
                previous.copy(
                    freshness = ActivityUnreadSummaryFreshness.Error,
                    revision = revision,
                    errorCode = blocker.code,
                    source = source
                )
        }

    private fun emptyProjection(serverId: String): ActivityUnreadSummaryProjection =
        ActivityUnreadSummaryProjection(serverId = serverId)

    private fun initialProjection(owner: ProcessActivityUnreadOwner): ActivityUnreadSummaryProjection =
        projectionCache
            ?.read(owner)
            ?.let { persisted ->
                ActivityUnreadSummaryProjection(
                    serverId = owner.serverId,
                    totalUnreadCount = persisted.totalUnreadCount.coerceAtLeast(0),
                    totalCount = persisted.totalCount.coerceAtLeast(0),
                    hasValue = true,
                    freshness = ActivityUnreadSummaryFreshness.Stale,
                    source = "persisted"
                )
            }
            ?: emptyProjection(owner.serverId)

    private fun persist(owner: ProcessActivityUnreadOwner, projection: ActivityUnreadSummaryProjection) {
        runCatching {
            projectionCache?.write(
                owner = owner,
                totalUnreadCount = projection.totalUnreadCount,
                totalCount = projection.totalCount
            )
        }.onFailure { error ->
            logger(
                "process_activity_unread_persist result=failure ${owner.diagnosticsKey} " +
                    "errorType=${error::class.simpleName.orEmpty()}"
            )
        }
    }

    private fun nextProjectionRevision(): Long {
        nextRevision += 1L
        return nextRevision
    }

    private fun publish(owner: ProcessActivityUnreadOwner, entry: Entry) {
        _projections.value = _projections.value + (owner to entry.projection)
    }

    private data class Attempt(
        val processGeneration: Long,
        val generation: Long,
        val factRevision: Long,
        val reason: String,
        val trigger: InboxRefreshTriggerCount?
    )

    private class Entry(
        var projection: ActivityUnreadSummaryProjection,
        var active: Boolean = false,
        var pending: Boolean = false,
        var pendingReason: String = "unspecified",
        // The realtime-fallback asks folded into the pending round: first trigger, count (#13126).
        var pendingTrigger: InboxRefreshTriggerCount? = null,
        // The round holds an ask other than realtime's: it keeps the short throttle window.
        var pendingUrgent: Boolean = false,
        var acceptedForegroundEpoch: Long = 0L,
        var lastAttemptStartedAtMillis: Long? = null,
        var lastVisibilityRequestAtMillis: Long? = null,
        var requestGeneration: Long = 0L,
        var factRevision: Long = 0L,
        var canonicalDirty: Boolean = false,
        // Realtime event markers admitted for the next fetch / covered by the running fetch.
        var pendingEventMarkers: Set<Any> = emptySet(),
        var inFlightEventMarkers: Set<Any> = emptySet(),
        // Latest non-authoritative fact counts (suppression-aware local view).
        var latestFactUnread: Int? = null,
        var latestFactTotal: Int? = null,
        // Raw aggregate counts from the last Content/Empty fetch (include suppressed rows).
        var lastServerUnread: Int? = null,
        var lastServerTotal: Int? = null
    )
}

/**
 * Activity's summary invalidation sources (`activity:<intent>`, [SharedActivityStore]) that only
 * ambient realtime causes. Everything else Activity reports (Done, Mark all read, read, unread,
 * follow, undo, a remote Done) is the person's action, here or on another device.
 */
private val RealtimeOnlyActivitySummarySources = setOf("realtime", "realtime_summary")

internal fun String.isRealtimeOnlyActivitySummarySource(): Boolean =
    removePrefix("activity:") in RealtimeOnlyActivitySummarySources

/**
 * The realtime events the badge may read for in the long window: ambient activity. A reconnect
 * catch-up (`sync:resume`, refresh-required) and a read on another device are read promptly.
 */
internal fun RealtimeEvent.isAmbientActivityUnreadEvent(): Boolean =
    when (this) {
        is SyncResumeResponse,
        is RealtimeRefreshRequired,
        is PerReceiverReadStateChanged -> false
        else -> true
    }

private fun DataResult<ActivityUnreadSummarySnapshot>.resultLabel(): String =
    when (this) {
        is DataResult.Content -> "content"
        is DataResult.Empty -> "empty"
        is DataResult.Error -> "error:${error.code}"
        is DataResult.NoPermission -> "blocked:${blocker.code}"
    }

internal fun processActivityUnreadRealtimeSink(
    currentAuthContext: () -> ProcessPresenceAuthContext?,
    store: ProcessActivityUnreadStore,
    diagnosticsLogger: (String) -> Unit = {}
): (ProcessActivityUnreadAdmission, RealtimeEvent) -> Unit = { admission, event ->
    if (!event.affectsActivityUnreadSummary()) {
        Unit
    } else {
        val context = currentAuthContext()
        val owner = context?.activityUnreadOwnerFor(admission.serverId)
        if (
            context == null ||
            owner == null ||
            context.accountId != admission.principalId ||
            context.authorityGeneration != admission.authGeneration
        ) {
            diagnosticsLogger(
                "process_activity_unread_realtime result=drop reason=admission_mismatch " +
                    "serverId=${admission.serverId} type=${event::class.simpleName.orEmpty()}"
            )
        } else {
            // The event value is the marker: every subscriber decodes the same frame to an equal event.
            store.invalidate(
                owner,
                reason = "realtime:${event::class.simpleName.orEmpty()}",
                eventMarker = event,
                realtimeOnly = event.isAmbientActivityUnreadEvent(),
                trigger = event.inboxRefreshTrigger()
            )
        }
    }
}

internal fun ProcessPresenceAuthContext.activityUnreadOwnerFor(rawServerId: String): ProcessActivityUnreadOwner? {
    val environment = environmentId.trim().takeIf { it.isNotBlank() } ?: return null
    val account = accountId.trim().takeIf { it.isNotBlank() } ?: return null
    val serverId = rawServerId.trim().takeIf { it.isNotBlank() } ?: return null
    return ProcessActivityUnreadOwner(environment, account, serverId)
}

internal fun RealtimePresenceAdmission.toActivityUnreadAdmission(): ProcessActivityUnreadAdmission =
    ProcessActivityUnreadAdmission(
        serverId = serverId,
        principalId = principalId,
        authGeneration = authGeneration
    )

internal fun RealtimeEvent.affectsActivityContent(): Boolean =
    when (this) {
        is MessageNew,
        is MessageUpdated,
        is DirectMessageCreated,
        is ThreadUpdated,
        is TaskCreated,
        is TaskUpdated,
        is TaskDeleted,
        // Workspace Artifacts: an object row changed (activityObjectsContract.ts); refetch.
        is build.raft.app.model.ActivityChanged,
        is SyncResumeResponse -> true
        is RealtimeRefreshRequired ->
            scope == RealtimeRefreshScope.Home ||
                scope == RealtimeRefreshScope.ChannelMembers ||
                scope == RealtimeRefreshScope.ServerMembers ||
                scope == RealtimeRefreshScope.Agents ||
                scope == RealtimeRefreshScope.Machines ||
                scope == RealtimeRefreshScope.Settings
        else -> false
    }

internal fun RealtimeEvent.affectsActivityUnreadSummary(): Boolean =
    affectsActivityContent() || this is PerReceiverReadStateChanged
