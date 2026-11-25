package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import build.raft.webcontainer.WebContainerIosSparePool
import build.raft.webcontainer.resource.WebResourceInterceptor

@Composable
internal actual fun rememberRaftWebContainerPlatformResourceInterceptor(): WebResourceInterceptor? = null

@Composable
internal actual fun RaftWebContainerPlatformScope(
    config: RaftWebViewConfig,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    // Schedule the container-owned iOS warm spare only once mobile_web_container_v1 actually runs,
    // avoiding allocating an extra idle WKWebView while the flag remains default-OFF.
    LaunchedEffect(config.allowPinchZoom, config.useWideViewPort) {
        WebContainerIosSparePool.schedule(
            preferredIgnoresViewportScaleLimits = config.allowPinchZoom || config.useWideViewPort,
        )
    }
    content()
}
