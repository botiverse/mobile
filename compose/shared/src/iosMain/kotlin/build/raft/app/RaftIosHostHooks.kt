package build.raft.app

import build.raft.app.compat.pager.RaftPageRegistry
import build.raft.app.image.RaftImageHost
import build.raft.app.image.RaftManagedImageRead
import build.raft.app.image.RaftManagedImageReader
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ReportUnhandledExceptionHook
import kotlin.native.setUnhandledExceptionHook
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.posix.memcpy

/**
 * Implemented by the iOS app (Swift, `RaftIosAvatarCacheHost`): the committed bytes of one managed
 * avatar render read (`slock-avatar-render-v1:` models), matched exactly on slot, commit identity
 * and model revision, or null when the durable store has no such entry (or reads are held).
 * Called off the main thread.
 */
interface RaftIosManagedImageStore {
    fun readManagedImage(
        nativePartitionKey: String,
        nativeSubjectKey: String,
        nativeBytesKey: String,
        commitGeneration: Long,
        commitNonce: String,
        modelRevision: Long,
    ): RaftIosManagedImageBytes?
}

/**
 * Implemented by the iOS app (Swift, `RaftIosUnhandledExceptionRecorder`): records a Kotlin
 * unhandled exception synchronously into the durable diagnostics log
 * (`RaftIosDiagnosticsRecordUnhandledException`) before the process terminates. Called on the
 * thread that threw, possibly not the main thread; must not throw and must not block on the main
 * thread.
 */
interface RaftIosUnhandledExceptionSink {
    fun recordUnhandledException(reason: String, stack: String)
}

/** One managed avatar read: the stored bytes and their sniffed content type. */
class RaftIosManagedImageBytes(
    val bytes: NSData,
    val contentType: String?,
)

/**
 * The iOS host hooks :shared's CMP runtime reads, installed once at app start (`iOSApp.init`),
 * before the first page is created. The iOS counterpart of Android's `RaftApplication`
 * `RaftPageRegistry.install()` + `RaftAndroidHostHooks.install`:
 *
 * - [RaftPageRegistry.install]: every :shared page renders through `RaftCmpRoot`, and the Raft
 *   Coil image loader becomes the singleton.
 * - [RaftImageHost.managedImageReader]: managed avatar bytes from the app's durable avatar store
 *   ([managedImages]; the Kuikly image handler read the same store through `imageForRenderModel:`).
 *
 * - Kotlin unhandled exceptions: forwarded to [unhandledExceptions] (the Kuikly render controller's
 *   `onUnhandledException` used to record them), then the runtime terminates as before.
 *
 * The sandbox WebView (`RaftIosSandboxWebView`) and the video player (`RaftVideoPlayer`) need no
 * host hooks on iOS: the release notes document cache was Android-only.
 */
object RaftIosHostHooks {
    private var installed = false

    /** Main thread only. Idempotent. */
    fun install(
        managedImages: RaftIosManagedImageStore?,
        unhandledExceptions: RaftIosUnhandledExceptionSink?,
    ) {
        if (installed) return
        installed = true
        if (unhandledExceptions != null) installUnhandledExceptionHook(unhandledExceptions)
        RaftPageRegistry.install()
        if (managedImages != null) {
            RaftImageHost.managedImageReader =
                RaftManagedImageReader { descriptor ->
                    withContext(Dispatchers.Default) {
                        val read =
                            managedImages.readManagedImage(
                                nativePartitionKey = descriptor.slot.nativePartitionKey,
                                nativeSubjectKey = descriptor.slot.nativeSubjectKey,
                                nativeBytesKey = descriptor.slot.nativeBytesKey,
                                commitGeneration = descriptor.commitIdentity.commitGeneration,
                                commitNonce = descriptor.commitIdentity.nonce,
                                modelRevision = descriptor.modelRevision,
                            ) ?: return@withContext null
                        val bytes = read.bytes.toRaftHostByteArray()
                        if (bytes.isEmpty()) return@withContext null
                        RaftManagedImageRead(bytes = bytes, contentType = read.contentType)
                    }
                }
        }
    }
}

/**
 * Records every Kotlin unhandled exception through [sink], then hands it to the previously
 * installed hook (if any). Returning from the hook lets the Kotlin/Native runtime terminate the
 * process as it would have without a hook, so this only adds a diagnostics record.
 */
@OptIn(ExperimentalNativeApi::class)
private fun installUnhandledExceptionHook(sink: RaftIosUnhandledExceptionSink) {
    var previous: ReportUnhandledExceptionHook? = null
    previous =
        setUnhandledExceptionHook { throwable ->
            try {
                val reason = throwable::class.qualifiedName.orEmpty().ifEmpty { "Throwable" } +
                    (throwable.message?.let { ": $it" } ?: "")
                sink.recordUnhandledException(reason = reason, stack = throwable.stackTraceToString())
            } catch (_: Throwable) {
                // Recording is best effort; never mask the original exception.
            }
            previous?.invoke(throwable)
        }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toRaftHostByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val out = ByteArray(size)
    out.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
    return out
}
