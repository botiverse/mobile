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
