package build.raft.app.data

import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RaftNativePullToRefreshPreferenceTest {
    private class MapPreferenceStore : SharedPreferenceStore {
        val values = mutableMapOf<String, String?>()

        override fun getString(key: String): String? = values[key]

        override fun putString(key: String, value: String?) {
            values[key] = value
        }
    }

    @AfterTest
    fun resetRuntime() {
        RaftNativePullToRefreshRuntime.publish(RAFT_NATIVE_PULL_TO_REFRESH_DEFAULT)
    }

    @Test
    fun nativePullToRefreshDefaultsToOff() {
        assertFalse(RAFT_NATIVE_PULL_TO_REFRESH_DEFAULT)
        val store = RaftKeyValueNativePullToRefreshPreferenceStore(MapPreferenceStore())
        assertFalse(store.readNativePullToRefreshEnabledSync())
    }

    @Test
    fun savingPersistsAndPublishesTheLiveValue() = runBlocking {
        val backing = MapPreferenceStore()
        val store = RaftKeyValueNativePullToRefreshPreferenceStore(backing)

        store.saveNativePullToRefreshEnabled(true)
        assertTrue(store.readNativePullToRefreshEnabledSync())
        assertTrue(RaftKeyValueNativePullToRefreshPreferenceStore(backing).readNativePullToRefreshEnabledSync())
        assertTrue(RaftNativePullToRefreshRuntime.enabled())

        store.saveNativePullToRefreshEnabled(false)
        assertFalse(store.readNativePullToRefreshEnabledSync())
        assertFalse(RaftNativePullToRefreshRuntime.enabled())
    }

    @Test
    fun settingsMobileSnapshotShowsAndTogglesTheSwitch() = runBlocking {
        val dataSource =
            SharedSettingsDataSource(
                sessionStore = InMemoryRaftAuthSessionStore(null),
                nativePullToRefreshPreferenceStore =
                    RaftKeyValueNativePullToRefreshPreferenceStore(MapPreferenceStore()),
            )

        val initial = assertIs<DataResult.Content<SettingsMobileSnapshot>>(dataSource.loadMobilePreferences()).data
        assertTrue(initial.nativePullToRefresh.available)
        assertFalse(initial.nativePullToRefresh.enabled)

        val updated =
            assertIs<DataResult.Content<SettingsMobileSnapshot>>(dataSource.setNativePullToRefreshEnabled(true)).data
        assertTrue(updated.nativePullToRefresh.enabled)
        assertEquals("Enabled", updated.nativePullToRefresh.statusLabel)
        assertTrue(RaftNativePullToRefreshRuntime.enabled())
    }

    @Test
    fun switchIsHiddenWithoutAStore() = runBlocking {
        val dataSource = SharedSettingsDataSource(sessionStore = InMemoryRaftAuthSessionStore(null))
        val snapshot = assertIs<DataResult.Content<SettingsMobileSnapshot>>(dataSource.loadMobilePreferences()).data
        assertFalse(snapshot.nativePullToRefresh.available)
    }
}
