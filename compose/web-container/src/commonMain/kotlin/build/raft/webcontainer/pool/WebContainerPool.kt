package build.raft.webcontainer.pool

import build.raft.webcontainer.WebIsolationScope
import build.raft.webcontainer.resource.WebResourceSource
import build.raft.webcontainer.webContainerMonotonicMillis

/** Memory pressure severity used to trim pooled/kept-alive WebView instances. */
enum class WebMemoryTrimLevel {
    MODERATE,
    CRITICAL,
}

/** Key matching a pooled container by caller key, isolation boundary, and color-scheme mode. */
data class WebContainerPoolKey(
    val keepAliveKey: String,
    val isolationScope: WebIsolationScope,
    val isDarkTheme: Boolean,
) {
    init {
        require(keepAliveKey.isNotBlank()) { "keepAliveKey must not be blank." }
    }
}

/** Read-only snapshot of a parked keep-alive container's active document state. */
data class WebContainerKeptSnapshot(
    val configuredUrl: String,
    val currentUrl: String,
    val loaded: Boolean,
)

/** Platform container instance that can be parked in [WebContainerKeepAlivePool]. */
interface WebPooledContainer {
    val isolationScope: WebIsolationScope
    val isDarkTheme: Boolean

    /** Anti-zombie health probe checked before vending a pooled instance. */
    fun isHealthy(): Boolean

    /** Permanently tears down the underlying platform WebView and releases resources. */
    fun destroy()
}

/**
 * LRU + TTL keep-alive pool for platform WebView instances.
 *
 * Guarantees:
 * - Never vends an expired or unhealthy (zombie / crashed / load-failed) instance.
 * - Matches strictly on `(keepAliveKey, isolationScope, isDarkTheme)`.
 * - Supports targeted [evictScope] so platform profile deletion never collides with idle views.
 */
