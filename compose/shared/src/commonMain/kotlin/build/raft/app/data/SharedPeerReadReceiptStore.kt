package build.raft.app.data

import build.raft.app.model.ScopeReadUpdated
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Who the cached read receipts belong to. Any change (environment, account, server) drops every
 * scope, like the web's `registerServerReset` + the `serverEpoch` check in `fetchScope`.
 */
data class PeerReadReceiptOwner(
    val environmentId: String,
    val accountId: String,
    val serverId: String,
)

/** Per-scope read receipts of one [owner]; a scope is present only after its hydrate said so. */
data class PeerReadReceiptState(
    val owner: PeerReadReceiptOwner? = null,
    val scopes: Map<String, PeerReadReceiptScope> = emptyMap(),
)

sealed interface PeerReadReceiptIntent {
    /** The viewer's current owner; a different owner (or null) clears every scope. */
    data class BindOwner(val owner: PeerReadReceiptOwner?) : PeerReadReceiptIntent

    /** A hydrate answer for [scopeId]; null [scope] means the server sent none (scope off). */
    data class HydrateLoaded(
        val owner: PeerReadReceiptOwner,
        val scopeId: String,
        val scope: PeerReadReceiptScope?,
    ) : PeerReadReceiptIntent

    /** A `scope_read:updated` peer frame (summaryChanged frames re-fetch instead). */
    data class PeerAdvanced(val owner: PeerReadReceiptOwner, val update: ScopeReadUpdated) : PeerReadReceiptIntent

    /** The flag turned off: web `clearAll`. */
    data object ClearAll : PeerReadReceiptIntent
}

/**
 * Pure reducer. Answers for another owner are stale (the account or server changed while the GET
 * was in flight) and ignored; frames for a scope with no hydrate are dropped (hydrate presence is
 * the gate); out-of-order frames keep the higher watermark. Returns the same instance on a no-op.
 */
fun reducePeerReadReceipts(state: PeerReadReceiptState, intent: PeerReadReceiptIntent): PeerReadReceiptState =
    when (intent) {
        is PeerReadReceiptIntent.BindOwner ->
            if (intent.owner == state.owner) state else PeerReadReceiptState(owner = intent.owner)
        is PeerReadReceiptIntent.ClearAll ->
            if (state.scopes.isEmpty()) state else state.copy(scopes = emptyMap())
        is PeerReadReceiptIntent.HydrateLoaded -> {
            when {
                intent.owner != state.owner -> state
                intent.scope == null ->
                    if (intent.scopeId in state.scopes) state.copy(scopes = state.scopes - intent.scopeId) else state
                else ->
                    state.copy(
                        scopes =
                            state.scopes +
                                (intent.scopeId to mergePeerReadReceiptHydrate(state.scopes[intent.scopeId], intent.scope)),
                    )
            }
        }
        is PeerReadReceiptIntent.PeerAdvanced -> {
            val current = state.scopes[intent.update.scopeId]
            if (intent.owner != state.owner || current == null || intent.update.summaryChanged) {
                state
            } else {
                val next = current.mergePeerReadAdvance(intent.update)
                if (next === current) state else state.copy(scopes = state.scopes + (intent.update.scopeId to next))
            }
        }
    }

/**
 * Read receipts for one shell, mirroring web `useReadReceiptStore`: [hydrate] is single-flight per
 * scope, [consumeScopeUpdated] advances watermarks or, for a summary scope, re-fetches it (after
 * an in-flight hydrate, as the web chains it). A failed fetch leaves the scope as it was.
 */
class SharedPeerReadReceiptStore internal constructor(
    private val scope: CoroutineScope,
    private val fetchHydrate: suspend (PeerReadReceiptOwner, String) -> PeerReadReceiptHydrateResult,
    private val logger: (String) -> Unit = {},
) {
    private val mutableState = MutableStateFlow(PeerReadReceiptState())
    val state: StateFlow<PeerReadReceiptState> = mutableState.asStateFlow()

    private val lock = SynchronizedObject()
    private val inFlight = mutableMapOf<Pair<PeerReadReceiptOwner, String>, Job>()

    fun dispatch(intent: PeerReadReceiptIntent) {
        if (intent is PeerReadReceiptIntent.BindOwner && intent.owner != state.value.owner) {
            synchronized(lock) { inFlight.clear() }
        }
        mutableState.update { reducePeerReadReceipts(it, intent) }
    }

    fun hydrate(owner: PeerReadReceiptOwner, scopeId: String) {
        if (scopeId.isBlank()) return
        val key = owner to scopeId
        synchronized(lock) {
            if (inFlight[key]?.isActive == true) return
            inFlight[key] = launchHydrate(owner, scopeId, after = null)
        }
    }

    fun consumeScopeUpdated(owner: PeerReadReceiptOwner, update: ScopeReadUpdated) {
        val current = state.value
        if (current.owner != owner) return
        val known = current.scopes[update.scopeId] ?: return
        if (!update.summaryChanged) {
            dispatch(PeerReadReceiptIntent.PeerAdvanced(owner, update))
            return
        }
        // A detailed scope ignores summaryChanged (an agent viewer's per-peer hydrate; server note).
        if (known !is PeerReadReceiptScope.Summary) return
        val key = owner to update.scopeId
        synchronized(lock) {
            val running = inFlight[key]?.takeIf { it.isActive }
            inFlight[key] = launchHydrate(owner, update.scopeId, after = running)
        }
    }

    private fun launchHydrate(owner: PeerReadReceiptOwner, scopeId: String, after: Job?): Job =
        scope.launch {
            after?.join()
            when (val result = fetchHydrate(owner, scopeId)) {
                is PeerReadReceiptHydrateResult.Loaded ->
                    dispatch(PeerReadReceiptIntent.HydrateLoaded(owner, scopeId, result.scope))
                is PeerReadReceiptHydrateResult.Failed ->
                    logger("peer_read_receipts_hydrate_failed scope=${scopeId.take(8)} reason=${result.reason}")
            }
        }
}
