@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package build.raft.app

import androidx.compose.runtime.Composable
import kotlin.time.TimeSource
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSTimer
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidReceiveMemoryWarningNotification
import platform.UIKit.UIApplicationState
import platform.WebKit.WKWebView

// No document keep-alive here yet (the Artifact viewer's parked WebView is Android only): every
// page creates and destroys its own WebView, taking the spare one (RaftIosWebViewSpare) when it can.
internal actual fun raftWebViewKeptDocument(key: String): RaftWebViewKeptDocument? = null

internal actual fun raftWebViewEvaluateInKept(key: String, origin: String, script: String): Boolean = false

internal actual fun raftWebViewKeepAliveClear() = Unit

@Composable
internal actual fun RaftWebViewPrewarmEffect(config: RaftWebViewConfig?) = Unit

/**
 * One spare WKWebView, made ahead of the first web page (Plans & Billing, Changelog, the in-app
 * browser, the Artifact viewer, ...), so that page does not pay WebKit's start-up when it opens:
 * the web content and networking processes, the default data store. iOS's counterpart of Android's
 * `RaftAndroidWebViewWarmup` (which only warms the provider); here the page takes the warm view
 * itself ([take]), when its configuration is the spare's ([raftIosWebViewConfiguration] with the
 * persistent store and scripts on). It carries no page script, bridge or session: the page adds its
 * own to the view's user content controller before it loads anything.
 *
 * At most one spare exists. It is made once the app is active and settled ([schedule], after
 * [WARM_DELAY_SECONDS]), made again a while after a page takes it ([REFILL_DELAY_SECONDS]), and
 * dropped on a memory warning (made again on the next take). Main thread only.
 */
internal object RaftIosWebViewSpare {
    /** The spare's own document (an empty one: it only starts WebKit). */
    const val BLANK_URL = "about:blank"
    private const val WARM_DELAY_SECONDS = 2.0
    private const val REFILL_DELAY_SECONDS = 5.0
    private const val LOG_TAG = "SlockWebViewWarmup"

    private var spare: WKWebView? = null
    private var timer: NSTimer? = null
    private var installed = false

    /** Makes the spare shortly after launch, once the app is active. Idempotent. */
    fun schedule() {
        if (installed) return
        installed = true
        val center = NSNotificationCenter.defaultCenter
        center.addObserverForName(UIApplicationDidReceiveMemoryWarningNotification, null, NSOperationQueue.mainQueue) { _ ->
            drop("memory_warning")
        }
        center.addObserverForName(UIApplicationDidBecomeActiveNotification, null, NSOperationQueue.mainQueue) { _ ->
            refillLater(WARM_DELAY_SECONDS)
        }
        refillLater(WARM_DELAY_SECONDS)
    }

    /** The spare (now the caller's), or null; a new one is made a while later. */
    fun take(): WKWebView? {
        val view = spare ?: return null
        spare = null
        raftLogInfo(LOG_TAG, "webview_spare result=taken")
        if (installed) refillLater(REFILL_DELAY_SECONDS)
        return view
    }

    private fun refillLater(delaySeconds: Double) {
        if (spare != null || timer != null) return
        timer =
            NSTimer.scheduledTimerWithTimeInterval(delaySeconds, repeats = false) { _ ->
                timer = null
                fill()
            }
    }

    private fun fill() {
        if (spare != null) return
        // A background launch (a push, a background fetch) makes nothing; the next activation does.
        if (UIApplication.sharedApplication.applicationState != UIApplicationState.UIApplicationStateActive) return
        val started = TimeSource.Monotonic.markNow()
        runCatching {
            val view =
                WKWebView(
                    frame = CGRectMake(0.0, 0.0, 0.0, 0.0),
                    configuration = raftIosWebViewConfiguration(ephemeral = false, allowScripts = true),
                )
            // An empty document starts the web content process; the page's load replaces it, and
            // the host does not count it as history (RaftIosSandboxWebView.canGoBack).
            view.loadHTMLString("", baseURL = null)
            spare = view
        }.onSuccess {
            raftLogInfo(LOG_TAG, "webview_spare result=created elapsedMs=${started.elapsedNow().inWholeMilliseconds}")
        }.onFailure { error ->
            raftLogWarn(LOG_TAG, "webview_spare result=failed errorType=${error::class.simpleName ?: "unknown"}")
        }
    }

    private fun drop(reason: String) {
        timer?.invalidate()
        timer = null
        val view = spare ?: return
        spare = null
        view.stopLoading()
        raftLogInfo(LOG_TAG, "webview_spare result=dropped reason=$reason")
    }
}
