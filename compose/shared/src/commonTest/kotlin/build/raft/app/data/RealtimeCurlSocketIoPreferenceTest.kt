package build.raft.app.data

import build.raft.app.network.REALTIME_CURL_SOCKETIO_PREFERENCE_KEY
import build.raft.app.network.RaftCurlSocketIoPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Task #154: the three-state "realtime over the new network stack (curl)" Settings switch. */
class RealtimeCurlSocketIoPreferenceTest {
    @BeforeTest
    fun setUp() {
        RaftCurlSocketIoPolicy.markPlatformSupported()
    }

    @AfterTest
    fun tearDown() {
        RaftCurlSocketIoPolicy.resetForTest()
    }

    @Test
    fun absentPreferenceIsOffWhateverTheServerFlagAndOnlyAStoredChoiceTurnsCurlOn() {
        val keyValues = InMemoryRaftKeyValueStore()
        val preferenceStore = KeyValueRealtimeTransportPreferenceStore(keyValues)
        var serverFlag = true
        RaftCurlSocketIoPolicy.bindServerFlag { serverId -> serverId == "server-a" && serverFlag }
        RaftCurlSocketIoPolicy.bindUserChoice { preferenceStore.readCurlSocketIoUserChoiceSync() }

        assertNull(preferenceStore.readCurlSocketIoUserChoiceSync())
        // artin 2026-10-23: the server flag no longer selects curl.
        assertFalse(RaftCurlSocketIoPolicy.isEnabledFor("server-a"))
        assertFalse(RaftCurlSocketIoPolicy.isEnabledFor("server-b"))
        serverFlag = false
        assertFalse(RaftCurlSocketIoPolicy.isEnabledFor("server-a"))

        keyValues.putString(REALTIME_CURL_SOCKETIO_PREFERENCE_KEY, "true")
        assertEquals(true, preferenceStore.readCurlSocketIoUserChoiceSync())
        assertTrue(RaftCurlSocketIoPolicy.isEnabledFor("server-a"))
        assertTrue(RaftCurlSocketIoPolicy.isEnabledFor("server-b"))

        serverFlag = true
        keyValues.putString(REALTIME_CURL_SOCKETIO_PREFERENCE_KEY, "false")
        assertEquals(false, preferenceStore.readCurlSocketIoUserChoiceSync())
        assertFalse(RaftCurlSocketIoPolicy.isEnabledFor("server-a"))

        keyValues.putString(REALTIME_CURL_SOCKETIO_PREFERENCE_KEY, "garbage")
        assertNull(preferenceStore.readCurlSocketIoUserChoiceSync())
        assertFalse(RaftCurlSocketIoPolicy.isEnabledFor("server-a"))
    }

    @Test
    fun savingWritesTheExplicitChoiceAsTrueFalseStrings(): Unit = runBlocking {
        val keyValues = InMemoryRaftKeyValueStore()
        val preferenceStore = KeyValueRealtimeTransportPreferenceStore(keyValues)

        preferenceStore.saveCurlSocketIoUserChoice(false)
        assertEquals("false", keyValues.getString(REALTIME_CURL_SOCKETIO_PREFERENCE_KEY))
        assertEquals(false, preferenceStore.readCurlSocketIoUserChoiceSync())

        preferenceStore.saveCurlSocketIoUserChoice(true)
        assertEquals("true", keyValues.getString(REALTIME_CURL_SOCKETIO_PREFERENCE_KEY))
        assertEquals(true, preferenceStore.readCurlSocketIoUserChoiceSync())
    }

    @Test
    fun mobileSnapshotShowsOffWhenNoChoiceExistsEvenWithTheServerFlagOn(): Unit = runBlocking {
        val keyValues = InMemoryRaftKeyValueStore()
        var serverFlag = true
        RaftCurlSocketIoPolicy.bindServerFlag { serverId -> serverId == "server-a" && serverFlag }
        val dataSource = dataSource(keyValues)

        val flagOn = assertIs<DataResult.Content<SettingsMobileSnapshot>>(dataSource.loadMobilePreferences())
        assertTrue(flagOn.data.realtimeCurlSocketIo.available)
        assertFalse(flagOn.data.realtimeCurlSocketIo.enabled)

        serverFlag = false
        val disabledByServer = assertIs<DataResult.Content<SettingsMobileSnapshot>>(dataSource.loadMobilePreferences())
        assertFalse(disabledByServer.data.realtimeCurlSocketIo.enabled)
        assertNull(keyValues.getString(REALTIME_CURL_SOCKETIO_PREFERENCE_KEY))
    }

