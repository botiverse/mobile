package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import build.raft.app.model.ServerId
import kotlin.time.Clock
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
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
@RaftCoreInternalApi data class RuntimeAccountUsageKey(
    val owner: SharedComputersOwner,
    val serverId: String,
    val machineId: String,
    val provider: RuntimeAccountUsageProvider
)

@RaftCoreInternalApi sealed interface RuntimeAccountUsageLoad {
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
@RaftCoreInternalApi fun SharedComputersOwner.sameIdentityAs(other: SharedComputersOwner?): Boolean =
    other != null &&
        environmentId.trim().trimEnd('/') == other.environmentId.trim().trimEnd('/') &&
        accountId.trim() == other.accountId.trim()

/** What [SharedRuntimeAccountUsageStore.refresh] got back. */
@RaftCoreInternalApi sealed interface RuntimeAccountUsageRefreshLoad {
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
@RaftCoreInternalApi class SharedRuntimeAccountUsageStore(
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
    /**
     * Optional synchronous owner probe used to fence Frame-1 [peekSync] / [peekAllSync] reads
     * against sign-out, account switches, and session generation changes without suspending.
     */
    private val currentIdentitySync: (() -> SharedComputersOwner?)? = null,
) {
    private val readLimiter = Semaphore(maxConcurrentReads.coerceAtLeast(1))

    private class Cached(val value: RuntimeAccountUsageLoad, val expiresAt: Long)

    private val mutex = Mutex()
    private val cacheLock = SynchronizedObject()
    private var cacheGeneration = 0L
    private val cache = mutableMapOf<RuntimeAccountUsageKey, Cached>()
    private val inFlight = mutableMapOf<RuntimeAccountUsageKey, CompletableDeferred<RuntimeAccountUsageLoad>>()
    private val refreshStartedAt = mutableMapOf<RuntimeAccountUsageKey, Long>()
    private val refreshesInFlight = mutableMapOf<RuntimeAccountUsageKey, CompletableDeferred<RuntimeAccountUsageRefreshLoad>>()
    private class CachedAuthView(val value: RuntimeAuthView?, val expiresAt: Long)
    private val authViews = mutableMapOf<RuntimeAuthViewKey, CachedAuthView>()
    private val authViewGenerations = mutableMapOf<String, Long>()
    private val limitResetAttempts = mutableMapOf<RuntimeAccountUsageKey, RuntimeLimitResetIdempotencyKey>()
    /** Claude: one attempt per kind (web `limitResetAttemptKey(subject + kind)`). */
    private val claudeLimitResetAttempts = mutableMapOf<Pair<RuntimeAccountUsageKey, RuntimeLimitResetKind>, RuntimeLimitResetIdempotencyKey>()

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

    /** Runs the runtime's updater on the Computer; the cached view goes either way (web `finally invalidate`). */
    suspend fun upgradeRuntime(
        owner: SharedComputersOwner,
        serverId: String,
        machineId: String,
        runtime: RuntimeAuthRuntime,
    ): RuntimeAuthCall<RuntimeUpgradeResult> =
        try {
            asOwner(owner) { client.upgradeRuntime(ServerId(serverId), machineId, runtime) }
        } finally {
            withContext(NonCancellable) { invalidateRuntimeAuthView(machineId) }
        }

    /** Reads what installing the runtime would run there; nothing runs and nothing is invalidated. */
    suspend fun runtimeInstallPlan(
        owner: SharedComputersOwner,
        serverId: String,
        machineId: String,
        runtime: RuntimeAuthRuntime,
    ): RuntimeAuthCall<RuntimeInstallPlan> =
        asOwner(owner) { client.runtimeInstallPlan(ServerId(serverId), machineId, runtime) }

    /** Runs the runtime's installer on the Computer; the cached view goes either way (web `finally invalidate`). */
    suspend fun installRuntime(
        owner: SharedComputersOwner,
        serverId: String,
        machineId: String,
        runtime: RuntimeAuthRuntime,
    ): RuntimeAuthCall<RuntimeInstallResult> =
        try {
            asOwner(owner) { client.installRuntime(ServerId(serverId), machineId, runtime) }
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

    /** Saves a provider's API key; a save, or one that may have happened, drops the cached view. */
    suspend fun connectRuntimeProvider(
        owner: SharedComputersOwner,
        serverId: String,
        machineId: String,
        runtime: RuntimeAuthRuntime,
        providerId: String,
        apiKey: String,
        baseUrl: String? = null,
        modelIds: List<String>? = null,
    ): RuntimeAuthCall<RuntimeAuthProviderConnectResult> {
        val result =
            asOwner(owner) {
                client.connectRuntimeProvider(
                    serverId = ServerId(serverId),
                    machineId = machineId,
                    runtime = runtime,
                    providerId = providerId,
                    apiKey = apiKey,
                    baseUrl = baseUrl,
                    modelIds = modelIds,
                )
            }
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

    /** [limitResetAttemptKey] for one kind of Claude reset. */
    suspend fun claudeLimitResetAttemptKey(key: RuntimeAccountUsageKey, kind: RuntimeLimitResetKind): RuntimeLimitResetIdempotencyKey =
        mutex.withLock { claudeLimitResetAttempts.getOrPut(key to kind) { RuntimeLimitResetIdempotencyKey.mint() } }

    /** Any answer ends a Claude attempt (web `handleClaudeOutcome`); only no answer keeps its key. */
    suspend fun forgetClaudeLimitResetAttempt(key: RuntimeAccountUsageKey, kind: RuntimeLimitResetKind) {
        mutex.withLock { claudeLimitResetAttempts.remove(key to kind) }
    }

    /** Reads which Claude limit resets [key]'s account holds; never cached (it opens Claude Code each time). */
    suspend fun readClaudeLimitResets(key: RuntimeAccountUsageKey, consent: Boolean): RuntimeLimitResetsReadLoad {
        if (!key.owner.sameIdentityAs(currentIdentity())) return RuntimeLimitResetsReadLoad.Superseded
        val result = client.readClaudeLimitResets(ServerId(key.serverId), key.machineId, consent)
        if (!key.owner.sameIdentityAs(currentIdentity())) return RuntimeLimitResetsReadLoad.Superseded
        return result
    }

    /**
     * Spends one of [key]'s account limit resets with [idempotencyKey]. The usage the Computer read
     * right after it is written to the read cache, as a fresh manual refresh is, so the next [read]
     * shows it; without one the cached read is dropped, since it may no longer be true.
     */
    suspend fun redeemLimitReset(
        key: RuntimeAccountUsageKey,
        idempotencyKey: RuntimeLimitResetIdempotencyKey,
        /** Claude: the kind the person chose. */
        kind: RuntimeLimitResetKind? = null
    ): RuntimeLimitResetLoad {
        if (!key.owner.sameIdentityAs(currentIdentity())) return RuntimeLimitResetLoad.Superseded
        val writeGeneration = synchronized(cacheLock) { cacheGeneration }
        val result = client.redeemRuntimeLimitReset(ServerId(key.serverId), key.machineId, key.provider, idempotencyKey, kind)
        if (!key.owner.sameIdentityAs(currentIdentity())) return RuntimeLimitResetLoad.Superseded
        withContext(NonCancellable) {
            mutex.withLock {
                val snapshot = (result as? RuntimeLimitResetLoad.Done)?.reply?.snapshot
                synchronized(cacheLock) {
                    if (snapshot != null && cacheGeneration == writeGeneration) {
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
        val writeGeneration = synchronized(cacheLock) { cacheGeneration }
        val (deferred, owner, startedAt) =
            mutex.withLock {
                refreshesInFlight[key]?.let { return@withLock Triple(it, false, 0L) }
                val started = refreshStartedAt[key]
                if (started != null && clock.nowEpochMillis() - started < refreshCooldownMillis) {
                    return RuntimeAccountUsageRefreshLoad.Done(
                        RuntimeAccountUsageRefresh(accepted = false, state = RuntimeAccountUsageRefreshState.Cooldown)
                    )
                }
                val now = clock.nowEpochMillis()
                refreshStartedAt[key] = now
                val created = CompletableDeferred<RuntimeAccountUsageRefreshLoad>()
                refreshesInFlight[key] = created
                Triple(created, true, now)
            }
        if (!owner) return deferred.await()
        val result =
            try {
                fetchRefresh(key, reason)
            } catch (cancellation: CancellationException) {
                withContext(NonCancellable) {
                    mutex.withLock {
                        // #587 site 6 (slock #16501): a refresh that brought back no reading gives its
                        // 2-minute client window back so Retry asks again immediately.
                        if (refreshStartedAt[key] == startedAt) refreshStartedAt.remove(key)
                        if (refreshesInFlight[key] === deferred) refreshesInFlight.remove(key)
                    }
                }
                deferred.cancel(cancellation)
                throw cancellation
            } catch (_: Throwable) {
                RuntimeAccountUsageRefreshLoad.Failed("runtime_account_usage_refresh_network")
            }
        withContext(NonCancellable) {
            mutex.withLock {
                val refreshed = (result as? RuntimeAccountUsageRefreshLoad.Done)?.refresh
                val snapshot = refreshed?.snapshot
                if (snapshot != null && refreshed.state == RuntimeAccountUsageRefreshState.Fresh) {
                    synchronized(cacheLock) {
                        if (cacheGeneration == writeGeneration) {
                            cache[key] =
                                Cached(
                                    RuntimeAccountUsageLoad.Loaded(RuntimeAccountUsageRead.Available(stale = false, snapshot = snapshot)),
                                    clock.nowEpochMillis() + cacheMillis
                                )
                        }
                    }
                }
                val giveWindowBack =
                    result !is RuntimeAccountUsageRefreshLoad.Done ||
                        refreshed?.state == RuntimeAccountUsageRefreshState.Timeout ||
                        refreshed?.state == RuntimeAccountUsageRefreshState.ComputerOffline
                if (giveWindowBack && refreshStartedAt[key] == startedAt) {
                    refreshStartedAt.remove(key)
                }
                if (refreshesInFlight[key] === deferred) refreshesInFlight.remove(key)
            }
        }
        deferred.complete(result)
        return result
    }

    /** Drops the cached read for [key], so the next [read] asks the server. */
    suspend fun invalidate(key: RuntimeAccountUsageKey) {
        mutex.withLock { synchronized(cacheLock) { cache.remove(key) } }
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

    private fun SharedComputersOwner.matchesSyncOwner(live: SharedComputersOwner?): Boolean =
        live != null && sameIdentityAs(live) && sessionGeneration == live.sessionGeneration

    /**
     * Synchronous Frame-1 peek of [key]; never suspends or triggers a network request.
     * Fences strictly against [currentIdentitySync] (environment + account + session generation)
     * when configured. When [allowExpiredLoaded] is true, returns a previously-loaded snapshot
     * past the 60 s refresh TTL so cards paint warm while refreshing in the background (never
     * returns an expired failure or 403).
     */
    fun peekSync(
        key: RuntimeAccountUsageKey,
        allowExpiredLoaded: Boolean = false,
    ): RuntimeAccountUsageLoad? {
        val liveProbe = currentIdentitySync
        if (liveProbe != null && !key.owner.matchesSyncOwner(liveProbe())) return null
        val now = clock.nowEpochMillis()
        return synchronized(cacheLock) {
            val cached = cache[key] ?: return@synchronized null
            if (cached.expiresAt > now) {
                cached.value.takeUnless { it is RuntimeAccountUsageLoad.Superseded }
            } else if (allowExpiredLoaded && cached.value is RuntimeAccountUsageLoad.Loaded) {
                cached.value
            } else {
                null
            }
        }
    }

    /** Batch synchronous Frame-1 peek for [keys] under one lock acquisition; never starts a request. */
    fun peekAllSync(
        keys: Collection<RuntimeAccountUsageKey>,
        allowExpiredLoaded: Boolean = false,
    ): Map<RuntimeAccountUsageKey, RuntimeAccountUsageLoad> {
        if (keys.isEmpty()) return emptyMap()
        val liveProbe = currentIdentitySync
        val liveOwner = if (liveProbe != null) liveProbe() ?: return emptyMap() else null
        val now = clock.nowEpochMillis()
        return synchronized(cacheLock) {
            val out = LinkedHashMap<RuntimeAccountUsageKey, RuntimeAccountUsageLoad>()
            for (key in keys) {
                if (liveProbe != null && !key.owner.matchesSyncOwner(liveOwner)) continue
                val cached = cache[key] ?: continue
                val value =
                    if (cached.expiresAt > now) {
                        cached.value.takeUnless { it is RuntimeAccountUsageLoad.Superseded }
                    } else if (allowExpiredLoaded && cached.value is RuntimeAccountUsageLoad.Loaded) {
                        cached.value
                    } else {
                        null
                    }
                if (value != null) out[key] = value
            }
            out
        }
    }

    /** The cached value for [key] if it has not expired; never triggers a request. */
    suspend fun peek(key: RuntimeAccountUsageKey): RuntimeAccountUsageLoad? =
        mutex.withLock {
            val now = clock.nowEpochMillis()
            synchronized(cacheLock) { cache[key]?.takeIf { it.expiresAt > now }?.value }
        }

    suspend fun read(key: RuntimeAccountUsageKey, force: Boolean = false): RuntimeAccountUsageLoad {
        // Not even a cached value is served to an owner that is no longer the signed-in identity.
        if (!key.owner.sameIdentityAs(currentIdentity())) return RuntimeAccountUsageLoad.Superseded
        while (true) {
            val (deferred, owner, writeGeneration) = mutex.withLock {
                if (!force) {
                    val now = clock.nowEpochMillis()
                    synchronized(cacheLock) { cache[key]?.takeIf { it.expiresAt > now }?.value }?.let { return it }
                }
                inFlight[key]?.let { return@withLock Triple(it, false, 0L) }
                val created = CompletableDeferred<RuntimeAccountUsageLoad>()
                inFlight[key] = created
                val gen = synchronized(cacheLock) { cacheGeneration }
                Triple(created, true, gen)
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
                    // for a different identity/server/Computer/provider or after a clear/drop.
                    if (result !is RuntimeAccountUsageLoad.Superseded) {
                        synchronized(cacheLock) {
                            if (cacheGeneration == writeGeneration) {
                                cache[key] = Cached(result, clock.nowEpochMillis() + cacheMillis)
                            }
                        }
                    }
                    if (inFlight[key] === deferred) inFlight.remove(key)
                }
            }
            deferred.complete(result)
            return result
        }
    }

    /**
     * Synchronously clears all cached usage snapshots and bumps the write fence generation so any
     * in-flight read from before sign-out / account switch cannot repopulate the cache.
     */
    fun clearSync() {
        synchronized(cacheLock) {
            cacheGeneration += 1L
            cache.clear()
        }
    }

    /**
     * Synchronously drops every cached usage snapshot for [serverId] (e.g. on lost server access)
     * and bumps the write fence generation so late responses for that server cannot land.
     */
    fun dropServerSync(serverId: String) {
        val clean = serverId.trim()
        synchronized(cacheLock) {
            cacheGeneration += 1L
            cache.keys.removeAll { it.serverId == clean }
        }
    }

    /** Drops every cached value and refresh window, e.g. on sign-out. */
    suspend fun clear() {
        mutex.withLock {
            clearSync()
            refreshStartedAt.clear()
            authViews.clear()
            limitResetAttempts.clear()
            claudeLimitResetAttempts.clear()
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

@RaftCoreInternalApi const val RUNTIME_ACCOUNT_USAGE_CACHE_MILLIS: Long = 60_000L

/** Web `RUNTIME_AUTH_CLIENT_CACHE_MS`. */
@RaftCoreInternalApi const val RUNTIME_AUTH_VIEW_CACHE_MILLIS: Long = 30_000L

/** Usage GETs in flight at once ([SharedRuntimeAccountUsageStore]); Tenny's rack request rules. */
@RaftCoreInternalApi const val RUNTIME_ACCOUNT_USAGE_MAX_CONCURRENT_READS: Int = 4

/** Web `RUNTIME_ACCOUNT_USAGE_CLIENT_REFRESH_COOLDOWN_MS`: one refresh per key every 2 minutes. */
@RaftCoreInternalApi const val RUNTIME_ACCOUNT_USAGE_REFRESH_COOLDOWN_MILLIS: Long = 120_000L
