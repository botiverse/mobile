package build.raft.designsystem.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * What a page paints behind the status bar, so the host can pick the bar's icons from it
 * ([raftSystemBarsAppearance]). Pages draw edge to edge: the head that pads for the status bar
 * paints that strip, the frame on the app shell and section heads (Raft Yellow, or a theme's
 * frame, which is dark in light mode for Space and Quest) and the canvas on most pushed pages.
 *
 * Heads that pad for the status bar claim it with [RaftStatusBarBackdropClaim]; the newest live
 * claim wins (a head composed over another, e.g. an in-page overlay, is composed later), and
 * [color] is null while nothing claims it (the host then uses the canvas).
 */
@Stable
class RaftStatusBarBackdrop {
    private val claims = mutableStateListOf<Claim>()

    /** The colour of the newest claim, or null when no head claims the status bar. */
    val color: Color?
        get() = claims.lastOrNull()?.color

    internal fun add(claim: Claim) {
        claims.add(claim)
    }

    internal fun remove(claim: Claim) {
        claims.remove(claim)
    }

    internal class Claim(color: Color) {
        var color: Color by mutableStateOf(color)
    }
}

/** The page root's [RaftStatusBarBackdrop]; null outside a host that reads it (fixtures, dialogs). */
val LocalRaftStatusBarBackdrop = staticCompositionLocalOf<RaftStatusBarBackdrop?> { null }

/**
 * Claims the status bar strip for [color] while this is composed and [enabled] (the head pads
 * for the status bar). The claim keeps its place when [color] changes (a theme switch), so it
 * only moves to the front when the head is composed again.
 */
@Composable
fun RaftStatusBarBackdropClaim(color: Color, enabled: Boolean = true) {
    val backdrop = LocalRaftStatusBarBackdrop.current ?: return
    if (!enabled) return
    val claim = remember(backdrop) { RaftStatusBarBackdrop.Claim(color) }
    SideEffect { claim.color = color }
    DisposableEffect(backdrop, claim) {
        backdrop.add(claim)
        onDispose { backdrop.remove(claim) }
    }
}
