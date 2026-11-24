package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import build.raft.app.data.realtime.MessageSeqEnvelope
import build.raft.app.data.realtime.MessageSeqRecoveryPolicy
import build.raft.app.data.realtime.MessageSeqRecoveryState
import build.raft.app.data.realtime.MessageSeqRepairBatch
import build.raft.app.data.realtime.RealtimeAppliedSeqCursorStore
import build.raft.app.model.AgentActivitySubscribed
import build.raft.app.model.AgentCreated
import build.raft.app.model.ConversationKind
import build.raft.app.model.DirectMessageCreated
import build.raft.app.model.Heartbeat
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberPresenceUpdated
import build.raft.app.model.Message
import build.raft.app.model.MessageNew
import build.raft.app.model.MessageType
import build.raft.app.model.MessageUpdated
import build.raft.app.model.ProductFeedbackUnreadUpdated
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.RealtimeRefreshScope
import build.raft.app.model.ServerAppearanceUpdated
import build.raft.app.model.ServerId
import build.raft.app.model.ServerNotificationPreferencesChanged
import build.raft.app.model.ServerPlanUpdated
import build.raft.app.model.SyncResumeResponse
import build.raft.app.model.TaskCreated
import build.raft.app.model.TaskDeleted
import build.raft.app.model.TaskSummary
import build.raft.app.model.TaskUpdated
import build.raft.app.model.ThreadUpdated
import build.raft.app.model.UserProfileUpdated
import build.raft.app.network.sync.CanonicalFinalPreparedCommitStep
import build.raft.app.network.sync.CanonicalMessageFoldInput
import build.raft.app.network.sync.CanonicalMessageFoldKind
import build.raft.app.network.sync.LegacyRealtimeDedupKey
import build.raft.app.network.sync.PartitionDenialReason
import build.raft.app.network.sync.PreparedCurrentGrantMessageCommitDisposition
import build.raft.app.network.sync.SyncCoreMessageShadowFoldLane
import build.raft.app.network.sync.SyncCoreReactionShadowLane
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@RaftCoreInternalApi interface RealtimeDomainEventOwner {
    fun registerSinks(sinks: RealtimeDomainEventSinks)
    fun unregisterSinks(sinks: RealtimeDomainEventSinks)
    fun registerActiveThreadSink(sink: RealtimeActiveThreadSink)
    fun unregisterActiveThreadSink(sink: RealtimeActiveThreadSink)
    fun accept(serverId: String, event: RealtimeEvent, source: String = "unknown")
    fun acceptHomeUnreadProjection(serverId: String, event: RealtimeEvent, source: String = "unknown")
}

@RaftCoreInternalApi data class RealtimeDomainEventSinks(
    val applyHomeShell: (String, RealtimeEvent) -> Unit = { _, _ -> },
    val applyHomeUnreadProjection: (String, RealtimeEvent) -> Unit = { _, _ -> },
    val applyServerMembersRefresh: (String, RealtimeRefreshRequired) -> Unit = { _, _ -> },
    val applyAgentActivity: (MemberPresenceUpdated) -> Unit = {},
    val requestMemberDetailRefresh: () -> Unit = {},
    val joinChannel: (String) -> Unit = {},
    val emitRouteMarker: (RealtimeEvent) -> Unit = {},
    val log: (String) -> Unit = {}
)

@RaftCoreInternalApi data class RealtimeGuardedDomainEventSinks(
    val applyHomeShell: suspend (String, RealtimeEvent) -> Unit = { _, _ -> },
    val applyHomeUnreadProjection: suspend (String, RealtimeEvent) -> Unit = { _, _ -> },
    val applyServerMembersRefresh: suspend (String, RealtimeRefreshRequired) -> Unit = { _, _ -> },
    val applyAgentActivity: suspend (MemberPresenceUpdated) -> Unit = {},
    val requestMemberDetailRefresh: suspend () -> Unit = {},
    val joinChannel: suspend (String) -> Unit = {},
    val emitRouteMarker: suspend (RealtimeEvent) -> Unit = {},
    val log: suspend (String) -> Unit = {},
)

/**
 * Per-route sink for standalone Thread pagers. Registered while a Thread
 * route is mounted; the owner tees every accepted event (including
 * Heartbeat, which Thread needs for seq-gap recovery) into each registered
 * sink after dedup. Thread routes no longer decode raw realtime payloads
 * themselves, so the owner's dedup ledger is the single ingress decision
 * point for shell and thread consumers alike.
 */
@RaftCoreInternalApi fun interface RealtimeActiveThreadSink {
    fun deliver(serverId: String, event: RealtimeEvent)
}

@RaftCoreInternalApi fun interface RealtimeGuardedActiveThreadSink {
    suspend fun deliver(serverId: String, event: RealtimeEvent)
}

/**
 * One guarded sink and the thread it runs on. Ingress runs on a background dispatcher; a sink that writes
 * Compose snapshot state or other main-only objects registers with [onMain] and is called on
 * `Dispatchers.Main`, one event after another, so it never races its own UI.
 */
private class RealtimeGuardedSinkRegistration(
    val sink: RealtimeGuardedActiveThreadSink,
    val onMain: Boolean,
)

