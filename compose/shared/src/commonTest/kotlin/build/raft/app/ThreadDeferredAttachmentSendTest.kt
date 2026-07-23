package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ThreadDeferredAttachmentSendTest {
    private fun attachment(
        id: String,
        status: String,
        fileName: String = "$id.bin"
    ): KuiklyPendingAttachmentItem =
        KuiklyPendingAttachmentItem(
            id = id,
            fileName = fileName,
            status = status
        )

    private fun context(attachments: List<KuiklyPendingAttachmentItem>): KuiklyThreadPendingSendContext =
        KuiklyThreadPendingSendContext(
            channelId = "channel-1",
            body = "body",
            asTask = true,
            attachmentIds = attachments.readyThreadAttachmentIdsForSend(),
            attachments = attachments,
            mentionChannelId = "channel-1",
            streamChannelId = "channel-1",
            randomId = "random-1"
        )

    @Test
    fun outOfOrderUploadsRemainAllOrNoneAndPreserveSelectionOrder() {
        val send =
            newThreadDeferredAttachmentSend(
                localMessageId = "local-message-1",
                context =
                    context(
                        listOf(
                            attachment("server-ready", "ready"),
                            attachment("picked-local-1", "uploading"),
                            attachment("picked-local-2", "uploading")
                        )
                    ),
                timestampLabel = "Now",
                generation = 4L,
                startedFromComposer = true
            )

        val secondFinished =
            send.recordUploadSuccess(
                localAttachmentId = "picked-local-2",
                attachments = listOf(attachment("server-2", "ready"))
            )
        assertNull(secondFinished.readyContextOrNull())

        val allFinished =
            secondFinished.recordUploadSuccess(
                localAttachmentId = "picked-local-1",
                attachments =
                    listOf(
                        attachment("server-1a", "ready"),
                        attachment("server-1b", "ready")
                    )
            )
        val ready = requireNotNull(allFinished.readyContextOrNull())

        assertEquals(
            listOf("server-ready", "server-1a", "server-1b", "server-2"),
            ready.attachmentIds
        )
        assertEquals(ready.attachmentIds, ready.attachments.map { it.id })
        assertEquals("body", ready.body)
        assertTrue(ready.asTask)
        assertEquals("random-1", ready.randomId)
    }

    @Test
    fun unknownAndDuplicateCompletionsCannotChangeBoundSnapshot() {
        val send =
            newThreadDeferredAttachmentSend(
                localMessageId = "local-message-1",
                context = context(listOf(attachment("picked-local-1", "uploading"))),
                timestampLabel = "Now",
                generation = 7L,
                startedFromComposer = false
            )

        assertSame(
            send,
            send.recordUploadSuccess(
                localAttachmentId = "new-draft-upload",
                attachments = listOf(attachment("server-new", "ready"))
            )
        )
        val completed =
            send.recordUploadSuccess(
                localAttachmentId = "picked-local-1",
                attachments = listOf(attachment("server-original", "ready"))
            )
        assertSame(
            completed,
            completed.recordUploadSuccess(
                localAttachmentId = "picked-local-1",
                attachments = listOf(attachment("server-late-duplicate", "ready"))
            )
        )
        assertEquals(listOf("server-original"), completed.readyContextOrNull()?.attachmentIds)
        assertFalse(completed.startedFromComposer)
    }

    @Test
    fun staleOrTerminalUploadAttemptCannotPublishLateSuccess() {
        val firstAdmission = ThreadAttachmentUploadAttemptFence().begin("picked-local-1")
        assertTrue(firstAdmission.fence.accepts("picked-local-1", firstAdmission.attemptToken))

        val failed = firstAdmission.fence.terminate(setOf("picked-local-1"))
        assertFalse(failed.accepts("picked-local-1", firstAdmission.attemptToken))

        val retryAdmission = failed.begin("picked-local-1")
        assertTrue(retryAdmission.fence.accepts("picked-local-1", retryAdmission.attemptToken))
        assertFalse(retryAdmission.fence.accepts("picked-local-1", firstAdmission.attemptToken))

        val completed = retryAdmission.fence.complete("picked-local-1", retryAdmission.attemptToken)
        assertFalse(completed.accepts("picked-local-1", retryAdmission.attemptToken))
    }

    @Test
    fun waitingAttachmentsRemainAnActiveCancelableLocalSendState() {
        assertTrue(ThreadMessageDeliverySending.isActiveThreadLocalSendDelivery())
        assertTrue(ThreadMessageDeliveryWaitingAttachments.isActiveThreadLocalSendDelivery())
        assertFalse(ThreadMessageDeliveryFailed.isActiveThreadLocalSendDelivery())
        assertFalse(ThreadMessageDeliverySent.isActiveThreadLocalSendDelivery())
    }
}
