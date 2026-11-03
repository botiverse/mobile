package build.raft.app

import build.raft.app.downloads.RaftDownloadEntry
import build.raft.app.downloads.RaftDownloadStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Compose 版: the file page's saved copy (Show in Finder / Open) and its words. */
class RaftFilePreviewSavedFileTest {
    private class FakeHost(var present: Set<String>) : RaftSavedFileHost {
        override val revealsInFinder = true

        override fun exists(uri: String) = uri in present

        override fun folderName(uri: String) = uri.substringBeforeLast('/').substringAfterLast('/')

        override suspend fun reveal(uri: String) = true

        override suspend fun open(uri: String) = true
    }

    private val uri = "file:/Users/a/Downloads/report.pdf"

    private fun entry(status: RaftDownloadStatus = RaftDownloadStatus.Completed, localUri: String? = uri) =
        RaftDownloadEntry(
            id = "e",
            ownerKey = "owner",
            attachmentId = "att",
            fileName = "report.pdf",
            status = status,
            localUri = localUri,
            createdAtMillis = 0L,
            updatedAtMillis = 0L,
        )

    @Test
    fun aFinishedDownloadStillOnDiskIsTheSavedCopyWithItsFolder() {
        assertEquals(
            RaftFilePreviewSavedFile(uri = uri, folderName = "Downloads"),
            raftFilePreviewSavedFile(entry(), downloading = false, host = FakeHost(setOf(uri))),
        )
    }

    @Test
    fun noSavedCopyOffersDownload() {
        val host = FakeHost(setOf(uri))
        // Each case: why the page offers Download again.
        val cases =
            listOf(
                "no file manager (Android / iOS)" to raftFilePreviewSavedFile(entry(), downloading = false, host = null),
                "a new download runs" to raftFilePreviewSavedFile(entry(), downloading = true, host = host),
                "never downloaded" to raftFilePreviewSavedFile(null, downloading = false, host = host),
                "the download failed" to raftFilePreviewSavedFile(entry(RaftDownloadStatus.Failed), downloading = false, host = host),
                "still downloading" to raftFilePreviewSavedFile(entry(RaftDownloadStatus.Downloading), downloading = false, host = host),
                "no location" to raftFilePreviewSavedFile(entry(localUri = " "), downloading = false, host = host),
                "moved or deleted" to raftFilePreviewSavedFile(entry(), downloading = false, host = FakeHost(emptySet())),
            )
        cases.forEach { (case, saved) -> assertNull(saved, case) }
    }

    @Test
    fun revealLabelAndSavedToast() {
        val terms = RaftFilePreviewSavedFileTerms("Show in Finder", "Show in folder", "Saved to $RaftSavedToFolderPlaceholder")
        assertEquals("Show in Finder", raftFilePreviewRevealActionLabel(revealsInFinder = true, terms))
        assertEquals("Show in folder", raftFilePreviewRevealActionLabel(revealsInFinder = false, terms))
        assertEquals("Saved to Downloads", raftFilePreviewSavedToFolderMessage("Downloads", terms))
        assertNull(raftFilePreviewSavedToFolderMessage(null, terms))
        assertNull(raftFilePreviewSavedToFolderMessage("", terms))
    }
}
