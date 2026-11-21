package build.raft.app

import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RaftAndroidWebViewProviderCacheTest {
    private val queuedTasks = mutableListOf<() -> Unit>()
    private val loggedLines = mutableListOf<String>()

    @Before
    fun setUp() {
        RaftAndroidWebViewProvider.resetForTest()
        queuedTasks.clear()
        loggedLines.clear()
        RaftAndroidWebViewProvider.backgroundRunner = { task -> queuedTasks += task }
        RaftAndroidWebViewProvider.infoLogger = { line -> loggedLines += line }
    }

    @After
    fun tearDown() {
        RaftAndroidWebViewProvider.resetForTest()
    }

    @Test
    fun logOnceRunsOffMainPopulatesCacheAndLogsOnce() {
        val app = RuntimeEnvironment.getApplication()
        var loaderCalls = 0
        RaftAndroidWebViewProvider.packageLoader = {
            loaderCalls += 1
            "com.google.android.webview" to "131.0.6778.39"
        }

        RaftAndroidWebViewProvider.logOnce(app)
        RaftAndroidWebViewProvider.logOnce(app)
        // First WebView construction must not synchronously call WebViewCompat.getCurrentWebViewPackage.
        assertEquals(0, loaderCalls)
        assertEquals(1, queuedTasks.size)
        assertEquals(emptyList<String>(), loggedLines)

        queuedTasks.removeAt(0).invoke()
        assertEquals(1, loaderCalls)
        assertEquals(
            listOf("webview_provider package=com.google.android.webview version=131.0.6778.39"),
            loggedLines,
        )

        // Subsequent label reads hit the in-memory cache with zero extra IPC.
        assertEquals("com.google.android.webview 131.0.6778.39", RaftAndroidWebViewProvider.label(app))
        assertEquals("com.google.android.webview 131.0.6778.39", RaftAndroidWebViewProvider.label(app))
        assertEquals(1, loaderCalls)
    }

    @Test
    fun labelCachesResultAndLogOnceUsesCacheWithoutBackgroundProbe() {
        val app = RuntimeEnvironment.getApplication()
        var loaderCalls = 0
        RaftAndroidWebViewProvider.packageLoader = {
            loaderCalls += 1
            "com.huawei.webview" to "114.0.5735.196"
        }

        assertEquals("com.huawei.webview 114.0.5735.196", RaftAndroidWebViewProvider.label(app))
        assertEquals("com.huawei.webview 114.0.5735.196", RaftAndroidWebViewProvider.label(app))
        assertEquals(1, loaderCalls)

        RaftAndroidWebViewProvider.logOnce(app)
        assertEquals(0, queuedTasks.size)
        assertEquals(1, loaderCalls)
        assertEquals(
            listOf("webview_provider package=com.huawei.webview version=114.0.5735.196"),
            loggedLines,
        )
    }

    @Test
    fun prewarmPopulatesCacheAsynchronously() {
        val app = RuntimeEnvironment.getApplication()
        var loaderCalls = 0
        RaftAndroidWebViewProvider.packageLoader = {
            loaderCalls += 1
            "com.android.webview" to "128.0"
        }

        RaftAndroidWebViewProvider.prewarm(app)
        assertEquals(0, loaderCalls)
        assertEquals(1, queuedTasks.size)

        queuedTasks.removeAt(0).invoke()
        assertEquals(1, loaderCalls)
        assertEquals("com.android.webview 128.0", RaftAndroidWebViewProvider.label(app))
        assertEquals(1, loaderCalls)

        // Already cached: another prewarm is a no-op.
        RaftAndroidWebViewProvider.prewarm(app)
        assertEquals(0, queuedTasks.size)
    }
}
