package build.raft.app

import build.raft.designsystem.components.LocalRaftFrameInsets
import build.raft.designsystem.components.RaftFrameInsets
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.compat.ui.LocalRaftPageActive
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftSideAction
import build.raft.designsystem.components.RaftSideActionColumn
import build.raft.designsystem.components.RaftSideActionColumnDefaults
import build.raft.designsystem.components.RaftSideActionColumnSide
import build.raft.designsystem.components.RaftSideColumnKeepOut

// iPhone Duo (raft-design #859, page-structure "Foldable phones" and "Frame insets"): on the outer
// screen, and on the inner screen held sideways, the system keeps a column on the camera's side.
// The page ends at the column's inner edge and the column holds the page's controls: Back first on
// a pushed page, then at most two page actions and a ⋯; on a top-level page the actions over the
// host's native (icon-only) tab bar. Off Duo nothing here draws or measures anything.

/**
 * The side-column facts for this window, mapped from the host's [RaftAdaptivePaneEvent] by
 * [toRaftSideColumnPosture] (one model: the event is the only reader of the payload). The default,
 * [None], is every Android window, every iPhone and iPad, and an iPhone Duo held where the system
 * keeps no column: pages lay out as they always have.
 *
 * - [sideColumn]: the system keeps a column (the outer screen; the inner screen held sideways).
 * - [stripEdge]: the column's PHYSICAL edge; it never mirrors in right-to-left.
 * - [stripWidth]: the column's width, its hairline included.
 * - [nativeTabBarHeight]: the native tab bar standing at the column's foot (0 when none).
 * - [keepOut]: the reserved regions (camera, fold) that cross the column, as vertical bands from
 *   the window's top, top first.
 */
@Immutable
internal data class RaftSideColumnPosture(
    val sideColumn: Boolean = false,
    val stripEdge: RaftSideActionColumnSide = RaftSideActionColumnSide.Right,
    val stripWidth: Dp = RaftSideActionColumnDefaults.Width,
    val nativeTabBarHeight: Dp = 0.dp,
    val keepOut: List<RaftSideColumnKeepOut> = emptyList(),
) {
    companion object {
        val None = RaftSideColumnPosture()
    }
}

/**
 * The column from the host's window facts (points = dp). No column unless [sideColumn] is set and
 * [stripEdge] is a side: `left` / `right` are physical (iOS converts the system's logical edge),
 * so nothing here looks at the layout direction. A missing width falls back to the column's
 * default. The keep-out bands are the [reservedRegions] (window rects) that cross the strip's
 * x-range on its edge of a `widthPx / density` wide window.
 */
internal fun RaftAdaptivePaneEvent.toRaftSideColumnPosture(): RaftSideColumnPosture {
    if (!sideColumn) return RaftSideColumnPosture.None
    val side =
        when (stripEdge) {
            RaftAdaptiveSideColumn.Left -> RaftSideActionColumnSide.Left
            RaftAdaptiveSideColumn.Right -> RaftSideActionColumnSide.Right
            RaftAdaptiveSideColumn.None -> return RaftSideColumnPosture.None
        }
    val widthPt = if (stripWidthPt.isFinite() && stripWidthPt > 0f) stripWidthPt else RaftSideActionColumnDefaults.Width.value
    val windowWidthPt = if (density > 0f) widthPx / density else 0f
    val columnLeft = if (side == RaftSideActionColumnSide.Right) windowWidthPt - widthPt else 0f
    val columnRight = columnLeft + widthPt
    val keepOut =
        reservedRegions
            .filter { it.isValid() && it.leftPt + it.widthPt > columnLeft && it.leftPt < columnRight }
            .sortedBy { it.topPt }
            .map { RaftSideColumnKeepOut(top = it.topPt.coerceAtLeast(0f).dp, bottom = (it.topPt + it.heightPt).dp) }
    return RaftSideColumnPosture(
        sideColumn = true,
        stripEdge = side,
        stripWidth = widthPt.dp,
        nativeTabBarHeight = (if (nativeTabBarHeightPt.isFinite()) nativeTabBarHeightPt.coerceAtLeast(0f) else 0f).dp,
        keepOut = keepOut,
    )
}

/** This window's side-column facts; [RaftSideColumnPosture.None] unless the page host provides them. */
internal val LocalRaftSideColumnPosture = staticCompositionLocalOf { RaftSideColumnPosture.None }

/** What the page shows in the side column: [primary] (Back) and its [actions]. */
@Immutable
internal data class RaftSideColumnControls(
    val primary: List<RaftSideAction> = emptyList(),
    val actions: List<RaftSideAction> = emptyList(),
)

/**
 * The column a [RaftSideColumnScaffold] draws, and the controls the page's headers give it. The
 * header composed last that is still on screen wins (a pushed pane over a list, the selected tab
 * over the kept-alive ones).
 */
@Stable
internal class RaftSideColumnHostState {
    internal class Entry {
        var controls by mutableStateOf(RaftSideColumnControls())
    }

    private val entries = mutableStateListOf<Entry>()

    /** Back for a page whose own header has none: the wide layout's history (the inner screen). */
    internal var fallbackBack by mutableStateOf<RaftSideAction?>(null)

    val controls: RaftSideColumnControls
        get() {
            val latest = entries.lastOrNull()?.controls ?: RaftSideColumnControls()
            val back = fallbackBack
            return if (latest.primary.isEmpty() && back != null) latest.copy(primary = listOf(back)) else latest
        }

