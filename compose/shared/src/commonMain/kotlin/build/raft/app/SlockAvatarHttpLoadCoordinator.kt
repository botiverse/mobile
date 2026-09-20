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
    private val nowMillis: () -> Long = { processOrigin.elapsedNow().inWholeMilliseconds }
) {
    private class Entry(
        initial: SlockAvatarHttpLoadSnapshot,
        var lastAccess: Long
    ) {
        val state = MutableStateFlow(initial)
        var observerCount: Int = 0
    }

    private val lock = SynchronizedObject()
    private val entries = linkedMapOf<SlockAvatarHttpLoadKey, Entry>()
    private var nextOwnerToken = 0L
    private var nextAccess = 0L

    init {
        require(maxEntries > 0)
        require(failureCooldownMillis > 0L)
    }

    fun stateFlow(key: SlockAvatarHttpLoadKey): StateFlow<SlockAvatarHttpLoadSnapshot> =
        synchronized(lock) { entryLocked(key).state.asStateFlow() }

    fun retryDelayMillis(snapshot: SlockAvatarHttpLoadSnapshot): Long =
        snapshot.retryDelayMillis(nowMillis())

    fun retain(key: SlockAvatarHttpLoadKey) {
        synchronized(lock) {
            val entry = entryLocked(key)
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
                if (current.ownerToken != null) return@synchronized null
                val adopted =
                    SlockAvatarHttpLoadLease(
                        generation = current.generation,
                        ownerToken = ++nextOwnerToken
                    )
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
            when (painter) {
                SlockAvatarHttpPainterPhase.Success -> {
                    entry.state.value =
                        current.copy(
                            painter = painter,
                            cache = SlockAvatarHttpCacheState.Ready,
                            ownerToken = null,
                            retryAtMillis = null
                        )
                }
                SlockAvatarHttpPainterPhase.Error -> {
                    val canFallback =
                        current.endpoint == SlockAvatarHttpEndpoint.Cdn && key.apiFallbackSource != null
                    entry.state.value =
                        if (canFallback) {
                            current.copy(
                                endpoint = SlockAvatarHttpEndpoint.Api,
                                painter = SlockAvatarHttpPainterPhase.Empty
                            )
                        } else {
                            current.copy(
                                painter = painter,
                                cache = SlockAvatarHttpCacheState.Cooldown,
                                ownerToken = null,
                                retryAtMillis = nowMillis() + failureCooldownMillis
                            )
                        }
                }
                else -> entry.state.value = current.copy(painter = painter)
            }
            touchLocked(entry)
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
            entry.state.value =
                current.copy(
                    painter = SlockAvatarHttpPainterPhase.Error,
                    cache = SlockAvatarHttpCacheState.Cooldown,
                    ownerToken = null,
                    retryAtMillis = nowMillis() + failureCooldownMillis
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
        private val processOrigin = TimeSource.Monotonic.markNow()
    }
}

internal val slockAvatarHttpLoadCoordinator = SlockAvatarHttpLoadCoordinator()

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
