package build.raft.app.data

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SharedSettingsHttp3Test {
    @Test
    fun settingsStoreRoutesHttp3IntentThroughTypedMobileAction() = runBlocking {
        val repository = RecordingNetworkTransportPreferenceRepository(available = true)
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemorySlockAuthSessionStore(),
                        networkTransportPreferenceRepository = repository
                    ),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            )

        store.accept(SharedSettingsIntent.SetHttp3Enabled(true))

        assertEquals(MOBILE_ACTION_HTTP3, store.state.value.actionState?.action)
        assertFalse(store.state.value.actionState?.inProgress ?: true)
        val mobile = assertIs<ScreenLoadState.Content<SettingsMobileSnapshot>>(store.state.value.mobile)
        assertTrue(mobile.data.http3.requestedEnabled)
        assertTrue(mobile.data.http3.effectiveEnabled)
    }

    @Test
    fun settingsMutationEchoesRequestedAndEffectiveState() = runBlocking {
        val repository = RecordingNetworkTransportPreferenceRepository(available = true)
        val dataSource =
            SharedSettingsDataSource(
                sessionStore = InMemorySlockAuthSessionStore(),
                networkTransportPreferenceRepository = repository
            )

        val result = dataSource.setHttp3Enabled(true)

        val content = assertIs<DataResult.Content<SettingsMobileSnapshot>>(result)
        assertTrue(content.data.http3.requestedEnabled)
        assertTrue(content.data.http3.effectiveEnabled)
        assertTrue(content.data.http3.curlRequestedForNewCalls)
        assertEquals(1, repository.mutations)
    }

    @Test
    fun unavailableEnableIsTypedAndDoesNotMutateRequestedState() = runBlocking {
        val repository = RecordingNetworkTransportPreferenceRepository(available = false)
        val dataSource =
            SharedSettingsDataSource(
                sessionStore = InMemorySlockAuthSessionStore(),
                networkTransportPreferenceRepository = repository
            )

        val result = dataSource.setHttp3Enabled(true)

        val error = assertIs<DataResult.Error>(result)
        assertEquals("settings_network_http3_unsupported", error.error.code)
        assertFalse(repository.snapshot.requestedHttp3Enabled)
        assertEquals(0, repository.mutations)
    }

    @Test
    fun rejectedEnableRefreshesTheStoreWithCurrentRuntimeReadiness() = runBlocking {
        val repository = RecordingNetworkTransportPreferenceRepository(available = true)
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemorySlockAuthSessionStore(),
                        networkTransportPreferenceRepository = repository
                    ),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            )

        store.accept(
            SharedSettingsIntent.OpenDestination(SharedSettingsDestination.Mobile)
        )
        val ready = assertIs<ScreenLoadState.Content<SettingsMobileSnapshot>>(store.state.value.mobile)
        assertTrue(ready.data.http3.canEnable)

        repository.setAvailable(false)
        store.accept(SharedSettingsIntent.SetHttp3Enabled(true))

        val refreshed = assertIs<ScreenLoadState.Content<SettingsMobileSnapshot>>(store.state.value.mobile)
        assertFalse(refreshed.data.http3.canEnable)
        assertFalse(refreshed.data.http3.requestedEnabled)
        assertEquals("unsupported", refreshed.data.http3.detail)
        assertEquals(
            "settings_network_http3_unsupported",
            store.state.value.actionState?.errorCode
        )
        assertEquals(
            "settings_network_http3_unsupported",
            store.state.value.routeErrorCode
        )
    }

    private class RecordingNetworkTransportPreferenceRepository(
        private var available: Boolean
    ) : NetworkTransportPreferenceRepository {
        var mutations: Int = 0
            private set
        var snapshot: NetworkTransportPreferenceSnapshot = snapshot(false)
            private set

        override fun getPreferenceSync(): NetworkTransportPreferenceSnapshot = snapshot

        override suspend fun setHttp3Enabled(
            enabled: Boolean
        ): NetworkTransportPreferenceMutationResult {
            if (enabled && !available) {
                return NetworkTransportPreferenceMutationResult.Rejected(
                    errorCode = "settings_network_http3_unsupported",
                    snapshot = snapshot
                )
            }
            mutations += 1
            snapshot = snapshot(enabled)
            return NetworkTransportPreferenceMutationResult.Applied(snapshot)
        }

        fun setAvailable(value: Boolean) {
            available = value
            snapshot = snapshot(snapshot.requestedHttp3Enabled)
        }

        private fun snapshot(enabled: Boolean): NetworkTransportPreferenceSnapshot =
            NetworkTransportPreferenceSnapshot(
                requestedHttp3Enabled = enabled,
                effectiveHttp3Enabled = enabled && available,
                curlRequestedForNewCalls = enabled && available,
                available = available,
                availability =
                    if (available) {
                        NetworkTransportPreferenceAvailability.Ready
                    } else {
                        NetworkTransportPreferenceAvailability.Http3Unsupported
                    },
                detail = if (available) "ready" else "unsupported",
                generation = mutations.toLong()
            )
    }
}
