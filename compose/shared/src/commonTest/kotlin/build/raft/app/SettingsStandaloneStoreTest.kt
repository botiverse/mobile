package build.raft.app

import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.SharedApiEnvironmentStore
import build.raft.app.data.SharedSettingsApiEnvironmentRequest
import build.raft.app.thread.key
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsStandaloneStoreTest {
    @Test
    fun standaloneWebBaseUrlReadsTheCanonicalCurrentEnvironment() {
        val persistence = InMemoryRaftKeyValueStore()
        val store = SharedApiEnvironmentStore(persistence)

        assertEquals(
            SharedApiEnvironmentStore.Production.webUrl,
            standaloneSettingsWebBaseUrl(store),
        )

        store.select(SharedApiEnvironmentStore.Staging.key)
        assertEquals(
            SharedApiEnvironmentStore.Staging.webUrl,
            standaloneSettingsWebBaseUrl(store),
        )

        store.save(
            SharedSettingsApiEnvironmentRequest(
                key = SharedApiEnvironmentStore.CustomKey,
                label = "Exact preview",
                apiBaseUrl = "https://api-aws-staging.botiverse.dev/api/",
                socketBaseUrl = "https://api-aws-staging.botiverse.dev",
                webUrl = "https://exact-preview.botiverse.dev",
            )
        )
        assertEquals(
            "https://exact-preview.botiverse.dev",
            standaloneSettingsWebBaseUrl(SharedApiEnvironmentStore(persistence)),
        )
    }
}
