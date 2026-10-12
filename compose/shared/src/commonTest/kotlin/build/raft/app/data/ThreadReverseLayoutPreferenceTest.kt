package build.raft.app.data

import build.raft.app.threadReverseLayoutEffective
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * artin 2026-10-11: the bottom-up message list is a Settings switch; `mobile_thread_reverse_layout_v0`
 * only sets its default. Three states: no choice follows the default, a choice overrides it both ways.
 */
class ThreadReverseLayoutPreferenceTest {
    @Test
    fun withoutAChoiceTheThreadFollowsTheDefaultAndAChoiceOverridesIt() {
        assertTrue(threadReverseLayoutEffective(userChoice = null) { true })
        assertFalse(threadReverseLayoutEffective(userChoice = null) { false })
        assertFalse(threadReverseLayoutEffective(userChoice = false) { true }, "user off beats a default-on flag")
        assertTrue(threadReverseLayoutEffective(userChoice = true) { false }, "user on beats a default-off flag")
    }

    @Test
    fun settingsSwitchShowsTheDefaultUntilChangedThenStoresTheChoice(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val keyValues = InMemorySlockKeyValueStore()
        var flagDefault = true
        val preferenceStore = KeyValueThreadReverseLayoutPreferenceStore(keyValues)
        val dataSource =
            SharedSettingsDataSource(
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                threadReverseLayoutPreferenceStore = preferenceStore,
                threadReverseLayoutDefault = { flagDefault }
            )
        val store = SharedSettingsStore(dataSource = dataSource, scope = scope)
        try {
            val following = assertIs<DataResult.Content<SettingsMobileSnapshot>>(dataSource.loadMobilePreferences()).data
            assertTrue(following.threadReverseLayout.available)
            assertTrue(following.threadReverseLayout.enabled, "no choice: shows the flag default")
            flagDefault = false
            assertFalse(assertIs<DataResult.Content<SettingsMobileSnapshot>>(dataSource.loadMobilePreferences()).data.threadReverseLayout.enabled)
            assertNull(preferenceStore.readThreadReverseLayoutUserChoiceSync())

            store.accept(SharedSettingsIntent.SetThreadReverseLayoutEnabled(true))
            assertEquals("true", keyValues.getString(THREAD_REVERSE_LAYOUT_PREFERENCE_KEY))
            val afterOn = assertIs<ScreenLoadState.Content<SettingsMobileSnapshot>>(store.state.value.mobile).data
            assertTrue(afterOn.threadReverseLayout.enabled, "the choice wins over a default-off flag")
            assertEquals(MOBILE_ACTION_THREAD_REVERSE_LAYOUT, store.state.value.actionState?.action)
            assertEquals(false, store.state.value.actionState?.inProgress)

            flagDefault = true
            store.accept(SharedSettingsIntent.SetThreadReverseLayoutEnabled(false))
            assertEquals(false, preferenceStore.readThreadReverseLayoutUserChoiceSync())
            assertFalse(assertIs<ScreenLoadState.Content<SettingsMobileSnapshot>>(store.state.value.mobile).data.threadReverseLayout.enabled)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun rowIsUnavailableWithoutPreferenceStore(): Unit = runBlocking {
        val dataSource = SharedSettingsDataSource(sessionStore = InMemorySlockAuthSessionStore(sampleSession()))
        val snapshot = assertIs<DataResult.Content<SettingsMobileSnapshot>>(dataSource.loadMobilePreferences())
        assertFalse(snapshot.data.threadReverseLayout.available)
        assertIs<DataResult.Error>(dataSource.setThreadReverseLayoutEnabled(true))
    }

    private fun sampleSession(): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "user-1@slock.local", displayName = "User One"),
            accessToken = "access",
            refreshToken = "refresh",
            selectedServerId = "server-a",
            servers = listOf(StoredAuthServer(id = "server-a", name = "Server A"))
        )
}