class WebContainerKeepAlivePool<T : WebPooledContainer>(
    private val maxEntries: Int = 3,
    private val ttlMs: Long = 10 * 60_000L,
    private val nowMs: () -> Long = ::webContainerMonotonicMillis,
    private val telemetrySink: WebContainerTelemetrySink? = null,
) {
    init {
        require(maxEntries >= 0) { "maxEntries must be >= 0." }
        require(ttlMs > 0L) { "ttlMs must be > 0." }
    }

    private data class Entry<T : WebPooledContainer>(
        val key: WebContainerPoolKey,
        val container: T,
        val parkedAtMs: Long,
    )

    private val entries = LinkedHashMap<WebContainerPoolKey, Entry<T>>()

    val size: Int
        get() = entries.size

    /**
     * Parks [container] under [key]. Returns `true` if parked, or `false` if rejected (destroyed
     * immediately) due to unhealthy state, key mismatch, or zero capacity.
     */
    fun park(
        key: WebContainerPoolKey,
        container: T,
    ): Boolean {
        val now = nowMs()
        pruneExpired(now)
        if (
            maxEntries == 0 ||
            !container.isHealthy() ||
            container.isolationScope != key.isolationScope ||
            container.isDarkTheme != key.isDarkTheme
        ) {
            container.destroy()
            emitPoolEvent("park_rejected", key.keepAliveKey)
            return false
        }
        entries.remove(key)?.let { replaced ->
            replaced.container.destroy()
            emitPoolEvent("evict_replaced", key.keepAliveKey)
        }
        while (entries.size >= maxEntries) {
            val eldestKey = entries.keys.firstOrNull() ?: break
            entries.remove(eldestKey)?.let { evicted ->
                evicted.container.destroy()
                emitPoolEvent("evict_lru", eldestKey.keepAliveKey)
            }
        }
        entries[key] = Entry(key = key, container = container, parkedAtMs = now)
        emitPoolEvent("parked", key.keepAliveKey)
        return true
    }

    /** Inspects a healthy parked container matching [key] without removing it from the pool. */
    fun peek(key: WebContainerPoolKey): T? {
        val now = nowMs()
        pruneExpired(now)
        val entry = entries[key] ?: return null
        if (!entry.container.isHealthy()) {
            entries.remove(key)
            entry.container.destroy()
            emitPoolEvent("evict_unhealthy_on_peek", key.keepAliveKey)
            return null
        }
        return entry.container
    }

    /** Takes a live, healthy container matching [key], or `null` if absent, expired, or unhealthy. */
    fun take(key: WebContainerPoolKey): T? {
        val now = nowMs()
        pruneExpired(now)
        val entry = entries.remove(key) ?: return null
        if (!entry.container.isHealthy()) {
            entry.container.destroy()
            emitPoolEvent("evict_unhealthy_on_take", key.keepAliveKey)
            return null
        }
        emitPoolEvent("reused", key.keepAliveKey)
        return entry.container
    }

    /** Removes and destroys the entry if it holds the exact [container] instance. */
    fun dropIfSame(container: T): Boolean {
        val targetKey = entries.entries.firstOrNull { it.value.container === container }?.key ?: return false
        entries.remove(targetKey)?.let { removed ->
            removed.container.destroy()
            emitPoolEvent("dropped", targetKey.keepAliveKey)
        }
        return true
    }

    /** Evicts and destroys all pooled instances bound to [scope]. Returns the number destroyed. */
    fun evictScope(scope: WebIsolationScope): Int {
        val matchingKeys = entries.keys.filter { it.isolationScope == scope }
        for (key in matchingKeys) {
            entries.remove(key)?.let { removed ->
                removed.container.destroy()
                emitPoolEvent("evict_scope", key.keepAliveKey)
            }
        }
        return matchingKeys.size
    }

    /** Responds to OS memory pressure by evicting expired/oldest entries (or all on [WebMemoryTrimLevel.CRITICAL]). */
    fun onMemoryPressure(level: WebMemoryTrimLevel): Int {
        val now = nowMs()
        var evicted = pruneExpired(now)
        when (level) {
            WebMemoryTrimLevel.CRITICAL -> {
                evicted += clearAll()
            }
            WebMemoryTrimLevel.MODERATE -> {
                val targetRemaining = entries.size / 2
                while (entries.size > targetRemaining) {
                    val eldestKey = entries.keys.firstOrNull() ?: break
                    entries.remove(eldestKey)?.let { removed ->
                        removed.container.destroy()
                        evicted += 1
                        emitPoolEvent("evict_memory_moderate", eldestKey.keepAliveKey)
                    }
                }
            }
        }
        return evicted
    }

    /** Evicts and destroys every pooled instance. Returns count destroyed. */
    fun clearAll(): Int {
        val all = entries.values.toList()
        entries.clear()
        for (entry in all) {
            entry.container.destroy()
            emitPoolEvent("evict_clear_all", entry.key.keepAliveKey)
        }
        return all.size
    }

    private fun pruneExpired(now: Long): Int {
        val expiredKeys = entries.entries.filter { now - it.value.parkedAtMs >= ttlMs }.map { it.key }
        for (key in expiredKeys) {
            entries.remove(key)?.let { removed ->
                removed.container.destroy()
                emitPoolEvent("evict_ttl", key.keepAliveKey)
            }
        }
        return expiredKeys.size
    }

    private fun emitPoolEvent(action: String, key: String) {
        telemetrySink?.onEvent(
            WebContainerTelemetryEvent.PoolEvent(
                action = action,
                keepAliveKey = key,
                poolSizeAfter = entries.size,
            ),
        )
    }
}

/** Result of requesting data-store / profile deletion for an isolation scope. */
enum class WebIsolationClearResult {
    CLEARED,
    DEFERRED_ACTIVE_INSTANCES,
    FAILED,
}

/**
 * Coordinates isolation-scope teardown across pooled and active foreground WebViews.
 *
 * Android `ProfileStore.deleteProfile(name)` fails if ANY live `WebView` (including idle pooled
 * instances) is still bound to that profile. This manager enforces the contract:
 * 1. Evict & destroy all pooled instances bound to `scope` first.
 * 2. If foreground active instances remain (`activeCount(scope) > 0`), defer platform deletion until
 *    the final active instance unregisters.
 * 3. Otherwise invoke platform deletion immediately.
 */
