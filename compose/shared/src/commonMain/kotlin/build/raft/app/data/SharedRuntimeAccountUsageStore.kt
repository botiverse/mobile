package build.raft.app.data

import build.raft.app.model.ServerId
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Cache identity for one usage read. Every dimension is part of the key so a result can only
 * ever be served back to the exact signed-in identity, server, Computer and provider that
 * requested it — switching any of them never reuses or backfills another subject's data.
 */
internal data class RuntimeAccountUsageKey(
    val owner: SharedComputersOwner,
    val serverId: String,
    val machineId: String,
    val provider: RuntimeAccountUsageProvider
)

internal sealed interface RuntimeAccountUsageLoad {
    data class Loaded(val read: RuntimeAccountUsageRead) : RuntimeAccountUsageLoad

    /** The server refused (403): the viewer may not see this Computer's account usage. */
    data object Forbidden : RuntimeAccountUsageLoad

    data class Failed(val code: String, val retryable: Boolean) : RuntimeAccountUsageLoad

    /**
     * The signed-in identity no longer matches the key's owner (before or after the request).
     * Never cached and never shown: the caller must re-resolve its owner and read again.
     */
    data object Superseded : RuntimeAccountUsageLoad
}

/** Same signed-in identity (environment + account), the guard the bound transport uses after a request. */
internal fun SharedComputersOwner.sameIdentityAs(other: SharedComputersOwner?): Boolean =
    other != null &&
        environmentId.trim().trimEnd('/') == other.environmentId.trim().trimEnd('/') &&
        accountId.trim() == other.accountId.trim()

/** What [SharedRuntimeAccountUsageStore.refresh] got back. */
internal sealed interface RuntimeAccountUsageRefreshLoad {
    data class Done(val refresh: RuntimeAccountUsageRefresh) : RuntimeAccountUsageRefreshLoad

    /** The server refused (403). */
    data object Forbidden : RuntimeAccountUsageRefreshLoad

    data class Failed(val code: String) : RuntimeAccountUsageRefreshLoad

    /** As [RuntimeAccountUsageLoad.Superseded]: the signed-in identity changed; never shown. */
    data object Superseded : RuntimeAccountUsageRefreshLoad
}

/**
 * Runtime account usage reads and refreshes (task #137; refresh artin task #18). Mirrors Web
 * `RuntimeAccountUsageClient`: a 60 s in-memory cache and request de-duplication per key, and a
 * refresh window of 2 minutes per key ([refresh] inside it answers `cooldown` without asking the
 * server). [read] with `force = true` re-reads the server.
 */
