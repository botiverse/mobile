package build.raft.app

import build.raft.app.resources.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class AttachmentOpenSemanticResultTest {
    private val localizedTerms =
        RaftAttachmentOpenResultTerms(
            downloadStopped = "下载已停止",
            unableToDownloadAttachment = "无法下载附件",
            noAppCanOpenFile = "没有应用可以打开此文件",
            storageUnavailable = "存储空间不可用",
            downloadFailed = "下载失败",
            unableToOpenAttachment = "无法打开附件"
        )

    @Test
    fun nativeOpenStatusesResolveToSharedLocalizedCopy() {
        assertNull(raftAttachmentOpenResultMessage("opened", localizedTerms))
        assertNull(raftAttachmentOpenResultMessage("", localizedTerms))
        assertNull(raftAttachmentOpenResultMessage("save_cancelled", localizedTerms))
        assertEquals("下载已停止", raftAttachmentOpenResultMessage("cancelled", localizedTerms))
        assertEquals("无法下载附件", raftAttachmentOpenResultMessage("missing", localizedTerms))
        assertEquals("没有应用可以打开此文件", raftAttachmentOpenResultMessage("no_app", localizedTerms))
        assertEquals("存储空间不可用", raftAttachmentOpenResultMessage("storage_failed", localizedTerms))
        assertEquals("下载失败", raftAttachmentOpenResultMessage("download_failed", localizedTerms))
        assertEquals("无法打开附件", raftAttachmentOpenResultMessage("failed", localizedTerms))
        assertEquals("无法打开附件", raftAttachmentOpenResultMessage("unexpected", localizedTerms))
    }

    @Test
    fun apkInstallHandoffsAreSilentAndOnlyARealFailureToasts() {
        assertNull(raftAttachmentOpenResultMessage("install_permission_required", localizedTerms))
        assertNull(raftAttachmentOpenResultMessage("install_via_files", localizedTerms))
        assertEquals("无法打开附件", raftAttachmentOpenResultMessage("install_unsupported", localizedTerms))
        listOf("install_permission_required", "install_via_files", "install_unsupported").forEach { status ->
            assertEquals(status, raftAttachmentOpenDiagnosticStatus(status))
        }
        assertEquals("opened", raftThreadAttachmentOpenStatus("install_permission_required"))
        assertEquals("opened", raftThreadAttachmentOpenStatus("install_via_files"))
        assertEquals("failed", raftThreadAttachmentOpenStatus("install_unsupported"))
    }

    @Test
    fun diagnosticStatusAllowsOnlyFixedCategories() {
        assertEquals("opened", raftAttachmentOpenDiagnosticStatus(" OPENED "))
        assertEquals("failed", raftAttachmentOpenDiagnosticStatus("failed"))
        assertEquals("download_failed", raftAttachmentOpenDiagnosticStatus("download_failed"))
        assertEquals("none", raftAttachmentOpenDiagnosticStatus(null))
        assertEquals("none", raftAttachmentOpenDiagnosticStatus(""))
        assertEquals("other", raftAttachmentOpenDiagnosticStatus("private-upstream-detail"))
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
            raftFilePreviewExternalOpenRejectionReason(
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
            traceRaftFilePreviewExternalOpenAdmission(
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
            traceRaftFilePreviewExternalOpenAdmission(
                rejectionReason = null,
                policy = RaftFilePreviewExternalOpenPolicy(requirePublicDownload = false, trackProgress = true),
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
        val policy = RaftFilePreviewExternalOpenPolicy(requirePublicDownload = false, trackProgress = true)
        val missingTrace = mutableListOf<String>()
        val missing =
            runRaftFilePreviewExternalOpenTraceFlow(
                policy = policy,
                resolveUrl = { null },
                onUrlResolved = { error("must not resolve") },
                openHost = { error("must not open") },
                statusOf = { null },
                trace = missingTrace::add
            )
        assertEquals(RaftFilePreviewExternalOpenTraceResult.UrlResolutionFailed, missing)
        assertEquals(
            listOf(
                "file_preview_open stage=url_resolution_failed committedRequired=false trackProgress=true"
            ),
            missingTrace
        )

        val events = mutableListOf<String>()
        val completed =
            runRaftFilePreviewExternalOpenTraceFlow(
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
                errorKindOf = { "http_status" },
                trace = { events += it }
            )
        assertEquals(RaftFilePreviewExternalOpenTraceResult.HostTerminal("failed"), completed)
        assertEquals(
            listOf(
                "resolve",
                "resolved",
                "file_preview_open stage=host_handoff committedRequired=false trackProgress=true",
                "host",
                "file_preview_open stage=host_terminal status=failed errorKind=http_status"
            ),
            events
        )

        val publicEvents = mutableListOf<String>()
        val publicOffline =
            runRaftFilePreviewExternalOpenTraceFlow(
                policy = RaftFilePreviewExternalOpenPolicy(requirePublicDownload = true, trackProgress = false),
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
        assertEquals(RaftFilePreviewExternalOpenTraceResult.HostTerminal("opened"), publicOffline)
        assertEquals(
            listOf(
                "resolve",
                "resolved:",
                "file_preview_open stage=host_handoff committedRequired=true trackProgress=false",
                "host:",
                "file_preview_open stage=host_terminal status=opened errorKind=none"
            ),
            publicEvents
        )
    }

    @Test
    fun hostErrorKindIsLoggedOnlyAsAShortIdentifier() {
        assertEquals("none", raftAttachmentOpenDiagnosticErrorKind(null))
        assertEquals("none", raftAttachmentOpenDiagnosticErrorKind(" "))
        assertEquals("storage_prepare", raftAttachmentOpenDiagnosticErrorKind("Storage_Prepare"))
        assertEquals("other", raftAttachmentOpenDiagnosticErrorKind("https://r2.example/x?sig=abc"))
        assertEquals("other", raftAttachmentOpenDiagnosticErrorKind("a".repeat(41)))
    }

    @Test
    fun materializationFailureLeavesExternalOpenRetryAdmissionAvailable() {
        assertEquals("无法下载附件", raftAttachmentOpenResultMessage("missing", localizedTerms))
        assertTrue(raftFilePreviewCanStartExternalOperation("failed"))
        assertFalse(raftFilePreviewCanStartExternalOperation("opening"))
        assertFalse(raftFilePreviewCanStartExternalOperation("downloading"))
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
            "cancelled" to Res.string.file_preview_download_stopped,
            "missing" to Res.string.file_preview_error_download_attachment,
            "no_app" to Res.string.file_preview_error_no_app,
            "storage_failed" to Res.string.file_preview_error_storage_unavailable,
            "download_failed" to Res.string.file_preview_download_failed,
            "failed" to Res.string.file_preview_error_open_attachment
        ).forEach { (status, expectedResource) ->
            assertEquals(
                expectedResource,
                ThreadAttachmentOpenUiState(status = status).threadAttachmentTerminalStatusResource()
            )
        }
        assertNotEquals(
            Res.string.file_preview_download_failed,
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
            assertEquals(status, raftThreadAttachmentOpenStatus(status))
        }
        assertEquals("failed", raftThreadAttachmentOpenStatus("failed"))
        assertEquals("failed", raftThreadAttachmentOpenStatus(""))
        assertEquals("failed", raftThreadAttachmentOpenStatus("native english detail"))
    }
}
