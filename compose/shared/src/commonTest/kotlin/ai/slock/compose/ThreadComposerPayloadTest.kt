package ai.slock.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    @Test
    fun composerFlowStateSeparatesLocalTypingFromHostPayloadSync() {
        val initial =
            SharedThreadComposerFlowState.initial(
                SharedThreadComposerSnapshot(value = "hello", asTask = false)
            )

        val typed = initial.applyLocalChange("hello world")
        assertEquals("hello world", typed.liveDraftText)
        assertEquals("hello", typed.payloadTextSeed)
        assertEquals(0, typed.syncNonce)

        val unchangedHost = typed.applyHostSnapshot(SharedThreadComposerSnapshot(value = "hello", asTask = false))
        assertEquals(typed, unchangedHost)

        val submittedHost = typed.applyHostSnapshot(SharedThreadComposerSnapshot(value = "", asTask = false))
        assertEquals("", submittedHost.liveDraftText)
        assertEquals("", submittedHost.payloadTextSeed)
        assertEquals(1, submittedHost.syncNonce)
    }

    @Test
    fun buildsThreadSurfaceComposerPayloadFromTypedProps() {
        val payload =
            buildThreadSurfaceComposerPayload(
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                displayName = "Threads",
                fallbackId = "fallback-1",
                enabled = false,
                seedText = "draft",
                seedAsTask = true,
                syncNonce = 3,
                focusRequestNonce = 5,
                composerMode = "sending",
                statusLabel = "Sending",
                mentionChannelId = "channel-1",
                parentMessageId = "message-1",
                streamChannelId = "thread-1"
            )

        assertEquals("draft", payload.seedText)
        assertTrue(payload.seedAsTask)
        assertEquals(3, payload.syncNonce)
        assertEquals(5, payload.focusRequestNonce)
        assertFalse(payload.enabled)
        assertEquals("sending", payload.composerMode)
        assertEquals("Sending", payload.statusLabel)
        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, payload.surfaceKind)
        assertEquals("channel-1", payload.mentionChannelId)
        assertEquals("message-1", payload.parentMessageId)
        assertEquals("thread-1", payload.streamChannelId)
        assertEquals("Reply in thread", payload.placeholder)
    }

    @Test
    fun typedComposerPayloadKeepsSuggestionsAndAttachmentsWithoutBuilderJson() {
        val payload =
            KuiklyThreadComposerPayload(
                pendingAttachments =
                    listOf(
                        KuiklyPendingAttachmentItem(
                            id = "a1",
                            fileName = "log.txt",
                            sizeLabel = "1 KB",
                            mimeType = "text/plain",
                            status = "uploading"
                        )
                    ),
                memberSuggestions =
                    listOf(
                        KuiklyComposerMemberItem(
                            displayName = "Cindy",
                            handle = "cindy"
                        )
                    ),
                channelSuggestions =
                    listOf(
                        KuiklyComposerChannelItem(
                            name = "general",
                            subtitle = "team"
                        )
                    )
            )

        assertEquals("log.txt", payload.pendingAttachments.single().fileName)
        assertEquals("Cindy", payload.memberSuggestions.single().displayName)
        assertEquals("general", payload.channelSuggestions.single().name)
    }
}
