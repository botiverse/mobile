package build.raft.app

import android.content.Context
import androidx.webkit.WebViewCompat
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The system WebView implementation pages run in (`WebView.getCurrentWebViewPackage()`): its
 * package and version. A page can render differently in the app than in the phone's browser when
 * the device's WebView is another (older) Chromium than the browser's, e.g. a Huawei
 * `com.huawei.webview` on Chromium 114 (artin's Artifact menu, #triage:#322). Diagnostics only.
 */
object RaftAndroidWebViewProvider {
    private data class ProviderInfo(
        val packageName: String?,
        val versionName: String?,
    )

    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val logged = AtomicBoolean(false)
    private val cachedInfo = AtomicReference<ProviderInfo?>(null)

    /** Test seam: loads `(packageName, versionName)` for the current WebView package. */
    @Volatile
    internal var packageLoader: (Context) -> Pair<String?, String?> = ::defaultPackageLoader

    /** Test seam: executes the off-main WebView package lookup for [logOnce] and [prewarm]. */
    @Volatile
    internal var backgroundRunner: (task: () -> Unit) -> Unit = { task ->
        backgroundScope.launch { runCatching(task) }
    }

    /** Test seam: records the once-per-process provider log line. */
    @Volatile
    internal var infoLogger: (String) -> Unit = { message ->
        raftLogInfo(RAFT_WEBVIEW_DIAGNOSTICS_TAG, message)
    }

    /** `<package> <version>` of the current WebView implementation, or `unavailable`. */
    fun label(context: Context): String {
        val info = cachedInfo.get() ?: resolveAndCache(context)
        return raftWebViewProviderLabel(info.packageName, info.versionName)
    }

    /** Populates the in-memory WebView package cache off the calling thread if not already cached. */
    fun prewarm(context: Context) {
        if (cachedInfo.get() != null) return
        val appContext = context.applicationContext ?: context
        backgroundRunner { resolveAndCache(appContext) }
    }

    /** Logs [label] once per process, when the first WebView is created, without blocking main. */
    internal fun logOnce(context: Context) {
        if (!logged.compareAndSet(false, true)) return
        val cached = cachedInfo.get()
        if (cached != null) {
            infoLogger(raftWebViewProviderLogLine(cached.packageName, cached.versionName))
            return
        }
        val appContext = context.applicationContext ?: context
        backgroundRunner {
            val info = resolveAndCache(appContext)
            infoLogger(raftWebViewProviderLogLine(info.packageName, info.versionName))
        }
    }

    private fun resolveAndCache(context: Context): ProviderInfo {
        val appContext = context.applicationContext ?: context
        val (packageName, versionName) = runCatching { packageLoader(appContext) }.getOrDefault(null to null)
        val resolved = ProviderInfo(packageName = packageName, versionName = versionName)
        cachedInfo.compareAndSet(null, resolved)
        return cachedInfo.get() ?: resolved
    }

    private fun defaultPackageLoader(context: Context): Pair<String?, String?> {
        val info = WebViewCompat.getCurrentWebViewPackage(context)
        return info?.packageName to info?.versionName
    }

    internal fun resetForTest() {
        logged.set(false)
        cachedInfo.set(null)
        packageLoader = ::defaultPackageLoader
        backgroundRunner = { task -> backgroundScope.launch { runCatching(task) } }
        infoLogger = { message -> raftLogInfo(RAFT_WEBVIEW_DIAGNOSTICS_TAG, message) }
    }
}

/** [RaftAndroidWebViewProvider.label]'s text: one line, bounded, `unavailable` without a package. */
internal fun raftWebViewProviderLabel(packageName: String?, versionName: String?): String {
    val pkg = packageName.raftWebViewProviderField() ?: return "unavailable"
    val version = versionName.raftWebViewProviderField() ?: "unknown"
    return "$pkg $version"
}

/** The once-per-process info line naming the WebView implementation. */
internal fun raftWebViewProviderLogLine(packageName: String?, versionName: String?): String =
    "webview_provider package=${packageName.raftWebViewProviderField() ?: "unavailable"} " +
        "version=${versionName.raftWebViewProviderField() ?: "unknown"}"

private fun String?.raftWebViewProviderField(): String? =
    this?.trim()?.replace(Regex("\\s+"), "_")?.take(80)?.takeIf { it.isNotEmpty() }
