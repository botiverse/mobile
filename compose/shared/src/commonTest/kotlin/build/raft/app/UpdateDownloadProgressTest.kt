package build.raft.app

import build.raft.app.data.*
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.test.*

class UpdateDownloadProgressTest {
    private fun payload(total: Long, downloaded: Long = 25L) = JSONObject()
        .put("status", "ok").put("token", "42").put("phase", "downloading")
        .put("totalBytes", total).put("downloadedBytes", downloaded)

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
            assertFalse(assertNotNull(payload(100).put("phase", phase).toSharedUpdateDownloadProgress()).active)
        }
        assertNull(payload(100).put("status", "unavailable").toSharedUpdateDownloadProgress())
        assertNull(payload(100).put("token", "").toSharedUpdateDownloadProgress())
        assertNull(payload(100).put("phase", "alien").toSharedUpdateDownloadProgress())
        assertTrue(assertNotNull(payload(100).put("phase", "paused").toSharedUpdateDownloadProgress()).active)
    }

    @Test fun failedDownloadOffersRetry() {
        val snapshot = SharedSettingsUpdateCheckSnapshot(supported = true, updateAvailable = true,
            statusLabel = "Failed", actionLabel = "Download", errorCode = "settings_hands_update_download_failed")
        assertEquals("Retry", appUpdatePrimaryActionLabel(snapshot, false))
        assertTrue(appUpdatePrimaryActionEnabled(snapshot, false, false, "failed"))
    }

    @Test fun byteLabelsStayReadable() {
        assertEquals("0 B", appUpdateBytesLabel(-1))
        assertEquals("512 B", appUpdateBytesLabel(512))
        assertEquals("1.5 KB", appUpdateBytesLabel(1536))
        assertEquals("50.0 MB", appUpdateBytesLabel(50L * 1024 * 1024))
    }
}
