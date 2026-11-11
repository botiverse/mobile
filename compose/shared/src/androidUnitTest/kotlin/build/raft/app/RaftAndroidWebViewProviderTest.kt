package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

/** #triage:#322: diagnostics name the system WebView (package and version) pages run in. */
class RaftAndroidWebViewProviderTest {
    @Test
    fun labelNamesPackageAndVersion() {
        assertEquals("com.huawei.webview 114.0.5735.196", raftWebViewProviderLabel("com.huawei.webview", "114.0.5735.196"))
        assertEquals("com.google.android.webview unknown", raftWebViewProviderLabel("com.google.android.webview", null))
        assertEquals("unavailable", raftWebViewProviderLabel(null, "131.0.6778.39"))
        assertEquals("unavailable", raftWebViewProviderLabel(" ", null))
    }

    @Test
    fun logLineStaysOneBoundedLine() {
        assertEquals(
            "webview_provider package=com.huawei.webview version=114.0.5735.196",
            raftWebViewProviderLogLine("com.huawei.webview", "114.0.5735.196"),
        )
        val line = raftWebViewProviderLogLine("pkg\nname", "1".repeat(200))
        assertEquals("webview_provider package=pkg_name version=${"1".repeat(80)}", line)
        assertEquals("webview_provider package=unavailable version=unknown", raftWebViewProviderLogLine(null, ""))
    }
}
