package ai.slock.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ThreadComposerPayloadTest {
    @Test
    fun parsesWrappedPayload() {
        val payload =
            parseThreadComposerPayload(
                payload = """{"seedText":"hello","placeholder":"Reply","syncNonce":2}""",
                fallbackPageData = """{"instanceId":"host-1"}""",
                instanceId = "host-1"
            )

        assertEquals("host-1", payload.instanceId)
        assertEquals("hello", payload.seedText)
        assertEquals("Reply", payload.placeholder)
        assertEquals(2, payload.syncNonce)
        assertNull(payload.statusLabel)
    }

    @Test
    fun parsesLegacyTopLevelPageDataWhenPayloadWrapperIsMissing() {
        val payload =
            parseThreadComposerPayload(
                payload = "",
                fallbackPageData = """{"instanceId":"host-2","seedText":"legacy","showAsTaskToggle":false}""",
                instanceId = "host-2"
            )

        assertEquals("host-2", payload.instanceId)
        assertEquals("legacy", payload.seedText)
        assertEquals(false, payload.showAsTaskToggle)
        assertNull(payload.statusLabel)
    }

    @Test
    fun treatsInitialEmptyPageDataAsIdleComposer() {
        val payload =
            parseThreadComposerPayload(
                payload = "",
                fallbackPageData = """{"instanceId":"host-3","appId":1}""",
                instanceId = "host-3"
            )

        assertEquals("host-3", payload.instanceId)
        assertEquals("idle", payload.composerMode)
        assertNull(payload.statusLabel)
    }

    @Test
    fun malformedWrappedPayloadStillShowsParseFailure() {
        val payload =
            parseThreadComposerPayload(
                payload = """{"seedText":""",
                fallbackPageData = """{"instanceId":"host-4"}""",
                instanceId = "host-4"
            )

        assertEquals("host-4", payload.instanceId)
        assertEquals("failed", payload.composerMode)
        assertEquals("Composer payload parse failed", payload.statusLabel)
    }
}
