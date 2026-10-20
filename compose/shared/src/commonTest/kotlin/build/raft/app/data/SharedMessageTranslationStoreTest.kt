package build.raft.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TestServerId = "server-1"

private fun testSession(serverId: String? = TestServerId): StoredAuthSession =
    StoredAuthSession(
        user = StoredAuthUser(id = "user-1", email = "user@example.com"),
        accessToken = "token",
        refreshToken = "refresh",
        selectedServerId = serverId
    )

private class FakeAuthSessionStore(private var session: StoredAuthSession? = testSession()) : RaftAuthSessionStore {
    override suspend fun readSession(): StoredAuthSession? = session
    override suspend fun saveSession(session: StoredAuthSession) {
        this.session = session
    }

    override suspend fun updateTokens(accessToken: String, refreshToken: String) = Unit
    override suspend fun updateSelectedServer(serverId: String?, serverSlug: String?) {
        session = session?.copy(selectedServerId = serverId, selectedServerSlug = serverSlug)
    }

    override suspend fun clearSession(reason: String, source: String) {
        session = null
    }
}

private class FakeMessageTranslationApi(
    var serverBody: String = """{"translationEnabled":true,"translationAvailable":true,"canManageTranslation":false}""",
    var batchResponses: MutableList<HomeNetworkResponse> = mutableListOf()
) : RaftMessageTranslationApi {
    val batchCalls = mutableListOf<Triple<List<String>, String, String>>()

    override suspend fun translateBatch(
        messageIds: List<String>,
        targetLanguage: String,
        mode: String,
        priority: DataRequestPriority
    ): HomeNetworkResponse {
        batchCalls += Triple(messageIds, targetLanguage, mode)
        return if (batchResponses.isNotEmpty()) batchResponses.removeAt(0) else HomeNetworkResponse(200, """{"results":[]}""")
    }

    override suspend fun serverTranslationSettings(serverId: String): HomeNetworkResponse =
        HomeNetworkResponse(200, serverBody)
}

private fun translatedResultBody(messageId: String, translated: String): String =
    """{"results":[{"messageId":"$messageId","status":"translated","translatedContent":"$translated","sourceLanguage":"zh","targetLanguage":"en"}]}"""

private fun candidate(
    messageId: String = "m1",
    body: String = "你好",
    isOwn: Boolean = false,
    isSystem: Boolean = false
): SharedMessageTranslationCandidate =
    SharedMessageTranslationCandidate(messageId = messageId, body = body, isOwn = isOwn, isSystem = isSystem)

class SharedMessageTranslationStoreTest {
    private fun store(
        api: FakeMessageTranslationApi,
        scope: CoroutineScope,
        authStore: RaftAuthSessionStore = FakeAuthSessionStore(),
        translationMode: () -> TranslationModePreference = { TranslationModePreference.Automatic },
        translationTargetLanguage: () -> String = { TranslationTargetLanguages.Browser },
        displayLanguage: () -> String? = { null }
    ): SharedMessageTranslationStore =
        SharedMessageTranslationStore(
            authStore = authStore,
            api = api,
            scope = scope,
            translationMode = translationMode,
            translationTargetLanguage = translationTargetLanguage,
            displayLanguage = displayLanguage
        )

    @Test
    fun ensureSettingsLoadedReadsLocalTargetAndServerSettings() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = FakeMessageTranslationApi()
        val store = store(api, scope, translationTargetLanguage = { "it" })

        store.ensureSettingsLoaded()

