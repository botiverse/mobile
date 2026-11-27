package build.raft.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SharedPasswordDisconnectPreviewStoreTest {
    private val samplePreview =
        SecurityRevokePreview(
            computers =
                listOf(
                    SecurityRevokePreviewComputer(
                        id = "comp-1",
                        name = "Studio Mac",
                        serverId = "srv-1",
                        serverName = "Botiverse",
                        agentCount = 2,
                    ),
                ),
            agentKeys = 1,
        )

    @Test
    fun syncFetchesOncePerKeyClearsOnCloseAndDropsStaleInFlightAnswer() = runTest {
        val pendingFirst = CompletableDeferred<SecurityRevokePreview?>()
        val pendingSecond = CompletableDeferred<SecurityRevokePreview?>()
        var calls = 0
        val store =
            SharedPasswordDisconnectPreviewStore(
                fetchPreview = {
                    calls += 1
                    when (calls) {
                        1 -> pendingFirst.await()
                        else -> pendingSecond.await()
                    }
                },
                scope = this,
            )

        store.sync("password_change:0")
        store.sync("password_change:0")
        runCurrent()
        assertEquals(1, calls, "same key does not re-fetch")
        assertNull(store.state.value.preview)

        // Closing cancels and forgets the first read; reopening starts a fresh read.
        store.sync(null)
        runCurrent()
        pendingFirst.complete(samplePreview)
        runCurrent()
        assertNull(store.state.value.preview, "stale answer must not land after close")

        store.sync("password_change:1")
        runCurrent()
        assertEquals(2, calls)
        pendingSecond.complete(samplePreview)
        runCurrent()
        assertEquals(samplePreview, store.state.value.preview)

        // Closing forgets the cached preview so reopening never shows a stale list.
        store.sync(null)
        assertNull(store.state.value.preview)
    }

    @Test
    fun failedReadKeepsNullPreview() = runTest {
        val network =
            ScriptedAgentControlsNetworkClient().apply {
                respond("GET", "me/sessions/disconnect-preview?action=password_change", 500, """{"error":"boom"}""")
            }
        val store = SharedPasswordDisconnectPreviewStore(network, this)
        store.sync("password_change:0")
        runCurrent()
        assertNull(store.state.value.preview)
        assertEquals(1, network.requests.count { it.path == "me/sessions/disconnect-preview?action=password_change" })
    }
}
