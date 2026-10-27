package build.raft.app.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import build.raft.app.cmp.RaftCmpRoot
import build.raft.app.cmp.host.BridgeWireHostCapabilities
import build.raft.app.cmp.host.RaftCmpHost

/**
 * Renders [navigator]'s page stack. Every page stays composed while it is on the stack (its state
 * and ViewModels survive a page opened on top, like an Android Activity) but only the top one is
 * laid out and drawn. Each page gets its own host (bridge, navigator, events) and ViewModel store,
 * cleared when the page leaves the stack. Esc / Cmd+[ go back.
 */
@Composable
fun RaftDesktopHost(navigator: RaftDesktopNavigator) {
    val top = navigator.pages.lastOrNull() ?: return
    Box(
        Modifier.fillMaxSize().onPreviewKeyEvent { event ->
            val back =
                event.type == KeyEventType.KeyDown &&
                    (event.key == Key.Escape || (event.isMetaPressed && event.key == Key.LeftBracket))
            back && navigator.back()
        }
    ) {
        navigator.pages.forEach { page ->
            key(page.id) {
                Box(if (page === top) Modifier.fillMaxSize() else Modifier.size(0.dp)) {
                    RaftDesktopPageContent(navigator, page)
                }
            }
        }
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
