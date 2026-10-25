package build.raft.app.data

import build.raft.app.model.MemberKind
import build.raft.app.model.ServerId
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

/**
 * Display identities of senders no roster of this server carries: a joint channel's people and
 * agents whose home is the other server. Filled on demand from `POST /api/actors/resolve`
 * ([ActorResolveApi]); mirrors web `store/localTables/actorResolve.ts` (slock 808bfe1c4, #11197).
 *
 * Rules:
 * - Scoped per signed-in owner (environment + account) and server; a row of one scope is never read
 *   for another.
 * - Callers ask only for joint-channel senders; a ref this server's members / agents know (deleted
 *   agents too) is never asked, checked again when the batch is sent.
 * - Asked once per scope: refs are batched (one short debounce, chunks of
 *   [ACTOR_RESOLVE_MAX_ACTORS]), deduped, and one batch per scope is in flight at a time. An id the
 *   server leaves out is not asked again; any failure marks the refs asked and is not retried.
 * - 403 (guest) or 404/405/501 (a server without the route) turns the scope off for the session:
 *   current behaviour stays, no error is surfaced.
 * - An answer is applied only while its scope is still the server's current owner and no clear ran
 *   since the request; otherwise it is dropped. An answer equal to the stored rows does not re-emit.
 * - [clear] (sign-out / account retirement) and [clearServer] (lost access) forget rows, asked
 *   refs and disabled scopes.
 */