    @Test
    fun togglingStoresExplicitChoiceThatServerFlagNoLongerOverrides(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val keyValues = InMemoryRaftKeyValueStore()
        var serverFlag = true
        RaftCurlSocketIoPolicy.bindServerFlag { serverId -> serverId == "server-a" && serverFlag }
        val preferenceStore = KeyValueRealtimeTransportPreferenceStore(keyValues)
        RaftCurlSocketIoPolicy.bindUserChoice { preferenceStore.readCurlSocketIoUserChoiceSync() }
        val store = SharedSettingsStore(dataSource = dataSource(keyValues), scope = scope)
        try {
            store.accept(SharedSettingsIntent.SetRealtimeCurlSocketIoEnabled(false))

            assertEquals("false", keyValues.getString(REALTIME_CURL_SOCKETIO_PREFERENCE_KEY))
            val afterOff = assertIs<ScreenLoadState.Content<SettingsMobileSnapshot>>(store.state.value.mobile).data
            assertFalse(afterOff.realtimeCurlSocketIo.enabled)
            assertEquals(MOBILE_ACTION_REALTIME_CURL_SOCKETIO, store.state.value.actionState?.action)
            assertEquals(false, store.state.value.actionState?.inProgress)
            assertFalse(RaftCurlSocketIoPolicy.isEnabledFor("server-a"))

            serverFlag = true
            assertFalse(RaftCurlSocketIoPolicy.isEnabledFor("server-a"))
            assertEquals(false, preferenceStore.readCurlSocketIoUserChoiceSync())

            store.accept(SharedSettingsIntent.SetRealtimeCurlSocketIoEnabled(true))
            assertEquals("true", keyValues.getString(REALTIME_CURL_SOCKETIO_PREFERENCE_KEY))
            val afterOn = assertIs<ScreenLoadState.Content<SettingsMobileSnapshot>>(store.state.value.mobile).data
            assertTrue(afterOn.realtimeCurlSocketIo.enabled)

            serverFlag = false
            assertTrue(RaftCurlSocketIoPolicy.isEnabledFor("server-a"))
            assertTrue(RaftCurlSocketIoPolicy.isEnabledFor("server-b"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun rowIsUnavailableWithoutPreferenceStore(): Unit = runBlocking {
        val dataSource = SharedSettingsDataSource(sessionStore = InMemoryRaftAuthSessionStore(sampleSession()))

        val snapshot = assertIs<DataResult.Content<SettingsMobileSnapshot>>(dataSource.loadMobilePreferences())
        assertFalse(snapshot.data.realtimeCurlSocketIo.available)
        assertIs<DataResult.Error>(dataSource.setRealtimeCurlSocketIoEnabled(true))
    }

    @Test
    fun rowIsHiddenWhereNoPlatformHostCanUseCurl(): Unit = runBlocking {
        RaftCurlSocketIoPolicy.resetForTest()
        val snapshot = assertIs<DataResult.Content<SettingsMobileSnapshot>>(
            dataSource(InMemoryRaftKeyValueStore()).loadMobilePreferences()
        )
        assertFalse(snapshot.data.realtimeCurlSocketIo.available)
    }

    private fun dataSource(keyValues: InMemoryRaftKeyValueStore): SharedSettingsDataSource =
        SharedSettingsDataSource(
            sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
            realtimeTransportPreferenceStore = KeyValueRealtimeTransportPreferenceStore(keyValues)
        )

    private fun sampleSession(): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "user-1@slock.local", displayName = "User One"),
            accessToken = "access",
            refreshToken = "refresh",
            selectedServerId = "server-a",
            servers =
                listOf(
                    StoredAuthServer(id = "server-a", name = "Server A"),
                    StoredAuthServer(id = "server-b", name = "Server B")
                )
        )
}
