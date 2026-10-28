package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import build.raft.app.compat.json.JSONObject
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

/** A rect the system keeps for itself, in window points (= dp), physical edges. */
@Immutable
internal data class RaftReservedRegion(
    val left: Dp,
    val top: Dp,
    val right: Dp,
    val bottom: Dp,
    val kind: Kind = Kind.Occlusion,
) {
    /** UIKit's `UIView.ReservedRegion` kinds: the camera (occlusion) or the fold (division). */
    enum class Kind { Occlusion, Division }
}

/**
 * The host's side-column facts for this window (iOS `RaftIosAdaptivePanePublisher`, in the
 * `slockAdaptivePaneChanged` payload; see [toRaftSideColumnPosture]). The default, [None], is
 * every Android window, every iPhone and iPad, and an iPhone Duo held where the system keeps no
 * column: pages lay out as they always have.
 *
 * - [sideColumn]: the system keeps a column on the camera's side (the outer screen; the inner
 *   screen held sideways).
 * - [stripEdge]: the column's PHYSICAL edge; it never mirrors in right-to-left.
 * - [stripWidth]: the column's width, its hairline included.
 * - [nativeTabBarHeight]: the native tab bar standing at the column's foot (0 when none).
 * - [reservedRegions]: the camera and the fold, window rects (empty on iOS 27.0).
 */
@Immutable
internal data class RaftSideColumnPosture(
    val sideColumn: Boolean = false,
    val stripEdge: RaftSideActionColumnSide = RaftSideActionColumnSide.Right,
    val stripWidth: Dp = RaftSideActionColumnDefaults.Width,
    val nativeTabBarHeight: Dp = 0.dp,
    val reservedRegions: List<RaftReservedRegion> = emptyList(),
) {
    /**
     * The [reservedRegions] that cross a column [stripWidth] wide on [stripEdge] of a
     * [windowWidth] window whose top is at the window's top, as the column's keep-out bands.
     */
    fun keepOutBands(windowWidth: Dp): List<RaftSideColumnKeepOut> {
        val columnLeft = if (stripEdge == RaftSideActionColumnSide.Right) windowWidth - stripWidth else 0.dp
        val columnRight = columnLeft + stripWidth
        return reservedRegions
            .filter { it.right > columnLeft && it.left < columnRight && it.bottom > it.top }
            .sortedBy { it.top }
            .map { RaftSideColumnKeepOut(top = it.top.coerceAtLeast(0.dp), bottom = it.bottom) }
    }

    companion object {
        val None = RaftSideColumnPosture()
    }
}

/**
 * Reads the side-column facts from the host's adaptive-pane payload. Keys (points, physical):
 * `sideColumn` (bool), `stripEdge` ("right" / "left"), `stripWidth`, `nativeTabBarHeight`, and
 * `reservedRegions`: an array of `{x, y, width, height, kind}` with `kind` "occlusion" or
 * "division". A payload without `sideColumn: true` (every host today) is [RaftSideColumnPosture.None].
 */
internal fun JSONObject.toRaftSideColumnPosture(): RaftSideColumnPosture {
    if (!optBoolean("sideColumn", false)) return RaftSideColumnPosture.None
    val width = optDouble("stripWidth", 0.0)
    val regions =
        optJSONArray("reservedRegions")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                val rect = array.opt(index) as? JSONObject ?: return@mapNotNull null
                val x = rect.optDouble("x", 0.0)
                val y = rect.optDouble("y", 0.0)
                val w = rect.optDouble("width", 0.0)
                val h = rect.optDouble("height", 0.0)
                if (w <= 0.0 || h <= 0.0) return@mapNotNull null
                RaftReservedRegion(
                    left = x.dp,
                    top = y.dp,
                    right = (x + w).dp,
                    bottom = (y + h).dp,
                    kind = if (rect.optString("kind") == "division") RaftReservedRegion.Kind.Division else RaftReservedRegion.Kind.Occlusion,
                )
            }
        }.orEmpty()
    return RaftSideColumnPosture(
        sideColumn = true,
        stripEdge = if (optString("stripEdge") == "left") RaftSideActionColumnSide.Left else RaftSideActionColumnSide.Right,
        stripWidth = if (width > 0.0) width.dp else RaftSideActionColumnDefaults.Width,
        nativeTabBarHeight = optDouble("nativeTabBarHeight", 0.0).coerceAtLeast(0.0).dp,
        reservedRegions = regions,
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
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val windowWidth = maxWidth
        Box(
            Modifier
                .fillMaxSize()
                // Physical: the column is the hardware's, not the writing direction's.
                .absolutePadding(left = left, right = right)
                // The column is the system's safe area on its side: what the page pads for it, the
                // column already keeps (the rest of a wider safe area still pads).
                .consumeWindowInsets(PaddingValues.Absolute(left = left, right = right)),
        ) {
            // TODO(iphone-duo, LocalRaftFrameInsets): when the design system's LocalRaftFrameInsets
            // lands (sindresorhus), provide it here with the column's width on its physical edge
            // (`right = posture.stripWidth` on the right) and, where a camera band crosses the top
            // of the page, a top inset, so menus and toasts keep 8dp from the column and tooltips
            // flip away from it (page-structure "Frame insets").
            CompositionLocalProvider(
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
                keepOut = posture.keepOutBands(windowWidth),
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
