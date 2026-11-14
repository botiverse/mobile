package build.raft.app.data.realtime

import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.REALTIME_KMP_CLIENT_V1_FEATURE_FLAG_KEY
import build.raft.app.data.REGISTERED_SERVER_FEATURE_FLAG_KEYS
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The common Socket.IO client switch: off by default, server value or developer override, host-gated. */
class RaftRealtimeKmpClientFlagTest {
    private val store = InMemoryRaftKeyValueStore()

    @BeforeTest @AfterTest fun reset() = RaftRealtimeKmpClientFlag.resetForTests()

    @Test fun `the key is evaluated with the other mobile flags`() {
        assertEquals("realtime_kmp_client_v1", REALTIME_KMP_CLIENT_V1_FEATURE_FLAG_KEY)
        assertTrue(REALTIME_KMP_CLIENT_V1_FEATURE_FLAG_KEY in REGISTERED_SERVER_FEATURE_FLAG_KEYS)
    }

    @Test fun `off without a host adapter whatever is stored`() {
        RaftRealtimeKmpClientFlag.recordServerEvaluation(store, REALTIME_KMP_CLIENT_V1_FEATURE_FLAG_KEY, true)
        RaftRealtimeKmpClientFlag.setLocalOverride(store, true)
        assertFalse(RaftRealtimeKmpClientFlag.enabled(store))
    }

    @Test fun `server value applies until the developer switch is set and the switch wins both ways`() {
        RaftRealtimeKmpClientFlag.hostSupported = true
        assertFalse(RaftRealtimeKmpClientFlag.enabled(store), "default off")
        RaftRealtimeKmpClientFlag.recordServerEvaluation(store, "drafts_section_v0", true)
        assertFalse(RaftRealtimeKmpClientFlag.enabled(store), "another key is ignored")
        RaftRealtimeKmpClientFlag.recordServerEvaluation(store, REALTIME_KMP_CLIENT_V1_FEATURE_FLAG_KEY, true)
        assertTrue(RaftRealtimeKmpClientFlag.enabled(store))
        RaftRealtimeKmpClientFlag.setLocalOverride(store, false)
        assertFalse(RaftRealtimeKmpClientFlag.enabled(store), "local off rolls back even with the server on")
        RaftRealtimeKmpClientFlag.recordServerEvaluation(store, REALTIME_KMP_CLIENT_V1_FEATURE_FLAG_KEY, false)
        RaftRealtimeKmpClientFlag.setLocalOverride(store, true)
        assertTrue(RaftRealtimeKmpClientFlag.enabled(store), "local on wins over a server off")
    }
}
