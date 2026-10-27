package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * The members-roster half of a presence transport connect, after the provider moved the presence
 * epoch (`noteTransportEpoch` + `rebaselineTransport`, both immediate: the new socket's frames
 * must land on the new epoch).
 *
 * Immediate, every connect: [invalidate] retires the pre-connect roster flight and cache of
 * [ProcessServerMembersCore], so no later load (forced or not) can answer with a roster read
 * under the old transport.
 *
 * Through [jitter]: the roster refetch itself (`/servers/:id/members` + `/agents` +
 * `/servers/:id/machines`, then peer-agent channel slices) in every store showing the server.
 * A first connect fetches at once; a reconnect waits the client's 0–20 s delay, except the connect
 * that follows a server switch ([RaftReconnectRefetchDecision.ImmediateAfterSwitch]: a switch back to
 * a server visited before is a reconnect, and its agents showed offline until the delay ended,
 * Hands 81ad5dae); that one fetches at once with a reconnect's scope. While it waits,
 * presence shows the last roster plus every live presence push of the new socket; a member whose
 * state changed during the outage and who sends no push stays stale until the refetch.
 *
 * Incremental sync on for the server (`agentsOnlyOnReconnect`, `mobile_sync_changes_v0`): a
 * RE-connect refetches only `/agents` ([SharedServerMembersStore.refreshAgents]) and the machine
 * list ([refreshMachines]), still through [jitter]. Agent presence and machine online status are
 * not in the change log; humans, machine rows and channel rosters are (Ray / Tenny / Scout 10-27).
 * A first connect keeps the full refetch.
 *
 * A sync-on RE-connect also leaves the roster valid: no [invalidate]. The held roster stays the
 * process core's cached answer, so a Home reload right after the reconnect (catch-up, sync
 * `home=true`) reuses it instead of reading `/members` + `/agents` + `/machines` at once, outside
 * the jitter (staging run 10-27, Tenny). A failed pull still gets the full refetch
 * ([onSyncPullFailed]).
 *
 * A failed refetch (not Content) is logged and retried once per store on [retryScope], after a
 * short wait and only while the connect's transport is still current
 * ([SharedServerMembersStore.retryPresenceTransportRefill]). Past that, the next successful roster
 * load of any kind writes agent status back ([ProcessMemberPresenceRegistry.needsRosterRefill]).
 */
