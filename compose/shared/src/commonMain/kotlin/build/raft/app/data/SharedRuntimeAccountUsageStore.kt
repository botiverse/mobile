package build.raft.app.data

import build.raft.app.model.ServerId
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
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
 *
 * At most [maxConcurrentReads] usage GETs are in flight at once, across every key (the Computers
 * page reads one per provider per computer; Tenny's request rules for the rack, 4); the rest
 * wait for a slot. Refresh POSTs are not limited here: the person asks for each one.
 */
internal class SharedRuntimeAccountUsageStore(
    private val client: RaftComputersApiClient,
    /** The identity the network client will actually authenticate as right now. */
    private val currentIdentity: suspend () -> SharedComputersOwner?,
    private val clock: RaftEpochMillisSource = RaftEpochMillisSource { Clock.System.now().toEpochMilliseconds() },
    private val cacheMillis: Long = RUNTIME_ACCOUNT_USAGE_CACHE_MILLIS,
    private val refreshCooldownMillis: Long = RUNTIME_ACCOUNT_USAGE_REFRESH_COOLDOWN_MILLIS,
    maxConcurrentReads: Int = RUNTIME_ACCOUNT_USAGE_MAX_CONCURRENT_READS,
    /**
     * Outlives every card: a sign-in's cancel is sent from here, so leaving the page (an account or
     * server switch, an activity recreated, the card scrolled away) still ends the login on the
     * Computer instead of holding its runtime for the login's 20 min TTL.
     */
    private val detachedScope: CoroutineScope = CoroutineScope(SupervisorJob()),
) {
    private val readLimiter = Semaphore(maxConcurrentReads.coerceAtLeast(1))

    private class Cached(val value: RuntimeAccountUsageLoad, val expiresAt: Long)

    private val mutex = Mutex()
    private val cache = mutableMapOf<RuntimeAccountUsageKey, Cached>()
    private val inFlight = mutableMapOf<RuntimeAccountUsageKey, CompletableDeferred<RuntimeAccountUsageLoad>>()
    private val refreshStartedAt = mutableMapOf<RuntimeAccountUsageKey, Long>()
    private val refreshesInFlight = mutableMapOf<RuntimeAccountUsageKey, CompletableDeferred<RuntimeAccountUsageRefreshLoad>>()
    private class CachedAuthView(val value: RuntimeAuthView?, val expiresAt: Long)
    private val authViews = mutableMapOf<RuntimeAuthViewKey, CachedAuthView>()
    private val authViewGenerations = mutableMapOf<String, Long>()
    private val limitResetAttempts = mutableMapOf<RuntimeAccountUsageKey, RuntimeLimitResetIdempotencyKey>()

    /** One Computer's runtime-auth view per signed-in identity (web `RuntimeAuthClient` keys it by server and Computer). */
    private data class RuntimeAuthViewKey(val owner: SharedComputersOwner, val serverId: String, val machineId: String)

    /** [runtimeAuthView] for a usage card's subject. */
    suspend fun runtimeAuthView(key: RuntimeAccountUsageKey, force: Boolean = false): RuntimeAuthView? =
        runtimeAuthView(key.owner, key.serverId, key.machineId, force)

    /**
     * Who may act on the Computer's runtimes and their sign-in status (web `RuntimeAuthClient.read`,
     * cached 30 s per identity, server and Computer). Null when the read failed or was refused (403:
     * the viewer may not see them), so nobody is offered an action the server would refuse; a
     * failure is not cached. A read that an [invalidateRuntimeAuthView] overtook is not cached.
     */
    suspend fun runtimeAuthView(
        owner: SharedComputersOwner,
        serverId: String,
        machineId: String,
        force: Boolean = false,
    ): RuntimeAuthView? {
        if (!owner.sameIdentityAs(currentIdentity())) return null
        val viewKey = RuntimeAuthViewKey(owner, serverId, machineId)
        val generation =
            mutex.withLock {
                if (!force) authViews[viewKey]?.takeIf { it.expiresAt > clock.nowEpochMillis() }?.let { return it.value }
                authViewGenerations[machineId] ?: 0L
            }
        // A transport that fails the read offers no action and leaves the card as it is.
        val result =
            try {
                client.runtimeAuthView(ServerId(serverId), machineId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return null
            }
        if (!owner.sameIdentityAs(currentIdentity())) return null
        val view =
            when (result) {
                is DataResult.Content -> result.data
                // 403: this viewer may not act; remembered like a view, as it will not change soon.
                is DataResult.NoPermission -> if (result.blocker.code == "server_forbidden") null else return null
                else -> return null
            }
        mutex.withLock {
            if ((authViewGenerations[machineId] ?: 0L) == generation) {
                authViews[viewKey] = CachedAuthView(view, clock.nowEpochMillis() + RUNTIME_AUTH_VIEW_CACHE_MILLIS)
            }
        }
        return view
    }

    /**
     * The Computer's sign-in status may have changed (`machine:runtime_auth:updated`, a login or a
     * logout): every identity's and server's cached view of [machineId] goes (the event names only
     * the Computer), and a read already running is not cached.
     */
    suspend fun invalidateRuntimeAuthView(machineId: String) {
        mutex.withLock {
            authViewGenerations[machineId] = (authViewGenerations[machineId] ?: 0L) + 1
            authViews.keys.removeAll { it.machineId == machineId }
        }
    }

    /** Starts a login on the Computer for the signed-in [owner] only. */
    suspend fun startRuntimeLogin(
        owner: SharedComputersOwner,
        serverId: String,
        machineId: String,
        runtime: RuntimeAuthRuntime,
        region: RuntimeAuthLoginRegion? = null,
        providerId: String? = null,
        oauthMethod: String? = null,
    ): RuntimeAuthCall<String> =
        asOwner(owner) { client.startRuntimeLogin(ServerId(serverId), machineId, runtime, region, providerId, oauthMethod) }

    suspend fun submitRuntimeLoginCode(
        owner: SharedComputersOwner,
        serverId: String,
        machineId: String,
        runtime: RuntimeAuthRuntime,
        loginId: String,
        code: String,
    ): RuntimeAuthCall<Unit> =
        asOwner(owner) { client.submitRuntimeLoginCode(ServerId(serverId), machineId, runtime, loginId, code) }

    /** Ends a login this app started; nothing waits on its answer, and a failure changes nothing. */
    suspend fun cancelRuntimeLogin(owner: SharedComputersOwner, serverId: String, machineId: String, runtime: RuntimeAuthRuntime, loginId: String) {
        if (!owner.sameIdentityAs(currentIdentity())) return
        try {
            client.cancelRuntimeLogin(ServerId(serverId), machineId, runtime, loginId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The Computer's own bound ends the login.
        }
    }

    /**
     * [cancelRuntimeLogin] on [detachedScope], not the caller's: the card that asks may be leaving
     * composition, and its scope is cancelled right after.
     */
    fun cancelRuntimeLoginDetached(owner: SharedComputersOwner, serverId: String, machineId: String, runtime: RuntimeAuthRuntime, loginId: String) {
        detachedScope.launch(NonCancellable) { cancelRuntimeLogin(owner, serverId, machineId, runtime, loginId) }
    }

    /** Signs the runtime out; the cached view of the Computer goes either way (web `finally invalidate`). */
    suspend fun logoutRuntime(
        owner: SharedComputersOwner,
        serverId: String,
        machineId: String,
        runtime: RuntimeAuthRuntime,
        providerId: String? = null,
    ): RuntimeAuthCall<RuntimeAuthLogoutResult> =
        try {
            asOwner(owner) { client.logoutRuntime(ServerId(serverId), machineId, runtime, providerId) }
        } finally {
            withContext(NonCancellable) { invalidateRuntimeAuthView(machineId) }
        }

    suspend fun runtimeAuthProviders(
        owner: SharedComputersOwner,
        serverId: String,
        machineId: String,
        runtime: RuntimeAuthRuntime,
    ): RuntimeAuthCall<RuntimeAuthProvidersResult> =
        asOwner(owner) { client.runtimeAuthProviders(ServerId(serverId), machineId, runtime) }

    /** Saves a Pi provider's key; a save, or one that may have happened, drops the cached view. */
    suspend fun connectRuntimeProvider(
        owner: SharedComputersOwner,
        serverId: String,
        machineId: String,
        runtime: RuntimeAuthRuntime,
        providerId: String,
        apiKey: String,
    ): RuntimeAuthCall<RuntimeAuthProviderConnectResult> {
        val result = asOwner(owner) { client.connectRuntimeProvider(ServerId(serverId), machineId, runtime, providerId, apiKey) }
        val value = (result as? RuntimeAuthCall.Done)?.value
        if (value == RuntimeAuthProviderConnectResult.Connected || (value as? RuntimeAuthProviderConnectResult.Failed)?.reason == "timed_out") {
            invalidateRuntimeAuthView(machineId)
        }
        return result
    }

    /** Runs [call] only for the signed-in [owner], and drops its answer when the identity changed meanwhile. */
    private suspend fun <T> asOwner(owner: SharedComputersOwner, call: suspend () -> RuntimeAuthCall<T>): RuntimeAuthCall<T> {
        if (!owner.sameIdentityAs(currentIdentity())) return RuntimeAuthCall.Superseded
        val result =
            try {
                call()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                RuntimeAuthCall.Refused(RuntimeAuthRequestError(status = null, code = null))
            }
        return if (owner.sameIdentityAs(currentIdentity())) result else RuntimeAuthCall.Superseded
    }

    /**
     * The idempotency key of [key]'s current limit-reset attempt (web `limitResetAttemptKey`): the
     * one kept from an attempt whose outcome was never learned, else a new one, kept until
     * [forgetLimitResetAttempt].
     */
    suspend fun limitResetAttemptKey(key: RuntimeAccountUsageKey): RuntimeLimitResetIdempotencyKey =
        mutex.withLock { limitResetAttempts.getOrPut(key) { RuntimeLimitResetIdempotencyKey.mint() } }

    /** The attempt settled (web `forgetLimitResetAttemptKey`): the next click is a new attempt. */
    suspend fun forgetLimitResetAttempt(key: RuntimeAccountUsageKey) {
        mutex.withLock { limitResetAttempts.remove(key) }
    }

    /**
     * Spends one of [key]'s account limit resets with [idempotencyKey]. The usage the Computer read
     * right after it is written to the read cache, as a fresh manual refresh is, so the next [read]
     * shows it; without one the cached read is dropped, since it may no longer be true.
     */
    suspend fun redeemLimitReset(
        key: RuntimeAccountUsageKey,
        idempotencyKey: RuntimeLimitResetIdempotencyKey
    ): RuntimeLimitResetLoad {
        if (!key.owner.sameIdentityAs(currentIdentity())) return RuntimeLimitResetLoad.Superseded
        val result = client.redeemRuntimeLimitReset(ServerId(key.serverId), key.machineId, key.provider, idempotencyKey)
        if (!key.owner.sameIdentityAs(currentIdentity())) return RuntimeLimitResetLoad.Superseded
        withContext(NonCancellable) {
            mutex.withLock {
                val snapshot = (result as? RuntimeLimitResetLoad.Done)?.reply?.snapshot
                if (snapshot != null) {
                    cache[key] =
                        Cached(
                            RuntimeAccountUsageLoad.Loaded(RuntimeAccountUsageRead.Available(stale = false, snapshot = snapshot)),
                            clock.nowEpochMillis() + cacheMillis
                        )
                } else {
                    cache.remove(key)
                }
            }
        }
        return result
    }

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
            authViews.clear()
            limitResetAttempts.clear()
        }
    }

    private suspend fun fetch(key: RuntimeAccountUsageKey): RuntimeAccountUsageLoad {
        // The GET authenticates with whatever session is current, so the key's owner must be that
        // session both when the request starts and when its response arrives; otherwise the
        // response may belong to another account and is dropped.
        if (!key.owner.sameIdentityAs(currentIdentity())) return RuntimeAccountUsageLoad.Superseded
        val result = readLimiter.withPermit { client.runtimeAccountUsage(ServerId(key.serverId), key.machineId, key.provider) }
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

/** Web `RUNTIME_AUTH_CLIENT_CACHE_MS`. */
internal const val RUNTIME_AUTH_VIEW_CACHE_MILLIS: Long = 30_000L

/** Usage GETs in flight at once ([SharedRuntimeAccountUsageStore]); Tenny's rack request rules. */
internal const val RUNTIME_ACCOUNT_USAGE_MAX_CONCURRENT_READS: Int = 4

/** Web `RUNTIME_ACCOUNT_USAGE_CLIENT_REFRESH_COOLDOWN_MS`: one refresh per key every 2 minutes. */
internal const val RUNTIME_ACCOUNT_USAGE_REFRESH_COOLDOWN_MILLIS: Long = 120_000L