class WebIsolationScopeManager(
    private val telemetrySink: WebContainerTelemetrySink? = null,
) {
    private val activeCounts = LinkedHashMap<WebIsolationScope, Int>()
    private val deferredDeletions = LinkedHashMap<WebIsolationScope, Pair<(WebIsolationScope) -> Int, (WebIsolationScope) -> Boolean>>()

    fun activeCount(scope: WebIsolationScope): Int = activeCounts[scope] ?: 0

    fun hasPendingDeletion(scope: WebIsolationScope): Boolean = scope in deferredDeletions

    fun registerActive(scope: WebIsolationScope) {
        activeCounts[scope] = (activeCounts[scope] ?: 0) + 1
    }

    /**
     * Unregisters one active foreground instance. If count reaches 0 and a deferred deletion was
     * queued for [scope], runs pool eviction followed by platform deletion automatically.
     */
    fun unregisterActive(scope: WebIsolationScope): WebIsolationClearResult? {
        val current = activeCounts[scope] ?: 0
        val next = (current - 1).coerceAtLeast(0)
        if (next == 0) {
            activeCounts.remove(scope)
        } else {
            activeCounts[scope] = next
            return null
        }
        val deferred = deferredDeletions.remove(scope) ?: return null
        val evicted = deferred.first(scope)
        val deleted = runCatching { deferred.second(scope) }.getOrDefault(false)
        val result = if (deleted) WebIsolationClearResult.CLEARED else WebIsolationClearResult.FAILED
        telemetrySink?.onEvent(
            WebContainerTelemetryEvent.IsolationScopeCleared(
                scope = scope,
                evictedPooledCount = evicted,
                result = result,
            ),
        )
        return result
    }

    fun clearIsolationScope(
        scope: WebIsolationScope,
        evictPooled: (WebIsolationScope) -> Int,
        deletePlatformStore: (WebIsolationScope) -> Boolean,
    ): WebIsolationClearResult {
        val evicted = evictPooled(scope)
        if (activeCount(scope) > 0) {
            deferredDeletions[scope] = evictPooled to deletePlatformStore
            telemetrySink?.onEvent(
                WebContainerTelemetryEvent.IsolationScopeCleared(
                    scope = scope,
                    evictedPooledCount = evicted,
                    result = WebIsolationClearResult.DEFERRED_ACTIVE_INSTANCES,
                ),
            )
            return WebIsolationClearResult.DEFERRED_ACTIVE_INSTANCES
        }
        deferredDeletions.remove(scope)
        val deleted = runCatching { deletePlatformStore(scope) }.getOrDefault(false)
        val result = if (deleted) WebIsolationClearResult.CLEARED else WebIsolationClearResult.FAILED
        telemetrySink?.onEvent(
            WebContainerTelemetryEvent.IsolationScopeCleared(
                scope = scope,
                evictedPooledCount = evicted,
                result = result,
            ),
        )
        return result
    }
}

/** Structured container observability events (never contain raw query tokens or user PII). */
sealed interface WebContainerTelemetryEvent {
    data class StartupCompleted(val elapsedMs: Long) : WebContainerTelemetryEvent

    data class StartupFailed(val reason: String) : WebContainerTelemetryEvent

    data class NavigationBlocked(
        val surfaceId: String,
        val sanitizedUrl: String,
        val reason: String,
    ) : WebContainerTelemetryEvent

    data class RenderProcessGone(
        val surfaceId: String,
        val didCrash: Boolean,
        val recovered: Boolean,
        val attempt: Int,
    ) : WebContainerTelemetryEvent

    data class WatchdogTerminalError(
        val surfaceId: String,
        val reason: String,
    ) : WebContainerTelemetryEvent

    data class JsbCallCompleted(
        val surfaceId: String,
        val method: String,
        val success: Boolean,
        val errorCode: String?,
        val durationMs: Long,
    ) : WebContainerTelemetryEvent

    data class ResourceIntercepted(
        val surfaceId: String,
        val sanitizedUrl: String,
        val hit: Boolean,
        val source: WebResourceSource?,
        val fallbackReason: String?,
    ) : WebContainerTelemetryEvent

    data class PoolEvent(
        val action: String,
        val keepAliveKey: String,
        val poolSizeAfter: Int,
    ) : WebContainerTelemetryEvent

    data class IsolationScopeCleared(
        val scope: WebIsolationScope,
        val evictedPooledCount: Int,
        val result: WebIsolationClearResult,
    ) : WebContainerTelemetryEvent
}

/** Host-provided sink receiving [WebContainerTelemetryEvent]s for logging or metrics export. */
fun interface WebContainerTelemetrySink {
    fun onEvent(event: WebContainerTelemetryEvent)
}

