package build.raft.app

import build.raft.app.base.BridgeAttachmentDownloadProgress
import build.raft.app.base.BridgeAttachmentDownloadIdentity
import build.raft.app.base.bridgeAttachmentDownloadWireId
import build.raft.app.base.toLogicalDownloadProgress
import build.raft.app.base.toBridgeAttachmentDownloadProgress
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SlockFilePreviewProgressMailboxTest {
    private val englishDownloadTerms =
        SlockFilePreviewDownloadStatusTerms(
            downloading = "Downloading",
            paused = "Paused",
            saving = "Saving"
        )

    @Test
    fun offThreadOfferDoesNotCommitStateUntilConsumerReceives() = runBlocking {
        val mailbox = SlockFilePreviewProgressMailbox()
        val event = progressEvent(progress(downloadedBytes = 69_765L, totalBytes = 48_535_109L))
        var state = SlockFilePreviewProgressState()

        withContext(Dispatchers.Default) {
            assertTrue(mailbox.offer(event.operation, event.progress))
        }

        assertNull(state.progress)
        state =
            reduceSlockFilePreviewProgressEvent(
                current = state,
                event = requireNotNull(mailbox.nextOrNull()),
                currentOperationId = event.operation.operationId,
                expectedDownloadId = event.progress.downloadId,
                commitAllowed = { _, _ -> true }
            )
        assertEquals(69_765L, state.progress?.downloadedBytes)
        mailbox.close()
    }

    @Test
    fun knownDownloadManagerTotalSurvivesMailboxAndRendersDeterminatePercent() = runBlocking {
        val mailbox = SlockFilePreviewProgressMailbox()
        val owner = progressEvent(progress(downloadedBytes = 0L, totalBytes = null)).operation.owner
        val logicalDownloadId = "download-1"
        val wireDownloadId = bridgeAttachmentDownloadWireId(owner, logicalDownloadId)
        val parsed =
            JSONObject(
                """{"downloadId":"$wireDownloadId","attemptToken":"attempt-1","recordVersion":2,"sequence":1,"phase":"running","downloadedBytes":69765,"totalBytes":48535109,"percent":1}"""
            ).toBridgeAttachmentDownloadProgress().toLogicalDownloadProgress(
                BridgeAttachmentDownloadIdentity(logicalId = logicalDownloadId, wireId = wireDownloadId)
            )
        val event = progressEvent(parsed)
        mailbox.offer(event.operation, event.progress)

        var state =
            reduceSlockFilePreviewProgressEvent(
                current = SlockFilePreviewProgressState(),
                event = requireNotNull(mailbox.nextOrNull()),
                currentOperationId = event.operation.operationId,
                expectedDownloadId = logicalDownloadId,
                commitAllowed = { _, _ -> true }
            )

        assertEquals(48_535_109L, state.progress?.totalBytes)
        assertEquals(1, state.progress?.percent)
        assertEquals(
            SlockFilePreviewDownloadPresentation(
                statusLabel = "Downloading 1%",
                inProgress = true,
                paused = false,
                progressPercent = 1
            ),
            slockFilePreviewDownloadPresentation(
                status = "downloading",
                failureMessage = "",
                failureFallback = "Download failed",
                progress = state.progress,
                terms = englishDownloadTerms
            )
        )
        val advancing =
            parsed.copy(
                sequence = 2L,
                downloadedBytes = 24_267_554L,
                percent = 49
            )
        mailbox.offer(event.operation, advancing)
        state =
            reduceSlockFilePreviewProgressEvent(
                current = state,
                event = requireNotNull(mailbox.nextOrNull()),
                currentOperationId = event.operation.operationId,
                expectedDownloadId = logicalDownloadId,
                commitAllowed = { _, _ -> true }
            )
        assertEquals(24_267_554L, state.progress?.downloadedBytes)
        assertEquals(49, state.progress?.percent)
        assertEquals(
            SlockFilePreviewDownloadPresentation(
                statusLabel = "Downloading 49%",
                inProgress = true,
                paused = false,
                progressPercent = 49
            ),
            slockFilePreviewDownloadPresentation(
                status = "downloading",
                failureMessage = "",
                failureFallback = "Download failed",
                progress = state.progress,
                terms = englishDownloadTerms
            )
        )
        mailbox.close()
    }

    @Test
    fun unknownTotalStillRendersAdvancingDownloadedBytes() {
        val label =
            slockFilePreviewDownloadStatusLabel(
                progress(downloadedBytes = 69_765L, totalBytes = null),
                englishDownloadTerms
            )

        assertEquals("Downloading 68 KB", label)
    }

    @Test
    fun operationAndOwnerGenerationFencesRejectStaleMailboxEvents() {
        val currentProgress = progress(sequence = 2L, downloadedBytes = 200L, totalBytes = 1_000L, percent = 20)
        val current =
            SlockFilePreviewProgressState(
                progress = currentProgress,
                attemptToken = currentProgress.attemptToken,
                recordVersion = currentProgress.recordVersion
            )
        val staleOwnerEvent =
            progressEvent(
                progress = currentProgress.copy(sequence = 3L, downloadedBytes = 300L, percent = 30),
                generation = 1L
            )
        val wrongOperationEvent =
            progressEvent(
                progress = currentProgress.copy(sequence = 3L, downloadedBytes = 300L, percent = 30),
                generation = 2L
            ).let { event ->
                event.copy(operation = event.operation.copy(operationId = "operation-old"))
            }
        val fence: (AttachmentOperationToken, String) -> Boolean = { token, operationId ->
            token.operationId == operationId && token.owner.generation == 2L
        }

        assertSame(
            current,
            reduceSlockFilePreviewProgressEvent(
                current,
                staleOwnerEvent,
                currentOperationId = "operation-current",
                expectedDownloadId = "download-1",
                commitAllowed = fence
            )
        )
        assertSame(
            current,
            reduceSlockFilePreviewProgressEvent(
                current,
                wrongOperationEvent,
                currentOperationId = "operation-current",
                expectedDownloadId = "download-1",
                commitAllowed = fence
            )
        )
    }

    @Test
    fun closePreservesBufferedTerminalEventForConsumer() = runBlocking {
        val mailbox = SlockFilePreviewProgressMailbox()
        val running = progressEvent(progress(sequence = 1L, downloadedBytes = 100L, totalBytes = 1_000L, percent = 10))
        val complete =
            progressEvent(
                progress(
                    sequence = 2L,
                    phase = "successful",
                    status = "complete",
                    downloadedBytes = 1_000L,
                    totalBytes = 1_000L,
                    percent = 100
                )
            )

        mailbox.offer(running.operation, running.progress)
        mailbox.offer(complete.operation, complete.progress)
        mailbox.close()

        assertEquals(running, mailbox.nextOrNull())
        assertEquals(complete, mailbox.nextOrNull())
        assertNull(mailbox.nextOrNull())
    }

    private fun progress(
        sequence: Long = 1L,
        phase: String = "running",
        status: String = "progress",
        downloadedBytes: Long,
        totalBytes: Long?,
        percent: Int? = null
    ) = BridgeAttachmentDownloadProgress(
        downloadId = "download-1",
        attemptToken = "attempt-1",
        recordVersion = 2L,
        sequence = sequence,
        phase = phase,
        status = status,
        percent = percent,
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes
    )

    private fun progressEvent(
        progress: BridgeAttachmentDownloadProgress,
        generation: Long = 2L
    ) = SlockFilePreviewProgressEvent(
        operation =
            AttachmentOperationToken(
                owner =
                    AttachmentOwnerLease(
                        shellInstanceId = "shell-1",
                        scopeId = "scope-$generation",
                        authEpoch = 1L,
                        principalKey = AttachmentPrincipalKey("a".repeat(64)),
                        generation = generation
                    ),
                operationId = "operation-current",
                downloadId = "download-1"
            ),
        progress = progress
    )
}
