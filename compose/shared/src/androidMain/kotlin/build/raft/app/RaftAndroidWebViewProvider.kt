package build.raft.app

import android.content.Context
import androidx.webkit.WebViewCompat
import build.raft.app.label
import build.raft.app.thread.label
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

    private class CachedProvider(
        val appContext: Context,
        val info: ProviderInfo,
    )

    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val logged = AtomicBoolean(false)
    private val cachedProvider = AtomicReference<CachedProvider?>(null)

    /** Test seam: loads `(packageName, versionName)` for the current WebView package. */
    @Volatile
    internal var packageLoader: (Context) -> Pair<String?, String?> = ::defaultPackageLoader

    /** Test seam: executes the off-main WebView package lookup for [logOnce]. */
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
        val appContext = context.applicationContext ?: context
        val info = cachedFor(appContext) ?: resolveAndCache(appContext)
        return raftWebViewProviderLabel(info.packageName, info.versionName)
    }

    /** Leading Chromium major version of the current WebView package, or `null` when unknown. */
    fun chromiumMajorVersion(context: Context): Int? {
        val appContext = context.applicationContext ?: context
        val info = cachedFor(appContext) ?: resolveAndCache(appContext)
        return raftWebViewChromiumMajorVersion(info.versionName)
    }

    /** Logs [label] once per process, when the first WebView is created, without blocking main. */
    internal fun logOnce(context: Context) {
        if (!logged.compareAndSet(false, true)) return
        val appContext = context.applicationContext ?: context
        val cached = cachedFor(appContext)
        if (cached != null) {
            infoLogger(raftWebViewProviderLogLine(cached.packageName, cached.versionName))
            return
        }
        backgroundRunner {
            val info = resolveAndCache(appContext)
            infoLogger(raftWebViewProviderLogLine(info.packageName, info.versionName))
        }
    }

    private fun cachedFor(appContext: Context): ProviderInfo? =
        cachedProvider.get()?.takeIf { it.appContext === appContext }?.info

    private fun resolveAndCache(appContext: Context): ProviderInfo {
        val loaded = runCatching { packageLoader(appContext) }.getOrNull()
            ?: return ProviderInfo(packageName = null, versionName = null)
        val resolved = ProviderInfo(packageName = loaded.first, versionName = loaded.second)
        cachedProvider.set(CachedProvider(appContext = appContext, info = resolved))
        return resolved
    }

    private fun defaultPackageLoader(context: Context): Pair<String?, String?> {
        val info = WebViewCompat.getCurrentWebViewPackage(context)
        return info?.packageName to info?.versionName
    }

    internal fun resetForTest() {
        logged.set(false)
        cachedProvider.set(null)
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

/** Extracts the positive leading Chromium major version from a WebView `versionName` (e.g. `"139.0.7258.143"` -> `139`). */
internal fun raftWebViewChromiumMajorVersion(versionName: String?): Int? {
    val trimmed = versionName?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    val majorToken = trimmed.substringBefore('.')
    if (majorToken.isEmpty() || majorToken.any { it !in '0'..'9' }) return null
    return majorToken.toIntOrNull()?.takeIf { it > 0 }
}

private fun String?.raftWebViewProviderField(): String? =
    this?.trim()?.replace(Regex("\\s+"), "_")?.take(80)?.takeIf { it.isNotEmpty() }
