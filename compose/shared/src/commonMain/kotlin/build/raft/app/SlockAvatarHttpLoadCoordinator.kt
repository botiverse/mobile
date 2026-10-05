package build.raft.app

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.TimeSource

internal enum class SlockAvatarHttpEndpoint {
    Cdn,
    Api
}

internal enum class SlockAvatarHttpPainterPhase {
    Empty,
    Loading,
    Success,
    Error
}

internal enum class SlockAvatarHttpCacheState {
    Idle,
    InFlight,
    Ready,
    Cooldown
}

internal data class SlockAvatarHttpLoadKey(
    val primarySource: String,
    val primaryEndpoint: SlockAvatarHttpEndpoint,
    val apiFallbackSource: String? = null
) {
    init {
        require(primarySource.isNotBlank())
        require(apiFallbackSource == null || apiFallbackSource.isNotBlank())
        require(apiFallbackSource != primarySource)
    }

    fun sourceFor(endpoint: SlockAvatarHttpEndpoint): String? =
        when (endpoint) {
            SlockAvatarHttpEndpoint.Cdn ->
                primarySource.takeIf { primaryEndpoint == SlockAvatarHttpEndpoint.Cdn }
            SlockAvatarHttpEndpoint.Api ->
                if (primaryEndpoint == SlockAvatarHttpEndpoint.Api) primarySource else apiFallbackSource
        }

    val diagnosticsSource: String
        get() = primarySource.slockAvatarDiagnosticsSource()
}

internal data class SlockAvatarHttpLoadSnapshot(
    val generation: Long = 0L,
    val endpoint: SlockAvatarHttpEndpoint = SlockAvatarHttpEndpoint.Cdn,
    val painter: SlockAvatarHttpPainterPhase = SlockAvatarHttpPainterPhase.Empty,
    val cache: SlockAvatarHttpCacheState = SlockAvatarHttpCacheState.Idle,
    val ownerToken: Long? = null,
    val retryAtMillis: Long? = null
) {
    fun retryDelayMillis(nowMillis: Long): Long =
        if (cache == SlockAvatarHttpCacheState.Cooldown) {
            ((retryAtMillis ?: nowMillis) - nowMillis).coerceAtLeast(0L)
        } else {
            0L
        }
}

internal data class SlockAvatarHttpLoadLease(
    val generation: Long,
    val ownerToken: Long
)

/**
 * Process-session rendering coordinator for HTTP avatars.
 *
 * This is intentionally not product state: it owns only painter request arbitration, cooldown,
 * and cache reuse. The member store remains the sole avatar identity authority.
 */
