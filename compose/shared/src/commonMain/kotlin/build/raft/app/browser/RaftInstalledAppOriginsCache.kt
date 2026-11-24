package build.raft.app

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeSource

/*
 * Per-server in-memory cache of installed Raft App origins (`GET integrations/clients` + installed
 * rows of `GET integrations/marketplace`, #proj-workspace:#356 Decision #9).
 *
 * Used so installed Raft App links in chat (plain/labelled markdown links and cross-origin App
 * link card buttons such as Stamp's "Review in Stamp" on a GitHub PR card) promote directly to
 * Layer 2 `openAppEntry` (Default WebView profile, bypassing LinkOpenMode and Android Chromium
 * MULTI_PROFILE) with 0ms synchronous hits once warmed, and a bounded coalesced lookup on cold
 * cache. Fails closed (returns null / falls back to ordinary Layer 3 / external link) on any
 * mismatch, missing scope, timeout, or network error.
 */

internal const val RAFT_INSTALLED_APP_ORIGINS_CACHE_TTL_MS: Long = 5 * 60 * 1000L
internal const val RAFT_INSTALLED_APP_LOOKUP_TIMEOUT_MS: Long = 1_500L

internal data class RaftInstalledAppScope(
    val accountId: String,
    val environmentKey: String,
    val serverId: String,
)

internal sealed interface RaftInstalledAppSyncLookup {
    data class Matched(
        val match: RaftInstalledAppOriginMatch,
        val serverId: String,
    ) : RaftInstalledAppSyncLookup

    data object KnownNotInstalled : RaftInstalledAppSyncLookup

    data object NotLoaded : RaftInstalledAppSyncLookup
}