@RaftCoreInternalApi class SharedRealtimeDomainEventOwner(
    private val tasksDataStore: SharedTasksDataStore? = null,
    /**
     * Realtime `agent:deleted` on a server of the current grant: the agent's cached detail page (memory and
     * its stored offline copy) goes, so a cold / offline open never shows a deleted agent.
     */
    private val onAgentDeleted: (suspend (serverId: String, agentId: String) -> Unit)? = null,
    /**
     * Realtime `channel:members-updated` on a server of the current grant (offline channel member lists), with
     * the admission identity frozen from that grant; null on the canonical-final surface delivery (no grant).
     */
    private val onChannelMembersUpdated: (suspend (serverId: String, channelId: String?, admission: RealtimePresenceAdmission?) -> Unit)? = null,
    private val cursorStore: RealtimeAppliedSeqCursorStore? = null,
    private val runIfCurrentGrant: suspend (RealtimeGrantRecord, suspend () -> Unit) -> Boolean = { _, _ -> false },
    private val runWithCurrentMessageGrant:
        (suspend (RealtimeGrantRecord, suspend (CurrentMessageCommitContext) -> Unit) -> Boolean)? = null,
    private val canonicalFinalOwnerGateway: RealtimeCanonicalFinalOwnerGateway? = null,
    /** Phase 3a fail-open bound on how long display waits for the durable V2 commit. */
    private val canonicalMessageV2CommitTimeoutMs: Long = CANONICAL_MESSAGE_V2_REALTIME_COMMIT_TIMEOUT_MS,
) : RealtimeDomainEventOwner {
    private var sinks: RealtimeDomainEventSinks? = null
    private var guardedSinks: RealtimeGuardedDomainEventSinks? = null
    // Copy-on-write: composables add and remove sinks on main while ingress iterates on a background
    // thread. Writers swap in a new list under the lock; readers take the current list without it.
    private val activeThreadSinkLock = SynchronizedObject()
    @Volatile
    private var activeThreadSinks: List<RealtimeActiveThreadSink> = emptyList()
    @Volatile
    private var guardedActiveThreadSinks: List<RealtimeGuardedSinkRegistration> = emptyList()
    private val acceptedKeys = ArrayDeque<String>()
    private val acceptedKeySet = mutableSetOf<String>()
    private val recoveryStates = mutableMapOf<String, MessageSeqRecoveryState<RealtimeEvent>>()

    // Sync-core messages shadow fold+persist lane. Null until the provider installs it (see
    // installMessageShadowFoldLane); a null lane means the shadow path is entirely absent (e.g. unit tests
    // that don't exercise it). Invisible to sinks and fail-open; the legacy unowned path can only emit a
    // bounded partition-denial diagnostic and never persists.
    private var messageShadowFoldLane: SyncCoreMessageShadowFoldLane? = null
    // T4 canonical-final commit step. Production installs none (the canonical-final gateway is unbound), so a
    // prepared frame falls back to the legacy path; Phase 4a binds the V2 store here.
    private var canonicalFinalCommitStep: CanonicalFinalPreparedCommitStep? = null
    private var reactionShadowLane: SyncCoreReactionShadowLane? = null
    private var canonicalMessageV2OwnerGateway: CanonicalMessageV2OwnerGateway? = null
    private var canonicalMessageV2ResumeGateway: RealtimeResumeCanonicalMessageV2Gateway? = null

    fun installMessageShadowFoldLane(lane: SyncCoreMessageShadowFoldLane) {
        messageShadowFoldLane = lane
    }

    fun installCanonicalFinalCommitStep(step: CanonicalFinalPreparedCommitStep) {
        canonicalFinalCommitStep = step
    }

    fun installReactionShadowLane(lane: SyncCoreReactionShadowLane) {
        reactionShadowLane = lane
    }

    fun installCanonicalMessageV2OwnerGateway(gateway: CanonicalMessageV2OwnerGateway) {
        canonicalMessageV2OwnerGateway = gateway
    }

    /** Message storage Phase 3b: stores a `sync:resume:response`'s messages before its applied seq is saved. */
    fun installCanonicalMessageV2ResumeGateway(gateway: RealtimeResumeCanonicalMessageV2Gateway) {
        canonicalMessageV2ResumeGateway = gateway
    }

    /** Task #167: forwards a new realtime transport epoch to the durable message canary. Caller checks the grant. */
    suspend fun noteTransportEpoch(grant: RealtimeGrantRecord, transportEpoch: Long) {
        canonicalMessageV2OwnerGateway?.noteTransportEpoch(grant, transportEpoch)
    }

    // task #136 (agent-status SSOT): agent presence facts must reach the
    // process presence registry for the OWNER's whole lifetime, not only while
    // a page composition happens to have sinks registered — the page-scoped
    // sink windows were exactly how machine-offline transitions got lost on
    // the Members surface. The sink receives the IMMUTABLE admission identity
    // derived from the grant the event was accepted under (post-merge audit
    // on PR #1054: resolving the owner from live auth state at delivery let a
    // late old-account callback be attributed to the new account). Entry
    // points without a grant have no principal and must NOT write presence.
    private var memberPresenceSink: ((RealtimePresenceAdmission, MemberPresenceUpdated) -> Unit)? = null
    private var activityUnreadSink: ((ProcessActivityUnreadAdmission, RealtimeEvent) -> Unit)? = null

    fun installMemberPresenceSink(sink: (RealtimePresenceAdmission, MemberPresenceUpdated) -> Unit) {
        memberPresenceSink = sink
    }

    fun installActivityUnreadSink(sink: (ProcessActivityUnreadAdmission, RealtimeEvent) -> Unit) {
        activityUnreadSink = sink
    }

    // `appearance:updated` writes the member's theme onto the stored server of the account the
    // event's grant belongs to (never the live session's account). Process entry only: it runs
    // once per accepted frame, under the current grant, whether or not a shell is mounted.
    private var serverAppearanceSink: (suspend (principalId: String, event: ServerAppearanceUpdated) -> Unit)? = null

    fun installServerAppearanceSink(sink: suspend (principalId: String, event: ServerAppearanceUpdated) -> Unit) {
        serverAppearanceSink = sink
    }

    // `user:profile-updated`: a person's new name / avatar, patched in place into the member stores and,
    // when it is the grant's own account, its stored user. Process entry only, like the appearance sink.
    private var userProfileUpdatedSink: (suspend (principalId: String, event: UserProfileUpdated) -> Unit)? = null

    fun installUserProfileUpdatedSink(sink: suspend (principalId: String, event: UserProfileUpdated) -> Unit) {
        userProfileUpdatedSink = sink
    }

    // `product_feedback:unread` / `product_feedback:app_unread`: one app's feedback unread total, sent to the
    // person's user room (no server); the sink takes every app's for the grant's account (the dot sums them,
    // RaftFeedbackUnreadStore). Process entry only.
    private var feedbackUnreadSink: ((principalId: String, event: ProductFeedbackUnreadUpdated) -> Unit)? = null

    fun installFeedbackUnreadSink(sink: (principalId: String, event: ProductFeedbackUnreadUpdated) -> Unit) {
        feedbackUnreadSink = sink
    }

    // `actor:updated` comes on the grant's server socket (a joint channel room): it patches the joint-actor
    // row of that server. Process entry only, like the profile sink.
    private var actorUpdatedSink: ((serverId: String, event: build.raft.app.model.ActorUpdated) -> Unit)? = null

    fun installActorUpdatedSink(sink: (serverId: String, event: build.raft.app.model.ActorUpdated) -> Unit) {
        actorUpdatedSink = sink
    }

    // `agent:activity:subscribed` answers this process's own subscribe on the grant's server socket.
    private var agentActivitySubscribedSink: ((serverId: String, event: AgentActivitySubscribed) -> Unit)? = null

    fun installAgentActivitySubscribedSink(sink: (serverId: String, event: AgentActivitySubscribed) -> Unit) {
        agentActivitySubscribedSink = sink
    }

    // `machine:status` on the grant's server socket (daemon reconnect after a server restart or
    // outage): the server does not emit `agent:activity_state: "online"` for idle agents, so web
    // runs `executeMachineReloadRecovery` (`loadMachines()` + `loadAgents()`). Process entry only:
    // covers every active shell, window, and route showing the server.
    private var machineStatusSink: ((serverId: String, event: RealtimeRefreshRequired) -> Unit)? = null

    fun installMachineStatusSink(sink: (serverId: String, event: RealtimeRefreshRequired) -> Unit) {
        machineStatusSink = sink
    }

    // All `RealtimeRefreshScope.Machines` pushes (`machine:status`, `machine:updated`,
    // `machine:capabilities`, `computer:upgrade:*`, `machine:upgrade-request`, `daemon:status`):
    // updates SharedComputersStore directly for servers with a loaded or observed slice even while
    // the Computers page is not visible (#wg-mobile-feedback:#273).
    private var machinesRealtimeSink: ((serverId: String, event: RealtimeRefreshRequired) -> Unit)? = null

    fun installMachinesRealtimeSink(sink: (serverId: String, event: RealtimeRefreshRequired) -> Unit) {
        machinesRealtimeSink = sink
    }

    // `notification_prefs:updated` with a server mode is emitted to the viewer's own user room. Process entry
    // only, like the appearance sink: it refreshes the viewer's last-known workspace notification preference
    // under the grant's account whether or not a Settings page is mounted.
    private var serverNotificationPreferencesSink:
        (suspend (principalId: String, serverId: String, event: ServerNotificationPreferencesChanged) -> Unit)? = null

    fun installServerNotificationPreferencesSink(
        sink: suspend (principalId: String, serverId: String, event: ServerNotificationPreferencesChanged) -> Unit,
    ) {
        serverNotificationPreferencesSink = sink
    }

    override fun registerSinks(sinks: RealtimeDomainEventSinks) {
        this.sinks = sinks
    }

    override fun unregisterSinks(sinks: RealtimeDomainEventSinks) {
        if (this.sinks === sinks || this.sinks == sinks) {
            this.sinks = null
        }
    }

    /**
     * True while an AppShell's ready content has its guarded Domain / Home-unread sinks registered.
     * Before that, canonical-final AppShell surfaces fail closed (no delivered-ledger entry), so a
     * caller that must observe a delivery waits on this instead of a fixed delay.
     */
    val hasGuardedSinks: Boolean
        get() = guardedSinks != null

    fun registerGuardedSinks(sinks: RealtimeGuardedDomainEventSinks) {
        guardedSinks = sinks
    }

    fun unregisterGuardedSinks(sinks: RealtimeGuardedDomainEventSinks) {
        if (guardedSinks === sinks || guardedSinks == sinks) {
            guardedSinks = null
        }
    }

    override fun registerActiveThreadSink(sink: RealtimeActiveThreadSink) {
        synchronized(activeThreadSinkLock) {
            if (sink !in activeThreadSinks) activeThreadSinks = activeThreadSinks + sink
        }
    }

    override fun unregisterActiveThreadSink(sink: RealtimeActiveThreadSink) {
        synchronized(activeThreadSinkLock) { activeThreadSinks = activeThreadSinks - sink }
    }

    /**
     * [onMain]: deliver on `Dispatchers.Main` (ingress waits for it, so each sink still sees events in
     * order). Use it for a sink that writes Compose state or calls UI directly. Leave it off for a sink
     * that only hands the event to a store (StateFlow, own dispatcher): it keeps that work off main.
     */
    fun registerGuardedActiveThreadSink(sink: RealtimeGuardedActiveThreadSink, onMain: Boolean = false) {
        synchronized(activeThreadSinkLock) {
            if (guardedActiveThreadSinks.none { it.sink == sink }) {
                guardedActiveThreadSinks = guardedActiveThreadSinks + RealtimeGuardedSinkRegistration(sink, onMain)
            }
        }
    }

    fun unregisterGuardedActiveThreadSink(sink: RealtimeGuardedActiveThreadSink) {
        synchronized(activeThreadSinkLock) {
            guardedActiveThreadSinks = guardedActiveThreadSinks.filterNot { it.sink == sink }
        }
    }

    private suspend fun deliverToGuardedActiveThreadSinks(serverId: String, event: RealtimeEvent) {
        for (registration in guardedActiveThreadSinks) {
            if (!registration.onMain) {
                registration.sink.deliver(serverId, event)
                continue
            }
            withContext(Dispatchers.Main.immediate) {
                // Unregistered (onDispose, also on main) while this event waited for main: drop it there.
                if (guardedActiveThreadSinks.any { it === registration }) registration.sink.deliver(serverId, event)
            }
        }
    }

    /**
     * RFC 082 §4.4: a message the sync log says changed in place (a card message's update), to the
     * conversation pages only, as their tee of a live `message:updated`. It is a row read now, not a
     * socket frame: no seq recovery, no duplicate key, no Home or Activity projection (they show its
     * text, which the next list read brings).
     */
    suspend fun deliverSyncedMessageUpdate(serverId: String, event: MessageUpdated) {
        val resolvedServerId = serverId.takeIf { it.isNotBlank() } ?: return
        activeThreadSinks.forEach { sink -> sink.deliver(resolvedServerId, event) }
        deliverToGuardedActiveThreadSinks(resolvedServerId, event)
    }

    suspend fun accept(
        grant: RealtimeGrantRecord,
        event: RealtimeEvent,
        source: String = "unknown",
        transportEpoch: Long? = null,
    ): Boolean =
        runGuardedCanonicalShadowFirst(
            RealtimeCanonicalFinalOwnerEntry.Selected,
            grant,
            event,
            source,
            transportEpoch,
        ) { serverId, acceptedEvent, acceptedSource ->
            // The admission identity is frozen from the grant this event was
            // accepted under — the only entry allowed to write presence.
            acceptGuarded(serverId, acceptedEvent, acceptedSource, grant.presenceAdmission())
        }

    suspend fun acceptHomeUnreadProjection(
        grant: RealtimeGrantRecord,
        event: RealtimeEvent,
        source: String = "unknown",
        transportEpoch: Long? = null,
    ): Boolean =
        runGuardedCanonicalShadowFirst(
            RealtimeCanonicalFinalOwnerEntry.BackgroundUnread,
            grant,
            event,
            source,
            transportEpoch,
        ) { serverId, acceptedEvent, acceptedSource ->
            acceptGuardedHomeUnreadProjection(serverId, acceptedEvent, acceptedSource)
        }

    suspend fun acceptProcessMutation(
        grant: RealtimeGrantRecord,
        event: RealtimeEvent,
        source: String = "unknown",
        presenceTransportEpoch: Long? = null,
    ): Boolean =
        runGuardedCanonicalShadowFirst(
            RealtimeCanonicalFinalOwnerEntry.Process,
            grant,
            event,
            source,
            presenceTransportEpoch,
        ) { serverId, acceptedEvent, acceptedSource ->
            // Process transport grants never capture shell surfaces. The
            // canonical commit still runs first, including with zero mounted
            // sinks, before process-global cursor/tasks/presence mutate once.
            acceptGuarded(
                serverId,
                acceptedEvent,
                acceptedSource,
                grant.presenceAdmission(presenceTransportEpoch)
            )
            // `appearance:updated` is per member, sent to their user room: it names whichever of their
            // servers changed, not the connection's. The sink writes only the grant's account's servers.
            if (acceptedEvent is ServerAppearanceUpdated) {
                serverAppearanceSink?.invoke(grant.principalId, acceptedEvent)
            }
            if (acceptedEvent is ServerNotificationPreferencesChanged && acceptedEvent.serverId.value == serverId) {
                serverNotificationPreferencesSink?.invoke(grant.principalId, serverId, acceptedEvent)
            }
            // Sent per server room: it names the connection's server.
            if (acceptedEvent is UserProfileUpdated && acceptedEvent.serverId.value == serverId) {
                userProfileUpdatedSink?.invoke(grant.principalId, acceptedEvent)
            }
            if (acceptedEvent is ProductFeedbackUnreadUpdated) {
                feedbackUnreadSink?.invoke(grant.principalId, acceptedEvent)
            }
            // `link_card.operation_changed` goes to the viewer's room on this server: a card following
            // that operation reads its status again (RaftLinkCardOperationHints).
            if (acceptedEvent is build.raft.app.model.LinkCardOperationChanged) {
                RaftLinkCardOperationHints.publish(serverId, acceptedEvent)
            }
            // `link_card.changed` goes to the viewer's room on this server too: the cards of that
            // object are read again (RaftLinkCardChangedHints -> SharedAppLinkPreviewStore).
            if (acceptedEvent is build.raft.app.model.LinkCardChanged) {
                RaftLinkCardChangedHints.publish(serverId, acceptedEvent)
            }
            if (acceptedEvent is build.raft.app.model.ActorUpdated) {
                actorUpdatedSink?.invoke(serverId, acceptedEvent)
            }
            // Sent to the user room: applied only for the connection's server (the next open of another
            // server's conversation reads its preferences anyway).
            if (acceptedEvent is build.raft.app.model.MessageDisplayPrefsChanged && acceptedEvent.serverId.value == serverId) {
                RaftMessageDisplayPrefsStore.applyRealtime(
                    acceptedEvent.serverId.value,
                    acceptedEvent.scopeId,
                    RaftMessageDisplayPrefs(acceptedEvent.collapseLongMessages, acceptedEvent.prefsVersion),
                )
            }
            // Unified notification levels and account settings (slock docs/contracts/notifications.md
            // §2.3): the process stores the channel / thread menus, the badge and the settings page read.
            when (acceptedEvent) {
                is ServerNotificationPreferencesChanged ->
                    RaftNotificationLevel.fromWire(acceptedEvent.serverPushMode)?.let { level ->
                        RaftNotificationLevelStore.applyServerLevel(acceptedEvent.serverId.value, level, acceptedEvent.prefsVersion)
                    }
                is build.raft.app.model.NotificationLevelChanged -> RaftNotificationLevelStore.applyRealtime(acceptedEvent)
                is build.raft.app.model.NotificationAccountSettingsChanged ->
                    RaftNotificationAccountSettingsStore.applyRealtime(acceptedEvent.settings)
                else -> Unit
            }
            if (acceptedEvent is AgentActivitySubscribed) {
                agentActivitySubscribedSink?.invoke(serverId, acceptedEvent)
            }
            if (acceptedEvent is RealtimeRefreshRequired && acceptedEvent.isMachineStatusUpdate()) {
                machineStatusSink?.invoke(serverId, acceptedEvent)
            }
            if (acceptedEvent is RealtimeRefreshRequired && acceptedEvent.scope == RealtimeRefreshScope.Machines) {
                val eventServerId = acceptedEvent.serverId?.value?.trim()
                if (eventServerId.isNullOrEmpty() || eventServerId == serverId) {
                    machinesRealtimeSink?.invoke(serverId, acceptedEvent)
                }
            }
        }

    suspend fun acceptTerminalProcessMutation(
        grant: RealtimeGrantRecord,
        event: RealtimeEvent,
        source: String = "unknown",
        runTerminalIfCurrentGrant: suspend (RealtimeGrantRecord, suspend () -> Unit) -> Boolean,
        terminalMutation: suspend () -> Unit,
    ): Boolean =
        runTerminalIfCurrentGrant(grant) {
            acceptGuarded(grant.serverId, event, source, grant.presenceAdmission())
            terminalMutation()
        }

    /**
     * T3 owner entry shared by selected-server and background-unread guarded projections. A nullable runner
     * preserves the exact legacy [runIfCurrentGrant] path for standalone/test owners; provider-owned owners
     * run the central canonical shadow lane first, then continue through the unchanged legacy path.
     */
    private suspend fun runGuardedCanonicalShadowFirst(
        entry: RealtimeCanonicalFinalOwnerEntry,
        grant: RealtimeGrantRecord,
        event: RealtimeEvent,
        source: String,
        transportEpoch: Long?,
        legacyContinuation: suspend (String, RealtimeEvent, String) -> Unit,
    ): Boolean {
        val currentMessageRunner = runWithCurrentMessageGrant
            ?: return runIfCurrentGrant(grant) {
                legacyContinuation(grant.serverId, event, source)
            }
        val resumeGateway = canonicalMessageV2ResumeGateway
        if (event is SyncResumeResponse && resumeGateway != null) {
            // Phase 3b: the replayed messages are stored (loose) before the legacy continuation saves the applied seq.
            var commit: (suspend () -> Unit)? = null
            val current = currentMessageRunner(grant) { commit = resumeGateway.captureUnderGrant(grant, event) }
            if (!current) return false
            val failure = commit?.let { runCanonicalMessageV2CommitFailOpen(grant, "resume currentSeq=${event.currentSeq}", it) }
            if (failure == "timeout") {
                // Fail-open: the applied seq is about to move while the detached write still runs.
                val line = "canonical_v2_resume outcome=cursor_ahead_of_write server=${grant.serverId} currentSeq=${event.currentSeq} " +
                    "rows=${event.messages.size}"
                guardedSinks?.log?.invoke(line) ?: sinks?.log?.invoke(line)
            }
            return runIfCurrentGrant(grant) {
                legacyContinuation(grant.serverId, event, source)
            }
        }
        val foldInput = event.canonicalMessageFoldInputOrNull()
        if (foldInput == null) {
            return runIfCurrentGrant(grant) {
                legacyContinuation(grant.serverId, event, source)
            }
        }
        // Message storage Phase 3a: the durable V2 commit is bound to the exact current grant (an in-memory read in
        // the slot) and its disk work runs after the slot released the authority registry, on every route; display
        // follows after a fresh grant check (the T4 gateway's own, or runIfCurrentGrant below).
        if (!commitCanonicalMessageV2AfterGrantCheck(currentMessageRunner, grant, foldInput, transportEpoch)) return false
        val legacyKey = event.realtimeDomainKey(grant.serverId)?.let(::LegacyRealtimeDedupKey)
        val gateway = canonicalFinalOwnerGateway
        if (gateway != null && legacyKey != null) {
            return gateway.accept(
                entry = entry,
                grant = grant,
                event = event,
                source = source,
                legacyKey = legacyKey,
                foldInput = foldInput,
                legacyWithoutT3 = { context ->
                    legacyContinuation(context.partition.serverId, event, source)
                },
                legacyWithCurrentT3 = { context ->
                    legacyContinuation(context.partition.serverId, event, source)
                },
                commitPrepared = { context ->
                    canonicalFinalCommitStep?.commitPrepared(
                        partition = context.partition,
                        legacyKey = legacyKey,
                        foldInput = foldInput,
                    ) ?: PreparedCurrentGrantMessageCommitDisposition.LegacyFallback.DefinitelyPrewrite(
                        "lane_unavailable",
                    )
                },
            )
        }
        return runIfCurrentGrant(grant) {
            legacyContinuation(grant.serverId, event, source)
        }
    }

    /**
     * Phase 3a: binds the V2 commit inside the exact-grant slot ([CanonicalMessageV2OwnerGateway.captureUnderGrant]
     * reads only in-memory state there), then runs it fail-open after the slot. `false` = the grant was not current:
     * nothing is committed and the frame must not be displayed.
     */
    private suspend fun commitCanonicalMessageV2AfterGrantCheck(
        currentMessageRunner: suspend (RealtimeGrantRecord, suspend (CurrentMessageCommitContext) -> Unit) -> Boolean,
        grant: RealtimeGrantRecord,
        foldInput: CanonicalMessageFoldInput,
        transportEpoch: Long?,
    ): Boolean {
        var commit: (suspend () -> Unit)? = null
        val current = currentMessageRunner(grant) {
            commit = canonicalMessageV2OwnerGateway?.captureUnderGrant(grant, foldInput, transportEpoch)
        }
        if (!current) return false
        commit?.let { runCanonicalMessageV2CommitFailOpen(grant, "msg=${foldInput.row.id} seq=${foldInput.row.seq}", it) }
        return true
    }

    /**
     * Fail-open: display never waits longer than [canonicalMessageV2CommitTimeoutMs] on, and is never skipped by,
     * the durable write. A throw is logged and swallowed; past the timeout the frame is displayed while the
     * production ingest keeps the commit queued in order (it is never cancelled half-way, so the live coverage
     * tracker's continuity stays exact). Only the caller's own cancellation propagates.
     */
    private suspend fun runCanonicalMessageV2CommitFailOpen(
        grant: RealtimeGrantRecord,
        label: String,
        commit: suspend () -> Unit,
    ): String? {
        val failure = try {
            val completed = withTimeoutOrNull(canonicalMessageV2CommitTimeoutMs) {
                commit()
                true
            }
            if (completed == null) "timeout" else null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            "error=${throwable::class.simpleName ?: "unknown"}"
        }
        if (failure != null) {
            val line = "canonical_v2_realtime outcome=display_only server=${grant.serverId} $label reason=$failure"
            guardedSinks?.log?.invoke(line) ?: sinks?.log?.invoke(line)
        }
        return failure
    }

    override fun accept(serverId: String, event: RealtimeEvent, source: String) {
        val resolvedServerId = serverId.takeIf { it.isNotBlank() } ?: return
        val key = event.realtimeDomainKey(resolvedServerId)
        if (key != null && !rememberAcceptedKey(key)) {
            sinks?.log?.invoke("realtime_domain_accept_skip source=$source reason=duplicate key=$key")
            return
        }
        val acceptedEvents = acceptedEventsForFold(resolvedServerId, event, source)
        if (acceptedEvents.isEmpty()) return
        // Sync-core messages SHADOW partition gate, BEFORE the sink fan-out and invisible to it. This
        // legacy overload has no principal, so only canonical MessageNew/Updated frames are counted and
        // denied; non-message and non-canonical events produce no diagnostic. The sink path stays untouched.
        messageShadowFoldLane?.let { lane ->
            val deniedFrameCount = acceptedEvents.count { acceptedEvent ->
                when (acceptedEvent) {
                    is MessageNew -> acceptedEvent.decodedCanonical != null
                    is MessageUpdated -> acceptedEvent.decodedCanonical != null
                    else -> false
                }
            }
            if (deniedFrameCount > 0) {
                lane.partitionDenied(
                    serverId = resolvedServerId,
                    reason = PartitionDenialReason.PrincipalUnavailable,
                    deniedFrameCount = deniedFrameCount,
                )
            }
        }
        reactionShadowLane?.let { lane -> observeReactionShadow(lane, resolvedServerId, acceptedEvents) }
        val activeSinks = sinks
        // Tee to Thread routes before the Heartbeat early-return: Thread
        // consumes Heartbeat seq for realtime gap recovery. Snapshot the list
        // so a sink unregistering during delivery cannot corrupt iteration.
        acceptedEvents.forEach { acceptedEvent ->
            activeThreadSinks.forEach { sink -> sink.deliver(resolvedServerId, acceptedEvent) }
            if (acceptedEvent is Heartbeat) {
                activeSinks?.log?.invoke(
                    "realtime_domain_accept source=$source serverId=$resolvedServerId projection=none type=heartbeat"
                )
                return@forEach
            }
            if (acceptedEvent.updatesSharedTaskProjection()) {
                activeSinks?.log?.invoke(
                    "tasks_realtime_drop source=$source serverId=$resolvedServerId reason=owner_admission_missing"
                )
            }
            activeSinks?.applyHomeShell?.invoke(resolvedServerId, acceptedEvent)
            if (acceptedEvent is DirectMessageCreated) {
                activeSinks?.joinChannel?.invoke(acceptedEvent.channelId.value)
            }
            if (acceptedEvent is ServerPlanUpdated) {
                activeSinks?.log?.invoke(
                    "shared_realtime_server_plan_updated serverId=${acceptedEvent.serverId.value} plan=${acceptedEvent.plan}"
                )
            }
            if (acceptedEvent is MemberPresenceUpdated && acceptedEvent.kind == build.raft.app.model.MemberKind.Agent) {
                // This legacy overload has no principal (see the shadow-lane
                // note above): without a grant there is no admission identity,
                // so it must never write the presence registry — a held
                // old-account callback released here after an account switch
                // would otherwise be attributed to whoever is signed in now.
                activeSinks?.log?.invoke(
                    "presence_write_dropped source=$source serverId=$resolvedServerId reason=no_admission"
                )
                activeSinks?.applyAgentActivity?.invoke(acceptedEvent)
            } else if (acceptedEvent.refreshesMemberDetail()) {
                if (acceptedEvent is RealtimeRefreshRequired) {
                    activeSinks?.applyServerMembersRefresh?.invoke(resolvedServerId, acceptedEvent)
                }
                activeSinks?.requestMemberDetailRefresh?.invoke()
            }
            activeSinks?.emitRouteMarker?.invoke(acceptedEvent)
            if (acceptedEvent !is MemberPresenceUpdated) {
                activeSinks?.log?.invoke(
                    "realtime_domain_accept source=$source serverId=$resolvedServerId projection=domain"
                )
            }
        }
    }

    override fun acceptHomeUnreadProjection(serverId: String, event: RealtimeEvent, source: String) {
        val resolvedServerId = serverId.takeIf { it.isNotBlank() } ?: return
        val key = event.realtimeDomainKey(resolvedServerId)
        if (key != null && !rememberAcceptedKey(key)) {
            sinks?.log?.invoke("realtime_domain_accept_skip source=$source reason=duplicate key=$key")
            return
        }
        val acceptedEvents = acceptedEventsForFold(resolvedServerId, event, source)
        if (acceptedEvents.isEmpty()) return
        acceptedEvents.forEach { acceptedEvent ->
            if (acceptedEvent !is Heartbeat) {
                sinks?.applyHomeUnreadProjection?.invoke(resolvedServerId, acceptedEvent)
                sinks?.log?.invoke(
                    "realtime_domain_accept source=$source serverId=$resolvedServerId projection=home_unread_count"
                )
            }
        }
    }

    private suspend fun acceptGuarded(
        serverId: String,
        event: RealtimeEvent,
        source: String,
        admission: RealtimePresenceAdmission?,
    ) {
        val resolvedServerId = serverId.takeIf { it.isNotBlank() } ?: return
        val key = event.realtimeDomainKey(resolvedServerId)
        if (key != null && !rememberAcceptedKey(key)) {
            guardedSinks?.log?.invoke("realtime_domain_accept_skip source=$source reason=duplicate key=$key")
            return
        }
        val acceptedEvents = acceptedEventsForFold(resolvedServerId, event, source)
        if (acceptedEvents.isEmpty()) return
        val activeSinks = guardedSinks
        acceptedEvents.forEach { acceptedEvent ->
            deliverToGuardedActiveThreadSinks(resolvedServerId, acceptedEvent)
            if (acceptedEvent is Heartbeat) {
                activeSinks?.log?.invoke(
                    "realtime_domain_accept source=$source serverId=$resolvedServerId projection=none type=heartbeat"
                )
                return@forEach
            }
            deliverGuardedAppShellDomainFinal(resolvedServerId, acceptedEvent, source, activeSinks, admission)
            // Product-side apply receipt for the subscription audit (task #13): logged once per
            // event AFTER the guarded domain-sink dispatch returned — i.e. after applyHomeShell /
            // applyHomeUnreadProjection and the member/presence writes completed without throwing.
            // A duplicate redelivery is dropped upstream at rememberAcceptedKey
            // (realtime_domain_accept_skip) and never reaches this line, and a sink failure
            // propagates before it, so one line per (id, seq) is the applied-once witness on the
            // product store edge — not merely a pre-dispatch gate pass.
            when (acceptedEvent) {
                is MessageNew ->
                    activeSinks?.log?.invoke(
                        "realtime_domain_apply source=$source serverId=$resolvedServerId " +
                            "kind=message_new id=${acceptedEvent.message.id.value} seq=${acceptedEvent.message.seq ?: -1L}"
                    )
                is MessageUpdated ->
                    activeSinks?.log?.invoke(
                        "realtime_domain_apply source=$source serverId=$resolvedServerId " +
                            "kind=message_updated id=${acceptedEvent.message.id.value} seq=${acceptedEvent.message.seq ?: -1L}"
                    )
                else -> Unit
            }
        }
    }

    private suspend fun acceptGuardedHomeUnreadProjection(
        serverId: String,
        event: RealtimeEvent,
        source: String,
    ) {
        val resolvedServerId = serverId.takeIf { it.isNotBlank() } ?: return
        val key = event.realtimeDomainKey(resolvedServerId)
        if (key != null && !rememberAcceptedKey(key)) {
            guardedSinks?.log?.invoke("realtime_domain_accept_skip source=$source reason=duplicate key=$key")
            return
        }
        val acceptedEvents = acceptedEventsForFold(resolvedServerId, event, source)
        if (acceptedEvents.isEmpty()) return
        acceptedEvents.forEach { acceptedEvent ->
            if (acceptedEvent !is Heartbeat) {
                guardedSinks?.let { activeSinks ->
                    deliverGuardedAppShellHomeUnreadFinal(
                        sinks = activeSinks,
                        serverId = resolvedServerId,
                        event = acceptedEvent,
                        source = source,
                    )
                }
            }
        }
    }

    suspend fun deliverGuardedAppShellDomainFinal(
        sinks: RealtimeGuardedDomainEventSinks,
        serverId: String,
        event: RealtimeEvent,
        source: String = "canonical_final",
    ) {
        // Canonical-final surface delivery: gateway-admitted message frames
        // only — no grant reaches this entry, so it carries no presence
        // admission and can never write the presence registry.
        deliverGuardedAppShellDomainFinal(serverId, event, source, sinks, admission = null)
    }

    private suspend fun deliverGuardedAppShellDomainFinal(
        serverId: String,
        event: RealtimeEvent,
        source: String,
        activeSinks: RealtimeGuardedDomainEventSinks?,
        admission: RealtimePresenceAdmission?,
    ) {
        val resolvedServerId = serverId.takeIf { it.isNotBlank() } ?: return
        if (event is Heartbeat) return
        event.patchSharedTaskProjection(resolvedServerId)
        event.deletedAgentIdOrNull()?.let { agentId -> onAgentDeleted?.invoke(resolvedServerId, agentId) }
        event.updatedChannelMembersOrNull()?.let { update ->
            onChannelMembersUpdated?.invoke(resolvedServerId, update.channelId, admission)
        }
        deliverGuardedAppShellProjectionFinal(resolvedServerId, event, source, activeSinks)
        if (event is MemberPresenceUpdated && event.kind == build.raft.app.model.MemberKind.Agent) {
            if (admission != null) {
                memberPresenceSink?.invoke(admission, event)
            } else {
                activeSinks?.log?.invoke(
                    "presence_write_dropped source=$source serverId=$resolvedServerId reason=no_admission"
                )
            }
        }
        if (admission != null) {
            activityUnreadSink?.invoke(admission.toActivityUnreadAdmission(), event)
        }
    }

    private suspend fun deliverGuardedAppShellProjectionFinal(
        resolvedServerId: String,
        event: RealtimeEvent,
        source: String,
        activeSinks: RealtimeGuardedDomainEventSinks?,
    ) {
        activeSinks?.applyHomeShell?.invoke(resolvedServerId, event)
        if (event is DirectMessageCreated) {
            activeSinks?.joinChannel?.invoke(event.channelId.value)
        }
        if (event is ServerPlanUpdated) {
            activeSinks?.log?.invoke(
                "shared_realtime_server_plan_updated serverId=${event.serverId.value} plan=${event.plan}"
            )
        }
        if (event is MemberPresenceUpdated && event.kind == build.raft.app.model.MemberKind.Agent) {
            activeSinks?.applyAgentActivity?.invoke(event)
        } else if (event.refreshesMemberDetail()) {
            if (event is RealtimeRefreshRequired) {
                activeSinks?.applyServerMembersRefresh?.invoke(resolvedServerId, event)
            }
            activeSinks?.requestMemberDetailRefresh?.invoke()
        }
        activeSinks?.emitRouteMarker?.invoke(event)
        if (event !is MemberPresenceUpdated) {
            activeSinks?.log?.invoke(
                "realtime_domain_accept source=$source serverId=$resolvedServerId projection=domain"
            )
        }
    }

    suspend fun deliverGuardedAppShellHomeUnreadFinal(
        sinks: RealtimeGuardedDomainEventSinks,
        serverId: String,
        event: RealtimeEvent,
        source: String = "canonical_final",
    ) {
        val resolvedServerId = serverId.takeIf { it.isNotBlank() } ?: return
        if (event is Heartbeat) return
        sinks.applyHomeUnreadProjection.invoke(resolvedServerId, event)
        sinks.log.invoke(
            "realtime_domain_accept source=$source serverId=$resolvedServerId projection=home_unread_count"
        )
    }

    /** Normalized reaction SHADOW observation. No persistence, cache mutation, cursor, or sink output. */
    private fun observeReactionShadow(
        lane: SyncCoreReactionShadowLane,
        resolvedServerId: String,
        acceptedEvents: List<RealtimeEvent>,
    ) {
        try {
            for (acceptedEvent in acceptedEvents) {
                val decoded =
                    when (acceptedEvent) {
                        is MessageNew -> acceptedEvent.decodedCanonical
                        is MessageUpdated -> acceptedEvent.decodedCanonical
                        else -> null
                    } ?: continue
                lane.observe(resolvedServerId, decoded)
            }
        } catch (throwable: Throwable) {
            lane.logFailure(resolvedServerId, messageId = "unknown", throwable)
        }
    }

    private fun acceptedEventsForFold(serverId: String, event: RealtimeEvent, source: String): List<RealtimeEvent> {
        val store = cursorStore ?: return listOf(event)
        val server = ServerId(serverId)
        val currentState = recoveryStates.getOrPut(serverId) {
            MessageSeqRecoveryPolicy.initial(
                appliedSeq = store.readAppliedLastSeq(server),
                epoch = store.currentEpoch(server)
            )
        }
        val result =
            when (event) {
                is MessageNew,
                is MessageUpdated -> {
                    val frame = event.messageSeqEnvelope(currentState.epoch) ?: return listOf(event)
                    MessageSeqRecoveryPolicy.onFrame(currentState, frame)
                }
                is SyncResumeResponse ->
                    MessageSeqRecoveryPolicy.onRepairBatch(
                        currentState,
                        MessageSeqRepairBatch(
                            events = event.messages.mapNotNull { message ->
                                MessageSeqEnvelope(
                                    seq = message.seq?.takeIf { it > 0L } ?: return@mapNotNull null,
                                    epoch = currentState.epoch,
                                    event = MessageNew(message)
                                )
                            },
                            currentSeq = event.currentSeq,
                            hasMore = event.hasMore,
                            epoch = currentState.epoch
                        )
                    )
                else -> return listOf(event)
            }
        val nextState =
            result.state.copy(
                serverSeq = maxOf(result.state.serverSeq, result.state.appliedSeq),
                appliedEvents = emptyList()
            )
        recoveryStates[serverId] = nextState
        if (nextState.appliedSeq > currentState.appliedSeq) {
            store.saveAppliedLastSeq(server, nextState.appliedSeq, nextState.epoch)
        }
        val frameSeq =
            when (event) {
                is MessageNew -> event.message.seq
                is MessageUpdated -> event.message.seq
                else -> null
            }
        if (frameSeq != null && frameSeq > currentState.appliedSeq + 1L) {
            sinks?.log?.invoke(
                "realtime_domain_accept_repair_request source=$source serverId=$serverId " +
                    "reason=message_seq_gap serverSeq=${result.state.serverSeq} appliedSeq=${result.state.appliedSeq}"
            )
        }
        return when (event) {
            is MessageNew,
            is MessageUpdated,
            is SyncResumeResponse -> listOf(event)
            else -> result.state.appliedEvents
        }
    }

    private fun rememberAcceptedKey(key: String): Boolean {
        if (!acceptedKeySet.add(key)) return false
        acceptedKeys.addLast(key)
        while (acceptedKeys.size > AcceptedRealtimeKeyLimit) {
            acceptedKeySet.remove(acceptedKeys.removeFirst())
        }
        return true
    }

    private suspend fun RealtimeEvent.patchSharedTaskProjection(serverId: String) {
        val store = tasksDataStore ?: return
        suspend fun patch(task: TaskSummary) {
            when (store.upsertTaskForCurrentOwner(serverId, task)) {
                SharedTaskFactFoldOutcome.Accepted -> Unit
                SharedTaskFactFoldOutcome.StaleIgnored ->
                    sinks?.log?.invoke(
                        "tasks_canonical_stale_drop serverId=$serverId taskId=${task.id.value} " +
                            "revision=${task.revision ?: -1L}"
                    )
                SharedTaskFactFoldOutcome.Conflict ->
                    sinks?.log?.invoke(
                        "tasks_canonical_conflict serverId=$serverId taskId=${task.id.value} " +
                        "revision=${task.revision ?: -1L} requires=authoritative_refetch"
                    )
                null ->
                    sinks?.log?.invoke(
                        "tasks_realtime_drop serverId=$serverId taskId=${task.id.value} reason=owner_changed"
                    )
            }
        }
        when (this) {
            is MessageNew -> {
                // Task v2: a reply in a task's own conversation moves its reply count (web
                // socketBridge `applyTaskConversationReply`); lifecycle lines do not count.
                val context = message.conversationContext
                val taskId = context?.taskId
                if (context?.channelKind == ConversationKind.Task && taskId != null && message.messageType != MessageType.System) {
                    store.bumpTaskConversationReplyCount(serverId, taskId, message.id)
                }
            }
            is TaskCreated -> tasks.forEach { task -> patch(task) }
            is TaskUpdated -> patch(task)
            is TaskDeleted -> {
                if (!store.removeTaskForCurrentOwner(serverId, taskId)) {
                    sinks?.log?.invoke(
                        "tasks_realtime_drop serverId=$serverId taskId=${taskId.value} reason=owner_changed"
                    )
                }
            }
            else -> Unit
        }
    }
}

