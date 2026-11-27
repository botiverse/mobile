package build.raft.app

import android.app.Activity
import android.content.MutableContextWrapper
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.webkit.PermissionRequest
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.widget.FrameLayout
import build.raft.webcontainer.WebContainerAndroidAudioPermissionBridge
import java.time.Duration
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RaftAndroidSandboxWebViewChromeSupportTest {
    private val createdViews = mutableListOf<RaftAndroidSandboxWebView>()

    @After
    fun tearDown() {
        WebContainerAndroidAudioPermissionBridge.requester = null
        for (view in createdViews) {
            view.destroy()
        }
        createdViews.clear()
    }

    private fun createAttachedView(
        events: RaftWebViewEvents = RaftWebViewEvents(),
        allowNewWindows: Boolean = true,
        url: String = "https://example.com/start",
        mainFrameOrigin: String? = null,
        audioCaptureAllowedOrigin: String? = null,
        storagePartition: RaftWebViewStoragePartition? = null,
    ): Pair<Activity, RaftAndroidSandboxWebView> {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        val activity = controller.get()
        val host =
            RaftAndroidSandboxWebView(
                MutableContextWrapper(activity),
                events,
                initialStoragePartition = storagePartition ?: RaftWebViewStoragePartition.Default,
            )
        createdViews += host
        activity.setContentView(host)
        controller.start().resume().visible()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(34))
        host.applyConfig(
            RaftWebViewConfig(
                url = url,
                allowScripts = true,
                allowNavigation = true,
                domStorageEnabled = true,
                useWideViewPort = false,
                cacheEnabled = true,
                ephemeral = false,
                injectedJavaScript = "",
                allowedNavigationSchemes = setOf("http", "https"),
                hostEventsEnabled = false,
                mainFrameOrigin = mainFrameOrigin,
                storagePartition = storagePartition,
                allowNewWindows = allowNewWindows,
                audioCaptureAllowedOrigin = audioCaptureAllowedOrigin,
            ),
        )
        return activity to host
    }

    private fun fakeRequest(url: String): WebResourceRequest =
        object : WebResourceRequest {
            override fun getUrl(): Uri = Uri.parse(url)

            override fun isForMainFrame(): Boolean = true

            override fun isRedirect(): Boolean = false

            override fun hasGesture(): Boolean = true

            override fun getMethod(): String = "GET"

            override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
        }

    @Test
    fun legacySandboxFullscreenPermissionDenyAndWindowOpenWorkCleanly() {
        val blocked = mutableListOf<String>()
        val caught = mutableListOf<String>()
        val events =
            RaftWebViewEvents().apply {
                onNavigationBlocked = { blocked += it }
                catchesMainFrameNavigation = { ask ->
                    if (ask.url.startsWith("https://raft.example.com/oauth/authorize")) {
                        caught += ask.url
                        true
                    } else {
                        false
                    }
                }
            }
        val (activity, host) = createAttachedView(events = events, allowNewWindows = true)
        val webView = host.getChildAt(0) as WebView
        val chromeClient = shadowOf(webView).webChromeClient

        // 1) Fullscreen custom view shows and exits on visibility GONE, settling callback once.
        var hiddenCount = 0
        chromeClient.onShowCustomView(FrameLayout(activity)) { hiddenCount += 1 }
        assertTrue(host.fullscreenController.isFullscreen)
        host.dispatchWindowVisibilityChanged(View.GONE)
        assertFalse(host.fullscreenController.isFullscreen)
        assertEquals(1, hiddenCount)
        host.dispatchWindowVisibilityChanged(View.VISIBLE)

        // 2) Web media permission request is explicitly denied.
        var denied = 0
        var granted: Array<String>? = null
        chromeClient.onPermissionRequest(
            object : PermissionRequest() {
                override fun getOrigin(): Uri = Uri.parse("https://example.com")

                override fun getResources(): Array<String> =
                    arrayOf(RESOURCE_VIDEO_CAPTURE, RESOURCE_AUDIO_CAPTURE)

                override fun grant(resources: Array<String>?) {
                    granted = resources
                }

                override fun deny() {
                    denied += 1
                }
            },
        )
        assertEquals(1, denied)
        assertNull(granted)

        // 3) Multiple windows enabled when allowNewWindows = true; auto popups remain off.
        assertTrue(webView.settings.supportMultipleWindows())
        assertFalse(webView.settings.javaScriptCanOpenWindowsAutomatically)

        var dispatchedTransport: WebView.WebViewTransport? = null
        val handler =
            Handler(Looper.getMainLooper()) { msg ->
                dispatchedTransport = msg.obj as? WebView.WebViewTransport
                true
            }

        // User-gesture popup allowed URL loads in parent WebView.
        val msgAllowed = Message.obtain(handler).apply { obj = webView.WebViewTransport() }
        assertTrue(chromeClient.onCreateWindow(webView, false, true, msgAllowed))
        shadowOf(Looper.getMainLooper()).idle()
        val popupAllowed = assertNotNull(dispatchedTransport?.webView)
        assertTrue(shadowOf(popupAllowed).webViewClient.shouldOverrideUrlLoading(popupAllowed, fakeRequest("https://example.com/popup")))
        assertEquals("https://example.com/popup", shadowOf(webView).lastLoadedUrl)

        // User-gesture popup matching catchesMainFrameNavigation is intercepted by host.
        val msgCaught = Message.obtain(handler).apply { obj = webView.WebViewTransport() }
        assertTrue(chromeClient.onCreateWindow(webView, false, true, msgCaught))
        shadowOf(Looper.getMainLooper()).idle()
        val popupCaught = assertNotNull(dispatchedTransport?.webView)
        assertTrue(
            shadowOf(popupCaught).webViewClient.shouldOverrideUrlLoading(
                popupCaught,
                fakeRequest("https://raft.example.com/oauth/authorize?state=1"),
            ),
        )
        assertEquals(listOf("https://raft.example.com/oauth/authorize?state=1"), caught)

        // Disallowed scheme routes to onNavigationBlocked and never loads in parent.
        val msgBlocked = Message.obtain(handler).apply { obj = webView.WebViewTransport() }
        assertTrue(chromeClient.onCreateWindow(webView, false, true, msgBlocked))
        shadowOf(Looper.getMainLooper()).idle()
        val popupBlocked = assertNotNull(dispatchedTransport?.webView)
        assertTrue(shadowOf(popupBlocked).webViewClient.shouldOverrideUrlLoading(popupBlocked, fakeRequest("mailto:test@example.com")))
        assertEquals(listOf("mailto:test@example.com"), blocked)
    }

    @Test
    fun legacySandboxAutoGrantsAudioOnlyForMatchingRaftOriginAfterOsPermissionAndFailsClosedOtherwise() {
        var requesterCalls = 0
        WebContainerAndroidAudioPermissionBridge.requester = {
            requesterCalls += 1
            true
        }
        val (_, raftHost) =
            createAttachedView(
                allowNewWindows = false,
                url = "https://app.raft.build/artifacts/pt_1",
                mainFrameOrigin = "https://app.raft.build",
                audioCaptureAllowedOrigin = "https://app.raft.build",
            )
        val raftWebView = raftHost.getChildAt(0) as WebView
        val raftChromeClient = shadowOf(raftWebView).webChromeClient

        // 1) Matching origin + audio-only + OS permission granted -> grants RESOURCE_AUDIO_CAPTURE.
        var grantedAudio: Array<String>? = null
        var deniedAudio = 0
        raftChromeClient.onPermissionRequest(
            object : PermissionRequest() {
                override fun getOrigin(): Uri = Uri.parse("https://app.raft.build/")
                override fun getResources(): Array<String> = arrayOf(RESOURCE_AUDIO_CAPTURE)
                override fun grant(resources: Array<String>?) {
                    grantedAudio = resources
                }
                override fun deny() {
                    deniedAudio += 1
                }
            },
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, requesterCalls)
        assertContentEquals(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE), grantedAudio)
        assertEquals(0, deniedAudio)

        // 2) Sandboxed content iframe origin -> denied immediately without calling OS requester.
        var deniedIframe = 0
        var grantedIframe: Array<String>? = null
        raftChromeClient.onPermissionRequest(
            object : PermissionRequest() {
                override fun getOrigin(): Uri = Uri.parse("https://abc.w.raftusercontent.com/")
                override fun getResources(): Array<String> = arrayOf(RESOURCE_AUDIO_CAPTURE)
                override fun grant(resources: Array<String>?) {
                    grantedIframe = resources
                }
                override fun deny() {
                    deniedIframe += 1
                }
            },
        )
        assertEquals(1, deniedIframe)
        assertNull(grantedIframe)
        assertEquals(1, requesterCalls)

        // 3) Mixed audio + video on Raft origin -> denied immediately without calling OS requester.
        var deniedMixed = 0
        var grantedMixed: Array<String>? = null
        raftChromeClient.onPermissionRequest(
            object : PermissionRequest() {
                override fun getOrigin(): Uri = Uri.parse("https://app.raft.build/")
                override fun getResources(): Array<String> = arrayOf(RESOURCE_AUDIO_CAPTURE, RESOURCE_VIDEO_CAPTURE)
                override fun grant(resources: Array<String>?) {
                    grantedMixed = resources
                }
                override fun deny() {
                    deniedMixed += 1
                }
            },
        )
        assertEquals(1, deniedMixed)
        assertNull(grantedMixed)
        assertEquals(1, requesterCalls)

        // 4) Cancel while OS prompt is pending -> never grants or denies.
        val pendingCancel = CompletableDeferred<Boolean>()
        WebContainerAndroidAudioPermissionBridge.requester = { pendingCancel.await() }
        var canceledGranted = 0
        var canceledDenied = 0
        val cancelableRequest =
            object : PermissionRequest() {
                override fun getOrigin(): Uri = Uri.parse("https://app.raft.build/")
                override fun getResources(): Array<String> = arrayOf(RESOURCE_AUDIO_CAPTURE)
                override fun grant(resources: Array<String>?) {
                    canceledGranted += 1
                }
                override fun deny() {
                    canceledDenied += 1
                }
            }
        raftChromeClient.onPermissionRequest(cancelableRequest)
        assertTrue(raftHost.audioPermissionController.hasPendingRequest)
        raftChromeClient.onPermissionRequestCanceled(cancelableRequest)
        assertFalse(raftHost.audioPermissionController.hasPendingRequest)
        pendingCancel.complete(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, canceledGranted)
        assertEquals(0, canceledDenied)

        // 5) Unlocked surface (mainFrameOrigin = null) fails closed even if audioCaptureAllowedOrigin was set.
        var unlockedRequesterCalls = 0
        WebContainerAndroidAudioPermissionBridge.requester = {
            unlockedRequesterCalls += 1
            true
        }
        val (_, unlockedHost) =
            createAttachedView(
                allowNewWindows = false,
                url = "https://app.raft.build/settings/billing",
                mainFrameOrigin = null,
                audioCaptureAllowedOrigin = "https://app.raft.build",
            )
        var unlockedDenied = 0
        var unlockedGranted = 0
        shadowOf(unlockedHost.getChildAt(0) as WebView).webChromeClient.onPermissionRequest(
            object : PermissionRequest() {
                override fun getOrigin(): Uri = Uri.parse("https://app.raft.build/")
                override fun getResources(): Array<String> = arrayOf(RESOURCE_AUDIO_CAPTURE)
                override fun grant(resources: Array<String>?) {
                    unlockedGranted += 1
                }
                override fun deny() {
                    unlockedDenied += 1
                }
            },
        )
        assertEquals(1, unlockedDenied)
        assertEquals(0, unlockedGranted)
        assertEquals(0, unlockedRequesterCalls)
    }
}
