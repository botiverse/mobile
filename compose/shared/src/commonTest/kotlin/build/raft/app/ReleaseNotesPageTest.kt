package build.raft.app

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
    fun releaseNotesResponseSelectsExactVersionAndLanguage() {
        val payload = JSONObject(
            """{
                "lang":"zh-CN",
                "releases":[
                    {"version":"1.8.0","version_code":1080000,"changelog":"wrong"},
                    {
                        "version":"1.7.0",
                        "version_code":1070000,
                        "changelog":"fallback",
                        "release_notes":{"zh-CN":"中文说明","en":"English notes"}
                    }
                ]
            }""".trimIndent()
        )

        assertEquals(
            ReleaseNotesDocument(
                versionCode = 1_070_000L,
                versionName = "1.7.0",
                markdown = "中文说明",
                languageTag = "zh-CN"
            ),
            releaseNotesDocumentFromResponse(payload, "1070000", "zh-CN")
        )
        assertNull(releaseNotesDocumentFromResponse(payload, "1060000", "zh-CN"))
    }

    @Test
    fun releaseNotesApiParamsBindExactVersionAndOptionalLanguage() {
        val localized = releaseNotesApiRequestParams(" 1070000 ", " zh-CN ")
        assertEquals("1070000", localized.optString("version_code"))
        assertEquals("zh-CN", localized.optString("lang"))

        val system = releaseNotesApiRequestParams("1070000", null)
        assertEquals("1070000", system.optString("version_code"))
        assertEquals("", system.optString("lang"))
    }

    @Test
    fun releaseNotesResponseFallsBackToLanguageFamilyThenChangelog() {
        val familyPayload = JSONObject(
            """{
                "lang":"zh-Hans",
                "releases":[{
                    "version":"1.7.0",
                    "version_code":1070000,
                    "changelog":"fallback",
                    "release_notes":{"zh-CN":"中文说明","en":"English notes"}
                }]
            }""".trimIndent()
        )
        assertEquals(
            "中文说明",
            releaseNotesDocumentFromResponse(familyPayload, "1070000", null)?.markdown
        )

        val changelogPayload = JSONObject(
            """{
                "lang":"ja",
                "releases":[{
                    "version":"1.7.0",
                    "version_code":1070000,
                    "changelog":"fallback",
                    "release_notes":{}
                }]
            }""".trimIndent()
        )
        assertEquals(
            "fallback",
            releaseNotesDocumentFromResponse(changelogPayload, "1070000", null)?.markdown
        )
    }

    @Test
    fun releaseNotesCacheRoundTripsExactVersionAndLocale() {
        val document =
            ReleaseNotesDocument(
                versionCode = 1_070_001L,
                versionName = "1.7.0",
                markdown = "## Fixed\n\n- Faster notes",
                languageTag = "en"
            )
        assertEquals(
            document,
            releaseNotesCachedDocument(releaseNotesCachedDocumentJson(document))
        )
        assertEquals(
            "releaseNotes.native.v1:1070001:en",
            releaseNotesCacheKey("1070001", "en")
        )
        assertNull(releaseNotesCachedDocument("not-json"))
    }
}
