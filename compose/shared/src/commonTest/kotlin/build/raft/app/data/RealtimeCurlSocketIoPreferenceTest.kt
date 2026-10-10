package build.raft.app.data

import build.raft.app.network.REALTIME_CURL_SOCKETIO_PREFERENCE_KEY
import build.raft.app.network.SlockCurlSocketIoPolicy
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
        SlockCurlSocketIoPolicy.markPlatformSupported()
    }

    @AfterTest
    fun tearDown() {
        SlockCurlSocketIoPolicy.resetForTest()
    }

    @Test
    fun absentPreferenceFollowsServerFlagAndStoredChoiceOverridesIt() {
        val keyValues = InMemorySlockKeyValueStore()
        val preferenceStore = KeyValueRealtimeTransportPreferenceStore(keyValues)
        var serverFlag = true
        SlockCurlSocketIoPolicy.bindServerFlag { serverId -> serverId == "server-a" && serverFlag }
        SlockCurlSocketIoPolicy.bindUserChoice { preferenceStore.readCurlSocketIoUserChoiceSync() }

        assertNull(preferenceStore.readCurlSocketIoUserChoiceSync())
        assertTrue(SlockCurlSocketIoPolicy.isEnabledFor("server-a"))
        assertFalse(SlockCurlSocketIoPolicy.isEnabledFor("server-b"))
        serverFlag = false
        assertFalse(SlockCurlSocketIoPolicy.isEnabledFor("server-a"))

        keyValues.putString(REALTIME_CURL_SOCKETIO_PREFERENCE_KEY, "true")
        assertEquals(true, preferenceStore.readCurlSocketIoUserChoiceSync())
        assertTrue(SlockCurlSocketIoPolicy.isEnabledFor("server-a"))
        assertTrue(SlockCurlSocketIoPolicy.isEnabledFor("server-b"))

        serverFlag = true
        keyValues.putString(REALTIME_CURL_SOCKETIO_PREFERENCE_KEY, "false")
        assertEquals(false, preferenceStore.readCurlSocketIoUserChoiceSync())
        assertFalse(SlockCurlSocketIoPolicy.isEnabledFor("server-a"))

        keyValues.putString(REALTIME_CURL_SOCKETIO_PREFERENCE_KEY, "garbage")
        assertNull(preferenceStore.readCurlSocketIoUserChoiceSync())
        assertTrue(SlockCurlSocketIoPolicy.isEnabledFor("server-a"))
    }

    @Test
    fun savingWritesTheExplicitChoiceAsTrueFalseStrings(): Unit = runBlocking {
        val keyValues = InMemorySlockKeyValueStore()
        val preferenceStore = KeyValueRealtimeTransportPreferenceStore(keyValues)

        preferenceStore.saveCurlSocketIoUserChoice(false)
        assertEquals("false", keyValues.getString(REALTIME_CURL_SOCKETIO_PREFERENCE_KEY))
        assertEquals(false, preferenceStore.readCurlSocketIoUserChoiceSync())

        preferenceStore.saveCurlSocketIoUserChoice(true)
        assertEquals("true", keyValues.getString(REALTIME_CURL_SOCKETIO_PREFERENCE_KEY))
        assertEquals(true, preferenceStore.readCurlSocketIoUserChoiceSync())
    }

    @Test
    fun mobileSnapshotShowsServerFlagForCurrentServerWhenNoChoiceExists(): Unit = runBlocking {
        val keyValues = InMemorySlockKeyValueStore()
        var serverFlag = true
        SlockCurlSocketIoPolicy.bindServerFlag { serverId -> serverId == "server-a" && serverFlag }
        val dataSource = dataSource(keyValues)

        val enabledByServer = assertIs<DataResult.Content<SettingsMobileSnapshot>>(dataSource.loadMobilePreferences())
        assertTrue(enabledByServer.data.realtimeCurlSocketIo.available)
        assertTrue(enabledByServer.data.realtimeCurlSocketIo.enabled)

        serverFlag = false
        val disabledByServer = assertIs<DataResult.Content<SettingsMobileSnapshot>>(dataSource.loadMobilePreferences())
        assertFalse(disabledByServer.data.realtimeCurlSocketIo.enabled)
        assertNull(keyValues.getString(REALTIME_CURL_SOCKETIO_PREFERENCE_KEY))
    }

    @Test
    fun togglingStoresExplicitChoiceThatServerFlagNoLongerOverrides(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val keyValues = InMemorySlockKeyValueStore()
        var serverFlag = true
        SlockCurlSocketIoPolicy.bindServerFlag { serverId -> serverId == "server-a" && serverFlag }
        val preferenceStore = KeyValueRealtimeTransportPreferenceStore(keyValues)
        SlockCurlSocketIoPolicy.bindUserChoice { preferenceStore.readCurlSocketIoUserChoiceSync() }
        val store = SharedSettingsStore(dataSource = dataSource(keyValues), scope = scope)
        try {
            store.accept(SharedSettingsIntent.SetRealtimeCurlSocketIoEnabled(false))

            assertEquals("false", keyValues.getString(REALTIME_CURL_SOCKETIO_PREFERENCE_KEY))
            val afterOff = assertIs<ScreenLoadState.Content<SettingsMobileSnapshot>>(store.state.value.mobile).data
            assertFalse(afterOff.realtimeCurlSocketIo.enabled)
            assertEquals(MOBILE_ACTION_REALTIME_CURL_SOCKETIO, store.state.value.actionState?.action)
            assertEquals(false, store.state.value.actionState?.inProgress)
            assertFalse(SlockCurlSocketIoPolicy.isEnabledFor("server-a"))

            serverFlag = true
            assertFalse(SlockCurlSocketIoPolicy.isEnabledFor("server-a"))
            assertEquals(false, preferenceStore.readCurlSocketIoUserChoiceSync())

            store.accept(SharedSettingsIntent.SetRealtimeCurlSocketIoEnabled(true))
            assertEquals("true", keyValues.getString(REALTIME_CURL_SOCKETIO_PREFERENCE_KEY))
            val afterOn = assertIs<ScreenLoadState.Content<SettingsMobileSnapshot>>(store.state.value.mobile).data
            assertTrue(afterOn.realtimeCurlSocketIo.enabled)

            serverFlag = false
            assertTrue(SlockCurlSocketIoPolicy.isEnabledFor("server-a"))
            assertTrue(SlockCurlSocketIoPolicy.isEnabledFor("server-b"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun rowIsUnavailableWithoutPreferenceStore(): Unit = runBlocking {
        val dataSource = SharedSettingsDataSource(sessionStore = InMemorySlockAuthSessionStore(sampleSession()))

        val snapshot = assertIs<DataResult.Content<SettingsMobileSnapshot>>(dataSource.loadMobilePreferences())
        assertFalse(snapshot.data.realtimeCurlSocketIo.available)
        assertIs<DataResult.Error>(dataSource.setRealtimeCurlSocketIoEnabled(true))
    }

    @Test
    fun rowIsHiddenWhereNoPlatformHostCanUseCurl(): Unit = runBlocking {
        SlockCurlSocketIoPolicy.resetForTest()
        val snapshot = assertIs<DataResult.Content<SettingsMobileSnapshot>>(
            dataSource(InMemorySlockKeyValueStore()).loadMobilePreferences()
        )
        assertFalse(snapshot.data.realtimeCurlSocketIo.available)
    }

    private fun dataSource(keyValues: InMemorySlockKeyValueStore): SharedSettingsDataSource =
        SharedSettingsDataSource(
            sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
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
