package build.raft.app.data

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal enum class SharedReferenceKind {
    Thread,
    Message,
    Task
}

/**
 * One reference as the resolver remembers it. [scopeId] is the reply-cache namespace (environment,
 * account and server), so a resolution never answers for another account or server.
 */
internal data class SharedReferenceKey(
    val scopeId: String,
    val kind: SharedReferenceKind,
    val conversationId: String,
    val id: String
) {
    companion object {
        fun of(scopeId: String?, kind: SharedReferenceKind, conversationId: String, id: String): SharedReferenceKey? {
            val scope = scopeId?.trim()?.takeIf { it.isNotBlank() } ?: return null
            val conversation = conversationId.trim().takeIf { it.isNotBlank() } ?: return null
            val normalizedId = id.trim().lowercase().takeIf { it.isNotBlank() } ?: return null
            return SharedReferenceKey(scope, kind, conversation, normalizedId)
        }
    }
}

internal enum class SharedReferenceSource {
    /** Data already on the device: channel list, loaded messages, thread summaries, thread cache. */
    Local,
    /** An earlier fetch of the same reference. */
    Resolved,
    /** This open's own fetch. */
    Network
}

/** Why a reference does not open. Every one of them is a toast; none navigates. */
internal enum class SharedReferenceFailure {
    NotFound,
    NoAccess,
    /** The named conversation is not in this server's list (another server's, or one never joined). */
    OtherServer,
    AuthRequired,
    Unavailable
}

internal sealed interface SharedReferenceOutcome<out T : Any> {
    data class Open<T : Any>(val target: T, val source: SharedReferenceSource) : SharedReferenceOutcome<T>

    data class Failed(
        val failure: SharedReferenceFailure,
        /** The answer that failed, for the store's existing result type and error toasts. */
        val result: DataResult<Nothing>
    ) : SharedReferenceOutcome<Nothing>
}

/** One fetch in flight; [actionId] is the chip it was started from (null: no chip). */
internal data class SharedReferenceFetch(
    val actionId: String?,
    val generation: Long
)

internal fun DataResult<Nothing>.sharedReferenceFailure(): SharedReferenceFailure =
    when (this) {
        is DataResult.Empty ->
            if (reason == EmptyReason.NoChannels) SharedReferenceFailure.OtherServer else SharedReferenceFailure.NotFound
        is DataResult.NoPermission ->
            if (blocker.isAuthSessionBlocker()) SharedReferenceFailure.AuthRequired else SharedReferenceFailure.NoAccess
        is DataResult.Error,
        // Not a failure; listed only because the type admits it.
        is DataResult.Content -> SharedReferenceFailure.Unavailable
    }

/**
 * Opens references local first (artin 11-07): a reference the device already knows opens at once
 * with no loading state; only a local miss fetches, and only that fetch is in [inFlight]. Thread,
 * message and task references all go through this one resolver, so the chip spinner is one state
 * bound to the single fetch phase rather than drawn per chip. A fetched resolution is kept, so the
 * next tap on the same reference is local.
 */
internal class SharedReferenceResolver(
    private val capacity: Int = SharedReferenceResolverCapacity
) {
    private val lock = SynchronizedObject()
    private val resolved = LinkedHashMap<SharedReferenceKey, Any>()
    private val generations = atomic(0L)
    private val mutableInFlight = MutableStateFlow<Map<Long, SharedReferenceFetch>>(emptyMap())

    /**
     * Every reference fetch running now, by generation. Pages fetch concurrently, so this is a set,
     * not one slot: a chip is busy iff its own action is in it ([isFetching]).
     */
    val inFlight: StateFlow<Map<Long, SharedReferenceFetch>> = mutableInFlight.asStateFlow()

    fun isFetching(actionId: String?): Boolean = inFlight.value.isFetching(actionId)

    suspend fun <T : Any> resolve(
        key: SharedReferenceKey?,
        actionId: String?,
        local: suspend () -> T?,
        fetch: suspend () -> DataResult<T>,
        /** Whether a fetched target may answer later taps (an incomplete one must not). */
        cacheable: (T) -> Boolean = { true }
    ): SharedReferenceOutcome<T> {
        local()?.let { return SharedReferenceOutcome.Open(it, SharedReferenceSource.Local) }
        key?.let(::cached)?.let { cachedTarget ->
            @Suppress("UNCHECKED_CAST")
            (cachedTarget as? T)?.let { return SharedReferenceOutcome.Open(it, SharedReferenceSource.Resolved) }
        }
        return when (val result = fetchPhase(actionId) { fetch() }) {
            is DataResult.Content -> {
                if (key != null && cacheable(result.data)) remember(key, result.data)
                SharedReferenceOutcome.Open(result.data, SharedReferenceSource.Network)
            }
            is DataResult.Empty -> SharedReferenceOutcome.Failed(result.sharedReferenceFailure(), result)
            is DataResult.Error -> SharedReferenceOutcome.Failed(result.sharedReferenceFailure(), result)
            is DataResult.NoPermission -> SharedReferenceOutcome.Failed(result.sharedReferenceFailure(), result)
        }
    }

    /** Runs [block] as a reference fetch phase: it is in [inFlight] for exactly its duration. */
    suspend fun <R> fetchPhase(actionId: String?, block: suspend () -> R): R {
        val fetch = SharedReferenceFetch(actionId?.trim()?.takeIf { it.isNotBlank() }, generations.incrementAndGet())
        mutableInFlight.update { it + (fetch.generation to fetch) }
        try {
            return block()
        } finally {
            mutableInFlight.update { it - fetch.generation }
        }
    }

    fun cached(key: SharedReferenceKey): Any? = synchronized(lock) { resolved[key] }

    fun remember(key: SharedReferenceKey, target: Any) {
        synchronized(lock) {
            resolved.remove(key)
            resolved[key] = target
            while (resolved.size > capacity) {
                val eldest = resolved.keys.firstOrNull() ?: break
                resolved.remove(eldest)
            }
        }
    }

    fun forget(key: SharedReferenceKey) {
        synchronized(lock) { resolved.remove(key) }
    }

    /** Forgets every kept resolution whose target matches [predicate] (its target is gone or refused). */
    fun forgetTargets(predicate: (SharedReferenceKey, Any) -> Boolean): Int =
        synchronized(lock) {
            val stale = resolved.entries.filter { (key, target) -> predicate(key, target) }.map { it.key }
            stale.forEach(resolved::remove)
            stale.size
        }

    fun clear() {
        synchronized(lock) { resolved.clear() }
    }
}

internal fun Map<Long, SharedReferenceFetch>.isFetching(actionId: String?): Boolean {
    val normalized = actionId?.trim()?.takeIf { it.isNotBlank() } ?: return false
    return values.any { it.actionId == normalized }
}

internal const val SharedReferenceResolverCapacity = 256
