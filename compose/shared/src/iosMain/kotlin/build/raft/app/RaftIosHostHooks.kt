package build.raft.app

import build.raft.app.network.installIosRaftUserAgentDevice
import build.raft.app.data.RaftAppAppearanceRuntime
import build.raft.app.data.RaftFrameMetricsTraceReporter
import build.raft.app.compat.pager.RaftPageRegistry
import build.raft.app.image.RaftImageHost
import build.raft.app.image.RaftManagedImageRead
import build.raft.app.image.RaftManagedImageReader
import build.raft.designsystem.theme.RaftPointerInputState
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ReportUnhandledExceptionHook
import kotlin.native.setUnhandledExceptionHook
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.UIKit.UIScreen
import platform.UIKit.UIUserInterfaceStyle
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

    /**
     * A page controller went away: drop its window-inset snapshot (keyed `ios:page:<uuid>`, see
     * `RaftPageViewController`). Main thread only.
     */
    fun forgetPageWindowInsets(windowId: String) {
        RaftHostWindowInsets.remove(windowId)
    }

    /**
     * Installs the iOS host's scroll-session edge sink for [RaftScrollFrameSignal] so
     * `RaftIosDisplayLinkJankTracker` can measure `mobile.frames.scroll` sessions with parity to
     * Android's `RaftScrollFrameProbe`. Main thread only.
     */
    fun installScrollFrameSignalSink(sink: (list: String, scrolling: Boolean) -> Unit) {
        RaftScrollFrameSignal.sink = sink
    }

    /** Reports one finished iOS scroll frame-metrics session to ScopeDB (`mobile.frames.scroll`). */
    fun reportScrollFrameTrace(
        page: String,
        list: String,
        reason: String,
        firstFrameMs: Long,
        durationMs: Long,
        frames: Int,
        jankFrames: Int,
        longestFrameMs: Long,
        p95FrameMs: Long,
        frameIntervalNanos: Long,
        endFrameIntervalNanos: Long,
        skippedSessions: Int,
        skippedJankFrames: Int,
        skippedLongestFrameMs: Long,
        deviceModel: String?,
        osVersion: String?,
    ) {
        RaftFrameMetricsTraceReporter.reportScroll(
            page = page,
            list = list,
            reason = reason,
            firstFrameMs = firstFrameMs,
            durationMs = durationMs,
            frames = frames,
            jankFrames = jankFrames,
            longestFrameMs = longestFrameMs,
            p95FrameMs = p95FrameMs,
            frameIntervalNanos = frameIntervalNanos,
            endFrameIntervalNanos = endFrameIntervalNanos,
            skippedSessions = skippedSessions,
            skippedJankFrames = skippedJankFrames,
            skippedLongestFrameMs = skippedLongestFrameMs,
            deviceModel = deviceModel,
            osVersion = osVersion,
        )
    }

    /** Flushes throttled iOS scroll sessions that hit jank when pausing (`reason = "paused_skipped"`). */
    fun reportScrollSkippedFlushFrameTrace(
        page: String,
        list: String,
        skippedSessions: Int,
        skippedJankFrames: Int,
        skippedLongestFrameMs: Long,
        deviceModel: String?,
        osVersion: String?,
    ) {
        RaftFrameMetricsTraceReporter.reportScrollSkippedFlush(
            page = page,
            list = list,
            skippedSessions = skippedSessions,
            skippedJankFrames = skippedJankFrames,
            skippedLongestFrameMs = skippedLongestFrameMs,
            deviceModel = deviceModel,
            osVersion = osVersion,
        )
    }

    /** Reports one iOS foreground-return frame window to ScopeDB (`mobile.frames.resume`). */
    fun reportResumeFrameTrace(
        page: String,
        reason: String,
        backgroundMs: Long,
        startToResumeMs: Long,
        firstFrameMs: Long,
        windowMs: Long,
        frames: Int,
        jankFrames: Int,
        blockedMs: Long,
        longestFrameMs: Long,
        frameIntervalNanos: Long,
        deviceModel: String?,
        osVersion: String?,
    ) {
        RaftFrameMetricsTraceReporter.reportResume(
            page = page,
            reason = reason,
            backgroundMs = backgroundMs,
            startToResumeMs = startToResumeMs,
            firstFrameMs = firstFrameMs,
            windowMs = windowMs,
            frames = frames,
            jankFrames = jankFrames,
            blockedMs = blockedMs,
            longestFrameMs = longestFrameMs,
            frameIntervalNanos = frameIntervalNanos,
            deviceModel = deviceModel,
            osVersion = osVersion,
        )
    }

    /** Reports one iOS page push/pop transition to ScopeDB (`mobile.frames.nav_transition`). */
    fun reportNavTransitionFrameTrace(
        navKind: String,
        outcome: String,
        fromPage: String,
        toPage: String,
        firstFrameMs: Long,
        durationMs: Long,
        frames: Int,
        jankFrames: Int,
        longestFrameMs: Long,
        p95FrameMs: Long,
        frameIntervalNanos: Long,
        endFrameIntervalNanos: Long,
        deviceModel: String?,
        osVersion: String?,
    ) {
        RaftFrameMetricsTraceReporter.reportNavTransition(
            navKind = navKind,
            outcome = outcome,
            fromPage = fromPage,
            toPage = toPage,
            firstFrameMs = firstFrameMs,
            durationMs = durationMs,
            frames = frames,
            jankFrames = jankFrames,
            longestFrameMs = longestFrameMs,
            p95FrameMs = p95FrameMs,
            frameIntervalNanos = frameIntervalNanos,
            endFrameIntervalNanos = endFrameIntervalNanos,
            deviceModel = deviceModel,
            osVersion = osVersion,
        )
    }

    /** Main thread only. Idempotent. */
    fun install(
        managedImages: RaftIosManagedImageStore?,
        unhandledExceptions: RaftIosUnhandledExceptionSink?,
    ) {
        if (installed) return
        installed = true
        if (unhandledExceptions != null) installUnhandledExceptionHook(unhandledExceptions)
        RaftPageRegistry.install()
        installRaftIosProductEventsLifecycle()
        // One spare WKWebView a moment after launch, for the first in-app web page (nothing waits for it).
        RaftIosWebViewSpare.schedule()
        // iPhone vs iPad in the User-Agent: the server's product-event `$device_type` comes from it.
        installIosRaftUserAgentDevice()
        // The iPad app on a Mac has no touch screen: desktop control metrics from the first page.
        if (raftPlatformIsiOSAppOnMac()) RaftPointerInputState.assumeNoTouchScreen()
        // An attached hardware keyboard (iPad): the "Enter sends" default and the keyboard inset follow it.
        RaftIosHardwareKeyboardState.install()
        // Settings > Appearance > Theme: the app-scope stores are installed (RaftIosAppScopeMMKV)
        // before these hooks, so the stored choice applies before the first page composes.
        RaftAppAppearanceRuntime.onSystemDarkChanged(
            UIScreen.mainScreen.traitCollection.userInterfaceStyle == UIUserInterfaceStyle.UIUserInterfaceStyleDark
        )
        RaftAppAppearanceRuntime.ensureLoaded()
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
                // Clipped reason + compacted frames (head and tail of a long stack): one diagnostics line.
                val record = raftUnhandledExceptionRecord(throwable, throwable::class.qualifiedName.orEmpty())
                sink.recordUnhandledException(reason = record.reason, stack = record.stack)
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
