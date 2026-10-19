package build.raft.app.shared

import android.content.Context
import build.raft.app.RaftAndroidWebViewHooks
import build.raft.app.RaftWebViewDocument
import build.raft.app.image.RaftImageHost
import build.raft.app.image.RaftManagedImageRead
import build.raft.app.image.RaftManagedImageReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Installs the Android host hooks :shared's CMP runtime reads (once per process, idempotent):
 *
 * - [RaftImageHost.managedImageReader]: managed avatar bytes from the app-scope avatar cache
 *   (`SlockAndroidAvatarCacheProcess.beginRender`), with the decoded-size registration and the
 *   render lease the Kuikly image adapter used (`registerDecoded`, `close`).
 * - [RaftAndroidWebViewHooks.mainDocumentProvider]: release notes main documents from
 *   [SlockReleaseNotesDocumentCache] (the Kuikly sandbox WebView's disk cache), called by the
 *   WebView off the main thread.
 *
 * The WebView `<input type=file>` hook (`RaftWebViewFileChooserHost`) is implemented by the page
 * host Activity, [SlockCmpActivity].
 */
internal object RaftAndroidHostHooks {
    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            installed = true
        }
        val appContext = context.applicationContext
        RaftImageHost.managedImageReader = RaftManagedImageReader { descriptor ->
            withContext(Dispatchers.IO) {
                val read = SlockAndroidAvatarCacheProcess.currentOrNull()?.beginRender(descriptor)
                    ?: return@withContext null
                RaftManagedImageRead(
                    bytes = read.bytes,
                    contentType = read.contentType,
                    onDecoded = { width, height, scaleIdentity ->
                        read.registerDecoded(
                            width = width.coerceAtLeast(0),
                            height = height.coerceAtLeast(0),
                            scaleIdentity = scaleIdentity,
                        )
                    },
                    close = { read.close() },
                )
            }
        }
        val releaseNotesDocuments = SlockReleaseNotesDocumentCache(appContext)
        RaftAndroidWebViewHooks.mainDocumentProvider = { url ->
            releaseNotesDocuments.loadForWebView(url)?.let { document ->
                RaftWebViewDocument(bytes = document.bytes, mimeType = document.mimeType, source = document.source)
            }
        }
    }
}
