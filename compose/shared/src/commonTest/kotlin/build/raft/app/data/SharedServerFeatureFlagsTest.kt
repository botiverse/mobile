package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedServerFeatureFlagsTest {

    private val server = ServerId("s1")

    private class FakeApi(private val response: HomeNetworkResponse) : SlockFeatureFlagsApi {
        var lastKeys: List<String>? = null
        override suspend fun evaluate(serverId: ServerId, keys: List<String>): HomeNetworkResponse {
            lastKeys = keys
            return response
        }
    }

    private fun ok(body: String) = HomeNetworkResponse(code = 200, bodyText = body)

    @Test
    fun prefetchResolvesEnabledFlagAndDefaultsUnknownOff() = runTest {
        val api = FakeApi(ok("""{"evaluations":[{"key":"sync_core_messages_v0","enabled":true}]}"""))
        val flags = SharedServerFeatureFlags(api)
        val snapshot = flags.prefetch(server)
        assertTrue(snapshot.resolved)
        assertTrue(flags.isEnabled(server, SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY))
        assertFalse(flags.isEnabled(server, READ_RECEIPTS_FEATURE_FLAG_KEY)) // absent from evaluations -> off
        assertEquals(REGISTERED_SERVER_FEATURE_FLAG_KEYS, api.lastKeys) // batch: all registered keys
    }

    @Test
    fun explicitDisabledFlagStaysOff() = runTest {
        val api = FakeApi(ok("""{"evaluations":[{"key":"sync_core_messages_v0","enabled":false}]}"""))
        val flags = SharedServerFeatureFlags(api)
        flags.prefetch(server)
        assertFalse(flags.isEnabled(server, SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY))
    }

    @Test
    fun unresolvedSnapshotDefaultsAllOff() = runTest {
        val flags = SharedServerFeatureFlags(FakeApi(ok("{}")))
        // no prefetch yet
        assertFalse(flags.snapshotFor(server).resolved)
        assertFalse(flags.isEnabled(server, SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY))
        assertFalse(flags.isEnabled(null, SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY))
    }

    @Test
    fun failedFetchLeavesFlagsOffNeverThrows() = runTest {
        val flags = SharedServerFeatureFlags(FakeApi(HomeNetworkResponse(code = 500, bodyText = null)))
        val snapshot = flags.prefetch(server)
        assertFalse(snapshot.resolved) // failed -> stays Unresolved, flags default off (feature stays dark)
        assertFalse(flags.isEnabled(server, SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY))
    }

    @Test
    fun malformedBodyDoesNotThrow() = runTest {
        val flags = SharedServerFeatureFlags(FakeApi(ok("not-json")))
        assertFalse(flags.prefetch(server).resolved)
    }
}
