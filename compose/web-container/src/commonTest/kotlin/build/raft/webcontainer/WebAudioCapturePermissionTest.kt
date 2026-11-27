package build.raft.webcontainer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WebAudioCapturePermissionTest {
    private val raftOrigin = WebContainerOrigin(scheme = "https", host = "app.raft.build")

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
}
