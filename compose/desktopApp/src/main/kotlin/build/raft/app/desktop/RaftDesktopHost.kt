package build.raft.app.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import build.raft.app.cmp.RaftCmpRoot
import build.raft.app.cmp.RaftCmpToastLayer
import build.raft.app.cmp.host.BridgeWireHostCapabilities
import build.raft.app.cmp.host.RaftCmpHost
import build.raft.app.nav.RaftPageStackDisplay

/**
 * Renders [navigator]'s back stack with Navigation 3: :shared's [RaftPageStackDisplay], a
 * `NavDisplay` over [RaftDesktopNavigator.pages] with the shared stack scene. Every page stays
 * composed while it is on the stack (its state and ViewModels survive a page opened on top, like an
 * Android Activity) but only the top one is laid out and drawn. Each page gets its own host (bridge,
 * navigator, events) and ViewModel store, cleared when the page leaves the stack. Esc / Cmd+[ go back. A mouse's back / forward
 * buttons step through the shell's history ([RaftDesktopNavigator.history]).
 */
@Composable
fun RaftDesktopHost(navigator: RaftDesktopNavigator) {
    // The window's one toast host, over every page of the stack (each page's RaftCmpRoot uses it).
    RaftCmpToastLayer {
        RaftDesktopNavDisplay(navigator) { page -> RaftDesktopPageContent(navigator, page) }
    }
}

/**
 * The back stack's `NavDisplay` ([RaftPageStackDisplay]) with the window's back input; [pageContent]
 * draws one entry. Back with only the root page left does nothing here: the key handler leaves the
 * key unconsumed (the shell's own back handling — closing a pane, its wide history — gets it) and
 * `NavDisplay`'s back handler is off (no previous entry) and a no-op anyway.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun RaftDesktopNavDisplay(
    navigator: RaftDesktopNavigator,
    pageContent: @Composable (RaftDesktopPage) -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            // Bubbling, not preview: a focused popup, menu or suggestion list that handles Esc itself
            // (closing) wins over going back.
            .onKeyEvent { event ->
                val back =
                    event.type == KeyEventType.KeyDown &&
                        (event.key == Key.Escape || (event.isMetaPressed && event.key == Key.LeftBracket))
                back && navigator.back()
            }
            // Initial pass: no control below uses the side buttons, and a list that consumes the
            // press for its own scrolling must not swallow them.
            .onPointerEvent(PointerEventType.Press, pass = PointerEventPass.Initial) { event ->
                raftDesktopHistoryMouseButton(event.button)?.let(navigator::history)
            }
    ) {
        RaftPageStackDisplay(navigator.stack, pageContent)
    }
}

@Composable
private fun RaftDesktopPageContent(navigator: RaftDesktopNavigator, page: RaftDesktopPage) {
    val transport = remember { RaftDesktopBridgeTransport(onClosePage = { navigator.close(page) }) }
    val host =
        remember {
            RaftCmpHost(
                capabilities = BridgeWireHostCapabilities(transport),
                navigator = navigator.navigatorFor(page),
                bridgeTransport = transport,
                events = page.events,
            )
        }
    // Window facts for the adaptive layout (rail + split panes at >= 768dp): the page's size,
    // republished on every resize. A page under the top one is 0x0 and publishes nothing.
    val publisher = remember { RaftDesktopAdaptivePanePublisher(page.events::send) }
    val density = LocalDensity.current.density
    var size by remember { mutableStateOf(IntSize.Zero) }
    // After the first composition is applied, i.e. after the page subscribed to page.events.
    LaunchedEffect(size, density) {
        if (publisher.publish(size.width, size.height, density)) {
            log("adaptive pane page=${page.args.pageName} widthPx=${size.width} heightPx=${size.height} density=$density")
        }
    }
    Box(Modifier.fillMaxSize().onSizeChanged { size = it }) {
        RaftCmpRoot(args = page.args, host = host)
    }
}