/** A realtime `channel:members-updated`: [channelId] null when the event names no channel (every channel). */
@RaftCoreInternalApi data class RealtimeChannelMembersUpdate(val channelId: String?)

/**
 * A realtime `channel:members-updated`, else null. One without a channel id applies to every channel of the
 * server (as ThreadPage reads it), never dropped.
 */
@RaftCoreInternalApi fun RealtimeEvent.updatedChannelMembersOrNull(): RealtimeChannelMembersUpdate? =
    (this as? RealtimeRefreshRequired)
        ?.takeIf { it.scope == RealtimeRefreshScope.ChannelMembers }
        ?.let { event -> RealtimeChannelMembersUpdate(event.channelId?.value?.trim()?.takeIf(String::isNotEmpty)) }

/** The agent of a realtime `agent:deleted`, else null. */
@RaftCoreInternalApi fun RealtimeEvent.deletedAgentIdOrNull(): String? =
    (this as? RealtimeRefreshRequired)
        ?.takeIf { it.scope == RealtimeRefreshScope.Agents && it.reason == build.raft.app.data.realtime.RealtimeSocketEvents.AgentDeleted }
        ?.let { event -> event.subjectId?.trim()?.takeIf(String::isNotEmpty) ?: event.memberId?.value?.trim()?.takeIf(String::isNotEmpty) }

