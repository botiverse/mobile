package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.coroutines.CancellationException
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
 * A first connect fetches at once; a reconnect waits the client's 0–20 s delay. While it waits,
 * presence shows the last roster plus every live presence push of the new socket; a member whose
 * state changed during the outage and who sends no push stays stale until the refetch.
 */
internal class PresenceTransportRosterRefetch(
    private val jitter: RaftReconnectRefetchJitter,
    private val invalidate: suspend (owner: ProcessServerMembersOwner, reason: String) -> Unit,
    /** Stores showing the server, read when the refetch runs (a shell may have switched meanwhile). */
    private val storesForServer: (serverId: String) -> List<SharedServerMembersStore>,
    /** False once the owner's binding is gone (sign-out / account switch while waiting). */
    private val isCurrentOwner: (ProcessServerMembersOwner) -> Boolean = { true },
    private val logger: (String) -> Unit = {},
    private val warnLogger: (String) -> Unit = {},
) {
    suspend fun onTransportRebaselined(
        owner: ProcessServerMembersOwner,
        transport: ProcessPresenceTransportEpoch,
        rebaselineResult: ProcessPresenceTransportRebaselineResult,
    ): RaftReconnectRefetchDecision {
        invalidate(owner, REASON)
        val decision =
            jitter.onConnected(
                session = RaftReconnectSession(owner.environmentId, owner.accountId, owner.serverId),
                source = SOURCE,
            ) { refetch(owner, transport, rebaselineResult) }
        if (decision == RaftReconnectRefetchDecision.Immediate) refetch(owner, transport, rebaselineResult)
        return decision
    }

    private suspend fun refetch(
        owner: ProcessServerMembersOwner,
        transport: ProcessPresenceTransportEpoch,
        rebaselineResult: ProcessPresenceTransportRebaselineResult,
    ) {
        if (!isCurrentOwner(owner)) {
            logger("presence_transport_rebaseline serverId=${owner.serverId} epoch=${transport.epoch} result=owner_retired")
            return
        }
        val stores = storesForServer(owner.serverId)
        logger(
            "presence_transport_rebaseline serverId=${owner.serverId} epoch=${transport.epoch} " +
                "result=${rebaselineResult.name} stores=${stores.size}"
        )
        coroutineScope {
            stores.forEach { store ->
                launch {
                    try {
                        // Roster refill first, then peer-server agents of joint channels (not on
                        // this roster; their facts come back from the channel-members refetch).
                        store.refreshAfterPresenceTransportConnected(
                            serverId = ServerId(owner.serverId),
                            transport = transport,
                            reason = REASON
                        )
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

    companion object {
        const val SOURCE = "server_members"
        const val REASON = "presence_transport_connected"
    }
}
