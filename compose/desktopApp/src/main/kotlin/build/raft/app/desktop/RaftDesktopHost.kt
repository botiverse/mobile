package build.raft.app.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
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
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import build.raft.app.cmp.RaftCmpRoot
import build.raft.app.cmp.host.BridgeWireHostCapabilities
import build.raft.app.cmp.host.RaftCmpHost

/**
 * Renders [navigator]'s back stack with Navigation 3: a `NavDisplay` over [RaftDesktopNavigator.pages]
 * with [RaftDesktopStackSceneStrategy]. Every page stays composed while it is on the stack (its
 * state and ViewModels survive a page opened on top, like an Android Activity) but only the top one
 * is laid out and drawn. Each page gets its own host (bridge, navigator, events) and ViewModel
 * store, cleared when the page leaves the stack. Esc / Cmd+[ go back. A mouse's back / forward
 * buttons step through the shell's history ([RaftDesktopNavigator.history]).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun RaftDesktopHost(navigator: RaftDesktopNavigator) {
    // Closing the last page quits; NavDisplay needs a non-empty stack until then.
    if (navigator.pages.isEmpty()) return
    val sceneStrategy = remember { RaftDesktopStackSceneStrategy<RaftDesktopPage>() }
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
        NavDisplay(
            backStack = navigator.pages,
            // The window's back event (Esc that nothing focused took): same as the keys above.
            onBack = { navigator.back() },
            sceneStrategies = listOf(sceneStrategy),
            entryProvider = { page ->
                NavEntry(page, contentKey = page.contentKey, metadata = raftDesktopPaneRoleMetadata(page.args.pageName)) {
                    RaftDesktopPageContent(navigator, page)
                }
            },
        )
        RaftDesktopToasts.Overlay()
    }
}

@Composable
private fun RaftDesktopPageContent(navigator: RaftDesktopNavigator, page: RaftDesktopPage) {
    val owner = remember { PageViewModelStoreOwner() }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
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
    // Window facts for the adaptive layout (rail + split panes at >= 840dp): the page's size,
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
        CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
            RaftCmpRoot(args = page.args, host = host)
        }
    }
}

private class PageViewModelStoreOwner : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}
