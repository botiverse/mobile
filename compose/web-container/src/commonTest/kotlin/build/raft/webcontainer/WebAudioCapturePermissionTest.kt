package build.raft.webcontainer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebAudioCapturePermissionTest {
    private val raftOrigin = WebContainerOrigin(scheme = "https", host = "app.raft.build")

    private data class RecordedMediaDecision(
        val granted: Boolean,
        val kind: WebMediaCaptureKind,
        val reason: String,
        val missingKeys: List<String>,
        val origin: String,
        val isMainFrame: Boolean,
    )

    @Test
    fun allowsExactOriginMatchForAudioOnlyIncludingDefaultPortAndCaseNormalization() {
        val standard =
            evaluateWebAudioCapturePermission(
                requestOriginUrl = "https://app.raft.build/",
                requestedResources = listOf(WEB_PERMISSION_RESOURCE_AUDIO_CAPTURE),
                allowedOrigin = raftOrigin,
            )
        val allowed = assertIs<WebAudioCapturePermissionDecision.AllowAudioCapture>(standard)
        assertEquals(raftOrigin, allowed.origin)

        val upperWithDefaultPort =
            evaluateWebAudioCapturePermission(
                requestOriginUrl = "HTTPS://App.Raft.Build:443/",
                requestedResources = listOf(WEB_PERMISSION_RESOURCE_AUDIO_CAPTURE),
                allowedOrigin = WebContainerOrigin(scheme = "https", host = "app.raft.build", port = 443),
            )
        assertIs<WebAudioCapturePermissionDecision.AllowAudioCapture>(upperWithDefaultPort)

        val customPortAllowed = WebContainerOrigin(scheme = "https", host = "app.raft.build", port = 8443)
        val customPortMatch =
            evaluateWebAudioCapturePermission(
                requestOriginUrl = "https://app.raft.build:8443/",
                requestedResources = listOf(WEB_PERMISSION_RESOURCE_AUDIO_CAPTURE),
                allowedOrigin = customPortAllowed,
            )
        assertEquals(
            WebAudioCapturePermissionDecision.AllowAudioCapture(customPortAllowed),
            customPortMatch,
        )

        val localDevAllowed = WebContainerOrigin(scheme = "http", host = "localhost", port = 3000)
        val localDevMatch =
            evaluateWebAudioCapturePermission(
                requestOriginUrl = "http://localhost:3000/",
                requestedResources = listOf(WEB_PERMISSION_RESOURCE_AUDIO_CAPTURE),
                allowedOrigin = localDevAllowed,
            )
        assertEquals(
            WebAudioCapturePermissionDecision.AllowAudioCapture(localDevAllowed),
            localDevMatch,
        )
    }

    @Test
    fun rejectsNullAllowedOriginAndNonHttpSchemes() {
        val disabled =
            evaluateWebAudioCapturePermission(
                requestOriginUrl = "https://app.raft.build/",
                requestedResources = listOf(WEB_PERMISSION_RESOURCE_AUDIO_CAPTURE),
                allowedOrigin = null,
            )
        assertEquals(WebAudioCapturePermissionDecision.Deny(WEB_MEDIA_CAPTURE_DENIED_REASON), disabled)

        val nonHttpAllowed =
            evaluateWebAudioCapturePermission(
                requestOriginUrl = "file://localhost/",
                requestedResources = listOf(WEB_PERMISSION_RESOURCE_AUDIO_CAPTURE),
                allowedOrigin = WebContainerOrigin(scheme = "file", host = "localhost"),
            )
        assertEquals(WebAudioCapturePermissionDecision.Deny("scheme_not_allowed"), nonHttpAllowed)
    }

    @Test
    fun rejectsSandboxedContentDomainsLookalikesSchemeDowngradePortMismatchAndOpaqueOrigins() {
        val disallowedOrigins =
            listOf(
                "https://abc.w.raftusercontent.com/",
                "https://artifact-1.raftusercontent.com/",
                "https://raftusercontent.com/",
                "https://evil.app.raft.build/",
                "https://app.raft.build.evil.com/",
                "https://app.raft.build./",
                "http://app.raft.build/",
                "https://app.raft.build:8443/",
                "https://staging.raft.build/",
            )
        for (origin in disallowedOrigins) {
            val decision =
                evaluateWebAudioCapturePermission(
                    requestOriginUrl = origin,
                    requestedResources = listOf(WEB_PERMISSION_RESOURCE_AUDIO_CAPTURE),
                    allowedOrigin = raftOrigin,
                )
            val denied = assertIs<WebAudioCapturePermissionDecision.Deny>(decision, "Expected deny for $origin")
            assertEquals("origin_not_allowed", denied.reason, "Unexpected deny reason for $origin")
        }

        val customPortDenied =
            evaluateWebAudioCapturePermission(
                requestOriginUrl = "https://app.raft.build/",
                requestedResources = listOf(WEB_PERMISSION_RESOURCE_AUDIO_CAPTURE),
                allowedOrigin = WebContainerOrigin(scheme = "https", host = "app.raft.build", port = 8443),
            )
        assertEquals(WebAudioCapturePermissionDecision.Deny("origin_not_allowed"), customPortDenied)

        val opaqueOrMalformed =
            listOf(
                null,
                "",
                "   ",
                "null",
                "about:blank",
                "data:text/html,<h1>hi</h1>",
                "blob:https://app.raft.build/1234",
                "file:///data/local/tmp/index.html",
                "https://user:pass@app.raft.build/",
            )
        for (origin in opaqueOrMalformed) {
            val decision =
                evaluateWebAudioCapturePermission(
                    requestOriginUrl = origin,
                    requestedResources = listOf(WEB_PERMISSION_RESOURCE_AUDIO_CAPTURE),
                    allowedOrigin = raftOrigin,
                )
            val denied = assertIs<WebAudioCapturePermissionDecision.Deny>(decision, "Expected deny for $origin")
            assertEquals("invalid_request_origin", denied.reason, "Unexpected deny reason for $origin")
        }
    }

    @Test
    fun rejectsCameraVideoMidiProtectedMediaMixedAndEmptyResources() {
        val emptyDecision =
            evaluateWebAudioCapturePermission(
                requestOriginUrl = "https://app.raft.build/",
                requestedResources = emptyList(),
                allowedOrigin = raftOrigin,
            )
        assertEquals(WebAudioCapturePermissionDecision.Deny("empty_resources"), emptyDecision)

        val disallowedResourceSets =
            listOf(
                listOf("android.webkit.resource.VIDEO_CAPTURE"),
                listOf(WEB_PERMISSION_RESOURCE_AUDIO_CAPTURE, "android.webkit.resource.VIDEO_CAPTURE"),
                listOf("android.webkit.resource.VIDEO_CAPTURE", WEB_PERMISSION_RESOURCE_AUDIO_CAPTURE),
                listOf("android.webkit.resource.MIDI_SYSEX"),
                listOf(WEB_PERMISSION_RESOURCE_AUDIO_CAPTURE, "android.webkit.resource.MIDI_SYSEX"),
                listOf("android.webkit.resource.PROTECTED_MEDIA_ID"),
                listOf(""),
                listOf("unknown.resource"),
            )
        for (resources in disallowedResourceSets) {
            val decision =
                evaluateWebAudioCapturePermission(
                    requestOriginUrl = "https://app.raft.build/",
                    requestedResources = resources,
                    allowedOrigin = raftOrigin,
                )
            val denied = assertIs<WebAudioCapturePermissionDecision.Deny>(decision, "Expected deny for $resources")
            assertEquals("non_audio_resource_requested", denied.reason, "Unexpected deny reason for $resources")
        }
    }

    @Test
    fun parseWebSecurityOriginNormalizesValidComponentsAndRejectsOpaqueOrMalformed() {
        assertEquals(
            WebContainerOrigin(scheme = "https", host = "app.raft.build"),
            parseWebSecurityOrigin(protocol = "https", host = "app.raft.build", port = 0L),
        )
        assertEquals(
            WebContainerOrigin(scheme = "https", host = "app.raft.build"),
            parseWebSecurityOrigin(protocol = "HTTPS", host = "App.Raft.Build", port = 443L),
        )
        assertEquals(
            WebContainerOrigin(scheme = "https", host = "app.raft.build", port = 8443),
            parseWebSecurityOrigin(protocol = "https", host = "app.raft.build", port = 8443L),
        )
        assertEquals(
            WebContainerOrigin(scheme = "http", host = "localhost"),
            parseWebSecurityOrigin(protocol = "http", host = "localhost", port = 80L),
        )
        assertEquals(
            WebContainerOrigin(scheme = "http", host = "localhost", port = 3000),
            parseWebSecurityOrigin(protocol = "http", host = "localhost", port = 3000L),
        )
        assertEquals(
            WebContainerOrigin(scheme = "http", host = "::1", port = 3000),
            parseWebSecurityOrigin(protocol = "http", host = "::1", port = 3000L),
        )
        assertEquals(
            WebContainerOrigin(scheme = "http", host = "::1", port = 3000),
            parseWebSecurityOrigin(protocol = "http", host = "[::1]", port = 3000L),
        )

        assertNull(parseWebSecurityOrigin(protocol = "", host = "", port = 0L))
        assertNull(parseWebSecurityOrigin(protocol = "null", host = "", port = 0L))
        assertNull(parseWebSecurityOrigin(protocol = null, host = "app.raft.build", port = 0L))
        assertNull(parseWebSecurityOrigin(protocol = "https", host = null, port = 0L))
        assertNull(parseWebSecurityOrigin(protocol = "https", host = "app.raft.build", port = -1L))
        assertNull(parseWebSecurityOrigin(protocol = "https", host = "app.raft.build", port = 65536L))
        assertNull(parseWebSecurityOrigin(protocol = "https", host = "app.raft.build:8443", port = 0L))
        assertNull(parseWebSecurityOrigin(protocol = "https", host = "user:pass@app.raft.build", port = 0L))
        assertNull(parseWebSecurityOrigin(protocol = "https", host = "app.raft.build/path", port = 0L))
        assertNull(parseWebSecurityOrigin(protocol = "https", host = "app.raft.build?q=1", port = 0L))
        assertNull(parseWebSecurityOrigin(protocol = "https", host = "[]", port = 0L))
    }

    @Test
    fun evaluateWebMediaCaptureAudioPermissionEnforcesKindMainFrameAndStrictOrigins() {
        // Exact main-frame microphone match on own origin succeeds (including default port normalization).
        assertEquals(
            WebAudioCapturePermissionDecision.AllowAudioCapture(raftOrigin),
            evaluateWebMediaCaptureAudioPermission(
                kind = WebMediaCaptureKind.Microphone,
                isMainFrame = true,
                requestOrigin = parseWebSecurityOrigin("HTTPS", "App.Raft.Build", 443L),
                allowedOrigin = raftOrigin,
            ),
        )

        val customPort = WebContainerOrigin(scheme = "http", host = "localhost", port = 3000)
        assertEquals(
            WebAudioCapturePermissionDecision.AllowAudioCapture(customPort),
            evaluateWebMediaCaptureAudioPermission(
                kind = WebMediaCaptureKind.Microphone,
                isMainFrame = true,
                requestOrigin = parseWebSecurityOrigin("http", "localhost", 3000L),
                allowedOrigin = customPort,
            ),
        )

        // Null allowedOrigin fails closed with default policy reason.
        assertEquals(
            WebAudioCapturePermissionDecision.Deny(WEB_MEDIA_CAPTURE_DENIED_REASON),
            evaluateWebMediaCaptureAudioPermission(
                kind = WebMediaCaptureKind.Microphone,
                isMainFrame = true,
                requestOrigin = raftOrigin,
                allowedOrigin = null,
            ),
        )

        // Non-http/https allowedOrigin fails closed.
        assertEquals(
            WebAudioCapturePermissionDecision.Deny("scheme_not_allowed"),
            evaluateWebMediaCaptureAudioPermission(
                kind = WebMediaCaptureKind.Microphone,
                isMainFrame = true,
                requestOrigin = WebContainerOrigin(scheme = "file", host = "localhost"),
                allowedOrigin = WebContainerOrigin(scheme = "file", host = "localhost"),
            ),
        )

        // Camera, CameraAndMicrophone, and Unknown are always denied even on own main frame.
        for (disallowedKind in listOf(WebMediaCaptureKind.Camera, WebMediaCaptureKind.CameraAndMicrophone, WebMediaCaptureKind.Unknown)) {
            assertEquals(
                WebAudioCapturePermissionDecision.Deny("non_audio_resource_requested"),
                evaluateWebMediaCaptureAudioPermission(
                    kind = disallowedKind,
                    isMainFrame = true,
                    requestOrigin = raftOrigin,
                    allowedOrigin = raftOrigin,
                ),
                "Expected deny for $disallowedKind",
            )
        }

        // Subframes / iframes are always denied — even if origin matches allowedOrigin.
        assertEquals(
            WebAudioCapturePermissionDecision.Deny("subframe_not_allowed"),
            evaluateWebMediaCaptureAudioPermission(
                kind = WebMediaCaptureKind.Microphone,
                isMainFrame = false,
                requestOrigin = raftOrigin,
                allowedOrigin = raftOrigin,
            ),
        )

        // Opaque / null requestOrigin or frameOrigin fails closed.
        assertEquals(
            WebAudioCapturePermissionDecision.Deny("invalid_request_origin"),
            evaluateWebMediaCaptureAudioPermission(
                kind = WebMediaCaptureKind.Microphone,
                isMainFrame = true,
                requestOrigin = null,
                allowedOrigin = raftOrigin,
            ),
        )
        assertEquals(
            WebAudioCapturePermissionDecision.Deny("invalid_request_origin"),
            evaluateWebMediaCaptureAudioPermission(
                kind = WebMediaCaptureKind.Microphone,
                isMainFrame = true,
                requestOrigin = raftOrigin,
                allowedOrigin = raftOrigin,
                frameOrigin = null,
            ),
        )

        // Wrong host, sandboxed content domain, trailing dot, scheme downgrade, wrong port, or mismatched frameOrigin fail closed.
        val wrongOrigins =
            listOf(
                WebContainerOrigin(scheme = "https", host = "abc.w.raftusercontent.com"),
                WebContainerOrigin(scheme = "https", host = "evil.app.raft.build"),
                WebContainerOrigin(scheme = "https", host = "app.raft.build.evil.com"),
                WebContainerOrigin(scheme = "https", host = "app.raft.build."),
                WebContainerOrigin(scheme = "http", host = "app.raft.build"),
                WebContainerOrigin(scheme = "https", host = "app.raft.build", port = 8443),
            )
        for (wrong in wrongOrigins) {
            assertEquals(
                WebAudioCapturePermissionDecision.Deny("origin_not_allowed"),
                evaluateWebMediaCaptureAudioPermission(
                    kind = WebMediaCaptureKind.Microphone,
                    isMainFrame = true,
                    requestOrigin = wrong,
                    allowedOrigin = raftOrigin,
                ),
                "Expected origin_not_allowed for requestOrigin=$wrong",
            )
            assertEquals(
                WebAudioCapturePermissionDecision.Deny("origin_not_allowed"),
                evaluateWebMediaCaptureAudioPermission(
                    kind = WebMediaCaptureKind.Microphone,
                    isMainFrame = true,
                    requestOrigin = raftOrigin,
                    allowedOrigin = raftOrigin,
                    frameOrigin = wrong,
                ),
                "Expected origin_not_allowed for frameOrigin=$wrong",
            )
        }
    }

    @Test
    fun controllerGrantsWhenAuthorizedAndDeniesImmediatelyForDeniedRestrictedOrMissingUsageDescription() {
        val decisions = mutableListOf<RecordedMediaDecision>()
        var allowedOrigin: WebContainerOrigin? = raftOrigin
        var foreground = true
        var hasUsage = true
        var osStatus = WebOsMicrophonePermissionStatus.Authorized
        var promptCalls = 0
        var mainDispatches = 0

        val controller =
            WebMediaCaptureAudioPermissionController(
                allowedOriginProvider = { allowedOrigin },
                isHostForegroundActive = { foreground },
                hasUsageDescription = { hasUsage },
                osAuthorizationStatus = { osStatus },
                requestOsPermission = { promptCalls += 1 },
                runOnMainThread = { block ->
                    mainDispatches += 1
                    block()
                },
                onDecision = { granted, kind, reason, missingKeys, origin, isMainFrame ->
                    decisions += RecordedMediaDecision(granted, kind, reason, missingKeys, origin, isMainFrame)
                },
            )

        // 1) Authorized -> grants immediately on main thread without prompting OS.
        val authorizedResults = mutableListOf<Boolean>()
        controller.handleRequest(
            kind = WebMediaCaptureKind.Microphone,
            isMainFrame = true,
            requestOrigin = raftOrigin,
        ) { authorizedResults += it }
        assertEquals(listOf(true), authorizedResults)
        assertEquals(0, promptCalls)
        assertEquals(1, mainDispatches)
        assertFalse(controller.hasPendingRequest)
        assertEquals(
            RecordedMediaDecision(true, WebMediaCaptureKind.Microphone, "granted", emptyList(), "https://app.raft.build", true),
            decisions.last(),
        )

        // 2) Denied -> denies immediately without prompting OS.
        osStatus = WebOsMicrophonePermissionStatus.Denied
        val deniedResults = mutableListOf<Boolean>()
        controller.handleRequest(
            kind = WebMediaCaptureKind.Microphone,
            isMainFrame = true,
            requestOrigin = raftOrigin,
        ) { deniedResults += it }
        assertEquals(listOf(false), deniedResults)
        assertEquals(0, promptCalls)
        assertEquals("os_permission_denied", decisions.last().reason)

        // 3) Restricted -> denies immediately without prompting OS.
        osStatus = WebOsMicrophonePermissionStatus.Restricted
        val restrictedResults = mutableListOf<Boolean>()
        controller.handleRequest(
            kind = WebMediaCaptureKind.Microphone,
            isMainFrame = true,
            requestOrigin = raftOrigin,
        ) { restrictedResults += it }
        assertEquals(listOf(false), restrictedResults)
        assertEquals(0, promptCalls)
        assertEquals("os_permission_restricted", decisions.last().reason)

        // 4) Missing NSMicrophoneUsageDescription -> denies before touching OS authorization APIs.
        osStatus = WebOsMicrophonePermissionStatus.Authorized
        hasUsage = false
        val missingUsageResults = mutableListOf<Boolean>()
        controller.handleRequest(
            kind = WebMediaCaptureKind.Microphone,
            isMainFrame = true,
            requestOrigin = raftOrigin,
        ) { missingUsageResults += it }
        assertEquals(listOf(false), missingUsageResults)
        assertEquals(0, promptCalls)
        assertEquals("missing_usage_description", decisions.last().reason)
        assertEquals(listOf(IOS_MICROPHONE_USAGE_DESCRIPTION_KEY), decisions.last().missingKeys)

        // 5) Policy/foreground violations deny immediately without prompting OS.
        hasUsage = true
        foreground = false
        val inactiveResults = mutableListOf<Boolean>()
        controller.handleRequest(
            kind = WebMediaCaptureKind.Microphone,
            isMainFrame = true,
            requestOrigin = raftOrigin,
        ) { inactiveResults += it }
        assertEquals(listOf(false), inactiveResults)
        assertEquals("host_inactive", decisions.last().reason)

        foreground = true
        val blockedCases =
            listOf(
                Triple(WebMediaCaptureKind.Camera, true, raftOrigin) to "non_audio_resource_requested",
                Triple(WebMediaCaptureKind.CameraAndMicrophone, true, raftOrigin) to "non_audio_resource_requested",
                Triple(WebMediaCaptureKind.Microphone, false, raftOrigin) to "subframe_not_allowed",
                Triple(WebMediaCaptureKind.Microphone, true, WebContainerOrigin("https", "abc.w.raftusercontent.com")) to "origin_not_allowed",
                Triple(WebMediaCaptureKind.Microphone, true, WebContainerOrigin("http", "app.raft.build")) to "origin_not_allowed",
                Triple(WebMediaCaptureKind.Microphone, true, WebContainerOrigin("https", "app.raft.build", 8443)) to "origin_not_allowed",
            )
        for ((input, expectedReason) in blockedCases) {
            val out = mutableListOf<Boolean>()
            controller.handleRequest(
                kind = input.first,
                isMainFrame = input.second,
                requestOrigin = input.third,
            ) { out += it }
            assertEquals(listOf(false), out)
            assertEquals(expectedReason, decisions.last().reason)
        }
        allowedOrigin = null
        val nullAllowedOut = mutableListOf<Boolean>()
        controller.handleRequest(
            kind = WebMediaCaptureKind.Microphone,
            isMainFrame = true,
            requestOrigin = raftOrigin,
        ) { nullAllowedOut += it }
        assertEquals(listOf(false), nullAllowedOut)
        assertEquals(WEB_MEDIA_CAPTURE_DENIED_REASON, decisions.last().reason)
        assertEquals(0, promptCalls)
    }

    @Test
    fun controllerHandlesNotDeterminedAsyncGrantDenyCancelRevokeSupersedeAndDoubleCallbackOnce() {
        val decisions = mutableListOf<RecordedMediaDecision>()
        var allowedOrigin: WebContainerOrigin? = raftOrigin
        var foreground = true
        var pendingOsCallback: ((Boolean) -> Unit)? = null
        var throwOnPrompt = false
        val queuedMainTasks = mutableListOf<() -> Unit>()

        fun drainMain() {
            while (queuedMainTasks.isNotEmpty()) {
                queuedMainTasks.removeAt(0).invoke()
            }
        }

        val controller =
            WebMediaCaptureAudioPermissionController(
                allowedOriginProvider = { allowedOrigin },
                isHostForegroundActive = { foreground },
                hasUsageDescription = { true },
                osAuthorizationStatus = { WebOsMicrophonePermissionStatus.NotDetermined },
                requestOsPermission = { onResult ->
                    if (throwOnPrompt) error("tcc_unavailable")
                    pendingOsCallback = onResult
                },
                runOnMainThread = { block -> queuedMainTasks += block },
                onDecision = { granted, kind, reason, missingKeys, origin, isMainFrame ->
                    decisions += RecordedMediaDecision(granted, kind, reason, missingKeys, origin, isMainFrame)
                },
            )

        // 1) Async OS grant dispatches back onto main thread and delivers Grant strictly once even if OS fires twice.
        val grantOut = mutableListOf<Boolean>()
        controller.handleRequest(WebMediaCaptureKind.Microphone, true, raftOrigin) { grantOut += it }
        assertTrue(controller.hasPendingRequest)
        assertEquals(emptyList<Boolean>(), grantOut)
        val firstCallback = checkNotNull(pendingOsCallback)
        firstCallback(true)
        assertEquals(emptyList<Boolean>(), grantOut) // queued onto main thread
        drainMain()
        assertEquals(listOf(true), grantOut)
        assertFalse(controller.hasPendingRequest)
        assertEquals("granted", decisions.last().reason)
        // Duplicate OS callback must NOT double-invoke decisionHandler.
        firstCallback(true)
        drainMain()
        assertEquals(listOf(true), grantOut)

        // 2) Async OS denial settles once with Deny.
        val denyOut = mutableListOf<Boolean>()
        controller.handleRequest(WebMediaCaptureKind.Microphone, true, raftOrigin) { denyOut += it }
        checkNotNull(pendingOsCallback).invoke(false)
        drainMain()
        assertEquals(listOf(false), denyOut)
        assertEquals("os_permission_denied", decisions.last().reason)

        // 3) Host becomes inactive before OS grant resolves -> denies once with host_inactive_after_prompt.
        val bgOut = mutableListOf<Boolean>()
        controller.handleRequest(WebMediaCaptureKind.Microphone, true, raftOrigin) { bgOut += it }
        foreground = false
        checkNotNull(pendingOsCallback).invoke(true)
        drainMain()
        assertEquals(listOf(false), bgOut)
        assertEquals("host_inactive_after_prompt", decisions.last().reason)
        foreground = true

        // 4) Allowed origin revoked before OS grant resolves -> denies once and never grants.
        val revokeOut = mutableListOf<Boolean>()
        controller.handleRequest(WebMediaCaptureKind.Microphone, true, raftOrigin) { revokeOut += it }
        allowedOrigin = null
        checkNotNull(pendingOsCallback).invoke(true)
        drainMain()
        assertEquals(listOf(false), revokeOut)
        assertEquals(WEB_MEDIA_CAPTURE_DENIED_REASON, decisions.last().reason)
        allowedOrigin = raftOrigin

        // 5) cancelAll settles pending callback once immediately; late OS grant is ignored.
        val cancelOut = mutableListOf<Boolean>()
        controller.handleRequest(WebMediaCaptureKind.Microphone, true, raftOrigin) { cancelOut += it }
        val canceledCallback = checkNotNull(pendingOsCallback)
        controller.cancelAll("host_disposed")
        drainMain()
        assertEquals(listOf(false), cancelOut)
        assertFalse(controller.hasPendingRequest)
        assertEquals("host_disposed", decisions.last().reason)
        canceledCallback(true)
        drainMain()
        assertEquals(listOf(false), cancelOut)

        // 6) Superseding request denies the first once and lets the second resolve.
        val firstOut = mutableListOf<Boolean>()
        val secondOut = mutableListOf<Boolean>()
        controller.handleRequest(WebMediaCaptureKind.Microphone, true, raftOrigin) { firstOut += it }
        val staleCallback = checkNotNull(pendingOsCallback)
        controller.handleRequest(WebMediaCaptureKind.Microphone, true, raftOrigin) { secondOut += it }
        val latestCallback = checkNotNull(pendingOsCallback)
        drainMain()
        assertEquals(listOf(false), firstOut)
        assertEquals(emptyList<Boolean>(), secondOut)
        staleCallback(true)
        latestCallback(true)
        drainMain()
        assertEquals(listOf(false), firstOut)
        assertEquals(listOf(true), secondOut)

        // 7) Throwing requestOsPermission still settles callback once with Deny (never leaks decisionHandler).
        throwOnPrompt = true
        val throwOut = mutableListOf<Boolean>()
        controller.handleRequest(WebMediaCaptureKind.Microphone, true, raftOrigin) { throwOut += it }
        drainMain()
        assertEquals(listOf(false), throwOut)
        assertFalse(controller.hasPendingRequest)
        assertEquals("os_permission_request_failed", decisions.last().reason)
    }
}
