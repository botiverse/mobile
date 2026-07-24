package build.raft.app

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReleaseNotesPageTest {
    @Test
    fun releaseNotesVersionCodeFromPageDataAcceptsOnlyDigits() {
        assertEquals(
            "1010000",
            releaseNotesVersionCodeFromPageData(JSONObject().put("versionCode", " 1010000 "))
        )
        assertEquals(
            "",
            releaseNotesVersionCodeFromPageData(JSONObject().put("versionCode", "1.1.0"))
        )
        assertEquals(
            "",
            releaseNotesVersionCodeFromPageData(JSONObject().put("versionCode", "1010000&lang=zh"))
        )
    }

    @Test
    fun sharedSettingsWebViewWaitsForBodyFrameBelowHeader() {
        assertFalse(settingsWebViewFrameIsReady(positionY = 0f, width = 1080, height = 2200))
        assertFalse(settingsWebViewFrameIsReady(positionY = 180f, width = 0, height = 2200))
        assertFalse(settingsWebViewFrameIsReady(positionY = 180f, width = 1080, height = 0))
        assertTrue(settingsWebViewFrameIsReady(positionY = 180f, width = 1080, height = 2200))
    }
}
