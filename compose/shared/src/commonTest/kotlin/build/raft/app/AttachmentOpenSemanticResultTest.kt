package build.raft.app

import build.raft.app.resources.MR
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttachmentOpenSemanticResultTest {
    private val localizedTerms =
        SlockAttachmentOpenResultTerms(
            downloadStopped = "下载已停止",
            unableToDownloadAttachment = "无法下载附件",
            noAppCanOpenFile = "没有应用可以打开此文件",
            storageUnavailable = "存储空间不可用",
            unableToOpenAttachment = "无法打开附件"
        )

    @Test
    fun nativeOpenStatusesResolveToSharedLocalizedCopy() {
        assertNull(slockAttachmentOpenResultMessage("opened", localizedTerms))
        assertNull(slockAttachmentOpenResultMessage("", localizedTerms))
        assertEquals("下载已停止", slockAttachmentOpenResultMessage("cancelled", localizedTerms))
        assertEquals("无法下载附件", slockAttachmentOpenResultMessage("missing", localizedTerms))
        assertEquals("没有应用可以打开此文件", slockAttachmentOpenResultMessage("no_app", localizedTerms))
        assertEquals("存储空间不可用", slockAttachmentOpenResultMessage("storage_failed", localizedTerms))
        assertEquals("无法打开附件", slockAttachmentOpenResultMessage("failed", localizedTerms))
        assertEquals("无法打开附件", slockAttachmentOpenResultMessage("unexpected", localizedTerms))
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
        listOf("opened", "cancelled", "missing", "no_app", "storage_failed").forEach { status ->
            assertEquals(status, slockThreadAttachmentOpenStatus(status))
        }
        assertEquals("failed", slockThreadAttachmentOpenStatus("failed"))
        assertEquals("failed", slockThreadAttachmentOpenStatus(""))
        assertEquals("failed", slockThreadAttachmentOpenStatus("native english detail"))
    }
}
