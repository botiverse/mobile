package build.raft.app

import build.raft.app.data.*
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

    @Test fun realByteCountsProduceBoundedPercent() {
        assertEquals(25, payload(100).toSharedUpdateDownloadProgress()?.percent)
        assertEquals(100, payload(100, 101).toSharedUpdateDownloadProgress()?.percent)
        assertEquals(0, payload(100, -1).toSharedUpdateDownloadProgress()?.percent)
        assertEquals(100, payload(Long.MAX_VALUE, Long.MAX_VALUE).toSharedUpdateDownloadProgress()?.percent)
    }

    @Test fun unknownLengthDoesNotInventPercent() {
        for (total in listOf(0L, -1L)) {
            val progress = assertNotNull(payload(total).toSharedUpdateDownloadProgress())
            assertNull(progress.percent)
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
