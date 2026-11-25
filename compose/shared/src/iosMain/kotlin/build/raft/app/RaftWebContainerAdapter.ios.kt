package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import build.raft.webcontainer.resource.WebResourceInterceptor

@Composable
internal actual fun rememberRaftWebContainerPlatformResourceInterceptor(): WebResourceInterceptor? = null

@Composable
internal actual fun RaftWebContainerPlatformScope(
    config: RaftWebViewConfig,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    content()
}
