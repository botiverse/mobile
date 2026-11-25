package build.raft.app

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import build.raft.webcontainer.LocalAndroidWebFileChooser
import build.raft.webcontainer.WebContainerAndroidFileChooser
import build.raft.webcontainer.resource.WebResourceInterceptor
import build.raft.webcontainer.resource.WebResourceResponseData
import build.raft.webcontainer.resource.WebResourceSource

private fun unwrapWebContainerFileChooserHost(context: Context): RaftWebViewFileChooserHost? {
    var current: Context? = context
    while (current != null) {
        if (current is RaftWebViewFileChooserHost) return current
        current = (current as? ContextWrapper)?.baseContext ?: return null
    }
    return null
}

private val RaftAndroidWebContainerFileChooser =
    WebContainerAndroidFileChooser { context, params, callback ->
        val host = unwrapWebContainerFileChooserHost(context)
        if (host == null) {
            callback.onReceiveValue(null)
            false
        } else {
            host.launchSandboxWebViewFileChooser(params, callback)
        }
    }

/**
 * Bridges [RaftAndroidWebViewHooks.mainDocumentProvider] (off-main-thread host document caches
 * such as release notes) into `:web-container`'s [WebResourceInterceptor] pipeline.
 */
internal val RaftAndroidMainDocumentResourceInterceptor =
    WebResourceInterceptor { request ->
        if (!request.isMainFrame || !request.method.equals("GET", ignoreCase = true)) return@WebResourceInterceptor null
        val provider = RaftAndroidWebViewHooks.mainDocumentProvider ?: return@WebResourceInterceptor null
        val document = runCatching { provider(request.url) }.getOrNull() ?: return@WebResourceInterceptor null
        WebResourceResponseData(
            mimeType = document.mimeType,
            encoding = "utf-8",
            statusCode = 200,
            reasonPhrase = "OK",
            body = document.bytes,
            source = WebResourceSource.HOST_CACHE,
        )
    }

@Composable
internal actual fun rememberRaftWebContainerPlatformResourceInterceptor(): WebResourceInterceptor? =
    RaftAndroidMainDocumentResourceInterceptor

@Composable
internal actual fun RaftWebContainerPlatformScope(
    config: RaftWebViewConfig,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    if (LocalAndroidWebFileChooser.current != null) {
        content()
    } else {
        CompositionLocalProvider(LocalAndroidWebFileChooser provides RaftAndroidWebContainerFileChooser) {
            content()
        }
    }
}
