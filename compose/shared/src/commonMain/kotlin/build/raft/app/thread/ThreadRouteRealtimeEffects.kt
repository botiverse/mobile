package build.raft.app.thread

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.app.RaftAdaptiveDetailCloseRequest
import build.raft.app.base.BridgeModule
import build.raft.app.compat.pager.Pager
import build.raft.app.data.RaftAuthSessionStore
import build.raft.app.data.RaftHostNetworkEvents
import build.raft.app.data.RaftRealtimeEvents
import build.raft.app.data.RaftSharedStoreProvider
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.RealtimeCanonicalFinalSinkLifecycleEffects
import build.raft.app.data.RealtimeCanonicalFinalSinkSurfaceKey
import build.raft.app.data.RealtimeGuardedActiveThreadSink
import build.raft.app.data.RealtimeRoomLeaseOwner
import build.raft.app.data.SharedRealtimeDomainEventOwner
import build.raft.app.data.SharedRealtimeRoomLeaseRegistry
import build.raft.app.data.StoredAuthSession
import build.raft.app.model.RealtimeEvent
import build.raft.app.shouldHoldThreadRoomLease
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The page's realtime room lease: joins the conversation's rooms while the page holds the lease and
 * releases them when it stops (the reply thread's own room stays joined). Plain values only: the
 * effect reads them when it starts, as before.
 */
@Composable
@NonRestartableComposable
internal fun ThreadRouteRoomLeaseEffect(
    realtimeEvents: RaftRealtimeEvents,
    realtimeRoomLeaseRegistry: SharedRealtimeRoomLeaseRegistry,
    storeScope: RaftShellStoreScope,
    pager: Pager,
    adaptiveCloseRequest: RaftAdaptiveDetailCloseRequest?,
    currentSession: StoredAuthSession?,
    payload: RaftThreadScreenPayload,
    active: Boolean,
) {
    val roomLeaseActive = shouldHoldThreadRoomLease(adaptiveCloseRequest, active)
    DisposableEffect(
        realtimeEvents,
        realtimeRoomLeaseRegistry,
        storeScope.shellInstanceId,
        pager.pagerId,
        adaptiveCloseRequest?.generation,
        currentSession?.currentServerId,
        payload.parentChannelId,
        payload.sourceThreadId,
        payload.streamChannelId,
        roomLeaseActive,
    ) {
        val activeRoomIds = payload.activeRealtimeRoomIds()
        val roomServerId = currentSession?.currentServerId.orEmpty()
        val owner =
            roomServerId.takeIf(String::isNotBlank)?.let { serverId ->
                RealtimeRoomLeaseOwner(
                    shellInstanceId = storeScope.shellInstanceId,
                    serverId = serverId,
                    consumerId = "thread-pager-${pager.pagerId}",
                    generation = adaptiveCloseRequest?.generation ?: 1L,
                )
            }
        if (roomLeaseActive && owner != null) {
            // Task #14c: the reply thread's own room stays joined after the page closes (the parent's does not).
            payload.replyThreadRealtimeRoomIds().forEach { roomId ->
                realtimeRoomLeaseRegistry.keepJoinedUntilClear(owner.serverId, roomId)
            }
            realtimeEvents.applyRoomTransitions(realtimeRoomLeaseRegistry.acquire(owner, activeRoomIds))
        }
        onDispose {
            if (roomLeaseActive && owner != null) {
                realtimeEvents.applyRoomTransitions(realtimeRoomLeaseRegistry.release(owner))
            }
        }
    }
}

/**
 * The offline stand-in's recovery: the network/realtime-reconnect signals, the deferred retry once a
 * running refresh settles, and the backoff retry while the visible page shows a failed load. Takes
 * the current session as `State`: the listeners read it live.
 */
@Composable
@NonRestartableComposable
internal fun ThreadOfflineStandInRecoveryEffects(
    bridge: BridgeModule,
    threadViewModel: SharedThreadViewModel,
    sharedStores: RaftSharedStoreProvider,
    realtimeEvents: RaftRealtimeEvents,
    coroutineScope: CoroutineScope,
    latestCurrentSessionState: State<StoredAuthSession?>,
    payload: RaftThreadScreenPayload,
    active: Boolean,
) {
    val latestCurrentSession by latestCurrentSessionState
    // An offline stand-in (durable last-known frame whose refresh failed) is only replaced by a network
    // answer; nothing re-asked after the network came back, so it stayed until the channel was re-entered
    // (HuaTuo, alpha 71). The host's authoritative online signal now re-requests it in place. A signal
    // that lands while a refresh is still running is remembered (LiBai #2782): that refresh was issued
    // offline and may fail back to the stand-in, so the retry fires once it settles.
    val offlineStandInRecovery = remember(threadViewModel) { OfflineStandInRecoveryGate() }
    // (the gate and attempt counter are per view model, so a new conversation starts clean)
    fun refreshOfflineStandIn(reason: String, current: RaftThreadScreenPayload) {
        bridge.log(
            "thread_offline_standin_refresh reason=$reason " +
                "threadId=${current.sourceThreadId.orEmpty()} status=${current.status}"
        )
        threadViewModel.dispatch(SharedThreadIntent.Refresh)
    }
    // Two recovery signals share one gate: the realtime transport reconnecting (the server is reachable
    // again — the one that matters; the system can report "online" while the server is not, and a
    // per-app block never changes the system state, HuaTuo alpha 74) and the host's authoritative
    // online signal as a supplement.
    DisposableEffect(threadViewModel, sharedStores.eventBus, realtimeEvents) {
        fun onRecovered(reason: String) {
            val current = threadViewModel.state.value.payload
            val refresh = offlineStandInRecovery.onNetworkRecovered(current)
            bridge.log(
                "thread_offline_recovery_signal reason=$reason action=${offlineStandInRecoveryAction(current)} " +
                    "refresh=$refresh status=${current.status} durable=${current.durableLastKnown} " +
                    "mainLoadFailed=${current.mainLoadFailed} error=${current.errorCode ?: "none"} " +
                    "refreshing=${current.backgroundRefreshing}"
            )
            if (refresh) refreshOfflineStandIn(reason, current)
        }
        val hostNetworkEvents = RaftHostNetworkEvents(sharedStores.eventBus)
        // The bus calls listeners on the posting thread (on iOS the realtime host's queue): move to the
        // page's scope on main, where the gate's other caller (onPayloadSettled) runs.
        val networkSubscription =
            hostNetworkEvents.addAuthoritativeOnlineListener { coroutineScope.launch { onRecovered("network_recovered") } }
        val realtimeSubscription =
            realtimeEvents.addListener { eventData ->
                if (isRealtimeTransportConnectedEvent(eventData, latestCurrentSession?.currentServerId)) {
                    coroutineScope.launch { onRecovered("realtime_connected") }
                }
            }
        onDispose {
            hostNetworkEvents.removeListener(networkSubscription)
            realtimeEvents.removeListener(realtimeSubscription)
        }
    }
    LaunchedEffect(
        offlineStandInRecovery,
        payload.durableLastKnown,
        payload.backgroundRefreshing,
        payload.status,
        payload.errorCode
    ) {
        val current = threadViewModel.state.value.payload
        if (offlineStandInRecovery.onPayloadSettled(current)) refreshOfflineStandIn("network_recovered_deferred", current)
    }
    // Signals are not enough on their own: Android never publishes the realtime reconnect to pages, and a
    // per-app block never changes the system network state (HuaTuo alpha 78 logs). While the visible page
    // shows a failed load, it also re-asks on a backoff; a successful answer stops it.
    var offlineRecoveryRetryAttempt by remember(threadViewModel) { mutableStateOf(0) }
    val offlineRecoveryAction = offlineStandInRecoveryAction(payload)
    // Keyed on the view model and the attempt too: a switch to another failed conversation, or a failure
    // that returns within one frame, must restart the timer (LiBai #2782).
    LaunchedEffect(threadViewModel, offlineRecoveryAction, active, offlineRecoveryRetryAttempt) {
        if (offlineRecoveryAction == OfflineStandInRecoveryAction.None) {
            offlineRecoveryRetryAttempt = 0
            return@LaunchedEffect
        }
        if (offlineRecoveryAction != OfflineStandInRecoveryAction.Refresh || !active) return@LaunchedEffect
        delay(offlineRecoveryRetryDelayMs(offlineRecoveryRetryAttempt))
        val current = threadViewModel.state.value.payload
        if (offlineStandInRecoveryAction(current) == OfflineStandInRecoveryAction.Refresh) {
            offlineRecoveryRetryAttempt += 1
            refreshOfflineStandIn("retry_backoff_$offlineRecoveryRetryAttempt", current)
        }
    }
}

/**
 * The page's canonical realtime sinks: the guarded active-thread sink on the domain event owner and
 * the canonical-final lifecycle sink, both mounted with the route core (also during LoadingShell).
 * Takes the event handler as `State`: the sinks read it live.
 */
@Composable
@NonRestartableComposable
internal fun ThreadRouteRealtimeSinkEffects(
    realtimeDomainEventOwner: SharedRealtimeDomainEventOwner,
    authStore: RaftAuthSessionStore,
    coroutineScope: CoroutineScope,
    canonicalFinalLifecycleEffects: RealtimeCanonicalFinalSinkLifecycleEffects?,
    canonicalFinalThreadSlot: Pair<String, String>?,
    canonicalFinalThreadSurfaceRouteKey: String,
    latestCoreThreadRealtimeEventHandlerState: State<suspend (RealtimeEvent) -> Unit>,
) {
    val latestCoreThreadRealtimeEventHandler by latestCoreThreadRealtimeEventHandlerState
    // The owner records accepted event keys before fan-out. Keep the canonical
    // Thread sink mounted during LoadingShell so accepted events are never lost
    // behind the route's feature-surface gate.
    DisposableEffect(realtimeDomainEventOwner, authStore, coroutineScope) {
        val sink =
            RealtimeGuardedActiveThreadSink { serverId, event ->
                val currentSessionServerId =
                    authStore.readSession()
                        ?.currentServerId
                        ?.takeIf { it.isNotBlank() }
                        ?: return@RealtimeGuardedActiveThreadSink
                if (currentSessionServerId != serverId) return@RealtimeGuardedActiveThreadSink
                if (!shouldDeliverToActiveThreadRealtimeSignal(event)) return@RealtimeGuardedActiveThreadSink
                latestCoreThreadRealtimeEventHandler(event)
            }
        realtimeDomainEventOwner.registerGuardedActiveThreadSink(sink)
        onDispose { realtimeDomainEventOwner.unregisterGuardedActiveThreadSink(sink) }
    }
    DisposableEffect(
        canonicalFinalLifecycleEffects,
        canonicalFinalThreadSlot,
        canonicalFinalThreadSurfaceRouteKey,
        authStore,
    ) {
        val threadInstanceKey = canonicalFinalThreadSurfaceRouteKey
        val handle =
            canonicalFinalThreadSlot?.let { (scopeLeaseId, serverId) ->
                canonicalFinalLifecycleEffects?.mount(
                    scopeLeaseId = scopeLeaseId,
                    serverId = serverId,
                    surfaceKey = RealtimeCanonicalFinalSinkSurfaceKey.ThreadCore(threadInstanceKey),
                ) { event ->
                    val currentSessionServerId =
                        authStore.readSession()
                            ?.currentServerId
                            ?.takeIf { it.isNotBlank() }
                            ?: return@mount
                    if (currentSessionServerId != serverId) return@mount
                    if (!shouldDeliverToActiveThreadRealtimeSignal(event)) return@mount
                    latestCoreThreadRealtimeEventHandler(event)
                }
            }
        onDispose { canonicalFinalLifecycleEffects?.dispose(handle) }
    }
}
