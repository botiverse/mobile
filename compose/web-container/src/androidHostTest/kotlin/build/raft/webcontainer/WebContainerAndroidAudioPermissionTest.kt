package build.raft.webcontainer

import android.app.Activity
import android.content.Context
import android.content.MutableContextWrapper
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.webkit.PermissionRequest
import android.webkit.WebView
import build.raft.webcontainer.jsb.JsbRegistry
import build.raft.webcontainer.pool.WebContainerTelemetryEvent
import build.raft.webcontainer.pool.WebContainerTelemetrySink
import build.raft.webcontainer.resilience.WebContainerWatchdogPolicy
import java.time.Duration
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Before
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
class WebContainerAndroidAudioPermissionTest {
    private val createdHosts = mutableListOf<WebContainerAndroidView>()
    private val raftOrigin = WebContainerOrigin(scheme = "https", host = "app.raft.build")

    private class FakePermissionRequest(
        private val originUri: Uri,
        private val requestedResources: Array<String>,
    ) : PermissionRequest() {
        var deniedCount: Int = 0
        var grantCount: Int = 0
        var grantedResources: Array<String>? = null

        override fun getOrigin(): Uri = originUri

        override fun getResources(): Array<String> = requestedResources

        override fun grant(resources: Array<String>?) {
            grantCount += 1
            grantedResources = resources
        }

        override fun deny() {
            deniedCount += 1
        }
    }

    @Before
    fun setUp() {
        WebContainerAndroidKeepAlive.clearAll()
        WebContainerAndroidAudioPermissionBridge.requester = null
    }

    @After
    fun tearDown() {
        WebContainerAndroidAudioPermissionBridge.requester = null
        for (host in createdHosts) {
            host.destroy()
        }
        createdHosts.clear()
        WebContainerAndroidKeepAlive.clearAll()
    }

