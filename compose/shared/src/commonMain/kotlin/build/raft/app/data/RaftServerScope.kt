package build.raft.app.data

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The (account, server) a shell's server-scoped data belongs to. Two keys are equal exactly when
 * data loaded for one may be shown for the other.
 */
internal data class RaftServerScopeKey(
    val accountId: String,
    val serverId: String?,
)

internal fun StoredAuthSession.raftServerScopeKey(): RaftServerScopeKey =
    RaftServerScopeKey(
        accountId = user.id.trim(),
        serverId = currentServerId?.trim()?.takeIf { it.isNotBlank() },
    )

/**
 * The server scope of this auth store: read once on collection, then re-read after every
 * auth-session event except a token rotation, and emitted only when it changes. `null` while
 * signed out. A read that fails is skipped instead of reported as signed out. Without an event
 * source the flow emits the one read and completes.
 *
 * This is the one server-switch signal for screens and their stores. With a shell auth store
 * ([RaftShellAuthSessionStore]), a selection from the server switcher, drawer or rail emits
 * [AuthSessionEvent.BindingMetadataChanged], so a page that stays on screen across the switch
 * (the wide layout's inline Saved / Computers / Settings, the Search tab) sees the new server
 * here. A store whose content belongs to one server keeps the key it loaded for and, when this
 * flow moves away from it, drops that content and loads again for the new key, as web's
 * `registerServerReset` stores do under `<MainLayout key={server.id}>`.
 *
 * It is also the `ServerSelected` trigger the planned per-server sync engine takes over; until
 * then each server-scoped store follows it itself.
 */
internal fun RaftAuthSessionStore.raftServerScopeFlow(): Flow<RaftServerScopeKey?> {
    suspend fun read(): Result<RaftServerScopeKey?> =
        runCatchingPreservingCancellation { readSession()?.raftServerScopeKey() }
    val events =
        (this as? RaftAuthSessionEventSource)?.authSessionEvents
            // Nothing announces a change: the one read is all there is, and the flow completes.
            ?: return flow { read().onSuccess { emit(it) } }
    val wakes = events.filter { event -> event !is AuthSessionEvent.TokensRotated }
    return channelFlow<RaftServerScopeKey?> {
        val pending = Channel<Unit>(Channel.CONFLATED)
        // Subscribe before the first read so no selection between the two is missed.
        launch(start = CoroutineStart.UNDISPATCHED) { wakes.collect { pending.trySend(Unit) } }
        pending.trySend(Unit)
        for (signal in pending) {
            // A failed read is skipped, not reported as signed out.
            read().onSuccess { send(it) }
        }
    }.distinctUntilChanged()
}

/** The selected server id of [raftServerScopeFlow], for screens that need only the id. */
internal fun RaftAuthSessionStore.raftSelectedServerIdFlow(): Flow<String?> =
    raftServerScopeFlow().map { it?.serverId }.distinctUntilChanged()
