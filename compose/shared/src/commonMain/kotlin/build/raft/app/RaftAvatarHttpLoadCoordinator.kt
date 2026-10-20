package build.raft.app

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.TimeSource

internal enum class RaftAvatarHttpEndpoint {
    Cdn,
    Api
}

internal enum class RaftAvatarHttpPainterPhase {
    Empty,
    Loading,
    Success,
    Error
}

internal enum class RaftAvatarHttpCacheState {
    Idle,
    InFlight,
    Ready,
    Cooldown
}

internal data class RaftAvatarHttpLoadKey(
    val primarySource: String,
    val primaryEndpoint: RaftAvatarHttpEndpoint,
    val apiFallbackSource: String? = null
) {
    init {
        require(primarySource.isNotBlank())
        require(apiFallbackSource == null || apiFallbackSource.isNotBlank())
        require(apiFallbackSource != primarySource)
    }

    fun sourceFor(endpoint: RaftAvatarHttpEndpoint): String? =
        when (endpoint) {
            RaftAvatarHttpEndpoint.Cdn ->
                primarySource.takeIf { primaryEndpoint == RaftAvatarHttpEndpoint.Cdn }
            RaftAvatarHttpEndpoint.Api ->
                if (primaryEndpoint == RaftAvatarHttpEndpoint.Api) primarySource else apiFallbackSource
        }

    val diagnosticsSource: String
        get() = primarySource.raftAvatarDiagnosticsSource()
}

internal data class RaftAvatarHttpLoadSnapshot(
    val generation: Long = 0L,
    val endpoint: RaftAvatarHttpEndpoint = RaftAvatarHttpEndpoint.Cdn,
    val painter: RaftAvatarHttpPainterPhase = RaftAvatarHttpPainterPhase.Empty,
    val cache: RaftAvatarHttpCacheState = RaftAvatarHttpCacheState.Idle,
    val ownerToken: Long? = null,
    val retryAtMillis: Long? = null
) {
    fun retryDelayMillis(nowMillis: Long): Long =
        if (cache == RaftAvatarHttpCacheState.Cooldown) {
            ((retryAtMillis ?: nowMillis) - nowMillis).coerceAtLeast(0L)
        } else {
            0L
        }
}

internal data class RaftAvatarHttpLoadLease(
    val generation: Long,
    val ownerToken: Long
)

/**
 * Process-session rendering coordinator for HTTP avatars.
 *
 * This is intentionally not product state: it owns only painter request arbitration, cooldown,
 * and cache reuse. The member store remains the sole avatar identity authority.
 */
