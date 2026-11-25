package build.raft.app

import androidx.compose.runtime.Composable
import build.raft.app.data.RaftDesktopPaths
import build.raft.webcontainer.MacOsNativeWebEngine
import build.raft.webcontainer.WebIsolationScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// No WebView keep-alive here yet: every page creates and destroys its own WebView.
internal actual fun raftWebViewKeptDocument(key: String): RaftWebViewKeptDocument? = null

internal actual fun raftWebViewEvaluateInKept(key: String, origin: String, script: String): Boolean = false

internal actual fun raftWebViewKeepAliveClear() = Unit

internal actual suspend fun raftClearPlatformWebViewAuthStorage(origins: Set<String>) {
    // Clear macOS native WKWebsiteDataStore default scope ONLY if the direct WKWebView bridge was
    // already activated in this process (flag-OFF sessions never dlopen the dylib or alter sign-out).
    runCatching {
        if (MacOsNativeWebEngine.Default.isAlreadyLoaded) {
            MacOsNativeWebEngine.Default.clearIsolationScope(WebIsolationScope.Default)
        }
    }
    // Authenticated desktop WebViews use incognito/ephemeral storage (so macOS Wry never writes
    // tokens to ~/Library/WebKit); also purge any legacy disk webview directory off the caller thread.
    withContext(Dispatchers.IO) {
        runCatching {
            val dir = RaftDesktopPaths.existingWebViewDataDirOrNull() ?: return@runCatching
            dir.listFiles()?.forEach { child -> runCatching { child.deleteRecursively() } }
        }
    }
}

@Composable
internal actual fun RaftWebViewPrewarmEffect(config: RaftWebViewConfig?) = Unit
