package build.raft.app

import build.raft.app.data.ScreenError
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SharedPreferenceStore
import build.raft.app.data.SharedSettingsAboutSnapshot
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleaseNotesPageTest {
    @Test
    fun releaseNotesApiUrlBindsPlatformAppSlug() {
        assertEquals(
            "https://hands.build/public/v2/apps/raft-android/release-notes",
            releaseNotesApiUrl("raft-android")
        )
        assertEquals(
            "https://hands.build/public/v2/apps/raft-ios/release-notes",
            releaseNotesApiUrl("raft-ios")
        )
        assertEquals(
            "https://hands.build/public/v2/apps/raft-ohos/release-notes",
            releaseNotesApiUrl("raft-ohos")
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
            releaseNotesAboutResolution(
                ScreenLoadState.Error(ScreenError(code = "network_error"))
            )
        )
    }

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
    fun releaseNotesApiParamsBindExactVersionAndResolvedLanguage() {
        val localized = releaseNotesApiRequestParams(" 1070000 ", " zh-CN ")
        assertEquals("1070000", localized.optString("version_code"))
        assertEquals("zh-CN", localized.optString("lang"))

        val system = releaseNotesApiRequestParams("1070000", null)
        assertEquals("1070000", system.optString("version_code"))
        assertEquals("en", system.optString("lang"))
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
            "releaseNotes.native.v2:1070001:en",
            releaseNotesCacheKey("1070001", "en")
        )
        assertNull(releaseNotesCachedDocument("not-json"))
    }

    @Test
    fun releaseNotesCacheRejectsValidJsonForWrongRequestAuthority() {
        val document =
            ReleaseNotesDocument(
                versionCode = 1_070_001L,
                versionName = "1.7.0",
                markdown = "Fixed",
                languageTag = "zh-CN"
            )
        val raw =
            releaseNotesCachedDocumentJson(
                document = document,
                requestedVersionCode = "1070001",
                requestedLanguageTag = "zh-CN"
            )

        assertNull(releaseNotesCachedDocument(raw, "1070000", "zh-CN"))
        assertNull(releaseNotesCachedDocument(raw, "1070001", "en"))
        assertEquals(document, releaseNotesCachedDocument(raw, "1070001", "zh-CN"))
    }

    @Test
    fun releaseNotesRequestIdentityFailsClosedBeforeCacheOrNetwork() {
        assertNull(releaseNotesRequestIdentity("", "en"))
        assertNull(releaseNotesRequestIdentity("1.7.0", "en"))
        assertNull(releaseNotesRequestIdentity("1070001&lang=zh", "en"))
        assertEquals(
            ReleaseNotesRequestIdentity("1070001", "zh-CN"),
            releaseNotesRequestIdentity(" 1070001 ", " zh-CN ")
        )
    }

    @Test
    fun releaseNotesRepositoryRejectsInvalidVersionWithoutStorageOrNetwork() = runBlocking {
        var remoteCalls = 0
        val preferences = CountingPreferenceStore()
        val repository =
            ReleaseNotesRepository(
                remoteDataSource =
                    object : ReleaseNotesRemoteDataSource {
                        override suspend fun fetch(
                            versionCode: String,
                            languageTag: String?
                        ): ReleaseNotesNetworkResult {
                            remoteCalls += 1
                            return successfulReleaseNotesResult(1_070_001L, "en", "unexpected")
                        }
                    },
                preferenceStore = preferences
            )

        assertNull(repository.cachedDocument("1.7.0", "en"))
        assertTrue(repository.refresh("", "en") is ReleaseNotesRepositoryResult.Failure)
        assertEquals(0, remoteCalls)
        assertEquals(0, preferences.reads)
        assertEquals(0, preferences.writes)
    }

    @Test
    fun releaseNotesSystemLocaleNormalizationProducesStableRequestKey() {
        assertEquals("zh-CN", releaseNotesNormalizeLanguageTag("zh_CN.UTF-8"))
        assertEquals("en-US", releaseNotesNormalizeLanguageTag("en-US@calendar=gregorian"))
        assertTrue(releaseNotesNormalizeLanguageTag("C").isNotBlank())
    }

    @Test
    fun releaseNotesStoreDiscardsCanceledOlderRequestGeneration() = runBlocking {
        val older = CompletableDeferred<ReleaseNotesNetworkResult>()
        val newer = CompletableDeferred<ReleaseNotesNetworkResult>()
        val pending = ArrayDeque(listOf(older, newer))
        val remote =
            object : ReleaseNotesRemoteDataSource {
                override suspend fun fetch(
                    versionCode: String,
                    languageTag: String?
                ): ReleaseNotesNetworkResult = pending.removeFirst().await()
            }
        val store =
            ReleaseNotesStore(
                repository = ReleaseNotesRepository(remote, MemoryPreferenceStore()),
                scope = this
            )

        store.dispatch(ReleaseNotesIntent.Load("1070000", "en"))
        yield()
        store.dispatch(ReleaseNotesIntent.Load("1070001", "zh-CN"))
        yield()
        newer.complete(successfulReleaseNotesResult(1_070_001L, "zh-CN", "new"))
        yield()
        older.complete(successfulReleaseNotesResult(1_070_000L, "en", "old"))
        yield()

        val content = store.state.value as ReleaseNotesBodyState.Content
        assertEquals(1_070_001L, content.document.versionCode)
        assertEquals("new", content.document.markdown)
        store.clear()
    }

    private fun successfulReleaseNotesResult(
        versionCode: Long,
        languageTag: String,
        markdown: String
    ): ReleaseNotesNetworkResult =
        ReleaseNotesNetworkResult(
            data =
                JSONObject(
                    """{
                        "lang":"$languageTag",
                        "releases":[{
                            "version":"1.7.0",
                            "version_code":$versionCode,
                            "release_notes":{"$languageTag":"$markdown"}
                        }]
                    }""".trimIndent()
                ),
            success = true,
            errorMessage = "",
            statusCode = 200
        )

    private class MemoryPreferenceStore : SharedPreferenceStore {
        private val values = mutableMapOf<String, String?>()

        override fun getString(key: String): String? = values[key]

        override fun putString(key: String, value: String?) {
            values[key] = value
        }
    }

    private class CountingPreferenceStore : SharedPreferenceStore {
        var reads = 0
        var writes = 0

        override fun getString(key: String): String? {
            reads += 1
            return null
        }

        override fun putString(key: String, value: String?) {
            writes += 1
        }
    }
}