/**
 * A realtime `agent:updated`: an agent's stored profile (avatar, name, description) changed. It
 * carries only the id; like web, the roster's agents are read again (`/agents`).
 */
@RaftCoreInternalApi fun RealtimeEvent.isAgentProfileUpdate(): Boolean =
    this is RealtimeRefreshRequired &&
        scope == RealtimeRefreshScope.Agents &&
        reason == build.raft.app.data.realtime.RealtimeSocketEvents.AgentUpdated

/**
 * A realtime `machine:status` push (web `executeMachineReloadRecovery`, slock `socketBridge.ts` /
 * `agentOrchestrator.ts`): a Computer came online or went offline. Re-reading `/agents` recovers
 * server-derived activity for idle agents that receive no `agent:activity_state: "online"` push.
 */
@RaftCoreInternalApi fun RealtimeEvent.isMachineStatusUpdate(): Boolean =
    this is RealtimeRefreshRequired &&
        scope == RealtimeRefreshScope.Machines &&
        reason == build.raft.app.data.realtime.RealtimeSocketEvents.MachineStatus

private fun RealtimeEvent.updatesSharedTaskProjection(): Boolean =
    this is TaskCreated || this is TaskUpdated || this is TaskDeleted

private fun RealtimeEvent.refreshesMemberDetail(): Boolean =
    this is AgentCreated ||
        (
            this is RealtimeRefreshRequired &&
                (
                    scope == RealtimeRefreshScope.ServerMembers ||
                        scope == RealtimeRefreshScope.Agents ||
                        scope == RealtimeRefreshScope.Machines
                    )
            ) ||
        this is ServerPlanUpdated

