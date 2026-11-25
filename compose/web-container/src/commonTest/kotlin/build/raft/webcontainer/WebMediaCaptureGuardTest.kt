package build.raft.webcontainer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WebMediaCaptureGuardTest {
    private val cameraDesc = "Raft uses camera access when you take a photo to attach to a message."
    private val micDesc = "Raft uses microphone access when you use voice or audio features in Raft and Raft Apps."

    private fun evaluateWithMap(
        kind: WebMediaCaptureKind,
        plist: Map<String, Any?>,
    ): IosWebMediaCaptureDecision =
        evaluateIosWebMediaCapturePermission(kind) { key ->
            isValidIosUsageDescription(plist[key])
        }

    @Test
    fun requiredKeysMatchEachMediaCaptureKind() {
        assertEquals(
            listOf(IOS_CAMERA_USAGE_DESCRIPTION_KEY),
            requiredIosUsageDescriptionKeys(WebMediaCaptureKind.Camera),
        )
        assertEquals(
            listOf(IOS_MICROPHONE_USAGE_DESCRIPTION_KEY),
            requiredIosUsageDescriptionKeys(WebMediaCaptureKind.Microphone),
        )
        assertEquals(
            listOf(IOS_CAMERA_USAGE_DESCRIPTION_KEY, IOS_MICROPHONE_USAGE_DESCRIPTION_KEY),
            requiredIosUsageDescriptionKeys(WebMediaCaptureKind.CameraAndMicrophone),
        )
        assertEquals(
            emptyList(),
            requiredIosUsageDescriptionKeys(WebMediaCaptureKind.Unknown),
        )
    }

    @Test
    fun rawMediaCaptureTypeMappingFailsClosedToUnknown() {
        assertEquals(WebMediaCaptureKind.Camera, webMediaCaptureKindFromRaw(0L))
        assertEquals(WebMediaCaptureKind.Microphone, webMediaCaptureKindFromRaw(1L))
        assertEquals(WebMediaCaptureKind.CameraAndMicrophone, webMediaCaptureKindFromRaw(2L))
        assertEquals(WebMediaCaptureKind.Unknown, webMediaCaptureKindFromRaw(-1L))
        assertEquals(WebMediaCaptureKind.Unknown, webMediaCaptureKindFromRaw(3L))
        assertEquals(WebMediaCaptureKind.Unknown, webMediaCaptureKindFromRaw(99L))
    }

    @Test
    fun isValidIosUsageDescriptionRequiresNonBlankString() {
        assertTrue(isValidIosUsageDescription(micDesc))
        assertFalse(isValidIosUsageDescription(null))
        assertFalse(isValidIosUsageDescription(""))
        assertFalse(isValidIosUsageDescription("   \t\n "))
        assertFalse(isValidIosUsageDescription(123))
        assertFalse(isValidIosUsageDescription(true))
    }

    @Test
    fun cameraPromptsOnlyWhenCameraKeyIsNonBlank() {
        assertEquals(
            IosWebMediaCaptureDecision.Prompt,
            evaluateWithMap(WebMediaCaptureKind.Camera, mapOf(IOS_CAMERA_USAGE_DESCRIPTION_KEY to cameraDesc)),
        )
        assertEquals(
            IosWebMediaCaptureDecision.Prompt,
            evaluateWithMap(
                WebMediaCaptureKind.Camera,
                mapOf(
                    IOS_CAMERA_USAGE_DESCRIPTION_KEY to cameraDesc,
                    IOS_MICROPHONE_USAGE_DESCRIPTION_KEY to micDesc,
                ),
            ),
        )

        for (badPlist in listOf(
            emptyMap<String, Any?>(),
            mapOf(IOS_MICROPHONE_USAGE_DESCRIPTION_KEY to micDesc),
            mapOf(IOS_CAMERA_USAGE_DESCRIPTION_KEY to ""),
            mapOf(IOS_CAMERA_USAGE_DESCRIPTION_KEY to "   "),
            mapOf(IOS_CAMERA_USAGE_DESCRIPTION_KEY to null),
        )) {
            val decision = assertIs<IosWebMediaCaptureDecision.Deny>(evaluateWithMap(WebMediaCaptureKind.Camera, badPlist))
            assertEquals("missing_usage_description", decision.reason)
            assertEquals(listOf(IOS_CAMERA_USAGE_DESCRIPTION_KEY), decision.missingKeys)
        }
    }

    @Test
    fun microphonePromptsOnlyWhenMicrophoneKeyIsNonBlank() {
        assertEquals(
            IosWebMediaCaptureDecision.Prompt,
            evaluateWithMap(WebMediaCaptureKind.Microphone, mapOf(IOS_MICROPHONE_USAGE_DESCRIPTION_KEY to micDesc)),
        )
        assertEquals(
            IosWebMediaCaptureDecision.Prompt,
            evaluateWithMap(
                WebMediaCaptureKind.Microphone,
                mapOf(
                    IOS_CAMERA_USAGE_DESCRIPTION_KEY to cameraDesc,
                    IOS_MICROPHONE_USAGE_DESCRIPTION_KEY to micDesc,
                ),
            ),
        )

        // Exact crash scenario from Hands 22cc5a87 / 1fa0888e: camera key present, microphone key missing.
        val missingMicOnly =
            assertIs<IosWebMediaCaptureDecision.Deny>(
                evaluateWithMap(WebMediaCaptureKind.Microphone, mapOf(IOS_CAMERA_USAGE_DESCRIPTION_KEY to cameraDesc)),
            )
        assertEquals("missing_usage_description", missingMicOnly.reason)
        assertEquals(listOf(IOS_MICROPHONE_USAGE_DESCRIPTION_KEY), missingMicOnly.missingKeys)

        for (badPlist in listOf(
            emptyMap<String, Any?>(),
            mapOf(IOS_MICROPHONE_USAGE_DESCRIPTION_KEY to ""),
            mapOf(IOS_MICROPHONE_USAGE_DESCRIPTION_KEY to "\t  "),
            mapOf(IOS_MICROPHONE_USAGE_DESCRIPTION_KEY to null),
        )) {
            val decision =
                assertIs<IosWebMediaCaptureDecision.Deny>(evaluateWithMap(WebMediaCaptureKind.Microphone, badPlist))
            assertEquals("missing_usage_description", decision.reason)
            assertEquals(listOf(IOS_MICROPHONE_USAGE_DESCRIPTION_KEY), decision.missingKeys)
        }
    }

    @Test
    fun cameraAndMicrophoneRequiresBothKeysNonBlank() {
        assertEquals(
            IosWebMediaCaptureDecision.Prompt,
            evaluateWithMap(
                WebMediaCaptureKind.CameraAndMicrophone,
                mapOf(
                    IOS_CAMERA_USAGE_DESCRIPTION_KEY to cameraDesc,
                    IOS_MICROPHONE_USAGE_DESCRIPTION_KEY to micDesc,
                ),
            ),
        )

        val missingBoth =
            assertIs<IosWebMediaCaptureDecision.Deny>(
                evaluateWithMap(WebMediaCaptureKind.CameraAndMicrophone, emptyMap()),
            )
        assertEquals("missing_usage_description", missingBoth.reason)
        assertEquals(
            listOf(IOS_CAMERA_USAGE_DESCRIPTION_KEY, IOS_MICROPHONE_USAGE_DESCRIPTION_KEY),
            missingBoth.missingKeys,
        )

        val missingMic =
            assertIs<IosWebMediaCaptureDecision.Deny>(
                evaluateWithMap(
                    WebMediaCaptureKind.CameraAndMicrophone,
                    mapOf(
                        IOS_CAMERA_USAGE_DESCRIPTION_KEY to cameraDesc,
                        IOS_MICROPHONE_USAGE_DESCRIPTION_KEY to "  ",
                    ),
                ),
            )
        assertEquals("missing_usage_description", missingMic.reason)
        assertEquals(listOf(IOS_MICROPHONE_USAGE_DESCRIPTION_KEY), missingMic.missingKeys)

        val missingCamera =
            assertIs<IosWebMediaCaptureDecision.Deny>(
                evaluateWithMap(
                    WebMediaCaptureKind.CameraAndMicrophone,
                    mapOf(
                        IOS_CAMERA_USAGE_DESCRIPTION_KEY to "",
                        IOS_MICROPHONE_USAGE_DESCRIPTION_KEY to micDesc,
                    ),
                ),
            )
        assertEquals("missing_usage_description", missingCamera.reason)
        assertEquals(listOf(IOS_CAMERA_USAGE_DESCRIPTION_KEY), missingCamera.missingKeys)
    }

    @Test
    fun unknownKindAlwaysFailsClosedEvenWhenAllKeysPresent() {
        var lookupCalled = false
        val decision =
            assertIs<IosWebMediaCaptureDecision.Deny>(
                evaluateIosWebMediaCapturePermission(WebMediaCaptureKind.Unknown) {
                    lookupCalled = true
                    true
                },
            )
        assertEquals("unknown_media_capture_type", decision.reason)
        assertEquals(emptyList(), decision.missingKeys)
        assertFalse(lookupCalled)
    }

    @Test
    fun lookupExceptionsFailClosedAndDeny() {
        val decision =
            assertIs<IosWebMediaCaptureDecision.Deny>(
                evaluateIosWebMediaCapturePermission(WebMediaCaptureKind.Microphone) {
                    throw IllegalStateException("bundle_unavailable")
                },
            )
        assertEquals("missing_usage_description", decision.reason)
        assertEquals(listOf(IOS_MICROPHONE_USAGE_DESCRIPTION_KEY), decision.missingKeys)
    }
}
