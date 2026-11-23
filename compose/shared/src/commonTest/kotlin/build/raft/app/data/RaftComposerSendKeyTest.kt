package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RaftComposerSendKeyTest {
    private class MapPreferenceStore : SharedPreferenceStore {
        val values = mutableMapOf<String, String?>()

        override fun getString(key: String): String? = values[key]

        override fun putString(key: String, value: String?) {
            values[key] = value
        }
    }

    @AfterTest
    fun tearDown() {
        RaftComposerSendKeyRuntime.resetForTest(null)
    }

    @Test
    fun wireValuesParseAndSerializeMatchingWebContract() {
        assertEquals(RaftComposerSendKey.Enter, RaftComposerSendKey.fromWire(null))
        assertEquals(RaftComposerSendKey.Enter, RaftComposerSendKey.fromWire(""))
        assertEquals(RaftComposerSendKey.Enter, RaftComposerSendKey.fromWire("enter"))
        assertEquals(RaftComposerSendKey.Enter, RaftComposerSendKey.fromWire("unknown"))
        assertEquals(RaftComposerSendKey.ModEnter, RaftComposerSendKey.fromWire("mod_enter"))
        assertEquals(RaftComposerSendKey.ModEnter, RaftComposerSendKey.fromWire(" MOD_ENTER "))
        assertNull(RaftComposerSendKey.Enter.toStoredAccountWireOrNull())
        assertEquals("mod_enter", RaftComposerSendKey.ModEnter.toStoredAccountWireOrNull())
    }

    @Test
    fun storePersistsSelectionAcrossRestartsAndDefaultsToEnter() {
        val prefs = MapPreferenceStore()
        val first = RaftComposerSendKeyStore { prefs }
        assertEquals(RaftComposerSendKey.Enter, first.sendKey)

        first.setSendKey(RaftComposerSendKey.ModEnter)
        assertEquals("mod_enter", prefs.values[RaftComposerSendKeyStorageKey])

        // Fresh instance simulates app restart.
        val second = RaftComposerSendKeyStore { prefs }
        assertEquals(RaftComposerSendKey.ModEnter, second.sendKey)

        second.setSendKey(RaftComposerSendKey.Enter)
        assertEquals("enter", prefs.values[RaftComposerSendKeyStorageKey])
        assertEquals(RaftComposerSendKey.Enter, RaftComposerSendKeyStore { prefs }.sendKey)
    }

    @Test
    fun repositoryReadsAndPatchesAccountComposerSendKeyAndUpdatesSession() = runBlocking {
        val prefs = MapPreferenceStore()
        RaftComposerSendKeyRuntime.resetForTest(prefs)
        val sessionStore = InMemoryRaftAuthSessionStore(sampleSession())
        val patchBodies = mutableListOf<String>()
        var accountJson =
            """{"id":"user-1","email":"user@slock.ai","name":"user","displayName":"User","composerSendKey":"mod_enter"}"""
        val network =
            object : RaftNetworkClient {
                override suspend fun request(
                    priority: DataRequestPriority,
                    path: String,
                    method: String,
                    query: List<Pair<String, String>>,
                    serverIdOverride: ServerId?,
                    bodyText: String?,
                ): HomeNetworkResponse {
                    val normalized = path.trimStart('/')
                    return when {
                        normalized == "auth/me" && method == "GET" -> HomeNetworkResponse(200, accountJson)
                        normalized == "auth/me" && method == "PATCH" -> {
                            patchBodies += bodyText.orEmpty()
                            accountJson =
                                if (bodyText?.contains("mod_enter") == true) {
                                    """{"id":"user-1","email":"user@slock.ai","name":"user","displayName":"User","composerSendKey":"mod_enter"}"""
                                } else {
                                    """{"id":"user-1","email":"user@slock.ai","name":"user","displayName":"User","composerSendKey":null}"""
                                }
                            HomeNetworkResponse(200, accountJson)
                        }
                        else -> error("Unexpected request $method $normalized")
                    }
                }
            }
        val synced = mutableListOf<RaftComposerSendKey>()
        val repository =
            RealSettingsRepository(
                sessionStore = sessionStore,
                networkClient = network,
                onComposerSendKeyUpdated = {
                    synced += it
                    RaftComposerSendKeyRuntime.setSendKey(it)
                },
            )

        // GET /api/auth/me hydrates composerSendKey from account.
        val loaded = assertIs<DataResult.Content<SettingsAppearanceSnapshot>>(repository.getAppearanceSettings()).data
        assertEquals(RaftComposerSendKey.ModEnter, loaded.composerSendKey)
        assertEquals(RaftComposerSendKey.ModEnter, RaftComposerSendKeyRuntime.sendKey)

        // PATCH /api/auth/me updates account to enter and persists to session store.
        val updatedToEnter =
            assertIs<DataResult.Content<SettingsAppearanceSnapshot>>(
                repository.updateComposerSendKey(RaftComposerSendKey.Enter)
            ).data
        assertEquals("""{"composerSendKey":"enter"}""", patchBodies.last())
        assertEquals(RaftComposerSendKey.Enter, updatedToEnter.composerSendKey)
        assertNull(sessionStore.readSession()?.user?.composerSendKey)

        // PATCH /api/auth/me updates account back to mod_enter and persists to session store.
        val updatedToMod =
            assertIs<DataResult.Content<SettingsAppearanceSnapshot>>(
                repository.updateComposerSendKey(RaftComposerSendKey.ModEnter)
            ).data
        assertEquals("""{"composerSendKey":"mod_enter"}""", patchBodies.last())
        assertEquals(RaftComposerSendKey.ModEnter, updatedToMod.composerSendKey)
        assertEquals("mod_enter", sessionStore.readSession()?.user?.composerSendKey)
        assertEquals(
            listOf(RaftComposerSendKey.ModEnter, RaftComposerSendKey.Enter, RaftComposerSendKey.ModEnter),
            synced,
        )
    }

    @Test
    fun storeAppliesOptimisticallyRevertsOnFailureAndIgnoresStaleLateFailure() = runBlocking {
        val prefs = MapPreferenceStore()
        RaftComposerSendKeyRuntime.resetForTest(prefs)
        val firstPatch = CompletableDeferred<HomeNetworkResponse>()
        val secondPatch = CompletableDeferred<HomeNetworkResponse>()
        var patchCount = 0
        val network =
            object : RaftNetworkClient {
                override suspend fun request(
                    priority: DataRequestPriority,
                    path: String,
                    method: String,
                    query: List<Pair<String, String>>,
                    serverIdOverride: ServerId?,
                    bodyText: String?,
                ): HomeNetworkResponse {
                    val normalized = path.trimStart('/')
                    return when {
                        normalized == "auth/me" && method == "GET" ->
                            HomeNetworkResponse(
                                200,
                                """{"id":"user-1","email":"user@slock.ai","name":"user","displayName":"User","composerSendKey":null}""",
                            )
                        normalized == "auth/me" && method == "PATCH" -> {
                            patchCount += 1
                            when (patchCount) {
                                1 -> firstPatch.await()
                                2 -> secondPatch.await()
                                else ->
                                    HomeNetworkResponse(
                                        code = 500,
                                        bodyText = null,
                                        errorBodyText = """{"error":"server_error"}""",
                                        responseHeaders = mapOf("X-Trace-Id" to listOf("0123456789abcdef0123456789abcdef")),
                                    )
                            }
                        }
                        else -> error("Unexpected request $method $normalized")
                    }
                }
            }
        val sessionStore = InMemoryRaftAuthSessionStore(sampleSession())
        val repository =
            RealSettingsRepository(
                sessionStore = sessionStore,
                networkClient = network,
                onComposerSendKeyUpdated = RaftComposerSendKeyRuntime::setSendKey,
            )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = sessionStore,
                        settingsRepository = repository,
                    ),
                scope = scope,
            )

        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Appearance))
        assertEquals(
            RaftComposerSendKey.Enter,
            assertIs<ScreenLoadState.Content<SettingsAppearanceSnapshot>>(store.state.value.appearance).data.composerSendKey,
        )

        // Launch first change (to ModEnter), then supersede immediately with second change (back to Enter).
        val job1 = scope.launch { store.accept(SharedSettingsIntent.UpdateComposerSendKey(RaftComposerSendKey.ModEnter)) }
        yield()
        assertEquals(
            RaftComposerSendKey.ModEnter,
            assertIs<ScreenLoadState.Content<SettingsAppearanceSnapshot>>(store.state.value.appearance).data.composerSendKey,
        )
        assertEquals(RaftComposerSendKey.ModEnter, RaftComposerSendKeyRuntime.sendKey)

        val job2 = scope.launch { store.accept(SharedSettingsIntent.UpdateComposerSendKey(RaftComposerSendKey.Enter)) }
        yield()
        assertEquals(
            RaftComposerSendKey.Enter,
            assertIs<ScreenLoadState.Content<SettingsAppearanceSnapshot>>(store.state.value.appearance).data.composerSendKey,
        )

        // Newer call succeeds first; older call then fails late and must be ignored.
        secondPatch.complete(
            HomeNetworkResponse(
                200,
                """{"id":"user-1","email":"user@slock.ai","name":"user","displayName":"User","composerSendKey":null}""",
            ),
        )
        job2.join()
        firstPatch.complete(HomeNetworkResponse(500, null, """{"error":"stale_error"}"""))
        job1.join()

        assertEquals(
            RaftComposerSendKey.Enter,
            assertIs<ScreenLoadState.Content<SettingsAppearanceSnapshot>>(store.state.value.appearance).data.composerSendKey,
        )
        assertNull(store.state.value.actionState?.errorCode)

        // A failing latest change reverts to the stored value and surfaces the failure state.
        store.accept(SharedSettingsIntent.UpdateComposerSendKey(RaftComposerSendKey.ModEnter))
        assertEquals(
            RaftComposerSendKey.Enter,
            assertIs<ScreenLoadState.Content<SettingsAppearanceSnapshot>>(store.state.value.appearance).data.composerSendKey,
        )
        assertEquals(RaftComposerSendKey.Enter, RaftComposerSendKeyRuntime.sendKey)
        assertEquals(APPEARANCE_ACTION_COMPOSER_SEND_KEY, store.state.value.actionState?.action)
        assertNotNull(store.state.value.actionState?.errorCode)
        assertNotNull(store.state.value.actionState?.screenError)
        scope.cancel()
    }

    private fun sampleSession(): StoredAuthSession =
        StoredAuthSession(
            user =
                StoredAuthUser(
                    id = "user-1",
                    email = "user@slock.ai",
                    name = "user",
                    displayName = "User",
                    description = null,
                    avatarUrl = null,
                    emailVerified = true,
                ),
            accessToken = "token",
            refreshToken = "refresh",
            selectedServerId = "server-1",
            selectedServerSlug = "raft",
            servers = emptyList(),
        )
}
