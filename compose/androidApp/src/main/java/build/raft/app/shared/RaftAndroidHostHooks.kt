package build.raft.app.shared

import android.content.Context
import build.raft.app.RaftAndroidWebViewHooks
import build.raft.app.RaftWebViewDocument
import build.raft.app.image.RaftImageHost
import build.raft.app.image.RaftManagedImageRead
import build.raft.app.image.RaftManagedImageReader
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Installs the Android host hooks :shared's CMP runtime reads (once per process, idempotent):
 *
 * - [RaftImageHost.managedImageReader]: managed avatar bytes from the app-scope avatar cache
 *   (`RaftAndroidAvatarCacheProcess.beginRender`), with the decoded-size registration and the
 *   render lease the Kuikly image adapter used (`registerDecoded`, `close`).
 * - [RaftAndroidWebViewHooks.mainDocumentProvider]: release notes main documents from
 *   [RaftReleaseNotesDocumentCache] (the Kuikly sandbox WebView's disk cache), called by the
 *   WebView off the main thread.
 * - [build.raft.app.data.RaftConversationReadHost.listener]: a conversation read in the app clears
 *   its unread notification ([AndroidUnreadNotificationPoster.clearConversation]).
 *
 * The WebView `<input type=file>` hook (`RaftWebViewFileChooserHost`) is implemented by the page
 * host Activity, [RaftCmpActivity].
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
            val read =
                raftOpenRenderLease(
                    context = Dispatchers.IO,
                    open = { RaftAndroidAvatarCacheProcess.currentOrNull()?.beginRender(descriptor) },
                    close = { it.close() },
                ) ?: return@RaftManagedImageReader null
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
        AndroidUnreadNotificationPoster.installConversationReadListener(appContext)
        val releaseNotesDocuments = RaftReleaseNotesDocumentCache(appContext)
        RaftAndroidWebViewHooks.mainDocumentProvider = { url ->
            releaseNotesDocuments.loadForWebView(url)?.let { document ->
                RaftWebViewDocument(bytes = document.bytes, mimeType = document.mimeType, source = document.source)
            }
        }
    }
}

/**
 * Opens a render lease on [context] and hands it to the caller without ever leaking it.
 *
 * `withContext` resumes its caller with a prompt cancellation guarantee: if the caller was
 * cancelled while [open] ran, the returned value is discarded and a [CancellationException] is
 * thrown instead, so a lease returned *from* the block would never be closed (and the avatar
 * cache's retire / clearAll would wait forever for the render drain). The lease is therefore
 * passed out through a side slot; [open] runs [NonCancellable] so an acquired lease is always
 * recorded, and a caller cancelled before the hand-over closes it here.
 */
internal suspend fun <T : Any> raftOpenRenderLease(
    context: CoroutineContext,
    open: () -> T?,
    close: (T) -> Unit,
    beforeHandOver: () -> Unit = {},
): T? {
    val slot = AtomicReference<T?>(null)
    try {
        withContext(context + NonCancellable) { slot.set(open()) }
        // Test hook: runs in the window between the block returning and the hand-over,
        // which is otherwise unobservable. Production never passes this -- it defaults to
        // a no-op, so behaviour is unchanged. Do not pass anything with side effects.
        beforeHandOver()
        // A cancel can arrive after withContext returns but before the hand-over below.
        // Observing it here makes "cancelled before the hand-over" deterministic: either
        // this throws and the catch closes the lease, or the caller is still active.
        currentCoroutineContext().ensureActive()
    } catch (failure: Throwable) {
        slot.getAndSet(null)?.let(close)
        throw failure
    }
    return slot.getAndSet(null)
}
