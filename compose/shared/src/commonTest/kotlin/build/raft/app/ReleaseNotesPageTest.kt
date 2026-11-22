package build.raft.app

import build.raft.app.data.AppLocale
import build.raft.app.data.ScreenError
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SharedSettingsAboutSnapshot
import build.raft.app.compat.json.JSONObject
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
    fun followingTheSystemLanguageTheNotesUrlStillNamesIt() {
        // Android reads the notes page through the app's document cache, whose request has no
        // Accept-Language: with "follow system" the URL itself must carry the system's language.
        assertEquals(
            "https://hands.build/notes/raft-ios?version_code=12800007&lang=zh-CN",
            sharedSettingsReleaseNotesUrlForLocaleWithSlug("raft-ios", "12800007", releaseNotesLocaleTag(AppLocale.System, "zh-Hans-CN"))
        )
        assertEquals("zh-CN", releaseNotesLocaleTag(AppLocale.System, "zh-CN"))
        assertEquals("zh-TW", releaseNotesLocaleTag(AppLocale.System, "zh-Hant-HK"))
        assertEquals("ja", releaseNotesLocaleTag(AppLocale.System, "ja-JP"))
        assertEquals("es", releaseNotesLocaleTag(AppLocale.System, "es-ES"))
        assertEquals("pt-BR", releaseNotesLocaleTag(AppLocale.System, "pt-BR"))
        // A language the app has no notes in, or none at all: English, as the page would fall back.
        assertEquals("en", releaseNotesLocaleTag(AppLocale.System, "fr-FR"))
        assertEquals("en", releaseNotesLocaleTag(AppLocale.System, null))
        // An explicit app language wins over the system's.
        assertEquals("ja", releaseNotesLocaleTag(AppLocale.Japanese, "zh-Hans-CN"))
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

    @Test
    fun releaseNotesLocaleNormalizationRejectsProcessLocaleSentinels() {
        assertEquals("en", releaseNotesNormalizeLanguageTag("C"))
        assertEquals("en-US", releaseNotesNormalizeLanguageTag("en-US@calendar=gregorian"))
        assertTrue(releaseNotesNormalizeLanguageTag("zh_CN.UTF-8").equals("zh-CN"))
    }

    @Test
    fun releaseNotesUrlTargetsHtmlRouteInsteadOfJsonEndpoint() {
        val url = sharedSettingsReleaseNotesUrlForLocale("1070001", "en")
        assertTrue(url.startsWith("https://hands.build/notes/"))
        assertFalse(url.contains("/public/v2/apps/"))
        assertFalse(url.contains("release-notes"))
    }
}