internal class SharedRuntimeAccountUsageStore(
    private val client: RaftComputersApiClient,
    /** The identity the network client will actually authenticate as right now. */
    private val currentIdentity: suspend () -> SharedComputersOwner?,
    private val clock: RaftEpochMillisSource = RaftEpochMillisSource { Clock.System.now().toEpochMilliseconds() },
    private val cacheMillis: Long = RUNTIME_ACCOUNT_USAGE_CACHE_MILLIS,
    private val refreshCooldownMillis: Long = RUNTIME_ACCOUNT_USAGE_REFRESH_COOLDOWN_MILLIS
) {
    private class Cached(val value: RuntimeAccountUsageLoad, val expiresAt: Long)

    private val mutex = Mutex()
    private val cache = mutableMapOf<RuntimeAccountUsageKey, Cached>()
    private val inFlight = mutableMapOf<RuntimeAccountUsageKey, CompletableDeferred<RuntimeAccountUsageLoad>>()
    private val refreshStartedAt = mutableMapOf<RuntimeAccountUsageKey, Long>()
    private val refreshesInFlight = mutableMapOf<RuntimeAccountUsageKey, CompletableDeferred<RuntimeAccountUsageRefreshLoad>>()

    /** Milliseconds left before [refresh] asks the server again for [key]; 0 when it may. */
    suspend fun refreshCooldownRemainingMillis(key: RuntimeAccountUsageKey): Long =
        mutex.withLock {
            val started = refreshStartedAt[key] ?: return@withLock 0L
            (refreshCooldownMillis - (clock.nowEpochMillis() - started)).coerceAtLeast(0L)
        }

    /**
     * Asks the Computer for a new snapshot. Inside the refresh window it answers `cooldown`
     * without a request; a refresh already running for [key] is shared. A `fresh` answer's
     * snapshot is written to the read cache, so the next [read] shows it.
     */
    suspend fun refresh(
        key: RuntimeAccountUsageKey,
        reason: RuntimeAccountUsageRefreshReason
    ): RuntimeAccountUsageRefreshLoad {
        if (!key.owner.sameIdentityAs(currentIdentity())) return RuntimeAccountUsageRefreshLoad.Superseded
        val (deferred, owner) =
            mutex.withLock {
                refreshesInFlight[key]?.let { return@withLock it to false }
                val started = refreshStartedAt[key]
                if (started != null && clock.nowEpochMillis() - started < refreshCooldownMillis) {
                    return RuntimeAccountUsageRefreshLoad.Done(
                        RuntimeAccountUsageRefresh(accepted = false, state = RuntimeAccountUsageRefreshState.Cooldown)
                    )
                }
                refreshStartedAt[key] = clock.nowEpochMillis()
                val created = CompletableDeferred<RuntimeAccountUsageRefreshLoad>()
                refreshesInFlight[key] = created
                created to true
            }
        if (!owner) return deferred.await()
        val result =
            try {
                fetchRefresh(key, reason)
            } catch (error: Throwable) {
                withContext(NonCancellable) {
                    mutex.withLock { if (refreshesInFlight[key] === deferred) refreshesInFlight.remove(key) }
                }
                deferred.completeExceptionally(error)
                throw error
            }
        withContext(NonCancellable) {
            mutex.withLock {
                val refreshed = (result as? RuntimeAccountUsageRefreshLoad.Done)?.refresh
                val snapshot = refreshed?.snapshot
                if (snapshot != null && refreshed.state == RuntimeAccountUsageRefreshState.Fresh) {
                    cache[key] =
                        Cached(
                            RuntimeAccountUsageLoad.Loaded(RuntimeAccountUsageRead.Available(stale = false, snapshot = snapshot)),
                            clock.nowEpochMillis() + cacheMillis
                        )
                }
                if (refreshesInFlight[key] === deferred) refreshesInFlight.remove(key)
            }
        }
        deferred.complete(result)
        return result
    }

    /** Drops the cached read for [key], so the next [read] asks the server. */
    suspend fun invalidate(key: RuntimeAccountUsageKey) {
        mutex.withLock { cache.remove(key) }
    }

    private suspend fun fetchRefresh(
        key: RuntimeAccountUsageKey,
        reason: RuntimeAccountUsageRefreshReason
    ): RuntimeAccountUsageRefreshLoad {
        val result = client.refreshRuntimeAccountUsage(ServerId(key.serverId), key.machineId, key.provider, reason)
        if (!key.owner.sameIdentityAs(currentIdentity())) return RuntimeAccountUsageRefreshLoad.Superseded
        return when (result) {
            is DataResult.Content -> RuntimeAccountUsageRefreshLoad.Done(result.data)
            is DataResult.NoPermission ->
                if (result.blocker.code == "server_forbidden") {
                    RuntimeAccountUsageRefreshLoad.Forbidden
                } else {
                    RuntimeAccountUsageRefreshLoad.Failed(result.blocker.code)
                }
            is DataResult.Error -> RuntimeAccountUsageRefreshLoad.Failed(result.error.code)
            is DataResult.Empty -> RuntimeAccountUsageRefreshLoad.Failed("runtime_account_usage_refresh_empty")
        }
    }

    /** The cached value for [key] if it has not expired; never triggers a request. */
    suspend fun peek(key: RuntimeAccountUsageKey): RuntimeAccountUsageLoad? =
        mutex.withLock { cache[key]?.takeIf { it.expiresAt > clock.nowEpochMillis() }?.value }

    suspend fun read(key: RuntimeAccountUsageKey, force: Boolean = false): RuntimeAccountUsageLoad {
        // Not even a cached value is served to an owner that is no longer the signed-in identity.
        if (!key.owner.sameIdentityAs(currentIdentity())) return RuntimeAccountUsageLoad.Superseded
        while (true) {
            val (deferred, owner) = mutex.withLock {
                if (!force) {
                    cache[key]?.takeIf { it.expiresAt > clock.nowEpochMillis() }?.let { return it.value }
                }
                inFlight[key]?.let { return@withLock it to false }
                val created = CompletableDeferred<RuntimeAccountUsageLoad>()
                inFlight[key] = created
                created to true
            }
            if (!owner) {
                try {
                    return deferred.await()
                } catch (_: CancellationException) {
                    // The caller that owned the shared request was cancelled (its subject
                    // changed). If this caller is still active, issue its own read.
                    currentCoroutineContext().ensureActive()
                    continue
                }
            }
            val result =
                try {
                    fetch(key)
                } catch (error: Throwable) {
                    withContext(NonCancellable) {
                        mutex.withLock { if (inFlight[key] === deferred) inFlight.remove(key) }
                    }
                    deferred.completeExceptionally(error)
                    throw error
                }
            withContext(NonCancellable) {
                mutex.withLock {
                    // The result is stored under its own key only; it can never be published
                    // for a different identity/server/Computer/provider.
                    if (result !is RuntimeAccountUsageLoad.Superseded) {
                        cache[key] = Cached(result, clock.nowEpochMillis() + cacheMillis)
                    }
                    if (inFlight[key] === deferred) inFlight.remove(key)
                }
            }
            deferred.complete(result)
            return result
        }
    }

    /** Drops every cached value and refresh window, e.g. on sign-out. */
    suspend fun clear() {
        mutex.withLock {
            cache.clear()
            refreshStartedAt.clear()
        }
    }

    private suspend fun fetch(key: RuntimeAccountUsageKey): RuntimeAccountUsageLoad {
        // The GET authenticates with whatever session is current, so the key's owner must be that
        // session both when the request starts and when its response arrives; otherwise the
        // response may belong to another account and is dropped.
        if (!key.owner.sameIdentityAs(currentIdentity())) return RuntimeAccountUsageLoad.Superseded
        val result = client.runtimeAccountUsage(ServerId(key.serverId), key.machineId, key.provider)
        if (!key.owner.sameIdentityAs(currentIdentity())) return RuntimeAccountUsageLoad.Superseded
        return when (result) {
            is DataResult.Content -> RuntimeAccountUsageLoad.Loaded(result.data)
            is DataResult.NoPermission ->
                if (result.blocker.code == "server_forbidden") {
                    RuntimeAccountUsageLoad.Forbidden
                } else {
                    RuntimeAccountUsageLoad.Failed(result.blocker.code, retryable = false)
                }
            is DataResult.Error -> RuntimeAccountUsageLoad.Failed(result.error.code, result.error.retryable)
            is DataResult.Empty -> RuntimeAccountUsageLoad.Failed("runtime_account_usage_empty", retryable = true)
        }
    }
}

internal const val RUNTIME_ACCOUNT_USAGE_CACHE_MILLIS: Long = 60_000L

/** Web `RUNTIME_ACCOUNT_USAGE_CLIENT_REFRESH_COOLDOWN_MS`: one refresh per key every 2 minutes. */
internal const val RUNTIME_ACCOUNT_USAGE_REFRESH_COOLDOWN_MILLIS: Long = 120_000L