internal class RaftAvatarHttpLoadCoordinator(
    private val maxEntries: Int = 256,
    private val failureCooldownMillis: Long = DEFAULT_FAILURE_COOLDOWN_MILLIS,
    private val stuckOwnerTimeoutMillis: Long = DEFAULT_STUCK_OWNER_TIMEOUT_MILLIS,
    private val nowMillis: () -> Long = { processOrigin.elapsedNow().inWholeMilliseconds }
) {
    private class Entry(
        initial: RaftAvatarHttpLoadSnapshot,
        var lastAccess: Long
    ) {
        val state = MutableStateFlow(initial)
        var observerCount: Int = 0

        /**
         * Last time the in-flight owner showed progress (lease issued/adopted or a painter phase
         * reported). An owner that stays alive but never reports — e.g. its composition is kept
         * alive but frozen offscreen — is treated as adoptable once this goes stale.
         */
        var ownerProgressMillis: Long = 0L

        /**
         * Consecutive failure count since the last success/idle reset. Drives the bounded
         * exponential cooldown: each failed generation doubles the wait, so a transient
         * network error recovers in seconds while a permanently absent resource backs
         * off to the cap instead of spamming the transport.
         */
        var consecutiveFailures: Int = 0
    }

    private fun cooldownDelayMillis(failures: Int): Long {
        // First failure waits the base delay; each subsequent consecutive failure doubles
        // it, capped at the configured maximum so absent resources don't retry forever.
        val base = failureCooldownMillis.coerceAtMost(MAX_SINGLE_FAILURE_COOLDOWN_MILLIS)
        var delay = base
        repeat((failures - 1).coerceAtLeast(0)) {
            delay = (delay * 2L).coerceAtMost(failureCooldownMillis)
        }
        return delay.coerceAtMost(failureCooldownMillis)
    }

    private val lock = SynchronizedObject()
    private val entries = linkedMapOf<RaftAvatarHttpLoadKey, Entry>()
    private var nextOwnerToken = 0L
    private var nextAccess = 0L

    init {
        require(maxEntries > 0)
        require(failureCooldownMillis > 0L)
        require(stuckOwnerTimeoutMillis > 0L)
    }

    fun stateFlow(key: RaftAvatarHttpLoadKey): StateFlow<RaftAvatarHttpLoadSnapshot> =
        synchronized(lock) { entryLocked(key).state.asStateFlow() }

    fun retryDelayMillis(snapshot: RaftAvatarHttpLoadSnapshot): Long =
        snapshot.retryDelayMillis(nowMillis())

    /** How long a non-owner waits before asking [tryAcquire] to adopt a silent owner's flight. */
    val stuckOwnerRecheckDelayMillis: Long
        get() = stuckOwnerTimeoutMillis + 1L

    fun retain(key: RaftAvatarHttpLoadKey) {
        synchronized(lock) {
            val entry = entryLocked(key)
            val current = entry.state.value
            // A brand-new observer after every row left is a fresh user intent (e.g. page
            // re-entry). Re-arm a cooling-down entry so a single transport failure cannot pin
            // the avatar to its placeholder for the rest of the process lifetime; the backoff
            // counter still grows on each subsequent failure, so tight retry loops stay bounded.
            if (
                entry.observerCount == 0 &&
                current.cache == RaftAvatarHttpCacheState.Cooldown &&
                current.ownerToken == null
            ) {
                entry.state.value = current.copy(retryAtMillis = nowMillis())
            }
            entry.observerCount += 1
        }
    }

    fun release(key: RaftAvatarHttpLoadKey) {
        synchronized(lock) {
            entries[key]?.let { entry ->
                entry.observerCount = (entry.observerCount - 1).coerceAtLeast(0)
                touchLocked(entry)
            }
            trimLocked()
        }
    }

    fun tryAcquire(key: RaftAvatarHttpLoadKey): RaftAvatarHttpLoadLease? =
        synchronized(lock) {
            val entry = entryLocked(key)
            val current = entry.state.value
            if (current.cache == RaftAvatarHttpCacheState.Ready) {
                return@synchronized null
            }
            if (current.cache == RaftAvatarHttpCacheState.InFlight) {
                // An in-flight request with no owner (its composable owner left the viewport while
                // observers remain) is inheritable: a new row adopts the existing generation so the
                // same download keeps running instead of restarting from byte 0.
                // The same applies when the owner token is set but the owner stopped reporting
                // progress past stuckOwnerTimeoutMillis — e.g. a keep-alive offscreen composition
                // that still holds the lease but whose fetch pipeline no longer runs. Adoption
                // bumps the owner token, so a late-waking old owner can no longer write phases.
                if (
                    current.ownerToken != null &&
                    nowMillis() - entry.ownerProgressMillis <= stuckOwnerTimeoutMillis
                ) {
                    return@synchronized null
                }
                val adopted =
                    RaftAvatarHttpLoadLease(
                        generation = current.generation,
                        ownerToken = ++nextOwnerToken
                    )
                entry.ownerProgressMillis = nowMillis()
                entry.state.value = current.copy(ownerToken = adopted.ownerToken)
                touchLocked(entry)
                return@synchronized adopted
            }
            if (current.retryDelayMillis(nowMillis()) > 0L) return@synchronized null
            val lease =
                RaftAvatarHttpLoadLease(
                    generation = current.generation + 1L,
                    ownerToken = ++nextOwnerToken
                )
            entry.ownerProgressMillis = nowMillis()
            entry.state.value =
                RaftAvatarHttpLoadSnapshot(
                    generation = lease.generation,
                    endpoint = key.primaryEndpoint,
                    painter = RaftAvatarHttpPainterPhase.Empty,
                    cache = RaftAvatarHttpCacheState.InFlight,
                    ownerToken = lease.ownerToken
                )
            touchLocked(entry)
            lease
        }

    /**
     * [endpoint] is the endpoint whose URL the reporting painter rendered. Once an observer has
     * moved the flight from CDN to API, a late result for the CDN URL must not settle the API
     * attempt (LiBai, review of #2838). Production callers always pass it.
     */
    fun reportPainterPhase(
        key: RaftAvatarHttpLoadKey,
        lease: RaftAvatarHttpLoadLease,
        painter: RaftAvatarHttpPainterPhase,
        endpoint: RaftAvatarHttpEndpoint? = null
    ) {
        synchronized(lock) {
            val entry = entries[key] ?: return@synchronized
            val current = entry.state.value
            if (!current.isOwnedBy(lease)) return@synchronized
            if (endpoint != null && endpoint != current.endpoint) return@synchronized
            entry.ownerProgressMillis = nowMillis()
            when (painter) {
                RaftAvatarHttpPainterPhase.Success -> settleSuccessLocked(entry, current)
                RaftAvatarHttpPainterPhase.Error -> settleErrorLocked(key, entry, current)
                else -> entry.state.value = current.copy(painter = painter)
            }
            touchLocked(entry)
        }
    }

    /**
     * A row that renders the in-flight endpoint without holding the lease. Its result settles the
     * flight exactly as the owner's would, so no row depends on another row being drawn: on
     * Kuikly an image only starts loading when it is drawn, and an owner that is composed but never
     * drawn (offscreen, hidden, a 1dp prewarm surface) used to leave every other row blank.
     * Fenced by generation and endpoint; Empty/Loading from an observer change nothing.
     */
    fun reportObserverPhase(
        key: RaftAvatarHttpLoadKey,
        generation: Long,
        endpoint: RaftAvatarHttpEndpoint,
        painter: RaftAvatarHttpPainterPhase
    ) {
        synchronized(lock) {
            val entry = entries[key] ?: return@synchronized
            val current = entry.state.value
            if (
                current.cache != RaftAvatarHttpCacheState.InFlight ||
                current.generation != generation ||
                current.endpoint != endpoint
            ) {
                return@synchronized
            }
            when (painter) {
                RaftAvatarHttpPainterPhase.Success -> settleSuccessLocked(entry, current)
                RaftAvatarHttpPainterPhase.Error -> settleErrorLocked(key, entry, current)
                else -> return@synchronized
            }
            touchLocked(entry)
        }
    }

    private fun settleSuccessLocked(entry: Entry, current: RaftAvatarHttpLoadSnapshot) {
        entry.consecutiveFailures = 0
        entry.state.value =
            current.copy(
                painter = RaftAvatarHttpPainterPhase.Success,
                cache = RaftAvatarHttpCacheState.Ready,
                ownerToken = null,
                retryAtMillis = null
            )
    }

    private fun settleErrorLocked(
        key: RaftAvatarHttpLoadKey,
        entry: Entry,
        current: RaftAvatarHttpLoadSnapshot
    ) {
        val canFallback =
            current.endpoint == RaftAvatarHttpEndpoint.Cdn && key.apiFallbackSource != null
        entry.state.value =
            if (canFallback) {
                current.copy(
                    endpoint = RaftAvatarHttpEndpoint.Api,
                    painter = RaftAvatarHttpPainterPhase.Empty
                )
            } else {
                entry.consecutiveFailures += 1
                current.copy(
                    painter = RaftAvatarHttpPainterPhase.Error,
                    cache = RaftAvatarHttpCacheState.Cooldown,
                    ownerToken = null,
                    retryAtMillis = nowMillis() + cooldownDelayMillis(entry.consecutiveFailures)
                )
            }
    }

    /** A ready consumer that still errors invalidates that cache generation for every row. */
    fun reportReadyConsumerError(
        key: RaftAvatarHttpLoadKey,
        generation: Long,
        endpoint: RaftAvatarHttpEndpoint? = null
    ) {
        synchronized(lock) {
            val entry = entries[key] ?: return@synchronized
            val current = entry.state.value
            if (current.generation != generation || current.cache != RaftAvatarHttpCacheState.Ready) {
                return@synchronized
            }
            // A late error for the endpoint the generation already moved off does not invalidate
            // the endpoint that succeeded.
            if (endpoint != null && endpoint != current.endpoint) return@synchronized
            entry.consecutiveFailures += 1
            entry.state.value =
                current.copy(
                    painter = RaftAvatarHttpPainterPhase.Error,
                    cache = RaftAvatarHttpCacheState.Cooldown,
                    ownerToken = null,
                    retryAtMillis = nowMillis() + cooldownDelayMillis(entry.consecutiveFailures)
                )
            touchLocked(entry)
        }
    }

    fun releaseOwner(
        key: RaftAvatarHttpLoadKey,
        lease: RaftAvatarHttpLoadLease
    ) {
        synchronized(lock) {
            val entry = entries[key] ?: return@synchronized
            val current = entry.state.value
            if (!current.isOwnedBy(lease)) return@synchronized
            entry.state.value =
                if (entry.observerCount > 1) {
                    // Other rows still observe this avatar. Orphan the in-flight generation instead
                    // of resetting to Idle so a surviving row can adopt it and continue the download.
                    current.copy(ownerToken = null)
                } else {
                    entry.consecutiveFailures = 0
                    current.copy(
                        painter = RaftAvatarHttpPainterPhase.Empty,
                        cache = RaftAvatarHttpCacheState.Idle,
                        ownerToken = null,
                        retryAtMillis = null
                    )
                }
            touchLocked(entry)
        }
    }

    internal fun entryCount(): Int = synchronized(lock) { entries.size }

    private fun RaftAvatarHttpLoadSnapshot.isOwnedBy(lease: RaftAvatarHttpLoadLease): Boolean =
        cache == RaftAvatarHttpCacheState.InFlight &&
            generation == lease.generation &&
            ownerToken == lease.ownerToken

    private fun entryLocked(key: RaftAvatarHttpLoadKey): Entry =
        entries.getOrPut(key) {
            Entry(
                initial = RaftAvatarHttpLoadSnapshot(endpoint = key.primaryEndpoint),
                lastAccess = ++nextAccess
            )
        }.also(::touchLocked)

    private fun touchLocked(entry: Entry) {
        entry.lastAccess = ++nextAccess
    }

    private fun trimLocked() {
        while (entries.size > maxEntries) {
            val candidate =
                entries.entries
                    .asSequence()
                    .filter { (_, entry) ->
                        entry.observerCount == 0 && entry.state.value.cache != RaftAvatarHttpCacheState.InFlight
                    }
                    .minByOrNull { (_, entry) -> entry.lastAccess }
                    ?: return
            entries.remove(candidate.key)
        }
    }

    internal companion object {
        const val DEFAULT_FAILURE_COOLDOWN_MILLIS = 5 * 60 * 1_000L
        const val DEFAULT_STUCK_OWNER_TIMEOUT_MILLIS = 30_000L

        /**
         * Upper bound on the first (single-failure) cooldown step. Transient errors
         * recover after roughly this long; consecutive failures double toward
         * [DEFAULT_FAILURE_COOLDOWN_MILLIS].
         */
        const val MAX_SINGLE_FAILURE_COOLDOWN_MILLIS = 10_000L
        private val processOrigin = TimeSource.Monotonic.markNow()
    }
}

