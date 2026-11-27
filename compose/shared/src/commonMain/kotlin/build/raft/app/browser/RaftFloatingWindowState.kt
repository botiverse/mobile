package build.raft.app

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.data.ArtifactKind
import build.raft.app.data.ArtifactLinkTarget
import build.raft.app.data.RaftShellStoreScope
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * Unified Floating Window State, Mini-Window Geometry Math, and Process-Wide Coordinator
 * across Installed Raft Apps and Artifact Viewer (dm:@artin:#175 Part 2).
 *
 * Presentation lifecycle:
 * - FullScreen: pushed stack page (`ComposeRaftInAppBrowserPage` / `ComposeRaftArtifactViewerPage`).
 * - MiniWindow: interactive draggable + bottom-left/bottom-right resizable floating window over
 *   Home / Chat / Threads. At most ONE active MiniWindow is visible at a time; floating another
 *   surface collapses the previous MiniWindow cleanly into the bilateral magnetic EdgePill.
 * - EdgePill: collapsed to the Left or Right magnetic edge pill while preserving warm keep-alive
 *   WebViews underneath.
 *
 * Security & session hygiene:
 * - Logout, account switch, server switch, or environment switch immediately clears ALL floating
 *   Artifacts and ALL App tabs, evicting every keep-alive WebView slot cleanly.
 */

internal const val RAFT_FLOATING_ARTIFACT_MAX_WINDOWS: Int = 5

internal const val RAFT_MINI_WINDOW_MIN_WIDTH_DP_VALUE: Float = 220f
internal const val RAFT_MINI_WINDOW_MIN_HEIGHT_DP_VALUE: Float = 220f
internal const val RAFT_MINI_WINDOW_MAX_WIDTH_DP_VALUE: Float = 440f
internal const val RAFT_MINI_WINDOW_MAX_HEIGHT_DP_VALUE: Float = 580f
internal const val RAFT_MINI_WINDOW_DEFAULT_MAX_WIDTH_DP_VALUE: Float = 320f
internal const val RAFT_MINI_WINDOW_DEFAULT_MAX_HEIGHT_DP_VALUE: Float = 380f
internal const val RAFT_MINI_WINDOW_DEFAULT_WIDTH_FRACTION: Float = 0.80f
internal const val RAFT_MINI_WINDOW_DEFAULT_HEIGHT_FRACTION: Float = 0.48f
internal const val RAFT_MINI_WINDOW_MAX_HEIGHT_FRACTION: Float = 0.72f
internal const val RAFT_MINI_WINDOW_DEFAULT_X_FRACTION: Float = 0.92f
internal const val RAFT_MINI_WINDOW_DEFAULT_Y_FRACTION: Float = 0.16f

internal val RaftMiniWindowMinWidthDp: Dp = RAFT_MINI_WINDOW_MIN_WIDTH_DP_VALUE.dp
internal val RaftMiniWindowMinHeightDp: Dp = RAFT_MINI_WINDOW_MIN_HEIGHT_DP_VALUE.dp
internal val RaftMiniWindowMaxWidthDp: Dp = RAFT_MINI_WINDOW_MAX_WIDTH_DP_VALUE.dp
internal val RaftMiniWindowMaxHeightDp: Dp = RAFT_MINI_WINDOW_MAX_HEIGHT_DP_VALUE.dp

/** Unified presentation mode for an open Installed Raft App or Artifact window. */
internal enum class RaftFloatingWindowPresentation {
    FullScreen,
    MiniWindow,
    EdgePill,
}

/** Stable identifier for a managed floating window across Apps and Artifacts. */
internal sealed interface RaftFloatingWindowId {
    data class AppTab(val tabId: Long) : RaftFloatingWindowId

    data class Artifact(val key: String) : RaftFloatingWindowId
}

/** One floating Artifact window tracked by [RaftFloatingWindowCoordinator]. */
internal data class RaftFloatingArtifactWindow(
    val artifactId: String,
    val canonicalId: String,
    val version: Int?,
    val title: String,
    val kind: ArtifactKind = ArtifactKind.Other,
    val scope: RaftArtifactViewerScope,
    val conversationId: String? = null,
    val hostHead: Int? = null,
    val langTag: String? = null,
    val keepAliveKey: String,
    val embeddedPageUrl: String,
    val desktopMode: Boolean = false,
    val storeScope: RaftShellStoreScope? = null,
    val environmentKey: String? = null,
    val lastUsedSeq: Long = 0L,
) {
    val target: ArtifactLinkTarget
        get() = ArtifactLinkTarget(artifactId, version)

    /** Canonical Artifact identity key (normalizes short IDs and `pt_<uuid>` identically). */
    val windowKey: String
        get() = if (version == null) canonicalId else "$canonicalId;version=$version"

    val windowId: RaftFloatingWindowId.Artifact
        get() = RaftFloatingWindowId.Artifact(windowKey)

    val displayTitle: String
        get() = title.trim()
}

/** Unified entry representing either an Installed Raft App tab or a Floating Artifact window. */
internal sealed interface RaftFloatingWindowEntry {
    val id: RaftFloatingWindowId
    val lastUsedSeq: Long

    data class AppTab(val tab: RaftAppBrowserTab) : RaftFloatingWindowEntry {
        override val id: RaftFloatingWindowId.AppTab get() = RaftFloatingWindowId.AppTab(tab.tabId)
        override val lastUsedSeq: Long get() = tab.lastUsedSeq
    }

    data class Artifact(val artifact: RaftFloatingArtifactWindow) : RaftFloatingWindowEntry {
        override val id: RaftFloatingWindowId.Artifact get() = artifact.windowId
        override val lastUsedSeq: Long get() = artifact.lastUsedSeq
    }
}

/** Interactive resize corner on the floating Mini-Window. */
internal enum class RaftFloatingMiniWindowResizeCorner {
    BottomLeft,
    BottomRight,
}

/**
 * User-preferred Mini-Window size (in dp) and normalized position fractions `[0f, 1f]`.
 *
 * Stored independently of transient IME / rotation clamping so popping the keyboard up clamps the
 * window above the IME without losing the user's preferred size once the keyboard dismisses.
 */
internal data class RaftFloatingMiniWindowPlacement(
    val preferredWidthDp: Float? = null,
    val preferredHeightDp: Float? = null,
    val xFraction: Float = RAFT_MINI_WINDOW_DEFAULT_X_FRACTION,
    val yFraction: Float = RAFT_MINI_WINDOW_DEFAULT_Y_FRACTION,
)

/** Live viewport dimensions and safe-area / IME insets (in px) constraining the Mini-Window. */
internal data class RaftFloatingMiniWindowViewportPx(
    val viewportWidthPx: Float,
    val viewportHeightPx: Float,
    val leftSafeAreaPx: Float = 0f,
    val rightSafeAreaPx: Float = 0f,
    val topSafeAreaPx: Float = 0f,
    val bottomSafeAreaPx: Float = 0f,
    val imeBottomPx: Float = 0f,
    val headerGapPx: Float = 0f,
    val bottomExtraPx: Float = 0f,
    val horizontalMarginPx: Float = 0f,
    val imeGapPx: Float = 0f,
    val density: Float = 1f,
)

/** Concrete physical bounding rectangle (in px) of the floating Mini-Window inside the viewport. */
internal data class RaftFloatingMiniWindowBoundsPx(
    val leftPx: Float,
    val topPx: Float,
    val widthPx: Float,
    val heightPx: Float,
) {
    val rightPx: Float get() = leftPx + widthPx
    val bottomPx: Float get() = topPx + heightPx
}

private data class RaftMiniWindowSafeFramePx(
    val minX: Float,
    val maxRight: Float,
    val minY: Float,
    val maxBottom: Float,
    val minWidthPx: Float,
    val maxWidthPx: Float,
    val minHeightPx: Float,
    val maxHeightPx: Float,
    val defaultWidthPx: Float,
    val defaultHeightPx: Float,
    val density: Float,
) {
    val availableWidthPx: Float get() = (maxRight - minX).coerceAtLeast(0f)
    val availableHeightPx: Float get() = (maxBottom - minY).coerceAtLeast(0f)
}

private fun Float.safeFiniteNonNegative(): Float = if (isFinite()) coerceAtLeast(0f) else 0f

private fun Float.safeFiniteDelta(): Float = if (isFinite()) this else 0f

/**
 * Pure, deterministic Mini-Window geometry engine:
 * - Resolves default and user-preferred dimensions within strict min/max and safe-area / IME bounds.
 * - Handles free header dragging clamped inside the safe viewport frame.
 * - Handles interactive bottom-left (anchors right & top edges) and bottom-right (anchors left & top
 *   edges) corner resizing with strict min/max and viewport boundary enforcement.
 * - Guards against rotation, tiny/narrow viewports, live IME pop-up, and non-finite inputs.
 */
internal object RaftFloatingMiniWindowGeometry {
    private fun resolveSafeFrame(viewport: RaftFloatingMiniWindowViewportPx): RaftMiniWindowSafeFramePx {
        val safeDensity = if (viewport.density.isFinite() && viewport.density > 0f) viewport.density else 1f
        val safeViewportW = viewport.viewportWidthPx.safeFiniteNonNegative()
        val safeViewportH = viewport.viewportHeightPx.safeFiniteNonNegative()
        val safeLeft = viewport.leftSafeAreaPx.safeFiniteNonNegative()
        val safeRight = viewport.rightSafeAreaPx.safeFiniteNonNegative()
        val safeTop = viewport.topSafeAreaPx.safeFiniteNonNegative()
        val safeBottom = viewport.bottomSafeAreaPx.safeFiniteNonNegative()
        val safeIme = viewport.imeBottomPx.safeFiniteNonNegative()
        val safeHeaderGap = viewport.headerGapPx.safeFiniteNonNegative()
        val safeBottomExtra = viewport.bottomExtraPx.safeFiniteNonNegative()
        val safeMargin = viewport.horizontalMarginPx.safeFiniteNonNegative()
        val safeImeGap = viewport.imeGapPx.safeFiniteNonNegative()

        val minX = (safeLeft + safeMargin).coerceAtMost(safeViewportW)
        val maxRight = (safeViewportW - safeRight - safeMargin).coerceAtLeast(minX)
        val availableWidth = (maxRight - minX).coerceAtLeast(0f)

        val minY = (safeTop + safeHeaderGap).coerceAtMost(safeViewportH)
        val normalBottom = safeBottom + safeBottomExtra
        val imeBottom = if (safeIme > 0f) safeIme + safeImeGap else 0f
        val effectiveBottom = maxOf(normalBottom, imeBottom)
        val maxBottom = (safeViewportH - effectiveBottom).coerceAtLeast(minY)
        val availableHeight = (maxBottom - minY).coerceAtLeast(0f)

        val minWidthTargetPx = RAFT_MINI_WINDOW_MIN_WIDTH_DP_VALUE * safeDensity
        val maxWidthTargetPx = RAFT_MINI_WINDOW_MAX_WIDTH_DP_VALUE * safeDensity
        val minHeightTargetPx = RAFT_MINI_WINDOW_MIN_HEIGHT_DP_VALUE * safeDensity
        val maxHeightTargetPx = RAFT_MINI_WINDOW_MAX_HEIGHT_DP_VALUE * safeDensity
        val defaultMaxWidthPx = RAFT_MINI_WINDOW_DEFAULT_MAX_WIDTH_DP_VALUE * safeDensity
        val defaultMaxHeightPx = RAFT_MINI_WINDOW_DEFAULT_MAX_HEIGHT_DP_VALUE * safeDensity

        val maxWidthPx = minOf(availableWidth, maxWidthTargetPx).coerceAtLeast(0f)
        val minWidthPx = minOf(minWidthTargetPx, maxWidthPx).coerceAtLeast(0f)

        val proportionalMaxH = minOf(availableHeight * RAFT_MINI_WINDOW_MAX_HEIGHT_FRACTION, maxHeightTargetPx)
        val maxHeightPx =
            if (availableHeight >= minHeightTargetPx) {
                maxOf(proportionalMaxH, minHeightTargetPx).coerceAtMost(availableHeight)
            } else {
                availableHeight
            }.coerceAtLeast(0f)
        val minHeightPx = minOf(minHeightTargetPx, maxHeightPx).coerceAtLeast(0f)

        val defaultWidthPx =
            minOf(availableWidth * RAFT_MINI_WINDOW_DEFAULT_WIDTH_FRACTION, defaultMaxWidthPx)
                .coerceIn(minWidthPx, maxWidthPx)
        val defaultHeightPx =
            minOf(availableHeight * RAFT_MINI_WINDOW_DEFAULT_HEIGHT_FRACTION, defaultMaxHeightPx)
                .coerceIn(minHeightPx, maxHeightPx)

        return RaftMiniWindowSafeFramePx(
            minX = minX,
            maxRight = maxRight,
            minY = minY,
            maxBottom = maxBottom,
            minWidthPx = minWidthPx,
            maxWidthPx = maxWidthPx,
            minHeightPx = minHeightPx,
            maxHeightPx = maxHeightPx,
            defaultWidthPx = defaultWidthPx,
            defaultHeightPx = defaultHeightPx,
            density = safeDensity,
        )
    }

    /** Resolves concrete clamped pixel bounds from [placement] within [viewport]. */
    fun resolveBounds(
        placement: RaftFloatingMiniWindowPlacement,
        viewport: RaftFloatingMiniWindowViewportPx,
    ): RaftFloatingMiniWindowBoundsPx {
        val frame = resolveSafeFrame(viewport)
        val targetWidth =
            placement.preferredWidthDp
                ?.takeIf { it.isFinite() && it > 0f }
                ?.let { it * frame.density }
                ?: frame.defaultWidthPx
        val targetHeight =
            placement.preferredHeightDp
                ?.takeIf { it.isFinite() && it > 0f }
                ?.let { it * frame.density }
                ?: frame.defaultHeightPx

        val clampedW = targetWidth.coerceIn(frame.minWidthPx, frame.maxWidthPx)
        val clampedH = targetHeight.coerceIn(frame.minHeightPx, frame.maxHeightPx)

        val safeXFraction =
            if (placement.xFraction.isFinite()) {
                placement.xFraction.coerceIn(0f, 1f)
            } else {
                RAFT_MINI_WINDOW_DEFAULT_X_FRACTION
            }
        val safeYFraction =
            if (placement.yFraction.isFinite()) {
                placement.yFraction.coerceIn(0f, 1f)
            } else {
                RAFT_MINI_WINDOW_DEFAULT_Y_FRACTION
            }

        val xSpan = (frame.availableWidthPx - clampedW).coerceAtLeast(0f)
        val ySpan = (frame.availableHeightPx - clampedH).coerceAtLeast(0f)
        val leftPx = frame.minX + xSpan * safeXFraction
        val topPx = frame.minY + ySpan * safeYFraction

        return RaftFloatingMiniWindowBoundsPx(
            leftPx = leftPx,
            topPx = topPx,
            widthPx = clampedW,
            heightPx = clampedH,
        )
    }

    /** Clamps arbitrary [bounds] strictly inside [viewport]'s safe bounds and min/max size limits. */
    fun clampBounds(
        bounds: RaftFloatingMiniWindowBoundsPx,
        viewport: RaftFloatingMiniWindowViewportPx,
    ): RaftFloatingMiniWindowBoundsPx {
        val frame = resolveSafeFrame(viewport)
        val rawW = if (bounds.widthPx.isFinite()) bounds.widthPx else frame.defaultWidthPx
        val rawH = if (bounds.heightPx.isFinite()) bounds.heightPx else frame.defaultHeightPx
        val clampedW = rawW.coerceIn(frame.minWidthPx, frame.maxWidthPx)
        val clampedH = rawH.coerceIn(frame.minHeightPx, frame.maxHeightPx)
        val maxLeft = (frame.maxRight - clampedW).coerceAtLeast(frame.minX)
        val maxTop = (frame.maxBottom - clampedH).coerceAtLeast(frame.minY)
        val rawLeft = if (bounds.leftPx.isFinite()) bounds.leftPx else frame.minX
        val rawTop = if (bounds.topPx.isFinite()) bounds.topPx else frame.minY
        return RaftFloatingMiniWindowBoundsPx(
            leftPx = rawLeft.coerceIn(frame.minX, maxLeft),
            topPx = rawTop.coerceIn(frame.minY, maxTop),
            widthPx = clampedW,
            heightPx = clampedH,
        )
    }

    /** Moves [bounds] by `[deltaXPx, deltaYPx]` while keeping size constant and clamped to [viewport]. */
    fun dragBy(
        bounds: RaftFloatingMiniWindowBoundsPx,
        deltaXPx: Float,
        deltaYPx: Float,
        viewport: RaftFloatingMiniWindowViewportPx,
    ): RaftFloatingMiniWindowBoundsPx {
        val clamped = clampBounds(bounds, viewport)
        val frame = resolveSafeFrame(viewport)
        val maxLeft = (frame.maxRight - clamped.widthPx).coerceAtLeast(frame.minX)
        val maxTop = (frame.maxBottom - clamped.heightPx).coerceAtLeast(frame.minY)
        return clamped.copy(
            leftPx = (clamped.leftPx + deltaXPx.safeFiniteDelta()).coerceIn(frame.minX, maxLeft),
            topPx = (clamped.topPx + deltaYPx.safeFiniteDelta()).coerceIn(frame.minY, maxTop),
        )
    }

    /**
     * Resizes [bounds] from [corner] by `[deltaXPx, deltaYPx]`:
     * - [RaftFloatingMiniWindowResizeCorner.BottomRight]: anchors the left and top edges while
     *   adjusting width and height.
     * - [RaftFloatingMiniWindowResizeCorner.BottomLeft]: anchors the right and top edges while
     *   adjusting width and shifting `leftPx` symmetrically.
     */
    fun resizeBy(
        bounds: RaftFloatingMiniWindowBoundsPx,
        corner: RaftFloatingMiniWindowResizeCorner,
        deltaXPx: Float,
        deltaYPx: Float,
        viewport: RaftFloatingMiniWindowViewportPx,
    ): RaftFloatingMiniWindowBoundsPx {
        val current = clampBounds(bounds, viewport)
        val frame = resolveSafeFrame(viewport)
        val dx = deltaXPx.safeFiniteDelta()
        val dy = deltaYPx.safeFiniteDelta()

        val maxHeightFromTop = minOf(frame.maxHeightPx, (frame.maxBottom - current.topPx).coerceAtLeast(0f))
            .coerceAtLeast(frame.minHeightPx)
        val nextHeight = (current.heightPx + dy).coerceIn(frame.minHeightPx, maxHeightFromTop)
        val nextTop = current.topPx.coerceIn(frame.minY, (frame.maxBottom - nextHeight).coerceAtLeast(frame.minY))

        return when (corner) {
            RaftFloatingMiniWindowResizeCorner.BottomRight -> {
                val maxWidthFromLeft = minOf(frame.maxWidthPx, (frame.maxRight - current.leftPx).coerceAtLeast(0f))
                    .coerceAtLeast(frame.minWidthPx)
                val nextWidth = (current.widthPx + dx).coerceIn(frame.minWidthPx, maxWidthFromLeft)
                val nextLeft = current.leftPx.coerceIn(frame.minX, (frame.maxRight - nextWidth).coerceAtLeast(frame.minX))
                RaftFloatingMiniWindowBoundsPx(
                    leftPx = nextLeft,
                    topPx = nextTop,
                    widthPx = nextWidth,
                    heightPx = nextHeight,
                )
            }
            RaftFloatingMiniWindowResizeCorner.BottomLeft -> {
                val anchoredRight = current.rightPx.coerceIn(frame.minX, frame.maxRight)
                val maxWidthFromRight = minOf(frame.maxWidthPx, (anchoredRight - frame.minX).coerceAtLeast(0f))
                    .coerceAtLeast(frame.minWidthPx)
                val nextWidth = (current.widthPx - dx).coerceIn(frame.minWidthPx, maxWidthFromRight)
                val nextLeft = (anchoredRight - nextWidth).coerceIn(frame.minX, (frame.maxRight - nextWidth).coerceAtLeast(frame.minX))
                RaftFloatingMiniWindowBoundsPx(
                    leftPx = nextLeft,
                    topPx = nextTop,
                    widthPx = nextWidth,
                    heightPx = nextHeight,
                )
            }
        }
    }

    /**
     * Converts concrete [bounds] back into a persisted [RaftFloatingMiniWindowPlacement].
     *
     * Pass [preservePreferredSizeFrom] after a pure move-drag so moving the window while the IME is
     * temporarily open never overwrites the user's non-IME preferred width/height.
     */
    fun boundsToPlacement(
        bounds: RaftFloatingMiniWindowBoundsPx,
        viewport: RaftFloatingMiniWindowViewportPx,
        preservePreferredSizeFrom: RaftFloatingMiniWindowPlacement? = null,
    ): RaftFloatingMiniWindowPlacement {
        val frame = resolveSafeFrame(viewport)
        val clamped = clampBounds(bounds, viewport)
        val xSpan = frame.availableWidthPx - clamped.widthPx
        val ySpan = frame.availableHeightPx - clamped.heightPx
        val xFraction =
            if (xSpan > 1f) {
                ((clamped.leftPx - frame.minX) / xSpan).coerceIn(0f, 1f)
            } else {
                preservePreferredSizeFrom?.xFraction?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
                    ?: RAFT_MINI_WINDOW_DEFAULT_X_FRACTION
            }
        val yFraction =
            if (ySpan > 1f) {
                ((clamped.topPx - frame.minY) / ySpan).coerceIn(0f, 1f)
            } else {
                preservePreferredSizeFrom?.yFraction?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
                    ?: RAFT_MINI_WINDOW_DEFAULT_Y_FRACTION
            }
        val preferredWidthDp =
            preservePreferredSizeFrom?.preferredWidthDp
                ?: (clamped.widthPx / frame.density).takeIf { it.isFinite() && it > 0f }
        val preferredHeightDp =
            preservePreferredSizeFrom?.preferredHeightDp
                ?: (clamped.heightPx / frame.density).takeIf { it.isFinite() && it > 0f }
        return RaftFloatingMiniWindowPlacement(
            preferredWidthDp = preferredWidthDp,
            preferredHeightDp = preferredHeightDp,
            xFraction = xFraction,
            yFraction = yFraction,
        )
    }
}

/** Immutable floating-window state persisted in [RaftFloatingWindowCoordinator]. */
internal data class RaftFloatingWindowState(
    /** Open floating Artifact windows ordered most-recently-used first (capped at [RAFT_FLOATING_ARTIFACT_MAX_WINDOWS]). */
    val artifacts: List<RaftFloatingArtifactWindow> = emptyList(),
    /** Currently active interactive Mini-Window (at most 1 across Apps + Artifacts), or `null`. */
    val activeMiniWindow: RaftFloatingWindowId? = null,
    /** Currently open full-screen Artifact window key (when expanded from MiniWindow/EdgePill), or `null`. */
    val fullScreenArtifactKey: String? = null,
    /** Persisted user-preferred Mini-Window size and normalized position. */
    val miniWindowPlacement: RaftFloatingMiniWindowPlacement = RaftFloatingMiniWindowPlacement(),
    /** Unified MRU sequence overrides so App tabs and Artifacts interleave accurately in recency order. */
    val recencyById: Map<RaftFloatingWindowId, Long> = emptyMap(),
)

/**
 * Unified snapshot combining [RaftAppBrowserTabsState] and [RaftFloatingWindowState] to drive
 * both the Resizable Mini-Window overlay and the Bilateral Magnetic Edge Pill + Switcher Sheet.
 */
internal data class RaftFloatingWindowSnapshot(
    val appTabs: List<RaftAppBrowserTab>,
    val artifacts: List<RaftFloatingArtifactWindow>,
    val activeFullScreenAppTabId: Long?,
    val activeFullScreenArtifactKey: String?,
    val activeMiniWindowEntry: RaftFloatingWindowEntry?,
    /** Minimized items ordered MRU-first across both Apps and Artifacts. */
    val minimizedEntries: List<RaftFloatingWindowEntry>,
    val miniWindowPlacement: RaftFloatingMiniWindowPlacement,
    val pillEdge: RaftFloatingPillEdge,
    val pillYFraction: Float,
) {
    val hasFullScreenSurface: Boolean
        get() = activeFullScreenAppTabId != null || activeFullScreenArtifactKey != null

    /** Interactive Mini-Window renders over Home/Chat/Threads whenever active and no full-screen surface is up. */
    val showMiniWindow: Boolean
        get() = !hasFullScreenSurface && activeMiniWindowEntry != null

    /** Bilateral Edge Pill renders whenever no full-screen surface and no Mini-Window is up and ≥1 item is minimized. */
    val showEdgePill: Boolean
        get() = !hasFullScreenSurface && activeMiniWindowEntry == null && minimizedEntries.isNotEmpty()

    val totalOpenCount: Int
        get() = appTabs.size + artifacts.size
}

/** Pure projection from [tabsState] + [windowState] into [RaftFloatingWindowSnapshot]. */
internal fun resolveRaftFloatingWindowSnapshot(
    tabsState: RaftAppBrowserTabsState,
    windowState: RaftFloatingWindowState,
): RaftFloatingWindowSnapshot {
    val activeFullScreenTabId =
        tabsState.activeTabId?.takeIf { id -> tabsState.tabs.any { it.tabId == id } }
    val activeFullScreenArtifactKey =
        windowState.fullScreenArtifactKey?.takeIf { key -> windowState.artifacts.any { it.windowKey == key } }

    val activeMiniEntry: RaftFloatingWindowEntry? =
        if (activeFullScreenTabId != null || activeFullScreenArtifactKey != null) {
            null
        } else {
            when (val activeId = windowState.activeMiniWindow) {
                is RaftFloatingWindowId.AppTab ->
                    tabsState.tabs.firstOrNull { it.tabId == activeId.tabId }?.let(RaftFloatingWindowEntry::AppTab)
                is RaftFloatingWindowId.Artifact ->
                    windowState.artifacts.firstOrNull { it.windowKey == activeId.key }?.let(RaftFloatingWindowEntry::Artifact)
                null -> null
            }
        }

    fun recencyOf(entry: RaftFloatingWindowEntry): Long =
        windowState.recencyById[entry.id] ?: entry.lastUsedSeq

    val minimized =
        buildList {
            tabsState.tabs.forEach { tab ->
                if (tab.tabId != activeFullScreenTabId) {
                    add(RaftFloatingWindowEntry.AppTab(tab))
                }
            }
            windowState.artifacts.forEach { artifact ->
                if (artifact.windowKey != activeFullScreenArtifactKey) {
                    add(RaftFloatingWindowEntry.Artifact(artifact))
                }
            }
        }.sortedByDescending(::recencyOf)

    return RaftFloatingWindowSnapshot(
        appTabs = tabsState.tabs,
        artifacts = windowState.artifacts,
        activeFullScreenAppTabId = activeFullScreenTabId,
        activeFullScreenArtifactKey = activeFullScreenArtifactKey,
        activeMiniWindowEntry = activeMiniEntry,
        minimizedEntries = minimized,
        miniWindowPlacement = windowState.miniWindowPlacement,
        pillEdge = tabsState.pillEdge,
        pillYFraction = tabsState.pillYFraction,
    )
}

/**
 * Process-wide unified floating window coordinator bridging [RaftMobileWebPanelCoordinator] (App
 * tabs) and floating Artifact windows with a single active Mini-Window slot and unified bilateral
 * magnetic Edge Pill + Switcher state.
 *
 * Uses [MutableStateFlow] (never top-level Compose snapshot state) so background threads such as
 * sign-out storage clearing or OS memory trim hooks can safely update or clear it.
 */
internal class RaftFloatingWindowCoordinator(
    val webPanelCoordinator: RaftMobileWebPanelCoordinator = RaftMobileWebPanelCoordinator.process,
    private val maxArtifacts: Int = RAFT_FLOATING_ARTIFACT_MAX_WINDOWS,
    private val evictArtifactKeepAlive: (scope: RaftArtifactViewerScope, keepAliveKey: String) -> Unit = { scope, key ->
        RaftArtifactViewerMemory.cache.forgetKeptDocument(scope, key)
        raftWebViewKeepAliveDrop(key)
    },
) {
    init {
        require(maxArtifacts >= 1) { "maxArtifacts must be >= 1" }
    }

    private val lock = SynchronizedObject()
    private var recencySeq = 0L
    private val _state = MutableStateFlow(RaftFloatingWindowState())
    val state: StateFlow<RaftFloatingWindowState> = _state.asStateFlow()

    /** Computes the current unified snapshot combining App tabs and floating Artifacts. */
    fun currentSnapshot(): RaftFloatingWindowSnapshot =
        resolveRaftFloatingWindowSnapshot(webPanelCoordinator.state.value, _state.value)

    /** Resolves the current presentation mode of [id], or `null` if it is closed / unknown. */
    fun presentationOf(id: RaftFloatingWindowId): RaftFloatingWindowPresentation? {
        val snapshot = currentSnapshot()
        return when (id) {
            is RaftFloatingWindowId.AppTab -> {
                if (snapshot.appTabs.none { it.tabId == id.tabId }) return null
                when {
                    snapshot.activeFullScreenAppTabId == id.tabId -> RaftFloatingWindowPresentation.FullScreen
                    snapshot.activeMiniWindowEntry?.id == id -> RaftFloatingWindowPresentation.MiniWindow
                    else -> RaftFloatingWindowPresentation.EdgePill
                }
            }
            is RaftFloatingWindowId.Artifact -> {
                if (snapshot.artifacts.none { it.windowKey == id.key }) return null
                when {
                    snapshot.activeFullScreenArtifactKey == id.key -> RaftFloatingWindowPresentation.FullScreen
                    snapshot.activeMiniWindowEntry?.id == id -> RaftFloatingWindowPresentation.MiniWindow
                    else -> RaftFloatingWindowPresentation.EdgePill
                }
            }
        }
    }

    /**
     * Floats an open installed Raft App tab [tabId] into interactive [RaftFloatingWindowPresentation.MiniWindow]
     * mode, preserving its live LRU warm WebView and collapsing any previously active Mini-Window into
     * the Edge Pill.
     */
    fun floatAppTabInMiniWindow(tabId: Long): Boolean {
        val existing = webPanelCoordinator.tabById(tabId) ?: return false
        if (!existing.isLive) {
            webPanelCoordinator.activateTab(tabId)
            webPanelCoordinator.suspendTab(tabId)
        } else if (webPanelCoordinator.state.value.activeTabId == tabId) {
            webPanelCoordinator.suspendTab(tabId)
        } else {
            webPanelCoordinator.suspendActive()
        }
        val windowId = RaftFloatingWindowId.AppTab(tabId)
        synchronized(lock) {
            recencySeq += 1L
            val current = _state.value
            _state.value =
                current.copy(
                    activeMiniWindow = windowId,
                    fullScreenArtifactKey = null,
                    recencyById = current.recencyById + (windowId to recencySeq),
                )
        }
        return true
    }

    /**
     * Registers or updates a floating Artifact window and opens it in interactive
     * [RaftFloatingWindowPresentation.MiniWindow] mode, collapsing any previously active Mini-Window
     * (App or Artifact) into the Edge Pill cleanly.
     */
    fun floatArtifactInMiniWindow(entry: RaftFloatingArtifactWindow): RaftFloatingArtifactWindow {
        webPanelCoordinator.suspendActive()
        var evicted: RaftFloatingArtifactWindow? = null
        val stored =
            synchronized(lock) {
                recencySeq += 1L
                val nowSeq = recencySeq
                val current = _state.value
                val working = current.artifacts.filterNot { it.windowKey == entry.windowKey }.toMutableList()
                if (working.size >= maxArtifacts) {
                    val victim =
                        working
                            .filter { it.windowKey != current.fullScreenArtifactKey }
                            .minByOrNull { it.lastUsedSeq }
                            ?: working.minByOrNull { it.lastUsedSeq }
                    if (victim != null) {
                        working.removeAll { it.windowKey == victim.windowKey }
                        evicted = victim
                    }
                }
                val promoted = entry.copy(lastUsedSeq = nowSeq)
                val nextArtifacts = listOf(promoted) + working
                val nextRecency =
                    (current.recencyById - evicted?.windowId.let { listOfNotNull(it) }.toSet()) +
                        (promoted.windowId to nowSeq)
                _state.value =
                    current.copy(
                        artifacts = nextArtifacts,
                        activeMiniWindow = promoted.windowId,
                        fullScreenArtifactKey =
                            current.fullScreenArtifactKey?.takeUnless { it == promoted.windowKey },
                        recencyById = nextRecency,
                    )
                promoted
            }
        evicted?.let { victim -> evictArtifactKeepAlive(victim.scope, victim.keepAliveKey) }
        return stored
    }

    /** Opens an existing minimized App tab or Artifact window into interactive Mini-Window mode. */
    fun openInMiniWindow(id: RaftFloatingWindowId): Boolean =
        when (id) {
            is RaftFloatingWindowId.AppTab -> floatAppTabInMiniWindow(id.tabId)
            is RaftFloatingWindowId.Artifact -> {
                var found = false
                synchronized(lock) {
                    val current = _state.value
                    val target = current.artifacts.firstOrNull { it.windowKey == id.key } ?: return@synchronized
                    recencySeq += 1L
                    val nowSeq = recencySeq
                    val promoted = target.copy(lastUsedSeq = nowSeq)
                    val remaining = current.artifacts.filterNot { it.windowKey == id.key }
                    _state.value =
                        current.copy(
                            artifacts = listOf(promoted) + remaining,
                            activeMiniWindow = promoted.windowId,
                            fullScreenArtifactKey =
                                current.fullScreenArtifactKey?.takeUnless { it == promoted.windowKey },
                            recencyById = current.recencyById + (promoted.windowId to nowSeq),
                        )
                    found = true
                }
                if (found) {
                    webPanelCoordinator.suspendActive()
                }
                found
            }
        }

    /** Collapses the currently active Mini-Window into the bilateral Edge Pill without destroying its WebView. */
    fun collapseMiniWindowToEdgePill(): Boolean =
        synchronized(lock) {
            val current = _state.value
            if (current.activeMiniWindow == null) return@synchronized false
            _state.value = current.copy(activeMiniWindow = null)
            true
        }

    /** Promotes an App tab [tabId] from MiniWindow/EdgePill into FullScreen, hiding the Mini-Window cleanly. */
    fun prepareExpandAppTabToFullScreen(tabId: Long): RaftAppBrowserTab? {
        val activated = webPanelCoordinator.activateTab(tabId) ?: return null
        val windowId = RaftFloatingWindowId.AppTab(tabId)
        synchronized(lock) {
            recencySeq += 1L
            val current = _state.value
            _state.value =
                current.copy(
                    activeMiniWindow = null,
                    fullScreenArtifactKey = null,
                    recencyById = current.recencyById + (windowId to recencySeq),
                )
        }
        return activated
    }

    /** Promotes a floating Artifact [windowKey] from MiniWindow/EdgePill into FullScreen. */
    fun prepareExpandArtifactToFullScreen(windowKey: String): RaftFloatingArtifactWindow? {
        webPanelCoordinator.suspendActive()
        return synchronized(lock) {
            val current = _state.value
            val target = current.artifacts.firstOrNull { it.windowKey == windowKey } ?: return@synchronized null
            recencySeq += 1L
            val nowSeq = recencySeq
            val promoted = target.copy(lastUsedSeq = nowSeq)
            val remaining = current.artifacts.filterNot { it.windowKey == windowKey }
            _state.value =
                current.copy(
                    artifacts = listOf(promoted) + remaining,
                    activeMiniWindow = null,
                    fullScreenArtifactKey = promoted.windowKey,
                    recencyById = current.recencyById + (promoted.windowId to nowSeq),
                )
            promoted
        }
    }

    /** Synchronizes state when an App tab becomes active in Full-Screen (`InAppBrowserPage`). */
    fun onAppTabActivatedFullScreen(tabId: Long) {
        val windowId = RaftFloatingWindowId.AppTab(tabId)
        synchronized(lock) {
            recencySeq += 1L
            val current = _state.value
            _state.value =
                current.copy(
                    activeMiniWindow = null,
                    fullScreenArtifactKey = null,
                    recencyById = current.recencyById + (windowId to recencySeq),
                )
        }
    }

    /** Synchronizes state when a full-screen Artifact viewer opens for [windowKey]. */
    fun onArtifactEnteredFullScreen(windowKey: String) {
        synchronized(lock) {
            val current = _state.value
            val tracked = current.artifacts.any { it.windowKey == windowKey }
            val nextMini =
                if (current.activeMiniWindow == RaftFloatingWindowId.Artifact(windowKey)) {
                    null
                } else {
                    current.activeMiniWindow
                }
            _state.value =
                current.copy(
                    activeMiniWindow = nextMini,
                    fullScreenArtifactKey = if (tracked) windowKey else current.fullScreenArtifactKey,
                )
        }
    }

    /**
     * Synchronizes state when a full-screen Artifact viewer closes:
     * - When [keepFloating] is true (explicit Float window action), keeps the Artifact floating.
     * - Otherwise removes it from the floating list while preserving its normal multi-slot cache.
     */
    fun onArtifactFullScreenClosed(
        windowKey: String,
        keepFloating: Boolean = false,
    ) {
        synchronized(lock) {
            val current = _state.value
            val nextFullScreen = current.fullScreenArtifactKey?.takeUnless { it == windowKey }
            if (keepFloating) {
                if (nextFullScreen != current.fullScreenArtifactKey) {
                    _state.value = current.copy(fullScreenArtifactKey = nextFullScreen)
                }
                return@synchronized
            }
            val nextArtifacts = current.artifacts.filterNot { it.windowKey == windowKey }
            val nextMini = current.activeMiniWindow?.takeUnless { it == RaftFloatingWindowId.Artifact(windowKey) }
            val nextRecency = current.recencyById - RaftFloatingWindowId.Artifact(windowKey)
            _state.value =
                current.copy(
                    artifacts = nextArtifacts,
                    activeMiniWindow = nextMini,
                    fullScreenArtifactKey = nextFullScreen,
                    recencyById = nextRecency,
                )
        }
    }

    /** Updates metadata (e.g. title or version) on an already-tracked floating Artifact window. */
    fun updateArtifactMetadata(
        windowKey: String,
        title: String? = null,
        embeddedPageUrl: String? = null,
    ) {
        val cleanTitle = title?.trim()?.takeIf { it.isNotEmpty() }
        val cleanUrl = embeddedPageUrl?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanTitle == null && cleanUrl == null) return
        synchronized(lock) {
            val current = _state.value
            val index = current.artifacts.indexOfFirst { it.windowKey == windowKey }
            if (index < 0) return@synchronized
            val existing = current.artifacts[index]
            val nextTitle = cleanTitle ?: existing.title
            val nextUrl = cleanUrl ?: existing.embeddedPageUrl
            if (existing.title == nextTitle && existing.embeddedPageUrl == nextUrl) return@synchronized
            val updated = current.artifacts.toMutableList()
            updated[index] = existing.copy(title = nextTitle, embeddedPageUrl = nextUrl)
            _state.value = current.copy(artifacts = updated)
        }
    }

    /** Returns the floating Artifact window for [windowKey], if currently tracked. */
    fun artifactByKey(windowKey: String): RaftFloatingArtifactWindow? =
        synchronized(lock) {
            _state.value.artifacts.firstOrNull { it.windowKey == windowKey }
        }

    /** Closes App tab [tabId], evicting its keep-alive WebView and releasing its entry token. */
    fun closeAppTab(tabId: Long): Boolean {
        val id = RaftFloatingWindowId.AppTab(tabId)
        synchronized(lock) {
            val current = _state.value
            val nextMini = current.activeMiniWindow?.takeUnless { it == id }
            if (nextMini != current.activeMiniWindow || id in current.recencyById) {
                _state.value =
                    current.copy(
                        activeMiniWindow = nextMini,
                        recencyById = current.recencyById - id,
                    )
            }
        }
        return webPanelCoordinator.closeTab(tabId)
    }

    /** Closes floating Artifact [windowKey] and surgically evicts its keep-alive WebView slot. */
    fun closeArtifact(windowKey: String): Boolean {
        val id = RaftFloatingWindowId.Artifact(windowKey)
        val removed =
            synchronized(lock) {
                val current = _state.value
                val target = current.artifacts.firstOrNull { it.windowKey == windowKey } ?: return@synchronized null
                _state.value =
                    current.copy(
                        artifacts = current.artifacts.filterNot { it.windowKey == windowKey },
                        activeMiniWindow = current.activeMiniWindow?.takeUnless { it == id },
                        fullScreenArtifactKey = current.fullScreenArtifactKey?.takeUnless { it == windowKey },
                        recencyById = current.recencyById - id,
                    )
                target
            } ?: return false
        evictArtifactKeepAlive(removed.scope, removed.keepAliveKey)
        return true
    }

    /** Closes whichever App tab or floating Artifact window matches [id]. */
    fun closeWindow(id: RaftFloatingWindowId): Boolean =
        when (id) {
            is RaftFloatingWindowId.AppTab -> closeAppTab(id.tabId)
            is RaftFloatingWindowId.Artifact -> closeArtifact(id.key)
        }

    /** Closes ALL floating Artifacts AND ALL App tabs, evicting every warm keep-alive WebView. */
    fun closeAll() {
        val removedArtifacts =
            synchronized(lock) {
                val current = _state.value
                val existing = current.artifacts
                if (existing.isNotEmpty() || current.activeMiniWindow != null || current.fullScreenArtifactKey != null || current.recencyById.isNotEmpty()) {
                    _state.value =
                        current.copy(
                            artifacts = emptyList(),
                            activeMiniWindow = null,
                            fullScreenArtifactKey = null,
                            recencyById = emptyMap(),
                        )
                }
                existing
            }
        removedArtifacts.forEach { artifact -> evictArtifactKeepAlive(artifact.scope, artifact.keepAliveKey) }
        webPanelCoordinator.closeAll()
    }

    /** Alias of [closeAll] used by sign-out, account switch, and storage-clear hooks. */
    fun clearAll() = closeAll()

    /** Persists the user's preferred Mini-Window size & normalized viewport position. */
    fun updateMiniWindowPlacement(placement: RaftFloatingMiniWindowPlacement) {
        val sanitized =
            RaftFloatingMiniWindowPlacement(
                preferredWidthDp = placement.preferredWidthDp?.takeIf { it.isFinite() && it > 0f },
                preferredHeightDp = placement.preferredHeightDp?.takeIf { it.isFinite() && it > 0f },
                xFraction =
                    if (placement.xFraction.isFinite()) {
                        placement.xFraction.coerceIn(0f, 1f)
                    } else {
                        RAFT_MINI_WINDOW_DEFAULT_X_FRACTION
                    },
                yFraction =
                    if (placement.yFraction.isFinite()) {
                        placement.yFraction.coerceIn(0f, 1f)
                    } else {
                        RAFT_MINI_WINDOW_DEFAULT_Y_FRACTION
                    },
            )
        synchronized(lock) {
            val current = _state.value
            if (current.miniWindowPlacement == sanitized) return@synchronized
            _state.value = current.copy(miniWindowPlacement = sanitized)
        }
    }

    /** Persists the Edge Pill vertical fraction via [webPanelCoordinator]. */
    fun setPillYFraction(yFraction: Float) = webPanelCoordinator.setPillYFraction(yFraction)

    /** Persists the Edge Pill horizontal edge via [webPanelCoordinator]. */
    fun setPillEdge(edge: RaftFloatingPillEdge) = webPanelCoordinator.setPillEdge(edge)

    /** Atomically persists both Edge Pill dock edge and vertical fraction via [webPanelCoordinator]. */
    fun setPillDockPosition(edge: RaftFloatingPillEdge, yFraction: Float) =
        webPanelCoordinator.setPillDockPosition(edge, yFraction)

    /**
     * Hibernates background App tabs on OS memory pressure while protecting whichever App tab is
     * currently visible in Full-Screen OR interactive Mini-Window mode.
     */
    fun hibernateBackgroundWindows(): List<RaftAppBrowserTab> {
        val protectedTabId =
            synchronized(lock) {
                (_state.value.activeMiniWindow as? RaftFloatingWindowId.AppTab)?.tabId
            }
        return webPanelCoordinator.hibernateBackgroundTabs(protectedTabId = protectedTabId)
    }

    /**
     * Clears every floating Artifact window and App tab on sign-out, account switch, server switch,
     * or environment switch so no floating surface or warm WebView ever crosses security boundaries.
     */
    fun onSessionContextChanged(
        accountId: String?,
        serverId: String?,
        environmentKey: String?,
    ) {
        webPanelCoordinator.onSessionContextChanged(
            accountId = accountId,
            serverId = serverId,
            environmentKey = environmentKey,
        )
        val cleanAccount = accountId?.trim()?.takeIf { it.isNotEmpty() }
        val cleanServer = serverId?.trim()?.takeIf { it.isNotEmpty() }
        val cleanEnv = environmentKey?.trim()?.takeIf { it.isNotEmpty() }
        val envWebOrigin = cleanEnv?.substringBefore('|')?.let(::raftWebOriginOf)
        val envApiBase = cleanEnv?.substringAfter('|', "")?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
        val remainingTabIds = webPanelCoordinator.state.value.tabs.mapTo(HashSet()) { it.tabId }

        val removedArtifacts =
            synchronized(lock) {
                val current = _state.value
                val shouldClearArtifacts =
                    current.artifacts.any { artifact ->
                        val signedOut = cleanAccount == null
                        val accountMismatch = cleanAccount != null && artifact.scope.accountId != cleanAccount
                        val serverMismatch = cleanServer != null && artifact.scope.serverId != cleanServer
                        val storedEnvMismatch =
                            cleanEnv != null &&
                                artifact.environmentKey != null &&
                                artifact.environmentKey != cleanEnv
                        val originMismatch = envWebOrigin != null && artifact.scope.webOrigin != envWebOrigin
                        val apiMismatch =
                            envApiBase != null &&
                                artifact.scope.environmentId.trim().trimEnd('/') != envApiBase
                        signedOut || accountMismatch || serverMismatch || storedEnvMismatch || originMismatch || apiMismatch
                    }
                val nextArtifacts = if (shouldClearArtifacts) emptyList() else current.artifacts
                val nextFullScreen =
                    if (shouldClearArtifacts) null else current.fullScreenArtifactKey
                val nextMini =
                    when (val active = current.activeMiniWindow) {
                        is RaftFloatingWindowId.AppTab -> active.takeIf { it.tabId in remainingTabIds }
                        is RaftFloatingWindowId.Artifact ->
                            if (shouldClearArtifacts) null else active.takeIf { nextArtifacts.any { a -> a.windowKey == active.key } }
                        null -> null
                    }
                val nextRecency =
                    current.recencyById.filterKeys { key ->
                        when (key) {
                            is RaftFloatingWindowId.AppTab -> key.tabId in remainingTabIds
                            is RaftFloatingWindowId.Artifact -> nextArtifacts.any { it.windowKey == key.key }
                        }
                    }
                if (
                    nextArtifacts != current.artifacts ||
                    nextFullScreen != current.fullScreenArtifactKey ||
                    nextMini != current.activeMiniWindow ||
                    nextRecency != current.recencyById
                ) {
                    _state.value =
                        current.copy(
                            artifacts = nextArtifacts,
                            activeMiniWindow = nextMini,
                            fullScreenArtifactKey = nextFullScreen,
                            recencyById = nextRecency,
                        )
                }
                if (shouldClearArtifacts) current.artifacts else emptyList()
            }
        removedArtifacts.forEach { artifact -> evictArtifactKeepAlive(artifact.scope, artifact.keepAliveKey) }
    }

    companion object {
        val process: RaftFloatingWindowCoordinator = RaftFloatingWindowCoordinator()
    }
}