    private fun createAttachedHost(
        allowedOrigin: WebContainerOrigin? = raftOrigin,
        telemetrySink: WebContainerTelemetrySink? = null,
    ): Pair<Activity, WebContainerAndroidView> {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        val activity = controller.get()
        val host =
            WebContainerAndroidView(
                context = MutableContextWrapper(activity),
                initialConfig =
                    WebContainerConfig(
                        url = "https://app.raft.build/artifacts/pt_123",
                        surfaceId = "artifact_viewer",
                        allowScripts = true,
                        navigationPolicy =
                            WebNavigationPolicy(
                                allowNavigation = true,
                                allowedSchemes = setOf("http", "https"),
                                lockedMainFrameOrigin = allowedOrigin,
                                audioCaptureAllowedOrigin = allowedOrigin,
                            ),
                    ),
                events = WebContainerEvents(),
                jsbRegistry = JsbRegistry.Empty,
                resourceInterceptor = null,
                telemetrySink = telemetrySink,
                fileChooser = null,
                watchdogPolicy = WebContainerWatchdogPolicy(),
                nowMs = SystemClock::uptimeMillis,
            )
        createdHosts += host
        activity.setContentView(host)
        controller.start().resume().visible()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(34))
        return activity to host
    }

    @Test
    fun resourceAudioCaptureConstantMatchesAndroidSdk() {
        assertEquals(PermissionRequest.RESOURCE_AUDIO_CAPTURE, WEB_PERMISSION_RESOURCE_AUDIO_CAPTURE)
    }

    @Test
    fun allowedOriginAudioOnlyGrantsAfterOsRecordAudioPermissionIsGranted() {
        val observedContexts = mutableListOf<Context>()
        val telemetry = mutableListOf<WebContainerTelemetryEvent.MediaCapturePermission>()
        WebContainerAndroidAudioPermissionBridge.requester = { appContext ->
            observedContexts += appContext
            true
        }

        val (activity, host) =
            createAttachedHost(
                allowedOrigin = raftOrigin,
                telemetrySink =
                    WebContainerTelemetrySink { event ->
                        if (event is WebContainerTelemetryEvent.MediaCapturePermission) {
                            telemetry += event
                        }
                    },
            )
        val webView = host.getChildAt(0) as WebView
        val chromeClient = shadowOf(webView).webChromeClient
        val request =
            FakePermissionRequest(
                originUri = Uri.parse("https://app.raft.build/"),
                requestedResources = arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE),
            )

        chromeClient.onPermissionRequest(request)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, observedContexts.size)
        assertEquals(activity.applicationContext, observedContexts.single())
        assertEquals(1, request.grantCount)
        assertContentEquals(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE), request.grantedResources)
        assertEquals(0, request.deniedCount)
        assertFalse(host.audioPermissionController.hasPendingRequest)
        assertEquals(1, telemetry.size)
        assertTrue(telemetry.single().grantedPrompt)
        assertEquals("https://app.raft.build", telemetry.single().origin)
    }

    @Test
    fun allowedOriginAudioOnlyDeniesWhenOsRecordAudioPermissionIsDeniedOrUninstalled() {
        var requesterCalls = 0
        val (_, host) = createAttachedHost(allowedOrigin = raftOrigin)
        val webView = host.getChildAt(0) as WebView
        val chromeClient = shadowOf(webView).webChromeClient

        // 1) Unset process requester fails closed to deny without granting.
        val unsetRequest =
            FakePermissionRequest(
                originUri = Uri.parse("https://app.raft.build/"),
                requestedResources = arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE),
            )
        chromeClient.onPermissionRequest(unsetRequest)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, unsetRequest.deniedCount)
        assertEquals(0, unsetRequest.grantCount)
        assertNull(unsetRequest.grantedResources)

        // 2) Installed requester returning false (OS denial / no foreground Activity) denies cleanly.
        WebContainerAndroidAudioPermissionBridge.requester = {
            requesterCalls += 1
            false
        }
        val deniedRequest =
            FakePermissionRequest(
                originUri = Uri.parse("https://app.raft.build/"),
                requestedResources = arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE),
            )
        chromeClient.onPermissionRequest(deniedRequest)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, requesterCalls)
        assertEquals(1, deniedRequest.deniedCount)
        assertEquals(0, deniedRequest.grantCount)
        assertNull(deniedRequest.grantedResources)
        assertFalse(host.audioPermissionController.hasPendingRequest)
    }

    @Test
    fun disallowedOriginsVideoAndMixedRequestsDenyImmediatelyWithoutCallingOsRequester() {
        var requesterCalls = 0
        WebContainerAndroidAudioPermissionBridge.requester = {
            requesterCalls += 1
            true
        }

        val (_, host) = createAttachedHost(allowedOrigin = raftOrigin)
        val webView = host.getChildAt(0) as WebView
        val chromeClient = shadowOf(webView).webChromeClient

        val blockedCases =
            listOf(
                FakePermissionRequest(
                    originUri = Uri.parse("https://abc.w.raftusercontent.com/"),
                    requestedResources = arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE),
                ),
                FakePermissionRequest(
                    originUri = Uri.parse("http://app.raft.build/"),
                    requestedResources = arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE),
                ),
                FakePermissionRequest(
                    originUri = Uri.parse("https://app.raft.build:8443/"),
                    requestedResources = arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE),
                ),
                FakePermissionRequest(
                    originUri = Uri.parse("https://app.raft.build.evil.com/"),
                    requestedResources = arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE),
                ),
                FakePermissionRequest(
                    originUri = Uri.parse("https://app.raft.build/"),
                    requestedResources = arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE),
                ),
                FakePermissionRequest(
                    originUri = Uri.parse("https://app.raft.build/"),
                    requestedResources =
                        arrayOf(
                            PermissionRequest.RESOURCE_AUDIO_CAPTURE,
                            PermissionRequest.RESOURCE_VIDEO_CAPTURE,
                        ),
                ),
                FakePermissionRequest(
                    originUri = Uri.parse("https://app.raft.build/"),
                    requestedResources = arrayOf(PermissionRequest.RESOURCE_MIDI_SYSEX),
                ),
                FakePermissionRequest(
                    originUri = Uri.parse("https://app.raft.build/"),
                    requestedResources = emptyArray(),
                ),
            )

        for (request in blockedCases) {
            chromeClient.onPermissionRequest(request)
            assertEquals(1, request.deniedCount)
            assertEquals(0, request.grantCount)
            assertNull(request.grantedResources)
        }
        assertEquals(0, requesterCalls)
        assertFalse(host.audioPermissionController.hasPendingRequest)

        // Default null audioCaptureAllowedOrigin also denies own-origin audio without calling OS requester.
        val (_, defaultHost) = createAttachedHost(allowedOrigin = null)
        val defaultWebView = defaultHost.getChildAt(0) as WebView
        val defaultRequest =
            FakePermissionRequest(
                originUri = Uri.parse("https://app.raft.build/"),
                requestedResources = arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE),
            )
        shadowOf(defaultWebView).webChromeClient.onPermissionRequest(defaultRequest)
        assertEquals(1, defaultRequest.deniedCount)
        assertEquals(0, defaultRequest.grantCount)
        assertEquals(0, requesterCalls)
    }

    @Test
    fun onPermissionRequestCanceledWhileOsPromptPendingNeverCallsGrantOrDeny() {
        val pendingOs = CompletableDeferred<Boolean>()
        WebContainerAndroidAudioPermissionBridge.requester = { pendingOs.await() }

        val (_, host) = createAttachedHost(allowedOrigin = raftOrigin)
        val webView = host.getChildAt(0) as WebView
        val chromeClient = shadowOf(webView).webChromeClient
        val request =
            FakePermissionRequest(
                originUri = Uri.parse("https://app.raft.build/"),
                requestedResources = arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE),
            )

        chromeClient.onPermissionRequest(request)
        assertTrue(host.audioPermissionController.hasPendingRequest)
        assertEquals(0, request.grantCount)
        assertEquals(0, request.deniedCount)

        chromeClient.onPermissionRequestCanceled(request)
        assertFalse(host.audioPermissionController.hasPendingRequest)

        pendingOs.complete(true)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(0, request.grantCount)
        assertEquals(0, request.deniedCount)
        assertNull(request.grantedResources)
    }

    @Test
    fun destroyAndKeepAliveParkWhileOsPromptPendingDenyCleanlyAndNeverGrant() {
        // 1) destroy() while OS prompt is pending.
        val pendingDestroy = CompletableDeferred<Boolean>()
        WebContainerAndroidAudioPermissionBridge.requester = { pendingDestroy.await() }
        val (_, destroyHost) = createAttachedHost(allowedOrigin = raftOrigin)
        val destroyWebView = destroyHost.getChildAt(0) as WebView
        val destroyRequest =
            FakePermissionRequest(
                originUri = Uri.parse("https://app.raft.build/"),
                requestedResources = arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE),
            )

        shadowOf(destroyWebView).webChromeClient.onPermissionRequest(destroyRequest)
        assertTrue(destroyHost.audioPermissionController.hasPendingRequest)

        destroyHost.destroy()
        assertFalse(destroyHost.audioPermissionController.hasPendingRequest)
        assertEquals(1, destroyRequest.deniedCount)
        assertEquals(0, destroyRequest.grantCount)

        pendingDestroy.complete(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, destroyRequest.deniedCount)
        assertEquals(0, destroyRequest.grantCount)
        assertNull(destroyRequest.grantedResources)

        // 2) detachForKeepAlive() while OS prompt is pending.
        val pendingPark = CompletableDeferred<Boolean>()
        WebContainerAndroidAudioPermissionBridge.requester = { pendingPark.await() }
        val (_, parkHost) = createAttachedHost(allowedOrigin = raftOrigin)
        val parkWebView = parkHost.getChildAt(0) as WebView
        val parkRequest =
            FakePermissionRequest(
                originUri = Uri.parse("https://app.raft.build/"),
                requestedResources = arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE),
            )

        shadowOf(parkWebView).webChromeClient.onPermissionRequest(parkRequest)
        assertTrue(parkHost.audioPermissionController.hasPendingRequest)

        parkHost.detachForKeepAlive()
        assertFalse(parkHost.audioPermissionController.hasPendingRequest)
        assertEquals(1, parkRequest.deniedCount)
        assertEquals(0, parkRequest.grantCount)

        pendingPark.complete(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, parkRequest.deniedCount)
        assertEquals(0, parkRequest.grantCount)
        assertNull(parkRequest.grantedResources)
    }

    @Test
    fun backgroundVisibilityOrPolicyRevocationWhileOsPromptPendingDeniesInsteadOfGranting() {
        // 1) Host becomes GONE before OS prompt resolves -> denies instead of granting.
        val pendingBg = CompletableDeferred<Boolean>()
        WebContainerAndroidAudioPermissionBridge.requester = { pendingBg.await() }
        val (_, bgHost) = createAttachedHost(allowedOrigin = raftOrigin)
        val bgWebView = bgHost.getChildAt(0) as WebView
        val bgRequest =
            FakePermissionRequest(
                originUri = Uri.parse("https://app.raft.build/"),
                requestedResources = arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE),
            )

        shadowOf(bgWebView).webChromeClient.onPermissionRequest(bgRequest)
        assertTrue(bgHost.audioPermissionController.hasPendingRequest)
        bgHost.dispatchWindowVisibilityChanged(View.GONE)

        pendingBg.complete(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, bgRequest.deniedCount)
        assertEquals(0, bgRequest.grantCount)
        assertNull(bgRequest.grantedResources)
        assertFalse(bgHost.audioPermissionController.hasPendingRequest)

        // 2) Config drops audioCaptureAllowedOrigin while OS prompt is pending -> denies and never grants.
        val pendingRevoke = CompletableDeferred<Boolean>()
        WebContainerAndroidAudioPermissionBridge.requester = { pendingRevoke.await() }
        val (_, revokeHost) = createAttachedHost(allowedOrigin = raftOrigin)
        val revokeWebView = revokeHost.getChildAt(0) as WebView
        val revokeRequest =
            FakePermissionRequest(
                originUri = Uri.parse("https://app.raft.build/"),
                requestedResources = arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE),
            )

        shadowOf(revokeWebView).webChromeClient.onPermissionRequest(revokeRequest)
        assertTrue(revokeHost.audioPermissionController.hasPendingRequest)

        revokeHost.applyConfig(
            WebContainerConfig(
                url = "https://app.raft.build/artifacts/pt_123",
                surfaceId = "artifact_viewer",
                allowScripts = true,
                navigationPolicy =
                    WebNavigationPolicy(
                        allowNavigation = true,
                        allowedSchemes = setOf("http", "https"),
                        lockedMainFrameOrigin = raftOrigin,
                        audioCaptureAllowedOrigin = null,
                    ),
            ),
        )
        assertFalse(revokeHost.audioPermissionController.hasPendingRequest)
        assertEquals(1, revokeRequest.deniedCount)

        pendingRevoke.complete(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, revokeRequest.deniedCount)
        assertEquals(0, revokeRequest.grantCount)
        assertNull(revokeRequest.grantedResources)
    }

    @Test
    fun asyncOsGrantCompletesOnMainThreadWhenHostStaysActive() {
        val pendingOs = CompletableDeferred<Boolean>()
        WebContainerAndroidAudioPermissionBridge.requester = { pendingOs.await() }

        val (_, host) = createAttachedHost(allowedOrigin = raftOrigin)
        val webView = host.getChildAt(0) as WebView
        val request =
            FakePermissionRequest(
                originUri = Uri.parse("https://app.raft.build/"),
                requestedResources = arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE),
            )

        shadowOf(webView).webChromeClient.onPermissionRequest(request)
        assertTrue(host.audioPermissionController.hasPendingRequest)
        assertEquals(0, request.grantCount)

        pendingOs.complete(true)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(host.audioPermissionController.hasPendingRequest)
        assertEquals(1, request.grantCount)
        assertNotNull(request.grantedResources)
        assertContentEquals(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE), request.grantedResources)
        assertEquals(0, request.deniedCount)
    }
}