internal class SlockAvatarHttpLoadCoordinator(
    private val maxEntries: Int = 256,
    private val failureCooldownMillis: Long = DEFAULT_FAILURE_COOLDOWN_MILLIS,
    private val stuckOwnerTimeoutMillis: Long = DEFAULT_STUCK_OWNER_TIMEOUT_MILLIS,
    private val nowMillis: () -> Long = { processOrigin.elapsedNow().inWholeMilliseconds }
) {
    private class Entry(
        initial: SlockAvatarHttpLoadSnapshot,
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
    private val entries = linkedMapOf<SlockAvatarHttpLoadKey, Entry>()
    private var nextOwnerToken = 0L
    private var nextAccess = 0L

    init {
        require(maxEntries > 0)
        require(failureCooldownMillis > 0L)
        require(stuckOwnerTimeoutMillis > 0L)
    }

    fun stateFlow(key: SlockAvatarHttpLoadKey): StateFlow<SlockAvatarHttpLoadSnapshot> =
        synchronized(lock) { entryLocked(key).state.asStateFlow() }

    fun retryDelayMillis(snapshot: SlockAvatarHttpLoadSnapshot): Long =
        snapshot.retryDelayMillis(nowMillis())

    /** How long a non-owner waits before asking [tryAcquire] to adopt a silent owner's flight. */
    val stuckOwnerRecheckDelayMillis: Long
        get() = stuckOwnerTimeoutMillis + 1L

    fun retain(key: SlockAvatarHttpLoadKey) {
        synchronized(lock) {
            val entry = entryLocked(key)
            val current = entry.state.value
            // A brand-new observer after every row left is a fresh user intent (e.g. page
            // re-entry). Re-arm a cooling-down entry so a single transport failure cannot pin
            // the avatar to its placeholder for the rest of the process lifetime; the backoff
            // counter still grows on each subsequent failure, so tight retry loops stay bounded.
            if (
                entry.observerCount == 0 &&
                current.cache == SlockAvatarHttpCacheState.Cooldown &&
                current.ownerToken == null
            ) {
                entry.state.value = current.copy(retryAtMillis = nowMillis())
            }
            entry.observerCount += 1
        }
    }

    fun release(key: SlockAvatarHttpLoadKey) {
        synchronized(lock) {
            entries[key]?.let { entry ->
                entry.observerCount = (entry.observerCount - 1).coerceAtLeast(0)
                touchLocked(entry)
            }
            trimLocked()
        }
    }

    fun tryAcquire(key: SlockAvatarHttpLoadKey): SlockAvatarHttpLoadLease? =
        synchronized(lock) {
            val entry = entryLocked(key)
            val current = entry.state.value
            if (current.cache == SlockAvatarHttpCacheState.Ready) {
                return@synchronized null
            }
            if (current.cache == SlockAvatarHttpCacheState.InFlight) {
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
                    SlockAvatarHttpLoadLease(
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
                SlockAvatarHttpLoadLease(
                    generation = current.generation + 1L,
                    ownerToken = ++nextOwnerToken
                )
            entry.ownerProgressMillis = nowMillis()
            entry.state.value =
                SlockAvatarHttpLoadSnapshot(
                    generation = lease.generation,
                    endpoint = key.primaryEndpoint,
                    painter = SlockAvatarHttpPainterPhase.Empty,
                    cache = SlockAvatarHttpCacheState.InFlight,
                    ownerToken = lease.ownerToken
                )
            touchLocked(entry)
            lease
        }

    fun reportPainterPhase(
        key: SlockAvatarHttpLoadKey,
        lease: SlockAvatarHttpLoadLease,
        painter: SlockAvatarHttpPainterPhase
    ) {
        synchronized(lock) {
            val entry = entries[key] ?: return@synchronized
            val current = entry.state.value
            if (!current.isOwnedBy(lease)) return@synchronized
            entry.ownerProgressMillis = nowMillis()
            when (painter) {
                SlockAvatarHttpPainterPhase.Success -> settleSuccessLocked(entry, current)
                SlockAvatarHttpPainterPhase.Error -> settleErrorLocked(key, entry, current)
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
        key: SlockAvatarHttpLoadKey,
        generation: Long,
        endpoint: SlockAvatarHttpEndpoint,
        painter: SlockAvatarHttpPainterPhase
    ) {
        synchronized(lock) {
            val entry = entries[key] ?: return@synchronized
            val current = entry.state.value
            if (
                current.cache != SlockAvatarHttpCacheState.InFlight ||
                current.generation != generation ||
                current.endpoint != endpoint
            ) {
                return@synchronized
            }
            when (painter) {
                SlockAvatarHttpPainterPhase.Success -> settleSuccessLocked(entry, current)
                SlockAvatarHttpPainterPhase.Error -> settleErrorLocked(key, entry, current)
                else -> return@synchronized
            }
            touchLocked(entry)
        }
    }

    private fun settleSuccessLocked(entry: Entry, current: SlockAvatarHttpLoadSnapshot) {
        entry.consecutiveFailures = 0
        entry.state.value =
            current.copy(
                painter = SlockAvatarHttpPainterPhase.Success,
                cache = SlockAvatarHttpCacheState.Ready,
                ownerToken = null,
                retryAtMillis = null
            )
    }

    private fun settleErrorLocked(
        key: SlockAvatarHttpLoadKey,
        entry: Entry,
        current: SlockAvatarHttpLoadSnapshot
    ) {
        val canFallback =
            current.endpoint == SlockAvatarHttpEndpoint.Cdn && key.apiFallbackSource != null
        entry.state.value =
            if (canFallback) {
                current.copy(
                    endpoint = SlockAvatarHttpEndpoint.Api,
                    painter = SlockAvatarHttpPainterPhase.Empty
                )
            } else {
                entry.consecutiveFailures += 1
                current.copy(
                    painter = SlockAvatarHttpPainterPhase.Error,
                    cache = SlockAvatarHttpCacheState.Cooldown,
                    ownerToken = null,
                    retryAtMillis = nowMillis() + cooldownDelayMillis(entry.consecutiveFailures)
                )
            }
    }

    /** A ready consumer that still errors invalidates that cache generation for every row. */
    fun reportReadyConsumerError(
        key: SlockAvatarHttpLoadKey,
        generation: Long
    ) {
        synchronized(lock) {
            val entry = entries[key] ?: return@synchronized
            val current = entry.state.value
            if (current.generation != generation || current.cache != SlockAvatarHttpCacheState.Ready) {
                return@synchronized
            }
            entry.consecutiveFailures += 1
            entry.state.value =
                current.copy(
                    painter = SlockAvatarHttpPainterPhase.Error,
                    cache = SlockAvatarHttpCacheState.Cooldown,
                    ownerToken = null,
                    retryAtMillis = nowMillis() + cooldownDelayMillis(entry.consecutiveFailures)
                )
            touchLocked(entry)
        }
    }

    fun releaseOwner(
        key: SlockAvatarHttpLoadKey,
        lease: SlockAvatarHttpLoadLease
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
                        painter = SlockAvatarHttpPainterPhase.Empty,
                        cache = SlockAvatarHttpCacheState.Idle,
                        ownerToken = null,
                        retryAtMillis = null
                    )
                }
            touchLocked(entry)
        }
    }

    internal fun entryCount(): Int = synchronized(lock) { entries.size }

    private fun SlockAvatarHttpLoadSnapshot.isOwnedBy(lease: SlockAvatarHttpLoadLease): Boolean =
        cache == SlockAvatarHttpCacheState.InFlight &&
            generation == lease.generation &&
            ownerToken == lease.ownerToken

    private fun entryLocked(key: SlockAvatarHttpLoadKey): Entry =
        entries.getOrPut(key) {
            Entry(
                initial = SlockAvatarHttpLoadSnapshot(endpoint = key.primaryEndpoint),
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
                        entry.observerCount == 0 && entry.state.value.cache != SlockAvatarHttpCacheState.InFlight
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

internal val slockAvatarHttpLoadCoordinator = SlockAvatarHttpLoadCoordinator()

internal enum class SlockAvatarHttpAcquireAttempt { Now, AfterStuckOwnerTimeout, None }

/**
 * When an avatar row should ask the coordinator for a lease. A flight owned by another row is
 * only adoptable once that owner has gone silent past the stuck-owner timeout, and nothing else
 * changes this row's state while it stays silent, so the row has to come back after the timeout
 * itself (HarmonyOS 1.13.0: every server avatar waited forever on a silent owner).
 */
internal fun slockAvatarHttpAcquireAttempt(
    snapshot: SlockAvatarHttpLoadSnapshot,
    ownsCurrentRequest: Boolean
): SlockAvatarHttpAcquireAttempt =
    when {
        snapshot.cache == SlockAvatarHttpCacheState.Idle ||
            snapshot.cache == SlockAvatarHttpCacheState.Cooldown ||
            // A flight whose owner left the viewport while other observers remain is orphaned
            // (InFlight with a null ownerToken); a surviving/new row adopts it right away.
            (snapshot.cache == SlockAvatarHttpCacheState.InFlight && snapshot.ownerToken == null) ->
            SlockAvatarHttpAcquireAttempt.Now
        snapshot.cache == SlockAvatarHttpCacheState.InFlight && !ownsCurrentRequest ->
            SlockAvatarHttpAcquireAttempt.AfterStuckOwnerTimeout
        else -> SlockAvatarHttpAcquireAttempt.None
    }

internal fun SlockAvatarHttpLoadSnapshot.diagnosticsCacheState(localOwnerToken: Long?): String =
    when (cache) {
        SlockAvatarHttpCacheState.Idle -> "miss"
        SlockAvatarHttpCacheState.Ready -> "ready"
        SlockAvatarHttpCacheState.Cooldown -> "cooldown"
        SlockAvatarHttpCacheState.InFlight ->
            if (ownerToken != null && ownerToken == localOwnerToken) "single_flight_owner" else "single_flight_join"
    }

internal fun SlockAvatarHttpEndpoint.diagnosticsName(): String = name.lowercase()

internal fun SlockAvatarHttpPainterPhase.diagnosticsName(): String = name.lowercase()