/** Blank warm-up document URL loaded by platform spare WebViews to spin up the engine process. */
const val WEB_CONTAINER_SPARE_BLANK_URL: String = "about:blank"

/**
 * Pure single-slot warm spare state machine shared by platform spare pools (such as iOS
 * `WebContainerIosSparePool`).
 *
 * Guarantees:
 * - Hands out a spare only after its warm-up load matches the current generation AND has not
 *   exceeded [maxIdleMs] (anti-stale / anti-zombie TTL).
 * - Immediately retires a spare when its content process dies while idle ([invalidateIfSame]).
 * - Retires any replaced or dropped spare cleanly so native delegates/views never leak.
 */
class WebContainerSpareSlot<T : Any>(
    private val nowMs: () -> Long = ::webContainerMonotonicMillis,
    private val retire: (T) -> Unit,
) {
    private var item: T? = null
    private var ready: Boolean = false
    private var readyAt: Long = 0L
    private var generation: Int = 0

    val hasItem: Boolean
        get() = item != null

    val isReady: Boolean
        get() = item != null && ready

    val readyAtMs: Long?
        get() = if (item != null && ready) readyAt else null

    /** Installs [next] as the warming spare (retiring any previous one) and returns its generation. */
    fun put(next: T): Int {
        item?.let(retire)
        item = next
        ready = false
        readyAt = 0L
        generation += 1
        return generation
    }

    /** Marks the warming spare ready if [generation] matches the currently held instance. */
    fun markReady(
        generation: Int,
        atMs: Long = nowMs(),
    ): Boolean {
        if (generation != this.generation || item == null) return false
        ready = true
        readyAt = atMs
        return true
    }

    /**
     * Retires the held spare if [sameInstance] confirms it matches [target] (used when the OS
     * terminates a parked spare's WebContent process while idle).
     */
    fun invalidateIfSame(
        target: T,
        sameInstance: (T, T) -> Boolean = { a, b -> a === b },
    ): Boolean {
        val current = item ?: return false
        if (!sameInstance(current, target)) return false
        item = null
        ready = false
        readyAt = 0L
        generation += 1
        retire(current)
        return true
    }

    /**
     * Takes the ready spare if healthy and within [maxIdleMs], or returns `null`.
     * An expired ready spare is retired immediately so callers never inherit a stale zombie.
     */
    fun take(maxIdleMs: Long = Long.MAX_VALUE): T? {
        val current = item ?: return null
        if (!ready) return null
        val idleMs = nowMs() - readyAt
        if (maxIdleMs < Long.MAX_VALUE && idleMs >= maxIdleMs) {
            item = null
            ready = false
            readyAt = 0L
            generation += 1
            retire(current)
            return null
        }
        item = null
        ready = false
        readyAt = 0L
        return current
    }

    /** Retires the held spare (ready or warming) and returns `1` if one was retired, else `0`. */
    fun drop(): Int {
        val current = item ?: return 0
        item = null
        ready = false
        readyAt = 0L
        generation += 1
        retire(current)
        return 1
    }
}

/**
 * Determines whether an iOS container creation may consume the warmed `Default`-scope spare.
 *
 * Unlike legacy warm pools, `allowPinchZoom = true` surfaces (such as Artifact Viewer) ARE eligible
 * because the warm spare configures `ignoresViewportScaleLimits = true` and enforces pinch zoom
 * dynamically via `scrollView.pinchGestureRecognizer`.
 */
fun isIosWarmSpareEligible(
    isolationScope: WebIsolationScope,
    allowScripts: Boolean,
    hasCustomScheme: Boolean,
    isRecoveryOrRebuild: Boolean,
): Boolean =
    isolationScope == WebIsolationScope.Default &&
        allowScripts &&
        !hasCustomScheme &&
        !isRecoveryOrRebuild

/**
 * Returns `true` when [navigationUrl] is a leftover callback from a warm spare's initial
 * `about:blank` warm-up document rather than the surface's configured document.
 */
fun shouldIgnoreWarmSpareBlankNavigation(
    usingSpare: Boolean,
    navigationUrl: String?,
    configuredUrl: String,
): Boolean {
    if (!usingSpare || navigationUrl != WEB_CONTAINER_SPARE_BLANK_URL) return false
    val trimmed = configuredUrl.trim()
    return trimmed.isNotEmpty() && trimmed != WEB_CONTAINER_SPARE_BLANK_URL
}
