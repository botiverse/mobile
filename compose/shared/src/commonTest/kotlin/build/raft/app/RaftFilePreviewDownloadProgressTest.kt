package build.raft.app

import build.raft.app.base.BridgeAttachmentDownloadProgress
import build.raft.app.base.raftAttachmentHostDownloadId
import build.raft.app.base.toBridgeAttachmentDownloadProgress
import build.raft.app.compat.json.JSONObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** The file page's download progress: the host's samples reach it, and a fast download is seen. */
@OptIn(ExperimentalCoroutinesApi::class)
class RaftFilePreviewDownloadProgressTest {
    private val owner =
        AttachmentOwnerLease(
            shellInstanceId = "shell-1",
            scopeId = "scope-1",
            authEpoch = 1L,
            principalKey = AttachmentPrincipalKey("a".repeat(64)),
            generation = 1L
        )
    private val activeDownloadId = "attempt-1:thread_attachment:att-1:archive.zip"

    /** A host `progress` event: bytes plus the percent its meter computed for them. */
    private fun hostSample(downloadId: String, sequence: Long, phase: String, downloadedBytes: Long): BridgeAttachmentDownloadProgress =
        JSONObject().apply {
            put("status", "progress")
            put("downloadId", downloadId)
            put("attemptToken", "token-1")
            put("sequence", sequence.toString())
            put("phase", phase)
            put("downloadedBytes", downloadedBytes.toString())
            put("totalBytes", "3000000")
            if (downloadedBytes > 0L) put("percent", (downloadedBytes * 100L / 3_000_000L).toString())
        }.toBridgeAttachmentDownloadProgress()

    @Test
    fun pageProgressFollowsTheHostSamplesOfItsOwnDownload() {
        // Regression (1.15.3): the host names the download by its principal-scoped id; the page
        // fenced on its bare id and dropped every sample, so the bar never moved.
        val hostId = raftAttachmentHostDownloadId(owner, activeDownloadId)
        var progress: BridgeAttachmentDownloadProgress? =
            RaftFilePreviewInitialDownloadProgress.copy(downloadId = activeDownloadId)
        for (sample in listOf(hostSample(hostId, 1L, "response_wait", 0L), hostSample(hostId, 2L, "receiving", 1_500_000L))) {
            progress = raftFilePreviewReducePageProgress(progress, sample, owner, activeDownloadId)
        }

        val view = raftFilePreviewDownloadView(status = "downloading", progress = progress, errorMessage = null)
        assertEquals(RaftFilePreviewDownloadPhase.Active, view.phase)
        assertEquals(50, view.percent)
        assertEquals(1_500_000L, view.downloadedBytes)
        assertEquals(3_000_000L, view.totalBytes)
    }

    @Test
    fun pageProgressIgnoresSamplesOfAnotherDownload() {
        val current = hostSample(raftAttachmentHostDownloadId(owner, activeDownloadId), 2L, "receiving", 100L)
        val foreign =
            listOf(
                hostSample(activeDownloadId, 3L, "receiving", 200L),
                hostSample(raftAttachmentHostDownloadId(owner.copy(principalKey = AttachmentPrincipalKey("b".repeat(64))), activeDownloadId), 3L, "receiving", 200L),
                hostSample(raftAttachmentHostDownloadId(owner, "attempt-2:other"), 3L, "receiving", 200L)
            )
        for (sample in foreign) {
            assertSame(current, raftFilePreviewReducePageProgress(current, sample, owner, activeDownloadId), "sample=${sample.downloadId}")
        }
    }

    private val halfway =
        RaftFilePreviewDownloadView(
            phase = RaftFilePreviewDownloadPhase.Active,
            percent = 50,
            downloadedBytes = 1_500_000L,
            totalBytes = 3_000_000L,
            bytesPerSecond = 9_000_000L
        )
    private val idle = RaftFilePreviewDownloadView(phase = RaftFilePreviewDownloadPhase.Idle)

    @Test
    fun thePanelNeverFillsTheBarItself() = runTest {
        val frames = MutableStateFlow(RaftFilePreviewDownloadHoldInput(halfway))
        val shown = mutableListOf<RaftFilePreviewDownloadView>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            frames.raftHoldingDownloadPanel(minVisibleMillis = 600L, timeSource = testScheduler.timeSource).collect { shown += it }
        }
        advanceTimeBy(100L)
        // Idle without a completed transfer (no fact that the file was committed): no 100%.
        frames.value = RaftFilePreviewDownloadHoldInput(idle)
        runCurrent()
        assertEquals(listOf(halfway, halfway), shown)
    }
}