    internal fun add(entry: Entry) {
        entries.remove(entry)
        entries.add(entry)
    }

    internal fun remove(entry: Entry) {
        entries.remove(entry)
    }
}

/** The enclosing [RaftSideColumnScaffold]'s column while the system keeps one; null otherwise. */
internal val LocalRaftSideColumnHost = staticCompositionLocalOf<RaftSideColumnHostState?> { null }

/**
 * A page root: with [posture] keeping a side column, the page ends at the column's inner edge and
 * the column ([RaftSideActionColumn]) shows what the page's headers hand it through
 * [raftSideColumnControls]. Otherwise it is [content] as it is, with nothing around it.
 */
@Composable
internal fun RaftSideColumnScaffold(
    posture: RaftSideColumnPosture,
    content: @Composable () -> Unit,
) {
    if (!posture.sideColumn) {
        CompositionLocalProvider(
            LocalRaftSideColumnPosture provides posture,
            LocalRaftSideColumnHost provides null,
            content = content,
        )
        return
    }
    val host = remember { RaftSideColumnHostState() }
    val onRight = posture.stripEdge == RaftSideActionColumnSide.Right
    val left = if (onRight) 0.dp else posture.stripWidth
    val right = if (onRight) posture.stripWidth else 0.dp
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                // Physical: the column is the hardware's, not the writing direction's.
                .absolutePadding(left = left, right = right)
                // The column is the system's safe area on its side: what the page pads for it, the
                // column already keeps (the rest of a wider safe area still pads).
                .consumeWindowInsets(PaddingValues.Absolute(left = left, right = right)),
        ) {
            // The column is a frame inset (page-structure "Frame insets"): menus keep 8dp from it and
            // tooltips flip away from it. Physical, never mirrored. The camera sits inside the column's
            // own band, so the page has no top inset of its own.
            CompositionLocalProvider(
                LocalRaftFrameInsets provides RaftFrameInsets(left = left, right = right),
                LocalRaftSideColumnPosture provides posture,
                LocalRaftSideColumnHost provides host,
                content = content,
            )
        }
        val controls = host.controls
        Box(
            Modifier
                .align(if (onRight) AbsoluteAlignment.TopRight else AbsoluteAlignment.TopLeft)
                .fillMaxHeight()
                // The column is transparent over the page's paper, not the frame.
                .background(RaftColor.Canvas),
        ) {
            RaftSideActionColumn(
                primary = controls.primary,
                actions = controls.actions,
                side = posture.stripEdge,
                width = posture.stripWidth,
                // Back or the tab bar, never both: a page with Back has no tab bar under it.
                bottomReserved = if (controls.primary.isEmpty()) posture.nativeTabBarHeight else 0.dp,
                keepOut = posture.keepOut,
            )
        }
    }
}

/**
 * Hands [back] (a pushed page) and [actions] to the enclosing side column, while this page is on
 * screen and [enabled]. True when the column shows them: the caller then draws neither Back nor
 * the icon buttons in its header. False (off Duo, or with no column) leaves the header as it is.
 */
@Composable
internal fun raftSideColumnControls(
    back: RaftSideAction?,
    actions: List<RaftSideAction> = emptyList(),
    enabled: Boolean = true,
): Boolean {
    val host = LocalRaftSideColumnHost.current
    if (host == null || !enabled || !LocalRaftPageActive.current) return false
    val entry = remember(host) { RaftSideColumnHostState.Entry() }
    val controls = RaftSideColumnControls(primary = listOfNotNull(back), actions = actions)
    SideEffect { entry.controls = controls }
    DisposableEffect(host, entry) {
        host.add(entry)
        onDispose { host.remove(entry) }
    }
    return true
}

/**
 * Gives the enclosing side column [back] for the pages that have no Back of their own (the wide
 * layout's detail pane, whose way back is the shell's history). True when the column shows it.
 */
@Composable
internal fun raftSideColumnFallbackBack(back: RaftSideAction): Boolean {
    val host = LocalRaftSideColumnHost.current ?: return false
    SideEffect { host.fallbackBack = back }
    DisposableEffect(host) { onDispose { host.fallbackBack = null } }
    return true
}

/** True inside a [RaftSideColumnScaffold] whose column is showing. */
@Composable
internal fun raftSideColumnActive(): Boolean = LocalRaftSideColumnHost.current != null

/** The column's Back (`arrow-left`, 44pt): first in the column, never in the ⋯. */
@Composable
internal fun raftSideColumnBackAction(onBack: () -> Unit, enabled: Boolean = true): RaftSideAction =
    RaftSideAction(
        id = "back",
        label = stringResource(Res.string.common_back),
        onClick = onBack,
        enabled = enabled,
        keepVisible = true,
    ) { size -> RaftGlyphIcon(RaftGlyph.ArrowLeft, size = size) }

/** A page action drawn with a Lucide icon in the column's ink. */
internal fun raftSideColumnLucideAction(
    id: String,
    label: String,
    icon: RaftLucideIconName,
    onClick: () -> Unit,
    attention: Boolean = false,
    attentionDescription: String? = null,
    priority: Int = 0,
): RaftSideAction =
    RaftSideAction(
        id = id,
        label = label,
        onClick = onClick,
        attention = attention,
        attentionDescription = attentionDescription,
        priority = priority,
    ) { size -> RaftContentLucideIcon(icon, size = size) }
