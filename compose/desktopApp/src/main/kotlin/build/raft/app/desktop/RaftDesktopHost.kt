package build.raft.app.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
        RaftCmpRoot(args = page.args, host = host)
    }
}

private class PageViewModelStoreOwner : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}
