package build.raft.app

import build.raft.app.data.ScreenError
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SharedSettingsAboutSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals

class ReleaseNotesPageTest {
    @Test
    fun releaseNotesAboutTerminalFailureMapsToRetryableError() {
        assertEquals(
            ReleaseNotesAboutResolution.Loading,
            releaseNotesAboutResolution(ScreenLoadState.Loading)
        )
        assertEquals(
            ReleaseNotesAboutResolution.Ready("1070001"),
            releaseNotesAboutResolution(
                ScreenLoadState.Content(SharedSettingsAboutSnapshot(buildLabel = "1070001"))
            )
        )
        assertEquals(
            ReleaseNotesAboutResolution.Failure,
            releaseNotesAboutResolution(
                ScreenLoadState.Content(SharedSettingsAboutSnapshot(buildLabel = "1.7.0"))
            )
        )
        assertEquals(
            ReleaseNotesAboutResolution.Failure,
            releaseNotesAboutResolution(ScreenLoadState.Error(ScreenError(code = "network_error")))
        )
    }

    @Test
    fun releaseNotesUrlUsesHandsPublicHistoryRouteAndPlatformSlug() {
        assertEquals(
            "https://hands.build/notes/raft-android?version_code=1060000&lang=en",
            sharedSettingsReleaseNotesUrlForLocale(" 1060000 ", "en")
        )
        assertEquals(
            "https://hands.build/notes/raft-ios?version_code=1060000&lang=zh-CN",
            sharedSettingsReleaseNotesUrlForLocaleWithSlug("raft-ios", "1060000", "zh-CN")
        )
        assertEquals(
            "https://hands.build/notes/raft-ohos?version_code=1060000&lang=ja",
            sharedSettingsReleaseNotesUrlForLocaleWithSlug("raft-ohos", "1060000", "ja")
        )
    }

    @Test
    fun releaseNotesUrlNormalizesLocaleAndFailsClosedForInvalidVersion() {
        assertEquals(
            "https://hands.build/notes/raft-android?version_code=1060000&lang=zh-CN",
            sharedSettingsReleaseNotesUrlForLocale("1060000", " zh_CN.UTF-8 ")
        )
        assertEquals("", sharedSettingsReleaseNotesUrlForLocale("1.7.0", "en"))
        assertEquals("", sharedSettingsReleaseNotesUrlForLocale("", "en"))
    }
}
