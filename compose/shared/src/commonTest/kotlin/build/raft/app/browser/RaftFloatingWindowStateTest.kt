package build.raft.app

import build.raft.app.data.ArtifactKind
import build.raft.app.data.ArtifactLinkTarget
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaftFloatingWindowStateTest {
    private val webOrigin = "https://app.raft.build"
    private val envApi = "https://api.raft.build/api"
    private val envKey = "$webOrigin|$envApi"
    private val scopeA = RaftArtifactViewerScope(webOrigin, envApi, "acct-1", "srv-1")

    private fun artifactWindow(
        index: Int,
        scope: RaftArtifactViewerScope = scopeA,
        version: Int? = null,
        environmentKey: String? = envKey,
    ): RaftFloatingArtifactWindow {
        val id = "pt_00000000-0000-4000-8000-${index.toString().padStart(12, '0')}"
        val target = ArtifactLinkTarget(id, version)
        return RaftFloatingArtifactWindow(
            artifactId = id,
            canonicalId = target.canonicalId,
            version = version,
            title = "Artifact $index",
            kind = ArtifactKind.Html,
            scope = scope,
            conversationId = "conv-1",
            hostHead = null,
            langTag = "en",
            keepAliveKey = scope.artifactKeepAliveKey(target, "conv-1", null, "en"),
            embeddedPageUrl = raftArtifactViewerEmbeddedPageUrl(scope.webOrigin, target, "conv-1", null, "en"),
            desktopMode = false,
            environmentKey = environmentKey,
        )
    }

    private fun openAppTab(
        webPanelCoordinator: RaftMobileWebPanelCoordinator,
        index: Int,
        accountId: String = "acct-1",
        serverId: String = "srv-1",
        environment: String = envKey,
    ): RaftAppBrowserTab =
        webPanelCoordinator.openOrActivateApp(
            launchUrl = "https://app$index.example/home",
            appEntryToken = "tok-$index",
            request =
                RaftAppEntryRequest(
                    scopeOrigin = "https://app$index.example",
                    appName = "App $index",
                    preferredServerId = serverId,
                ),
            accountId = accountId,
            serverId = serverId,
            environmentKey = environment,
        ).tab

    private fun assertNear(expected: Float, actual: Float, tolerance: Float = 0.5f, label: String = "") {
        assertTrue(
            abs(expected - actual) <= tolerance,
            "$label expected=$expected actual=$actual (tolerance=$tolerance)",
        )
    }

    @Test
    fun miniWindowGeometryResolvesDefaultsClampsSafeAreasImeRotationTinyViewportAndNonFiniteInputs() {
        val phoneViewport =
            RaftFloatingMiniWindowViewportPx(
                viewportWidthPx = 400f,
                viewportHeightPx = 844f,
                leftSafeAreaPx = 0f,
                rightSafeAreaPx = 0f,
                topSafeAreaPx = 44f,
                bottomSafeAreaPx = 34f,
                imeBottomPx = 0f,
                headerGapPx = 8f,
                bottomExtraPx = 12f,
                horizontalMarginPx = 8f,
                imeGapPx = 8f,
                density = 1f,
            )

        // Default portrait phone: availableWidth = 384 -> 80% = 307.2 (<= 320); availableHeight = 746 -> 48% = 358.08 (<= 380).
        val defaultBounds =
            RaftFloatingMiniWindowGeometry.resolveBounds(
                placement = RaftFloatingMiniWindowPlacement(),
                viewport = phoneViewport,
            )
        assertNear(307.2f, defaultBounds.widthPx, label = "default width")
        assertNear(358.08f, defaultBounds.heightPx, label = "default height")
        assertTrue(defaultBounds.leftPx >= 8f)
        assertTrue(defaultBounds.rightPx <= 392f)
        assertTrue(defaultBounds.topPx >= 52f)
        assertTrue(defaultBounds.bottomPx <= 798f)

        // Tablet viewport enforces max cap 440dp x 580dp.
        val tabletViewport =
            RaftFloatingMiniWindowViewportPx(
                viewportWidthPx = 1024f,
                viewportHeightPx = 1280f,
                topSafeAreaPx = 24f,
                bottomSafeAreaPx = 24f,
                horizontalMarginPx = 8f,
                density = 1f,
            )
        val oversizedBounds =
            RaftFloatingMiniWindowGeometry.resolveBounds(
                placement = RaftFloatingMiniWindowPlacement(preferredWidthDp = 900f, preferredHeightDp = 900f),
                viewport = tabletViewport,
            )
        assertNear(RAFT_MINI_WINDOW_MAX_WIDTH_DP_VALUE, oversizedBounds.widthPx, label = "tablet max width")
        assertNear(RAFT_MINI_WINDOW_MAX_HEIGHT_DP_VALUE, oversizedBounds.heightPx, label = "tablet max height")

        // Undersized preference enforces min cap 220dp x 220dp.
        val undersizedBounds =
            RaftFloatingMiniWindowGeometry.resolveBounds(
                placement = RaftFloatingMiniWindowPlacement(preferredWidthDp = 80f, preferredHeightDp = 90f),
                viewport = phoneViewport,
            )
        assertNear(RAFT_MINI_WINDOW_MIN_WIDTH_DP_VALUE, undersizedBounds.widthPx, label = "min width")
        assertNear(RAFT_MINI_WINDOW_MIN_HEIGHT_DP_VALUE, undersizedBounds.heightPx, label = "min height")

        // Live IME clamps bottom strictly above keyboard while preserving preferred size once IME closes.
        val userPlacement =
            RaftFloatingMiniWindowPlacement(
                preferredWidthDp = 320f,
                preferredHeightDp = 420f,
                xFraction = 0.5f,
                yFraction = 0.9f,
            )
        val noImeBounds = RaftFloatingMiniWindowGeometry.resolveBounds(userPlacement, phoneViewport)
        assertNear(420f, noImeBounds.heightPx, label = "preferred height without IME")
        val imeViewport = phoneViewport.copy(imeBottomPx = 340f)
        val withImeBounds = RaftFloatingMiniWindowGeometry.resolveBounds(userPlacement, imeViewport)
        assertTrue(
            withImeBounds.bottomPx <= imeViewport.viewportHeightPx - imeViewport.imeBottomPx - imeViewport.imeGapPx + 0.5f,
            "Window must stay strictly above IME: bottom=${withImeBounds.bottomPx}",
        )
        // Dragging while IME is open preserves preferredHeightDp when preservePreferredSizeFrom is supplied.
        val draggedDuringIme = RaftFloatingMiniWindowGeometry.dragBy(withImeBounds, -20f, -10f, imeViewport)
        val savedAfterImeDrag =
            RaftFloatingMiniWindowGeometry.boundsToPlacement(
                bounds = draggedDuringIme,
                viewport = imeViewport,
                preservePreferredSizeFrom = userPlacement,
            )
        assertEquals(320f, savedAfterImeDrag.preferredWidthDp)
        assertEquals(420f, savedAfterImeDrag.preferredHeightDp)
        val restoredAfterIme = RaftFloatingMiniWindowGeometry.resolveBounds(savedAfterImeDrag, phoneViewport)
        assertNear(420f, restoredAfterIme.heightPx, label = "preferred height restored after IME dismisses")

        // Landscape rotation with left display cutout keeps window inside horizontal safe insets.
        val landscapeViewport =
            RaftFloatingMiniWindowViewportPx(
                viewportWidthPx = 844f,
                viewportHeightPx = 390f,
                leftSafeAreaPx = 44f,
                rightSafeAreaPx = 12f,
                topSafeAreaPx = 16f,
                bottomSafeAreaPx = 21f,
                horizontalMarginPx = 8f,
                density = 1f,
            )
        val rotatedBounds = RaftFloatingMiniWindowGeometry.resolveBounds(userPlacement, landscapeViewport)
        assertTrue(rotatedBounds.leftPx >= 52f)
        assertTrue(rotatedBounds.rightPx <= 824f)
        assertTrue(rotatedBounds.topPx >= 16f)
        assertTrue(rotatedBounds.bottomPx <= 369f)

        // Tiny / narrow viewport degrades cleanly without overflowing or throwing.
        val tinyViewport =
            RaftFloatingMiniWindowViewportPx(
                viewportWidthPx = 180f,
                viewportHeightPx = 160f,
                topSafeAreaPx = 10f,
                bottomSafeAreaPx = 10f,
                horizontalMarginPx = 8f,
                density = 1f,
            )
        val tinyBounds = RaftFloatingMiniWindowGeometry.resolveBounds(userPlacement, tinyViewport)
        assertNear(164f, tinyBounds.widthPx, label = "tiny viewport width")
        assertNear(140f, tinyBounds.heightPx, label = "tiny viewport height")
        assertNear(8f, tinyBounds.leftPx)
        assertNear(10f, tinyBounds.topPx)

        // Non-finite inputs never leak NaN or Infinity.
        val nanPlacement =
            RaftFloatingMiniWindowPlacement(
                preferredWidthDp = Float.NaN,
                preferredHeightDp = Float.POSITIVE_INFINITY,
                xFraction = Float.NEGATIVE_INFINITY,
                yFraction = Float.NaN,
            )
        val nanViewport =
            phoneViewport.copy(
                leftSafeAreaPx = Float.NaN,
                imeBottomPx = Float.POSITIVE_INFINITY,
                density = Float.NaN,
            )
        val safeNanBounds = RaftFloatingMiniWindowGeometry.resolveBounds(nanPlacement, nanViewport)
        assertTrue(safeNanBounds.leftPx.isFinite())
        assertTrue(safeNanBounds.topPx.isFinite())
        assertTrue(safeNanBounds.widthPx.isFinite())
        assertTrue(safeNanBounds.heightPx.isFinite())
    }

    @Test
    fun miniWindowResizeBySupportsBothBottomRightAndBottomLeftCornersWithStrictAnchoredEdgesAndClamping() {
        val viewport =
            RaftFloatingMiniWindowViewportPx(
                viewportWidthPx = 420f,
                viewportHeightPx = 844f,
                topSafeAreaPx = 44f,
                bottomSafeAreaPx = 34f,
                headerGapPx = 8f,
                bottomExtraPx = 12f,
                horizontalMarginPx = 8f,
                density = 1f,
            )
        val start =
            RaftFloatingMiniWindowBoundsPx(
                leftPx = 80f,
                topPx = 120f,
                widthPx = 260f,
                heightPx = 300f,
            )

        // 1. Bottom-Right expand: left (80) & top (120) stay strictly anchored; width & height grow.
        val seExpanded =
            RaftFloatingMiniWindowGeometry.resizeBy(
                bounds = start,
                corner = RaftFloatingMiniWindowResizeCorner.BottomRight,
                deltaXPx = 40f,
                deltaYPx = 60f,
                viewport = viewport,
            )
        assertNear(80f, seExpanded.leftPx, label = "SE anchored left")
        assertNear(120f, seExpanded.topPx, label = "SE anchored top")
        assertNear(300f, seExpanded.widthPx, label = "SE expanded width")
        assertNear(360f, seExpanded.heightPx, label = "SE expanded height")

        // 2. Bottom-Right shrink past minimum clamps cleanly at 220 x 220 with left/top still anchored.
        val seClampedMin =
            RaftFloatingMiniWindowGeometry.resizeBy(
                bounds = start,
                corner = RaftFloatingMiniWindowResizeCorner.BottomRight,
                deltaXPx = -200f,
                deltaYPx = -200f,
                viewport = viewport,
            )
        assertNear(80f, seClampedMin.leftPx)
        assertNear(120f, seClampedMin.topPx)
        assertNear(220f, seClampedMin.widthPx)
        assertNear(220f, seClampedMin.heightPx)

        // 3. Bottom-Right expand past right safe edge (412) stops flush at right safe edge.
        val seClampedRight =
            RaftFloatingMiniWindowGeometry.resizeBy(
                bounds = start,
                corner = RaftFloatingMiniWindowResizeCorner.BottomRight,
                deltaXPx = 300f,
                deltaYPx = 0f,
                viewport = viewport,
            )
        assertNear(80f, seClampedRight.leftPx)
        assertNear(412f, seClampedRight.rightPx, label = "SE stops flush at right safe bound")

        // 4. Bottom-Left expand (dragging LEFT with negative dx): right (340) & top (120) stay strictly anchored!
        val swExpanded =
            RaftFloatingMiniWindowGeometry.resizeBy(
                bounds = start,
                corner = RaftFloatingMiniWindowResizeCorner.BottomLeft,
                deltaXPx = -50f,
                deltaYPx = 40f,
                viewport = viewport,
            )
        assertNear(start.rightPx, swExpanded.rightPx, label = "SW anchored right")
        assertNear(start.topPx, swExpanded.topPx, label = "SW anchored top")
        assertNear(310f, swExpanded.widthPx, label = "SW expanded width")
        assertNear(30f, swExpanded.leftPx, label = "SW shifted left")
        assertNear(340f, swExpanded.heightPx, label = "SW expanded height")

        // 5. Bottom-Left shrink past minimum (dragging RIGHT with positive dx): right edge stays anchored at 340!
        val swClampedMin =
            RaftFloatingMiniWindowGeometry.resizeBy(
                bounds = start,
                corner = RaftFloatingMiniWindowResizeCorner.BottomLeft,
                deltaXPx = 200f,
                deltaYPx = -200f,
                viewport = viewport,
            )
        assertNear(start.rightPx, swClampedMin.rightPx, label = "SW min-clamped anchored right")
        assertNear(220f, swClampedMin.widthPx)
        assertNear(120f, swClampedMin.leftPx)
        assertNear(220f, swClampedMin.heightPx)

        // 6. Bottom-Left expand past left safe edge (8) stops flush at minX = 8 while right stays anchored at 340!
        val swClampedLeft =
            RaftFloatingMiniWindowGeometry.resizeBy(
                bounds = start,
                corner = RaftFloatingMiniWindowResizeCorner.BottomLeft,
                deltaXPx = -400f,
                deltaYPx = 0f,
                viewport = viewport,
            )
        assertNear(8f, swClampedLeft.leftPx, label = "SW stops flush at left safe bound")
        assertNear(start.rightPx, swClampedLeft.rightPx, label = "SW right stays anchored")
        assertNear(332f, swClampedLeft.widthPx)

        // 7. Non-finite resize deltas are ignored safely.
        val swNan =
            RaftFloatingMiniWindowGeometry.resizeBy(
                bounds = start,
                corner = RaftFloatingMiniWindowResizeCorner.BottomLeft,
                deltaXPx = Float.NaN,
                deltaYPx = Float.POSITIVE_INFINITY,
                viewport = viewport,
            )
        assertEquals(start, swNan)
    }

    @Test
    fun unifiedCoordinatorManagesAppAndArtifactLifecycleSingleMiniWindowAndFullSessionWipe() {
        val releasedTokens = mutableListOf<String>()
        val evictedAppKeys = mutableListOf<String>()
        val evictedArtifactKeys = mutableListOf<String>()
        val webPanelCoordinator =
            RaftMobileWebPanelCoordinator(
                releaseEntryToken = { token, _ -> releasedTokens += token },
                evictKeepAlive = { key -> evictedAppKeys += key },
            )
        val coordinator =
            RaftFloatingWindowCoordinator(
                webPanelCoordinator = webPanelCoordinator,
                maxArtifacts = 5,
                evictArtifactKeepAlive = { _, key -> evictedArtifactKeys += key },
            )

        // 1. Open App 1 FullScreen -> float to MiniWindow -> visible in MiniWindow, EdgePill hidden.
        val app1 = openAppTab(webPanelCoordinator, 1)
        val app1Id = RaftFloatingWindowId.AppTab(app1.tabId)
        assertEquals(RaftFloatingWindowPresentation.FullScreen, coordinator.presentationOf(app1Id))
        assertFalse(coordinator.currentSnapshot().showMiniWindow)
        assertFalse(coordinator.currentSnapshot().showEdgePill)

        assertTrue(coordinator.floatAppTabInMiniWindow(app1.tabId))
        assertEquals(RaftFloatingWindowPresentation.MiniWindow, coordinator.presentationOf(app1Id))
        assertTrue(coordinator.currentSnapshot().showMiniWindow)
        assertFalse(coordinator.currentSnapshot().showEdgePill)
        assertTrue(webPanelCoordinator.isKeepAliveEligible(app1.keepAliveKey))

        // 2. Float Artifact 1 -> becomes active MiniWindow and collapses App 1 cleanly into EdgePill!
        val art1 = artifactWindow(1)
        coordinator.floatArtifactInMiniWindow(art1)
        assertEquals(RaftFloatingWindowPresentation.MiniWindow, coordinator.presentationOf(art1.windowId))
        assertEquals(RaftFloatingWindowPresentation.EdgePill, coordinator.presentationOf(app1Id))
        assertEquals(art1.windowId, coordinator.currentSnapshot().activeMiniWindowEntry?.id)
        assertEquals(2, coordinator.currentSnapshot().minimizedEntries.size)
        assertEquals(art1.windowId, coordinator.currentSnapshot().minimizedEntries.first().id)

        // 3. Collapse MiniWindow -> EdgePill shows with BOTH Artifact 1 and App 1 warm underneath.
        assertTrue(coordinator.collapseMiniWindowToEdgePill())
        assertEquals(RaftFloatingWindowPresentation.EdgePill, coordinator.presentationOf(art1.windowId))
        assertEquals(RaftFloatingWindowPresentation.EdgePill, coordinator.presentationOf(app1Id))
        assertFalse(coordinator.currentSnapshot().showMiniWindow)
        assertTrue(coordinator.currentSnapshot().showEdgePill)
        assertTrue(evictedAppKeys.isEmpty())
        assertTrue(evictedArtifactKeys.isEmpty())

        // 4. Open App 1 back in MiniWindow from EdgePill -> App 1 active MiniWindow, Artifact 1 stays in EdgePill.
        assertTrue(coordinator.openInMiniWindow(app1Id))
        assertEquals(RaftFloatingWindowPresentation.MiniWindow, coordinator.presentationOf(app1Id))
        assertEquals(RaftFloatingWindowPresentation.EdgePill, coordinator.presentationOf(art1.windowId))

        // 5. Memory pressure while App 1 is visible in MiniWindow protects App 1 from hibernation!
        val app2 = openAppTab(webPanelCoordinator, 2)
        assertTrue(coordinator.floatAppTabInMiniWindow(app1.tabId))
        val hibernated = coordinator.hibernateBackgroundWindows()
        assertEquals(listOf(app2.tabId), hibernated.map { it.tabId })
        assertTrue(webPanelCoordinator.tabById(app1.tabId)?.isLive == true)
        assertFalse(webPanelCoordinator.tabById(app2.tabId)?.isLive == true)

        // 6. Expand Artifact 1 to FullScreen -> MiniWindow hides, Artifact 1 is FullScreen.
        val expandedArt1 = coordinator.prepareExpandArtifactToFullScreen(art1.windowKey)
        assertNotNull(expandedArt1)
        assertEquals(RaftFloatingWindowPresentation.FullScreen, coordinator.presentationOf(art1.windowId))
        assertFalse(coordinator.currentSnapshot().showMiniWindow)
        assertFalse(coordinator.currentSnapshot().showEdgePill)

        // Normal full-screen close removes Artifact 1 from floating list without evicting normal multi-slot cache.
        coordinator.onArtifactFullScreenClosed(art1.windowKey, keepFloating = false)
        assertNull(coordinator.presentationOf(art1.windowId))
        assertNull(coordinator.artifactByKey(art1.windowKey))

        // 7. Opening 6 Artifacts enforces max 5 cap and evicts oldest Artifact's keep-alive slot.
        val arts = (1..6).map { artifactWindow(it) }
        arts.forEach { coordinator.floatArtifactInMiniWindow(it) }
        assertEquals(5, coordinator.state.value.artifacts.size)
        assertNull(coordinator.artifactByKey(arts[0].windowKey))
        assertEquals(listOf(arts[0].keepAliveKey), evictedArtifactKeys)

        // 8. Closing a single Artifact evicts only that Artifact's keep-alive slot.
        assertTrue(coordinator.closeArtifact(arts[5].windowKey))
        assertNull(coordinator.artifactByKey(arts[5].windowKey))
        assertTrue(arts[5].keepAliveKey in evictedArtifactKeys)

        // 9. Persisted pill dock & mini-window placement survive closeAll and session switches.
        coordinator.setPillDockPosition(RaftFloatingPillEdge.Left, 0.3f)
        val savedPlacement = RaftFloatingMiniWindowPlacement(preferredWidthDp = 300f, preferredHeightDp = 340f, xFraction = 0.25f, yFraction = 0.4f)
        coordinator.updateMiniWindowPlacement(savedPlacement)

        // 10. Server switch immediately wipes ALL floating Artifacts AND ALL App tabs and evicts every keep-alive slot.
        coordinator.onSessionContextChanged(accountId = "acct-1", serverId = "srv-2", environmentKey = envKey)
        assertTrue(coordinator.state.value.artifacts.isEmpty())
        assertTrue(webPanelCoordinator.state.value.tabs.isEmpty())
        assertNull(coordinator.state.value.activeMiniWindow)
        assertEquals(RaftFloatingPillEdge.Left, coordinator.currentSnapshot().pillEdge)
        assertEquals(savedPlacement, coordinator.state.value.miniWindowPlacement)
        for (i in 1..4) {
            assertTrue(arts[i].keepAliveKey in evictedArtifactKeys, "Expected artifact $i keep-alive evicted on server switch")
        }
        assertTrue(app1.keepAliveKey in evictedAppKeys)
        assertTrue("tok-1" in releasedTokens)

        // 11. Logout, account switch, and environment switch each wipe everything cleanly.
        val artAfter = artifactWindow(10)
        coordinator.floatArtifactInMiniWindow(artAfter)
        openAppTab(webPanelCoordinator, 10)
        coordinator.onSessionContextChanged(accountId = null, serverId = null, environmentKey = envKey)
        assertTrue(coordinator.state.value.artifacts.isEmpty())
        assertTrue(webPanelCoordinator.state.value.tabs.isEmpty())
        assertTrue(artAfter.keepAliveKey in evictedArtifactKeys)

        val artEnv = artifactWindow(11)
        coordinator.floatArtifactInMiniWindow(artEnv)
        coordinator.onSessionContextChanged(
            accountId = "acct-1",
            serverId = "srv-1",
            environmentKey = "https://raft-app-staging.botiverse.dev|https://api-aws-staging.botiverse.dev/api",
        )
        assertTrue(coordinator.state.value.artifacts.isEmpty())
        assertTrue(artEnv.keepAliveKey in evictedArtifactKeys)
    }

    @Test
    fun artifactFloatWindowHostActionParsesCleanlyWithoutLeavingArtifact() {
        val web = "https://app.raft.build"
        val id = "pt_c82e597f-5434-4741-909b-d1c8a7a531c8"
        val url = "${raftArtifactViewerEmbeddedPageUrl(web, ArtifactLinkTarget(id, 2))}#host=float-window"

        assertTrue("float-window" in RAFT_ARTIFACT_VIEWER_HOST_MENU)
        assertEquals(RaftArtifactViewerHostAction.FloatWindow, raftArtifactViewerHostAction(url))
        assertEquals(RaftArtifactViewerNavigation.Stay(2), raftArtifactViewerNavigation(url, web, id))
        assertEquals(
            false,
            raftArtifactViewerHostDesktopAfterAction(RaftArtifactViewerHostAction.FloatWindow, current = false),
        )
        assertEquals(
            null,
            raftArtifactViewerHostDesktopAfterAction(RaftArtifactViewerHostAction.FloatWindow, current = null),
        )
    }
}