internal class RaftInstalledAppOriginsCache(
    private val fetchOrigins: suspend (serverId: String) -> RaftInstalledAppOriginsFetchResult?,
    private val scope: CoroutineScope,
    private val ttlMillis: Long = RAFT_INSTALLED_APP_ORIGINS_CACHE_TTL_MS,
    private val defaultLookupTimeoutMillis: Long = RAFT_INSTALLED_APP_LOOKUP_TIMEOUT_MS,
    private val nowMillis: () -> Long = defaultInstalledAppMonotonicNowMillis(),
) {
    private data class CacheKey(
        val accountId: String,
        val environmentKey: String,
        val serverId: String,
    )

    private data class CacheEntry(
        val origins: Map<String, RaftInstalledAppOriginMatch>,
        val expiresAtMs: Long,
    )

    private class InFlightFetch(
        val token: Long,
        val deferred: Deferred<RaftInstalledAppOriginsFetchResult?>,
    )

    private val lock = SynchronizedObject()
    private var activeAccountEnv: Pair<String, String>? = null
    private var globalGeneration: Long = 0L
    private var nextFetchToken: Long = 0L
    private val serverGenerations = HashMap<String, Long>()
    private val entries = HashMap<CacheKey, CacheEntry>()
    private val inFlight = HashMap<CacheKey, InFlightFetch>()

    fun lookupSync(
        appScope: RaftInstalledAppScope,
        targetOrigin: String,
    ): RaftInstalledAppSyncLookup {
        val key = appScope.normalizedKeyOrNull() ?: return RaftInstalledAppSyncLookup.KnownNotInstalled
        val normalizedOrigin = raftAppEntryOriginOf(targetOrigin)?.takeIf { it == targetOrigin }
            ?: return RaftInstalledAppSyncLookup.KnownNotInstalled
        var toCancel: List<Deferred<*>> = emptyList()
        val result =
            synchronized(lock) {
                toCancel = resetIfAccountOrEnvironmentChangedLocked(key.accountId, key.environmentKey)
                val entry = entries[key] ?: return@synchronized RaftInstalledAppSyncLookup.NotLoaded
                if (nowMillis() >= entry.expiresAtMs) {
                    entries.remove(key)
                    return@synchronized RaftInstalledAppSyncLookup.NotLoaded
                }
                val matched = entry.origins[normalizedOrigin]
                if (matched != null && matched.origin == normalizedOrigin) {
                    RaftInstalledAppSyncLookup.Matched(match = matched, serverId = key.serverId)
                } else {
                    RaftInstalledAppSyncLookup.KnownNotInstalled
                }
            }
        toCancel.forEach { it.cancel() }
        return result
    }

    fun warm(appScope: RaftInstalledAppScope) {
        val key = appScope.normalizedKeyOrNull() ?: return
        var toCancel: List<Deferred<*>> = emptyList()
        var toStart: Deferred<RaftInstalledAppOriginsFetchResult?>? = null
        synchronized(lock) {
            toCancel = resetIfAccountOrEnvironmentChangedLocked(key.accountId, key.environmentKey)
            val existing = entries[key]
            if (existing != null && nowMillis() < existing.expiresAtMs) {
                return@synchronized
            }
            if (existing != null) entries.remove(key)
            if (key !in inFlight) {
                toStart = createInFlightLocked(key).deferred
            }
        }
        toCancel.forEach { it.cancel() }
        toStart?.start()
    }

    suspend fun lookup(
        appScope: RaftInstalledAppScope,
        targetOrigin: String,
        timeoutMillis: Long = defaultLookupTimeoutMillis,
    ): RaftInstalledAppOriginMatch? {
        val key = appScope.normalizedKeyOrNull() ?: return null
        val normalizedOrigin = raftAppEntryOriginOf(targetOrigin)?.takeIf { it == targetOrigin } ?: return null
        var toCancel: List<Deferred<*>> = emptyList()
        var toStart: Deferred<RaftInstalledAppOriginsFetchResult?>? = null
        var deferred: Deferred<RaftInstalledAppOriginsFetchResult?>? = null
        var cachedResult: RaftInstalledAppOriginMatch? = null
        var cacheHit = false

        synchronized(lock) {
            toCancel = resetIfAccountOrEnvironmentChangedLocked(key.accountId, key.environmentKey)
            val existing = entries[key]
            if (existing != null && nowMillis() < existing.expiresAtMs) {
                cachedResult = existing.origins[normalizedOrigin]?.takeIf { it.origin == normalizedOrigin }
                cacheHit = true
            } else {
                if (existing != null) entries.remove(key)
                val currentInFlight = inFlight[key]
                if (currentInFlight != null) {
                    deferred = currentInFlight.deferred
                } else {
                    val created = createInFlightLocked(key)
                    deferred = created.deferred
                    toStart = created.deferred
                }
            }
        }
        toCancel.forEach { it.cancel() }
        if (cacheHit) return cachedResult
        toStart?.start()
        val activeDeferred = deferred ?: return null
        val fetchResult =
            try {
                if (timeoutMillis > 0L) {
                    withTimeoutOrNull(timeoutMillis) { activeDeferred.await() }
                } else {
                    activeDeferred.await()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
        return fetchResult?.origins?.get(normalizedOrigin)?.takeIf { it.origin == normalizedOrigin }
    }

    fun onAccountOrEnvironmentChanged(
        accountId: String?,
        environmentKey: String?,
    ) {
        val cleanAccount = accountId?.trim()?.takeIf { it.isNotEmpty() }
        val cleanEnv = environmentKey?.trim()?.takeIf { it.isNotEmpty() }
        val toCancel =
            synchronized(lock) {
                val next = if (cleanAccount != null && cleanEnv != null) cleanAccount to cleanEnv else null
                if (next == null || (activeAccountEnv != null && activeAccountEnv != next)) {
                    clearLocked().also { activeAccountEnv = next }
                } else {
                    emptyList()
                }
            }
        toCancel.forEach { it.cancel() }
    }

    fun dropServer(serverId: String) {
        val cleanServer = serverId.trim().takeIf { it.isNotEmpty() } ?: return
        val toCancel =
            synchronized(lock) {
                serverGenerations[cleanServer] = (serverGenerations[cleanServer] ?: 0L) + 1L
                val entryIterator = entries.keys.iterator()
                while (entryIterator.hasNext()) {
                    if (entryIterator.next().serverId == cleanServer) {
                        entryIterator.remove()
                    }
                }
                val cancelled = mutableListOf<Deferred<*>>()
                val flightIterator = inFlight.entries.iterator()
                while (flightIterator.hasNext()) {
                    val item = flightIterator.next()
                    if (item.key.serverId == cleanServer) {
                        cancelled += item.value.deferred
                        flightIterator.remove()
                    }
                }
                cancelled
            }
        toCancel.forEach { it.cancel() }
    }

    fun clearAll() {
        val toCancel =
            synchronized(lock) {
                activeAccountEnv = null
                clearLocked()
            }
        toCancel.forEach { it.cancel() }
    }

    private fun resetIfAccountOrEnvironmentChangedLocked(
        accountId: String,
        environmentKey: String,
    ): List<Deferred<*>> {
        val next = accountId to environmentKey
        val current = activeAccountEnv
        if (current != null && current != next) {
            val cancelled = clearLocked()
            activeAccountEnv = next
            return cancelled
        }
        if (current == null) {
            activeAccountEnv = next
        }
        return emptyList()
    }

    private fun clearLocked(): List<Deferred<*>> {
        globalGeneration += 1L
        entries.clear()
        val cancelled = inFlight.values.map { it.deferred }
        inFlight.clear()
        return cancelled
    }

    private fun createInFlightLocked(key: CacheKey): InFlightFetch {
        nextFetchToken += 1L
        val token = nextFetchToken
        val capturedGlobalGen = globalGeneration
        val capturedServerGen = serverGenerations[key.serverId] ?: 0L
        val deferred =
            scope.async(start = CoroutineStart.LAZY) {
                var result: RaftInstalledAppOriginsFetchResult? = null
                try {
                    result =
                        try {
                            fetchOrigins(key.serverId)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            null
                        }
                    if (result != null && result.complete) {
                        synchronized(lock) {
                            val scopeStillValid =
                                globalGeneration == capturedGlobalGen &&
                                    (serverGenerations[key.serverId] ?: 0L) == capturedServerGen &&
                                    activeAccountEnv == (key.accountId to key.environmentKey)
                            if (scopeStillValid) {
                                entries[key] =
                                    CacheEntry(
                                        origins = result.origins,
                                        expiresAtMs = nowMillis() + ttlMillis,
                                    )
                            }
                        }
                    }
                    result
                } finally {
                    synchronized(lock) {
                        if (inFlight[key]?.token == token) {
                            inFlight.remove(key)
                        }
                    }
                }
            }
        val created = InFlightFetch(token = token, deferred = deferred)
        inFlight[key] = created
        return created
    }

    private fun RaftInstalledAppScope.normalizedKeyOrNull(): CacheKey? {
        val cleanAccount = accountId.trim().takeIf { it.isNotEmpty() } ?: return null
        val cleanEnv = environmentKey.trim().takeIf { it.isNotEmpty() } ?: return null
        val cleanServer = serverId.trim().takeIf { it.isNotEmpty() } ?: return null
        return CacheKey(accountId = cleanAccount, environmentKey = cleanEnv, serverId = cleanServer)
    }
}

/**
 * Cancels any superseded cold-cache installed-App link lookup so rapid double-taps or tapping a
 * different link never race or open twice.
 */
internal class RaftPendingInstalledAppLookupGate {
    private val lock = SynchronizedObject()
    private var activeJob: Job? = null

    fun cancel() {
        val previous =
            synchronized(lock) {
                val existing = activeJob
                activeJob = null
                existing
            }
        previous?.cancel()
    }

    fun <T> launchLatest(
        scope: CoroutineScope,
        load: suspend () -> T,
        onComplete: (T) -> Unit,
    ) {
        lateinit var launched: Job
        val previous =
            synchronized(lock) {
                val existing = activeJob
                launched =
                    scope.launch(start = CoroutineStart.LAZY) {
                        var value: T? = null
                        var completed = false
                        try {
                            value = load()
                            completed = true
                        } finally {
                            val stillCurrent =
                                synchronized(lock) {
                                    if (activeJob === launched) {
                                        activeJob = null
                                        true
                                    } else {
                                        false
                                    }
                                }
                            if (completed && stillCurrent) {
                                @Suppress("UNCHECKED_CAST")
                                onComplete(value as T)
                            }
                        }
                    }
                activeJob = launched
                existing
            }
        previous?.cancel()
        launched.start()
    }
}

private fun defaultInstalledAppMonotonicNowMillis(): () -> Long {
    val origin = TimeSource.Monotonic.markNow()
    return { origin.elapsedNow().inWholeMilliseconds }
}
