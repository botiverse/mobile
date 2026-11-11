package build.raft.app

import android.content.Context
import androidx.webkit.WebViewCompat

/**
 * The system WebView implementation pages run in (`WebView.getCurrentWebViewPackage()`): its
 * package and version. A page can render differently in the app than in the phone's browser when
 * the device's WebView is another (older) Chromium than the browser's, e.g. a Huawei
 * `com.huawei.webview` on Chromium 114 (artin's Artifact menu, #triage:#322). Diagnostics only.
 */
object RaftAndroidWebViewProvider {
    @Volatile
    private var logged: Boolean = false

    /** `<package> <version>` of the current WebView implementation, or `unavailable`. */
    fun label(context: Context): String {
        val info = runCatching { WebViewCompat.getCurrentWebViewPackage(context) }.getOrNull()
        return raftWebViewProviderLabel(info?.packageName, info?.versionName)
    }

    /** Logs [label] once per process, when the first WebView is created. */
    internal fun logOnce(context: Context) {
        if (logged) return
        logged = true
        val info = runCatching { WebViewCompat.getCurrentWebViewPackage(context) }.getOrNull()
        raftLogInfo(RAFT_WEBVIEW_DIAGNOSTICS_TAG, raftWebViewProviderLogLine(info?.packageName, info?.versionName))
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
