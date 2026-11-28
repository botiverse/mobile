@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package build.raft.webcontainer

import build.raft.webcontainer.jsb.JsbRegistry
import build.raft.webcontainer.pool.WebContainerTelemetryEvent
import build.raft.webcontainer.pool.WebContainerTelemetrySink
import platform.CoreGraphics.CGRectMake
import platform.WebKit.WKMediaCaptureType
import platform.WebKit.WKPermissionDecision
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebContainerIosAudioPermissionTest {
    private val raftOrigin = WebContainerOrigin(scheme = "https", host = "app.raft.build")
    private val createdHosts = mutableListOf<WebContainerIosHost>()

    @AfterTest
    fun tearDown() {
        createdHosts.forEach { it.dispose() }
        createdHosts.clear()
    }

    private fun createAttachedHost(
        allowedOrigin: WebContainerOrigin? = raftOrigin,
        telemetry: MutableList<WebContainerTelemetryEvent.MediaCapturePermission>? = null,
    ): Pair<WebContainerIosHost, WKWebView> {
        val sink =
            telemetry?.let { sinkList ->
                WebContainerTelemetrySink { event ->
                    if (event is WebContainerTelemetryEvent.MediaCapturePermission) {
                        sinkList += event
                    }
                }
            }
        val container =
            WebContainerIosContainerView(
                events = WebContainerEvents(),
                jsbRegistry = JsbRegistry.Empty,
                resourceInterceptor = null,
                telemetrySink = sink,
            )
        val host = container.host
        createdHosts += host
        host.hasMicrophoneUsageDescription = { true }
        host.microphoneAuthorizationStatus = { WebOsMicrophonePermissionStatus.Authorized }
        host.requestMicrophoneAccess = { onResult -> onResult(false) }
        host.applyConfig(
            WebContainerConfig(
                url = "https://app.raft.build/artifacts/pt_123",
                surfaceId = "artifact_viewer",
                isolationScope = WebIsolationScope.Ephemeral,
                allowScripts = true,
                navigationPolicy =
                    WebNavigationPolicy(
                        allowNavigation = true,
                        allowedSchemes = setOf("http", "https"),
                        lockedMainFrameOrigin = allowedOrigin,
                        audioCaptureAllowedOrigin = allowedOrigin,
                    ),
            ),
        )
        host.onWindowAttachmentChanged(attached = true)
        val webView = container.subviews.single() as WKWebView
        return host to webView
    }

    @Test
    fun grantsOwnOriginMainFrameMicrophoneWhenOsPermissionAuthorizedAndDeniesDisallowedCases() {
        val telemetry = mutableListOf<WebContainerTelemetryEvent.MediaCapturePermission>()
        val (host, webView) = createAttachedHost(allowedOrigin = raftOrigin, telemetry = telemetry)
        var promptCalls = 0
        host.requestMicrophoneAccess = { promptCalls += 1 }

        val grantedDecisions = mutableListOf<WKPermissionDecision>()
        host.decideMediaCapturePermission(
            source = webView,
            originLabel = "https://app.raft.build",
            isMainFrame = true,
            type = WKMediaCaptureType.WKMediaCaptureTypeMicrophone,
        ) { grantedDecisions += it }

        assertEquals(listOf(WKPermissionDecision.WKPermissionDecisionGrant), grantedDecisions)
        assertEquals(0, promptCalls)
        assertEquals(1, telemetry.size)
        assertTrue(telemetry.single().grantedPrompt)
        assertNull(telemetry.single().reason)
        assertEquals("https://app.raft.build", telemetry.single().origin)
        assertTrue(telemetry.single().isMainFrame)

        // Camera, mixed camera+mic, subframe, wrong origin, wrong port, wrong scheme, and mismatched frameOrigin all deny.
        val blockedCases =
            listOf(
                Triple("https://app.raft.build", true, WKMediaCaptureType.WKMediaCaptureTypeCamera),
                Triple("https://app.raft.build", true, WKMediaCaptureType.WKMediaCaptureTypeCameraAndMicrophone),
                Triple("https://app.raft.build", false, WKMediaCaptureType.WKMediaCaptureTypeMicrophone),
                Triple("https://abc.w.raftusercontent.com", true, WKMediaCaptureType.WKMediaCaptureTypeMicrophone),
                Triple("http://app.raft.build", true, WKMediaCaptureType.WKMediaCaptureTypeMicrophone),
                Triple("https://app.raft.build:8443", true, WKMediaCaptureType.WKMediaCaptureTypeMicrophone),
            )
        for ((origin, isMainFrame, type) in blockedCases) {
            val out = mutableListOf<WKPermissionDecision>()
            host.decideMediaCapturePermission(
                source = webView,
                originLabel = origin,
                isMainFrame = isMainFrame,
                type = type,
            ) { out += it }
            assertEquals(listOf(WKPermissionDecision.WKPermissionDecisionDeny), out, "$type $origin mainFrame=$isMainFrame")
        }

        // Mismatched frame security origin denies even when request origin matches.
        val mismatchedFrameOut = mutableListOf<WKPermissionDecision>()
        host.decideMediaCapturePermission(
            source = webView,
            originLabel = "https://app.raft.build",
            isMainFrame = true,
            type = WKMediaCaptureType.WKMediaCaptureTypeMicrophone,
            frameOriginLabel = "https://abc.w.raftusercontent.com",
        ) { mismatchedFrameOut += it }
        assertEquals(listOf(WKPermissionDecision.WKPermissionDecisionDeny), mismatchedFrameOut)

        // Stale WebView denies immediately without invoking OS APIs.
        val stranger = WKWebView(frame = CGRectMake(0.0, 0.0, 1.0, 1.0), configuration = WKWebViewConfiguration())
        val strangerOut = mutableListOf<WKPermissionDecision>()
        host.decideMediaCapturePermission(
            source = stranger,
            originLabel = "https://app.raft.build",
            isMainFrame = true,
            type = WKMediaCaptureType.WKMediaCaptureTypeMicrophone,
        ) { strangerOut += it }
        assertEquals(listOf(WKPermissionDecision.WKPermissionDecisionDeny), strangerOut)
        assertEquals(0, promptCalls)
    }

    @Test
    fun deniesWhenOsPermissionDeniedRestrictedMissingUsageOrAllowedOriginNull() {
        val telemetry = mutableListOf<WebContainerTelemetryEvent.MediaCapturePermission>()
        val (host, webView) = createAttachedHost(allowedOrigin = raftOrigin, telemetry = telemetry)

        host.microphoneAuthorizationStatus = { WebOsMicrophonePermissionStatus.Denied }
        val deniedOut = mutableListOf<WKPermissionDecision>()
        host.decideMediaCapturePermission(
            source = webView,
            originLabel = "https://app.raft.build",
            isMainFrame = true,
            type = WKMediaCaptureType.WKMediaCaptureTypeMicrophone,
        ) { deniedOut += it }
        assertEquals(listOf(WKPermissionDecision.WKPermissionDecisionDeny), deniedOut)
        assertEquals("os_permission_denied", telemetry.last().reason)

        host.microphoneAuthorizationStatus = { WebOsMicrophonePermissionStatus.Restricted }
        val restrictedOut = mutableListOf<WKPermissionDecision>()
        host.decideMediaCapturePermission(
            source = webView,
            originLabel = "https://app.raft.build",
            isMainFrame = true,
            type = WKMediaCaptureType.WKMediaCaptureTypeMicrophone,
        ) { restrictedOut += it }
        assertEquals(listOf(WKPermissionDecision.WKPermissionDecisionDeny), restrictedOut)
        assertEquals("os_permission_restricted", telemetry.last().reason)

        host.microphoneAuthorizationStatus = { WebOsMicrophonePermissionStatus.Authorized }
        host.hasMicrophoneUsageDescription = { false }
        val missingUsageOut = mutableListOf<WKPermissionDecision>()
        host.decideMediaCapturePermission(
            source = webView,
            originLabel = "https://app.raft.build",
            isMainFrame = true,
            type = WKMediaCaptureType.WKMediaCaptureTypeMicrophone,
        ) { missingUsageOut += it }
        assertEquals(listOf(WKPermissionDecision.WKPermissionDecisionDeny), missingUsageOut)
        assertEquals("missing_usage_description", telemetry.last().reason)
        assertEquals(listOf(IOS_MICROPHONE_USAGE_DESCRIPTION_KEY), telemetry.last().missingKeys)

        val (defaultHost, defaultWebView) = createAttachedHost(allowedOrigin = null)
        val defaultOut = mutableListOf<WKPermissionDecision>()
        defaultHost.decideMediaCapturePermission(
            source = defaultWebView,
            originLabel = "https://app.raft.build",
            isMainFrame = true,
            type = WKMediaCaptureType.WKMediaCaptureTypeMicrophone,
        ) { defaultOut += it }
        assertEquals(listOf(WKPermissionDecision.WKPermissionDecisionDeny), defaultOut)
    }

    @Test
    fun notDeterminedPromptsOsOnceAndCancelsCleanlyOnLifecycleOrPolicyChanges() {
        val (host, webView) = createAttachedHost(allowedOrigin = raftOrigin)
        var pendingCallback: ((Boolean) -> Unit)? = null
        host.microphoneAuthorizationStatus = { WebOsMicrophonePermissionStatus.NotDetermined }
        host.requestMicrophoneAccess = { cb -> pendingCallback = cb }

        // 1) Async grant succeeds cleanly.
        val grantOut = mutableListOf<WKPermissionDecision>()
        host.decideMediaCapturePermission(
            source = webView,
            originLabel = "https://app.raft.build",
            isMainFrame = true,
            type = WKMediaCaptureType.WKMediaCaptureTypeMicrophone,
        ) { grantOut += it }
        assertTrue(host.audioPermissionController.hasPendingRequest)
        checkNotNull(pendingCallback).invoke(true)
        assertEquals(listOf(WKPermissionDecision.WKPermissionDecisionGrant), grantOut)
        assertFalse(host.audioPermissionController.hasPendingRequest)

        // 2) Window detach cancels pending request with Deny once; late OS grant is ignored.
        val detachOut = mutableListOf<WKPermissionDecision>()
        host.decideMediaCapturePermission(
            source = webView,
            originLabel = "https://app.raft.build",
            isMainFrame = true,
            type = WKMediaCaptureType.WKMediaCaptureTypeMicrophone,
        ) { detachOut += it }
        val detachedCb = checkNotNull(pendingCallback)
        host.onWindowAttachmentChanged(attached = false)
        assertEquals(listOf(WKPermissionDecision.WKPermissionDecisionDeny), detachOut)
        assertFalse(host.audioPermissionController.hasPendingRequest)
        detachedCb(true)
        assertEquals(listOf(WKPermissionDecision.WKPermissionDecisionDeny), detachOut)

        // 3) Policy change via applyConfig revoking audioCaptureAllowedOrigin cancels pending request cleanly.
        host.onWindowAttachmentChanged(attached = true)
        val policyOut = mutableListOf<WKPermissionDecision>()
        host.decideMediaCapturePermission(
            source = webView,
            originLabel = "https://app.raft.build",
            isMainFrame = true,
            type = WKMediaCaptureType.WKMediaCaptureTypeMicrophone,
        ) { policyOut += it }
        val policyCb = checkNotNull(pendingCallback)
        host.applyConfig(
            WebContainerConfig(
                url = "https://app.raft.build/artifacts/pt_123",
                surfaceId = "artifact_viewer",
                isolationScope = WebIsolationScope.Ephemeral,
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
        assertEquals(listOf(WKPermissionDecision.WKPermissionDecisionDeny), policyOut)
        policyCb(true)
        assertEquals(listOf(WKPermissionDecision.WKPermissionDecisionDeny), policyOut)

        // 4) dispose() cancels pending request once and never leaks decisionHandler.
        val (disposeHost, disposeWebView) = createAttachedHost(allowedOrigin = raftOrigin)
        var disposeCb: ((Boolean) -> Unit)? = null
        disposeHost.microphoneAuthorizationStatus = { WebOsMicrophonePermissionStatus.NotDetermined }
        disposeHost.requestMicrophoneAccess = { cb -> disposeCb = cb }
        val disposeOut = mutableListOf<WKPermissionDecision>()
        disposeHost.decideMediaCapturePermission(
            source = disposeWebView,
            originLabel = "https://app.raft.build",
            isMainFrame = true,
            type = WKMediaCaptureType.WKMediaCaptureTypeMicrophone,
        ) { disposeOut += it }
        assertTrue(disposeHost.audioPermissionController.hasPendingRequest)
        disposeHost.dispose()
        assertEquals(listOf(WKPermissionDecision.WKPermissionDecisionDeny), disposeOut)
        assertFalse(disposeHost.audioPermissionController.hasPendingRequest)
        checkNotNull(disposeCb).invoke(true)
        assertEquals(listOf(WKPermissionDecision.WKPermissionDecisionDeny), disposeOut)
    }
}
