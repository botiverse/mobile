package build.raft.app

import androidx.compose.runtime.Composable
import build.raft.app.data.RaftDesktopPaths
import build.raft.webcontainer.MacOsNativeWebEngine
import build.raft.webcontainer.WebIsolationScope
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val MACOS_NATIVE_DEFAULT_SCOPE_MARKER_FILENAME: String = "macos_native_default_webview_used"

// No WebView keep-alive here yet: every page creates and destroys its own WebView.
internal actual fun raftWebViewKeptDocument(key: String): RaftWebViewKeptDocument? = null

internal actual fun raftWebViewEvaluateInKept(key: String, origin: String, script: String): Boolean = false

internal actual fun raftWebViewKeepAliveClear() = Unit

/**
 * Persists a tiny marker file whenever the macOS native `WKWebView` opens a [WebIsolationScope.Default]
 * page, so a cold-restarted desktop process signing out before opening any WebView still knows to
 * load the native bridge and purge `WKWebsiteDataStore.defaultDataStore()`.
 */
fun markDesktopMacOsNativeDefaultScopeUsed(baseDataDir: File = RaftDesktopPaths.dataDir) {
    runCatching {
        if (!baseDataDir.isDirectory) baseDataDir.mkdirs()
        val marker = File(baseDataDir, MACOS_NATIVE_DEFAULT_SCOPE_MARKER_FILENAME)
        if (!marker.isFile) {
            marker.writeText("1")
        }
    }
}

/**
 * Purges macOS native `WKWebsiteDataStore.defaultDataStore()` on logout whenever EITHER the native
 * bridge was loaded in this process OR the persisted disk marker indicates a prior process opened a
 * `Default` scope native WebView. Awaits completion (bounded by 2_000 ms) and removes the marker
 * only after clearing succeeds; flag-OFF sessions and tests with uninitialized desktop storage skip
 * cleanly without triggering `dlopen` or creating the user's real Raft data directory.
 */
suspend fun clearDesktopMacOsNativeDefaultStorage(
    baseDataDirProvider: () -> File? = { RaftDesktopPaths.existingDataDirOrNull() },
    isAlreadyLoaded: () -> Boolean = { MacOsNativeWebEngine.Default.isAlreadyLoaded },
    clearDefaultScope: suspend () -> Boolean = {
        MacOsNativeWebEngine.Default.clearIsolationScopeAndAwait(
            scope = WebIsolationScope.Default,
            timeoutMs = 2_000L,
        )
    },
): Boolean {
    val marker =
        withContext(Dispatchers.IO) {
            runCatching {
                baseDataDirProvider()?.let { dir -> File(dir, MACOS_NATIVE_DEFAULT_SCOPE_MARKER_FILENAME) }
            }.getOrNull()
        }
    val markerExists =
        if (marker != null) {
            withContext(Dispatchers.IO) { runCatching { marker.isFile }.getOrDefault(false) }
        } else {
            false
        }
    if (!isAlreadyLoaded() && !markerExists) return false
    val cleared =
        try {
            clearDefaultScope()
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            false
        }
    if (cleared && marker != null && markerExists) {
        withContext(Dispatchers.IO) { runCatching { marker.delete() } }
    }
    return cleared
}

internal actual suspend fun raftClearPlatformWebViewAuthStorage(origins: Set<String>) {
    var nativeWebKitCleared = false
    try {
        nativeWebKitCleared = clearDesktopMacOsNativeDefaultStorage()
    } catch (ce: CancellationException) {
        throw ce
    } catch (_: Throwable) {
        // Best-effort native WebKit cleanup; fall through to legacy disk cleanup.
    }
    if (origins.isEmpty() && (nativeWebKitCleared || MacOsNativeWebEngine.Default.isAlreadyLoaded)) {
        try {
            MacOsNativeWebEngine.Default.clearIsolationScopeAndAwait(
                scope = WebIsolationScope.Named(RAFT_THIRD_PARTY_STORAGE_SCOPE_ID),
                timeoutMs = 2_000L,
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
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

internal actual suspend fun raftClearPlatformInAppBrowserStorage(): Boolean {
    return try {
        raftClearPlatformWebViewAuthStorage(emptySet())
        true
    } catch (ce: CancellationException) {
        throw ce
    } catch (_: Throwable) {
        false
    }
}

@Composable
internal actual fun RaftWebViewPrewarmEffect(config: RaftWebViewConfig?) = Unit