        val settings = store.state.value.settings
        assertTrue(settings.loaded)
        assertEquals(TestServerId, settings.serverId)
        assertTrue(settings.available)
        assertEquals(TranslationModePreference.Automatic, settings.translationMode)
        assertEquals("it", settings.effectiveLanguage)
        scope.cancel()
    }

    @Test
    fun noPreferenceTargetFollowsDisplayLanguage() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = FakeMessageTranslationApi()
        val store = store(api, scope, displayLanguage = { "ja" })

        store.ensureSettingsLoaded()

        assertEquals("ja", store.state.value.settings.effectiveLanguage)
        scope.cancel()
    }

    @Test
    fun noPreferenceTargetUsesExplicitAppLanguageOverDeviceLanguage() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = FakeMessageTranslationApi()
        // botiverse/slock#10449: with no saved target, translate into the UI
        // language. App language Simplified Chinese on an English device -> zh-cn.
        val store =
            store(
                api,
                scope,
                displayLanguage = { AppLocale.SimplifiedChinese.displayLanguageTag(systemLanguage = "en-US") }
            )

        store.ensureSettingsLoaded()
        store.requestAutoTranslations(listOf(candidate()))

        assertEquals("zh-cn", store.state.value.settings.effectiveLanguage)
        assertEquals("zh-cn", api.batchCalls.single().second)
        scope.cancel()
    }

    @Test
    fun noPreferenceTargetReadsCurrentDisplayLanguageForEveryRequest() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = FakeMessageTranslationApi()
        var appLocale = AppLocale.English
        val store = store(api, scope, displayLanguage = { appLocale.displayLanguageTag(systemLanguage = "ko-KR") })

        store.ensureSettingsLoaded()
        store.requestAutoTranslations(listOf(candidate(messageId = "m1")))
        appLocale = AppLocale.Japanese
        store.requestAutoTranslations(listOf(candidate(messageId = "m2")))

        assertEquals(listOf("en", "ja"), api.batchCalls.map { it.second })
        scope.cancel()
    }

    @Test
    fun explicitTargetOverridesDisplayLanguage() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = FakeMessageTranslationApi()
        val store =
            store(
                api,
                scope,
                translationTargetLanguage = { "ko" },
                displayLanguage = { "ja-JP" }
            )

        store.ensureSettingsLoaded()
        store.requestAutoTranslations(listOf(candidate()))

        assertEquals("ko", api.batchCalls.single().second)
        scope.cancel()
    }

    @Test
    fun localTargetChangeAppliesWithoutAccountPatchOrSettingsReload() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = FakeMessageTranslationApi()
        var target = "en"
        val store = store(api, scope, translationTargetLanguage = { target })

        store.ensureSettingsLoaded()
        target = "ja-JP"
        store.requestAutoTranslations(listOf(candidate()))

        assertEquals("ja", store.state.value.settings.effectiveLanguage)
        assertEquals("ja", api.batchCalls.single().second)
        scope.cancel()
    }

    @Test
    fun requestAutoTranslationsAppliesTranslatedResults() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = FakeMessageTranslationApi(
            batchResponses = mutableListOf(HomeNetworkResponse(200, translatedResultBody("m1", "Hello")))
        )
        val store = store(api, scope)
        store.ensureSettingsLoaded()

        store.requestAutoTranslations(listOf(candidate()))

        assertEquals(listOf(Triple(listOf("m1"), "en", "auto")), api.batchCalls)
        val entry = store.state.value.entries.getValue("m1")
        assertEquals(SharedMessageTranslationStatus.Translated, entry.status)
        assertEquals("Hello", entry.translatedContent)
        assertEquals("你好", entry.originalContent)
        scope.cancel()
    }

    @Test
    fun requestAutoTranslationsSkipsOwnAndSystemMessagesAndCachedEntries() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = FakeMessageTranslationApi(
            batchResponses = mutableListOf(HomeNetworkResponse(200, translatedResultBody("m1", "Hello")))
        )
        val store = store(api, scope)
        store.ensureSettingsLoaded()

        store.requestAutoTranslations(
            listOf(
                candidate(),
                candidate(messageId = "own", isOwn = true),
                candidate(messageId = "system", isSystem = true),
                candidate(messageId = "blank", body = "  ")
            )
        )
        // Cached entry with the same content must not refetch.
        store.requestAutoTranslations(listOf(candidate()))

        assertEquals(1, api.batchCalls.size)
        assertEquals(listOf("m1"), api.batchCalls.single().first)
        scope.cancel()
    }

    @Test
    fun editedMessageContentTriggersRefetch() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = FakeMessageTranslationApi(
            batchResponses = mutableListOf(
                HomeNetworkResponse(200, translatedResultBody("m1", "Hello")),
                HomeNetworkResponse(200, translatedResultBody("m1", "Hello world"))
            )
        )
        val store = store(api, scope)
        store.ensureSettingsLoaded()

        store.requestAutoTranslations(listOf(candidate(body = "你好")))
        store.requestAutoTranslations(listOf(candidate(body = "你好世界")))

        assertEquals(2, api.batchCalls.size)
        assertEquals("Hello world", store.state.value.entries.getValue("m1").translatedContent)
        scope.cancel()
    }

    @Test
    fun manualModeDoesNotAutoTranslate() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = FakeMessageTranslationApi()
        val store = store(api, scope, translationMode = { TranslationModePreference.Manual })
        store.ensureSettingsLoaded()

        store.requestAutoTranslations(listOf(candidate()))

        assertTrue(api.batchCalls.isEmpty())
        scope.cancel()
    }

    @Test
    fun originalModeBlocksEvenExplicitTranslateNow() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = FakeMessageTranslationApi()
        val store = store(api, scope, translationMode = { TranslationModePreference.Original })
        store.ensureSettingsLoaded()

        store.requestAutoTranslations(listOf(candidate()))
        store.translateNow(candidate())

        assertTrue(api.batchCalls.isEmpty())
        scope.cancel()
    }

    @Test
    fun manualModeTranslateNowUsesManualBatchMode() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = FakeMessageTranslationApi(
            batchResponses = mutableListOf(HomeNetworkResponse(200, translatedResultBody("m1", "Hello")))
        )
        val store = store(api, scope, translationMode = { TranslationModePreference.Manual })
        store.ensureSettingsLoaded()

        store.translateNow(candidate())

        assertEquals(1, api.batchCalls.size)
        assertEquals("manual", api.batchCalls.single().third)
        val entry = store.state.value.entries.getValue("m1")
        assertEquals(SharedMessageTranslationStatus.Translated, entry.status)
        assertEquals(false, entry.showOriginal)
        scope.cancel()
    }

    @Test
    fun translateNowWithFreshEntryOnlyTogglesDisplay() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = FakeMessageTranslationApi(
            batchResponses = mutableListOf(HomeNetworkResponse(200, translatedResultBody("m1", "Hello")))
        )
        val store = store(api, scope)
        store.ensureSettingsLoaded()
        store.requestAutoTranslations(listOf(candidate()))
        store.setShowOriginal("m1", true)

        store.translateNow(candidate())

        assertEquals(1, api.batchCalls.size)
        assertEquals(false, store.state.value.entries.getValue("m1").showOriginal)
        scope.cancel()
    }

    @Test
    fun serverDisabledResponseFlipsAvailabilityAndClearsPending() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = FakeMessageTranslationApi(
            batchResponses = mutableListOf(HomeNetworkResponse(403, null, """{"error":"disabled"}"""))
        )
        val store = store(api, scope)
        store.ensureSettingsLoaded()

        store.requestAutoTranslations(listOf(candidate()))

        assertFalse(store.state.value.settings.available)
        assertNull(store.state.value.entries["m1"])
        scope.cancel()
    }

    @Test
    fun failedBatchMarksEntriesFailedAndRetryUsesManualMode() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = FakeMessageTranslationApi(
            batchResponses = mutableListOf(
                HomeNetworkResponse(500, null, """{"error":"boom"}"""),
                HomeNetworkResponse(200, translatedResultBody("m1", "Hello"))
            )
        )
        val store = store(api, scope)
        store.ensureSettingsLoaded()

        store.requestAutoTranslations(listOf(candidate()))
        assertEquals(SharedMessageTranslationStatus.Failed, store.state.value.entries.getValue("m1").status)

        store.retryTranslation(candidate())
        assertEquals("manual", api.batchCalls[1].third)
        assertEquals(SharedMessageTranslationStatus.Translated, store.state.value.entries.getValue("m1").status)
        scope.cancel()
    }

    @Test
    fun missingBatchResultsBecomeNotFoundEntries() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = FakeMessageTranslationApi(
            batchResponses = mutableListOf(HomeNetworkResponse(200, """{"results":[]}"""))
        )
        val store = store(api, scope)
        store.ensureSettingsLoaded()

        store.requestAutoTranslations(listOf(candidate()))

        val entry = store.state.value.entries.getValue("m1")
        assertEquals(SharedMessageTranslationStatus.NotFound, entry.status)
        assertTrue(entry.isSilentTranslationEntry())
        scope.cancel()
    }

    @Test
    fun setShowOriginalIsNoOpWithoutEntry() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = FakeMessageTranslationApi()
        val store = store(api, scope)

        store.setShowOriginal("missing", true)

        assertTrue(store.state.value.entries.isEmpty())
        scope.cancel()
    }

    @Test
    fun silentSkipReasonsAreSilentAndProviderSkipIsNot() {
        val silent = SharedMessageTranslationEntry(
            messageId = "m1",
            status = SharedMessageTranslationStatus.Skipped,
            reason = "same_language"
        )
        val loud = SharedMessageTranslationEntry(
            messageId = "m2",
            status = SharedMessageTranslationStatus.Skipped,
            reason = "provider_unavailable"
        )
        assertTrue(silent.isSilentTranslationEntry())
        assertFalse(loud.isSilentTranslationEntry())
    }
}
