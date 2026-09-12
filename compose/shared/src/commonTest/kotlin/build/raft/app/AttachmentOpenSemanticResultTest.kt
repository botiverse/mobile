package build.raft.app

import build.raft.app.resources.MR
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class AttachmentOpenSemanticResultTest {
    private val localizedTerms =
        SlockAttachmentOpenResultTerms(
            downloadStopped = "下载已停止",
            unableToDownloadAttachment = "无法下载附件",
            noAppCanOpenFile = "没有应用可以打开此文件",
            storageUnavailable = "存储空间不可用",
            downloadFailed = "下载失败",
            unableToOpenAttachment = "无法打开附件"
        )

    @Test
    fun nativeOpenStatusesResolveToSharedLocalizedCopy() {
        assertNull(slockAttachmentOpenResultMessage("opened", localizedTerms))
        assertNull(slockAttachmentOpenResultMessage("", localizedTerms))
        assertNull(slockAttachmentOpenResultMessage("save_cancelled", localizedTerms))
        assertEquals("下载已停止", slockAttachmentOpenResultMessage("cancelled", localizedTerms))
        assertEquals("无法下载附件", slockAttachmentOpenResultMessage("missing", localizedTerms))
        assertEquals("没有应用可以打开此文件", slockAttachmentOpenResultMessage("no_app", localizedTerms))
        assertEquals("存储空间不可用", slockAttachmentOpenResultMessage("storage_failed", localizedTerms))
        assertEquals("下载失败", slockAttachmentOpenResultMessage("download_failed", localizedTerms))
        assertEquals("无法打开附件", slockAttachmentOpenResultMessage("failed", localizedTerms))
        assertEquals("无法打开附件", slockAttachmentOpenResultMessage("unexpected", localizedTerms))
    }

    @Test
    fun diagnosticStatusAllowsOnlyFixedCategories() {
        assertEquals("opened", slockAttachmentOpenDiagnosticStatus(" OPENED "))
        assertEquals("failed", slockAttachmentOpenDiagnosticStatus("failed"))
        assertEquals("download_failed", slockAttachmentOpenDiagnosticStatus("download_failed"))
        assertEquals("none", slockAttachmentOpenDiagnosticStatus(null))
        assertEquals("none", slockAttachmentOpenDiagnosticStatus(""))
        assertEquals("other", slockAttachmentOpenDiagnosticStatus("private-upstream-detail"))
    }

    @Test
    fun externalOpenAdmissionReportsOneFixedRejectionReason() {
        fun reason(
            canOpenWith: Boolean = true,
            exportInProgress: Boolean = false,
            downloadStatus: String = "idle",
            hasOwner: Boolean = true,
            hasPolicy: Boolean = true
        ): String? =
            slockFilePreviewExternalOpenRejectionReason(
                canOpenWith,
                exportInProgress,
                downloadStatus,
                hasOwner,
                hasPolicy
            )

        assertEquals("unsupported", reason(canOpenWith = false))
        assertEquals("export_busy", reason(exportInProgress = true))
        assertEquals("operation_busy", reason(downloadStatus = "downloading"))
        assertEquals("owner_missing", reason(hasOwner = false))
        assertEquals("state_checking", reason(hasPolicy = false))
        assertNull(reason())
    }

    @Test
    fun productionAdmissionTraceEmitsExactlyOneTerminalDecision() {
        val rejected = mutableListOf<String>()
        assertFalse(
            traceSlockFilePreviewExternalOpenAdmission(
                rejectionReason = "operation_busy",
                policy = null,
                trace = rejected::add
            )
        )
        assertEquals(
            listOf("file_preview_open stage=rejected reason=operation_busy"),
            rejected
        )

        val admitted = mutableListOf<String>()
        assertTrue(
            traceSlockFilePreviewExternalOpenAdmission(
                rejectionReason = null,
                policy = SlockFilePreviewExternalOpenPolicy(requirePublicDownload = false, trackProgress = true),
                trace = admitted::add
            )
        )
        assertEquals(
            listOf(
                "file_preview_open stage=admitted committedRequired=false trackProgress=true"
            ),
            admitted
        )
    }

    @Test
    fun productionExternalOpenTraceCoversMissingAndHostTerminalInOrder() = runBlocking {
        val policy = SlockFilePreviewExternalOpenPolicy(requirePublicDownload = false, trackProgress = true)
        val missingTrace = mutableListOf<String>()
        val missing =
            runSlockFilePreviewExternalOpenTraceFlow(
                policy = policy,
                resolveUrl = { null },
                onUrlResolved = { error("must not resolve") },
                openHost = { error("must not open") },
                statusOf = { null },
                trace = missingTrace::add
            )
        assertEquals(SlockFilePreviewExternalOpenTraceResult.UrlResolutionFailed, missing)
        assertEquals(
            listOf(
                "file_preview_open stage=url_resolution_failed committedRequired=false trackProgress=true"
            ),
            missingTrace
        )

        val events = mutableListOf<String>()
        val completed =
            runSlockFilePreviewExternalOpenTraceFlow(
                policy = policy,
                resolveUrl = {
                    events += "resolve"
                    "https://example.invalid/attachment"
                },
                onUrlResolved = { events += "resolved" },
                openHost = {
                    events += "host"
                    "failed"
                },
                statusOf = { it },
                trace = { events += it }
            )
        assertEquals(SlockFilePreviewExternalOpenTraceResult.HostTerminal("failed"), completed)
        assertEquals(
            listOf(
                "resolve",
                "resolved",
                "file_preview_open stage=host_handoff committedRequired=false trackProgress=true",
                "host",
                "file_preview_open stage=host_terminal status=failed"
            ),
            events
        )

        val publicEvents = mutableListOf<String>()
        val publicOffline =
            runSlockFilePreviewExternalOpenTraceFlow(
                policy = SlockFilePreviewExternalOpenPolicy(requirePublicDownload = true, trackProgress = false),
                resolveUrl = {
                    publicEvents += "resolve"
                    ""
                },
                onUrlResolved = { publicEvents += "resolved:$it" },
                openHost = {
                    publicEvents += "host:$it"
                    "opened"
                },
                statusOf = { it },
                trace = { publicEvents += it }
            )
        assertEquals(SlockFilePreviewExternalOpenTraceResult.HostTerminal("opened"), publicOffline)
        assertEquals(
            listOf(
                "resolve",
                "resolved:",
                "file_preview_open stage=host_handoff committedRequired=true trackProgress=false",
                "host:",
                "file_preview_open stage=host_terminal status=opened"
            ),
            publicEvents
        )
    }

    @Test
    fun materializationFailureLeavesExternalOpenRetryAdmissionAvailable() {
        assertEquals("无法下载附件", slockAttachmentOpenResultMessage("missing", localizedTerms))
        assertTrue(slockFilePreviewCanStartExternalOperation("failed"))
        assertFalse(slockFilePreviewCanStartExternalOperation("opening"))
        assertFalse(slockFilePreviewCanStartExternalOperation("downloading"))
    }

    @Test
    fun threadAttachmentBlocksRepeatedTapOnlyWhileTheExactOpenIsActive() {
        assertTrue(ThreadAttachmentOpenUiState(status = "downloading").threadAttachmentIsOpening())
        assertTrue(ThreadAttachmentOpenUiState(status = "paused").threadAttachmentIsOpening())
        assertFalse(ThreadAttachmentOpenUiState(status = "missing").threadAttachmentIsOpening())
        assertFalse(ThreadAttachmentOpenUiState(status = "failed").threadAttachmentIsOpening())
    }

    @Test
    fun threadFileInlineStatusUsesOpenSpecificLocalizedResource() {
        mapOf(
            "cancelled" to MR.strings.file_preview_download_stopped,
            "missing" to MR.strings.file_preview_error_download_attachment,
            "no_app" to MR.strings.file_preview_error_no_app,
            "storage_failed" to MR.strings.file_preview_error_storage_unavailable,
            "download_failed" to MR.strings.file_preview_download_failed,
            "failed" to MR.strings.file_preview_error_open_attachment
        ).forEach { (status, expectedResource) ->
            assertEquals(
                expectedResource,
                ThreadAttachmentOpenUiState(status = status).threadAttachmentTerminalStatusResource()
            )
        }
        assertNotEquals(
            MR.strings.file_preview_download_failed,
            ThreadAttachmentOpenUiState(status = "failed").threadAttachmentTerminalStatusResource()
        )
        assertNull(ThreadAttachmentOpenUiState(status = "downloading").threadAttachmentTerminalStatusResource())
        assertNull(ThreadAttachmentOpenUiState(status = "paused").threadAttachmentTerminalStatusResource())
        assertNull(ThreadAttachmentOpenUiState(status = "opened").threadAttachmentTerminalStatusResource())
        assertNull(ThreadAttachmentOpenUiState(status = "unexpected").threadAttachmentTerminalStatusResource())
        assertNull(null.threadAttachmentTerminalStatusResource())
    }

    @Test
    fun threadOpenPreservesKnownSemanticStatusesAndFailsClosedOtherwise() {
        listOf("opened", "cancelled", "save_cancelled", "missing", "no_app", "storage_failed", "download_failed").forEach { status ->
            assertEquals(status, slockThreadAttachmentOpenStatus(status))
        }
        assertEquals("failed", slockThreadAttachmentOpenStatus("failed"))
        assertEquals("failed", slockThreadAttachmentOpenStatus(""))
        assertEquals("failed", slockThreadAttachmentOpenStatus("native english detail"))
    }
}