private const val AcceptedRealtimeKeyLimit = 256

private fun RealtimeEvent.canonicalMessageFoldInputOrNull(): CanonicalMessageFoldInput? =
    when (this) {
        is MessageNew -> decodedCanonical?.let { carry ->
            CanonicalMessageFoldInput.from(CanonicalMessageFoldKind.New, carry)
        }
        is MessageUpdated -> decodedCanonical?.let { carry ->
            CanonicalMessageFoldInput.from(CanonicalMessageFoldKind.Updated, carry)
        }
        else -> null
    }

private fun RealtimeEvent.realtimeDomainKey(serverId: String): String? =
    when (this) {
        is MessageNew ->
            "message:new:$serverId:${message.id.value}:${message.seq ?: -1L}"
        is MessageUpdated ->
            "message:updated:$serverId:${message.id.value}:${message.seq ?: -1L}:${message.updatedAtLabel.orEmpty()}"
        is DirectMessageCreated ->
            "dm:new:$serverId:${channelId.value}"
        is ThreadUpdated ->
            "thread:updated:$serverId:${threadChannelId.value}:${parentMessageId.value}:$replyCount:${unreadCount ?: -1}:" +
                "${firstUnreadMessageId?.value.orEmpty()}:${lastReplyAt.orEmpty()}:" +
                "${latestReply?.messageId.orEmpty()}:${latestReply?.seq ?: -1L}"
        is build.raft.app.model.ThreadReplyUpdated ->
            "thread:reply_updated:$serverId:${threadChannelId.value}:${messageId.value}:$seq:$updatedAt"
        is TaskCreated ->
            "task:created:$serverId:${tasks.joinToString("|") { it.taskDomainKey() }}"
        is TaskUpdated ->
            "task:updated:$serverId:${task.taskDomainKey()}"
        is TaskDeleted ->
            "task:deleted:$serverId:${taskId.value}"
        is MemberPresenceUpdated ->
            // agent:activity serverSeq is monotonic per agent, not per server.
            // Include memberId so different agents with the same seq do not
            // suppress each other's presence updates in the app-shell fanout.
            // agent:seen is seq-less; lastSeenAt is the key so a new
            // observation is never deduped away.
            // agent:activity_state repeats its detailed frame's seq; its own key lets the presence
            // registry (strictly-newer rule) decide instead of the dedupe.
            serverSeq?.let { "member:presence:$serverId:${memberId.value}:$it${if (coarseState) ":state" else ""}" }
                ?: "member:presence:$serverId:${memberId.value}:${presence.name}:${agentStatus?.name.orEmpty()}:${statusLabel.orEmpty()}:${detailKind.orEmpty()}:${activityToolName.orEmpty()}:${lastSeenAt.orEmpty()}"
        // task #136: refresh hints are idempotent TRIGGERS, not facts, and the
        // event carries no seq — a stable key made the accepted-key ledger
        // swallow every later identical hint (artin's device logs:
        // accept_skip reason=duplicate key=refresh:…:Agents:agent:session:…),
        // so a second state change for the same agent never re-fetched. No key
        // → every hint processed; the downstream refetch already dedupes
        // in-flight loads, so bursts stay cheap. The unread store coalesces the
        // two subscribers' copies of one hint per round (mobile #3650); telling a
        // duplicate delivery from a genuinely repeated hint needs a server frame
        // id (#proj-mobile task #23).
        is RealtimeRefreshRequired -> null
        is ServerPlanUpdated ->
            "server:plan:$serverId:${this.serverId.value}:$plan"
        is AgentCreated ->
            "agent:created:$serverId:${agent.id.value}"
        is SyncResumeResponse ->
            "sync:resume:$serverId:$currentSeq:${messages.joinToString("|") { it.id.value }}"
        is Heartbeat ->
            "heartbeat:$serverId:$seq"
        else -> null
    }

private fun TaskSummary.taskDomainKey(): String =
    "${id.value}:${revision ?: -1L}:${status.name}:${updatedAtLabel}:${assignee?.id?.value.orEmpty()}"

private fun RealtimeEvent.messageSeqEnvelope(epoch: String): MessageSeqEnvelope<RealtimeEvent>? =
    when (this) {
        is MessageNew ->
            message.seq
                ?.takeIf { it > 0L }
                ?.let { seq -> MessageSeqEnvelope(seq = seq, epoch = epoch, event = this) }
        is MessageUpdated ->
            message.seq
                ?.takeIf { it > 0L }
                ?.let { seq -> MessageSeqEnvelope(seq = seq, epoch = epoch, event = this) }
        else -> null
    }