internal val raftAvatarHttpLoadCoordinator = RaftAvatarHttpLoadCoordinator()

internal enum class RaftAvatarHttpAcquireAttempt { Now, AfterStuckOwnerTimeout, None }

/**
 * When an avatar row should ask the coordinator for a lease. A flight owned by another row is
 * only adoptable once that owner has gone silent past the stuck-owner timeout, and nothing else
 * changes this row's state while it stays silent, so the row has to come back after the timeout
 * itself (HarmonyOS 1.13.0: every server avatar waited forever on a silent owner).
 */
internal fun raftAvatarHttpAcquireAttempt(
    snapshot: RaftAvatarHttpLoadSnapshot,
    ownsCurrentRequest: Boolean
): RaftAvatarHttpAcquireAttempt =
    when {
        snapshot.cache == RaftAvatarHttpCacheState.Idle ||
            snapshot.cache == RaftAvatarHttpCacheState.Cooldown ||
            // A flight whose owner left the viewport while other observers remain is orphaned
            // (InFlight with a null ownerToken); a surviving/new row adopts it right away.
            (snapshot.cache == RaftAvatarHttpCacheState.InFlight && snapshot.ownerToken == null) ->
            RaftAvatarHttpAcquireAttempt.Now
        snapshot.cache == RaftAvatarHttpCacheState.InFlight && !ownsCurrentRequest ->
            RaftAvatarHttpAcquireAttempt.AfterStuckOwnerTimeout
        else -> RaftAvatarHttpAcquireAttempt.None
    }

internal fun RaftAvatarHttpLoadSnapshot.diagnosticsCacheState(localOwnerToken: Long?): String =
    when (cache) {
        RaftAvatarHttpCacheState.Idle -> "miss"
        RaftAvatarHttpCacheState.Ready -> "ready"
        RaftAvatarHttpCacheState.Cooldown -> "cooldown"
        RaftAvatarHttpCacheState.InFlight ->
            if (ownerToken != null && ownerToken == localOwnerToken) "single_flight_owner" else "single_flight_join"
    }

internal fun RaftAvatarHttpEndpoint.diagnosticsName(): String = name.lowercase()

internal fun RaftAvatarHttpPainterPhase.diagnosticsName(): String = name.lowercase()
