package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task #75: a tap on a message attachment card opens a page, never a download. Files with an
 * in-app preview open it; any other file opens its file page, which offers Download, then Open /
 * Install and Share once the file is on the device.
 */
class ThreadAttachmentFileDetailTest {
    private val actionTerms =
        RaftFilePreviewActionTerms(download = "Download", install = "Install", open = "Open", openWith = "Open with")

    @Test
    fun tapRoutesFilesWithoutAPreviewToTheFilePageAndOthersToTheirPreview() {
        val cases =
            listOf(
                Triple("app-release.apk", "application/vnd.android.package-archive", RaftThreadAttachmentOpenRoute.FileDetail),
                Triple("app-release.apk", "", RaftThreadAttachmentOpenRoute.FileDetail),
                Triple("bundle.zip", "application/zip", RaftThreadAttachmentOpenRoute.FileDetail),
                Triple("spec.pdf", "application/pdf", RaftThreadAttachmentOpenRoute.FileDetail),
                Triple("blob.bin", "application/octet-stream", RaftThreadAttachmentOpenRoute.FileDetail),
                Triple("photo.png", "image/png", RaftThreadAttachmentOpenRoute.Preview("image")),
                Triple("README.md", "text/markdown", RaftThreadAttachmentOpenRoute.Preview("markdown")),
                Triple("notes.txt", "text/plain", RaftThreadAttachmentOpenRoute.Preview("text")),
                Triple("page.html", "text/html", RaftThreadAttachmentOpenRoute.Preview("text")),
                Triple("clip.mp4", "video/mp4", RaftThreadAttachmentOpenRoute.Preview("video")),
                Triple("voice.mp3", "audio/mpeg", RaftThreadAttachmentOpenRoute.Preview("audio")),
                Triple(
                    "sheet.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    RaftThreadAttachmentOpenRoute.Preview(RaftThreadAttachmentFileDetailKind)
                )
            )
        cases.forEach { (name, mimeType, expected) ->
            val route = RaftThreadAttachmentItem(id = "att-1", name = name, mimeType = mimeType).threadAttachmentOpenRoute()
            assertEquals(expected, route, "$name ($mimeType)")
        }
        assertEquals("file", RaftThreadAttachmentOpenRoute.FileDetail.requestedPreviewKind)
        assertEquals("markdown", RaftThreadAttachmentOpenRoute.Preview("markdown").requestedPreviewKind)
    }

    @Test
    fun fileDetailRouteCarriesOnlyTheAttachmentIdentity() {
        val apk = RaftThreadAttachmentItem(id = "att-apk", name = "app.apk", mimeType = "application/vnd.android.package-archive")
        val payload = apk.toThreadAttachmentLazyPreviewPayload(kind = apk.threadAttachmentOpenRoute().requestedPreviewKind)
        val launch = raftFilePreviewLaunchDescriptor(sessionId = "s-1", payload = payload)

        assertEquals("loading", payload.kind)
        assertEquals("file", payload.requestedKind)
        assertEquals(RaftFilePreviewRouteThreadAttachment, launch.routeKind)
        assertEquals("att-apk", launch.attachmentId)
        assertEquals("file", launch.previewKind)
    }

    @Test
    fun alreadyDownloadedFileShowsOpenOrInstallAndShareDirectly() {
        val downloaded = RaftFilePreviewPublicDownloadState.Downloaded(generation = 3L, sizeBytes = 4_096L, persistedUri = "content://downloads/7")
        val state = raftFilePreviewDownloadUiState(downloaded, downloadStatus = "idle")

        assertEquals(RaftFilePreviewDownloadUiState.Downloaded, state)
        assertTrue(raftFilePreviewShowsShareAction(canOpenWith = true, downloadState = state))
        assertEquals(
            "Install",
            raftFilePreviewPrimaryActionLabel(RaftFilePreviewPayload(externalFileName = "app.apk"), state, actionTerms)
        )
        assertEquals(
            "Open",
            raftFilePreviewPrimaryActionLabel(RaftFilePreviewPayload(externalFileName = "bundle.zip"), state, actionTerms)
        )
    }

    @Test
    fun shareWaitsForTheFileAndDownloadComesFirst() {
        val notDownloaded = raftFilePreviewDownloadUiState(RaftFilePreviewPublicDownloadState.NotDownloaded, downloadStatus = "idle")
        assertEquals(RaftFilePreviewDownloadUiState.NotDownloaded, notDownloaded)
        assertFalse(raftFilePreviewShowsShareAction(canOpenWith = true, downloadState = notDownloaded))
        assertEquals(
            "Download",
            raftFilePreviewPrimaryActionLabel(RaftFilePreviewPayload(externalFileName = "app.apk"), notDownloaded, actionTerms)
        )

        listOf("downloading", "paused").forEach { status ->
            val running = raftFilePreviewDownloadUiState(RaftFilePreviewPublicDownloadState.NotDownloaded, downloadStatus = status)
            assertEquals(RaftFilePreviewDownloadUiState.Downloading, running, status)
            assertFalse(raftFilePreviewShowsShareAction(canOpenWith = true, downloadState = running), status)
            assertNull(raftFilePreviewPrimaryActionLabel(RaftFilePreviewPayload(externalFileName = "app.apk"), running, actionTerms), status)
        }

        // Still checking the device copy: neither Download nor Share is claimed yet.
        val checking = raftFilePreviewDownloadUiState(RaftFilePreviewPublicDownloadState.Checking, downloadStatus = "idle")
        assertNull(checking)
        assertFalse(raftFilePreviewShowsShareAction(canOpenWith = true, downloadState = checking))
        assertFalse(
            raftFilePreviewShowsShareAction(canOpenWith = false, downloadState = RaftFilePreviewDownloadUiState.Downloaded)
        )
    }
}
