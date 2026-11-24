package build.raft.app

import build.raft.designsystem.theme.RaftScale

/**
 * Host-owned window context. It deliberately contains layout facts only: route and child-pager
 * navigation continue to be owned by the AppShell.
 */
@RaftCoreInternalApi data class RaftAdaptivePaneState(
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    val density: Float = 1f,
    val posture: RaftAdaptivePanePosture = RaftAdaptivePanePosture.Unknown,
    val layout: RaftAdaptivePaneLayout = RaftAdaptivePaneLayout.Compact,
    val sideColumn: RaftAdaptiveSideColumn = RaftAdaptiveSideColumn.None
)

/**
 * The side column the host lays out for the window: a vertical strip on one edge carrying
 * navigation, page actions and the tab bar.
 *
 * iPhone Duo (iOS 27, HIG "designing for iPhone Duo"): with the device folded (outer display) or
 * unfolded and held landscape, the system moves the tool bar, the tab bar and the navigation
 * buttons into one vertical strip on the right edge; only the inner display held portrait goes
 * back to a horizontal top bar. The host reports which shape the window is in, and the design
 * system's side column component places the controls accordingly.
 */
@RaftCoreInternalApi enum class RaftAdaptiveSideColumn {
    /** No side column: a horizontal top bar is used. */
    None,

    /** A vertical strip on the left of the window, whatever the language's reading direction. */
    Left,

    /** A vertical strip on the right of the window, whatever the language's reading direction. */
    Right
}

/**
 * A rectangle the host must keep interactive content out of, in window points: the front-facing
 * camera's occlusion region and the fold's division region, as reported by UIKit
 * (`UIView.reservedRegions(ofKind:)`). Empty on systems without those regions.
 */
@RaftCoreInternalApi data class RaftAdaptiveReservedRegion(
    val leftPt: Float,
    val topPt: Float,
    val widthPt: Float,
    val heightPt: Float,
    /**
     * What the region is: the camera's "occlusion", the fold's "division", or "unknown" when the
     * host does not say. The two are not interchangeable - the camera is a corner the content
     * must keep clear, the fold is a seam between the device's two halves - so the kind travels
     * with the rectangle rather than being guessed from the geometry.
     */
    val kind: RaftAdaptiveReservedRegionKind = RaftAdaptiveReservedRegionKind.Unknown
) {
    fun isValid(): Boolean =
        widthPt > 0f && heightPt > 0f && leftPt.isFinite() && topPt.isFinite() &&
            widthPt.isFinite() && heightPt.isFinite()
}

@RaftCoreInternalApi enum class RaftAdaptivePanePosture {
    Unknown,
    Flat,
    Folded,
    HalfFolded
}

@RaftCoreInternalApi enum class RaftAdaptivePaneLayout {
    Compact,
    DualPane
}

@RaftCoreInternalApi data class RaftAdaptivePaneEvent(
    val widthPx: Int,
    val heightPx: Int,
    val density: Float,
    val posture: RaftAdaptivePanePosture,
    /**
     * Whether the window is in the side-column shape (iPhone Duo: outer display, or the inner
     * display held landscape). False for every other device and window size.
     */
    val sideColumn: Boolean = false,
    /**
     * Which side of the window the vertical strip sits on, as a physical side. The host reports
     * the physical side, not the logical one: in a right-to-left language the system's trailing
     * edge is the left of the screen, and the strip is laid out by physical side.
     */
    val stripEdge: RaftAdaptiveSideColumn = RaftAdaptiveSideColumn.None,
    /** Width of the vertical strip, in points. */
    val stripWidthPt: Float = 0f,
    /** Height the native tab bar takes at the bottom of the strip, in points (0 when there is none). */
    val nativeTabBarHeightPt: Float = 0f,
    /** Camera and fold regions to keep content out of, in points. */
    val reservedRegions: List<RaftAdaptiveReservedRegion> = emptyList(),
    /**
     * Which of the iPhone Duo's displays the window is on: "duoOuter" (folded), "duoInner"
     * (unfolded) or "other" (any other device). The Duo's inner display can be held portrait,
     * where the side column is gone but the top bar is still not wanted, so shared needs to know
     * which display it is looking at and not only the shape of the window.
     */
    val display: RaftAdaptiveDisplay = RaftAdaptiveDisplay.Other
)

@RaftCoreInternalApi const val RaftAdaptivePanePagerEvent = "slockAdaptivePaneChanged"

/**
 * Pure reducer so every host delivers the same layout contract.
 *
 * Task #180 (artin 2026-10-17): a HALF_OPENED / half-folded posture no longer suppresses the second
 * pane. It follows the same width rule as every other posture -- half-folded devices are a real
 * large-screen shape (the folded-out half), and treating them as compact left those users on a
 * phone layout beside a wide-capable window. The Unknown posture still means "no window facts
 * yet" and stays compact. The side reply-thread column keeps its own, higher gate (>=1200dp plus
 * detail-area headroom), so this widens DualPane only.
 */
@RaftCoreInternalApi fun reduceRaftAdaptivePane(
    previous: RaftAdaptivePaneState,
    event: RaftAdaptivePaneEvent
): RaftAdaptivePaneState {
    if (event.widthPx <= 0 || event.heightPx <= 0 || event.density <= 0f) return previous

    val widthDp = event.widthPx / event.density
    val layout =
        if (
            event.posture != RaftAdaptivePanePosture.Unknown &&
            widthDp >= DualPaneMinimumWidthDp
        ) {
            RaftAdaptivePaneLayout.DualPane
        } else {
            RaftAdaptivePaneLayout.Compact
        }
    return RaftAdaptivePaneState(
        widthPx = event.widthPx,
        heightPx = event.heightPx,
        density = event.density,
        posture = event.posture,
        layout = layout,
        sideColumn =
            if (event.sideColumn) {
                event.stripEdge
            } else {
                RaftAdaptiveSideColumn.None
            }
    )
}

/** Which display the window is on. */
@RaftCoreInternalApi enum class RaftAdaptiveDisplay {
    /** The iPhone Duo's outer (folded) display. */
    DuoOuter,

    /** The iPhone Duo's inner (unfolded) display. */
    DuoInner,

    /** Any other device or display. */
    Other
}

/** What a reserved region is. */
@RaftCoreInternalApi enum class RaftAdaptiveReservedRegionKind {
    /** The camera: the region is occluded by hardware. */
    Occlusion,

    /** The fold: the region is the seam between the device's two halves. */
    Division,

    /** The host did not say what the region is. */
    Unknown
}

/**
 * The window width (dp) from which the app lays out wide: two panes, the navigation rail. 744,
 * the iPad mini's portrait width, so every iPad held upright gets the rail (artin 10-30). That is
 * 24 below the web's `md` breakpoint (packages/web MainLayout `(min-width: 768px)`): a browser
 * window that narrow is rare, an iPad mini is not. It was 840, Android's expanded width class.
 * Every wide/compact decision reads this constant, and so does the type scale: it is the design
 * system's [RaftScale.WideMinWidth], from where a window of any input takes the wide type.
 */
@RaftCoreInternalApi val DualPaneMinimumWidthDp: Float = RaftScale.WideMinWidth.value
