package build.raft.app.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Which rows the window's download bar shows (the centre-driven flow: RaftDownloadCenterTest). */
class RaftDownloadBarTest {
    private fun row(id: String, status: RaftDownloadStatus, updatedAt: Long, total: Long? = 100L, done: Long = 0L) =
        RaftDownloadEntry(
            id = id,
            ownerKey = "owner",
            attachmentId = "att-$id",
            fileName = "$id.zip",
            status = status,
            downloadedBytes = done,
            totalBytes = total,
            localUri = "file:/d/$id.zip".takeIf { status == RaftDownloadStatus.Completed },
            createdAtMillis = updatedAt,
            updatedAtMillis = updatedAt,
        )

    @Test
    fun earlierSessionsCancelledAndClosedRowsShowNothing() {
        val restored = row("old", RaftDownloadStatus.Completed, updatedAt = 5)
        assertNull(raftDownloadBarState(listOf(restored), sinceMillis = 10), "a row from before this launch")
        assertNull(raftDownloadBarState(listOf(row("c", RaftDownloadStatus.Cancelled, 20)), sinceMillis = 10), "a stopped download")
        val saved = row("s", RaftDownloadStatus.Completed, 20)
        assertNull(raftDownloadBarState(listOf(saved), sinceMillis = 10, dismissed = mapOf("s" to 20L)), "closed")
        // The same row changing again (downloaded again) shows once more.
        assertEquals(
            RaftDownloadBarState.Saved("s", "s.zip", "file:/d/s.zip"),
            raftDownloadBarState(listOf(saved.copy(updatedAtMillis = 30)), sinceMillis = 10, dismissed = mapOf("s" to 20L)),
        )
    }

    @Test
    fun runningDownloadsWinOverFinishedOnesAndUnknownSizesHaveNoFraction() {
        val rows =
            listOf(
                row("done", RaftDownloadStatus.Completed, 40),
                row("p", RaftDownloadStatus.Paused, 30, total = 200, done = 50),
                row("u", RaftDownloadStatus.Downloading, 20, total = null, done = 10),
            )
        assertEquals(RaftDownloadBarState.Downloading(2, null, null, fraction = null), raftDownloadBarState(rows, sinceMillis = 0))
        assertEquals(
            RaftDownloadBarState.Downloading(1, "p", "p.zip", fraction = 0.25f),
            raftDownloadBarState(rows.filterNot { it.id == "u" }, sinceMillis = 0),
        )
    }
}
