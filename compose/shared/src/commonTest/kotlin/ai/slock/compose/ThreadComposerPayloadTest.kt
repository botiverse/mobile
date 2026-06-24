package ai.slock.compose

import kotlinx.serialization.ExperimentalSerializationApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThreadComposerPayloadTest {
    @Test
    fun parsesWrappedLaunchPayload() {
        val payload =
            parseThreadComposerPayload(
                payload = """{"seedText":"hello","syncNonce":2,"streamChannelId":"thread-1"}""",
                fallbackPageData = """{"instanceId":"host-1"}""",
                instanceId = "host-1"
            )

        assertEquals("host-1", payload.instanceId)
        assertEquals("hello", payload.seedText)
        assertEquals("Message", payload.placeholder)
        assertEquals(2, payload.syncNonce)
        assertEquals("thread-1", payload.streamChannelId)
        assertNull(payload.statusLabel)
    }

    @Test
    fun rejectsWrappedFullContentPayload() {
        val payload =
            parseThreadComposerPayload(
                payload =
                    """
                    {
                      "seedText": "hello",
                      "placeholder": "Reply",
                      "pendingAttachments": [
                        {"id":"a1","fileName":"stale.txt"}
                      ]
                    }
                    """.trimIndent(),
                fallbackPageData = """{"instanceId":"host-content"}""",
                instanceId = "host-content"
            )

        assertEquals("host-content", payload.instanceId)
        assertEquals("", payload.seedText)
        assertEquals("Message", payload.placeholder)
        assertEquals(emptyList(), payload.pendingAttachments)
        assertEquals("failed", payload.composerMode)
        assertEquals("Composer payload parse failed", payload.statusLabel)
    }

    @Test
    fun topLevelPageDataUsesLaunchFieldsOnlyWhenPayloadWrapperIsMissing() {
        val payload =
            parseThreadComposerPayload(
                payload = "",
                fallbackPageData =
                    """
                    {
                      "instanceId": "host-2",
                      "seedText": "launch draft",
                      "seedAsTask": true,
                      "streamChannelId": "thread-2",
                      "appId": 1
                    }
                    """.trimIndent(),
                instanceId = "host-2"
            )

        assertEquals("host-2", payload.instanceId)
        assertEquals("launch draft", payload.seedText)
        assertTrue(payload.seedAsTask)
        assertEquals("thread-2", payload.streamChannelId)
        assertEquals(true, payload.showAsTaskToggle)
        assertEquals("Message", payload.placeholder)
        assertEquals(emptyList(), payload.pendingAttachments)
        assertEquals(emptyList(), payload.memberSuggestions)
        assertEquals(emptyList(), payload.channelSuggestions)
        assertEquals(emptyList(), payload.hookLabels)
        assertEquals("idle", payload.composerMode)
        assertNull(payload.statusLabel)
    }

    @Test
    fun rejectsTopLevelFullContentPayload() {
        val payload =
            parseThreadComposerPayload(
                payload = "",
                fallbackPageData =
                    """
                    {
                      "instanceId": "host-top-level-content",
                      "seedText": "launch draft",
                      "showAsTaskToggle": false,
                      "placeholder": "Stale placeholder",
                      "memberSuggestions": [
                        {"displayName":"Stale","handle":"stale"}
                      ],
                      "composerMode": "sending"
                    }
                    """.trimIndent(),
                instanceId = "host-top-level-content"
            )

        assertEquals("host-top-level-content", payload.instanceId)
        assertEquals("", payload.seedText)
        assertEquals("Message", payload.placeholder)
        assertEquals(emptyList(), payload.memberSuggestions)
        assertEquals("failed", payload.composerMode)
        assertEquals("Composer payload parse failed", payload.statusLabel)
    }

    @Test
    @OptIn(ExperimentalSerializationApi::class)
    fun composerLaunchWireSchemaExcludesContentFields() {
        val descriptor = WireThreadComposerLaunchPayload.serializer().descriptor
        val names = (0 until descriptor.elementsCount)
            .map { index -> descriptor.getElementName(index) }
            .toSet()

        listOf(
            "placeholder",
            "enabled",
            "attachmentActionsEnabled",
            "showAsTaskToggle",
            "composerMode",
            "statusLabel",
            "pendingAttachments",
            "memberSuggestions",
            "channelSuggestions",
            "hookLabels"
        ).forEach { forbidden ->
            assertFalse(forbidden in names, "$forbidden must stay outside compose_thread_composer launch pageData")
        }
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

    @Test
    fun sendAttachmentIdsExcludeLocalPickedAttachmentsUntilUploadFinishes() {
        val attachments =
            listOf(
                KuiklyPendingAttachmentItem(
                    id = "picked-123-0",
                    fileName = "local.png",
                    status = "ready"
                ),
                KuiklyPendingAttachmentItem(
                    id = "remote-attachment-1",
                    fileName = "remote.png",
                    status = "ready"
                ),
                KuiklyPendingAttachmentItem(
                    id = "remote-attachment-2",
                    fileName = "uploading.png",
                    status = "uploading"
                )
            )

        assertEquals(listOf("remote-attachment-1"), attachments.readyThreadAttachmentIdsForSend())
        assertTrue(attachments.hasBlockingThreadAttachmentUpload())
        assertEquals(
            listOf("uploading", "ready", "uploading"),
            attachments.withThreadAttachmentUploadWaitingState().map { attachment -> attachment.status }
        )
    }

    @Test
    fun readyUploadedAttachmentsDoNotBlockSend() {
        val attachments =
            listOf(
                KuiklyPendingAttachmentItem(
                    id = "remote-attachment-1",
                    fileName = "remote.png",
                    status = "ready"
                )
            )

        assertEquals(listOf("remote-attachment-1"), attachments.readyThreadAttachmentIdsForSend())
        assertFalse(attachments.hasBlockingThreadAttachmentUpload())
    }

    @Test
    fun composerSurfaceUsesStreamChannelForSendTargetAndParentForMentionScope() {
        val surface =
            KuiklyThreadComposerPayload(
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                streamChannelId = "reply-thread-1",
                mentionChannelId = "parent-channel-1",
                parentMessageId = "parent-message-1",
                showAsTaskToggle = false
            ).toThreadComposerSurface(
                channelId = "route-thread-1",
                parentChannelId = "parent-channel-1"
            )

        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, surface.kind)
        assertEquals("route-thread-1", surface.channelId)
        assertEquals("reply-thread-1", surface.sendChannelId)
        assertEquals("parent-channel-1", surface.mentionScopeChannelId)
        assertEquals("parent-message-1", surface.parentMessageId)
        assertFalse(surface.showTaskButton)
    }

    @Test
    fun pendingReplyComposerSurfaceHasNoSendTargetUntilStreamExists() {
        val surface =
            KuiklyThreadComposerPayload(
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                streamChannelId = null,
                mentionChannelId = "parent-channel-1",
                parentMessageId = "parent-message-1"
            ).toThreadComposerSurface(
                channelId = "",
                parentChannelId = "parent-channel-1"
            )

        assertNull(surface.sendChannelId)
        assertEquals("parent-channel-1", surface.mentionScopeChannelId)
    }

    @Test
    fun pendingMentionActionsExposeActionSupportAndTargetLabel() {
        val action =
            KuiklyPendingMentionActionItem(
                resolutionId = "res-1",
                targetType = "agent",
                targetHandle = "outside",
                availableActions = listOf("add", "notify_only")
            )

        assertTrue(action.supportsMentionAction("invite"))
        assertTrue(action.supportsMentionAction("notify"))
        assertEquals("@outside", action.targetLabel())
    }

    @Test
    fun mentionActionFailureStatusMapsKnownReasons() {
        assertEquals(
            "Only channel admins can add members from this mention action.",
            formatMentionActionStatusNotice(
                ai.slock.compose.data.PendingMentionActionExecuteResult(
                    resolutionId = "res-1",
                    status = "no_permission",
                    reason = "invite_requires_member_management_authority"
                )
            )
        )
        assertEquals(
            "This mention action could not be applied.",
            formatMentionActionStatusNotice(null)
        )
    }
}
