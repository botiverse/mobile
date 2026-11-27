package build.raft.app

import build.raft.app.resources.*
import build.raft.app.thread.ThreadAttachmentOpenUiState
import build.raft.app.thread.raftThreadAttachmentOpenStatus
import build.raft.app.thread.threadAttachmentIsOpening
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun threadOpenPreservesKnownSemanticStatusesAndFailsClosedOtherwise() {
        listOf("opened", "cancelled", "save_cancelled", "missing", "no_app", "storage_failed", "download_failed").forEach { status ->
            assertEquals(status, raftThreadAttachmentOpenStatus(status))
        }
        assertEquals("failed", raftThreadAttachmentOpenStatus("failed"))
        assertEquals("failed", raftThreadAttachmentOpenStatus(""))
        assertEquals("failed", raftThreadAttachmentOpenStatus("native english detail"))
    }
}
