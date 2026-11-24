@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package build.raft.webcontainer

import build.raft.webcontainer.pool.WebIsolationClearResult
import build.raft.webcontainer.pool.WebIsolationScopeManager
import kotlinx.cinterop.useContents
import platform.Foundation.NSDate
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSUUID
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.WebKit.WKWebsiteDataStore

/**
 * Main-thread singleton registry of `WKWebsiteDataStore` instances for [WebIsolationScope].
 *
 * On iOS 17+, `WKWebsiteDataStore.dataStoreForIdentifier(uuid)` instances are cached per scope ID
 * so every `WKWebView` targeting the same [WebIsolationScope.Named] shares one authoritative store
 * object instead of re-instantiating handles repeatedly. On iOS 15–16, named scopes fall back to
 * `WKWebsiteDataStore.nonPersistentDataStore()` (preserving isolation).
 */
object IosWebContainerDataStoreRegistry {
    private val cachedNamedStores = LinkedHashMap<String, WKWebsiteDataStore>()
    internal val scopeManager = WebIsolationScopeManager()

    private fun osMajorVersion(): Long =
        NSProcessInfo.processInfo.operatingSystemVersion.useContents { majorVersion }

    /** Resolves (and caches for [WebIsolationScope.Named]) the `WKWebsiteDataStore` for [scope]. */
    fun dataStoreFor(
        scope: WebIsolationScope,
        osMajorVersion: Long = osMajorVersion(),
    ): WKWebsiteDataStore {
        return when (scope) {
            WebIsolationScope.Default -> WKWebsiteDataStore.defaultDataStore()
            WebIsolationScope.Ephemeral -> WKWebsiteDataStore.nonPersistentDataStore()
            is WebIsolationScope.Named -> {
                if (osMajorVersion >= 17L) {
                    cachedNamedStores.getOrPut(scope.id) {
                        val uuid = NSUUID(uUIDString = deriveDeterministicScopeUuid(scope.id))
                        WKWebsiteDataStore.dataStoreForIdentifier(uuid)
                    }
                } else {
                    WKWebsiteDataStore.nonPersistentDataStore()
                }
            }
        }
    }

    /**
     * Clears website data (and on iOS 17+, removes the persistent named data store) after active
     * foreground views for [scope] have closed.
     */
    fun clearIsolationScope(
        scope: WebIsolationScope,
        osMajorVersion: Long = osMajorVersion(),
    ): WebIsolationClearResult =
        scopeManager.clearIsolationScope(
            scope = scope,
            evictPooled = { 0 },
            deletePlatformStore = { target -> deletePlatformStore(target, osMajorVersion) },
        )

    private fun deletePlatformStore(
        scope: WebIsolationScope,
        osMajorVersion: Long,
    ): Boolean {
        val epoch = NSDate.dateWithTimeIntervalSince1970(0.0)
        val allTypes = WKWebsiteDataStore.allWebsiteDataTypes()
        return when (scope) {
            WebIsolationScope.Default -> {
                WKWebsiteDataStore.defaultDataStore().removeDataOfTypes(allTypes, epoch) {}
                true
            }
            WebIsolationScope.Ephemeral -> true
            is WebIsolationScope.Named -> {
                val cached = cachedNamedStores.remove(scope.id)
                val uuid = if (osMajorVersion >= 17L) NSUUID(uUIDString = deriveDeterministicScopeUuid(scope.id)) else null
                if (cached != null) {
                    // Chain removeDataStoreForIdentifier AFTER removeDataOfTypes finishes so iOS 17+
                    // does not fail with WKErrorDataStoreInUse from concurrent store operations.
                    cached.removeDataOfTypes(allTypes, epoch) {
                        if (uuid != null) {
                            WKWebsiteDataStore.removeDataStoreForIdentifier(uuid) { _ -> }
                        }
                    }
                } else if (uuid != null) {
                    WKWebsiteDataStore.removeDataStoreForIdentifier(uuid) { _ -> }
                }
                true
            }
        }
    }
}