internal class PresenceTransportRosterRefetch(
    private val jitter: RaftReconnectRefetchJitter,
    private val invalidate: suspend (owner: ProcessServerMembersOwner, reason: String) -> Unit,
    /** Stores showing the server, read when the refetch runs (a shell may have switched meanwhile). */
    private val storesForServer: (serverId: String) -> List<SharedServerMembersStore>,
    /** False once the owner's binding is gone (sign-out / account switch while waiting). */
    private val isCurrentOwner: (ProcessServerMembersOwner) -> Boolean = { true },
    /** Sync-on reconnect: re-read the server's machine list (online status), once per refetch. */
    private val refreshMachines: suspend (serverId: String) -> Unit = {},
    private val logger: (String) -> Unit = {},
    private val warnLogger: (String) -> Unit = {},
    /** Where a failed refetch's one retry waits and runs; null runs it inline (tests). */
    private val retryScope: CoroutineScope? = null,
) {
    suspend fun onTransportRebaselined(
        owner: ProcessServerMembersOwner,
        transport: ProcessPresenceTransportEpoch,
        rebaselineResult: ProcessPresenceTransportRebaselineResult,
        /** Decided once at this connect, with the pull: incremental sync covers the server's lists. */
        agentsOnlyOnReconnect: Boolean = false,
    ): RaftReconnectRefetchDecision {
        // Sync off: retire the roster before the decision, as always (a delayed refetch may run at 0 ms).
        if (!agentsOnlyOnReconnect) invalidate(owner, REASON)
        val decision =
            jitter.onConnected(
                session = RaftReconnectSession(owner.environmentId, owner.accountId, owner.serverId),
                source = SOURCE,
            ) { refetch(owner, transport, rebaselineResult, agentsOnly = agentsOnlyOnReconnect) }
        if (decision == RaftReconnectRefetchDecision.Immediate) {
            // Sync on, first connect: the full forced refetch, so retire what the old transport read.
            if (agentsOnlyOnReconnect) invalidate(owner, REASON)
            refetch(owner, transport, rebaselineResult, agentsOnly = false)
        } else if (decision == RaftReconnectRefetchDecision.ImmediateAfterSwitch) {
            // The user is looking at this server: no jitter, but a reconnect's scope (sync on: agents
            // and machines only, the roster stays valid; sync off: the invalidate above + full refetch).
            refetch(owner, transport, rebaselineResult, agentsOnly = agentsOnlyOnReconnect)
        } else if (agentsOnlyOnReconnect) {
            logger(
                "presence_transport_rebaseline serverId=${owner.serverId} epoch=${transport.epoch} " +
                    "roster_invalidate=skipped cause=incremental_sync"
            )
        }
        return decision
    }

    /**
     * The incremental pull that stood in for the roster refetch of this (re)connect failed or timed
     * out: the full roster refetch runs instead, always after the reconnect jitter (Tenny 10-27). It
     * replaces an agents-only refetch still waiting for this server (one entry per source and server).
     */
    fun onSyncPullFailed(owner: ProcessServerMembersOwner, transport: ProcessPresenceTransportEpoch) {
        jitter.scheduleDelayed(serverId = owner.serverId, source = SOURCE) {
            refetch(owner, transport, ProcessPresenceTransportRebaselineResult.Current, agentsOnly = false)
        }
    }

    private suspend fun refetch(
        owner: ProcessServerMembersOwner,
        transport: ProcessPresenceTransportEpoch,
        rebaselineResult: ProcessPresenceTransportRebaselineResult,
        agentsOnly: Boolean,
    ) {
        if (!isCurrentOwner(owner)) {
            logger("presence_transport_rebaseline serverId=${owner.serverId} epoch=${transport.epoch} result=owner_retired")
            return
        }
        val stores = storesForServer(owner.serverId)
        logger(
            "presence_transport_rebaseline serverId=${owner.serverId} epoch=${transport.epoch} " +
                "result=${rebaselineResult.name} stores=${stores.size} refetch=${if (agentsOnly) "agents+machines" else "roster"}"
        )
        coroutineScope {
            if (agentsOnly) {
                launch {
                    try {
                        refreshMachines(owner.serverId)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Throwable) {
                        warnLogger("serverId=${owner.serverId} machines_refresh_failed=${error::class.simpleName ?: "unknown"}")
                    }
                }
            }
            stores.forEach { store ->
                launch {
                    try {
                        val result =
                            if (agentsOnly) {
                                store.refreshAgents(serverId = ServerId(owner.serverId), reason = REASON)
                            } else {
                                // Roster refill first, then peer-server agents of joint channels (not on
                                // this roster; their facts come back from the channel-members refetch).
                                store.refreshAfterPresenceTransportConnected(
                                    serverId = ServerId(owner.serverId),
                                    transport = transport,
                                    reason = REASON
                                )
                            }
                        if (result is DataResult.Error) {
                            warnLogger(
                                "serverId=${owner.serverId} epoch=${transport.epoch} refresh_failed=${result.error.code} " +
                                    "refetch=${if (agentsOnly) "agents" else "roster"} retry=scheduled"
                            )
                            scheduleRetry(store, owner, transport, agentsOnly)
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Throwable) {
                        warnLogger(
                            "serverId=${owner.serverId} refresh_failed=${error::class.simpleName ?: "unknown"}"
                        )
                    }
                }
            }
        }
    }

    private suspend fun scheduleRetry(
        store: SharedServerMembersStore,
        owner: ProcessServerMembersOwner,
        transport: ProcessPresenceTransportEpoch,
        agentsOnly: Boolean,
    ) {
        val retry: suspend () -> Unit = {
            try {
                if (isCurrentOwner(owner)) {
                    store.retryPresenceTransportRefill(
                        serverId = ServerId(owner.serverId),
                        transport = transport,
                        reason = REASON,
                        agentsOnly = agentsOnly,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                warnLogger("serverId=${owner.serverId} refresh_retry_failed=${error::class.simpleName ?: "unknown"}")
            }
        }
        val scope = retryScope
        if (scope == null) retry() else scope.launch { retry() }
    }

    companion object {
        const val SOURCE = "server_members"
        const val REASON = "presence_transport_connected"
    }
}
