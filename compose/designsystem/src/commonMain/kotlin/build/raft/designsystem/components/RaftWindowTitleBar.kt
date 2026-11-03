package build.raft.designsystem.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A window whose system title bar is laid over the app's top row, as the web desktop app's
 * (Electron `titleBarStyle: "hidden"`, raft-ui `--raft-frame-inset-start` / `-top`): the macOS
 * Compose 版 window, with its traffic lights over the start of [RaftTopBar].
 *
 * - [height]: the band at the window's top the title bar covers. The host reports it as the
 *   window's top safe area, so a page header starts under it; [RaftTopBar] takes the band into
 *   its own row instead ([RaftTopBarDefaults.rowTopInset]), so the bar and the title bar are one
 *   row.
 * - [startInset]: the room the window controls take at the bar's start (the traffic lights and
 *   their margin). The bar's frame runs under them; its items start after them.
 * - [modifier]: what the bar's area needs to be the window's title bar (the host's drag and
 *   hit-test handling); applied on the whole bar.
 *
 * Zero everywhere (the default, and every platform but macOS) changes nothing.
 */
@Stable
class RaftWindowTitleBar(
    val height: Dp = 0.dp,
    val startInset: Dp = 0.dp,
    val modifier: Modifier = Modifier,
) {
    companion object {
        val None = RaftWindowTitleBar()
    }
}

/** See [RaftWindowTitleBar]. Provided by the desktop host on macOS; [RaftWindowTitleBar.None] elsewhere. */
val LocalRaftWindowTitleBar = staticCompositionLocalOf { RaftWindowTitleBar.None }

/**
 * The bar's own top inset under [LocalRaftWindowTitleBar]: [topInset] (the window's top safe area)
 * less the title bar's band, which the bar's row covers itself. Unchanged without a title bar.
 */
@Composable
fun RaftTopBarDefaults.rowTopInset(topInset: Dp): Dp =
    raftTopBarRowTopInset(topInset, LocalRaftWindowTitleBar.current.height)

/** [rowTopInset] as a pure function: the band comes out of [topInset], never below zero. */
fun raftTopBarRowTopInset(topInset: Dp, titleBarHeight: Dp): Dp =
    (topInset - titleBarHeight.coerceAtLeast(0.dp)).coerceAtLeast(0.dp)
