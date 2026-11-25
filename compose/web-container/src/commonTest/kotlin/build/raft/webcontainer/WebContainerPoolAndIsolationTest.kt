package build.raft.webcontainer

import build.raft.webcontainer.jsb.JsbBootstrapScripts
import build.raft.webcontainer.pool.WEB_CONTAINER_SPARE_BLANK_URL
import build.raft.webcontainer.pool.WebContainerKeepAlivePool
import build.raft.webcontainer.pool.WebContainerPoolKey
import build.raft.webcontainer.pool.WebContainerSpareSlot
import build.raft.webcontainer.pool.WebIsolationClearResult
import build.raft.webcontainer.pool.WebIsolationScopeManager
import build.raft.webcontainer.pool.WebMemoryTrimLevel
import build.raft.webcontainer.pool.WebPooledContainer
import build.raft.webcontainer.pool.isIosWarmSpareEligible
import build.raft.webcontainer.pool.shouldIgnoreWarmSpareBlankNavigation
import build.raft.webcontainer.resource.CompositeWebResourceInterceptor
import build.raft.webcontainer.resource.WebOfflineBundleInterceptor
import build.raft.webcontainer.resource.WebResourceInterceptor
import build.raft.webcontainer.resource.WebResourceRequest
import build.raft.webcontainer.resource.WebResourceResponseData
import build.raft.webcontainer.resource.WebResourceSource
import build.raft.webcontainer.resource.inferWebMimeType
import build.raft.webcontainer.resource.isValidIosCustomScheme
import build.raft.webcontainer.resource.sanitizeRelativeBundlePath
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WebContainerPoolAndIsolationTest {
    private class FakePooledContainer(
        override val isolationScope: WebIsolationScope,
        override val isDarkTheme: Boolean = false,
        var healthy: Boolean = true,
    ) : WebPooledContainer {
        var destroyed: Boolean = false
            private set

        override fun isHealthy(): Boolean = healthy && !destroyed

        override fun destroy() {
            destroyed = true
        }
    }

    @Test
    fun keepAlivePoolEnforcesLruTtlThemeScopeAndAntiZombieHealth() {
        var now = 1_000L
        val pool = WebContainerKeepAlivePool<FakePooledContainer>(maxEntries = 2, ttlMs = 10_000L, nowMs = { now })
        val scope = WebIsolationScope.Named("app_a")

        val k1 = WebContainerPoolKey("k1", scope, isDarkTheme = false)
        val k2 = WebContainerPoolKey("k2", scope, isDarkTheme = false)
        val k3 = WebContainerPoolKey("k3", scope, isDarkTheme = false)

        val c1 = FakePooledContainer(scope)
        val c2 = FakePooledContainer(scope)
        val c3 = FakePooledContainer(scope)

        assertTrue(pool.park(k1, c1))
        assertTrue(pool.park(k2, c2))
        // LRU evicts c1 when k3 is parked.
        assertTrue(pool.park(k3, c3))
        assertTrue(c1.destroyed)
        assertNull(pool.take(k1))

        // Anti-zombie check: unhealthy entry is destroyed on take and never vended.
        c2.healthy = false
        assertNull(pool.take(k2))
        assertTrue(c2.destroyed)

        // Theme mismatch does not match.
        assertNull(pool.take(WebContainerPoolKey("k3", scope, isDarkTheme = true)))

        // TTL expiry destroys stale entry on next take.
        now += 10_001L
        assertNull(pool.take(k3))
        assertTrue(c3.destroyed)
    }

    @Test
    fun keepAlivePoolPeekInspectsWithoutRemovingAndEvictsUnhealthyOrExpired() {
        var now = 1_000L
        val pool = WebContainerKeepAlivePool<FakePooledContainer>(maxEntries = 2, ttlMs = 5_000L, nowMs = { now })
        val scope = WebIsolationScope.Default
        val k1 = WebContainerPoolKey("k1", scope, isDarkTheme = false)
        val k2 = WebContainerPoolKey("k2", scope, isDarkTheme = false)
        val c1 = FakePooledContainer(scope)
        val c2 = FakePooledContainer(scope)

        assertTrue(pool.park(k1, c1))
        assertTrue(pool.park(k2, c2))
        assertSame(c1, pool.peek(k1))
        assertEquals(2, pool.size)

        c1.healthy = false
        assertNull(pool.peek(k1))
        assertTrue(c1.destroyed)
        assertEquals(1, pool.size)

        now += 5_001L
        assertNull(pool.peek(k2))
        assertTrue(c2.destroyed)
        assertEquals(0, pool.size)
    }

    @Test
    fun keepAlivePoolRespondsToMemoryPressure() {
        val pool = WebContainerKeepAlivePool<FakePooledContainer>(maxEntries = 4, ttlMs = 60_000L, nowMs = { 0L })
        val scope = WebIsolationScope.Default
        val containers = (1..4).map { FakePooledContainer(scope) }
        containers.forEachIndexed { idx, c ->
            pool.park(WebContainerPoolKey("k$idx", scope, isDarkTheme = false), c)
        }

        val moderateEvicted = pool.onMemoryPressure(WebMemoryTrimLevel.MODERATE)
        assertEquals(2, moderateEvicted)
        assertEquals(2, pool.size)
        assertTrue(containers[0].destroyed && containers[1].destroyed)
        assertFalse(containers[2].destroyed || containers[3].destroyed)

        val criticalEvicted = pool.onMemoryPressure(WebMemoryTrimLevel.CRITICAL)
        assertEquals(2, criticalEvicted)
        assertEquals(0, pool.size)
        assertTrue(containers[2].destroyed && containers[3].destroyed)
    }

    @Test
    fun isolationScopeManagerEvictsPooledBeforeDeleteAndDefersWhileForegroundActive() {
        val pool = WebContainerKeepAlivePool<FakePooledContainer>(maxEntries = 4, ttlMs = 60_000L, nowMs = { 0L })
        val manager = WebIsolationScopeManager()
        val scopeA = WebIsolationScope.Named("app_a")
        val scopeB = WebIsolationScope.Named("app_b")

        val pooledA = FakePooledContainer(scopeA)
        val pooledB = FakePooledContainer(scopeB)
        pool.park(WebContainerPoolKey("a1", scopeA, false), pooledA)
        pool.park(WebContainerPoolKey("b1", scopeB, false), pooledB)

        // Foreground instance active for scopeA -> pooledA evicted immediately, store delete deferred.
        manager.registerActive(scopeA)
        val callOrder = mutableListOf<String>()
        val deferredResult =
            manager.clearIsolationScope(
                scope = scopeA,
                evictPooled = { target ->
                    callOrder += "evict"
                    pool.evictScope(target)
                },
                deletePlatformStore = {
                    callOrder += "delete"
                    assertTrue(pooledA.destroyed, "Pooled views MUST be destroyed before platform store delete")
                    true
                },
            )
        assertEquals(WebIsolationClearResult.DEFERRED_ACTIVE_INSTANCES, deferredResult)
        assertTrue(pooledA.destroyed)
        assertFalse(pooledB.destroyed)
        assertEquals(listOf("evict"), callOrder)
        assertTrue(manager.hasPendingDeletion(scopeA))

        // When final active view closes, deferred deletion fires automatically.
        val completion = manager.unregisterActive(scopeA)
        assertEquals(WebIsolationClearResult.CLEARED, completion)
        assertEquals(listOf("evict", "evict", "delete"), callOrder)
        assertFalse(manager.hasPendingDeletion(scopeA))
    }

    @Test
    fun offlineBundleInterceptorServesAssetsBlocksTraversalAndFallsBackSafely() {
        val files =
            mapOf(
                "index.html" to "<h1>ok</h1>".encodeToByteArray(),
                "assets/app.js" to "console.log(1);".encodeToByteArray(),
            )
        val interceptor =
            WebOfflineBundleInterceptor(
                httpsBaseUrlPrefix = "https://app.raft.build/bundle",
                iosCustomScheme = "raft-bundle",
                assetLoader = { path ->
                    if (path == "corrupt.js") error("disk I/O failure")
                    files[path]
                },
            )

        // Android https prefix hit (root defaults to index.html)
        val indexResp = interceptor.intercept(WebResourceRequest("https://app.raft.build/bundle/?v=1"))
        assertNotNull(indexResp)
        assertEquals("text/html", indexResp.mimeType)
        assertEquals(WebResourceSource.OFFLINE_BUNDLE, indexResp.source)
        assertContentEquals("<h1>ok</h1>".encodeToByteArray(), indexResp.body)

        // iOS custom scheme hit
        val jsResp = interceptor.intercept(WebResourceRequest("raft-bundle://hands/assets/app.js"))
        assertNotNull(jsResp)
        assertEquals("application/javascript", jsResp.mimeType)

        // Path traversal strictly rejected (fallback null)
        assertNull(interceptor.intercept(WebResourceRequest("https://app.raft.build/bundle/../secret.txt")))
        assertNull(interceptor.intercept(WebResourceRequest("https://app.raft.build/bundle/%2e%2e/secret.txt")))
        assertNull(sanitizeRelativeBundlePath("assets\\..\\secret.txt"))

        // Missing or unreadable asset falls back cleanly to network (null)
        assertNull(interceptor.intercept(WebResourceRequest("https://app.raft.build/bundle/missing.css")))
        assertNull(interceptor.intercept(WebResourceRequest("https://app.raft.build/bundle/corrupt.js")))

        // Composite interceptor survives throwing child and falls through to next interceptor
        val fallbackResp = WebResourceResponseData(mimeType = "text/plain", body = "ok".encodeToByteArray())
        val composite =
            CompositeWebResourceInterceptor(
                listOf(
                    WebResourceInterceptor { error("boom") },
                    WebResourceInterceptor { fallbackResp },
                ),
            )
        assertSame(fallbackResp, composite.intercept(WebResourceRequest("https://app.raft.build/x")))
    }

    @Test
    fun iosCustomSchemeValidationRejectsBuiltInWebKitSchemes() {
        assertTrue(isValidIosCustomScheme("raft-bundle"))
        assertTrue(isValidIosCustomScheme("raft.app"))
        assertFalse(isValidIosCustomScheme("http"))
        assertFalse(isValidIosCustomScheme("https"))
        assertFalse(isValidIosCustomScheme("HTTPS"))
        assertFalse(isValidIosCustomScheme("file"))
        assertFalse(isValidIosCustomScheme("about"))
        assertFalse(isValidIosCustomScheme("javascript"))
        assertFalse(isValidIosCustomScheme("1bad"))
        assertEquals("font/woff2", inferWebMimeType("fonts/inter.woff2"))
    }

    @Test
    fun warmSpareSlotEnforcesGenerationTtlZombieInvalidationAndDrop() {
        var now = 10_000L
        val retired = mutableListOf<String>()
        val slot = WebContainerSpareSlot<String>(nowMs = { now }, retire = { retired += it })

        // Warming spare cannot be taken until its exact generation finishes.
        val g1 = slot.put("spare-1")
        assertTrue(slot.hasItem)
        assertFalse(slot.isReady)
        assertNull(slot.take())
        assertFalse(slot.markReady(g1 - 1))
        assertTrue(slot.markReady(g1))
        assertTrue(slot.isReady)
        assertEquals(10_000L, slot.readyAtMs)

        // Taking hands out the ready spare and empties the slot without retiring it.
        assertEquals("spare-1", slot.take(maxIdleMs = 60_000L))
        assertFalse(slot.hasItem)
        assertTrue(retired.isEmpty())

        // Stale / expired ready spare is retired instead of vended.
        val g2 = slot.put("spare-2")
        assertTrue(slot.markReady(g2))
        now += 60_000L
        assertNull(slot.take(maxIdleMs = 60_000L))
        assertEquals(listOf("spare-2"), retired)
        assertFalse(slot.hasItem)

        // Idle zombie termination evicts matching spare immediately and ignores mismatched targets.
        val g3 = slot.put("spare-3")
        assertTrue(slot.markReady(g3))
        assertFalse(slot.invalidateIfSame("other-spare"))
        assertTrue(slot.isReady)
        assertTrue(slot.invalidateIfSame("spare-3"))
        assertFalse(slot.hasItem)
        assertEquals(listOf("spare-2", "spare-3"), retired)

        // Replacing retires prior spare; drop returns exact eviction count.
        slot.put("spare-4")
        slot.put("spare-5")
        assertEquals(listOf("spare-2", "spare-3", "spare-4"), retired)
        assertEquals(1, slot.drop())
        assertEquals(0, slot.drop())
        assertEquals(listOf("spare-2", "spare-3", "spare-4", "spare-5"), retired)
    }

    @Test
    fun iosWarmSpareEligibilityBlankGuardAndBlankCanvasScript() {
        // Default + scripts + no custom scheme + initial build -> eligible (including pinch-zoom Artifact Viewer).
        assertTrue(
            isIosWarmSpareEligible(
                isolationScope = WebIsolationScope.Default,
                allowScripts = true,
                hasCustomScheme = false,
                isRecoveryOrRebuild = false,
            ),
        )
        assertFalse(isIosWarmSpareEligible(WebIsolationScope.Ephemeral, true, false, false))
        assertFalse(isIosWarmSpareEligible(WebIsolationScope.Named("raft_third_party"), true, false, false))
        assertFalse(isIosWarmSpareEligible(WebIsolationScope.Default, false, false, false))
        assertFalse(isIosWarmSpareEligible(WebIsolationScope.Default, true, true, false))
        assertFalse(isIosWarmSpareEligible(WebIsolationScope.Default, true, false, true))

        assertTrue(shouldIgnoreWarmSpareBlankNavigation(true, WEB_CONTAINER_SPARE_BLANK_URL, "https://app.raft.build/a/1"))
        assertFalse(shouldIgnoreWarmSpareBlankNavigation(false, WEB_CONTAINER_SPARE_BLANK_URL, "https://app.raft.build/a/1"))
        assertFalse(shouldIgnoreWarmSpareBlankNavigation(true, "https://app.raft.build/a/1", "https://app.raft.build/a/1"))
        assertFalse(shouldIgnoreWarmSpareBlankNavigation(true, WEB_CONTAINER_SPARE_BLANK_URL, WEB_CONTAINER_SPARE_BLANK_URL))
        assertFalse(shouldIgnoreWarmSpareBlankNavigation(true, WEB_CONTAINER_SPARE_BLANK_URL, ""))

        val script = JsbBootstrapScripts.buildBlankCanvasPaintScript(0xFF123456.toInt(), isDark = true)
        assertTrue(script.contains("rgb(18,52,86)"))
        assertTrue(script.contains("color-scheme','dark"))
    }
}
