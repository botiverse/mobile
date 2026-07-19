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

private class FakeAuthSessionStore(private var session: StoredAuthSession? = testSession()) : SlockAuthSessionStore {
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
    var accountBody: String = """{"id":"user-1","email":"user@example.com","name":"User One","preferredLanguage":"en","autoTranslationEnabled":true,"preferredTranslationDisplay":"translated"}""",
    var serverBody: String = """{"translationEnabled":true,"translationAvailable":true,"canManageTranslation":false}""",
    var batchResponses: MutableList<HomeNetworkResponse> = mutableListOf()
) : SlockMessageTranslationApi {
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

    override suspend fun accountPreferences(): HomeNetworkResponse = HomeNetworkResponse(200, accountBody)

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
        authStore: SlockAuthSessionStore = FakeAuthSessionStore(),
        translationMode: () -> TranslationModePreference = { TranslationModePreference.Automatic },
        fallbackLanguage: () -> String? = { null }
    ): SharedMessageTranslationStore =
        SharedMessageTranslationStore(
            authStore = authStore,
            api = api,
            scope = scope,
            translationMode = translationMode,
            fallbackLanguage = fallbackLanguage
        )

    @Test
    fun ensureSettingsLoadedReadsAccountAndServerSettings() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = FakeMessageTranslationApi()
        val store = store(api, scope)

        store.ensureSettingsLoaded()

        val settings = store.state.value.settings
        assertTrue(settings.loaded)
        assertEquals(TestServerId, settings.serverId)
        assertTrue(settings.available)
        assertEquals(TranslationModePreference.Automatic, settings.translationMode)
        assertEquals("en", settings.effectiveLanguage)
        scope.cancel()
    }

    @Test
    fun ensureSettingsLoadedFallsBackToAppLanguageWhenNoPreference() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = FakeMessageTranslationApi(
            accountBody = """{"id":"user-1","email":"user@example.com","name":"User One","autoTranslationEnabled":true}"""
        )
        val store = store(api, scope, fallbackLanguage = { "en" })

        store.ensureSettingsLoaded()

        assertEquals("en", store.state.value.settings.effectiveLanguage)
        scope.cancel()
    }

    @Test
    fun supportedNonEnglishFallbackLanguageBecomesTranslationTarget() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = FakeMessageTranslationApi(
            accountBody = """{"id":"user-1","email":"user@example.com","name":"User One","autoTranslationEnabled":true}"""
        )
        val store = store(api, scope, fallbackLanguage = { "zh-Hans" })

        store.ensureSettingsLoaded()
        store.requestAutoTranslations(listOf(candidate()))

        assertEquals("zh-cn", store.state.value.settings.effectiveLanguage)
        assertEquals("zh-cn", api.batchCalls.single().second)
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
