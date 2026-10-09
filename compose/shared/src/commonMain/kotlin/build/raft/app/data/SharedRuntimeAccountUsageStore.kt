package build.raft.app.data

import build.raft.app.model.ServerId
import build.raft.kuiklybase.datetime.Clock
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
}

/**
 * Read-only runtime account usage reads (task #137). Mirrors Web `RuntimeAccountUsageClient`
 * for the read path only: a 60 s in-memory cache and request de-duplication per key. There is
 * deliberately no refresh/POST path; [read] with `force = true` simply re-reads the server.
 */
internal class SharedRuntimeAccountUsageStore(
    private val client: SlockComputersApiClient,
    private val clock: SlockEpochMillisSource = SlockEpochMillisSource { Clock.System.now().toEpochMilliseconds() },
    private val cacheMillis: Long = RUNTIME_ACCOUNT_USAGE_CACHE_MILLIS
) {
    private class Cached(val value: RuntimeAccountUsageLoad, val expiresAt: Long)

    private val mutex = Mutex()
    private val cache = mutableMapOf<RuntimeAccountUsageKey, Cached>()
    private val inFlight = mutableMapOf<RuntimeAccountUsageKey, CompletableDeferred<RuntimeAccountUsageLoad>>()

    /** The cached value for [key] if it has not expired; never triggers a request. */
    suspend fun peek(key: RuntimeAccountUsageKey): RuntimeAccountUsageLoad? =
        mutex.withLock { cache[key]?.takeIf { it.expiresAt > clock.nowEpochMillis() }?.value }

    suspend fun read(key: RuntimeAccountUsageKey, force: Boolean = false): RuntimeAccountUsageLoad {
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
                    cache[key] = Cached(result, clock.nowEpochMillis() + cacheMillis)
                    if (inFlight[key] === deferred) inFlight.remove(key)
                }
            }
            deferred.complete(result)
            return result
        }
    }

    /** Drops every cached value, e.g. on sign-out. */
    suspend fun clear() {
        mutex.withLock { cache.clear() }
    }

    private suspend fun fetch(key: RuntimeAccountUsageKey): RuntimeAccountUsageLoad =
        when (val result = client.runtimeAccountUsage(ServerId(key.serverId), key.machineId, key.provider)) {
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

internal const val RUNTIME_ACCOUNT_USAGE_CACHE_MILLIS: Long = 60_000L
