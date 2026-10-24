package build.raft.app

import build.raft.app.data.*
import build.raft.app.transfer.RaftTransferMeter
import kotlin.test.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class UpdateDownloadProgressTest {
    private fun payload(total: Long, downloaded: Long = 25L) = buildJsonObject {
        put("status", "ok")
        put("token", "42")
        put("phase", "downloading")
        put("totalBytes", total)
        put("downloadedBytes", downloaded)
    }

    private fun observed(vararg samples: SharedUpdateDownloadProgress) =
        RaftTransferMeter().let { meter -> samples.map { meter.observe(it) }.last() }

    @Test fun realByteCountsProduceBoundedPercentAndHundredOnlyWhenReady() {
        assertEquals(25, observed(assertNotNull(payload(100).toSharedUpdateDownloadProgress())).percent)
        // Every byte in, the APK is not ready yet: 99.
        assertEquals(99, observed(assertNotNull(payload(100, 101).toSharedUpdateDownloadProgress())).percent)
        assertNull(observed(assertNotNull(payload(100, -1).toSharedUpdateDownloadProgress())).percent)
        assertEquals(99, observed(assertNotNull(payload(Long.MAX_VALUE, Long.MAX_VALUE).toSharedUpdateDownloadProgress())).percent)
        val ready = SharedUpdateDownloadProgress("42", 100, 100, "ready")
        assertEquals(100, observed(SharedUpdateDownloadProgress("42", 60, 100, "downloading"), ready).percent)
        // A paused poll keeps the bar where it was.
        val paused = observed(SharedUpdateDownloadProgress("42", 60, 100, "downloading"), SharedUpdateDownloadProgress("42", 60, 100, "paused"))
        assertEquals(60, paused.percent)
        assertEquals(build.raft.app.transfer.RaftTransferPhase.Paused, paused.phase)
    }

    @Test fun unknownLengthDoesNotInventPercent() {
        for (total in listOf(0L, -1L)) {
            val progress = assertNotNull(payload(total).toSharedUpdateDownloadProgress())
            assertNull(observed(progress).percent)
            assertEquals(25L, progress.downloadedBytes)
        }
    }

    @Test fun terminalAndInvalidRepliesDoNotContinuePolling() {
        for (phase in listOf("ready", "failed")) {
            assertFalse(assertNotNull(payload(100).withEntry("phase", phase).toSharedUpdateDownloadProgress()).active)
        }
        assertNull(payload(100).withEntry("status", "unavailable").toSharedUpdateDownloadProgress())
        assertNull(payload(100).withEntry("token", "").toSharedUpdateDownloadProgress())
        assertNull(payload(100).withEntry("phase", "alien").toSharedUpdateDownloadProgress())
        assertTrue(assertNotNull(payload(100).withEntry("phase", "paused").toSharedUpdateDownloadProgress()).active)
    }

    @Test fun failedDownloadOffersRetry() {
        val snapshot = SharedSettingsUpdateCheckSnapshot(supported = true, updateAvailable = true,
            statusLabel = "Failed", actionLabel = "Download", errorCode = "settings_hands_update_download_failed")
        assertEquals("Retry", appUpdatePrimaryActionLabel(snapshot, false))
        assertTrue(appUpdatePrimaryActionEnabled(snapshot, false, false, "failed"))
    }

    @Test fun localTerminalSurvivesFailedOfferRefresh() {
        val initial = SharedSettingsUpdateCheckSnapshot(supported = true, updateAvailable = true,
            statusLabel = "Downloading", actionLabel = "Downloading", downloadProgressToken = "42")
        for (phase in listOf("ready", "failed")) {
            val progress = SharedUpdateDownloadProgress("42", 100, 100, phase)
            val local = initial.withLocalDownloadTerminal(progress)
            val remoteFailure: DataResult<SharedSettingsUpdateCheckSnapshot> = DataResult.Error(ScreenError("network_error"))
            val result = remoteFailure.toAppUpdateResultProjection(local, "unavailable", englishErrorPresentationStringsForTest())
            val retained = assertNotNull(result.snapshot).withLocalDownloadTerminal(progress)
            assertTrue(appUpdatePrimaryActionEnabled(retained, false, false, result.errorMessage))
            assertEquals(if (phase == "ready") "Install" else "Retry", appUpdatePrimaryActionLabel(retained, false))
        }
    }

    @Test fun oldAttemptCannotOverrideNewDownload() {
        val current = SharedSettingsUpdateCheckSnapshot(downloadProgressToken = "new")
        assertEquals(current, current.withLocalDownloadTerminal(SharedUpdateDownloadProgress("old", 100, 100, "failed")))
    }
}