class SharedJointActorDirectory internal constructor(
    private val coroutineScope: CoroutineScope,
    /** The current owner of [ServerId]'s data, or null while signed out. Synchronous. */
    private val owner: (ServerId) -> ProcessServerMembersOwner?,
    /** True when this server's members / agents (deleted agents too) already know [ActorResolveRef]. */
    private val knownLocally: (ServerId, ActorResolveRef) -> Boolean,
    private val resolve: suspend (ServerId, List<ActorResolveRef>) -> ActorResolveOutcome,
    private val debounceMillis: Long = ACTOR_RESOLVE_DEBOUNCE_MILLIS,
    private val logger: (String) -> Unit = {},
) {
    private data class Scope(
        val environmentId: String,
        val accountId: String,
        val serverId: String,
    )

    private class ScopeRequests {
        val asked = mutableSetOf<String>()
        val pending = LinkedHashMap<String, ActorResolveRef>()
        var flight: Job? = null
        var disabled = false
    }

    private val lock = SynchronizedObject()
    private val requests = mutableMapOf<Scope, ScopeRequests>()
    private val _rows = MutableStateFlow<Map<Scope, Map<String, ResolvedActor>>>(emptyMap())

    /** Rows per scope, keyed by actor id. Exposed for tests and diagnostics. */
    internal val rows: StateFlow<Map<*, Map<String, ResolvedActor>>> get() = _rows.asStateFlow()

    private fun scopeOf(serverId: ServerId): Scope? {
        val cleanServerId = serverId.value.trim().takeIf { it.isNotEmpty() } ?: return null
        val current = owner(ServerId(cleanServerId)) ?: return null
        if (current.serverId.trim() != cleanServerId) return null
        return Scope(current.environmentId.trim(), current.accountId.trim(), cleanServerId)
    }

    private fun Map<Scope, Map<String, ResolvedActor>>.rowFor(scope: Scope?, id: String, kind: MemberKind?): ResolvedActor? {
        val row = scope?.let { this[it] }?.get(id) ?: return null
        return row.takeIf { kind == null || it.kind == kind }
    }

    /** The resolved row for [id] on [serverId] for the current owner, if any. */
    fun current(serverId: ServerId, id: String, kind: MemberKind? = null): ResolvedActor? =
        _rows.value.rowFor(scopeOf(serverId), id.trim(), kind)

    /**
     * The resolved row for [id], asking the server for it when collected and still missing. Emits
     * again only when that row changes.
     */
    fun actorFlow(serverId: ServerId, ref: ActorResolveRef): Flow<ResolvedActor?> {
        val id = ref.id.trim()
        return _rows
            .map { rows -> rows.rowFor(scopeOf(serverId), id, ref.kind) }
            .distinctUntilChanged()
            .onStart { request(serverId, listOf(ref)) }
    }

    /** Asks for the refs this scope has no row for and has not asked yet. Cheap to call repeatedly. */
    fun request(serverId: ServerId, refs: List<ActorResolveRef>) {
        val scope = scopeOf(serverId) ?: return
        synchronized(lock) {
            val state = requests.getOrPut(scope) { ScopeRequests() }
            if (state.disabled) return
            val stored = _rows.value[scope].orEmpty()
            refs.forEach { raw ->
                if (raw.kind != MemberKind.Human && raw.kind != MemberKind.Agent) return@forEach
                val ref = raw.copy(id = raw.id.trim())
                if (!isActorResolveId(ref.id)) return@forEach
                val key = ref.key
                if (key in state.asked || key in state.pending || stored[ref.id]?.kind == ref.kind) return@forEach
                if (knownLocally(ServerId(scope.serverId), ref)) return@forEach
                state.pending[key] = ref
            }
            if (state.pending.isEmpty() || state.flight != null) return
            val flight = coroutineScope.launch(start = CoroutineStart.LAZY) { runFlight(scope, state) }
            state.flight = flight
            flight.start()
        }
    }

    /** The single in-flight batch loop of one scope: debounce, send, repeat while refs queued up. */
    private suspend fun runFlight(scope: Scope, state: ScopeRequests) {
        val serverId = ServerId(scope.serverId)
        val self = currentCoroutineContext()[Job]
        try {
            while (true) {
                delay(debounceMillis)
                val batch =
                    synchronized(lock) {
                        if (!isLive(scope, state)) return
                        val stored = _rows.value[scope].orEmpty()
                        val refs =
                            state.pending.values.filter { ref ->
                                // A roster may have learned it while the batch waited: not asked, so
                                // it stays askable if that roster later drops it.
                                stored[ref.id]?.kind != ref.kind && !knownLocally(serverId, ref)
                            }
                        state.pending.clear()
                        refs.forEach { state.asked += it.key }
                        refs
                    }
                if (batch.isEmpty()) return
                for (chunk in batch.chunked(ACTOR_RESOLVE_MAX_ACTORS)) {
                    val outcome =
                        try {
                            resolve(serverId, chunk)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (error: Throwable) {
                            ActorResolveOutcome.Failed(code = -1)
                        }
                    synchronized(lock) {
                        // A sign-out, account switch or clear since the request: the answer is for
                        // a scope nobody reads now.
                        if (!isLive(scope, state)) return
                        when (outcome) {
                            is ActorResolveOutcome.Resolved -> upsert(scope, outcome.actors)
                            ActorResolveOutcome.Unavailable -> {
                                state.disabled = true
                                state.pending.clear()
                            }
                            is ActorResolveOutcome.Failed -> Unit
                        }
                    }
                    when (outcome) {
                        ActorResolveOutcome.Unavailable -> {
                            logger("actor_resolve_unavailable refs=${chunk.size}")
                            return
                        }
                        is ActorResolveOutcome.Failed ->
                            logger("actor_resolve_failed code=${outcome.code} refs=${chunk.size} retried=false")
                        is ActorResolveOutcome.Resolved -> Unit
                    }
                }
                synchronized(lock) {
                    if (!isLive(scope, state) || state.pending.isEmpty()) return
                }
            }
        } finally {
            synchronized(lock) {
                if (state.flight === self) state.flight = null
            }
        }
    }

    private fun isLive(scope: Scope, state: ScopeRequests): Boolean =
        requests[scope] === state && scopeOf(ServerId(scope.serverId)) == scope

    /** Under [lock]. No row changed: no emission. */
    private fun upsert(scope: Scope, actors: List<ResolvedActor>) {
        if (actors.isEmpty()) return
        val current = _rows.value
        val existing = current[scope].orEmpty()
        val next = existing.toMutableMap()
        actors.forEach { actor -> next[actor.id] = actor }
        if (next == existing) return
        _rows.value = current + (scope to next)
    }

    /** Forgets every row, asked ref, pending batch and disabled scope (sign-out, account retirement). */
    fun clear() {
        synchronized(lock) {
            requests.values.forEach { it.flight?.cancel() }
            requests.clear()
            _rows.value = emptyMap()
        }
    }

    /** Forgets [serverId] for every owner (the person lost access to it, or it was removed). */
    fun clearServer(serverId: ServerId) {
        val key = serverId.value.trim()
        synchronized(lock) {
            val scopes = requests.keys.filter { it.serverId == key }
            scopes.forEach { requests.remove(it)?.flight?.cancel() }
            _rows.value = _rows.value.filterKeys { it.serverId != key }
        }
    }

    internal companion object {
        const val ACTOR_RESOLVE_DEBOUNCE_MILLIS = 50L
    }
}
